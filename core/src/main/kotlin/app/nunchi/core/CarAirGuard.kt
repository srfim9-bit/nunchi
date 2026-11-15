package app.nunchi.core

/**
 * 차가 스스로 하는 '외부 공기 유입 방지 제어'(현대·기아, 사양 적용 차종)를 알아본다.
 * 내비 지도와 속도로 터널(또는 외부 공기 차단 구간) 약 7초 전에 열린 창을 닫고 내기로 바꾸며, 지나면 창을 원래 위치로 연다.
 *
 * NUNCHI는 이 일을 따라 하지 않는다. 대신 이걸 '사람 손'으로 오해하지 않도록 알아보고, 그동안 창·내외기에서 손을 뗀다.
 * - 시작: 달리는 중 '내기 켜짐'과 '열린 창 닫힘'이 3초 안에 함께 일어남(NUNCHI가 보낸 명령 직후는 제외)
 * - 끝: 차가 닫았던 창을 다시 열거나, 외기로 돌아오거나, 정차하거나, 10분이 지남(국내 최장급 터널도 시속 80km면 9분 안쪽)
 * 길안내 중이면 NaviHelper의 '터널' 안내(TBT)로 미리 알 수 있다 — 다음 단계.
 */
class CarAirGuard {

    enum class Change { STARTED, ENDED }

    var active: Boolean = false
        private set
    var sinceMs: Long = 0L
        private set

    /** 차가 닫은 창과 닫기 전 위치. */
    var closedByCar: Map<Pane, Int> = emptyMap()
        private set

    private var recircOnAtMs: Long? = null
    private val recentCloses = mutableMapOf<Pane, Pair<Int, Long>>()
    private var ownCommandUntilMs = Long.MIN_VALUE / 2

    /** NUNCHI가 창·내외기를 움직인 직후의 변화는 차의 차단으로 보지 않는다. */
    fun noteOwnCommand(nowMs: Long) {
        ownCommandUntilMs = nowMs + PAIR_WINDOW_MS
    }

    fun onSignal(before: CabinState, after: CabinState, signal: VehicleSignal, nowMs: Long): Change? {
        if (active) return endIfOver(before, after, signal, nowMs)
        if (after.parked || nowMs < ownCommandUntilMs) return null

        val moved = Pane.moved(signal)
        when {
            signal is VehicleSignal.Recirculation && signal.on && !before.recirculating -> recircOnAtMs = nowMs
            moved != null -> {
                val (pane, position) = moved
                val was = before.windowAt(pane) ?: 0
                if (was > 0 && position <= 0) recentCloses[pane] = was to nowMs
            }
            else -> return null
        }
        recentCloses.entries.removeAll { nowMs - it.value.second > PAIR_WINDOW_MS }
        val recircAt = recircOnAtMs ?: return null
        if (nowMs - recircAt > PAIR_WINDOW_MS || recentCloses.isEmpty()) return null

        active = true
        sinceMs = nowMs
        closedByCar = recentCloses.mapValues { it.value.first }
        recentCloses.clear()
        recircOnAtMs = null
        return Change.STARTED
    }

    /** NUNCHI가 차의 차단을 덮어썼을 때(흡연 환기) — 이 차단은 끝난 것으로 친다. */
    fun forceEnd() {
        if (active) end()
    }

    fun onTick(nowMs: Long): Change? =
        if (active && nowMs - sinceMs >= MAX_GUARD_MS) end() else null

    private fun endIfOver(before: CabinState, after: CabinState, signal: VehicleSignal, nowMs: Long): Change? {
        val moved = Pane.moved(signal)
        val reopened = moved != null && moved.first in closedByCar && moved.second > 0
        val backToOutside = signal is VehicleSignal.Recirculation && !signal.on && before.recirculating
        return if (reopened || backToOutside || after.parked) end() else null
    }

    private fun end(): Change {
        active = false
        closedByCar = emptyMap()
        return Change.ENDED
    }

    private companion object {
        const val PAIR_WINDOW_MS = 3_000L
        const val MAX_GUARD_MS = 10 * 60_000L
    }
}
