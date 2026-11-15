package app.nunchi.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * R1 숨 쉬는 차 — 가상 CO2 센서로 '졸음 농도'에 닿기 전에 공기를 바꾼다.
 *
 * - 내기순환 중 추정 CO2가 1,900ppm에 닿으면 "공기가 탁해지고 있어요. 한 번 바꿀까요?" → 좋다고 하면 외기 90초 → 내기 복귀
 *   ("다음부턴 알아서"를 허락받았으면 "공기 한 번 바꿀게요" 하고 바로)
 * - 그 사이 운전자가 내기를 다시 켜면 존중하고 물러난다(쿨다운 후 재판단). 거절·무응답이어도 쿨다운 뒤에 다시 판단
 * - 3명 이상 탑승 + 내기순환이면 주행 2분 뒤 '외기로 둘까요?'를 한 번 여쭙는다
 */
class FreshAirCare(private val config: () -> CareConfig) {
    val model = Co2Model()

    val ppm: Double get() = model.ppm

    var peakPpm: Double = model.ppm
        private set

    var bursts: Int = 0
        private set

    private var burstUntilMs: Long? = null
    private var lastBurstEndMs: Long = Long.MIN_VALUE / 2
    private var lastOfferMs: Long = Long.MIN_VALUE / 2
    private var suggestedThisTrip = false
    private var drivingSinceMs: Long? = null

    val inBurst: Boolean get() = burstUntilMs != null

    /** [asking] = 지금 '공기를 바꿀까요?'를 여쭙고 대답을 기다리는 중. */
    fun status(state: CabinState, nowMs: Long = 0L, asking: Boolean = false): String = when {
        inBurst -> "외기로 공기 교체 중"
        !state.recirculating -> "외기 유입 중 — 공기 맑음"
        ppm >= config().co2TriggerPpm -> {
            // 탁해졌다고 마음대로 바꾸지 않는다 — 여쭙고, 사양·무응답이면 잠시 뒤 다시 여쭙는다
            val waitMs = max(lastBurstEndMs, lastOfferMs) + config().freshAirCooldownMs - nowMs
            when {
                asking -> "공기가 탁해요 — 바꿀지 여쭤보는 중"
                waitMs > 0 -> "공기가 탁해요 — ${kotlin.math.ceil(waitMs / 60_000.0).toInt()}분 뒤 다시 여쭤볼게요"
                else -> "공기가 탁해요 — 곧 여쭤볼게요"
            }
        }
        else -> {
            val minutes = minutesTo(2_000.0, state)
            if (minutes == null) "내기순환 — 안정" else "내기순환 — 2,000ppm까지 약 ${minutes.roundToInt()}분"
        }
    }

    fun minutesTo(targetPpm: Double, state: CabinState): Double? =
        model.minutesUntil(targetPpm, state.occupants, state.recirculating)

    fun resetPeak() {
        peakPpm = model.ppm
        bursts = 0
    }

    fun onSignal(before: CabinState, after: CabinState, signal: VehicleSignal, nowMs: Long): RoutineOutput {
        when (signal) {
            is VehicleSignal.Door -> if (signal.open && signal.door !in before.openDoors) model.doorOpened()
            is VehicleSignal.Gear -> when {
                !signal.parked && before.parked -> {
                    suggestedThisTrip = false
                    drivingSinceMs = nowMs
                }
                signal.parked -> drivingSinceMs = null
            }
            is VehicleSignal.Recirculation -> if (signal.on && inBurst) {
                burstUntilMs = null
                lastBurstEndMs = nowMs
                // 운전자일 수도, 차의 터널 차단일 수도 있다 — 누구든 존중하고 물러난다
                return RoutineOutput.info(nowMs, Routine.R1, "내기순환으로 바뀜 — 공기 교체를 멈추고 물러남")
            }
            else -> Unit
        }
        return RoutineOutput.NONE
    }

    /**
     * R6가 외기를 맡는 동안 R1은 CO2 계산만 하고 손을 뗀다.
     * 공기 교체 중이었다면 돌려놓으려던 '내기 복귀'를 돌려준다(보내지는 않는다).
     */
    fun yieldControl(nowMs: Long): List<CareCommand> {
        if (burstUntilMs == null) return emptyList()
        burstUntilMs = null
        lastBurstEndMs = nowMs
        return listOf(CareCommand.SetRecirculation(true))
    }

    fun onTick(state: CabinState, nowMs: Long, dtSeconds: Double, allowActions: Boolean = true): RoutineOutput {
        model.step(dtSeconds, state.occupants, state.recirculating)
        peakPpm = max(peakPpm, model.ppm)
        if (!allowActions) return RoutineOutput.NONE
        val cfg = config()

        val until = burstUntilMs
        if (until != null) {
            if (nowMs < until) return RoutineOutput.NONE
            burstUntilMs = null
            lastBurstEndMs = nowMs
            return RoutineOutput(
                commands = listOf(CareCommand.SetRecirculation(true)),
                events = listOf(CareEvent(nowMs, Routine.R1, CareKind.INFO,
                    "외기 ${cfg.freshAirBurstSeconds}초 완료 → 내기순환 복귀 (추정 ${ppmText()})")),
            )
        }

        if (state.recirculating && model.ppm >= cfg.co2TriggerPpm &&
            nowMs - lastBurstEndMs >= cfg.freshAirCooldownMs &&
            nowMs - lastOfferMs >= cfg.freshAirCooldownMs
        ) {
            lastOfferMs = nowMs
            return RoutineOutput(offer = Offer(
                action = CareAction.FRESH_AIR,
                ask = "공기가 탁해지고 있어요. 한 번 바꿀까요?",
                tell = "공기 한 번 바꿀게요.",
                reason = "내기순환 · 추정 CO2 ${ppmText()}",
                stillValid = { s -> s.recirculating && burstUntilMs == null },
                apply = { _, now -> startBurst(now) },
            ))
        }

        val drivingFor = drivingSinceMs?.let { nowMs - it }
        val settledIn = !state.parked && drivingFor != null && drivingFor >= cfg.suggestAfterDrivingMs
        if (settledIn && state.recirculating && state.occupants >= cfg.crowdedOccupants && !suggestedThisTrip) {
            suggestedThisTrip = true
            val n = state.occupants
            // 터널은 차의 '외부 공기 유입 방지 제어'(현대·기아)가 맡는다 — NUNCHI가 따로 약속하지 않는다
            return RoutineOutput(offer = Offer(
                action = CareAction.OUTSIDE_AIR,
                ask = "${n}명이 타면 공기가 금방 탁해져요. 외기로 둘까요?",
                tell = "${n}명이 타셔서 외기로 둘게요.",
                reason = "${n}명 탑승 · 내기순환 · 주행 ${cfg.suggestAfterDrivingMs / 60_000}분",
                stillValid = { s -> s.recirculating },
                apply = { _, now ->
                    RoutineOutput(
                        commands = listOf(CareCommand.SetRecirculation(false)),
                        events = listOf(CareEvent(now, Routine.R1, CareKind.FRESH_AIR_SUGGEST,
                            "${n}명 탑승 → CO2 상승 약 ${n}배 — 외기 기본으로 전환")),
                    )
                },
            ))
        }
        return RoutineOutput.NONE
    }

    /** 공기 교체 시작(허락받았거나 '네'라고 하셨을 때): 외기 N초 → 내기 복귀. */
    fun startBurst(nowMs: Long): RoutineOutput {
        val cfg = config()
        burstUntilMs = nowMs + cfg.freshAirBurstSeconds * 1_000L
        bursts++
        return RoutineOutput(
            commands = listOf(CareCommand.SetRecirculation(false)),
            events = listOf(CareEvent(nowMs, Routine.R1, CareKind.FRESH_AIR,
                "추정 CO2 ${ppmText()} — 2,000ppm 전에 외기 ${cfg.freshAirBurstSeconds}초 전환")),
        )
    }

    private fun ppmText() = "%,dppm".format(model.ppm.roundToInt())
}
