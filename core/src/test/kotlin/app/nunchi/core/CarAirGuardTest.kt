package app.nunchi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 차가 스스로 하는 터널 차단(외부 공기 유입 방지)을 사람 손으로 오해하지 않는다. */
class CarAirGuardTest {

    private val all = CareConfig(trusted = CareAction.entries.toSet())

    /** 내기순환·바람 3단·얼굴 바람·동승석 창 4 상태로 출발해 흐름 환기를 켠 차. */
    private fun ventingWhileDriving(): CareEngine {
        val e = CareEngine(all, startMs = 0L)
        e.onSignal(VehicleSignal.Recirculation(true), 0)
        e.onSignal(VehicleSignal.FanSpeed(3), 0)
        e.onSignal(VehicleSignal.FanDirectionChanged(FanDirection.FACE), 0)
        e.onSignal(VehicleSignal.OtherWindow(WindowId.PASSENGER, 4), 0)
        e.onSignal(VehicleSignal.Gear(parked = false), 1_000)
        e.startVentilation(10_000)
        return e
    }

    /** 차가 터널 7초 전에 하는 일: 열린 창을 닫고 내기로. */
    private fun carClosesForTunnel(e: CareEngine, atMs: Long) {
        e.onSignal(VehicleSignal.DriverWindow(0), atMs)
        e.onSignal(VehicleSignal.Recirculation(true), atMs + 500)
    }

    @Test
    fun `흡연 환기 중엔 차의 터널 차단을 한 번 덮어쓴다 — 실내 담배 연기가 더 해롭다`() {
        val e = ventingWhileDriving()
        e.onSignal(VehicleSignal.DriverWindow(0), 60_000)
        val back = e.onSignal(VehicleSignal.Recirculation(true), 60_500)
        assertTrue("출구 창을 다시 연다", CareCommand.SetDriverWindow(1) in back)
        assertTrue("외기로 되돌린다", CareCommand.SetRecirculation(false) in back)
        assertTrue(e.ventilation.isActive)
        assertFalse(e.ventilation.pausedForCar)
        assertTrue(e.tick(66_000).isEmpty())
        assertTrue("그만으로 오해하지 않는다", e.ventilation.isActive)
    }

    @Test
    fun `차가 또 닫으면 다투지 않고 맡겼다가, 터널을 나와 창을 다시 열면 이어 간다`() {
        val e = ventingWhileDriving()
        carClosesForTunnel(e, 60_000) // 1번째: 덮어씀
        carClosesForTunnel(e, 70_000) // 2번째: 맡김
        assertTrue(e.ventilation.pausedForCar)
        assertTrue(e.tick(76_000).isEmpty())
        assertTrue(e.ventilation.isActive)

        val after = e.onSignal(VehicleSignal.DriverWindow(1), 120_000) // 터널 지나 차가 창을 원래(틈)대로
        assertFalse(e.carGuard.active)
        assertFalse(e.ventilation.pausedForCar)
        assertEquals("차가 내기를 유지하면 외기를 다시", listOf(CareCommand.SetRecirculation(false)), after)
    }

    @Test
    fun `맡겨 둔 채 터널 안에서 7분이 끝나면 터널 규칙대로 — 내기로, 창 다시 열기는 터널을 나온 뒤`() {
        val e = ventingWhileDriving()
        carClosesForTunnel(e, 60_000)
        carClosesForTunnel(e, 70_000)
        val end = e.tick(10_000 + 7 * 60_000L)
        assertFalse(e.ventilation.isActive)
        assertFalse("터널 안에서 창을 열지 않는다", end.any { it is CareCommand.SetOtherWindow })
        assertTrue(CareCommand.SetRecirculation(true) in end)
        assertTrue(CareCommand.SetFanDirection(FanDirection.FACE) in end)

        val later = e.onSignal(VehicleSignal.DriverWindow(1), 10_000 + 8 * 60_000L) // 차가 터널을 나와 창을 다시 엶
        assertTrue("차가 다시 연 출구 창은 원래(닫힘)로", CareCommand.SetDriverWindow(0) in later)
        assertTrue(CareCommand.SetOtherWindow(WindowId.PASSENGER, 4) in later)
    }

    @Test
    fun `터널 차단 중에도 흡연 신호가 오면 바람길을 여쭙고, 켜면 연기가 먼저다`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.DriverWindow(2), 0)
        e.onSignal(VehicleSignal.Gear(parked = false), 1_000)
        carClosesForTunnel(e, 10_000)
        assertTrue(e.carGuard.active)
        val ask = e.onSignal(VehicleSignal.SmokingGesture(SeatId.DRIVER), 20_000)
        assertEquals(listOf(CareCommand.Say("바람길 열어 드릴까요?")), ask)
        val yes = e.approve(22_000)
        assertTrue(e.ventilation.isActive)
        assertTrue(CareCommand.SetRecirculation(false) in yes)
        assertFalse("차의 차단은 끝난 것으로 친다", e.carGuard.active)
    }

    @Test
    fun `터널을 지나 차가 창을 다시 열어도 '살짝 엶'으로 착각해 제안하지 않는다`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.DriverWindow(2), 0) // 정차 중에 열어 둠
        e.onSignal(VehicleSignal.Gear(parked = false), 1_000)
        carClosesForTunnel(e, 60_000)
        assertTrue(e.carGuard.active)
        e.onSignal(VehicleSignal.DriverWindow(2), 120_000)
        assertNull(e.proposal)
    }

    @Test
    fun `내기가 이미 켜져 있어 차단을 알아보지 못해도, 같은 높이로 다시 열린 창은 새 신호가 아니다`() {
        val e = CareEngine(startMs = 0L)
        e.onSignal(VehicleSignal.Recirculation(true), 0)
        e.onSignal(VehicleSignal.DriverWindow(2), 0)
        e.onSignal(VehicleSignal.Gear(parked = false), 1_000)
        e.onSignal(VehicleSignal.DriverWindow(0), 60_000)
        e.onSignal(VehicleSignal.DriverWindow(2), 150_000)
        assertNull(e.proposal)
    }

    @Test
    fun `터널 차단 중엔 '공기 한 번 바꿀까요'를 묻지 않는다`() {
        val e = CareEngine(startMs = 0L)
        SeatId.entries.forEach { e.onSignal(VehicleSignal.SeatOccupancy(it, true), 0) }
        e.onSignal(VehicleSignal.DriverWindow(2), 0)
        e.onSignal(VehicleSignal.Gear(parked = false), 1_000)
        carClosesForTunnel(e, 10_000)
        assertTrue(e.carGuard.active)
        val says = (11_000L..9 * 60_000L step 1_000).flatMap { e.tick(it) }.filterIsInstance<CareCommand.Say>()
        assertTrue("4명·내기 8분이면 원래는 물었을 것: ${e.air.ppm}", e.air.ppm > 1_900)
        assertTrue(says.isEmpty())
        assertNull(e.proposal)
    }

    @Test
    fun `NUNCHI가 스스로 창을 닫고 내기로 돌린 건 차의 터널 차단으로 보지 않는다`() {
        val e = ventingWhileDriving()
        e.stopVentilation(60_000) // 원위치: 운전석 창 닫고 내기로
        e.onSignal(VehicleSignal.DriverWindow(0), 60_500)
        e.onSignal(VehicleSignal.Recirculation(true), 61_000)
        assertFalse(e.carGuard.active)
    }
}
