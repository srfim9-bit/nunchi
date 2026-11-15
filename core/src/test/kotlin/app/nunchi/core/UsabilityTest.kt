package app.nunchi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 처음 쓰는 사람이 아무 순서로나 눌러 봐도 어색하지 않은가 — '보편적 사용자' 점검(0.5.4)에서 찾아 고친 것들.
 * 화면에 적힌 말과 실제 동작이 어긋나지 않는지, 대답할 사람이 없을 때 묻지 않는지, 바꾼 것을 돌려놓는지를 본다.
 */
class UsabilityTest {

    private fun says(cmds: List<CareCommand>) = cmds.filterIsInstance<CareCommand.Say>().map { it.text }

    private fun CareEngine.messages() = log.events.map { it.message }

    /** 2인·내기순환·주행 중 — CO2가 차오르는 차. */
    private fun stuffyCar(): CareEngine {
        val e = CareEngine(startMs = 0L)
        SeatId.entries.take(2).forEach { e.onSignal(VehicleSignal.SeatOccupancy(it, true), 0) }
        e.onSignal(VehicleSignal.Gear(parked = false), 0)
        e.onSignal(VehicleSignal.Recirculation(true), 0)
        return e
    }

    private fun untilProposal(e: CareEngine): Long {
        var t = 0L
        while (e.proposal == null) {
            t += 1_000
            e.tick(t)
            check(t < 30 * 60_000L) { "제안이 나오지 않음" }
        }
        return t
    }

    @Test
    fun `R0 — 다 내린 빈 차에는 1cm 습관을 여쭙지 않고, 다음에 타셨을 때 여쭙는다`() {
        val e = CareEngine(startMs = 0L)
        // 내림: 운전석 문 열림 → 자리 빔 → 문 닫힘
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), 1_000)
        e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, false), 2_000)
        val leaving = e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), 3_000)
        assertTrue("빈 차에 대고 말하지 않는다", says(leaving).isEmpty())
        assertNull(e.proposal)

        // 다시 탐: 대답할 사람이 있으니 이때 여쭙는다
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), 60_000)
        e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, true), 61_000)
        val ask = e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), 62_000)
        assertEquals(listOf("다음부터 손님 타실 때 창을 1cm 내려 둘까요? 문이 부드럽게 닫혀요."), says(ask))
    }

    @Test
    fun `R0 — 더운 차라도 다 내린 뒤엔 열기 빼기를 권하지 않는다`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.CabinTemperature(33f), 0)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), 1_000)
        e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, false), 2_000)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), 3_000)
        assertNull(e.proposal)
    }

    @Test
    fun `R0 — 1cm 습관은 하루 한 번만 여쭙고, 이미 여쭌 뒤엔 '여쭤볼게요'라고 적지 않는다`() {
        val e = CareEngine(startMs = 0L)
        assertEquals("대기 중 — 첫 승차가 끝나면 1cm 습관을 여쭤봐요", e.boarding.status)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000)
        assertEquals("승차 감지 — 다 타면 1cm 습관을 여쭤볼게요", e.boarding.status)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, false), 3_000)
        e.reject(4_000)
        assertEquals("대기 중 — 1cm 습관은 오늘 이미 여쭤봤어요 (내일 다시 여쭤볼게요)", e.boarding.status)

        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 60_000)
        assertEquals("승차 감지 — 1cm 습관은 오늘 이미 여쭤봐서 지켜보기만 해요", e.boarding.status)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, false), 62_000)
        assertNull("오늘은 다시 여쭙지 않는다", e.proposal)

        // 날이 바뀌면 다시 여쭙는다
        e.newDay()
        assertEquals("대기 중 — 첫 승차가 끝나면 1cm 습관을 여쭤봐요", e.boarding.status)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 120_000)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, false), 122_000)
        assertEquals(CareAction.WINDOW_CRACK, e.proposal?.offer?.action)
    }

    @Test
    fun `날이 바뀌면 '오늘은 먼저 권하지 않음'도 풀린다`() {
        val e = stuffyCar()
        var t = untilProposal(e)
        e.reject(t + 1_000)
        // 5분 쉬고 다시 여쭘 → 또 사양 → 오늘은 조용히
        while (e.proposal == null) {
            t += 1_000
            e.tick(t)
        }
        e.reject(t + 1_000)
        assertTrue(e.isQuiet(CareAction.FRESH_AIR))
        e.newDay()
        assertTrue(!e.isQuiet(CareAction.FRESH_AIR))
        assertTrue("기록은 남긴다", e.log.events.isNotEmpty())
    }

    @Test
    fun `R1 — 탁해졌는데 사양하셨으면 '곧 바꿉니다'가 아니라 언제 다시 여쭐지 알린다`() {
        val e = stuffyCar()
        val t = untilProposal(e)
        assertEquals("공기가 탁해요 — 바꿀지 여쭤보는 중", e.air.status(e.state, t, asking = true))
        e.reject(t + 1_000)
        assertEquals("공기가 탁해요 — 5분 뒤 다시 여쭤볼게요", e.air.status(e.state, t + 2_000))
        assertEquals("공기가 탁해요 — 1분 뒤 다시 여쭤볼게요", e.air.status(e.state, t + 4 * 60_000L + 30_000))
    }

    @Test
    fun `R6 — 좋다고 하신 뒤에는 쉬지 않고 다시 살피고, 사양하신 뒤에만 10분 쉬며 그 까닭을 한 번 남긴다`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.Gear(parked = false), 0)
        e.onSignal(VehicleSignal.SmokingGesture(SeatId.PASSENGER), 10_000)
        assertNotNull(e.proposal)
        e.approve(12_000)
        assertTrue(e.ventilation.isActive)
        e.stopVentilation(60_000)

        // 끄고 나서 또 피움 — 조금 전 '네'라고 하셨으니 다시 여쭙는다(예전엔 10분 동안 아무 반응이 없었다)
        e.onSignal(VehicleSignal.SmokingGesture(SeatId.PASSENGER), 90_000)
        assertNotNull("좋다고 하신 일은 쉬지 않는다", e.proposal)
        e.reject(92_000)

        // 사양하신 뒤: 재촉하지 않는다. 왜 조용한지는 한 번만 기록으로
        e.onSignal(VehicleSignal.SmokingGesture(SeatId.PASSENGER), 120_000)
        assertNull(e.proposal)
        e.onSignal(VehicleSignal.SmokingGesture(SeatId.PASSENGER), 150_000)
        assertEquals(1, e.messages().count { "다시 권하지 않아요" in it })
        assertTrue(e.smoke.cooldownLeftMs(150_000) > 0)

        // 10분 뒤엔 다시 살핀다
        val later = 90_000 + 10 * 60_000L + 1_000
        assertEquals(0L, e.smoke.cooldownLeftMs(later))
        e.onSignal(VehicleSignal.SmokingGesture(SeatId.PASSENGER), later)
        assertNotNull(e.proposal)
    }

    @Test
    fun `R6 — 알아서 하라고 허락하셨으면, 끈 뒤에 또 신호가 와도 바로 다시 켠다`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.Gear(parked = false), 0)
        e.onSignal(VehicleSignal.SmokingGesture(SeatId.DRIVER), 10_000)
        e.approve(12_000, always = true)
        e.stopVentilation(60_000)
        assertTrue(!e.ventilation.isActive)
        val again = e.onSignal(VehicleSignal.SmokingGesture(SeatId.DRIVER), 90_000)
        assertTrue("허락받은 일은 10분을 기다리지 않는다", e.ventilation.isActive)
        assertEquals(listOf("바람길 열게요."), says(again))
    }

    @Test
    fun `R2 — 여유가 돌아오면 쉬게 했던 에어컨과 바람을 원래대로 돌려놓는다`() {
        val e = CareEngine()
        e.onSignal(VehicleSignal.Comfort(Control.AC, 1), 0)
        e.onSignal(VehicleSignal.FanSpeed(5), 0)
        e.onSignal(VehicleSignal.RouteRemaining(100f), 0)
        e.onSignal(VehicleSignal.RangeRemaining(130f), 0)
        e.onSignal(VehicleSignal.RangeRemaining(110f), 1_000)
        assertEquals(listOf(CareCommand.SetAc(false), CareCommand.SetFanSpeed(2)), e.approve(2_000))

        // 충전하고 돌아옴
        val back = e.onSignal(VehicleSignal.RangeRemaining(300f), 600_000)
        assertEquals(listOf(CareCommand.SetAc(true), CareCommand.SetFanSpeed(5)), back)
        assertEquals("주행 여유 회복 → 에어컨·바람 원래대로", e.log.events.last().message)
    }

    @Test
    fun `R2 — 그 사이 직접 바꾸신 것은 돌려놓지 않는다`() {
        val e = CareEngine()
        e.onSignal(VehicleSignal.Comfort(Control.AC, 1), 0)
        e.onSignal(VehicleSignal.FanSpeed(5), 0)
        e.onSignal(VehicleSignal.RouteRemaining(100f), 0)
        e.onSignal(VehicleSignal.RangeRemaining(130f), 0)
        e.onSignal(VehicleSignal.RangeRemaining(110f), 1_000)
        e.approve(2_000)
        // 운전자가 에어컨을 다시 켜고 바람을 4단으로
        e.onSignal(VehicleSignal.Comfort(Control.AC, 1), 100_000)
        e.onSignal(VehicleSignal.FanSpeed(4), 101_000)
        val back = e.onSignal(VehicleSignal.RangeRemaining(300f), 600_000)
        assertTrue("직접 바꾸신 값은 존중한다", back.isEmpty())
        assertEquals("주행 여유 회복", e.log.events.last().message)
    }

    @Test
    fun `R8 — '타기' 없이 직접 조작부터 해도(시뮬레이터) 지금 타고 계신 것으로 보고 배운다`() {
        val e = CareEngine(startMs = 0L, hourOf = { 8 }, isWeekend = { false })
        e.onSignal(VehicleSignal.OutsideTemperature(-2f), 0)
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), 0)
        e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 0), 0)

        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), 1_000, human = true)
        e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), 3_000, human = true)
        assertEquals("방금 바꾸신 것: 설정온도 27℃ · 핸들 열선 켜기", e.habits.view(4_000).recording)
        // 묶음이 끝나면(20초 뒤, 탈 때 3분도 지난 뒤) 기억한다
        e.tick(4 * 60_000L)
        assertTrue(e.messages().any { it.startsWith("조작 묶음 기억: 설정온도 27℃ · 핸들 열선 켜기") })
    }

    @Test
    fun `R8 — 누가 바꿨는지 모르는 신호는 승차 기록이 없으면 여전히 배우지 않는다`() {
        val e = CareEngine(startMs = 0L, hourOf = { 8 }, isWeekend = { false })
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), 0)
        // 내린 뒤 시동이 꺼지며 바뀌는 값일 수 있다
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), 1_000)
        assertNull(e.habits.view(2_000).recording)
    }

    @Test
    fun `R8 — 여쭙는 중에 그 습관을 지우시면 여쭙던 것도 거둔다`() {
        var hour = 8
        val day = 24 * 3_600_000L
        val e = CareEngine(startMs = 0L, hourOf = { hour }, isWeekend = { false })
        e.setTrusted(CareAction.WINDOW_CRACK, true) // R0의 1cm 여쭘이 끼어들지 않게
        e.onSignal(VehicleSignal.OutsideTemperature(-2f), 0)
        fun reset(t: Long) {
            e.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), t)
            e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 0), t)
            e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 0), t)
        }
        fun board(t: Long) {
            e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), t)
            e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, true), t + 300)
            e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), t + 600)
            e.tick(t + 2_000) // 1.5초 뒤 R0가 창을 올린다
            e.tick(t + 5_000)
        }
        fun alight(t: Long) {
            e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), t)
            e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, false), t + 300)
            e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), t + 600)
            e.tick(t + 3_000)
            reset(t + 4_000)
        }
        reset(0)
        repeat(3) { d ->
            val t = d * day
            board(t + 1_000)
            e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), t + 10_000)
            e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), t + 12_000)
            e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 2), t + 14_000)
            alight(t + 300_000)
        }
        board(3 * day + 1_000)
        assertEquals(CareAction.HABIT, e.proposal?.offer?.action)

        val id = e.habits.view(3 * day + 6_000).habits.single().id
        e.habits.forgetHabit(id)
        e.tick(3 * day + 7_000)
        assertNull("지운 습관을 계속 여쭙지 않는다", e.proposal)
        assertTrue(e.approve(3 * day + 8_000).isEmpty())
        hour = 8
    }

    @Test
    fun `R8 — '타기'는 새 승차다, 문만 여닫아 본 뒤에 눌러도 방금 고른 시각과 날씨로 배운다`() {
        var hour = 21
        val e = CareEngine(startMs = 0L, hourOf = { hour }, isWeekend = { false })
        e.setTrusted(CareAction.WINDOW_CRACK, true)
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), 0)
        e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 0), 0)
        e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 0), 0)
        // 밤: 운전석 문을 열었다 닫아 보고(승차 기록이 시작됨) 시트 열선을 만져 봄
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), 1_000)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), 2_000)
        e.tick(4_000)
        e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 1), 5_000, human = true)

        // 시뮬레이터에서 아침 8시·영하 2℃로 바꾸고 '타기'(문 열림 → 닫힘)
        hour = 8
        e.onSignal(VehicleSignal.OutsideTemperature(-2f), 10_000)
        e.beginTrip(11_000)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), 11_100)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), 11_600)
        e.tick(14_000)
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), 20_000, human = true)
        e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), 22_000, human = true)
        e.tick(5 * 60_000L)

        val habits = e.habits.view(5 * 60_000L).habits.map { it.title to it.items }
        assertTrue("아침에 한 조작은 '추운 아침'으로 배운다: $habits",
            habits.any { it.first.startsWith("추운 아침") && "설정온도 27℃" in it.second && "운전석 열선" !in it.second })
        assertTrue("밤에 만져 본 것은 따로 남는다: $habits", habits.any { it.first.contains("밤") && "운전석 열선" in it.second })
    }

    @Test
    fun `R0 — 내려 둔 1cm를 사람이 직접 '1cm'로 고르면 그분의 창이다, 도로 올리지 않고 조작 묶음에도 넣는다`() {
        val e = CareEngine(startMs = 0L, hourOf = { 8 }, isWeekend = { false })
        e.setTrusted(CareAction.WINDOW_CRACK, true)
        e.onSignal(VehicleSignal.OutsideTemperature(-2f), 0)
        e.onSignal(VehicleSignal.DriverWindow(0), 0)
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), 0)
        e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 0), 0)

        // 탐: 문이 열리자 R0가 창을 1cm 내려 둠
        val crack = e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), 1_000)
        assertTrue(crack.contains(CareCommand.SetDriverWindow(1)))
        e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, true), 1_100)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), 1_200)
        // 창이 올라가기 전(1.5초 안)에 급한 손이 27℃ · 핸들 열선 · 창 1cm를 누름
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), 1_500, human = true)
        e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), 1_600, human = true)
        e.onSignal(VehicleSignal.DriverWindow(1), 1_700, human = true)

        val later = (2..8).flatMap { e.tick(it * 1_000L) }
        assertTrue("사람이 고른 창을 도로 닫지 않는다: $later", later.none { it is CareCommand.SetDriverWindow })
        assertEquals(1, e.state.driverWindow)
        assertEquals("방금 바꾸신 것: 설정온도 27℃ · 핸들 열선 켜기 · 운전석 창 살짝(1cm)", e.habits.view(9_000).recording)
        assertTrue(e.messages().any { it == "운전자가 창을 직접 맞춤 — 창은 그대로 둡니다" })
    }

    @Test
    fun `R0 — 내려 둔 창을 사람이 도로 닫으면 손을 떼고, 그건 습관으로 배우지 않는다`() {
        val e = CareEngine(startMs = 0L, hourOf = { 8 }, isWeekend = { false })
        e.setTrusted(CareAction.WINDOW_CRACK, true)
        e.onSignal(VehicleSignal.DriverWindow(0), 0)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), 1_000)
        e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, true), 1_100)
        e.onSignal(VehicleSignal.DriverWindow(0), 1_300, human = true)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), 1_500)
        val later = (2..8).flatMap { e.tick(it * 1_000L) }
        assertTrue(later.none { it is CareCommand.SetDriverWindow })
        assertEquals(0, e.state.driverWindow)
        assertNull("잠깐의 배려를 물린 것은 조작 습관이 아니다", e.habits.view(9_000).recording)
    }

    @Test
    fun `R0 — 내리자마자 다시 타도(창 올리기 전) 그새 닫힌 창을 다시 1cm 내려 둔다`() {
        val e = CareEngine(startMs = 0L)
        e.setTrusted(CareAction.WINDOW_CRACK, true)
        e.onSignal(VehicleSignal.DriverWindow(0), 0)
        // 내림: 문 열림(창 1cm) → 닫힘 → 시동이 꺼지며 창이 닫힘(시뮬레이터의 '내리기')
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), 1_000)
        e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, false), 1_100)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), 1_200)
        e.onSignal(VehicleSignal.DriverWindow(0), 1_250)
        // 1.5초가 지나기 전에 다시 탐
        val again = e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), 2_000)
        assertTrue("다시 타는 문에도 창을 내려 둔다: $again", again.contains(CareCommand.SetDriverWindow(1)))
        e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, true), 2_100)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), 2_200)
        val restore = (3..6).flatMap { e.tick(it * 1_000L) }
        assertTrue("문이 닫히면 원위치: $restore", restore.contains(CareCommand.SetDriverWindow(0)))
    }

    @Test
    fun `R8 — 알아서 맞추는 습관이 있어도, 타자마자 손이 먼저 간 값은 덮어쓰지 않는다`() {
        val day = 24 * 3_600_000L
        val e = CareEngine(startMs = 0L, hourOf = { 8 }, isWeekend = { false })
        e.setTrusted(CareAction.WINDOW_CRACK, true)
        e.onSignal(VehicleSignal.OutsideTemperature(-2f), 0)
        fun reset(t: Long) {
            e.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), t)
            e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 0), t)
            e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 0), t)
        }
        fun doors(t: Long, seated: Boolean) {
            e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), t)
            e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, seated), t + 100)
            e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), t + 200)
        }
        reset(0)
        repeat(3) { d ->
            val t = d * day
            doors(t + 1_000, true); e.tick(t + 3_000); e.tick(t + 6_000)
            e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), t + 10_000)
            e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), t + 12_000)
            e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 2), t + 14_000)
            if (e.proposal != null) e.approve(t + 15_000, always = true)
            doors(t + 300_000, false); e.tick(t + 303_000); reset(t + 304_000)
        }
        // 넷째 날: 여쭙고 '알아서'로 허락
        var t = 3 * day
        doors(t + 1_000, true); e.tick(t + 3_000); e.tick(t + 6_000)
        assertEquals(CareAction.HABIT, e.proposal?.offer?.action)
        e.approve(t + 7_000, always = true)
        doors(t + 300_000, false); e.tick(t + 303_000); reset(t + 304_000)
        // 다섯째 날: 타자마자 25℃를 먼저 누름 → 온도는 그대로 두고 나머지만 맞춘다
        t = 4 * day
        doors(t + 1_000, true)
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 250), t + 1_400, human = true)
        val cmds = (2..8).flatMap { e.tick(t + it * 1_000L) }
        assertEquals(250, e.state.read(Control.TEMP))
        assertTrue("나머지는 맞춘다: $cmds", e.state.read(Control.WHEEL_HEAT) == 1 && e.state.read(Control.SEAT_HEAT) == 2)
    }
}
