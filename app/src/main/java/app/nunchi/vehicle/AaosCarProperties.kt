package app.nunchi.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.nunchi.core.VehicleSignal

/**
 * AAOS 표준 차량 속성 구독.
 * - Pleos Vehicle SDK의 주행가능거리·기어 API가 지원 중단 예정이라, Pleos 안내대로 표준 속성
 *   (RANGE_REMAINING, GEAR_SELECTION)을 직접 읽는다.
 * - 주행 중 UX 제한(운전 중 화면 단순화)을 구독해 화면이 운전용 모드로 바뀌게 한다.
 * 표준 Car 라이브러리가 없는 기기에서도 앱이 죽지 않도록 모든 호출을 감싼다.
 */
class AaosCarProperties(
    private val context: Context,
    private val onSignal: (VehicleSignal) -> Unit,
    private val onDiag: (String) -> Unit,
    private val onDrivingRestricted: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>?) {
            value?.let(::handle)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            diag("표준 속성 오류: ${name(propertyId)}")
        }
    }

    fun start() {
        if (car != null) return
        try {
            val c = Car.createCar(context.applicationContext) ?: run {
                diag("표준 Car 서비스 없음")
                return
            }
            car = c
            subscribeUxRestrictions(c)
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            manager = m
            subscribe(m, VehiclePropertyIds.RANGE_REMAINING)
            subscribe(m, VehiclePropertyIds.GEAR_SELECTION)
            // R8: 습관을 가르는 날씨(추운 아침 / 더운 낮)
            subscribe(m, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE)
        } catch (t: Throwable) {
            diag("표준 Car API 사용 불가: ${t.javaClass.simpleName}")
        }
    }

    private fun subscribeUxRestrictions(c: Car) {
        try {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = ux
            ux.registerListener { restrictions ->
                val restricted = restrictions?.isRequiresDistractionOptimization == true
                main.post { onDrivingRestricted(restricted) }
            }
            val now = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            main.post { onDrivingRestricted(now) }
            diag("주행 중 UX 제한 구독 ✓")
        } catch (t: Throwable) {
            diag("주행 중 UX 제한 구독 실패: ${t.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION")
    private fun subscribe(m: CarPropertyManager, propertyId: Int) {
        try {
            val ok = m.registerCallback(callback, propertyId, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            diag("${name(propertyId)} 구독 ${if (ok) "✓" else "✗"}")
            runCatching { m.getProperty<Any>(propertyId, 0) }.getOrNull()?.let(::handle)
        } catch (t: Throwable) {
            diag("${name(propertyId)} 실패: ${t.javaClass.simpleName} ${t.message.orEmpty()}")
        }
    }

    private fun handle(value: CarPropertyValue<*>) {
        val signal = when (value.propertyId) {
            // 표준 단위는 미터
            VehiclePropertyIds.RANGE_REMAINING -> (value.value as? Float)?.let { VehicleSignal.RangeRemaining(it / 1_000f) }
            VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { VehicleSignal.Gear(parked = it == VehicleGear.GEAR_PARK) }
            VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE -> (value.value as? Float)?.let { VehicleSignal.OutsideTemperature(it) }
            else -> null
        } ?: return
        main.post { onSignal(signal) }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        runCatching { uxManager?.unregisterListener() }
        runCatching { manager?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        uxManager = null
        manager = null
        car = null
    }

    private fun name(propertyId: Int) = runCatching { VehiclePropertyIds.toString(propertyId) }.getOrDefault("$propertyId")

    private fun diag(message: String) {
        main.post { onDiag(message) }
    }
}
