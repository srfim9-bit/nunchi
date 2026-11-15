package app.nunchi.core

/**
 * R6 흐름 환기 — 담배 연기를 실내에 퍼뜨리지 않고 '한 줄기 흐름'에 태워 내보낸다.
 * 켜는 길은 메뉴가 아니라 제안이다([SmokeWatch]가 신호를 보고 "바람길 열어 드릴까요?"). 이 클래스는 켠 뒤의 동작만 맡는다.
 *
 * 원리(치환 환기): 외기 + 발밑으로 다소 센 바람 → 출구 창틈 하나로 공기가 빠지면
 * 발밑에서 창틈까지 부드러운 흐름이 생기고, 연기와 재가 그 흐름을 타고 나간다.
 * 몸통(얼굴) 쪽 바람은 연기를 실내에 흩뜨리므로 쓰지 않는다.
 *
 * 우선순위(사용자 결정): 실내 담배 연기는 동승자·뒷좌석에 터널 공기보다 훨씬 직접적인 위협이다.
 * → 흡연 중 흐름 환기는 다른 조건을 덮어쓴다. 차의 터널 차단(외부 공기 유입 방지)이 창을 닫고 내기로 바꿔도
 *   환기 한 번에 한 번은 다시 연다(차가 또 닫으면 다투지 않고 맡긴다). 비가 와도 창틈을 가장 작게(실차 약 0.5cm) 열어 계속한다.
 *   심한 폭우(와이퍼 연속 고속)일 때만 창은 열지 않고 외기·발밑 바람만 한다.
 *
 * - 출구 = 담배 피우는 사람 쪽 창(모르면 운전석). 나머지 창은 닫는다: 출구가 여럿이면 흐름이 갈라진다
 * - 정차 중엔 바람 한 단계 더: 주행 중엔 바깥 바람이 창틈에서 공기를 끌어내 주지만 정차 중엔 그 힘이 없다
 * - 비가 오거나 앞유리 바람을 쓰던 중이면 '발밑+앞유리': 시야(김 서림)가 먼저
 * - 7분 뒤 또는 끄면 바꾼 것만 원래대로(말없이). 운전자가 직접 만진 건 그대로 둔다
 * - 내기를 다시 켜거나 출구 창을 닫으면 '그만'으로 알아듣는다 — 단, 3초 기다려 차의 터널 차단인지 본다
 * - 끝나는 순간이 터널 안이면 터널 규칙을 따른다: 닫는 건 지금, 내기로, 창을 다시 여는 원위치는 터널을 나온 뒤
 */
class VentilationCare(private val config: () -> CareConfig) {

    /** 눈치가 잠시 맡는 조작 단위. */
    private sealed interface Knob {
        data object Recirc : Knob
        data object FanSpeed : Knob
        data object Direction : Knob
        data class Window(val pane: Pane) : Knob
    }

    /** 맡은 조작 하나: 맞춰 둔 값, 끝나면 돌려놓을 명령, 보낸 시각(메아리 구분용). */
    private data class Hold(val target: Any, val restore: CareCommand?, val sentAtMs: Long)

    /** '그만'인지 확인 중(3초 뒤 확정). 그 사이 차의 터널 차단으로 밝혀지면 취소. */
    private data class PendingQuit(val knob: Knob, val reason: String, val atMs: Long)

    private val holds = linkedMapOf<Knob, Hold>()
    private var untilMs: Long? = null
    private var pendingQuit: PendingQuit? = null

    /** 출구 창의 원래 위치(끝나면 돌려놓을 값). 폭우로 열지 않았다가 다시 열 때도 쓴다. */
    private var outletOriginal: Int = 0

    /** 차의 터널 차단이 진행 중인가(엔진이 알려 준다). */
    private var inCarGuard = false

    /** 이번 환기에서 이미 한 번 차의 터널 차단을 덮어썼는가 — 차가 또 닫으면 다투지 않는다. */
    private var overrodeThisSession = false

    /** 차가 두 번째로 닫아 맡긴 상태. */
    var pausedForCar: Boolean = false
        private set

    /** 터널 안에서 끝나 미뤄 둔 '창 다시 열기·내외기' 원위치(터널을 나오면 한다). */
    private var deferred: List<CareCommand> = emptyList()

    /** 연기가 빠지는 창. */
    var outlet: Pane = Pane.DRIVER
        private set

    val isActive: Boolean get() = untilMs != null

    fun remainingMs(nowMs: Long): Long = untilMs?.let { (it - nowMs).coerceAtLeast(0L) } ?: 0L

    fun status(nowMs: Long): String {
        if (!isActive) return ""
        if (pausedForCar) return "잠시 맡김 · 차가 터널에서 창을 또 닫았어요 — 지나면 이어서"
        // (터널을 한 번 덮어쓴 뒤엔 차단이 끝난 것으로 치므로 '연기가 먼저' 표시는 시작·덮어쓰기 기록에만 남긴다)
        val left = remainingMs(nowMs) / 1_000
        val direction = (holds[Knob.Direction]?.target as? FanDirection)?.label?.let { "$it 바람" } ?: "바람 방향 직접 조정"
        val fan = (holds[Knob.FanSpeed]?.target as? Int)?.let { "${it}단" } ?: "세기 직접 조정"
        val gap = holds[Knob.Window(outlet)]?.target as? Int
        val exit = when {
            gap == null -> "출구 ${outlet.label} 창"
            gap <= 0 -> "폭우라 창은 닫고"
            else -> "출구 ${outlet.label} 창 ${gap}단계"
        }
        val tunnel = if (inCarGuard) " · 터널이지만 연기가 먼저" else ""
        return "환기 중 · ${left / 60}분 ${"%02d".format(left % 60)}초 남음 · $direction $fan · $exit$tunnel"
    }

    /**
     * @param baseline 끝나고 돌려놓을 '원래' 상태(R0·R1이 잠깐 바꾼 값은 되돌린 상태)
     * @param state 지금 상태(열린 창·주행 여부·비)
     * @param outlet 연기가 빠질 창(담배 피우는 사람 쪽)
     * @param say 시작을 한마디로 알릴지('네'라고 답한 경우엔 이미 대화가 오갔으니 생략)
     */
    fun start(baseline: CabinState, state: CabinState, nowMs: Long, outlet: Pane = Pane.DRIVER, say: Boolean = true): RoutineOutput {
        if (isActive) return RoutineOutput.NONE
        val cfg = config()
        holds.clear()
        pendingQuit = null
        pausedForCar = false
        deferred = emptyList()
        // 터널 차단 중에 켜면 그 자체가 덮어쓰기다(연기가 먼저) — 차단은 엔진이 끝낸 것으로 친다
        overrodeThisSession = inCarGuard
        val startedInTunnel = inCarGuard
        inCarGuard = false
        untilMs = nowMs + cfg.ventilationMs
        this.outlet = outlet

        val commands = mutableListOf<CareCommand>()
        fun take(knob: Knob, command: CareCommand, target: Any, restore: CareCommand?) {
            holds[knob] = Hold(target, restore, nowMs)
            commands += command
        }

        val notes = mutableListOf("외기")

        val direction = directionFor(state)
        take(Knob.Direction, CareCommand.SetFanDirection(direction), direction,
            baseline.fanDirection?.let { CareCommand.SetFanDirection(it) })

        val fan = fanLevel(state.parked)
        take(Knob.FanSpeed, CareCommand.SetFanSpeed(fan), fan,
            CareCommand.SetFanSpeed(baseline.fanSpeed ?: cfg.fanDefault))
        notes += if (direction == FanDirection.FLOOR) "발밑 바람 ${fan}단" else "발밑+앞유리 바람 ${fan}단(김 서림 방지)"

        take(Knob.Recirc, CareCommand.SetRecirculation(false), false,
            CareCommand.SetRecirculation(baseline.recirculating))

        // 출구: R0가 잠깐 내려 둔 1cm는 '열려 있음'으로 치지 않는다 → 원래 값(baseline)으로 판단
        outletOriginal = baseline.windowAt(outlet) ?: 0
        val gap = outletGap(state)
        when {
            gap <= 0 -> notes += "심한 폭우라 창은 열지 않음"
            outletOriginal >= cfg.ventilationWindowStep -> notes += "${outlet.label} 창은 이미 열려 있어 그대로"
            else -> {
                take(Knob.Window(outlet), outlet.set(gap), gap, outlet.set(outletOriginal))
                notes += if (state.wiperOn) "${outlet.label} 창 가장 작게(비)" else "${outlet.label} 창 틈"
            }
        }

        // 나머지 창은 닫는다(끝나면 원래대로)
        val closing = Pane.entries.filter { it != outlet }.mapNotNull { pane ->
            val now = state.windowAt(pane) ?: 0
            if (now > 0) pane to now else null
        }
        closing.forEach { (pane, now) ->
            take(Knob.Window(pane), pane.set(0), 0, pane.set(baseline.windowAt(pane) ?: now))
        }
        if (closing.isNotEmpty()) notes += closing.joinToString(", ") { it.first.label } + " 창 닫음"
        if (startedInTunnel) notes += "터널이지만 연기가 먼저"

        if (say) commands += CareCommand.Say("바람길 열게요. 공기는 ${outlet.label} 창틈으로 빠져요.")
        return RoutineOutput(
            commands = commands,
            events = listOf(CareEvent(nowMs, Routine.R6, CareKind.VENTILATION,
                "흐름 환기 시작 — ${notes.joinToString(" · ")} (${cfg.ventilationMs / 60_000}분)")),
        )
    }

    /** 끝: 맡았던 것만 원래대로(운전자가 직접 만진 건 이미 손을 뗐다). */
    fun stop(state: CabinState, nowMs: Long, reason: String): RoutineOutput {
        if (!isActive) return RoutineOutput.NONE
        untilMs = null
        pendingQuit = null
        pausedForCar = false
        val keepClosed = state.wiperOn
        var keptAny = false
        val now = mutableListOf<CareCommand>()
        val later = mutableListOf<CareCommand>()
        holds.entries.sortedBy { (knob, _) -> restoreOrder(knob) }.forEach { (knob, hold) ->
            val restore = hold.restore ?: return@forEach
            val closedByUs = knob is Knob.Window && knob.pane != outlet
            if (closedByUs && keepClosed) {
                keptAny = true
                return@forEach
            }
            if (!inCarGuard) {
                now += restore
                return@forEach
            }
            // 터널 안에서 끝남 — 흡연이 끝났으니 차의 터널 규칙을 따른다.
            // 창: 닫는 건 지금. 차가 터널을 나오며 창을 자기 기억대로 다시 열기 때문에, 창 원위치는 모두 그 뒤에 한 번 더 한다
            when {
                knob == Knob.Recirc -> if (restore != CareCommand.SetRecirculation(true)) later += restore
                knob is Knob.Window -> {
                    if (!opens(restore)) now += restore
                    later += restore
                }
                else -> now += restore
            }
        }
        if (inCarGuard) now += CareCommand.SetRecirculation(true)
        holds.clear()
        deferred = later
        val tail = when {
            later.isNotEmpty() -> " (터널 안이라 내기로, 창 다시 열기·외기는 터널을 나온 뒤에)"
            inCarGuard -> " (터널 안이라 내기로)"
            keptAny -> " (비가 와서 닫아 둔 창은 그대로)"
            else -> ""
        }
        return RoutineOutput(
            commands = now,
            events = listOf(CareEvent(nowMs, Routine.R6, CareKind.INFO, "흐름 환기 끝($reason) → 바꾼 것만 원래대로$tail")),
        )
    }

    /**
     * 차의 터널 차단이 시작됨(엔진이 알려 준다).
     * 흐름 환기 중이면 '그만' 확인을 취소하고, 환기 한 번에 한 번은 덮어쓴다 — 실내 담배 연기가 더 해롭다.
     * 덮어썼으면 true를 [overrodeCar]에 남긴다(엔진이 차단을 끝난 것으로 친다). 두 번째면 다투지 않고 맡긴다.
     */
    fun onCarGuardStarted(state: CabinState, nowMs: Long): RoutineOutput {
        overrodeCar = false
        inCarGuard = true
        if (!isActive) return RoutineOutput.NONE
        pendingQuit = null
        if (!overrodeThisSession) {
            overrodeThisSession = true
            overrodeCar = true
            inCarGuard = false
            return overrideCar(state, nowMs)
        }
        pausedForCar = true
        return RoutineOutput.info(nowMs, Routine.R6, "차가 터널에서 창·내기를 또 닫음 — 다투지 않고 맡김, 지나면 이어서")
    }

    /** 방금 [onCarGuardStarted]에서 차의 차단을 덮어썼는가. */
    var overrodeCar: Boolean = false
        private set

    /** 차의 터널 차단이 끝남: 맡겼던 거면 이어 가고, 이미 끝났으면 미뤄 둔 원위치를 한다. */
    fun onCarGuardEnded(state: CabinState, nowMs: Long): RoutineOutput {
        inCarGuard = false
        if (!isActive) {
            val later = deferred
            deferred = emptyList()
            if (later.isEmpty()) return RoutineOutput.NONE
            return RoutineOutput(
                commands = later,
                events = listOf(CareEvent(nowMs, Routine.R6, CareKind.INFO, "터널 지남 → 미뤄 둔 창·내외기 원위치")),
            )
        }
        if (!pausedForCar) return RoutineOutput.NONE
        pausedForCar = false
        refreshEcho(nowMs)
        val commands = if (state.recirculating && Knob.Recirc in holds) listOf(CareCommand.SetRecirculation(false)) else emptyList()
        return RoutineOutput(
            commands = commands,
            events = listOf(CareEvent(nowMs, Routine.R6, CareKind.INFO,
                "터널 지남 → 흐름 환기 이어서${if (commands.isEmpty()) "" else "(외기 다시)"}")),
        )
    }

    fun onTick(state: CabinState, nowMs: Long): RoutineOutput {
        val until = untilMs ?: return RoutineOutput.NONE
        val quit = pendingQuit
        if (quit != null && nowMs - quit.atMs >= QUIT_CONFIRM_MS) {
            // 3초 동안 차의 터널 차단이 아니었다 → 사람이 '그만'한 것
            holds.remove(quit.knob)
            return stop(state, nowMs, quit.reason)
        }
        if (nowMs < until) return RoutineOutput.NONE
        return stop(state, nowMs, "${config().ventilationMs / 60_000}분 지남")
    }

    fun onSignal(before: CabinState, after: CabinState, signal: VehicleSignal, nowMs: Long): RoutineOutput {
        if (!isActive) return RoutineOutput.NONE
        val moved = Pane.moved(signal)
        if (pausedForCar && (moved != null || signal is VehicleSignal.Recirculation)) return RoutineOutput.NONE
        moved?.let { (pane, position) -> return onWindow(pane, position, after, nowMs) }
        return when (signal) {
            is VehicleSignal.Recirculation -> {
                if (signal.on && byHand(Knob.Recirc, true, nowMs) && pendingQuit == null) {
                    pendingQuit = PendingQuit(Knob.Recirc, "내기를 다시 켬", nowMs)
                } else if (!signal.on && pendingQuit?.knob == Knob.Recirc) {
                    pendingQuit = null // 곧바로 되돌림 — 그만이 아니었다
                }
                RoutineOutput.NONE
            }

            is VehicleSignal.FanSpeed ->
                if (byHand(Knob.FanSpeed, signal.level, nowMs)) {
                    holds.remove(Knob.FanSpeed)
                    RoutineOutput.info(nowMs, Routine.R6, "운전자가 바람 세기를 ${signal.level}단으로 맞춤 — 그 세기로 계속")
                } else RoutineOutput.NONE

            is VehicleSignal.FanDirectionChanged ->
                if (byHand(Knob.Direction, signal.direction, nowMs)) {
                    holds.remove(Knob.Direction)
                    RoutineOutput.info(nowMs, Routine.R6,
                        "운전자가 바람 방향을 '${signal.direction.label}'(으)로 바꿈 — 존중(몸 쪽 바람이면 연기가 퍼질 수 있음)")
                } else RoutineOutput.NONE

            is VehicleSignal.Gear -> onGear(before, after, nowMs)
            is VehicleSignal.Wiper, is VehicleSignal.HeavyRain -> onWeather(before, after, nowMs)
            else -> RoutineOutput.NONE
        }
    }

    // ---- 내부 ----

    /** 터널 차단을 한 번 덮어쓴다: 출구 창을 다시 열고 외기로. */
    private fun overrideCar(state: CabinState, nowMs: Long): RoutineOutput {
        val commands = mutableListOf<CareCommand>()
        val gap = holds[Knob.Window(outlet)]?.target as? Int
        if (gap != null && gap > 0 && (state.windowAt(outlet) ?: 0) != gap) commands += outlet.set(gap)
        if (state.recirculating && Knob.Recirc in holds) commands += CareCommand.SetRecirculation(false)
        refreshEcho(nowMs)
        return RoutineOutput(
            commands = commands,
            events = listOf(CareEvent(nowMs, Routine.R6, CareKind.INFO,
                "흡연 중 터널 차단 — 실내 담배 연기가 더 해로워 흐름 환기 유지" +
                    if (commands.isEmpty()) "" else "(차가 닫은 창·내기를 다시 엶)")),
        )
    }

    private fun onWindow(pane: Pane, position: Int, after: CabinState, nowMs: Long): RoutineOutput {
        val knob = Knob.Window(pane)
        if (pane == outlet && position > 0 && pendingQuit?.knob == knob) pendingQuit = null // 곧바로 다시 엶
        val held = holds[knob]
        if (held != null && !byHand(knob, position, nowMs)) return RoutineOutput.NONE
        if (held == null && pane != outlet) return RoutineOutput.NONE // 우리가 안 건드린 다른 창
        if (pane == outlet && position <= 0) {
            // 바로 끄지 않고 3초 확인 — 차의 터널 차단이면 '내기 켜짐'이 곧 따라온다
            if (pendingQuit == null) pendingQuit = PendingQuit(knob, "${pane.label} 창(출구)을 닫음", nowMs)
            return RoutineOutput.NONE
        }
        holds.remove(knob)
        return if (pane == outlet) {
            RoutineOutput.info(nowMs, Routine.R6, "${pane.label} 창 높이를 직접 맞춤 — 그대로 두고 환기는 계속")
        } else {
            RoutineOutput.info(nowMs, Routine.R6, "${pane.label} 창을 직접 엶 — 그대로 둠")
        }
    }

    /** 정차 ↔ 주행: 바람 한 단계 조정. 운전자가 직접 정한 세기면 건드리지 않는다. */
    private fun onGear(before: CabinState, after: CabinState, nowMs: Long): RoutineOutput {
        if (before.parked == after.parked) return RoutineOutput.NONE
        val hold = holds[Knob.FanSpeed] ?: return RoutineOutput.NONE
        val level = fanLevel(after.parked)
        if (level == hold.target) return RoutineOutput.NONE
        holds[Knob.FanSpeed] = hold.copy(target = level, sentAtMs = nowMs)
        val why = if (after.parked) "정차 — 바깥 바람이 없어 한 단계 올림" else "출발 — 바깥 바람이 도와 한 단계 내림"
        return RoutineOutput(
            commands = listOf(CareCommand.SetFanSpeed(level)),
            events = listOf(CareEvent(nowMs, Routine.R6, CareKind.INFO, "$why (${level}단)")),
        )
    }

    /** 날씨가 바뀜: 비면 창틈을 가장 작게 + 김 서림 방지, 폭우면 창은 닫고, 그치면 원래 틈으로. */
    private fun onWeather(before: CabinState, after: CabinState, nowMs: Long): RoutineOutput {
        if (before.wiperOn == after.wiperOn && before.heavyRain == after.heavyRain) return RoutineOutput.NONE
        val commands = mutableListOf<CareCommand>()
        val notes = mutableListOf<String>()

        val dirHold = holds[Knob.Direction]
        if (after.wiperOn && dirHold?.target == FanDirection.FLOOR) {
            holds[Knob.Direction] = dirHold.copy(target = FanDirection.DEFROST_AND_FLOOR, sentAtMs = nowMs)
            commands += CareCommand.SetFanDirection(FanDirection.DEFROST_AND_FLOOR)
            notes += "김 서림 방지로 발밑+앞유리 바람"
        }

        if (!pausedForCar) {
            val knob = Knob.Window(outlet)
            val gap = outletGap(after)
            val hold = holds[knob]
            if (hold != null && hold.target != gap) {
                holds[knob] = hold.copy(target = gap, sentAtMs = nowMs)
                commands += outlet.set(gap)
                notes += when {
                    gap <= 0 -> "심한 폭우 — 창은 닫고 외기·발밑 바람만"
                    after.wiperOn -> "비 — 창틈을 가장 작게"
                    else -> "비 그침 — 창틈 원래대로"
                }
            } else if (hold == null && gap > 0 && outletOriginal < config().ventilationWindowStep &&
                (after.windowAt(outlet) ?: 0) <= 0
            ) {
                // 폭우로 열지 않았던 창 — 빗발이 약해지면 연다
                holds[knob] = Hold(gap, outlet.set(outletOriginal), nowMs)
                commands += outlet.set(gap)
                notes += "빗발이 약해져 창틈을 다시 엶"
            }
        }
        if (commands.isEmpty()) return RoutineOutput.NONE
        return RoutineOutput(
            commands = commands,
            events = listOf(CareEvent(nowMs, Routine.R6, CareKind.INFO, notes.joinToString(" · "))),
        )
    }

    /** 출구 창틈: 맑으면 약 2cm, 비면 가장 작게(실차 약 0.5cm), 심한 폭우면 열지 않음. */
    private fun outletGap(state: CabinState): Int {
        val cfg = config()
        return when {
            state.heavyRain -> 0
            state.wiperOn -> minOf(cfg.windowCrackStep, cfg.ventilationWindowStep)
            else -> cfg.ventilationWindowStep
        }
    }

    /** 운전자가 직접 바꾼 값인가? 우리가 보낸 값의 메아리나, 보낸 직후 아직 옛 값이 오는 경우는 제외. */
    private fun byHand(knob: Knob, value: Any, nowMs: Long): Boolean {
        val hold = holds[knob] ?: return false
        if (value == hold.target) return false
        return nowMs - hold.sentAtMs >= ECHO_GUARD_MS
    }

    /** 창·내외기 메아리 기준 시각을 새로 잡는다. */
    private fun refreshEcho(nowMs: Long) {
        holds.keys.filter { touchesOutsideAir(it) }.forEach { knob -> holds[knob] = holds.getValue(knob).copy(sentAtMs = nowMs) }
    }

    private fun opens(command: CareCommand): Boolean = when (command) {
        is CareCommand.SetDriverWindow -> command.position > 0
        is CareCommand.SetOtherWindow -> command.position > 0
        else -> false
    }

    private fun directionFor(state: CabinState): FanDirection =
        if (state.wiperOn || state.fanDirection?.defrost == true) FanDirection.DEFROST_AND_FLOOR else FanDirection.FLOOR

    /** '다소 센' 바람: 최대보다 두 단계 아래, 정차 중엔 한 단계 더. */
    private fun fanLevel(parked: Boolean): Int {
        val max = config().fanMax
        val driving = (max - 2).coerceAtLeast(1)
        return if (parked) (driving + 1).coerceAtMost(max) else driving
    }

    /** 바깥 공기와 통하는 조작(창·내외기). */
    private fun touchesOutsideAir(knob: Knob) = knob is Knob.Window || knob == Knob.Recirc

    /** 돌려놓는 순서: 출구 창 → 닫아 둔 창 → 바람 방향 → 세기 → 내외기. */
    private fun restoreOrder(knob: Knob): Int = when (knob) {
        is Knob.Window -> if (knob.pane == outlet) 0 else 1 + knob.pane.ordinal
        Knob.Direction -> 10
        Knob.FanSpeed -> 11
        Knob.Recirc -> 12
    }

    private companion object {
        /** 창이 끝까지 움직이는 시간까지 감안해 R0(2초)보다 넉넉히. */
        const val ECHO_GUARD_MS = 3_000L

        /** '그만'을 확정하기 전 기다리는 시간 — 차의 터널 차단은 창 닫힘과 내기가 몇 초 안에 함께 온다. */
        const val QUIT_CONFIRM_MS = 3_000L
    }
}
