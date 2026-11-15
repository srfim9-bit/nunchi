package app.nunchi.core

import kotlin.math.exp
import kotlin.math.ln

/**
 * 가상 CO2 센서 — 실내 질량 균형 모델
 *
 *   dC/dt = N·s − λ·(C − Cout)
 *
 * N: 탑승 인원, s: 1인당 CO2 발생(약 3m³ 실내 기준 ppm/h), λ: 환기 횟수(1/h, 내기 0.5 / 외기 50)
 * 보정: 한국도로공사(2026.7) 실험 — 2인, 밀폐 내기순환, 30분에 600 → 5,000ppm.
 * 매 스텝 선형 ODE의 정확해를 쓰므로 데모 가속(큰 dt)에서도 안정적이다.
 */
class Co2Model(
    val outsidePpm: Double = 600.0,
    val perPersonPpmPerHour: Double = 4973.0,
    val recircExchangePerHour: Double = 0.5,
    val freshExchangePerHour: Double = 50.0,
    initialPpm: Double = outsidePpm,
) {
    var ppm: Double = initialPpm
        private set

    fun step(dtSeconds: Double, occupants: Int, recirculating: Boolean): Double {
        require(dtSeconds >= 0.0) { "dt must be >= 0" }
        val lambda = exchangeRate(recirculating)
        val eq = equilibrium(occupants, recirculating)
        ppm = eq + (ppm - eq) * exp(-lambda * dtSeconds / 3600.0)
        return ppm
    }

    /** 문이 열리면 실내 공기 상당 부분이 바깥 공기와 섞인다. */
    fun doorOpened(mixFraction: Double = 0.7) {
        ppm = outsidePpm + (ppm - outsidePpm) * (1.0 - mixFraction)
    }

    fun reset(value: Double = outsidePpm) {
        ppm = value
    }

    /** 지금 상태가 유지될 때 [targetPpm]까지 남은 분. 도달하지 않으면 null. */
    fun minutesUntil(targetPpm: Double, occupants: Int, recirculating: Boolean): Double? {
        if (ppm >= targetPpm) return 0.0
        val eq = equilibrium(occupants, recirculating)
        if (eq <= targetPpm) return null
        val ratio = (targetPpm - eq) / (ppm - eq)
        return -ln(ratio) / exchangeRate(recirculating) * 60.0
    }

    private fun exchangeRate(recirculating: Boolean) =
        if (recirculating) recircExchangePerHour else freshExchangePerHour

    private fun equilibrium(occupants: Int, recirculating: Boolean) =
        outsidePpm + occupants.coerceAtLeast(1) * perPersonPpmPerHour / exchangeRate(recirculating)
}
