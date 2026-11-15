package app.nunchi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기본 설정(아무것도 미리 허락받지 않음): 모든 동작은 먼저 여쭙고, 좋다고 하면 한다. */
class ProposalTest {

    private fun says(cmds: List<CareCommand>) = cmds.filterIsInstance<CareCommand.Say>().map { it.text }

    /** 2인·내기순환·주행 중. */
    private fun stuffyCar(): CareEngine {
        val e = CareEngine(startMs = 0L)
        SeatId.entries.take(2).forEach { e.onSignal(VehicleSignal.SeatOccupancy(it, true), 0) }
        e.onSignal(VehicleSignal.Gear(parked = false), 0)
        e.onSignal(VehicleSignal.Recirculation(true), 0)
        return e
    }

    /** 처음 제안이 나올 때까지 1초씩 진행. 그때의 시각과 명령을 돌려준다. */
    private fun untilProposal(e: CareEngine, from: Long, to: Long): Pair<Long, List<CareCommand>> {
        var t = from
        while (t <= to) {
            val out = e.tick(t)
            if (e.proposal != null) return t to out
            t += 1_000
        }
        error("제안이 나오지 않음")
    }

    @Test
    fun `R1 — 공기를 바꾸기 전에 먼저 여쭙고, 좋다고 하면 바꾼다`() {
        val e = stuffyCar()
        val (t, out) = untilProposal(e, 0, 12 * 60_000L)
        assertEquals(listOf("공기가 탁해지고 있어요. 한 번 바꿀까요?"), says(out))
        assertFalse("묻기만 하고 아직 바꾸지 않는다", out.any { it is CareCommand.SetRecirculation })

        val yes = e.approve(t + 3_000)
        assertEquals(listOf(CareCommand.SetRecirculation(false)), yes)
        assertEquals(CareKind.FRESH_AIR, e.log.events.last().kind)
        assertNull(e.proposal)
        val back = (t + 4_000..t + 95_000 step 1_000).flatMap { e.tick(it) }
        assertTrue("90초 뒤 내기 복귀", CareCommand.SetRecirculation(true) in back)
    }

    @Test
    fun `답이 없으면 1분 뒤 조용히 거두고, 두 번 연달아 사양하면 그날은 먼저 권하지 않는다`() {
        val e = stuffyCar()
        val (t1, _) = untilProposal(e, 0, 12 * 60_000L)
        val quiet = (t1 + 1_000..t1 + 60_000 step 1_000).flatMap { e.tick(it) }
        assertNull("1분 뒤 거둠", e.proposal)
        assertTrue("거둘 때 말하지 않는다", quiet.none { it is CareCommand.Say })

        val (t2, _) = untilProposal(e, t1 + 61_000, t1 + 10 * 60_000L)
        e.reject(t2 + 1_000)
        val (t3, _) = untilProposal(e, t2 + 2_000, t2 + 10 * 60_000L)
        e.reject(t3 + 1_000)
        assertTrue(e.isQuiet(CareAction.FRESH_AIR))
        (t3 + 2_000..t3 + 20 * 60_000L step 1_000).forEach { e.tick(it) }
        assertNull("오늘은 더 권하지 않는다", e.proposal)
    }

    @Test
    fun `"다음부턴 알아서"를 허락하면 그다음부턴 짧게 알리고 바로 한다`() {
        val e = stuffyCar()
        val (t, _) = untilProposal(e, 0, 12 * 60_000L)
        e.approve(t + 1_000, always = true)
        assertTrue(e.isTrusted(CareAction.FRESH_AIR))
        var next: List<CareCommand> = emptyList()
        var time = t + 2_000
        while (time < t + 15 * 60_000L && next.none { it == CareCommand.SetRecirculation(false) }) {
            next = e.tick(time)
            time += 1_000
        }
        assertEquals(listOf("공기 한 번 바꿀게요."), says(next))
        assertNull("묻지 않았다", e.proposal)
    }

    @Test
    fun `상황이 바뀌면(운전자가 직접 외기로) 제안을 거둔다`() {
        val e = stuffyCar()
        val (t, _) = untilProposal(e, 0, 12 * 60_000L)
        e.onSignal(VehicleSignal.Recirculation(false), t + 2_000)
        assertNull(e.proposal)
    }

    @Test
    fun `R0 — 첫 승차는 지켜보고, 다 타면 1cm 습관을 여쭙는다, 허락하면 다음부터 말없이`() {
        val e = CareEngine(startMs = 0L)
        assertTrue("물을 틈이 없는 순간엔 아무것도 안 한다", e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000).isEmpty())
        val ask = e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, false), 3_000)
        assertEquals(listOf("다음부터 손님 타실 때 창을 1cm 내려 둘까요? 문이 부드럽게 닫혀요."), says(ask))
        assertTrue(e.approve(4_000).isEmpty())
        assertTrue(e.isTrusted(CareAction.WINDOW_CRACK))

        assertEquals(listOf(CareCommand.SetDriverWindow(1)), e.onSignal(VehicleSignal.Door(DoorId.REAR_LEFT, true), 60_000))
    }

    @Test
    fun `R0 — 더운 날 열기 빼기도 먼저 여쭙는다`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.CabinTemperature(33f), 0)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000)
        val ask = e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, false), 3_000)
        assertEquals(listOf("차 안이 33℃예요. 30초만 바람을 세게 해서 열기를 뺄까요?"), says(ask))
        assertEquals(listOf(CareCommand.SetRecirculation(false), CareCommand.SetFanSpeed(8)), e.approve(5_000))
        assertEquals(CareKind.HEAT_PURGE, e.log.events.last().kind)
    }

    @Test
    fun `R2 — 에어컨을 끄기 전에 여쭙는다, 속도는 대신 줄이지 않는다`() {
        val e = CareEngine()
        e.onSignal(VehicleSignal.RouteRemaining(100f), 0)
        e.onSignal(VehicleSignal.RangeRemaining(130f), 0)
        val ask = e.onSignal(VehicleSignal.RangeRemaining(110f), 1_000)
        assertEquals(listOf("이대로 가면 도착 후 여유가 10%예요. 에어컨을 잠시 쉬고 바람을 줄일까요?"), says(ask))
        assertFalse(CareCommand.SetAc(false) in ask)
        assertTrue(CareCommand.SetAc(false) in e.approve(2_000))
    }

    @Test
    fun `R6 약한 신호 — 달리다 창을 살짝 열면 말없이 화면에만 제안, 출구는 그 창`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.Gear(parked = false), 0)
        val out = e.onSignal(VehicleSignal.DriverWindow(1), 10_000)
        assertTrue("말하지 않는다", says(out).isEmpty())
        val p = assertNotNullProposal(e)
        assertFalse(p.offer.voice)
        assertEquals(CareAction.VENTILATION, p.offer.action)

        val yes = e.approve(12_000)
        assertTrue(e.ventilation.isActive)
        assertEquals(Pane.DRIVER, e.ventilation.outlet)
        assertTrue(CareCommand.SetFanDirection(FanDirection.FLOOR) in yes)
    }

    @Test
    fun `R6 약한 신호 — 창을 활짝 열거나 정차 중이면 그냥 바람 쐬기로 본다`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.DriverWindow(1), 1_000)
        assertNull("정차 중엔 권하지 않는다", e.proposal)
        e.onSignal(VehicleSignal.DriverWindow(0), 2_000)

        e.onSignal(VehicleSignal.Gear(parked = false), 3_000)
        e.onSignal(VehicleSignal.DriverWindow(1), 10_000)
        assertNotNull(e.proposal)
        e.onSignal(VehicleSignal.DriverWindow(8), 11_000)
        assertNull("활짝 열면 거둔다", e.proposal)
    }

    @Test
    fun `R6 강한 신호 — 동승석 흡연 손동작이면 '담배' 대신 '바람길'로 여쭙고 출구는 동승석 창`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.DriverWindow(3), 0)
        val ask = e.onSignal(VehicleSignal.SmokingGesture(SeatId.PASSENGER), 5_000)
        assertEquals(listOf("바람길 열어 드릴까요?"), says(ask))
        val yes = e.approve(7_000)
        assertEquals(Pane.PASSENGER, e.ventilation.outlet)
        assertTrue(CareCommand.SetOtherWindow(WindowId.PASSENGER, 1) in yes)
        assertTrue("다른 창(운전석)은 닫아 흐름이 갈라지지 않게", CareCommand.SetDriverWindow(0) in yes)
        assertTrue("끝나면 운전석 창은 원래(3)대로", CareCommand.SetDriverWindow(3) in e.stopVentilation(60_000))
    }

    @Test
    fun `R6 강한 신호 — 실내 미세먼지가 확 뛰면 여쭙고, 방금 살짝 연 창을 출구로 고른다`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.CabinPm25(12), 0)
        e.onSignal(VehicleSignal.OtherWindow(WindowId.PASSENGER, 1), 20_000)
        val ask = e.onSignal(VehicleSignal.CabinPm25(180), 40_000)
        assertEquals(listOf("공기가 탁해졌네요. 바람길 열어 드릴까요?"), says(ask))
        e.approve(41_000)
        assertEquals(Pane.PASSENGER, e.ventilation.outlet)
    }

    @Test
    fun `R6 — 허락받았으면 강한 신호엔 바로 켜고, 약한 신호는 그래도 여쭙는다`() {
        val e = CareEngine(CareConfig(trusted = setOf(CareAction.VENTILATION)), startMs = 0L)
        val now = e.onSignal(VehicleSignal.SmokingGesture(SeatId.DRIVER), 1_000)
        assertEquals(listOf("바람길 열게요."), says(now))
        assertTrue(e.ventilation.isActive)
        e.stopVentilation(30_000)

        e.onSignal(VehicleSignal.Gear(parked = false), 40_000)
        e.onSignal(VehicleSignal.DriverWindow(1), 50_000)
        assertFalse(e.ventilation.isActive)
        assertNotNull("약한 신호는 허락받았어도 묻는다", e.proposal)
    }

    @Test
    fun `제안은 한 번에 하나 — 대답을 기다리는 동안 다른 제안을 쌓지 않는다`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.SmokingGesture(SeatId.DRIVER), 1_000)
        val first = e.proposal
        e.onSignal(VehicleSignal.RouteRemaining(100f), 2_000)
        val second = e.onSignal(VehicleSignal.RangeRemaining(110f), 3_000)
        assertTrue(says(second).isEmpty())
        assertEquals(first, e.proposal)
    }

    @Test
    fun `NUNCHI가 창을 움직인 직후의 창 신호는 사람 손으로 보지 않는다`() {
        val e = CareEngine(CareConfig(trusted = setOf(CareAction.WINDOW_CRACK)), startMs = 0L)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000) // 창 1cm
        e.onSignal(VehicleSignal.Gear(parked = false), 2_000) // 출발 → 원위치
        e.onSignal(VehicleSignal.DriverWindow(1), 2_500) // 아직 반영 안 된 옛 값
        assertNull(e.proposal)
    }

    @Test
    fun `여럿 타면 외기를 여쭙되, 터널은 차가 맡으니 따로 약속하지 않는다`() {
        val e = CareEngine(startMs = 0L)
        SeatId.entries.take(3).forEach { e.onSignal(VehicleSignal.SeatOccupancy(it, true), 0) }
        e.onSignal(VehicleSignal.Gear(parked = false), 0)
        e.onSignal(VehicleSignal.Recirculation(true), 0)
        val ask = (0L..130_000L step 1_000).flatMap { e.tick(it) }.filterIsInstance<CareCommand.Say>().map { it.text }
        assertEquals(listOf("3명이 타면 공기가 금방 탁해져요. 외기로 둘까요?"), ask)
        assertEquals(listOf(CareCommand.SetRecirculation(false)), e.approve(131_000))
    }

    private fun assertNotNullProposal(e: CareEngine): Proposal {
        val p = e.proposal
        assertNotNull(p)
        return p!!
    }
}
