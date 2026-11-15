package app.nunchi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardingCareTest {

    /** 동작 자체를 검증하므로 모든 일을 '미리 허락받은' 상태로 둔다(제안 흐름은 ProposalTest). */
    private fun engine(config: CareConfig = CareConfig()) =
        CareEngine(config.copy(trusted = CareAction.entries.toSet()), startMs = 0L)

    @Test
    fun `손님이 타면 창을 1cm 내리고 문이 다 닫히면 원위치`() {
        val e = engine()
        val open = e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, open = true), 1_000)
        assertEquals(listOf(CareCommand.SetDriverWindow(1)), open)
        assertEquals(CareKind.WINDOW_CRACK, e.log.events.last().kind)

        val close = e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, open = false), 4_000)
        assertTrue("문 닫는 순간에는 아직 올리지 않는다", close.isEmpty())
        assertTrue(e.tick(5_000).isEmpty())
        assertEquals(listOf(CareCommand.SetDriverWindow(0)), e.tick(5_600))
        assertEquals(0, e.state.driverWindow)
    }

    @Test
    fun `여러 명이 타면 마지막 문이 닫힐 때까지 기다린다`() {
        val e = engine()
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_RIGHT, true), 1_200)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, false), 3_000)
        assertTrue("동승석 문이 아직 열려 있음", e.tick(6_000).isEmpty())
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_RIGHT, false), 7_000)
        assertEquals(listOf(CareCommand.SetDriverWindow(0)), e.tick(8_600))
    }

    @Test
    fun `닫은 직후 다시 문이 열리면 창 올리기를 보류한다`() {
        val e = engine()
        e.onSignal(VehicleSignal.Door(DoorId.REAR_LEFT, true), 1_000)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_LEFT, false), 2_000)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_LEFT, true), 2_500)
        assertTrue(e.tick(4_000).isEmpty())
        e.onSignal(VehicleSignal.Door(DoorId.REAR_LEFT, false), 6_000)
        assertEquals(listOf(CareCommand.SetDriverWindow(0)), e.tick(7_600))
    }

    @Test
    fun `비가 오면 창은 그대로 둔다`() {
        val e = engine()
        e.onSignal(VehicleSignal.Wiper(on = true), 0)
        val out = e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000)
        assertTrue(out.isEmpty())
        assertEquals(CareKind.INFO, e.log.events.last().kind)
        assertEquals(0, e.report().total)
    }

    @Test
    fun `프레임리스 도어 차종은 창을 건드리지 않는다`() {
        val e = engine(CareConfig(frameless = true))
        assertTrue(e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000).isEmpty())
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, false), 2_000)
        assertTrue(e.tick(10_000).isEmpty())
    }

    @Test
    fun `주행 중 문 신호에는 반응하지 않는다`() {
        val e = engine()
        e.onSignal(VehicleSignal.Gear(parked = false), 0)
        assertTrue(e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000).isEmpty())
    }

    @Test
    fun `더운 날에는 문이 닫힌 뒤 30초 열기 배출 후 원위치`() {
        val e = engine()
        e.onSignal(VehicleSignal.CabinTemperature(33f), 0)
        e.onSignal(VehicleSignal.FanSpeed(2), 0)
        e.onSignal(VehicleSignal.Recirculation(true), 0)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000)

        val purge = e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, false), 3_000)
        assertEquals(listOf(CareCommand.SetRecirculation(false), CareCommand.SetFanSpeed(8)), purge)
        assertEquals(CareKind.HEAT_PURGE, e.log.events.last().kind)

        assertTrue(e.tick(20_000).isEmpty())
        val restore = e.tick(33_000)
        assertEquals(
            listOf(CareCommand.SetDriverWindow(0), CareCommand.SetFanSpeed(2), CareCommand.SetRecirculation(true)),
            restore,
        )
    }

    @Test
    fun `열기 배출 중 출발하면 창만 먼저 닫고 환기는 마저 한다`() {
        val e = engine()
        e.onSignal(VehicleSignal.CabinTemperature(33f), 0)
        e.onSignal(VehicleSignal.FanSpeed(2), 0)
        e.onSignal(VehicleSignal.Recirculation(true), 0)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, false), 3_000)

        assertEquals(listOf(CareCommand.SetDriverWindow(0)), e.onSignal(VehicleSignal.Gear(parked = false), 10_000))
        assertTrue(e.tick(20_000).isEmpty())
        assertEquals(listOf(CareCommand.SetFanSpeed(2), CareCommand.SetRecirculation(true)), e.tick(33_000))
    }

    @Test
    fun `운전자가 창을 직접 움직이면 손을 뗀다`() {
        val e = engine()
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000)
        // 2초 안의 메아리는 무시
        e.onSignal(VehicleSignal.DriverWindow(0), 1_500)
        e.onSignal(VehicleSignal.DriverWindow(5), 5_000)
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, false), 6_000)
        assertTrue(e.tick(9_000).isEmpty())
    }

    @Test
    fun `창을 올리기 전에 출발하면 바로 원위치`() {
        val e = engine()
        e.onSignal(VehicleSignal.Door(DoorId.REAR_RIGHT, true), 1_000)
        val out = e.onSignal(VehicleSignal.Gear(parked = false), 2_000)
        assertEquals(listOf(CareCommand.SetDriverWindow(0)), out)
    }
}
