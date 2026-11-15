package app.nunchi.core

/**
 * R0 1cm의 배려 — 인도 출장에서 만난 기사의 습관을 이식.
 *
 * 손님이 타는 순간(정차 중 문 열림) 운전석 창을 살짝 내려 두면, 문이 닫힐 때 압축된 공기가 창틈으로 빠져
 * 문을 세게 닫지 않아도 되고 귀도 먹먹하지 않다. 문이 모두 닫히면 창을 원위치한다.
 *
 * 순간이 짧아 그때그때 물을 수 없으므로, 첫 승차가 끝나면 "다음부터 이렇게 해 드릴까요?"라고 한 번 여쭙고(습관 제안)
 * 허락받은 뒤부터 말없이 한다. 더운 날 30초 열기 빼기는 그때그때 여쭙는다(허락하면 그것도 알아서).
 * - 비가 오면(와이퍼 작동) 창은 그대로, 프레임리스 도어 차종도 창은 그대로(문이 스스로 압력을 뺀다)
 * - 운전자가 창을 직접 만지면 즉시 손을 뗀다
 */
class BoardingCare(private val config: () -> CareConfig) {

    private sealed interface Phase {
        data object Idle : Phase

        /** 창을 살짝 내려 둔 상태. 문이 모두 닫히면 [restoreAtMs]가 정해진다. */
        data class Cracked(val previous: Int, val sentAtMs: Long, val restoreAtMs: Long? = null) : Phase

        /** 창은 건드리지 않고 승차만 지켜보는 상태(비·프레임리스·이미 열린 창·아직 허락 전). */
        data class Watching(val reason: String, val askHabit: Boolean = false) : Phase

        data class Purging(
            val previousWindow: Int?,
            val previousFan: Int?,
            val previousRecirc: Boolean,
            val untilMs: Long,
        ) : Phase
    }

    private var phase: Phase = Phase.Idle

    /** 습관 제안은 하루 한 번만(거절·무응답이면 다음 날 다시). */
    private var habitAskedToday = false

    private val crackAllowed: Boolean get() = CareAction.WINDOW_CRACK in config().trusted

    val status: String
        get() = when (val p = phase) {
            Phase.Idle -> when {
                crackAllowed -> "대기 중 — 손님이 타면 창을 1cm 내려 둡니다"
                // 하루 한 번만 여쭙는다 — 이미 여쭸는데 '여쭤봐요'라고 하면 기다리게 만든다
                habitAskedToday -> "대기 중 — 1cm 습관은 오늘 이미 여쭤봤어요 (내일 다시 여쭤볼게요)"
                else -> "대기 중 — 첫 승차가 끝나면 1cm 습관을 여쭤봐요"
            }
            is Phase.Cracked ->
                if (p.restoreAtMs == null) "창 1cm 열어 둠 — 문이 부드럽게 닫힙니다" else "문이 모두 닫힘 — 곧 창을 올립니다"
            is Phase.Watching -> p.reason
            is Phase.Purging -> "열기 배출 중 — 외기·바람 최대"
        }

    val isActive: Boolean get() = phase != Phase.Idle

    /**
     * 다른 루틴(R6)이 창을 맡을 때 하던 일을 내려놓는다.
     * 돌려놓으려던 명령을 돌려주므로, 맡는 쪽이 그 값을 '원래 값'으로 삼을 수 있다(보내지는 않는다).
     */
    fun cancel(): List<CareCommand> {
        val restore: List<CareCommand> = when (val p = phase) {
            is Phase.Cracked -> listOf(CareCommand.SetDriverWindow(p.previous))
            is Phase.Purging -> buildList<CareCommand> {
                p.previousWindow?.let { add(CareCommand.SetDriverWindow(it)) }
                p.previousFan?.let { add(CareCommand.SetFanSpeed(it)) }
                add(CareCommand.SetRecirculation(p.previousRecirc))
            }
            else -> emptyList()
        }
        phase = Phase.Idle
        return restore
    }

    /**
     * 다른 일(R8 습관)이 운전석 창을 맡을 때: 창 원위치만 내려놓는다.
     * 열기 배출 중이면 바람·내외기 원위치는 그대로 한다.
     */
    fun releaseWindow() {
        when (val p = phase) {
            is Phase.Cracked -> phase = Phase.Idle
            is Phase.Purging -> phase = p.copy(previousWindow = null)
            else -> Unit
        }
    }

    /**
     * 사람이 운전석 창을 직접 맞췄다는 걸 확실히 알 때(시뮬레이터의 손 조작): 잠깐 내려 둔 창이라도 그때부턴 그분의 창이다.
     * 원위치를 그만두고, 내리기 전 값(= 사람이 바꾸기 시작한 값)을 돌려준다. 맡고 있던 창이 없으면 null.
     * (실차 신호는 누가 바꿨는지 몰라 [onWindowMoved]의 메아리 가드를 거친다 — 내려 둔 값과 같은 값은 거기선 가릴 수 없다)
     */
    fun claimWindow(nowMs: Long): Pair<Int, RoutineOutput>? {
        val note = RoutineOutput.info(nowMs, Routine.R0, "운전자가 창을 직접 맞춤 — 창은 그대로 둡니다")
        return when (val p = phase) {
            is Phase.Cracked -> {
                phase = Phase.Idle
                p.previous to note
            }
            is Phase.Purging -> p.previousWindow?.let { previous ->
                phase = p.copy(previousWindow = null)
                previous to note
            }
            else -> null
        }
    }

    fun resetDay() {
        habitAskedToday = false
    }

    fun onSignal(before: CabinState, after: CabinState, signal: VehicleSignal, nowMs: Long): RoutineOutput =
        when (signal) {
            is VehicleSignal.Door ->
                if (signal.open && signal.door !in before.openDoors) onDoorOpened(after, signal.door, nowMs)
                else if (!signal.open) onDoorClosed(after, nowMs)
                else RoutineOutput.NONE
            is VehicleSignal.DriverWindow -> onWindowMoved(signal.position, nowMs)
            is VehicleSignal.Wiper -> if (signal.on) onRainStarted(nowMs) else RoutineOutput.NONE
            is VehicleSignal.Gear -> if (!signal.parked) onDriveAway(nowMs) else RoutineOutput.NONE
            else -> RoutineOutput.NONE
        }

    fun onTick(state: CabinState, nowMs: Long): RoutineOutput {
        when (val p = phase) {
            is Phase.Cracked -> {
                val due = p.restoreAtMs
                if (due != null && nowMs >= due) {
                    phase = Phase.Idle
                    return RoutineOutput(
                        commands = listOf(CareCommand.SetDriverWindow(p.previous)),
                        events = listOf(CareEvent(nowMs, Routine.R0, CareKind.INFO, "문이 모두 닫힘 → 창 원위치")),
                    )
                }
            }
            is Phase.Purging -> if (nowMs >= p.untilMs) return finishPurge(p, nowMs)
            else -> Unit
        }
        return RoutineOutput.NONE
    }

    /** 열기 배출 시작(허락받았거나 '네'라고 하셨을 때). */
    fun startPurge(state: CabinState, nowMs: Long, previousWindow: Int?): RoutineOutput {
        val cfg = config()
        phase = Phase.Purging(
            previousWindow = previousWindow,
            previousFan = state.fanSpeed,
            previousRecirc = state.recirculating,
            untilMs = nowMs + cfg.heatPurgeMs,
        )
        val temp = state.cabinTempC?.toInt()
        return RoutineOutput(
            commands = listOf(CareCommand.SetRecirculation(false), CareCommand.SetFanSpeed(cfg.fanMax)),
            events = listOf(
                CareEvent(nowMs, Routine.R0, CareKind.HEAT_PURGE,
                    "실내 ${temp ?: "?"}℃ — ${cfg.heatPurgeMs / 1000}초간 외기·바람 최대로 열기 배출"),
            ),
        )
    }

    private fun onDoorOpened(state: CabinState, door: DoorId, nowMs: Long): RoutineOutput {
        when (val p = phase) {
            is Phase.Cracked -> {
                // 다른 사람이 또 타는 중: 창 올리기 보류. 그새 창이 닫혀 있으면(내리자마자 다시 타는 경우 등) 다시 내려 둔다
                val step = config().windowCrackStep
                val reopen = state.parked && !state.wiperOn && (state.driverWindow ?: 0) < step
                phase = p.copy(restoreAtMs = null, sentAtMs = if (reopen) nowMs else p.sentAtMs)
                return if (reopen) RoutineOutput(commands = listOf(CareCommand.SetDriverWindow(step))) else RoutineOutput.NONE
            }
            is Phase.Watching, is Phase.Purging -> return RoutineOutput.NONE
            Phase.Idle -> Unit
        }
        if (!state.parked) return RoutineOutput.NONE
        val cfg = config()
        val currentWindow = state.driverWindow ?: 0
        return when {
            state.wiperOn -> {
                phase = Phase.Watching("비가 와서 창은 그대로 둡니다")
                RoutineOutput.info(nowMs, Routine.R0, "${door.label} 문 열림 — 비가 와서 창은 그대로")
            }
            cfg.frameless -> {
                phase = Phase.Watching("프레임리스 도어 — 문이 스스로 압력을 뺍니다")
                RoutineOutput.NONE
            }
            currentWindow > 0 -> {
                phase = Phase.Watching("창이 이미 열려 있어 그대로 둡니다")
                RoutineOutput.NONE
            }
            !crackAllowed -> {
                // 물을 틈이 없는 순간 — 이번엔 지켜보고, 다 타면 습관으로 여쭙는다
                phase = if (habitAskedToday) {
                    Phase.Watching("승차 감지 — 1cm 습관은 오늘 이미 여쭤봐서 지켜보기만 해요")
                } else {
                    Phase.Watching("승차 감지 — 다 타면 1cm 습관을 여쭤볼게요", askHabit = true)
                }
                RoutineOutput.NONE
            }
            else -> {
                phase = Phase.Cracked(previous = currentWindow, sentAtMs = nowMs)
                RoutineOutput(
                    commands = listOf(CareCommand.SetDriverWindow(cfg.windowCrackStep)),
                    events = listOf(
                        CareEvent(nowMs, Routine.R0, CareKind.WINDOW_CRACK,
                            "${door.label} 문 열림 → 운전석 창 1cm 내림 (문이 부드럽게 닫히도록)"),
                    ),
                )
            }
        }
    }

    private fun onDoorClosed(state: CabinState, nowMs: Long): RoutineOutput {
        if (!state.allDoorsClosed) return RoutineOutput.NONE
        val p = phase
        if (p !is Phase.Cracked && p !is Phase.Watching) return RoutineOutput.NONE
        val cfg = config()
        val temp = state.cabinTempC
        // 다 내린 빈 차엔 열기를 빼 줄 사람도, 대답할 사람도 없다(착석 센서가 없으면 운전자는 있다고 본다)
        val someone = state.occupiedSeats.isNotEmpty()
        val hot = someone && temp != null && temp >= cfg.heatPurgeThresholdC

        // 1) 더운 날: 허락받았으면 바로 열기 빼기(창은 연 채로), 아니면 여쭙는다
        if (hot && CareAction.HEAT_PURGE in cfg.trusted) {
            return startPurge(state, nowMs, previousWindow = (p as? Phase.Cracked)?.previous)
        }
        // 창 원위치 예약(또는 지켜보기 끝)
        phase = when (p) {
            is Phase.Cracked -> p.copy(restoreAtMs = nowMs + cfg.restoreDelayMs)
            else -> Phase.Idle
        }
        if (hot) return RoutineOutput(offer = purgeOffer(temp!!))

        // 2) 1cm 습관을 아직 허락받지 않았으면 승차가 끝난 지금 여쭙는다
        if (p is Phase.Watching && p.askHabit && someone) {
            habitAskedToday = true
            return RoutineOutput(offer = habitOffer())
        }
        return RoutineOutput.NONE
    }

    private fun purgeOffer(temp: Float) = Offer(
        action = CareAction.HEAT_PURGE,
        ask = "차 안이 ${temp.toInt()}℃예요. 30초만 바람을 세게 해서 열기를 뺄까요?",
        tell = "30초만 열기 뺄게요.",
        reason = "문이 모두 닫힘 · 실내 ${temp.toInt()}℃",
        stillValid = { s -> (s.cabinTempC ?: 0f) >= config().heatPurgeThresholdC },
        apply = { s, now -> startPurge(s, now, previousWindow = null) },
    )

    private fun habitOffer() = Offer(
        action = CareAction.WINDOW_CRACK,
        ask = "다음부터 손님 타실 때 창을 1cm 내려 둘까요? 문이 부드럽게 닫혀요.",
        tell = null,
        reason = "첫 승차 끝(문 열림 → 모두 닫힘)",
        habit = true,
        apply = { _, now -> RoutineOutput.info(now, Routine.R0, "1cm 습관 허락받음 — 다음 승차부터 말없이 창을 내려 둡니다") },
    )

    private fun onWindowMoved(position: Int, nowMs: Long): RoutineOutput {
        val p = phase as? Phase.Cracked ?: return RoutineOutput.NONE
        // 우리가 보낸 명령의 메아리(또는 아직 반영 안 된 옛 값)는 2초간 무시
        if (nowMs - p.sentAtMs < ECHO_GUARD_MS) return RoutineOutput.NONE
        if (position == config().windowCrackStep) return RoutineOutput.NONE
        phase = Phase.Idle
        return RoutineOutput.info(nowMs, Routine.R0, "운전자가 창을 직접 조작 — 개입을 멈춥니다")
    }

    private fun onRainStarted(nowMs: Long): RoutineOutput {
        val p = phase as? Phase.Cracked ?: return RoutineOutput.NONE
        phase = Phase.Idle
        return RoutineOutput(
            commands = listOf(CareCommand.SetDriverWindow(p.previous)),
            events = listOf(CareEvent(nowMs, Routine.R0, CareKind.INFO, "비 감지 → 창 바로 닫음")),
        )
    }

    private fun onDriveAway(nowMs: Long): RoutineOutput = when (val p = phase) {
        is Phase.Cracked -> {
            phase = Phase.Idle
            RoutineOutput(
                commands = listOf(CareCommand.SetDriverWindow(p.previous)),
                events = listOf(CareEvent(nowMs, Routine.R0, CareKind.INFO, "출발 → 창 원위치")),
            )
        }
        is Phase.Purging -> {
            // 출발하면 창은 바로 닫고, 환기는 30초를 마저 채운다
            val window = p.previousWindow
            if (window == null) {
                RoutineOutput.NONE
            } else {
                phase = p.copy(previousWindow = null)
                RoutineOutput(
                    commands = listOf(CareCommand.SetDriverWindow(window)),
                    events = listOf(CareEvent(nowMs, Routine.R0, CareKind.INFO, "출발 → 창은 바로 닫고 환기는 마저 진행")),
                )
            }
        }
        is Phase.Watching -> {
            phase = Phase.Idle
            RoutineOutput.NONE
        }
        Phase.Idle -> RoutineOutput.NONE
    }

    private fun finishPurge(p: Phase.Purging, nowMs: Long): RoutineOutput {
        phase = Phase.Idle
        val cfg = config()
        val commands = buildList<CareCommand> {
            p.previousWindow?.let { add(CareCommand.SetDriverWindow(it)) }
            add(CareCommand.SetFanSpeed(p.previousFan ?: cfg.fanDefault))
            add(CareCommand.SetRecirculation(p.previousRecirc))
        }
        return RoutineOutput(
            commands = commands,
            events = listOf(CareEvent(nowMs, Routine.R0, CareKind.INFO, "열기 배출 완료 → 창·공조 원위치")),
        )
    }

    private companion object {
        const val ECHO_GUARD_MS = 2_000L
    }
}
