package app.nunchi.core.sim

import app.nunchi.core.CareConfig
import org.junit.Assume.assumeTrue
import org.junit.Test

class DebugSim {
    @Test
    fun debug() {
        assumeTrue(System.getProperty("nunchi.debug") == "1")
        listOf(VirtualDriveExperiment.UNIQUE, VirtualDriveExperiment.COMMUTER).forEach { p ->
            val d = VirtualDrive(p, CareConfig(), seed = 1)
            val s = d.run(VirtualDriveExperiment.DAYS)
            println("== ${p.name}: trips=${s.trips} wanted=${s.opsWanted} byHand=${s.opsByHand} byNunchi=${s.opsByNunchi} unwanted=${s.opsUnwanted} asks=${s.habitAsks} acc=${s.habitAccepted} rej=${s.habitRejected} ign=${s.habitIgnored} applies=${s.habitApplies} firstHelp=${s.firstHelp} exc=${s.exceptionAsks}/${s.exceptionAuto}/${s.exceptionLearned} noise=${s.noiseHabits}")
            s.habitTitles.forEach { println("   H: $it") }
            s.aversions.forEach { println("   A: $it") }
            d.engine.log.events.filter { it.routine.name == "R8" || it.message.startsWith("제안") || it.message.contains("거둠") }.take(45).forEach { println("   L: ${it.message}") }
        }
    }
}
