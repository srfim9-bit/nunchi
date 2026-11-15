package app.nunchi.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

/** 하루 중 언제인가 — 습관은 시간대마다 다르다. */
enum class TimeBand(val label: String, val fromHour: Int) {
    DAWN("새벽", 0),
    MORNING("아침", 6),
    DAY("낮", 10),
    EVENING("저녁", 17),
    NIGHT("밤", 21);

    companion object {
        fun of(hour: Int): TimeBand = entries.last { hour >= it.fromHour }
    }
}

/** 바깥 온도(모르면 탈 때 실내 온도)로 본 날씨. 선선한 날은 말에 붙이지 않는다([rangeLabel]은 범위를 말할 때). */
enum class TempBand(val label: String, val rangeLabel: String) {
    COLD("추운", "추운"),
    COOL("쌀쌀한", "쌀쌀한"),
    MILD("", "선선한"),
    HOT("더운", "더운");

    companion object {
        fun of(celsius: Float?): TempBand = when {
            celsius == null -> MILD
            celsius < 5f -> COLD
            celsius < 15f -> COOL
            celsius < 26f -> MILD
            else -> HOT
        }
    }
}

enum class TripPhase(val label: String) {
    BOARDING("탈 때"),
    DRIVING("달리는 중"),
}

/** 지금 상황: 언제 · 날씨 · 비 · 동승자. */
data class Situation(val time: TimeBand, val temp: TempBand, val rain: Boolean, val company: Boolean) {
    /** "비 오는 추운 아침" */
    val phrase: String
        get() = buildString {
            if (rain) append("비 오는 ")
            if (temp.label.isNotEmpty()) append(temp.label).append(' ')
            append(time.label)
        }

    /** "추운 아침엔" / "추운 아침, 동승자가 있을 땐" */
    val whenPhrase: String get() = if (company) "$phrase, 동승자가 있을 땐" else "${phrase}엔"

    /** 카드용: "추운 아침 · 혼자" */
    val label: String get() = "$phrase · ${if (company) "동승" else "혼자"}"
}

/**
 * 습관이 들어맞는 범위. 시간대·동승은 그대로 두고, 날씨는 겪어 본 만큼만 넓힌다 —
 * 쌀쌀한 저녁에도 추운 저녁과 같은 조작을 하셨다면 '추운·쌀쌀한 저녁'으로(이웃한 온도대까지만, 한여름까지 번지지 않게),
 * 비 오는 날도 안 오는 날도 같으면 비는 따지지 않는다([rain] = null).
 */
data class HabitScope(
    val time: TimeBand,
    val company: Boolean,
    val tempMin: TempBand,
    val tempMax: TempBand,
    val rain: Boolean?,
) {
    fun matches(s: Situation): Boolean =
        time == s.time && company == s.company && s.temp.ordinal in tempMin.ordinal..tempMax.ordinal && (rain == null || rain == s.rain)

    /** 같은 시간대·동승이고 날씨만 이웃하게 다를 때 넓힐 수 있다. */
    fun canWiden(s: Situation): Boolean =
        time == s.time && company == s.company && s.temp.ordinal in (tempMin.ordinal - 1)..(tempMax.ordinal + 1)

    fun widen(s: Situation): HabitScope = copy(
        tempMin = if (s.temp.ordinal < tempMin.ordinal) s.temp else tempMin,
        tempMax = if (s.temp.ordinal > tempMax.ordinal) s.temp else tempMax,
        rain = if (rain == s.rain) rain else null,
    )

    /** 다른 시간대의 같은 날씨·동승(평소와 다른 시간에 비슷한 습관을 찾을 때). */
    fun matchesIgnoringTime(s: Situation): Boolean = copy(time = s.time).matches(s)

    /** "추운·쌀쌀한 저녁 · 혼자", "비 오는 추운 아침 · 혼자", "아침 · 동승" */
    val label: String
        get() {
            val temp = if (tempMin == tempMax) {
                tempMin.label
            } else {
                TempBand.entries.filter { it.ordinal in tempMin.ordinal..tempMax.ordinal }.joinToString("·") { it.rangeLabel }
            }
            val head = buildString {
                if (rain == true) append("비 오는 ")
                if (temp.isNotEmpty()) append(temp).append(' ')
                append(time.label)
            }
            return "$head · ${if (company) "동승" else "혼자"}"
        }

    companion object {
        fun exact(s: Situation) = HabitScope(s.time, s.company, s.temp, s.temp, s.rain)
    }
}

/** 화면용: 배운 습관 하나. */
data class HabitView(
    val id: String,
    val title: String,
    val items: String,
    val status: String,
    val ready: Boolean,
    val trusted: Boolean,
    val muted: Boolean,
    /** 달라진 취향을 알아차렸을 때 한 줄("취향이 달라지신 것 같아요: 설정온도 27℃ → 25℃ — 다음에 여쭤볼게요"). */
    val note: String? = null,
)

/** 화면용: 불편해하시는 것 하나(곧바로 되돌린 것). */
data class AversionView(val key: String, val text: String)

/** 화면용: '제가 아는 당신' 카드. */
data class LearnerView(
    val habits: List<HabitView> = emptyList(),
    val aversions: List<AversionView> = emptyList(),
    val usual: String? = null,
    val today: String? = null,
    val recording: String? = null,
    val dryAir: Boolean = false,
)

/**
 * R8 배우는 눈치 — 규칙을 미리 정해 두지 않고, 운전자가 직접 하는 조작에서 그 사람만의 습관을 익힌다.
 *
 * 1. 묶기: 20초 안에 이어서 바꾼 조작을 한 묶음으로, 그때 상황(시간대·날씨·비·동승자, 탈 때/달리는 중)과 함께 기억
 * 2. 알아보기: 같은 상황에서 같은 묶음이 3번 나오면 습관으로 인식한다. 조합이 아무리 독특해도 상관없다 — 정해 둔 규칙이 없으니까.
 *    이미 그렇게 돼 있어 손댈 필요가 없던 것도 '그대로 두셨다'로 센다(설정온도처럼 남아 있는 값).
 *    날씨만 조금 다른 날에도 같은 조작이면 범위를 넓힌다(추운 저녁 + 쌀쌀한 저녁 → 하나의 습관).
 * 3. 제안: 다음에 같은 상황이면 손대기 직전에 먼저 여쭙는다("추운 아침엔 늘 하시던 대로 맞춰 드릴까요?")
 * 4. 배움: 네(+) / 아니요(같은 습관을 2번 연달아 사양하시면 그만 권함) / 맞춘 걸 1분 안에 원래 값으로 되돌리면 분명한 '원치 않음'(알아서 하던 것도 다시 여쭙기로).
 *    맞춰 드린 뒤 더하신 조작은 같은 습관의 일부로 익힌다(습관이 조각나지 않게).
 *    맞춰 드린 걸 원래 값이 아닌 다른 값으로 바꾸시면 '원치 않음'이 아니라 '달라진 취향'일 수 있다 — 말없이 따라 바꾸지도,
 *    옛 습관을 고집하지도 않고 다음에 "지난번에 바꾸신 대로 맞춰 드릴까요?"라고 한 번 여쭙는다(네 = 습관을 바로 고침).
 *    차나 사람이 바꾼 걸 10초 안에 되돌리면 '불편해하시는 것'으로 기억 — 앞유리로 향한 바람을 두 번 되돌리면
 *    "김이 서리기 전에 습기를 미리 빼 둘까요?"라고 여쭙는다(시야가 먼저: 차가 김을 막으려 유리로 보내는 바람과는 다투지 않는다)
 * 5. 보여 주기: 익힌 것을 쉬운 말로 보여 주고 지울 수 있게 한다
 *
 * 평소와 다른 날(평일·주말을 나눠 보아 드문 시간대에 탐)엔 습관대로 하지 않고 여쭙기만 하며, 그날 조작은 습관에 넣지 않는다 —
 * 말없이 학습하는 자동화가 평소와 다른 날 사람을 당황하게 하는 한계를 보완한다.
 * NUNCHI가 보낸 명령의 반향, 차의 터널 공기 차단, 흐름 환기 중의 변화, 오토 공조가 스스로 바꾸는 바람은 사람의 조작으로 보지 않는다.
 */
class HabitLearner(
    private val config: () -> CareConfig,
    private val hourOf: (Long) -> Int = { systemHour(it) },
    /** 주말인가 — 평일 출근과 주말 나들이는 '평소'가 다르다. */
    private val weekendOf: (Long) -> Boolean = { systemWeekend(it) },
    /** 습관이 운전석 창을 맡을 때 R0가 창을 원위치하지 않도록 내려놓게 한다. */
    private val onTakesWindow: () -> Unit = {},
) {
    /** 시연용: 시계를 이 시각(0~23시)으로 본다. null이면 실제 시각. */
    var hourOverride: Int? = null

    /** 저장할 내용이 바뀔 때마다 오른다(앱이 보고 저장한다). */
    var revision = 0L
        private set

    /** 앞유리 쪽 바람을 불편해하셔서 "김 서리기 전 습기 미리 빼기"를 허락받았는가. */
    var dryAirEnabled = false
        private set

    private val habits = mutableListOf<Habit>()
    private val aversions = linkedMapOf<String, Aversion>()
    private val tripHistory = ArrayDeque<Pair<TimeBand, Boolean>>()
    private var nextId = 1
    private var dryPreferred = FanDirection.FLOOR
    private var glassAskedAtCount = 0
    private var glassOfferPending = false

    private var trip: Trip? = null
    private var open: OpenEpisode? = null
    private var lastState = CabinState()
    private var lastActivityMs = 0L
    private val lastValues = mutableMapOf<Control, Int>()
    private val own = mutableMapOf<Control, OwnCommand>()
    private val lastExternal = mutableMapOf<Control, Change>()

    fun isTrusted(habitId: String?): Boolean = find(habitId)?.trusted == true

    /** 이 설정의 지금 값을 한 번이라도 받았는가(처음 받은 값은 '기준'이라 바뀐 걸로 치지 않는다). */
    fun knows(control: Control): Boolean = control in lastValues

    // ---- 신호 ----

    fun onSignal(
        before: CabinState,
        after: CabinState,
        signal: VehicleSignal,
        nowMs: Long,
        guardActive: Boolean,
        venting: Boolean,
        human: Boolean = false,
        /** 사람이 직접 맞춘 것이 확실할 때, NUNCHI가 잠깐 바꿔 두기 전의 값(R0가 1cm 내려 두기 전 창 위치). */
        humanFrom: Int? = null,
    ): RoutineOutput {
        lastState = after
        var out = trackTrip(before, after, signal, nowMs)
        val (control, value) = signal.control() ?: return out
        val seen = lastValues.put(control, value)
        if (human && humanFrom != null) own.remove(control)
        // NUNCHI가 잠깐 바꿔 둔 값을 사람이 '그대로 두겠다'고 다시 고름 → 바꾸기 전 값에서 온 사람의 조작으로 본다.
        // 반대로 바꾸기 전 값으로 돌려놓았으면 배울 것이 없다(잠깐의 배려를 물린 것뿐)
        val prev = if (human && humanFrom != null) humanFrom else seen
        if (prev == null || prev == value) return out
        lastActivityMs = nowMs
        if (!human && isOwnEcho(control, prev, value, nowMs)) return out
        // 사람이 한 조작이 확실한데(시뮬레이터의 직접 조작) 승차 기록이 없으면, 지금 타고 계신 것으로 본다 —
        // '타기'를 누르지 않고 조작부터 해도 배울 수 있게
        if (human && trip == null) {
            out += startTrip(after, nowMs)
            if (after.allDoorsClosed) trip?.boardedAtMs = nowMs
        }

        // 내린 뒤, 또는 달리고 와서 세워 둔 차에서 바뀌는 것(시동을 끄며 꺼지는 열선·온도 초기화 등)은
        // 사람의 '습관'도 '원치 않음'도 아니다 — 타자마자 맞추고 곧바로 내려도 시동 끔을 '되돌림'으로 오해하지 않게
        val t = trip
        val ending = t == null || (after.parked && t.droveAtMs != null)
        // 곧바로 되돌림 = 불편해하심(차든 사람이든 바꾼 걸 10초 안에 원래대로)
        val revert = if (ending) null else detectRevert(control, prev, value, nowMs)
        if (revert != null) out += recordAversion(control, disliked = prev, preferred = value, nowMs = nowMs)
        // 방금 맞춰 드린 걸 1분 안에 되돌림 = 분명한 '원치 않음'
        out += checkUndo(control, value, nowMs)

        val fromGlass = control == Control.FAN_DIR && FanDirection.entries.getOrNull(prev)?.defrost == true
        val toGlass = control == Control.FAN_DIR && FanDirection.entries.getOrNull(value)?.defrost == true
        // 탄 사람이 있고, 아직 내릴 때가 아닐 때만 배운다
        val learnable = revert == null && !venting && !ending &&
            !(guardActive && (control == Control.RECIRC || control == Control.DRIVER_WINDOW)) &&
            !(control in AUTO_DRIVEN && after.read(Control.AUTO) == 1) &&
            // 앞유리 바람은 김을 막는 차의 일 — 그리로 가는 것도, 거기서 돌아오는 것도 습관이 아니다
            !toGlass && !fromGlass
        val ep = open
        if (learnable) {
            out += addToEpisode(control, prev, value, after, nowMs)
        } else if (ep != null && control in ep.after && !toGlass) {
            // 배우지 않는 변화라도 묶음 안의 값은 따라간다(켰다가 바로 끈 건 묶음에서 사라지게)
            ep.after[control] = value
            ep.lastMs = nowMs
        }
        return out
    }

    /**
     * [canAct] = 이번 틈에 다른 루틴이 낸 제안이 없음(허락받은 일은 바로 할 수 있음).
     * [canOffer] = 게다가 대답을 기다리는 제안도 없음(새로 여쭐 수 있음).
     */
    fun onTick(
        state: CabinState,
        nowMs: Long,
        canAct: Boolean,
        canOffer: Boolean,
        venting: Boolean,
        dryAirTrusted: Boolean,
    ): RoutineOutput {
        lastState = state
        var out = RoutineOutput.NONE
        open?.let { ep -> if (nowMs - ep.lastMs > config().habitGapMs && !stillBoarding(ep, nowMs)) out += finalize(ep, nowMs) }
        val t = trip
        if (t != null && state.parked && nowMs - lastActivityMs > config().tripIdleEndMs) out += endTrip(nowMs)
        if (venting || !canAct) return out
        dryAirOffer(state, dryAirTrusted)?.let { return out + RoutineOutput(offer = it) }
        if (canOffer) out += nextOffer(state, nowMs)
        return out
    }

    /** 차의 터널 차단이 시작됨 — 방금 '사람 손'으로 잡았던 내외기·창 변화는 차가 한 일이었다. */
    fun onCarGuardStarted(nowMs: Long) {
        val ep = open ?: return
        listOf(Control.RECIRC, Control.DRIVER_WINDOW).forEach { c ->
            val at = ep.addedAt[c] ?: return@forEach
            if (nowMs - at <= GUARD_PAIR_MS) {
                ep.after.remove(c)
                ep.before.remove(c)
                ep.addedAt.remove(c)
            }
        }
    }

    /** NUNCHI가 보낸 명령 — 그 반향을 사람의 조작으로 보지 않는다. [from] = 보내기 직전 값. */
    fun noteOwnCommand(command: CareCommand, from: Int?, nowMs: Long) {
        val (control, target) = command.control() ?: return
        val guard = if (control == Control.DRIVER_WINDOW) WINDOW_ECHO_MS else ECHO_MS
        own[control] = OwnCommand(target, from, nowMs, nowMs + guard)
        // 지금 값은 보낸 값으로 본다 — 반향이 늦거나 없어도, 사람이 원래 값으로 되돌리는 조작을 놓치지 않게
        lastValues[control] = target
    }

    // ---- 제안에 대한 대답 ----

    fun onApproved(habitId: String?, always: Boolean, nowMs: Long): RoutineOutput {
        val h = find(habitId) ?: return RoutineOutput.NONE
        h.accepted++
        h.rejectedInARow = 0
        if (always) h.trusted = true
        // 달라진 취향을 여쭌 것에 '네' = 습관을 지금 고친다(최근 관찰을 새 값으로)
        val revised = if (h.revised.isNotEmpty()) revisionText(h) else null
        h.commitRevision()
        revision++
        val notes = buildList {
            if (revised != null) add("달라진 취향 반영: $revised — 앞으로 이렇게 맞춰 드릴게요")
            if (always) add("'${h.scope.label}' 습관 허락받음 — 다음부터는 여쭙지 않고 맞춰 둘게요")
        }
        return if (notes.isEmpty()) RoutineOutput.NONE else RoutineOutput.info(nowMs, Routine.R8, notes.joinToString(" · "))
    }

    fun onRejected(habitId: String?, nowMs: Long): RoutineOutput {
        val h = find(habitId) ?: return RoutineOutput.NONE
        // 평소와 다른 날에 여쭌 걸 사양 = '오늘은 아니다'일 뿐, 평소 습관을 원치 않으시는 게 아니다
        if (trip?.unusual == true) {
            return RoutineOutput.info(nowMs, Routine.R8, "평소와 다른 날이라 오늘 사양하신 것은 '${h.scope.label}' 습관에 반영하지 않을게요")
        }
        // 달라진 취향을 여쭌 것에 '아니요' = 그날만의 일이었다 — 늘 하시던 대로 기억해 둔다
        val hadRevision = h.revised.isNotEmpty()
        h.revised = emptyMap()
        h.revisedFrom = emptyMap()
        h.rejectedInARow++
        val quiet = h.rejectedInARow >= config().quietAfterRejects
        if (quiet) h.muted = true
        revision++
        val head = if (hadRevision) "지난번 변경은 그날만의 일로 보고, 늘 하시던 대로 기억해 둘게요 · " else ""
        return RoutineOutput.info(
            nowMs, Routine.R8,
            head + if (quiet) "'${h.scope.label}' 습관을 ${h.rejectedInARow}번 연달아 사양하셔서, 이제 먼저 권하지 않을게요"
            else "'${h.scope.label}' 습관 사양 — 알겠습니다",
        )
    }

    // ---- 시뮬레이터 ----

    /**
     * (시뮬레이터) '타기'를 눌렀다 = 새 승차. 타고 있던 기록이 남아 있으면(문만 여닫아 본 뒤 등) 접고 새로 시작한다 —
     * 안 그러면 방금 고른 시각·날씨가 아니라, 먼저 열어 둔 묶음의 옛 상황으로 습관을 가른다.
     * 실차에선 문·착석·기어 신호가 승차를 가른다([trackTrip]).
     */
    fun beginTrip(state: CabinState, nowMs: Long): RoutineOutput {
        lastState = state
        lastActivityMs = nowMs
        var out = RoutineOutput.NONE
        if (trip != null) out += endTrip(nowMs)
        return out + startTrip(state, nowMs)
    }

    /** (시뮬레이터) 시각을 바꿨다 — 타고 있는 중이면 이번 승차의 시간대('아침 승차 중')도 따라 바꾼다. */
    fun retime(nowMs: Long) {
        val t = trip ?: return
        val band = TimeBand.of(hour(nowMs))
        if (band == t.band) return
        val weekend = weekendOf(nowMs)
        if (tripHistory.isNotEmpty()) tripHistory.removeLast()
        val sameType = tripHistory.filter { it.second == weekend }
        t.band = band
        t.unusual = sameType.size >= config().usualMinTrips && sameType.count { it.first == band } * 10 < sameType.size
        tripHistory.addLast(band to weekend)
        revision++
    }

    // ---- 화면에서 고치기 ----

    fun forgetHabit(id: String) {
        if (habits.removeAll { it.id == id }) revision++
    }

    fun untrustHabit(id: String) {
        find(id)?.let {
            it.trusted = false
            it.muted = false
            it.rejectedInARow = 0
            revision++
        }
    }

    fun forgetAversion(key: String) {
        if (aversions.remove(key) != null) {
            if (key == GLASS) {
                dryAirEnabled = false
                glassAskedAtCount = 0
            }
            revision++
        }
    }

    fun clearAll() {
        habits.clear()
        aversions.clear()
        tripHistory.clear()
        dryAirEnabled = false
        glassAskedAtCount = 0
        glassOfferPending = false
        open = null
        trip = null
        revision++
    }

    fun view(nowMs: Long): LearnerView {
        val min = config().habitMinRepeats
        val habitViews = habits.sortedWith(compareByDescending<Habit> { it.trusted }.thenByDescending { it.seen })
            .map { h ->
                val ready = ready(h) || h.trusted
                HabitView(
                    id = h.id,
                    title = "${h.scope.label} · ${h.phase.label}",
                    items = describe(h.core()),
                    status = when {
                        h.muted -> "먼저 권하지 않아요 (연달아 사양)"
                        h.revised.isNotEmpty() -> "달라진 취향 확인 중"
                        h.trusted -> "알아서 맞춰 드려요"
                        ready -> "다음엔 먼저 여쭤볼게요"
                        else -> "배우는 중 (${h.seen}/$min)"
                    },
                    ready = ready,
                    trusted = h.trusted,
                    muted = h.muted,
                    note = if (h.revised.isNotEmpty() && !h.muted) "취향이 달라지신 것 같아요: ${revisionText(h)} — 다음에 여쭤볼게요" else null,
                )
            }
        val usual = tripHistory.groupingBy { it.first }.eachCount().maxByOrNull { it.value }
            ?.takeIf { tripHistory.size >= 3 }
            ?.let { (band, n) -> "주로 타시는 시간: ${band.label} (${tripHistory.size}번 중 ${n}번)" }
        val t = trip
        val today = when {
            t == null -> null
            t.unusual -> "오늘은 평소와 다른 시간(${t.band.label})이에요 — 여쭙기만 하고, 오늘 조작은 습관에 넣지 않을게요"
            else -> "${t.band.label} 승차 중"
        }
        val recording = open?.let { ep ->
            val changes = ep.netChanges()
            if (changes.isEmpty()) null else "방금 바꾸신 것: ${describe(changes)}"
        }
        return LearnerView(
            habits = habitViews,
            aversions = aversions.values.map { AversionView(it.key, it.text) },
            usual = usual,
            today = today,
            recording = recording,
            dryAir = dryAirEnabled,
        )
    }

    // ---- 저장 · 복원 (한 줄에 하나, 자유 문자열 없음) ----

    fun export(): String = buildString {
        appendLine("NUNCHI-R8 3")
        appendLine("N|$nextId|${b(dryAirEnabled)}|${dryPreferred.ordinal}|$glassAskedAtCount")
        appendLine("B|${tripHistory.joinToString(",") { "${it.first.name}:${b(it.second)}" }}")
        habits.forEach { h ->
            // 최근 관찰: 관찰끼리는 ';', 조작끼리는 ','
            val recent = h.recent.joinToString(";") { obs -> obs.entries.joinToString(",") { (c, v) -> "${c.name}:$v" } }
            val s = h.scope
            appendLine(
                listOf(
                    "H", h.id, s.time.name, s.tempMin.name, s.tempMax.name, s.rain?.let { b(it) } ?: "-", b(s.company),
                    h.phase.name, h.seen, h.accepted, h.rejectedInARow, b(h.trusted), b(h.muted), h.lastSeenMs,
                    h.order.joinToString(",") { it.name }, recent, b(h.strong),
                    pairs(h.revised), pairs(h.revisedFrom),
                ).joinToString("|"),
            )
        }
        aversions.values.forEach { a ->
            appendLine("A|${a.key}|${a.control.name}|${a.disliked}|${a.preferred}|${a.count}|${a.lastMs}")
        }
    }

    /** 저장본을 읽는다. 알아볼 수 없는 줄은 건너뛴다(앱 업데이트로 형식이 바뀌어도 죽지 않게). */
    fun import(text: String) {
        val lines = text.lines()
        if (lines.firstOrNull()?.startsWith("NUNCHI-R8") != true) return
        habits.clear()
        aversions.clear()
        tripHistory.clear()
        lines.drop(1).forEach { line ->
            runCatching {
                val f = line.split("|")
                when (f[0]) {
                    "N" -> {
                        nextId = f[1].toInt()
                        dryAirEnabled = f[2] == "1"
                        dryPreferred = FanDirection.entries[f[3].toInt()]
                        glassAskedAtCount = f[4].toInt()
                    }
                    "B" -> if (f[1].isNotEmpty()) f[1].split(",").forEach { item ->
                        val parts = item.split(":")
                        tripHistory.addLast(TimeBand.valueOf(parts[0]) to (parts.getOrNull(1) == "1"))
                    }
                    "H" -> {
                        val scope = HabitScope(
                            time = TimeBand.valueOf(f[2]),
                            company = f[6] == "1",
                            tempMin = TempBand.valueOf(f[3]),
                            tempMax = TempBand.valueOf(f[4]),
                            rain = when (f[5]) { "1" -> true; "0" -> false; else -> null },
                        )
                        val h = Habit(f[1], scope, TripPhase.valueOf(f[7]))
                        h.seen = f[8].toInt()
                        h.accepted = f[9].toInt()
                        h.rejectedInARow = f[10].toInt()
                        h.trusted = f[11] == "1"
                        h.muted = f[12] == "1"
                        h.lastSeenMs = f[13].toLong()
                        if (f[14].isNotEmpty()) f[14].split(",").forEach { h.order += Control.valueOf(it) }
                        if (f.size > 15 && f[15].isNotEmpty()) {
                            f[15].split(";").forEach { obs -> h.remember(parsePairs(obs)) }
                        }
                        h.strong = f.getOrNull(16) == "1"
                        h.revised = parsePairs(f.getOrNull(17).orEmpty())
                        h.revisedFrom = parsePairs(f.getOrNull(18).orEmpty())
                        habits += h
                    }
                    "A" -> {
                        val a = Aversion(f[1], Control.valueOf(f[2]), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toLong())
                        aversions[a.key] = a
                    }
                }
            }
        }
    }

    // ---- 내부: 승차 ----

    private fun trackTrip(before: CabinState, after: CabinState, signal: VehicleSignal, nowMs: Long): RoutineOutput {
        var out = RoutineOutput.NONE
        when (signal) {
            is VehicleSignal.Door -> {
                lastActivityMs = nowMs
                val opened = signal.open && signal.door !in before.openDoors
                val t = trip
                if (opened && after.parked && signal.door == DoorId.FRONT_LEFT) {
                    if (t == null) {
                        out += startTrip(after, nowMs)
                    } else if (t.droveAtMs != null) {
                        out += endTrip(nowMs) // 달린 뒤 운전석 문이 열림 = 내림
                    }
                }
                trip?.let { tr ->
                    if (opened && after.wiperOn) tr.wetEntry = true
                    if (opened && !tr.boardOffered) tr.boardedAtMs = null
                    if (after.allDoorsClosed && tr.boardedAtMs == null && after.parked) tr.boardedAtMs = nowMs
                }
            }
            is VehicleSignal.SeatOccupancy -> if (signal.seat == SeatId.DRIVER && after.parked) {
                lastActivityMs = nowMs
                if (signal.occupied && trip == null) {
                    out += startTrip(after, nowMs)
                    if (after.allDoorsClosed) trip?.boardedAtMs = nowMs
                } else if (!signal.occupied && trip != null) {
                    out += endTrip(nowMs)
                }
            }
            is VehicleSignal.Gear -> {
                lastActivityMs = nowMs
                if (!signal.parked) {
                    if (trip == null) {
                        out += startTrip(after, nowMs)
                        trip?.boardedAtMs = nowMs
                    }
                    trip?.let { tr -> if (tr.droveAtMs == null) tr.droveAtMs = nowMs }
                }
            }
            is VehicleSignal.Wiper -> if (signal.on && !before.wiperOn) trip?.dryAirDone = false
            else -> Unit
        }
        return out
    }

    private fun startTrip(state: CabinState, nowMs: Long): RoutineOutput {
        val band = TimeBand.of(hour(nowMs))
        val weekend = weekendOf(nowMs)
        // 평일은 평일끼리, 주말은 주말끼리 견준다 — 주말 나들이를 '평소와 다른 날'로 오해하지 않게
        val sameType = tripHistory.filter { it.second == weekend }
        val unusual = sameType.size >= config().usualMinTrips && sameType.count { it.first == band } * 10 < sameType.size
        trip = Trip(nowMs, band, unusual, tempAtStart = state.outsideTempC ?: state.cabinTempC)
        tripHistory.addLast(band to weekend)
        while (tripHistory.size > MAX_TRIPS) tripHistory.removeFirst()
        revision++
        return if (unusual) {
            RoutineOutput.info(nowMs, Routine.R8, "평소와 다른 시간(${band.label})에 승차 — 여쭙기만 하고, 오늘 조작은 습관에 넣지 않음")
        } else {
            RoutineOutput.NONE
        }
    }

    private fun endTrip(nowMs: Long): RoutineOutput {
        val out = open?.let { finalize(it, nowMs) } ?: RoutineOutput.NONE
        open = null
        // 맞춰 드리고 아무것도 더 안 하셨다 = 그대로 좋으셨다(한 번 더 본 것으로)
        trip?.applied?.let { ap ->
            if (!ap.observed) {
                ap.observed = true
                find(ap.habitId)?.let { h ->
                    h.observe(ap.items, implicitFor(h, lastState, ap.items.keys), nowMs)
                    revision++
                }
            }
        }
        trip = null
        lastExternal.clear() // 되돌림은 한 승차 안에서만 짝을 맞춘다
        return out
    }

    private fun hour(nowMs: Long): Int = hourOverride ?: hourOf(nowMs)

    private fun situationOf(state: CabinState, nowMs: Long): Situation = Situation(
        time = TimeBand.of(hour(nowMs)),
        temp = TempBand.of(state.outsideTempC ?: trip?.tempAtStart ?: state.cabinTempC),
        rain = state.wiperOn,
        company = state.occupants >= 2,
    )

    private fun phaseOf(nowMs: Long): TripPhase {
        val t = trip ?: return TripPhase.DRIVING
        val boarded = t.boardedAtMs ?: return TripPhase.BOARDING
        return if (nowMs - boarded <= config().habitBoardingWindowMs) TripPhase.BOARDING else TripPhase.DRIVING
    }

    // ---- 내부: 사람 손 알아보기 ----

    private fun isOwnEcho(control: Control, prev: Int, value: Int, nowMs: Long): Boolean {
        val o = own[control] ?: return false
        if (nowMs > o.untilMs) {
            own.remove(control)
            return false
        }
        if (value == o.target) return true
        // 보낸 직후 잠깐 옛 값이 한 번 더 오는 경우(에뮬레이터)
        if (value == o.from && nowMs - o.atMs <= BOUNCE_MS) return true
        // 창은 몇 단계에 걸쳐 움직인다 — 보낸 값 쪽으로 가는 중이면 반향
        val from = o.from ?: prev
        return control == Control.DRIVER_WINDOW && value != from && value in minOf(from, o.target)..maxOf(from, o.target)
    }

    /** 10초 안에 원래 값으로 되돌렸는가. 창·내외기는 차의 터널 차단·R0와 얽혀 여기서 보지 않는다. */
    private fun detectRevert(control: Control, prev: Int, value: Int, nowMs: Long): Change? {
        if (control == Control.DRIVER_WINDOW || control == Control.RECIRC) return null
        val last = lastExternal[control]
        lastExternal[control] = Change(prev, value, nowMs)
        if (last == null || nowMs - last.atMs > config().revertWindowMs) return null
        if (last.to != prev || last.from != value) return null
        lastExternal.remove(control)
        return last
    }

    private fun recordAversion(control: Control, disliked: Int, preferred: Int, nowMs: Long): RoutineOutput {
        val glass = control == Control.FAN_DIR && FanDirection.entries.getOrNull(disliked)?.defrost == true
        val key = if (glass) GLASS else "${control.name}:$disliked"
        val a = aversions.getOrPut(key) { Aversion(key, control, disliked, preferred, 0, nowMs) }
        a.count++
        a.preferred = preferred
        a.lastMs = nowMs
        revision++
        if (glass && !dryAirEnabled && a.count >= GLASS_ASK_AFTER && a.count >= glassAskedAtCount + GLASS_ASK_AFTER) {
            glassAskedAtCount = a.count
            glassOfferPending = true
            FanDirection.entries.getOrNull(preferred)?.takeIf { !it.defrost }?.let { dryPreferred = it }
        }
        return RoutineOutput.info(nowMs, Routine.R8, "되돌림 감지: ${a.subject} → ${control.describe(preferred)} (${a.count}번째)")
    }

    private fun checkUndo(control: Control, value: Int, nowMs: Long): RoutineOutput {
        val ap = trip?.applied ?: return RoutineOutput.NONE
        if (nowMs - ap.atMs > config().undoAfterApplyMs) return RoutineOutput.NONE
        val target = ap.items[control] ?: return RoutineOutput.NONE
        if (value == target) return RoutineOutput.NONE
        ap.items.remove(control)
        val was = ap.before[control]
        // 원래 값이 아니라 다른 값으로 바꾸심 = '원치 않음'이 아니라 '요즘은 이렇게'일 수 있다 — 묶음이 끝나면 살펴본다
        if (config().habitNoticeDrift && was != null && value != was) {
            return RoutineOutput.info(
                nowMs, Routine.R8,
                "맞춰 드린 '${control.describe(target)}' → 직접 '${control.describe(value)}' (되돌리신 게 아니라 바꾸심 — 취향이 달라졌는지 살펴볼게요)",
            )
        }
        ap.undoneControls += control
        if (ap.undone) return RoutineOutput.NONE // 한 번 맞춘 것에 '원치 않음'은 한 번만
        ap.undone = true
        val h = find(ap.habitId) ?: return RoutineOutput.NONE
        val wasTrusted = h.trusted
        h.trusted = false
        h.rejectedInARow++
        if (h.rejectedInARow >= config().quietAfterRejects) h.muted = true
        revision++
        return RoutineOutput.info(
            nowMs, Routine.R8,
            if (wasTrusted) "곧바로 되돌리심: 알아서 맞춘 '${control.describe(target)}' → '${h.scope.label}' 습관은 다시 여쭤보기로"
            else "곧바로 되돌리심: 맞춰 드린 '${control.describe(target)}' → 다음엔 더 신중하게",
        )
    }

    // ---- 내부: 조작 묶음 ----

    /** 탈 때(다 타고 3분 안)는 쉬엄쉬엄 해도 '탈 때 세팅' 하나로 본다 — 중간에 잠깐 멈췄다고 습관이 조각나지 않게. */
    private fun stillBoarding(ep: OpenEpisode, nowMs: Long): Boolean =
        ep.phase == TripPhase.BOARDING && ep.trip != null && ep.trip === trip && phaseOf(nowMs) == TripPhase.BOARDING

    private fun addToEpisode(control: Control, prev: Int, value: Int, state: CabinState, nowMs: Long): RoutineOutput {
        var out = RoutineOutput.NONE
        val current = open
        if (current != null && nowMs - current.lastMs > config().habitGapMs && !stillBoarding(current, nowMs)) {
            out += finalize(current, nowMs)
        }
        val ep = open
            ?: OpenEpisode(nowMs, situationOf(state, nowMs), phaseOf(nowMs), trip?.unusual == true, trip).also { open = it }
        if (control !in ep.before) ep.before[control] = prev
        ep.after[control] = value
        ep.addedAt[control] = nowMs
        ep.lastMs = nowMs
        return out
    }

    private fun finalize(ep: OpenEpisode, nowMs: Long): RoutineOutput {
        open = null
        val changes = ep.netChanges()

        // 맞춰 드린 뒤 더 하신 것 = 같은 습관의 일부(습관이 조각나지 않게)
        val ap = trip?.applied
        if (ap != null && !ap.observed && ap.phase == ep.phase && ep.startMs >= ap.atMs) {
            val h = find(ap.habitId)
            if (h != null) {
                ap.observed = true
                val all = ap.items + changes
                val drift = noticeDrift(h, all, ap.undoneControls)
                h.observe(all, implicitFor(h, lastState, all.keys), nowMs)
                forgiveAversions(changes)
                revision++
                val notes = buildList {
                    if (changes.isNotEmpty()) add("맞춰 드린 뒤 더하신 조작도 같은 습관으로: ${describe(changes)}")
                    if (drift.isNotEmpty()) add("취향이 달라지신 것 같아요(${revisionText(h)}) — 다음에 '지난번에 바꾸신 대로' 여쭤볼게요")
                }
                return if (notes.isEmpty()) RoutineOutput.NONE else RoutineOutput.info(nowMs, Routine.R8, notes.joinToString(" · "))
            }
        }

        if (changes.isEmpty()) return RoutineOutput.NONE
        if (ep.unusual) {
            return RoutineOutput.info(nowMs, Routine.R8, "평소와 다른 날이라 습관에 넣지 않음: ${describe(changes)}")
        }
        val cfg = config()
        val s = ep.situation
        val candidates = habits.filter { it.phase == ep.phase }
        var note = ""
        val h = candidates.filter { it.scope.matches(s) }
            .maxByOrNull { it.similarity(changes) }
            ?.takeIf { it.similarity(changes) >= SAME_HABIT }
            ?: candidates.filter { !it.scope.matches(s) && it.scope.canWiden(s) }
                .maxByOrNull { it.similarity(changes) }
                ?.takeIf { it.similarity(changes) >= WIDEN_HABIT }
                ?.also { widened ->
                    widened.scope = widened.scope.widen(s)
                    absorbOverlaps(widened)
                    note = " · 날씨가 달라도 같은 조작이라 범위를 넓힘"
                }
            ?: Habit("h${nextId++}", HabitScope.exact(s), ep.phase).also { habits += it }
        val wasReady = ready(h)
        // 조작 셋 이상이 두 번 똑같으면 우연일 리 없다 — 세 번을 기다리지 않는다
        if (h.seen == 1 && !h.strong) {
            val first = h.recent.firstOrNull().orEmpty().entries.map { it.toPair() }.toSet()
            if ((changes.entries.map { it.toPair() }.toSet() intersect first).size >= STRONG_COMBO) h.strong = true
        }
        h.observe(changes, implicitFor(h, lastState, changes.keys), nowMs)
        forgiveAversions(changes)
        trimHabits()
        revision++
        val message = if (!wasReady && ready(h)) {
            val why = if (h.seen < cfg.habitMinRepeats) " — 조작 ${STRONG_COMBO}개 이상이 두 번 같아 우연이 아니라고 판단" else ""
            "습관으로 인식: ${h.scope.label} · ${h.phase.label} — ${describe(h.core())} (다음엔 먼저 여쭤볼게요)$why$note"
        } else {
            "조작 묶음 기억: ${describe(changes)} (${h.scope.label} · ${h.phase.label}, ${h.seen}번째)$note"
        }
        return RoutineOutput.info(nowMs, Routine.R8, message)
    }

    /** 곧바로 되돌렸던 값이라도 다시 직접 맞추고 그대로 두셨으면 불편해하신 게 아니었다 — 한 번의 실수로 그 값을 영영 막지 않게. */
    private fun forgiveAversions(kept: Map<Control, Int>) {
        kept.forEach { (c, v) -> if (aversions.remove("${c.name}:$v") != null) revision++ }
    }

    /**
     * 맞춰 드린 뒤 습관과 다른 값으로 바꾸신 것(원래대로 되돌린 건 빼고) = 달라진 취향일지 모른다.
     * 말없이 따라 바꾸지 않고(그날만의 일일 수 있으니), 옛 습관을 고집하지도 않는다 — 다음에 한 번 여쭙는다.
     */
    private fun noticeDrift(h: Habit, all: Map<Control, Int>, undone: Set<Control>): Map<Control, Int> {
        if (!config().habitNoticeDrift) return emptyMap()
        val core = h.core()
        val drift = all.filter { (c, v) -> c !in undone && core[c] != null && core[c] != v }
        if (drift.isEmpty()) return drift
        // 처음 바꾸시기 전 값을 기억해 둔다("3단 → 1단"). 원래 값으로 돌아오셨으면 달라진 게 아니다.
        val from = h.revisedFrom.toMutableMap()
        drift.keys.forEach { c -> if (c !in from) core[c]?.let { from[c] = it } }
        val revised = (h.revised + drift).filter { (c, v) -> from[c] != v }
        h.revised = revised
        h.revisedFrom = from.filterKeys { it in revised }
        return drift.filterKeys { it in revised }
    }

    /** "운전석 열선 3단 → 1단 · 설정온도 26℃ → 24℃" */
    private fun revisionText(h: Habit): String = h.revised.entries.joinToString(" · ") { (c, v) ->
        val old = h.revisedFrom[c]
        if (old == null) c.describe(v) else arrow(c.describe(old), c.describe(v))
    }

    /** 이번엔 손대지 않았지만 이미 이 습관대로 돼 있던 설정(설정온도·무드등 색처럼 남아 있는 값) = '그대로 두셨다'. */
    private fun implicitFor(h: Habit, state: CabinState, exclude: Set<Control>): Map<Control, Int> =
        h.known.filter { it !in exclude }.mapNotNull { c ->
            val best = h.bestValue(c) ?: return@mapNotNull null
            if (state.read(c) == best) c to best else null
        }.toMap()

    /** 넓어진 습관이 덮게 된, 비슷한 다른 습관은 하나로 합친다. */
    private fun absorbOverlaps(h: Habit) {
        val others = habits.filter { o ->
            o !== h && o.phase == h.phase && o.scope.time == h.scope.time && o.scope.company == h.scope.company &&
                o.scope.tempMin.ordinal >= h.scope.tempMin.ordinal && o.scope.tempMax.ordinal <= h.scope.tempMax.ordinal &&
                (h.scope.rain == null || o.scope.rain == h.scope.rain) &&
                o.similarityTo(h) >= WIDEN_HABIT
        }
        others.forEach { o ->
            h.order += o.order
            // 둘의 최근 관찰을 섞어 최근 것만 남긴다
            val merged = (h.recent + o.recent).takeLast(RECENT)
            h.recent.clear()
            merged.forEach { h.remember(it) }
            h.seen += o.seen
            h.accepted += o.accepted
            h.trusted = h.trusted || o.trusted
            h.muted = h.muted && o.muted
            h.lastSeenMs = maxOf(h.lastSeenMs, o.lastSeenMs)
            habits.remove(o)
        }
    }

    private fun trimHabits() {
        while (habits.size > MAX_HABITS) {
            val victim = habits.filter { !it.trusted }.minByOrNull { it.seen * 1_000_000_000L + it.lastSeenMs / 1_000 } ?: return
            habits.remove(victim)
        }
    }

    private fun find(id: String?): Habit? = habits.firstOrNull { it.id == id }

    // ---- 내부: 제안 ----

    private fun nextOffer(state: CabinState, nowMs: Long): RoutineOutput {
        val t = trip ?: return glassOffer()
        // 1) 다 탄 뒤(문이 모두 닫히고 잠시 뒤) — 탈 때 습관
        val boarded = t.boardedAtMs
        if (!t.boardOffered && boarded != null && nowMs - boarded >= BOARD_SETTLE_MS && state.allDoorsClosed) {
            t.boardOffered = true
            val s = situationOf(state, nowMs)
            if (t.unusual) {
                similarHabit(s, state)?.let { h ->
                    t.offered += h.id
                    return RoutineOutput(offer = habitOffer(h, s, state, unusual = true))
                }
                return RoutineOutput(commands = listOf(CareCommand.Notice("평소와 다른 시간이네요. 오늘은 따로 맞춰 두지 않을게요.")))
            }
            habitFor(s, TripPhase.BOARDING, state, t)?.let { h ->
                t.offered += h.id
                return RoutineOutput(offer = habitOffer(h, s, state, unusual = false))
            }
        }
        // 2) 달리는 중 습관(비가 오기 시작하는 등 상황이 바뀌면 그때)
        val drove = t.droveAtMs
        if (!t.unusual && !state.parked && drove != null && nowMs - drove >= config().habitDriveDelayMs) {
            val s = situationOf(state, nowMs)
            habitFor(s, TripPhase.DRIVING, state, t)?.let { h ->
                t.offered += h.id
                return RoutineOutput(offer = habitOffer(h, s, state, unusual = false))
            }
        }
        return glassOffer()
    }

    /** 여쭐 만큼 배웠나: 같은 상황에서 3번(조작 셋 이상이 똑같은 묶음이면 2번). */
    private fun ready(h: Habit): Boolean {
        val min = config().habitMinRepeats
        return !h.muted && (h.seen >= min || (h.strong && h.seen >= min - 1))
    }

    private fun habitFor(s: Situation, phase: TripPhase, state: CabinState, t: Trip): Habit? =
        habits.filter { it.phase == phase && it.scope.matches(s) && it.id !in t.offered && ready(it) && diff(it, state).isNotEmpty() }
            .maxByOrNull { it.seen + it.accepted }

    /** 평소와 다른 시간 — 날씨·비·동승이 같은 다른 시간대의 '탈 때' 습관(묻기만 한다). */
    private fun similarHabit(s: Situation, state: CabinState): Habit? =
        habits.filter {
            it.phase == TripPhase.BOARDING && it.scope.time != s.time && it.scope.matchesIgnoringTime(s) &&
                (ready(it) || (it.trusted && !it.muted)) && diff(it, state).isNotEmpty()
        }.maxByOrNull { it.seen + it.accepted }

    /** 지금 상태와 다른 핵심 조작만(이미 그렇게 돼 있는 건 빼고, 바로 되돌렸던 값도 뺀다). 달라진 취향은 달라진 값으로. */
    private fun diff(h: Habit, state: CabinState): Map<Control, Int> {
        // 이번에 타서 방금 직접 맞추신 것은 건드리지 않는다 — 손이 먼저 간 값을 차가 덮어쓰면 안 된다
        val touched = open?.after?.keys.orEmpty()
        return h.target().filter { (c, v) -> state.read(c) != v && "${c.name}:$v" !in aversions && c !in touched }
    }

    private fun habitOffer(h: Habit, s: Situation, state: CabinState, unusual: Boolean): Offer {
        // 지난번에 맞춰 드린 걸 다른 값으로 바꾸셨다 — 이번엔 그 값으로, 알아서 하던 습관이라도 한 번 여쭙는다
        val drifted = !unusual && h.revised.isNotEmpty()
        val ask = when {
            unusual -> {
                val sooner = s.time.ordinal < h.scope.time.ordinal
                val usual = Situation(h.scope.time, s.temp, s.rain, h.scope.company).phrase
                "평소보다 ${if (sooner) "이른" else "늦은"} 시간이네요. ${usual}처럼 맞춰 드릴까요?"
            }
            drifted -> "지난번에 바꾸신 대로 맞춰 드릴까요?"
            else -> "${s.whenPhrase} 늘 하시던 대로 맞춰 드릴까요?"
        }
        val items = diff(h, state)
        return Offer(
            action = CareAction.HABIT,
            key = h.id,
            ask = ask,
            detail = if (drifted) "${describe(items)} (달라진 것: ${revisionText(h)})" else describe(items),
            changes = items,
            tell = "${s.phrase} 세팅으로 맞춰 둘게요.",
            reason = when {
                unusual -> "평소와 다른 시간(${s.time.label}) · 비슷한 습관: ${h.scope.label}"
                drifted -> "${h.scope.label} — 맞춰 드린 뒤 바꾸심: ${revisionText(h)}"
                else -> "${h.scope.label} · ${h.phase.label} — 같은 조작 ${h.seen}번"
            },
            autoOk = !unusual && !drifted,
            // 화면에서 이 습관을 지우셨으면(잊기·모두 지우기) 여쭙던 것도 거둔다
            stillValid = { st -> habits.any { it === h } && diff(h, st).isNotEmpty() },
            apply = { st, now -> applyHabit(h, st, now) },
        )
    }

    private fun applyHabit(h: Habit, state: CabinState, nowMs: Long): RoutineOutput {
        val items = diff(h, state)
        if (items.isEmpty()) return RoutineOutput.NONE
        // 맞추기 직전 값 — 나중에 손대시면 '원래대로 되돌림(원치 않음)'인지 '다른 값으로 바꿈(달라진 취향)'인지 가른다
        val before = items.keys.mapNotNull { c -> state.read(c)?.let { c to it } }.toMap()
        trip?.applied = Applied(h.id, phaseOf(nowMs), items.toMutableMap(), nowMs, before)
        if (Control.DRIVER_WINDOW in items) onTakesWindow()
        val label = Situation(h.scope.time, lastState.let { TempBand.of(it.outsideTempC ?: trip?.tempAtStart ?: it.cabinTempC) }, lastState.wiperOn, h.scope.company).phrase
        return RoutineOutput(
            commands = items.map { (c, v) -> c.command(v) },
            events = listOf(CareEvent(nowMs, Routine.R8, CareKind.HABIT, "$label 세팅: ${describe(items)}")),
        )
    }

    private fun glassOffer(): RoutineOutput {
        if (!glassOfferPending) return RoutineOutput.NONE
        glassOfferPending = false
        val count = aversions[GLASS]?.count ?: return RoutineOutput.NONE
        val pref = dryPreferred
        return RoutineOutput(
            offer = Offer(
                action = CareAction.DRY_AIR,
                ask = "앞유리 쪽 바람이 불편하신 것 같아요. 다음부터는 김이 서리기 전에 습기를 미리 빼 둘까요? 바람은 ${Control.withRo(pref.label)} 보낼게요.",
                tell = null,
                reason = "앞유리로 향한 바람을 ${count}번 곧바로 되돌리심",
                habit = true,
                stillValid = { GLASS in aversions },
                apply = { _, now ->
                    dryAirEnabled = true
                    revision++
                    RoutineOutput.info(now, Routine.R8, "앞유리 쪽 바람을 불편해하심 → 비 오는 날 김 서리기 전 습기 미리 빼기(바람은 ${pref.label}) 허락받음")
                },
            ),
        )
    }

    /**
     * 비 + 서늘함 + (젖은 채 탐·여럿·내기) = 김이 서리기 직전. 유리에 바람을 쏘기 전에 실내 공기를 먼저 말린다:
     * 에어컨(제습) + 외기, 바람은 좋아하시는 쪽. 차가 김을 막으려 이미 유리로 보내고 있으면 그건 그대로 둔다(시야가 먼저).
     */
    private fun dryAirOffer(state: CabinState, trusted: Boolean): Offer? {
        if (!dryAirEnabled || !trusted) return null
        val t = trip ?: return null
        if (t.dryAirDone || !state.wiperOn || !state.allDoorsClosed) return null
        if (t.boardedAtMs == null && state.parked) return null
        val temp = state.outsideTempC ?: t.tempAtStart ?: state.cabinTempC ?: 15f
        if (temp > DRY_AIR_MAX_C) return null
        if (!(t.wetEntry || state.occupants >= 2 || state.recirculating)) return null
        t.dryAirDone = true
        val why = buildList {
            add("비")
            if (t.wetEntry) add("젖은 채 탐")
            if (state.occupants >= 2) add("${state.occupants}명")
            if (state.recirculating) add("내기순환")
        }.joinToString(" · ")
        return Offer(
            action = CareAction.DRY_AIR,
            ask = "김이 서리기 전에 습기를 미리 뺄까요?",
            tell = "김이 서리기 전에 습기를 미리 뺄게요.",
            reason = why,
            apply = { st, now -> dryAir(st, now, why) },
        )
    }

    private fun dryAir(state: CabinState, nowMs: Long, why: String): RoutineOutput {
        val pref = dryPreferred
        val dir = state.fanDirection
        val commands = buildList<CareCommand> {
            if (state.acOn != true) add(CareCommand.SetAc(true))
            if (state.recirculating) add(CareCommand.SetRecirculation(false))
            if (dir == null || (!dir.defrost && dir != pref)) add(CareCommand.SetFanDirection(pref))
        }
        val note = if (dir?.defrost == true) " · 앞유리 바람은 차가 김을 막는 중이라 그대로" else " · 바람 ${pref.label}"
        return RoutineOutput(
            commands = commands,
            events = listOf(CareEvent(nowMs, Routine.R8, CareKind.DRY_AIR, "$why → 김 서리기 전 에어컨으로 습기 빼기 · 외기$note")),
        )
    }

    private fun describe(items: Map<Control, Int>) = items.entries.joinToString(" · ") { (c, v) -> c.describe(v) }

    // ---- 자료 ----

    /**
     * 배운 습관 하나. 핵심 조작은 '최근 [RECENT]번'만 보고 정한다 — 오래된 습관도 새로 더하신 조작을 몇 번 만에 받아들이고,
     * 더는 안 하시는 조작은 몇 번 만에 놓는다(평생 누적으로 세면 습관이 굳어 버린다).
     */
    private class Habit(val id: String, var scope: HabitScope, val phase: TripPhase) {
        /** 최근 관찰(오래된 것부터). 관찰 하나 = 그때 바꾼 것 + 이미 그대로였던 것. */
        val recent = ArrayDeque<Map<Control, Int>>()
        /** 처음 나온 순서(말할 때 이 순서로). */
        val order = linkedSetOf<Control>()
        var seen = 0
        var accepted = 0
        var rejectedInARow = 0
        var trusted = false
        var muted = false
        var lastSeenMs = 0L
        /** 처음 두 번이 조작 셋 이상 똑같았다(우연이 아님). */
        var strong = false

        /** 맞춰 드린 뒤 다른 값으로 바꾸신 것 — 취향이 달라졌는지 다음에 한 번 여쭙는다. [revisedFrom] = 바꾸시기 전 값. */
        var revised: Map<Control, Int> = emptyMap()
        var revisedFrom: Map<Control, Int> = emptyMap()

        /** 맞출 값: 핵심 조작 + 달라진 것(달라진 것은 여쭐 때만 쓰인다 — 달라진 게 있으면 알아서 하던 습관도 여쭙는다). */
        fun target(): Map<Control, Int> = core() + revised

        /** 달라진 취향을 허락받음 — 최근 관찰을 새 값으로 고쳐 한 번에 받아들인다(옛 값이 다수결로 버티지 않게). */
        fun commitRevision() {
            if (revised.isEmpty()) return
            val updated = recent.map { it + revised }
            recent.clear()
            updated.forEach { remember(it) }
            order += revised.keys
            revised = emptyMap()
            revisedFrom = emptyMap()
        }

        /** 한 번 더 봄: 바꾼 것([changes]) + 이미 그대로였던 것([implicit]). */
        fun observe(changes: Map<Control, Int>, implicit: Map<Control, Int>, atMs: Long) {
            seen++
            lastSeenMs = atMs
            order += changes.keys
            order += implicit.keys
            remember(implicit + changes)
        }

        fun remember(items: Map<Control, Int>) {
            recent.addLast(items)
            while (recent.size > RECENT) recent.removeFirst()
        }

        val known: Set<Control> get() = order.filter { c -> recent.any { c in it } }.toSet()

        fun bestValue(c: Control): Int? = recent.mapNotNull { it[c] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        /** 핵심 조작: 최근 관찰의 60% 이상에 나온 조작(값은 가장 많이 나온 값). */
        fun core(): Map<Control, Int> {
            val need = ceil(recent.size * CORE_SHARE).toInt().coerceAtLeast(1)
            return order.mapNotNull { c ->
                val best = recent.mapNotNull { it[c] }.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return@mapNotNull null
                if (best.value >= need) c to best.key else null
            }.toMap()
        }

        fun similarity(changes: Map<Control, Int>): Double {
            val a = core().entries.map { it.toPair() }.toSet()
            val b = changes.entries.map { it.toPair() }.toSet()
            val union = (a union b).size
            return if (union == 0) 0.0 else (a intersect b).size.toDouble() / union
        }

        fun similarityTo(other: Habit): Double = similarity(other.core())
    }

    private class Aversion(
        val key: String,
        val control: Control,
        val disliked: Int,
        var preferred: Int,
        var count: Int,
        var lastMs: Long,
    ) {
        val subject: String get() = if (key == GLASS) "앞유리 쪽 바람" else control.describe(disliked)
        val text: String get() = "$subject — ${count}번 곧바로 되돌리심"
    }

    private class Trip(val startMs: Long, var band: TimeBand, var unusual: Boolean, val tempAtStart: Float?) {
        var boardedAtMs: Long? = null
        var boardOffered = false
        var droveAtMs: Long? = null
        val offered = mutableSetOf<String>()
        var wetEntry = false
        var dryAirDone = false
        var applied: Applied? = null
    }

    private class OpenEpisode(
        val startMs: Long,
        val situation: Situation,
        val phase: TripPhase,
        val unusual: Boolean,
        val trip: Trip?,
    ) {
        val before = linkedMapOf<Control, Int>()
        val after = linkedMapOf<Control, Int>()
        val addedAt = mutableMapOf<Control, Long>()
        var lastMs = startMs

        fun netChanges(): Map<Control, Int> = after.filter { (c, v) -> before[c] != v }
    }

    /** 이번 승차에 맞춰 드린 습관: 무엇을(되돌린 건 빠짐), 그 뒤 더 하신 것까지 한 번의 관찰로 센다. */
    private class Applied(
        val habitId: String,
        val phase: TripPhase,
        val items: MutableMap<Control, Int>,
        val atMs: Long,
        /** 맞추기 직전 값(되돌림과 바꿈을 가르는 기준). */
        val before: Map<Control, Int> = emptyMap(),
    ) {
        var observed = false
        var undone = false
        /** 원래 값으로 되돌리신 것 — '달라진 취향'으로 보지 않는다. */
        val undoneControls = mutableSetOf<Control>()
    }

    private class OwnCommand(val target: Int, val from: Int?, val atMs: Long, val untilMs: Long)

    private data class Change(val from: Int, val to: Int, val atMs: Long)

    companion object {
        private const val GLASS = "glass"
        private const val GLASS_ASK_AFTER = 2
        private const val SAME_HABIT = 0.5
        /** 날씨가 다른 날의 묶음으로 범위를 넓히려면 더 비슷해야 한다. */
        private const val WIDEN_HABIT = 0.6
        private const val CORE_SHARE = 0.6
        /** 핵심 조작을 정할 때 보는 최근 관찰 수(3번 중 2번, 5번 중 3번). */
        private const val RECENT = 5
        /** 이만큼 많은 조작이 두 번 똑같으면 두 번 만에 알아본다. */
        private const val STRONG_COMBO = 3
        private const val BOARD_SETTLE_MS = 2_000L
        private const val ECHO_MS = 3_000L
        private const val WINDOW_ECHO_MS = 8_000L
        private const val BOUNCE_MS = 1_500L
        private const val GUARD_PAIR_MS = 3_000L
        private const val DRY_AIR_MAX_C = 20f
        private const val MAX_TRIPS = 60
        private const val MAX_HABITS = 30

        /** 오토 공조가 켜져 있으면 차가 스스로 바꾸는 것 — 사람 습관으로 배우지 않는다. */
        private val AUTO_DRIVEN = setOf(Control.FAN, Control.FAN_DIR)

        private fun b(value: Boolean) = if (value) "1" else "0"

        /** "SEAT_HEAT:1,TEMP:240" */
        private fun pairs(items: Map<Control, Int>) = items.entries.joinToString(",") { (c, v) -> "${c.name}:$v" }

        private fun parsePairs(text: String): Map<Control, Int> =
            if (text.isEmpty()) emptyMap()
            else text.split(",").associate { item ->
                val (c, v) = item.split(":")
                Control.valueOf(c) to v.toInt()
            }

        /** "운전석 열선 3단" → "운전석 열선 1단" = "운전석 열선 3단 → 1단"(같은 앞말은 한 번만). */
        private fun arrow(from: String, to: String): String {
            val a = from.split(' ')
            val b = to.split(' ')
            var n = 0
            while (n < a.size - 1 && n < b.size - 1 && a[n] == b[n]) n++
            return if (n == 0) "$from → $to" else "$from → ${b.drop(n).joinToString(" ")}"
        }

        fun systemHour(ms: Long): Int = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).hour

        fun systemWeekend(ms: Long): Boolean =
            Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
