package app.nunchi.core.sim

import app.nunchi.core.AmbientColor
import app.nunchi.core.CareConfig
import app.nunchi.core.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 가상 운전자 4주 실험. 평소 테스트에선 짧은 점검(smoke)만,
 * 전체 실험·기본값 비교는 `NUNCHI_SIM=1 ./gradlew :core:test --tests '*VirtualDriveExperiment*'`로 돌린다
 * (결과: core/build/sim/report.md).
 */
class VirtualDriveExperiment {

    @Test
    fun `점검 — 독특한 조합도 몇 번 만에 익히고, 새벽 출장엔 여쭙기만 한다`() {
        val unique = VirtualDrive(UNIQUE, CareConfig(), seed = 1).run(DAYS)
        val helped = unique.firstHelp["독특한 아침"]
        assertTrue("독특한 조합을 익혀 도왔다: ${unique.firstHelp}", helped != null && helped <= 10)
        assertEquals(0, unique.noiseHabits)

        val commuter = VirtualDrive(COMMUTER, CareConfig(), seed = 1).run(DAYS)
        assertEquals("새벽 출장에 묻지 않고 맞춘 일", 0, commuter.exceptionAuto)
        assertEquals("새벽 출장 조작이 습관이 됨", 0, commuter.exceptionLearned)
        assertEquals("오토 공조 잡음을 습관으로 배움", 0, commuter.noiseHabits)

        val drift = VirtualDrive(DRIFT, CareConfig(), seed = 1).run(DAYS)
        val after = drift.firstCleanHelp["달라진 뒤"]
        assertTrue("취향이 달라지면 몇 번 만에 따라온다: ${drift.firstCleanHelp}", after != null && after <= 3)
    }

    @Test
    fun `실험 — 가상 운전자 4주, 기본값 비교`() {
        assumeTrue("NUNCHI_SIM=1 일 때만", System.getProperty("nunchi.sim") == "1")
        val seeds = (System.getProperty("nunchi.seeds")?.toIntOrNull() ?: 20)
        val configs = linkedMapOf(
            "기본(3번·20초)" to CareConfig(),
            "2번이면 제안" to CareConfig(habitMinRepeats = 2),
            "4번이면 제안" to CareConfig(habitMinRepeats = 4),
            "묶음 간격 10초" to CareConfig(habitGapMs = 10_000),
            "묶음 간격 30초" to CareConfig(habitGapMs = 30_000),
            "달라진 취향 알아차리기 끔" to CareConfig(habitNoticeDrift = false),
        )
        val report = StringBuilder()
        report.appendLine("# NUNCHI R8 가상 운전자 실험 (${DAYS}일 × 씨앗 ${seeds}개)")
        report.appendLine()
        report.appendLine("사람 모형: 승차마다 하고 싶은 조작(가끔 잊음), 제안의 75% 이상이 평소 하던 것이면 '네', 10%는 대답 안 함, 원치 않게 맞춰진 건 5~15초 안에 되돌림.")
        report.appendLine("차 모형: 명령 반향(20%는 옛 값이 먼저 튐), 오토 공조는 달리는 동안 2~5분마다 바람을 스스로 바꿈, 비 오는 날 오토 디포그가 4~8분마다 바람을 앞유리로.")
        report.appendLine()

        configs.forEach { (name, cfg) ->
            report.appendLine("## $name")
            report.appendLine()
            report.appendLine("| 운전자 | 승차 | 직접 조작 덜어 줌 | 여쭘 | 네 | 아니요 | 원치 않게 맞춤 | 알아서 맞춤 | 처음 제대로 도운 승차(중앙값) | 예외일: 여쭘/묻지 않고 맞춤/배움 | 잡음 습관 | 유리 바람: 되돌림→여쭘 · 습기 미리 뺀 횟수 |")
            report.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
            PERSONAS.forEach { persona ->
                val runs = (1..seeds).map { VirtualDrive(persona, cfg, seed = it.toLong()).run(DAYS) }
                report.appendLine(row(persona, runs))
            }
            report.appendLine()
        }

        // 취향이 달라진 뒤 얼마나 빨리 따라오나 — '달라진 취향 알아차리기' 켬/끔
        report.appendLine("## 취향이 달라진 뒤 (${DRIFT.summary})")
        report.appendLine()
        report.appendLine("| 설정 | 달라진 뒤 처음 제대로 도운 승차(중앙값) | 달라진 뒤 원치 않게 맞춘 조작 | 달라진 뒤 여쭘 | 달라진 뒤 직접 조작 덜어 줌 |")
        report.appendLine("|---|---|---|---|---|")
        listOf("기본(3번·20초)", "달라진 취향 알아차리기 끔").forEach { name ->
            val runs = (1..seeds).map { VirtualDrive(DRIFT, configs.getValue(name), seed = it.toLong()).run(DAYS) }
            val first = runs.mapNotNull { it.firstCleanHelp["달라진 뒤"] }.sorted()
            val firstText = if (first.isEmpty()) "없음" else "${first[first.size / 2]}번째 (${first.size * 100 / runs.size}%)"
            fun avg(f: (LabelStats) -> Number) = runs.map { r -> r.perLabel["달라진 뒤"]?.let { f(it).toDouble() } ?: 0.0 }.average()
            report.appendLine("| %s | %s | %.1f | %.1f | %.0f%% |".format(name, firstText, avg { it.unwanted }, avg { it.asks }, avg { it.handsSaved * 100 }))
        }
        report.appendLine()

        // 기본값으로 한 번 돌린 '배운 것' 예시
        report.appendLine("## 기본값 — 씨앗 1번이 4주 뒤 배운 것")
        report.appendLine()
        PERSONAS.forEach { persona ->
            val s = VirtualDrive(persona, CareConfig(), seed = 1).run(DAYS)
            report.appendLine("### ${persona.name} — ${persona.summary}")
            if (s.habitTitles.isEmpty()) report.appendLine("- (배운 습관 없음)")
            s.habitTitles.forEach { report.appendLine("- $it") }
            s.aversions.forEach { report.appendLine("- 불편해하는 것: $it") }
            report.appendLine()
        }

        val out = File("build/sim").apply { mkdirs() }.resolve("report.md")
        out.writeText(report.toString())
        println(report)
    }

    private fun row(persona: Persona, runs: List<SimStats>): String {
        fun avg(f: (SimStats) -> Number) = runs.map { f(it).toDouble() }.average()
        val labels = runs.flatMap { it.tripsPerLabel.keys }.distinct().filter { it != "무작위" && it != "새벽 출장" }
        val firstHelp = labels.joinToString(" · ") { label ->
            val helped = runs.mapNotNull { it.firstCleanHelp[label] }.sorted()
            val rate = helped.size * 100 / runs.size
            if (helped.isEmpty()) "$label 없음" else "$label ${helped[helped.size / 2]}번째(${rate}%)"
        }.ifEmpty { "—" }
        val exception = if (runs.all { it.exceptionTrips == 0 }) "—" else
            "%.1f/%.1f/%.1f".format(avg { it.exceptionAsks }, avg { it.exceptionAuto }, avg { it.exceptionLearned })
        val glass = if (!persona.dislikesGlassAir) "—" else {
            val asked = runs.mapNotNull { it.glassAskedAfterReverts }
            val askedText = if (asked.isEmpty()) "안 여쭘" else "${asked.sorted()[asked.size / 2]}번 되돌림→여쭘"
            "$askedText · %.1f회".format(avg { it.dryAirRuns })
        }
        return "| ${persona.name} | %.0f | %.0f%% | %.1f | %.1f | %.1f | %.1f | %.1f | %s | %s | %.1f | %s |".format(
            avg { it.trips }, avg { it.handsSaved * 100 }, avg { it.habitAsks }, avg { it.habitAccepted },
            avg { it.habitRejected }, avg { it.opsUnwanted }, avg { it.habitAuto }, firstHelp, exception,
            avg { it.noiseHabits }, glass,
        )
    }

    companion object {
        const val DAYS = 28

        private val ORANGE = AmbientColor.ORANGE.argb
        private val PURPLE = AmbientColor.PURPLE.argb
        private val GREEN = AmbientColor.GREEN.argb
        private val BLUE = AmbientColor.BLUE.argb

        /** 평일 아침 8시 혼자 출근(영하), 저녁 6시 반 퇴근, 주말 낮엔 가족과. 17일째 새벽 4시 출장. 오토 공조. */
        val COMMUTER = Persona(
            name = "출퇴근 운전자",
            summary = "평일 아침 8시 혼자 출근(영하)·저녁 6시 반 퇴근, 주말 낮 가족, 17일째 새벽 4시 출장, 오토 공조",
            autoClimate = true,
            dislikesGlassAir = false,
        ) { day, rnd ->
            val cold = rnd.nextDouble(-6.0, 4.0).toFloat()
            val rain = rnd.nextDouble() < 0.15
            buildList {
                if (day == 17) {
                    add(TripPlan(4, 10, cold - 3, label = "새벽 출장", exception = true, driveMinutes = 60,
                        boarding = mapOf(Control.SEAT_HEAT to 3, Control.WHEEL_HEAT to 1)))
                }
                if (day % 7 < 5) {
                    add(TripPlan(8, 0, cold, rain, label = "아침 출근",
                        boarding = mapOf(Control.SEAT_HEAT to 2, Control.WHEEL_HEAT to 1, Control.TEMP to 250)))
                    add(TripPlan(18, 30, cold + 4, rain, label = "저녁 퇴근", consistency = 0.85,
                        boarding = mapOf(Control.SEAT_HEAT to 1, Control.AMBIENT_COLOR to PURPLE)))
                } else if (rnd.nextDouble() < 0.6) {
                    add(TripPlan(11, 0, cold + 6, rain, passengers = 2 + rnd.nextInt(2), label = "주말 가족",
                        boarding = mapOf(Control.TEMP to 230, Control.AMBIENT_COLOR to GREEN)))
                }
            }
        }

        /** 매일 아침 7시 반(추움) 히터 27℃ + 창 1cm + 무드등 주황 + 미러 열선, 밤 10시엔 무드등 끄고 21℃. */
        val UNIQUE = Persona(
            name = "독특한 조합",
            summary = "매일 아침 7시 반(추움) 27℃+창 1cm+무드등 주황+미러 열선, 밤 10시엔 무드등 끄고 21℃",
            autoClimate = false,
            dislikesGlassAir = false,
        ) { _, rnd ->
            val c = rnd.nextDouble(-4.0, 3.0).toFloat()
            listOf(
                TripPlan(7, 30, c, rnd.nextDouble() < 0.1, label = "독특한 아침", consistency = 0.85,
                    boarding = mapOf(Control.TEMP to 270, Control.DRIVER_WINDOW to 1, Control.AMBIENT_COLOR to ORANGE, Control.MIRROR_HEAT to 1)),
                TripPlan(22, 0, c - 2, label = "밤",
                    boarding = mapOf(Control.AMBIENT to 0, Control.TEMP to 210)),
            )
        }

        /** 아무 때나 타고 조작도 그때그때 다름 — 배울 게 없어야 한다(불필요한 제안 측정). */
        val RANDOM = Persona(
            name = "습관 없음",
            summary = "아무 때나 타고 조작도 매번 다름(배울 게 없어야 함), 오토 공조",
            autoClimate = true,
            dislikesGlassAir = false,
        ) { _, rnd ->
            val pool: List<Pair<Control, List<Int>>> = listOf(
                Control.TEMP to listOf(200, 220, 240, 260),
                Control.SEAT_HEAT to listOf(0, 1, 2, 3),
                Control.WHEEL_HEAT to listOf(0, 1),
                Control.AMBIENT_COLOR to listOf(ORANGE, PURPLE, GREEN, BLUE),
                Control.SEAT_VENT to listOf(0, 1, 2),
                Control.MIRROR_HEAT to listOf(0, 1),
            )
            List(1 + rnd.nextInt(2)) {
                val items = pool.shuffled(rnd).take(rnd.nextInt(0, 4)).associate { (c, values) -> c to values.random(rnd) }
                TripPlan(rnd.nextInt(6, 23), rnd.nextInt(0, 60), rnd.nextDouble(-5.0, 30.0).toFloat(),
                    rain = rnd.nextDouble() < 0.2, passengers = rnd.nextInt(0, 3), label = "무작위",
                    boarding = items, consistency = 1.0)
            }
        }

        /** 매일 아침 9시, 비 오는 날 많음, 오토 디포그가 앞유리로 돌리면 곧바로 되돌림. */
        val GLASS = Persona(
            name = "유리 바람을 꺼림",
            summary = "매일 아침 9시(3~14℃), 비 40%, 오토 디포그가 앞유리로 돌리면 곧바로 되돌림, 오토 공조",
            autoClimate = true,
            dislikesGlassAir = true,
        ) { _, rnd ->
            listOf(
                TripPlan(9, 0, rnd.nextDouble(3.0, 14.0).toFloat(), rnd.nextDouble() < 0.4, passengers = rnd.nextInt(0, 2),
                    label = "아침", driveMinutes = 35, boarding = mapOf(Control.SEAT_HEAT to 1)),
            )
        }

        /** 취향이 달라지는 날(셋째 주 월요일). */
        const val CHANGE_DAY = 14

        /** 평일 아침 8시 혼자(영하). 셋째 주부터 취향이 달라짐 — 졸려서 24℃·파란 무드등, 열선은 1단으로. */
        val DRIFT = Persona(
            name = "취향이 달라짐",
            summary = "평일 아침 8시(영하) 열선 3단·핸들 열선·26℃·주황 → 15일째부터 열선 1단·핸들 열선·24℃·파랑",
            autoClimate = false,
            dislikesGlassAir = false,
        ) { day, rnd ->
            if (day % 7 >= 5) return@Persona emptyList()
            val before = day < CHANGE_DAY
            listOf(
                TripPlan(
                    8, 0, rnd.nextDouble(-6.0, 3.0).toFloat(), label = if (before) "달라지기 전" else "달라진 뒤",
                    boarding = if (before) {
                        mapOf(Control.SEAT_HEAT to 3, Control.WHEEL_HEAT to 1, Control.TEMP to 260, Control.AMBIENT_COLOR to ORANGE)
                    } else {
                        mapOf(Control.SEAT_HEAT to 1, Control.WHEEL_HEAT to 1, Control.TEMP to 240, Control.AMBIENT_COLOR to BLUE)
                    },
                ),
            )
        }

        val PERSONAS = listOf(COMMUTER, UNIQUE, RANDOM, GLASS, DRIFT)

        @Suppress("unused")
        private fun Random.chance(p: Double) = nextDouble() < p
    }
}
