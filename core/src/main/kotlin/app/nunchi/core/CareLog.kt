package app.nunchi.core

enum class Routine(val code: String, val title: String) {
    R0("R0", "1cm의 배려"),
    R1("R1", "숨 쉬는 차"),
    R2("R2", "마지막 20km"),
    R6("R6", "흐름 환기"),
    R8("R8", "배우는 눈치"),
}

/**
 * NUNCHI가 하는 일 하나하나. 기본은 '먼저 여쭙고(제안) 좋다고 하면 한다'.
 * 사용자가 "다음부턴 알아서"라고 허락한 일만 묻지 않고 바로 한다(언제든 해제).
 */
enum class CareAction(val routine: Routine, val label: String) {
    WINDOW_CRACK(Routine.R0, "탈 때 창 1cm"),
    HEAT_PURGE(Routine.R0, "더운 날 30초 열기 빼기"),
    FRESH_AIR(Routine.R1, "공기 한 번 바꾸기"),
    OUTSIDE_AIR(Routine.R1, "여럿 타면 외기로"),
    ENERGY_SAVE(Routine.R2, "에어컨 쉬고 바람 줄이기"),
    VENTILATION(Routine.R6, "바람길 열기"),
    /** 배운 습관 맞추기. 허락은 습관마다 따로([HabitLearner]가 기억) — 여기 trusted 목록엔 넣지 않는다. */
    HABIT(Routine.R8, "늘 하시던 대로 맞추기"),
    DRY_AIR(Routine.R8, "김 서리기 전 습기 빼기"),
}

/**
 * 제안 한 건 — NUNCHI의 기본 개입 방식.
 * 허락받지 않은 일은 [ask]로 여쭙고 '네'를 기다린다(② 제안). 1분 안에 답이 없으면 조용히 거둔다.
 * 미리 허락받은 일은 [tell]로 짧게 알리고 바로 [apply]한다(③ 자동 실행). [autoOk]가 false면 허락받았어도 묻는다(약한 신호).
 * [habit] = 지금 할 일이 아니라 "앞으로 이렇게 할까요?"를 여쭙는 제안(R0 1cm처럼 물을 틈이 없는 일). '네' = 허락.
 * [stillValid]가 false가 되면(상황이 바뀌면) 대답을 기다리지 않고 거둔다.
 * [detail] = 화면에만 덧붙이는 내용(무엇을 바꿀지). 음성은 [ask]만 짧게.
 * [key] = 배운 습관 하나를 가리키는 이름(R8) — 허락·사양을 습관마다 따로 기억한다.
 */
class Offer(
    val action: CareAction,
    val ask: String,
    val tell: String?,
    val reason: String,
    val voice: Boolean = true,
    val habit: Boolean = false,
    val autoOk: Boolean = true,
    val detail: String? = null,
    val key: String? = null,
    /** 이 제안이 바꿀 설정들(R8 습관 — 화면·시뮬레이션용). */
    val changes: Map<Control, Int> = emptyMap(),
    val stillValid: (CabinState) -> Boolean = { true },
    val apply: (state: CabinState, nowMs: Long) -> RoutineOutput,
) {
    val routine: Routine get() = action.routine
}

/** [counted] = '오늘의 1cm' 카드에 세는 배려인지(단순 기록은 세지 않음). */
enum class CareKind(val label: String, val counted: Boolean = true) {
    WINDOW_CRACK("창 1cm"),
    HEAT_PURGE("열기 배출"),
    FRESH_AIR("공기 교체"),
    FRESH_AIR_SUGGEST("외기 기본 전환"),
    ENERGY_SAVE("에너지 코칭"),
    RANGE_ALERT("충전 계획 안내"),
    TIRE_ALERT("타이어 점검 안내"),
    VENTILATION("흐름 환기"),
    VENT_SUGGEST("바람길 제안"),
    HABIT("습관 맞춤"),
    DRY_AIR("습기 미리 빼기"),
    INFO("기록", counted = false),
}

data class CareEvent(
    val atMs: Long,
    val routine: Routine,
    val kind: CareKind,
    val message: String,
)

/** 루틴 한 번의 결과: 차에 보낼 명령 + 남길 기록 + (있으면) 여쭐 제안 하나. */
data class RoutineOutput(
    val commands: List<CareCommand> = emptyList(),
    val events: List<CareEvent> = emptyList(),
    val offer: Offer? = null,
) {
    operator fun plus(other: RoutineOutput) =
        RoutineOutput(commands + other.commands, events + other.events, offer ?: other.offer)

    companion object {
        val NONE = RoutineOutput()
        fun info(atMs: Long, routine: Routine, message: String) =
            RoutineOutput(events = listOf(CareEvent(atMs, routine, CareKind.INFO, message)))
    }
}

/** '오늘의 1cm' — 운전 중엔 티 내지 않고, 하루 끝에 조용히 보고하는 카드. */
data class DailyReport(
    val counts: Map<CareKind, Int>,
    val peakCo2Ppm: Int?,
) {
    val total: Int get() = counts.values.sum()

    val summary: String
        get() = if (total == 0) {
            "오늘은 조용히 지켜보기만 했어요"
        } else {
            counts.entries.joinToString(" · ") { (kind, n) -> "${kind.label} ${n}회" }
        }

    companion object {
        val EMPTY = DailyReport(emptyMap(), null)
    }
}

class CareLog(private val capacity: Int = 300) {
    private val items = ArrayDeque<CareEvent>()

    val events: List<CareEvent> get() = items.toList()

    fun add(event: CareEvent) {
        items.addLast(event)
        while (items.size > capacity) items.removeFirst()
    }

    fun clear() = items.clear()

    fun report(sinceMs: Long = Long.MIN_VALUE, peakCo2Ppm: Int? = null): DailyReport {
        val counts = items
            .filter { it.atMs >= sinceMs && it.kind.counted }
            .groupingBy { it.kind }
            .eachCount()
        val ordered = CareKind.entries
            .filter { (counts[it] ?: 0) > 0 }
            .associateWith { counts.getValue(it) }
        return DailyReport(ordered, peakCo2Ppm)
    }
}
