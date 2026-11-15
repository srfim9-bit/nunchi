package app.nunchi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VentilationCareTest {

    private val minutes7 = 7 * 60_000L

    /** 내기순환 · 바람 3단 · 얼굴 바람 · 동승석 창 4단 열림 상태의 차(fanMax 8, 창틈 1단). */
    private fun engine(config: CareConfig = CareConfig()): CareEngine {
        // 다른 루틴(R0·R1·R2)과 겹치는 장면을 보려고 모든 일을 '미리 허락받은' 상태로 둔다
        val e = CareEngine(config.copy(trusted = CareAction.entries.toSet()), startMs = 0L)
        e.onSignal(VehicleSignal.Recirculation(true), 0)
        e.onSignal(VehicleSignal.FanSpeed(3), 0)
        e.onSignal(VehicleSignal.FanDirectionChanged(FanDirection.FACE), 0)
        e.onSignal(VehicleSignal.OtherWindow(WindowId.PASSENGER, 4), 0)
        return e
    }

    private fun List<CareCommand>.withoutSay() = filterNot { it is CareCommand.Say }

    @Test
    fun `시작하면 외기·발밑 바람·운전석 창틈, 다른 창은 닫는다`() {
        val e = engine()
        val out = e.startVentilation(10_000)
        assertEquals(
            listOf(
                CareCommand.SetFanDirection(FanDirection.FLOOR),
                CareCommand.SetFanSpeed(7), // 정차 중: 최대(8)보다 두 단계 아래 + 한 단계
                CareCommand.SetRecirculation(false),
                CareCommand.SetDriverWindow(1),
                CareCommand.SetOtherWindow(WindowId.PASSENGER, 0),
            ),
            out.withoutSay(),
        )
        assertTrue("시작은 한마디로 알린다", out.last() is CareCommand.Say)
        assertTrue(e.ventilation.isActive)
        assertEquals(1, e.report().counts[CareKind.VENTILATION])
        assertTrue("두 번 눌러도 한 번만", e.startVentilation(11_000).isEmpty())
    }

    @Test
    fun `7분 뒤 바꾼 것만 조용히 원래대로 — 창 먼저`() {
        val e = engine()
        e.startVentilation(10_000)
        assertTrue(e.tick(10_000 + minutes7 - 1).isEmpty())
        val restore = e.tick(10_000 + minutes7)
        assertEquals(
            listOf(
                CareCommand.SetDriverWindow(0),
                CareCommand.SetOtherWindow(WindowId.PASSENGER, 4),
                CareCommand.SetFanDirection(FanDirection.FACE),
                CareCommand.SetFanSpeed(3),
                CareCommand.SetRecirculation(true),
            ),
            restore,
        )
        assertFalse("끝날 땐 말하지 않는다", restore.any { it is CareCommand.Say })
        assertFalse(e.ventilation.isActive)
    }

    @Test
    fun `주행 중엔 한 단계 낮게, 정차하면 한 단계 올린다`() {
        val e = engine()
        e.onSignal(VehicleSignal.Gear(parked = false), 5_000)
        assertTrue(e.startVentilation(10_000).contains(CareCommand.SetFanSpeed(6)))
        assertEquals(listOf(CareCommand.SetFanSpeed(7)), e.onSignal(VehicleSignal.Gear(parked = true), 60_000))
        assertEquals(listOf(CareCommand.SetFanSpeed(6)), e.onSignal(VehicleSignal.Gear(parked = false), 90_000))
    }

    @Test
    fun `메아리는 무시하고, 운전자가 바람 세기를 바꾸면 그 세기로 계속`() {
        val e = engine()
        e.startVentilation(10_000)
        assertTrue(e.onSignal(VehicleSignal.FanSpeed(3), 10_300).isEmpty()) // 보낸 직후 옛 값
        assertTrue(e.onSignal(VehicleSignal.FanSpeed(7), 10_600).isEmpty()) // 메아리
        e.onSignal(VehicleSignal.FanSpeed(4), 30_000) // 손으로 4단
        assertTrue(e.ventilation.isActive)
        val restore = e.stopVentilation(40_000)
        assertFalse("운전자가 정한 세기는 돌려놓지 않는다", restore.any { it is CareCommand.SetFanSpeed })
        assertTrue(restore.contains(CareCommand.SetRecirculation(true)))
    }

    @Test
    fun `운전자가 내기를 다시 켜면 3초 확인 뒤 '그만'으로 알아듣고 나머지만 원래대로`() {
        val e = engine()
        e.startVentilation(10_000)
        assertTrue(e.onSignal(VehicleSignal.Recirculation(true), 30_000).isEmpty())
        assertTrue("터널 차단인지 3초 지켜본다", e.ventilation.isActive)
        val out = e.tick(33_000)
        assertFalse(e.ventilation.isActive)
        assertFalse(out.any { it is CareCommand.SetRecirculation })
        assertTrue(out.contains(CareCommand.SetDriverWindow(0)))
        assertTrue(out.contains(CareCommand.SetFanDirection(FanDirection.FACE)))
    }

    @Test
    fun `운전자가 운전석 창을 닫으면 3초 확인 뒤 환기를 끝낸다`() {
        val e = engine()
        e.startVentilation(10_000)
        assertTrue(e.onSignal(VehicleSignal.DriverWindow(0), 30_000).isEmpty())
        val out = e.tick(33_000)
        assertFalse(e.ventilation.isActive)
        assertFalse(out.any { it is CareCommand.SetDriverWindow })
        assertTrue(out.contains(CareCommand.SetRecirculation(true)))
    }

    @Test
    fun `운전자가 창을 더 열면 그 높이 그대로 두고 환기는 계속`() {
        val e = engine()
        e.startVentilation(10_000)
        e.onSignal(VehicleSignal.DriverWindow(4), 30_000)
        assertTrue(e.ventilation.isActive)
        assertFalse(e.stopVentilation(40_000).any { it is CareCommand.SetDriverWindow })
    }

    @Test
    fun `비가 오기 시작하면 발밑+앞유리로, 끝나도 다른 창은 다시 열지 않는다`() {
        val e = engine()
        e.startVentilation(10_000)
        assertEquals(
            listOf(CareCommand.SetFanDirection(FanDirection.DEFROST_AND_FLOOR)),
            e.onSignal(VehicleSignal.Wiper(on = true), 30_000),
        )
        val restore = e.stopVentilation(40_000)
        assertFalse(restore.any { it is CareCommand.SetOtherWindow })
        assertTrue(restore.contains(CareCommand.SetFanDirection(FanDirection.FACE)))
    }

    @Test
    fun `비가 와도 흡연 환기는 계속 — 창틈을 가장 작게, 심한 폭우면 창은 닫고, 그치면 원래 틈`() {
        val cfg = CareConfig(ventilationWindowStep = 4, windowCrackStep = 1)
        val e = engine(cfg)
        e.onSignal(VehicleSignal.Wiper(on = true), 1_000)
        assertTrue("비: 가장 작게", CareCommand.SetDriverWindow(1) in e.startVentilation(10_000))

        val heavy = e.onSignal(VehicleSignal.HeavyRain(heavy = true), 30_000)
        assertTrue("폭우: 창은 닫는다", CareCommand.SetDriverWindow(0) in heavy)
        assertTrue("폭우로 닫은 건 '그만'이 아니다", e.tick(40_000).isEmpty() && e.ventilation.isActive)

        e.onSignal(VehicleSignal.HeavyRain(heavy = false), 60_000).let { assertTrue(CareCommand.SetDriverWindow(1) in it) }
        e.onSignal(VehicleSignal.Wiper(on = false), 90_000).let { assertTrue("비 그침: 원래 틈", CareCommand.SetDriverWindow(4) in it) }
    }

    @Test
    fun `심한 폭우 중에 켜면 창은 열지 않고 외기·발밑 바람만`() {
        val e = engine(CareConfig(ventilationWindowStep = 4))
        e.onSignal(VehicleSignal.Wiper(on = true), 1_000)
        e.onSignal(VehicleSignal.HeavyRain(heavy = true), 1_000)
        val out = e.startVentilation(10_000)
        assertFalse(out.any { it is CareCommand.SetDriverWindow })
        assertTrue(CareCommand.SetRecirculation(false) in out)
        assertTrue(CareCommand.SetFanDirection(FanDirection.DEFROST_AND_FLOOR) in out)
    }

    @Test
    fun `비 오는 중이거나 앞유리 바람을 쓰던 중이면 처음부터 발밑+앞유리`() {
        val rainy = engine().apply { onSignal(VehicleSignal.Wiper(on = true), 1_000) }
        assertTrue(rainy.startVentilation(10_000).contains(CareCommand.SetFanDirection(FanDirection.DEFROST_AND_FLOOR)))

        val foggy = engine().apply { onSignal(VehicleSignal.FanDirectionChanged(FanDirection.DEFROST), 1_000) }
        assertTrue(foggy.startVentilation(10_000).contains(CareCommand.SetFanDirection(FanDirection.DEFROST_AND_FLOOR)))
    }

    @Test
    fun `운전석 창이 이미 넉넉히 열려 있으면 건드리지 않는다`() {
        val e = engine().apply { onSignal(VehicleSignal.DriverWindow(5), 1_000) }
        assertFalse(e.startVentilation(10_000).any { it is CareCommand.SetDriverWindow })
        assertFalse(e.stopVentilation(20_000).any { it is CareCommand.SetDriverWindow })
    }

    @Test
    fun `R0가 창을 1cm 내려 둔 중에 켜도 끝나면 원래(닫힘)로 돌아온다`() {
        val e = engine()
        assertEquals(listOf(CareCommand.SetDriverWindow(1)), e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 5_000))
        e.startVentilation(6_000)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, false), 8_000)
        assertTrue("R6 중엔 R0가 창을 올리지 않는다", e.tick(12_000).none { it is CareCommand.SetDriverWindow })
        assertTrue(e.stopVentilation(20_000).contains(CareCommand.SetDriverWindow(0)))
    }

    @Test
    fun `R1 공기 교체 중에 켜면 R1은 손을 떼고, 끝나면 내기로 돌아간다`() {
        val e = engine()
        SeatId.entries.forEach { e.onSignal(VehicleSignal.SeatOccupancy(it, true), 0) }
        assertTrue("4명·내기순환 5분 → 공기 교체", e.tick(300_000).contains(CareCommand.SetRecirculation(false)))
        e.startVentilation(301_000)
        assertTrue("R1의 '내기 복귀'가 끼어들지 않는다", e.tick(400_000).none { it is CareCommand.SetRecirculation })
        assertTrue(e.stopVentilation(410_000).contains(CareCommand.SetRecirculation(true)))
    }

    @Test
    fun `R6 중엔 R2 에너지 코칭이 바람을 줄이지 않는다`() {
        val e = engine()
        e.startVentilation(10_000)
        e.onSignal(VehicleSignal.RouteRemaining(100f), 20_000)
        val out = e.onSignal(VehicleSignal.RangeRemaining(110f), 21_000)
        assertTrue(out.contains(CareCommand.SetAc(false)))
        assertFalse(out.any { it is CareCommand.SetFanSpeed })
    }
}
