package app.nunchi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.nunchi.core.CareAction
import app.nunchi.core.Control
import app.nunchi.core.DoorId
import app.nunchi.core.SeatId
import app.nunchi.core.VehicleSignal
import app.nunchi.core.WindowId
import kotlinx.coroutines.flow.StateFlow

/** 화면용 얇은 창구. 실제 일은 상시 서비스 안의 [CareRuntime]이 한다. */
class NunchiViewModel(app: Application) : AndroidViewModel(app) {
    private val runtime = (app as NunchiApp).runtime.also { it.start() }

    val ui: StateFlow<UiState> = runtime.ui

    fun onCarPermissionsResult(granted: Map<String, Boolean>) = runtime.onCarPermissionsResult(granted)
    fun diag(message: String) = runtime.addDiagnostic(message)

    fun toggleDoor(door: DoorId) = runtime.toggleDoor(door)
    fun setOccupants(count: Int) = runtime.setOccupants(count)
    fun setRecirculation(on: Boolean) = runtime.simulate(VehicleSignal.Recirculation(on))
    fun setWeather(level: Int) = runtime.simulateWeather(level)
    fun setCabinTemp(celsius: Float) = runtime.simulate(VehicleSignal.CabinTemperature(celsius))
    fun setParked(parked: Boolean) = runtime.simulate(VehicleSignal.Gear(parked))
    fun setTireLow(low: Boolean) = runtime.simulate(VehicleSignal.TireLow(low))
    fun setRoute(km: Float?) = runtime.simulate(VehicleSignal.RouteRemaining(km))
    fun changeRange(deltaKm: Float) = runtime.changeRange(deltaKm)
    fun setDemoSpeed(speed: Int) = runtime.setDemoSpeed(speed)
    fun setFrameless(frameless: Boolean) = runtime.setFrameless(frameless)
    fun setVoice(on: Boolean) = runtime.setVoice(on)
    fun testVoice() = runtime.testVoice()
    fun setVoiceAnswer(on: Boolean) = runtime.setVoiceAnswer(on)
    fun approve(always: Boolean = false) = runtime.approve(always)
    fun reject() = runtime.reject()
    fun revokeTrust(action: CareAction) = runtime.revokeTrust(action)
    fun stopVentilation() = runtime.stopVentilation()
    fun simulateGesture(seat: SeatId) = runtime.simulateGesture(seat)
    fun simulateSmokeSpike() = runtime.simulateSmokeSpike()
    fun simulateCleanAir() = runtime.simulateCleanAir()
    fun toggleOtherWindow(window: WindowId) = runtime.toggleOtherWindow(window)
    fun showReport() = runtime.showReport()
    fun dismissReport() = runtime.dismissReport()
    fun toggleDiagnostics() = runtime.toggleDiagnostics()
    fun resetDay() = runtime.resetDay()

    // R8 배우는 눈치
    fun setHour(hour: Int?) = runtime.setHour(hour)
    fun setOutsideTemp(celsius: Float) = runtime.setOutsideTemp(celsius)
    fun board() = runtime.board()
    fun alight() = runtime.alight()
    fun manual(control: Control, value: Int) = runtime.manual(control, value)
    fun uniqueCombo() = runtime.uniqueCombo()
    fun carGlassAir() = runtime.carGlassAir()
    fun revertGlassAir() = runtime.revertGlassAir()
    fun forgetHabit(id: String) = runtime.forgetHabit(id)
    fun untrustHabit(id: String) = runtime.untrustHabit(id)
    fun forgetAversion(key: String) = runtime.forgetAversion(key)
    fun clearLearning() = runtime.clearLearning()
}
