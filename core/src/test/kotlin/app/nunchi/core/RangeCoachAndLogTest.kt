package app.nunchi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeCoachAndLogTest {

    @Test
    fun `목적지 여유율이 20퍼센트 아래면 에너지 코칭, 0 아래면 충전 안내`() {
        val e = CareEngine(CareConfig(trusted = CareAction.entries.toSet()))
        e.onSignal(VehicleSignal.RouteRemaining(100f), 0)
        assertTrue(e.onSignal(VehicleSignal.RangeRemaining(130f), 0).isEmpty())
        assertEquals(RangeLevel.OK, e.range.level)

        val tight = e.onSignal(VehicleSignal.RangeRemaining(110f), 1_000)
        assertEquals(RangeLevel.TIGHT, e.range.level)
        assertTrue(CareCommand.SetAc(false) in tight)
        assertTrue(tight.any { it is CareCommand.Say })

        assertTrue("같은 단계에서는 반복하지 않는다", e.onSignal(VehicleSignal.RangeRemaining(108f), 2_000).isEmpty())

        val short = e.onSignal(VehicleSignal.RangeRemaining(92f), 3_000)
        assertEquals(RangeLevel.SHORT, e.range.level)
        assertTrue((short.single() as CareCommand.Say).text.contains("8km"))
    }

    @Test
    fun `목적지가 없으면 주행가능 20km와 10km 기준`() {
        val e = CareEngine(CareConfig(trusted = CareAction.entries.toSet()))
        e.onSignal(VehicleSignal.RangeRemaining(25f), 0)
        assertEquals(RangeLevel.OK, e.range.level)
        e.onSignal(VehicleSignal.RangeRemaining(18f), 0)
        assertEquals(RangeLevel.TIGHT, e.range.level)
        e.onSignal(VehicleSignal.RangeRemaining(9f), 0)
        assertEquals(RangeLevel.SHORT, e.range.level)
    }

    @Test
    fun `타이어 저압 안내는 한 번만`() {
        val e = CareEngine(CareConfig(trusted = CareAction.entries.toSet()))
        assertEquals(1, e.onSignal(VehicleSignal.TireLow(true), 0).size)
        assertTrue(e.onSignal(VehicleSignal.TireLow(true), 1_000).isEmpty())
    }

    @Test
    fun `오늘의 1cm 카드는 배려만 센다`() {
        val log = CareLog()
        log.add(CareEvent(1, Routine.R0, CareKind.WINDOW_CRACK, ""))
        log.add(CareEvent(2, Routine.R0, CareKind.INFO, ""))
        log.add(CareEvent(3, Routine.R1, CareKind.FRESH_AIR, ""))
        log.add(CareEvent(4, Routine.R1, CareKind.FRESH_AIR, ""))
        val report = log.report(peakCo2Ppm = 1_880)
        assertEquals(3, report.total)
        assertEquals("창 1cm 1회 · 공기 교체 2회", report.summary)
        assertEquals("오늘은 조용히 지켜보기만 했어요", CareLog().report().summary)
    }
}
