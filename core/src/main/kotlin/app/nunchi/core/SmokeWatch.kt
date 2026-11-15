package app.nunchi.core

import kotlin.math.ceil
import kotlin.math.max

/**
 * R6 '먼저 알아채기' — 연기·냄새·흡연 신호를 보고 흐름 환기를 먼저 권한다(메뉴에서 찾게 하지 않는다).
 *
 * 신호가 얼마나 확실한지에 맞춰 개입 단계를 고른다.
 * - 강한 신호 → 음성으로 여쭙기("바람길 열어 드릴까요?"). 미리 허락받았으면 바로 켠다.
 *   · 흡연 손동작 — 실내 카메라(센서 계층 L2). 출구는 그 사람 쪽 창
 *   · 실내 미세먼지 급상승 — 공기질 센서를 단 차. 출구는 최근에 살짝 연 창(없으면 운전석)
 *   (둘 다 지금 Pleos SDK엔 읽는 API가 없어 데모에선 가상 센서로 넣는다)
 * - 약한 신호 → 말없이 화면에만 제안. 허락받았어도 묻는다.
 *   · 달리는 중 창을 손으로 '살짝' 엶 — 담배 피우기 전 흔한 행동. 지금 SDK(창 위치)로 바로 잡힌다(L0)
 *   · 곧바로 더 열거나 다시 닫으면 그냥 바람 쐬기였다고 보고 거둔다
 * 말은 돌려서 한다: "담배" 대신 "바람길" — 동승자 앞에서 민망하지 않게.
 */
class SmokeWatch(private val config: () -> CareConfig) {

    enum class Strength { WEAK, STRONG }

    enum class Source { WINDOW_CRACK, GESTURE, PM25 }

    data class Detection(val outlet: Pane, val strength: Strength, val source: Source, val reason: String)

    private var weakUsedThisTrip = false
    private var lastStrongMs = Long.MIN_VALUE / 2
    private var suppressedNoted = false

    /** 쉬는 중이라 넘긴 강한 신호를 한 번만 기록으로 남기려고 엔진이 가져간다. */
    private var suppressed: String? = null
    private var lastCrack: Pair<Pane, Long>? = null
    private var ownWindowMoveUntilMs = Long.MIN_VALUE / 2
    private var windowsQuietUntilMs = Long.MIN_VALUE / 2

    /** 달리다 닫힌 창의 직전 위치 — 같은 높이로 다시 열리는 건(차의 터널 차단 해제 등) '새로 살짝 엶'이 아니다. */
    private val closedWhileDriving = mutableMapOf<Pane, Pair<Int, Long>>()
    private val pm = ArrayDeque<Pair<Long, Int>>()

    /** '살짝' 연 창 = 최대 개방의 30% 이하(에뮬레이터 0..10 → 1~3단계). */
    fun smallLimit(): Int {
        val cfg = config()
        return max(1, ceil(cfg.windowMax * cfg.smallOpeningRatio).toInt())
    }

    /** NUNCHI가 창을 움직인 직후의 창 신호는 사람 손이 아니다(메아리·움직이는 중 값). */
    fun noteOwnWindowCommand(nowMs: Long) {
        ownWindowMoveUntilMs = nowMs + ECHO_GUARD_MS
    }

    /** 차의 터널 차단 중(그리고 끝난 직후)엔 창 신호를 보지 않는다. */
    fun quietWindowsUntil(untilMs: Long) {
        windowsQuietUntilMs = untilMs
    }

    fun resetDay() {
        weakUsedThisTrip = false
        lastStrongMs = Long.MIN_VALUE / 2
        suppressedNoted = false
        suppressed = null
    }

    /** 강한 신호로 여쭌 뒤(사양·무응답) 다시 여쭙기까지 남은 시간 — 재촉하지 않으려고 쉬는 중이면 0보다 크다. */
    fun cooldownLeftMs(nowMs: Long): Long = (lastStrongMs + config().ventSuggestCooldownMs - nowMs).coerceAtLeast(0L)

    /** '네'라고 하셨거나 허락받아 바로 켰다 — 쉬는 건 사양·무응답 뒤에만. 다음 신호엔 다시 살핀다. */
    fun onAccepted() {
        lastStrongMs = Long.MIN_VALUE / 2
        suppressedNoted = false
    }

    fun takeSuppressed(): String? = suppressed.also { suppressed = null }

    /** [venting] = 이미 흐름 환기 중 — 새로 권할 일도, 쉬는 시간을 다시 잴 일도 없다. */
    fun onSignal(before: CabinState, after: CabinState, signal: VehicleSignal, nowMs: Long, venting: Boolean = false): Detection? {
        if (signal is VehicleSignal.Gear && !signal.parked && before.parked) weakUsedThisTrip = false
        Pane.moved(signal)?.let { (pane, position) -> return onWindow(before, after, pane, position, nowMs) }
        return when (signal) {
            is VehicleSignal.SmokingGesture ->
                strong(Pane.of(signal.seat), Source.GESTURE, "${signal.seat.label} 흡연 손동작(실내 카메라)", nowMs, venting)
            is VehicleSignal.CabinPm25 -> pmRise(signal.ugm3, nowMs)?.let { rise ->
                strong(recentCrack(nowMs) ?: Pane.DRIVER, Source.PM25,
                    "실내 미세먼지 ${signal.ugm3}㎍/㎥ (2분 새 +$rise)", nowMs, venting)
            }
            else -> null
        }
    }

    private fun onWindow(before: CabinState, after: CabinState, pane: Pane, position: Int, nowMs: Long): Detection? {
        val was = before.windowAt(pane) ?: 0
        if (!after.parked && was > 0 && position <= 0) closedWhileDriving[pane] = was to nowMs
        if (nowMs < ownWindowMoveUntilMs || nowMs < windowsQuietUntilMs) return null
        if (was > 0 || position !in 1..smallLimit()) return null
        val closed = closedWhileDriving.remove(pane)
        if (closed != null && closed.first == position && nowMs - closed.second <= REOPEN_SAME_MS) return null
        lastCrack = pane to nowMs
        if (after.parked || after.wiperOn || weakUsedThisTrip) return null
        weakUsedThisTrip = true
        return Detection(pane, Strength.WEAK, Source.WINDOW_CRACK, "주행 중 ${pane.label} 창을 손으로 살짝 엶")
    }

    private fun strong(outlet: Pane, source: Source, reason: String, nowMs: Long, venting: Boolean): Detection? {
        if (venting) return null
        val left = cooldownLeftMs(nowMs)
        if (left > 0) {
            // 조금 전에 여쭸다 — 재촉하지 않는다. 왜 조용한지는 한 번만 기록으로 남긴다
            if (!suppressedNoted) {
                suppressedNoted = true
                suppressed = "$reason — 조금 전에 여쭤봐서 ${ceil(left / 60_000.0).toInt()}분 동안은 다시 권하지 않아요"
            }
            return null
        }
        lastStrongMs = nowMs
        suppressedNoted = false
        return Detection(outlet, Strength.STRONG, source, reason)
    }

    private fun recentCrack(nowMs: Long): Pane? =
        lastCrack?.takeIf { nowMs - it.second <= RECENT_CRACK_MS }?.first

    /** 2분 안의 가장 낮은 값보다 크게 뛰고, '매우 나쁨' 이상이면 연기로 본다. 뛴 폭을 돌려준다. */
    private fun pmRise(value: Int, nowMs: Long): Int? {
        val cfg = config()
        while (pm.isNotEmpty() && nowMs - pm.first().first > PM_WINDOW_MS) pm.removeFirst()
        val base = pm.minOfOrNull { it.second }
        pm.addLast(nowMs to value)
        if (base == null) return null
        val rise = value - base
        return if (value >= cfg.smokePm25 && rise >= cfg.smokePm25Rise) rise else null
    }

    private companion object {
        const val ECHO_GUARD_MS = 3_000L
        const val PM_WINDOW_MS = 2 * 60_000L
        const val RECENT_CRACK_MS = 3 * 60_000L
        const val REOPEN_SAME_MS = 15 * 60_000L
    }
}
