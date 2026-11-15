package app.nunchi.core

import kotlin.math.roundToInt

enum class RangeLevel(val label: String) {
    UNKNOWN("정보 없음"),
    OK("여유"),
    TIGHT("빠듯"),
    SHORT("부족"),
}

/**
 * R2 마지막 20km 코치 — 경고등 대신 '계획'을 보고한다.
 *
 * - 목적지가 있으면 여유율 = (주행가능거리 − 남은 거리) / 남은 거리
 *   20% 미만 → 에너지 세이빙(에어컨 쉼, 풍량↓) + 코칭 / 0% 미만 → 충전 계획 안내
 * - 목적지가 없으면 주행가능거리 20km(빠듯)·10km(부족) 기준
 * - 단계가 나빠질 때만 말한다(같은 말 반복 금지). 여유가 돌아오면 쉬게 했던 에어컨·바람을 원래대로 돌려놓는다
 *   (그 사이 운전자가 직접 바꾼 것은 건드리지 않는다)
 */
class RangeCoach(
    private val config: () -> CareConfig,
    /** 다른 루틴(R6 흐름 환기)이 바람을 맡고 있으면 바람은 건드리지 않는다. */
    private val fanBusy: () -> Boolean = { false },
) {
    var level: RangeLevel = RangeLevel.UNKNOWN
        private set

    var marginPct: Int? = null
        private set

    private var tireAlerted = false

    /** 에너지 절약으로 바꿔 둔 것(여유가 돌아오면 원래대로): 에어컨이 켜져 있었는지, 줄이기 전 바람 세기. */
    private data class Saved(val acWasOn: Boolean, val fanBefore: Int?, val fanLowered: Boolean)

    private var saved: Saved? = null

    fun evaluate(state: CabinState): Pair<RangeLevel, Int?> {
        val cfg = config()
        val range = state.rangeKm ?: return RangeLevel.UNKNOWN to null
        val route = state.routeKm
        if (route != null && route > 0f) {
            val margin = (range - route) / route
            val newLevel = when {
                margin < 0f -> RangeLevel.SHORT
                margin < cfg.rangeMarginTight -> RangeLevel.TIGHT
                else -> RangeLevel.OK
            }
            return newLevel to (margin * 100).roundToInt()
        }
        val newLevel = when {
            range <= cfg.lastStretchKm / 2 -> RangeLevel.SHORT
            range <= cfg.lastStretchKm -> RangeLevel.TIGHT
            else -> RangeLevel.OK
        }
        return newLevel to null
    }

    fun onSignal(state: CabinState, signal: VehicleSignal, nowMs: Long): RoutineOutput {
        if (signal is VehicleSignal.TireLow) return onTire(signal.low, nowMs)
        if (signal !is VehicleSignal.RangeRemaining && signal !is VehicleSignal.RouteRemaining) {
            return RoutineOutput.NONE
        }
        val (newLevel, pct) = evaluate(state)
        val old = level
        level = newLevel
        marginPct = pct
        if (newLevel == old) return RoutineOutput.NONE
        val range = state.rangeKm?.roundToInt() ?: 0
        val route = state.routeKm
        return when (newLevel) {
            RangeLevel.TIGHT -> if (old == RangeLevel.SHORT) {
                RoutineOutput.info(nowMs, Routine.R2, "여유 일부 회복 (빠듯)")
            } else {
                // 차는 속도를 대신 줄이지 않는다 — 에어컨·바람만 여쭙고, 속도는 권하기만 한다
                val situation = if (route != null) "이대로 가면 도착 후 여유가 ${pct ?: 0}%예요." else "주행가능거리가 ${range}km 남았어요."
                val why = if (route != null) "여유율 ${pct ?: 0}%" else "주행가능 ${range}km"
                RoutineOutput(offer = Offer(
                    action = CareAction.ENERGY_SAVE,
                    ask = "$situation 에어컨을 잠시 쉬고 바람을 줄일까요?",
                    tell = "$situation 에어컨은 잠시 쉬고 바람을 줄일게요. 경제속도로 가시면 더 멀리 가요.",
                    reason = why,
                    stillValid = { level == RangeLevel.TIGHT || level == RangeLevel.SHORT },
                    apply = { s, now ->
                        val fan = s.fanSpeed
                        val lowerFan = !fanBusy() && (fan == null || fan > 2)
                        saved = Saved(acWasOn = s.acOn == true, fanBefore = fan, fanLowered = lowerFan)
                        val commands = buildList<CareCommand> {
                            add(CareCommand.SetAc(false))
                            if (lowerFan) add(CareCommand.SetFanSpeed(2))
                        }
                        RoutineOutput(
                            commands = commands,
                            events = listOf(CareEvent(now, Routine.R2, CareKind.ENERGY_SAVE,
                                "$why → 에어컨 쉼${if (commands.size > 1) "·풍량 2단" else ""}, 경제속도 권유")),
                        )
                    },
                ))
            }
            RangeLevel.SHORT -> {
                val deficit = route?.let { (it - (state.rangeKm ?: 0f)).roundToInt().coerceAtLeast(1) }
                val say = when {
                    // 휴게소까지도 못 갈 수 있는 상황: 목적지 얘기 대신 가장 가까운 충전소
                    range <= NEAREST_ONLY_KM -> "주행가능거리가 ${range}km뿐이에요. 가장 가까운 충전소로 바로 가시죠."
                    deficit != null -> "이대로면 목적지 ${deficit}km 전에 배터리가 부족해요. 다음 휴게소에서 충전하시죠."
                    else -> "주행가능거리가 ${range}km예요. 지금 가까운 충전소로 가는 게 안전해요."
                }
                RoutineOutput(
                    commands = listOf(CareCommand.Say(say)),
                    events = listOf(CareEvent(nowMs, Routine.R2, CareKind.RANGE_ALERT,
                        when {
                            range <= NEAREST_ONLY_KM -> "주행가능 ${range}km → 가장 가까운 충전소 안내"
                            deficit != null -> "목적지까지 ${deficit}km 모자람 → 충전 계획 안내"
                            else -> "주행가능 ${range}km → 충전소 안내"
                        })),
                )
            }
            RangeLevel.OK -> if (old == RangeLevel.UNKNOWN) RoutineOutput.NONE else recover(state, nowMs)
            RangeLevel.UNKNOWN -> RoutineOutput.NONE
        }
    }

    /** 여유가 돌아왔다: 쉬게 했던 에어컨·바람을 원래대로. 그 사이 운전자가 직접 바꾼 것은 그대로 둔다. */
    private fun recover(state: CabinState, nowMs: Long): RoutineOutput {
        val s = saved ?: return RoutineOutput.info(nowMs, Routine.R2, "주행 여유 회복")
        saved = null
        val commands = buildList<CareCommand> {
            if (s.acWasOn && state.acOn == false) add(CareCommand.SetAc(true))
            if (s.fanLowered && state.fanSpeed == 2 && !fanBusy()) add(CareCommand.SetFanSpeed(s.fanBefore ?: config().fanDefault))
        }
        val what = if (commands.isEmpty()) "" else " → 에어컨·바람 원래대로"
        return RoutineOutput(
            commands = commands,
            events = listOf(CareEvent(nowMs, Routine.R2, CareKind.INFO, "주행 여유 회복$what")),
        )
    }

    private companion object {
        const val NEAREST_ONLY_KM = 5
    }

    private fun onTire(low: Boolean, nowMs: Long): RoutineOutput {
        if (!low) {
            tireAlerted = false
            return RoutineOutput.NONE
        }
        if (tireAlerted) return RoutineOutput.NONE
        tireAlerted = true
        return RoutineOutput(
            commands = listOf(CareCommand.Say("타이어 공기압이 낮아요. 속도를 조금 줄이고 가까운 정비소에 들르시죠.")),
            events = listOf(CareEvent(nowMs, Routine.R2, CareKind.TIRE_ALERT, "타이어 저압 → 감속·정비소 안내")),
        )
    }
}
