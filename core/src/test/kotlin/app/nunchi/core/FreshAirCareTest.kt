package app.nunchi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 동작 자체를 검증하므로 모든 일을 '미리 허락받은' 상태로 둔다(제안 흐름은 ProposalTest). */
private val ALL = CareConfig(trusted = CareAction.entries.toSet())

class FreshAirCareTest {

    /** 2인 탑승·내기순환·주행 중 상태를 만든다. */
    private fun driving(occupants: Int = 2): CareEngine {
        val e = CareEngine(ALL, startMs = 0L)
        val seats = SeatId.entries.take(occupants)
        seats.forEach { e.onSignal(VehicleSignal.SeatOccupancy(it, true), 0) }
        e.onSignal(VehicleSignal.Gear(parked = false), 0)
        e.onSignal(VehicleSignal.Recirculation(true), 0)
        return e
    }

    private fun runUntil(e: CareEngine, fromMs: Long, toMs: Long, stepMs: Long = 1_000): List<Pair<Long, CareCommand>> {
        val out = mutableListOf<Pair<Long, CareCommand>>()
        var t = fromMs
        while (t <= toMs) {
            e.tick(t).forEach { out += t to it }
            t += stepMs
        }
        return out
    }

    @Test
    fun `2인 내기순환이면 약 8분에 외기로 바꾸고 90초 뒤 복귀`() {
        val e = driving(occupants = 2)
        val cmds = runUntil(e, 0, 12 * 60_000L)
        val off = cmds.first { it.second == CareCommand.SetRecirculation(false) }.first
        val on = cmds.first { it.second == CareCommand.SetRecirculation(true) }.first
        assertTrue("외기 전환 시점 ${off / 60_000.0}분", off in 7 * 60_000L..9 * 60_000L)
        assertEquals(90_000L, on - off)
        assertTrue(cmds.any { it.second is CareCommand.Say })
        assertTrue("2,000ppm을 넘지 않는다: ${e.air.peakPpm}", e.air.peakPpm < 2_000.0)
    }

    @Test
    fun `45분 동안 한 번도 2000ppm을 넘지 않는다`() {
        val e = driving(occupants = 2)
        runUntil(e, 0, 45 * 60_000L)
        assertTrue("peak ${e.air.peakPpm}", e.air.peakPpm < 2_000.0)
        assertTrue("bursts ${e.air.bursts}", e.air.bursts >= 2)
    }

    @Test
    fun `운전자가 내기를 다시 켜면 물러나고 쿨다운 뒤 재판단한다`() {
        val e = driving(occupants = 2)
        val cmds = runUntil(e, 0, 9 * 60_000L)
        val off = cmds.first { it.second == CareCommand.SetRecirculation(false) }.first
        e.onSignal(VehicleSignal.Recirculation(true), off + 10_000)
        assertEquals(CareKind.INFO, e.log.events.last().kind)
        val after = runUntil(e, off + 11_000, off + 4 * 60_000L)
        assertTrue("쿨다운 중에는 다시 개입하지 않음", after.none { it.second == CareCommand.SetRecirculation(false) })
    }

    @Test
    fun `3명 이상이면 외기 기본 모드를 한 번만 제안한다`() {
        val e = driving(occupants = 4)
        runUntil(e, 0, 3 * 60_000L)
        val suggestions = e.log.events.count { it.kind == CareKind.FRESH_AIR_SUGGEST }
        assertEquals(1, suggestions)
    }

    @Test
    fun `정차 중이거나 출발 2분 전에는 외기 모드를 제안하지 않는다`() {
        val parked = CareEngine(ALL, startMs = 0L)
        SeatId.entries.take(3).forEach { parked.onSignal(VehicleSignal.SeatOccupancy(it, true), 0) }
        parked.onSignal(VehicleSignal.Recirculation(true), 0)
        runUntil(parked, 0, 4 * 60_000L)
        assertEquals(0, parked.log.events.count { it.kind == CareKind.FRESH_AIR_SUGGEST })

        val justLeft = driving(occupants = 3)
        runUntil(justLeft, 0, 110_000L)
        assertEquals(0, justLeft.log.events.count { it.kind == CareKind.FRESH_AIR_SUGGEST })
        runUntil(justLeft, 111_000L, 130_000L)
        assertEquals(1, justLeft.log.events.count { it.kind == CareKind.FRESH_AIR_SUGGEST })
    }

    @Test
    fun `외기 상태에서는 개입하지 않는다`() {
        val e = driving(occupants = 2)
        e.onSignal(VehicleSignal.Recirculation(false), 0)
        val cmds = runUntil(e, 0, 30 * 60_000L)
        assertTrue(cmds.isEmpty())
    }
}
