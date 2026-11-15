package app.nunchi.core

import kotlin.math.roundToInt

data class CareConfig(
    // 제안 → 승인 (NUNCHI의 기본 개입 방식)
    /** "다음부턴 알아서"라고 허락받은 일. 이것만 묻지 않고 바로 하고 짧게 알린다. 나머지는 먼저 여쭙는다. */
    val trusted: Set<CareAction> = emptySet(),
    /** 제안에 답이 없으면 이만큼 뒤 조용히 거둔다(재촉하지 않는다). */
    val proposalTimeoutMs: Long = 60_000,
    /** 같은 일을 연달아 이만큼 거절하면 그날은 먼저 권하지 않는다. */
    val quietAfterRejects: Int = 2,

    // R0 1cm의 배려
    /** 창을 '살짝' 내리는 단계. 실제 값은 Window capability 범위의 최소 개방 단계로 갱신한다. */
    val windowCrackStep: Int = 1,
    /** 창 최대 개방 단계(에뮬레이터 0..10). */
    val windowMax: Int = 10,
    /** 프레임리스 도어 차종은 문이 열릴 때 창이 스스로 내려가므로 창은 건드리지 않는다. */
    val frameless: Boolean = false,
    /** 마지막 문이 닫힌 뒤 창을 올리기까지의 여유. */
    val restoreDelayMs: Long = 1_500,
    val heatPurgeThresholdC: Float = 28f,
    val heatPurgeMs: Long = 30_000,
    val fanMax: Int = 8,
    val fanDefault: Int = 3,

    // R1 숨 쉬는 차
    /** 졸음 유발 농도(2,000ppm)에 닿기 전 개입. */
    val co2TriggerPpm: Double = 1_900.0,
    val freshAirBurstSeconds: Int = 90,
    val freshAirCooldownMs: Long = 5 * 60_000,
    val crowdedOccupants: Int = 3,
    /** 제안은 주행을 시작하고 이만큼 지난 뒤에만 — 타자마자 말 걸지 않는다. */
    val suggestAfterDrivingMs: Long = 2 * 60_000,

    // R6 흐름 환기
    /** 담배 한 대 + 남은 연기를 빼는 시간. 그 전에 끌 수 있다. */
    val ventilationMs: Long = 7 * 60_000,
    /** 연기가 빠질 창틈(약 2cm). 실제 값은 Window capability 범위의 약 4%로 갱신한다(에뮬레이터 0..10 → 1단계). */
    val ventilationWindowStep: Int = 1,
    /** 약한 신호: 최대의 이 비율 이하로 '살짝' 연 창. */
    val smallOpeningRatio: Float = 0.3f,
    /** 강한 신호: 실내 PM2.5가 환경부 '매우 나쁨'(76㎍/㎥) 이상이면서 2분 새 이만큼 이상 뛰면 연기로 본다. */
    val smokePm25: Int = 76,
    val smokePm25Rise: Int = 40,
    /** 강한 신호로 권한 뒤 이 시간 안에는 다시 권하지 않는다. */
    val ventSuggestCooldownMs: Long = 10 * 60_000,

    // R2 마지막 20km
    val rangeMarginTight: Float = 0.20f,
    val lastStretchKm: Float = 20f,

    // R8 배우는 눈치
    /** 이 간격 안에 연달아 손댄 조작은 한 묶음으로 본다. */
    val habitGapMs: Long = 20_000,
    /** 같은 상황에서 같은 묶음이 이만큼 나오면 습관으로 인식하고 다음에 먼저 여쭙는다. */
    val habitMinRepeats: Int = 3,
    /** 다 탄 뒤 이 시간 안의 조작은 '탈 때' 습관, 그 뒤는 '달리는 중' 습관. */
    val habitBoardingWindowMs: Long = 3 * 60_000,
    /** 달리는 중 습관은 출발하고 이만큼 지난 뒤에만 여쭙는다. */
    val habitDriveDelayMs: Long = 30_000,
    /** 차나 사람이 바꾼 걸 이 안에 원래대로 돌리면 '불편해하시는 것'으로 기억한다. */
    val revertWindowMs: Long = 10_000,
    /** 맞춰 드린 걸 이 안에 원래 값으로 되돌리면 그 습관에 분명한 '원치 않음'. */
    val undoAfterApplyMs: Long = 60_000,
    /** 맞춰 드린 걸 다른 값으로 바꾸시면 '달라진 취향'으로 보고 다음에 "지난번에 바꾸신 대로?" 여쭙는다(끄면 다수결로만 천천히 따라감). */
    val habitNoticeDrift: Boolean = true,
    /** 평소 타는 시간을 알려면 최소 이만큼 타 봐야 한다(그 전엔 '평소와 다른 날'을 따지지 않는다). */
    val usualMinTrips: Int = 5,
    /** 정차한 채 이만큼 아무 일이 없으면 이번 승차는 끝난 것으로 본다. */
    val tripIdleEndMs: Long = 30 * 60_000,
)

/** 대답을 기다리는 제안 하나. */
class Proposal(val offer: Offer, val atMs: Long)

/**
 * 눈치 케어 엔진 — 차량 신호를 받아 루틴을 돌리고, 차에 보낼 명령과 기록을 만든다.
 * 모든 동작은 '먼저 여쭙고, 좋다고 하면 한다'가 기본이다. 미리 허락받은 일만 바로 하고, 사용자는 언제든 거절·해제할 수 있다.
 * 플랫폼과 무관한 순수 Kotlin이라 JVM 단위 테스트로 검증한다.
 */
class CareEngine(
    config: CareConfig = CareConfig(),
    startMs: Long = 0L,
    /** 몇 시인가(R8 습관의 시간대). 테스트에선 고정 시각을 넣는다. null이면 기기 시각. */
    hourOf: ((Long) -> Int)? = null,
    /** 주말인가(R8 '평소와 다른 날'을 평일·주말 따로 본다). null이면 기기 날짜. */
    isWeekend: ((Long) -> Boolean)? = null,
) {
    var config: CareConfig = config
        private set

    var state: CabinState = CabinState()
        private set

    val boarding = BoardingCare { this.config }
    val air = FreshAirCare { this.config }
    val ventilation = VentilationCare { this.config }
    val range = RangeCoach({ this.config }, fanBusy = { ventilation.isActive })
    val smoke = SmokeWatch { this.config }
    /** 차가 스스로 하는 터널 차단(외부 공기 유입 방지)을 알아보고 비켜 준다. */
    val carGuard = CarAirGuard()
    /** R8 배우는 눈치 — 운전자가 직접 하는 조작에서 그 사람만의 습관을 익힌다. */
    val habits = HabitLearner(
        config = { this.config },
        hourOf = hourOf ?: { HabitLearner.systemHour(it) },
        weekendOf = isWeekend ?: { HabitLearner.systemWeekend(it) },
        onTakesWindow = { boarding.releaseWindow() },
    )
    val log = CareLog()

    /** 지금 대답을 기다리는 제안(한 번에 하나만 — 여러 개를 쌓아 두고 재촉하지 않는다). */
    var proposal: Proposal? = null
        private set

    private val rejects = mutableMapOf<CareAction, Int>()
    private var lastTickMs = startMs

    fun updateConfig(transform: (CareConfig) -> CareConfig) {
        config = transform(config)
    }

    fun isTrusted(action: CareAction) = action in config.trusted

    /** 연달아 거절해서 오늘은 먼저 권하지 않는 일인가. */
    fun isQuiet(action: CareAction) = (rejects[action] ?: 0) >= config.quietAfterRejects

    fun setTrusted(action: CareAction, trusted: Boolean) {
        config = config.copy(trusted = if (trusted) config.trusted + action else config.trusted - action)
    }

    /**
     * [human] = 사람이 한 조작임을 확실히 아는 신호(데모 시뮬레이터의 '손 조작'). 실차 신호는 누가 바꿨는지 모르므로 false —
     * 그때는 NUNCHI 명령의 메아리(에뮬레이터가 옛 값을 한 번 더 보내는 것 포함)를 사람 손으로 오해하지 않게 거른다.
     */
    fun onSignal(signal: VehicleSignal, nowMs: Long, human: Boolean = false): List<CareCommand> {
        val before = state
        state = state.apply(signal)
        // 차의 터널 차단부터 알아본다 — 그래야 아래 루틴들이 차가 한 일을 사람 손으로 오해하지 않는다
        val guardOut = onCarGuard(carGuard.onSignal(before, state, signal, nowMs), nowMs)
        // R6가 도는 동안 창은 R6 몫 — R0는 쉰다
        val venting = ventilation.isActive
        val detection = smoke.onSignal(before, state, signal, nowMs, venting)
        // 쉬는 중이라 넘긴 신호는 '왜 조용한지' 한 줄만 남긴다(재촉하지 않기)
        val skipped = smoke.takeSuppressed()?.let { RoutineOutput.info(nowMs, Routine.R6, it) } ?: RoutineOutput.NONE
        // 사람이 운전석 창을 직접 맞춤(시뮬레이터의 손 조작): R0가 잠깐 내려 둔 창이면 R0는 손을 떼고,
        // 그 값은 사람이 고른 값으로 배운다 — '탄 직후 1cm'를 눌렀는데 R0가 도로 닫아 버리면 안 된다
        val claimed = if (human && signal is VehicleSignal.DriverWindow) boarding.claimWindow(nowMs) else null
        // R8: 사람 손이 바꾼 것인지 가려 조작 묶음으로 배운다(제안은 tick에서 한 번에 하나씩)
        val learned = habits.onSignal(
            before, state, signal, nowMs,
            guardActive = carGuard.active, venting = venting, human = human, humanFrom = claimed?.first,
        )
        val out = guardOut + (claimed?.second ?: RoutineOutput.NONE) + learned + skipped +
            (if (venting) RoutineOutput.NONE else boarding.onSignal(before, state, signal, nowMs)) +
            air.onSignal(before, state, signal, nowMs) +
            range.onSignal(state, signal, nowMs) +
            ventilation.onSignal(before, state, signal, nowMs) +
            (if (detection != null && !venting) RoutineOutput(offer = ventOffer(detection)) else RoutineOutput.NONE)
        return settle(out, nowMs)
    }

    fun tick(nowMs: Long): List<CareCommand> {
        val dtSeconds = (nowMs - lastTickMs).coerceAtLeast(0L) / 1000.0
        lastTickMs = nowMs
        val guardOut = onCarGuard(carGuard.onTick(nowMs), nowMs)
        val venting = ventilation.isActive
        // 터널 안에선 외기 이야기를 꺼내지 않는다
        val out = guardOut + (if (venting) RoutineOutput.NONE else boarding.onTick(state, nowMs)) +
            air.onTick(state, nowMs, dtSeconds, allowActions = !venting && !carGuard.active) +
            ventilation.onTick(state, nowMs) +
            expire(nowMs)
        // R8은 다른 루틴이 여쭙는 중이면 기다렸다가(재촉하지 않고) 다음 틈에 여쭙는다
        val learned = habits.onTick(
            state, nowMs,
            canAct = out.offer == null,
            canOffer = proposal == null && out.offer == null,
            venting = ventilation.isActive,
            dryAirTrusted = isTrusted(CareAction.DRY_AIR),
        )
        return settle(out + learned, nowMs)
    }

    /** (시뮬레이터의 '타기') 지금부터 새 승차로 본다 — [HabitLearner.beginTrip]. */
    fun beginTrip(nowMs: Long): List<CareCommand> = settle(habits.beginTrip(state, nowMs), nowMs)

    // ---- 제안 → 승인 ----

    /** '네'. [always]면 "다음부턴 알아서" — 같은 일은 이제 묻지 않고 바로 한다. */
    fun approve(nowMs: Long, always: Boolean = false): List<CareCommand> {
        val p = proposal ?: return emptyList()
        proposal = null
        val action = p.offer.action
        if (action == CareAction.HABIT) {
            // 배운 습관은 허락도 습관마다 따로
            return settle(habits.onApproved(p.offer.key, always, nowMs) + p.offer.apply(state, nowMs), nowMs)
        }
        rejects.remove(action)
        val trust = always || p.offer.habit
        if (trust) setTrusted(action, true)
        val note = if (trust && !p.offer.habit) {
            RoutineOutput.info(nowMs, action.routine, "'${action.label}' 허락받음 — 다음부턴 묻지 않고 알아서")
        } else {
            RoutineOutput.NONE
        }
        return settle(note + p.offer.apply(state, nowMs), nowMs)
    }

    /** '아니요'. 연달아 거절하면 그날은 먼저 권하지 않는다. */
    fun reject(nowMs: Long): List<CareCommand> {
        val p = proposal ?: return emptyList()
        proposal = null
        val action = p.offer.action
        if (action == CareAction.HABIT) return settle(habits.onRejected(p.offer.key, nowMs), nowMs)
        val n = (rejects[action] ?: 0) + 1
        rejects[action] = n
        val message = if (n >= config.quietAfterRejects) {
            "'${action.label}' ${n}번 연달아 사양 — 오늘은 먼저 권하지 않음"
        } else {
            "'${action.label}' 사양 — 알겠습니다"
        }
        return settle(RoutineOutput.info(nowMs, action.routine, message), nowMs)
    }

    // ---- R6 흐름 환기 ----

    /** 바로 켜기(테스트·음성 명령용). 평소엔 [SmokeWatch]의 제안에 '네'라고 하면 켠다. */
    fun startVentilation(nowMs: Long, outlet: Pane = Pane.DRIVER): List<CareCommand> =
        settle(beginVentilation(nowMs, outlet, say = true), nowMs)

    fun stopVentilation(nowMs: Long): List<CareCommand> = settle(ventilation.stop(state, nowMs, "끄기"), nowMs)

    fun report(sinceMs: Long = Long.MIN_VALUE): DailyReport =
        log.report(sinceMs, air.peakPpm.roundToInt())

    fun resetDay() {
        log.clear()
        newDay()
    }

    /**
     * 날이 바뀌었다: '오늘은 먼저 권하지 않음'·'하루 한 번만 여쭘'·'조금 전에 여쭘'을 푼다(기록은 남긴다).
     * 이게 없으면 한 번 사양한 일을 앱을 다시 켤 때까지 영영 권하지 않게 된다.
     */
    fun newDay() {
        air.resetPeak()
        rejects.clear()
        boarding.resetDay()
        smoke.resetDay()
    }

    // ---- 내부 ----

    private fun beginVentilation(nowMs: Long, outlet: Pane, say: Boolean): RoutineOutput {
        if (ventilation.isActive) return RoutineOutput.NONE
        // R0·R1이 잠깐 바꿔 둔 창·내외기는, 그들이 돌려놓았을 값을 '원래 값'으로 삼는다
        val pending = boarding.cancel() + air.yieldControl(nowMs)
        val baseline = pending.fold(state) { s, c -> s.assume(c) }
        val out = ventilation.start(baseline, state, nowMs, outlet, say)
        if (carGuard.active) {
            // 터널 안에서 흡연 — 연기가 먼저. 창을 열고 외기로 가니 차의 차단은 끝난 것으로 친다
            carGuard.forceEnd()
            smoke.quietWindowsUntil(nowMs + AFTER_GUARD_QUIET_MS)
        }
        return out
    }

    private fun ventOffer(d: SmokeWatch.Detection): Offer = Offer(
        action = CareAction.VENTILATION,
        ask = when (d.source) {
            SmokeWatch.Source.PM25 -> "공기가 탁해졌네요. 바람길 열어 드릴까요?"
            SmokeWatch.Source.GESTURE -> "바람길 열어 드릴까요?"
            SmokeWatch.Source.WINDOW_CRACK -> "창만 열면 냄새가 안으로 돌아요. 발밑 바람으로 바람길 열어 드릴까요?"
        },
        tell = "바람길 열게요.",
        reason = d.reason,
        voice = d.strength == SmokeWatch.Strength.STRONG,
        autoOk = d.strength == SmokeWatch.Strength.STRONG,
        stillValid = { s ->
            !ventilation.isActive && (d.strength == SmokeWatch.Strength.STRONG ||
                (s.windowAt(d.outlet) ?: 0) in 1..smoke.smallLimit())
        },
        apply = { _, now ->
            smoke.onAccepted()
            beginVentilation(now, d.outlet, say = false)
        },
    )

    private fun onCarGuard(change: CarAirGuard.Change?, nowMs: Long): RoutineOutput = when (change) {
        CarAirGuard.Change.STARTED -> {
            smoke.quietWindowsUntil(Long.MAX_VALUE)
            habits.onCarGuardStarted(nowMs)
            val p = proposal
            val withdraw = if (p != null && p.offer.action in OUTSIDE_AIR_ACTIONS) {
                proposal = null
                RoutineOutput.info(nowMs, p.offer.routine, "터널 차단 중이라 '${p.offer.action.label}' 제안을 거둠")
            } else {
                RoutineOutput.NONE
            }
            val note = RoutineOutput.info(nowMs, Routine.R1,
                "차가 터널 앞에서 창을 닫고 내기로 바꿈(외부 공기 유입 방지) — 흡연 환기가 아니면 차를 따른다")
            val vent = ventilation.onCarGuardStarted(state, nowMs)
            if (ventilation.overrodeCar) {
                // 흡연 중: 연기가 먼저 — NUNCHI가 창·외기를 되돌렸으니 이 차단은 끝난 것으로 친다
                carGuard.forceEnd()
                smoke.quietWindowsUntil(nowMs + AFTER_GUARD_QUIET_MS)
            }
            note + vent + withdraw
        }
        CarAirGuard.Change.ENDED -> {
            smoke.quietWindowsUntil(nowMs + AFTER_GUARD_QUIET_MS)
            RoutineOutput.info(nowMs, Routine.R1, "터널 차단 끝") + ventilation.onCarGuardEnded(state, nowMs)
        }
        null -> RoutineOutput.NONE
    }

    /** 루틴이 낸 제안을 처리: 허락받은 일은 바로, 아니면 여쭙는다(한 번에 하나, 오늘 사양한 일은 조용히). */
    private fun route(offer: Offer, nowMs: Long): RoutineOutput {
        val action = offer.action
        if (carGuard.active && action in OUTSIDE_AIR_ACTIONS) return RoutineOutput.NONE
        val learned = action == CareAction.HABIT
        val trusted = if (learned) habits.isTrusted(offer.key) else isTrusted(action)
        if (offer.habit && trusted) return RoutineOutput.NONE
        if (!offer.habit && offer.autoOk && trusted) {
            val tell = offer.tell?.takeIf { offer.voice }?.let { listOf(CareCommand.Say(it)) }.orEmpty()
            return RoutineOutput(commands = tell) + offer.apply(state, nowMs)
        }
        // 배운 습관은 습관마다 따로 사양을 센다(R8이 직접 거름)
        if (proposal != null || (!learned && isQuiet(action))) return RoutineOutput.NONE
        proposal = Proposal(offer, nowMs)
        return RoutineOutput(
            commands = if (offer.voice) listOf(CareCommand.Say(offer.ask)) else emptyList(),
            events = listOf(CareEvent(nowMs, action.routine, CareKind.INFO,
                "제안: ${offer.reason} → “${offer.ask}”${if (offer.voice) "" else " (화면만)"}")),
        )
    }

    private fun expire(nowMs: Long): RoutineOutput {
        val p = proposal ?: return RoutineOutput.NONE
        if (nowMs - p.atMs < config.proposalTimeoutMs) return RoutineOutput.NONE
        proposal = null
        return RoutineOutput.info(nowMs, p.offer.routine, "답이 없어 '${p.offer.action.label}' 제안을 조용히 거둠")
    }

    /** 상황이 바뀌어 더는 필요 없는 제안은 대답을 기다리지 않고 거둔다. */
    private fun withdrawStale(nowMs: Long): RoutineOutput {
        val p = proposal ?: return RoutineOutput.NONE
        if (p.offer.stillValid(state)) return RoutineOutput.NONE
        proposal = null
        return RoutineOutput.info(nowMs, p.offer.routine, "상황이 바뀌어 '${p.offer.action.label}' 제안을 거둠")
    }

    private fun settle(out: RoutineOutput, nowMs: Long): List<CareCommand> {
        var result = RoutineOutput(out.commands, out.events)
        out.offer?.let { result += route(it, nowMs) }
        val commands = commit(result, nowMs)
        val stale = withdrawStale(nowMs)
        return commands + commit(stale, nowMs)
    }

    private fun commit(out: RoutineOutput, nowMs: Long): List<CareCommand> {
        out.events.forEach(log::add)
        out.commands.forEach { command ->
            // R8이 NUNCHI 자신의 손(과 그 메아리)을 사람 손으로 배우지 않게
            command.control()?.let { (control, _) -> habits.noteOwnCommand(command, state.read(control), nowMs) }
            state = state.assume(command)
        }
        val movesWindow = out.commands.any { it is CareCommand.SetDriverWindow || it is CareCommand.SetOtherWindow }
        if (movesWindow) smoke.noteOwnWindowCommand(nowMs)
        if (movesWindow || out.commands.any { it is CareCommand.SetRecirculation }) carGuard.noteOwnCommand(nowMs)
        return out.commands
    }

    private companion object {
        /** 바깥 공기를 들이는 일 — 차의 터널 차단 중엔 권하지도, 하지도 않는다. */
        val OUTSIDE_AIR_ACTIONS = setOf(
            // 흐름 환기(흡연)는 빠진다 — 실내 담배 연기가 터널 공기보다 해롭다(사용자 결정)
            CareAction.FRESH_AIR, CareAction.OUTSIDE_AIR, CareAction.HEAT_PURGE, CareAction.DRY_AIR,
        )
        const val AFTER_GUARD_QUIET_MS = 10_000L
    }
}
