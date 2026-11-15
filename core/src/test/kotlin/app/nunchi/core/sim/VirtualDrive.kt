package app.nunchi.core.sim

import app.nunchi.core.AmbientColor
import app.nunchi.core.CareAction
import app.nunchi.core.CareCommand
import app.nunchi.core.CareConfig
import app.nunchi.core.CareEngine
import app.nunchi.core.CareKind
import app.nunchi.core.Control
import app.nunchi.core.DoorId
import app.nunchi.core.FanDirection
import app.nunchi.core.Proposal
import app.nunchi.core.SeatId
import app.nunchi.core.VehicleSignal
import java.util.PriorityQueue
import kotlin.random.Random

/**
 * 가상 운전자 실험 — NUNCHI를 4주 동안 '살아 보게' 한다.
 *
 * 차(가상): 사람이 바꾼 값·NUNCHI 명령을 실제 값으로 들고 있고, 명령엔 반향을 돌려준다(가끔 옛 값이 먼저 한 번 튐).
 * 오토 공조면 달리는 동안 바람 세기·방향을 스스로 바꾸고, 비 오는 날엔 오토 디포그가 바람을 앞유리로 돌린다.
 * 내릴 때 시동이 꺼지며 열선·창은 꺼진다(설정온도·무드등 색은 남는다).
 *
 * 사람(가상): 승차마다 '하고 싶은 조작'이 있다(가끔 몇 개는 잊는다). NUNCHI가 여쭈면 2초 뒤 대답한다 —
 * 제안의 75% 이상이 평소 하던 것이면 '네'(두 번째부터 60%는 '다음부턴 알아서'), 아니면 '아니요'. 10%는 대답을 안 한다.
 * NUNCHI가 맞춘 것 중 원하지 않는 건 5~15초 안에 되돌린다. 나머지 하고 싶은 건 손으로 한다(1.5~6초 간격, 가끔 25초 멈칫).
 */
class VirtualDrive(private val persona: Persona, private val cfg: CareConfig, seed: Long) {

    private val rnd = Random(seed)
    private var hour = 8
    val engine = CareEngine(cfg, startMs = 0L, hourOf = { hour }, isWeekend = { ms -> (ms / DAY_MS) % 7 >= 5 })
    val stats = SimStats(persona.name)

    private var now = 0L
    private var seq = 0L
    private val pending = PriorityQueue<Pending>(compareBy<Pending>({ it.at }, { it.seq }))
    private val car = mutableMapOf<Control, Int>()

    private var plan: TripPlan? = null
    private var welcome = emptyMap<Control, Int>()
    private var habitEventsSeen = 0
    private var dryEventsSeen = 0
    private var decidingFor: Proposal? = null
    private val accepts = mutableMapOf<String, Int>()
    private var glassAsked = false

    private class Pending(val at: Long, val seq: Long, val action: () -> Unit)

    fun run(days: Int): SimStats {
        startCar()
        for (day in 0 until days) {
            val dayStart = day * DAY_MS
            persona.plan(day, rnd).sortedBy { it.hour * 60 + it.minute }.forEach { trip ->
                val at = dayStart + (trip.hour * 60 + trip.minute) * 60_000L
                if (at > now) advance(at, tickEvery = 60_000)
                drive(trip)
            }
            advance(dayStart + DAY_MS - 1, tickEvery = 300_000)
        }
        finish()
        return stats
    }

    // ---- 한 번의 승차 ----

    private fun drive(trip: TripPlan) {
        plan = trip
        hour = trip.hour
        stats.trips++
        val label = trip.label
        val index = (stats.tripsPerLabel[label] ?: 0) + 1
        stats.tripsPerLabel[label] = index
        if (trip.exception) stats.exceptionTrips++
        welcome = trip.boarding + trip.driving
        val nunchiBefore = stats.opsByNunchi
        val unwantedBefore = stats.opsUnwanted
        val wantedBefore = stats.opsWanted
        val asksBefore = stats.habitAsks

        // 날씨
        emit(VehicleSignal.OutsideTemperature(trip.outsideC))
        if (trip.rain != engine.state.wiperOn) emit(VehicleSignal.Wiper(trip.rain))

        // 오늘 탈 때 하려는 것(가끔 잊는다) — NUNCHI가 없었다면 이만큼 손으로 했을 것
        val todo = trip.boarding.filter { rnd.nextDouble() < trip.consistency }
        stats.opsWanted += todo.count { (c, v) -> car[c] != v }

        // 탄다
        emit(VehicleSignal.Door(DoorId.FRONT_LEFT, true))
        advance(now + 400, tickEvery = 200)
        emit(VehicleSignal.SeatOccupancy(SeatId.DRIVER, true))
        val seats = PASSENGER_SEATS.take(trip.passengers)
        seats.forEach { (door, seat) ->
            emit(VehicleSignal.Door(door, true))
            emit(VehicleSignal.SeatOccupancy(seat, true))
        }
        advance(now + 1_500, tickEvery = 500)
        seats.forEach { (door, _) -> emit(VehicleSignal.Door(door, false)) }
        emit(VehicleSignal.Door(DoorId.FRONT_LEFT, false))
        // NUNCHI가 여쭐 틈 + 대답
        advance(now + 6_000, tickEvery = 500)

        // 남은 건 손으로
        doByHand(todo)

        // 달린다
        emit(VehicleSignal.Gear(parked = false))
        val end = now + trip.driveMinutes * 60_000L
        if (trip.driving.isNotEmpty()) {
            val drivingTodo = trip.driving.filter { rnd.nextDouble() < trip.consistency }
            later(rnd.nextLong(60_000, 120_000)) {
                stats.opsWanted += drivingTodo.count { (c, v) -> car[c] != v }
                drivingTodo.forEach { (c, v) -> if (car[c] != v) userSet(c, v) }
            }
        }
        if (persona.autoClimate) scheduleAutoNoise(end)
        if (trip.rain) scheduleDefog(end)
        advance(end, tickEvery = 5_000)

        // 내린다(시동 끔)
        emit(VehicleSignal.Gear(parked = true))
        advance(now + 2_000, tickEvery = 500)
        emit(VehicleSignal.Door(DoorId.FRONT_LEFT, true))
        seats.forEach { (door, seat) ->
            emit(VehicleSignal.Door(door, true))
            emit(VehicleSignal.SeatOccupancy(seat, false))
        }
        emit(VehicleSignal.SeatOccupancy(SeatId.DRIVER, false))
        advance(now + 1_500, tickEvery = 500)
        seats.forEach { (door, _) -> emit(VehicleSignal.Door(door, false)) }
        emit(VehicleSignal.Door(DoorId.FRONT_LEFT, false))
        pending.clear()
        OFF_AT_SHUTDOWN.forEach { (c, v) -> carSet(c, v) }
        advance(now + 60_000, tickEvery = 10_000)
        plan = null

        // 이 승차의 성적(습관 label마다)
        val helped = stats.opsByNunchi - nunchiBefore
        val unwanted = stats.opsUnwanted - unwantedBefore
        stats.perLabel.getOrPut(label) { LabelStats() }.apply {
            trips++
            wanted += stats.opsWanted - wantedBefore
            byNunchi += helped
            this.unwanted += unwanted
            asks += stats.habitAsks - asksBefore
        }
        // 처음 '제대로' 도운 승차: NUNCHI가 맞춘 게 있고, 원치 않게 맞춘 건 하나도 없음
        if (helped > 0 && unwanted == 0) stats.firstCleanHelp.putIfAbsent(label, index)
    }

    private fun doByHand(todo: Map<Control, Int>) {
        todo.entries.shuffled(rnd).forEach { (c, v) ->
            if (car[c] == v) return@forEach // NUNCHI가 이미 맞춤
            val gap = if (rnd.nextDouble() < 0.08) 25_000L else rnd.nextLong(1_500, 6_000)
            advance(now + gap, tickEvery = 500)
            if (car[c] != v) userSet(c, v)
        }
    }

    // ---- 차 ----

    private fun startCar() {
        START_VALUES.forEach { (c, v) -> carSet(c, v) }
        if (persona.autoClimate) carSet(Control.AUTO, 1)
        emit(VehicleSignal.Gear(parked = true))
    }

    /** 차가 스스로 바꿈(사람 손 아님). */
    private fun carSet(c: Control, v: Int) {
        car[c] = v
        emit(c.signal(v))
    }

    private fun userSet(c: Control, v: Int) {
        car[c] = v
        stats.opsByHand++
        emit(c.signal(v))
    }

    /** 오토 공조: 달리는 동안 2~5분마다 바람 세기(가끔 방향)를 스스로 바꾼다. */
    private fun scheduleAutoNoise(end: Long) {
        var at = now
        while (true) {
            at += rnd.nextLong(120_000, 300_000)
            if (at >= end) break
            pending += Pending(at, seq++) {
                carSet(Control.FAN, rnd.nextInt(2, 7))
                if (rnd.nextDouble() < 0.3) {
                    carSet(Control.FAN_DIR, listOf(FanDirection.FACE, FanDirection.FACE_AND_FLOOR, FanDirection.FLOOR).random(rnd).ordinal)
                }
            }
        }
    }

    /** 비 오는 날 오토 디포그: 4~8분마다 바람을 앞유리로. 꺼리는 사람은 3~6초 안에 되돌린다(85%). */
    private fun scheduleDefog(end: Long) {
        var at = now
        while (true) {
            at += rnd.nextLong(240_000, 480_000)
            if (at >= end) break
            pending += Pending(at, seq++) {
                val before = car[Control.FAN_DIR] ?: FanDirection.FACE.ordinal
                if (FanDirection.entries[before].defrost) return@Pending
                carSet(Control.FAN_DIR, FanDirection.DEFROST_AND_FLOOR.ordinal)
                if (persona.dislikesGlassAir && rnd.nextDouble() < 0.85) {
                    later(rnd.nextLong(3_000, 6_000)) {
                        stats.glassReverts++
                        userSet(Control.FAN_DIR, before)
                    }
                } else {
                    later(rnd.nextLong(90_000, 150_000)) { carSet(Control.FAN_DIR, before) }
                }
            }
        }
    }

    // ---- NUNCHI와 주고받기 ----

    private fun emit(signal: VehicleSignal) = handle(engine.onSignal(signal, now))

    private fun handle(commands: List<CareCommand>) {
        val habitEvents = engine.log.events.count { it.kind == CareKind.HABIT }
        val habitBatch = habitEvents > habitEventsSeen
        habitEventsSeen = habitEvents
        val dryEvents = engine.log.events.count { it.kind == CareKind.DRY_AIR }
        stats.dryAirRuns += dryEvents - dryEventsSeen
        dryEventsSeen = dryEvents
        val tripPlan = plan
        if (habitBatch && tripPlan != null) {
            if (tripPlan.exception && decidingFor == null) stats.exceptionAuto++
            stats.firstHelp.putIfAbsent(tripPlan.label, stats.tripsPerLabel[tripPlan.label] ?: 0)
        }
        commands.forEach { cmd ->
            val (c, v) = cmd.control() ?: return@forEach
            val old = car[c]
            car[c] = v
            // 반향: 20%는 옛 값이 먼저 한 번 튄다
            if (old != null && old != v && rnd.nextDouble() < 0.2) later(150) { emit(c.signal(old)) }
            later(rnd.nextLong(300, 900)) { if (car[c] == v) emit(c.signal(v)) }
            if (habitBatch) {
                if (welcome[c] == v) {
                    stats.opsByNunchi++
                } else {
                    // 원하지 않는 걸 맞춤 → 되돌린다
                    stats.opsUnwanted++
                    val back = welcome[c] ?: old
                    if (back != null) later(rnd.nextLong(5_000, 15_000)) { if (car[c] == v) userSet(c, back) }
                }
            }
        }
    }

    private fun later(delayMs: Long, action: () -> Unit) {
        pending += Pending(now + delayMs, seq++, action)
    }

    /** 시간을 [until]까지 흘린다: 예약된 일을 처리하고 [tickEvery]마다 tick, 제안이 뜨면 대답한다. */
    private fun advance(until: Long, tickEvery: Long) {
        var nextTick = now + tickEvery
        while (true) {
            val ev = pending.peek()
            val evAt = ev?.at ?: Long.MAX_VALUE
            if (ev != null && evAt <= nextTick && evAt <= until) {
                pending.poll()
                now = maxOf(now, evAt)
                ev.action()
            } else if (nextTick <= until) {
                now = nextTick
                handle(engine.tick(now))
                react()
                nextTick += tickEvery
            } else {
                break
            }
        }
        now = maxOf(now, until)
    }

    private fun react() {
        val p = engine.proposal ?: return
        if (decidingFor === p) return
        decidingFor = p
        later(2_000) {
            if (engine.proposal !== p) {
                decidingFor = null
                return@later
            }
            decide(p)
            decidingFor = null
        }
    }

    private fun decide(p: Proposal) {
        val offer = p.offer
        when (offer.action) {
            CareAction.HABIT -> {
                stats.habitAsks++
                val tripPlan = plan
                if (tripPlan?.exception == true) stats.exceptionAsks++
                if (rnd.nextDouble() < 0.1) {
                    stats.habitIgnored++ // 대답 안 함 → 1분 뒤 조용히 거둠
                    return
                }
                val changes = offer.changes
                val liked = changes.count { (c, v) -> welcome[c] == v }
                val match = changes.isNotEmpty() && liked.toDouble() / changes.size >= 0.75
                if (match) {
                    val key = offer.key.orEmpty()
                    val n = accepts[key] ?: 0
                    accepts[key] = n + 1
                    stats.habitAccepted++
                    tripPlan?.let { stats.firstHelp.putIfAbsent(it.label, stats.tripsPerLabel[it.label] ?: 0) }
                    handle(engine.approve(now, always = n >= 1 && rnd.nextDouble() < 0.6))
                } else {
                    stats.habitRejected++
                    handle(engine.reject(now))
                }
            }
            CareAction.DRY_AIR -> {
                if (offer.habit && !glassAsked) {
                    glassAsked = true
                    stats.glassAskedAfterReverts = stats.glassReverts
                }
                stats.otherAsks++
                if (persona.dislikesGlassAir) handle(engine.approve(now)) else handle(engine.reject(now))
            }
            else -> {
                stats.otherAsks++
                if (rnd.nextDouble() < 0.5) handle(engine.approve(now)) else handle(engine.reject(now))
            }
        }
    }

    private fun finish() {
        val view = engine.habits.view(now)
        stats.habitsLearned = view.habits.count { it.ready }
        stats.noiseHabits = view.habits.count { h -> h.ready && FAN_WORDS.any { h.items.contains(it) } }
        stats.exceptionLearned = view.habits.count { it.title.startsWith("새벽") || it.title.contains(" 새벽") }
        stats.habitApplies = engine.log.events.count { it.kind == CareKind.HABIT }
        stats.trustedHabits = view.habits.count { it.trusted }
        stats.mutedHabits = view.habits.count { it.muted }
        stats.aversions = view.aversions.map { it.text }
        stats.habitTitles = view.habits.filter { it.ready }.map { "${it.title}: ${it.items} (${it.status})" }
    }

    companion object {
        const val DAY_MS = 24 * 3_600_000L

        val PASSENGER_SEATS = listOf(
            DoorId.FRONT_RIGHT to SeatId.PASSENGER,
            DoorId.REAR_LEFT to SeatId.REAR_LEFT,
            DoorId.REAR_RIGHT to SeatId.REAR_RIGHT,
        )

        val START_VALUES: Map<Control, Int> = linkedMapOf(
            Control.TEMP to 220,
            Control.AC to 0,
            Control.AUTO to 0,
            Control.FAN to 3,
            Control.FAN_DIR to FanDirection.FACE.ordinal,
            Control.RECIRC to 0,
            Control.SEAT_HEAT to 0,
            Control.SEAT_VENT to 0,
            Control.WHEEL_HEAT to 0,
            Control.FRONT_DEFROST to 0,
            Control.REAR_DEFROST to 0,
            Control.MIRROR_HEAT to 0,
            Control.AMBIENT to 1,
            Control.AMBIENT_COLOR to AmbientColor.BLUE.argb,
            Control.DRIVER_WINDOW to 0,
        )

        /** 시동이 꺼지면 꺼지는 것(설정온도·무드등 색·에어컨은 남는다). */
        val OFF_AT_SHUTDOWN: Map<Control, Int> = linkedMapOf(
            Control.SEAT_HEAT to 0,
            Control.SEAT_VENT to 0,
            Control.WHEEL_HEAT to 0,
            Control.MIRROR_HEAT to 0,
            Control.FRONT_DEFROST to 0,
            Control.REAR_DEFROST to 0,
            Control.DRIVER_WINDOW to 0,
        )

        /** 바람 세기·방향 습관(오토 공조 잡음에서 배우면 안 됨)을 가려내는 말. */
        val FAN_WORDS = listOf("바람 1단", "바람 2단", "바람 3단", "바람 4단", "바람 5단", "바람 6단", "바람 얼굴", "바람 발밑")
    }
}

/** 한 번의 승차 계획. */
data class TripPlan(
    val hour: Int,
    val minute: Int = 0,
    val outsideC: Float,
    val rain: Boolean = false,
    val passengers: Int = 0,
    /** 이 승차가 속한 '진짜 습관'(지표용). */
    val label: String,
    val boarding: Map<Control, Int>,
    val driving: Map<Control, Int> = emptyMap(),
    /** 항목마다 실제로 하는 확률(가끔 잊는다). */
    val consistency: Double = 0.9,
    /** 평소와 다른 날(새벽 출장 등). */
    val exception: Boolean = false,
    val driveMinutes: Int = 25,
)

class Persona(
    val name: String,
    val summary: String,
    val autoClimate: Boolean,
    val dislikesGlassAir: Boolean,
    val plan: (day: Int, rnd: Random) -> List<TripPlan>,
)

class SimStats(val persona: String) {
    var trips = 0
    val tripsPerLabel = linkedMapOf<String, Int>()
    /** NUNCHI가 없었다면 손으로 했을 조작 수. */
    var opsWanted = 0
    var opsByHand = 0
    /** NUNCHI가 맞춘 것 중 사람이 원한 것. */
    var opsByNunchi = 0
    /** NUNCHI가 맞췄는데 원하지 않아 되돌린 것. */
    var opsUnwanted = 0
    var habitAsks = 0
    var habitAccepted = 0
    var habitRejected = 0
    var habitIgnored = 0
    var habitApplies = 0
    var otherAsks = 0
    /** 습관(label)마다 몇 번째 승차에 처음 도왔나(제안이 맞았거나 알아서 맞춤). */
    val firstHelp = linkedMapOf<String, Int>()
    /** 습관(label)마다 처음 '제대로' 도운 승차 — NUNCHI가 맞춘 게 있고 원치 않게 맞춘 건 없음. */
    val firstCleanHelp = linkedMapOf<String, Int>()
    val perLabel = linkedMapOf<String, LabelStats>()
    var exceptionTrips = 0
    var exceptionAsks = 0
    /** 예외일에 묻지 않고 맞춘 횟수 — 0이어야 한다. */
    var exceptionAuto = 0
    /** 예외일 조작이 습관이 된 개수 — 0이어야 한다. */
    var exceptionLearned = 0
    var glassReverts = 0
    var glassAskedAfterReverts: Int? = null
    var dryAirRuns = 0
    var habitsLearned = 0
    var noiseHabits = 0
    var trustedHabits = 0
    var mutedHabits = 0
    var aversions: List<String> = emptyList()
    var habitTitles: List<String> = emptyList()

    val habitAuto: Int get() = (habitApplies - habitAccepted).coerceAtLeast(0)
    /** 손 조작 중 NUNCHI가 덜어 준 비율. */
    val handsSaved: Double get() = if (opsWanted == 0) 0.0 else opsByNunchi.toDouble() / opsWanted
}

/** 습관(label) 하나의 성적. */
class LabelStats {
    var trips = 0
    var wanted = 0
    var byNunchi = 0
    var unwanted = 0
    var asks = 0
    val handsSaved: Double get() = if (wanted == 0) 0.0 else byNunchi.toDouble() / wanted
}
