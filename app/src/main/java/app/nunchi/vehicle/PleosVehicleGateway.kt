package app.nunchi.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.Area
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessColorListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import ai.pleos.playground.vehicle.listener.HvacAutoStateListener
import ai.pleos.playground.vehicle.listener.HvacDefrosterStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacSeatVentilationListener
import ai.pleos.playground.vehicle.listener.HvacSideMirrorHeatStateListener
import ai.pleos.playground.vehicle.listener.HvacSteeringWheelHeatStateListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.nunchi.core.CareCommand
import app.nunchi.core.Control
import app.nunchi.core.DoorId
import app.nunchi.core.FanDirection
import app.nunchi.core.SeatId
import app.nunchi.core.VehicleSignal
import app.nunchi.core.WindowId
import kotlin.math.roundToInt

/**
 * Pleos Vehicle SDK ↔ 눈치 엔진 어댑터.
 *
 * - 차량 이벤트(리스너)를 [VehicleSignal]로 바꿔 메인 스레드로 넘긴다(주기적으로 묻지 않고, 바뀔 때 알림을 받는다).
 * - 엔진의 [CareCommand]를 SDK set API로 실행한다. 주행 제어 API는 쓰지 않는다.
 * - 도메인마다 독립적으로 실패를 처리해, 일부가 지원되지 않아도 나머지는 동작한다.
 */
class PleosVehicleGateway(
    context: Context,
    private val onSignal: (VehicleSignal) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onCapabilities: (Capabilities) -> Unit,
) {
    data class Capabilities(
        val windowCrackStep: Int?,
        val ventWindowStep: Int?,
        val windowMax: Int?,
        val fanMax: Int?,
    )

    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context.applicationContext)
    private val unregisters = mutableListOf<() -> Unit>()
    private val reported = mutableSetOf<String>()
    private var initialized = false

    private var climateArea = SeatArea(SeatValue.ROW_1_LEFT)
    private val driverWindow = WindowArea(WindowValue.ROW_1_LEFT)
    private val windshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val rearWindow = WindowArea(WindowValue.REAR_WINDSHIELD)
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private var ambientArea = SeatArea(SeatValue.ROW_1_LEFT)
    private var mirrorArea = MirrorArea(MirrorValue.DRIVER_LEFT, MirrorValue.DRIVER_RIGHT)

    private var windowCrackStep: Int? = null
    private var ventWindowStep: Int? = null
    private var windowMax: Int? = null
    private var fanMax: Int? = null

    private val doors = mapOf(
        DoorId.FRONT_LEFT to DoorValue.ROW_1_LEFT,
        DoorId.FRONT_RIGHT to DoorValue.ROW_1_RIGHT,
        DoorId.REAR_LEFT to DoorValue.ROW_2_LEFT,
        DoorId.REAR_RIGHT to DoorValue.ROW_2_RIGHT,
    )
    private val seats = mapOf(
        SeatId.DRIVER to SeatValue.ROW_1_LEFT,
        SeatId.PASSENGER to SeatValue.ROW_1_RIGHT,
        SeatId.REAR_LEFT to SeatValue.ROW_2_LEFT,
        SeatId.REAR_RIGHT to SeatValue.ROW_2_RIGHT,
    )
    private val otherWindows = mapOf(
        WindowId.PASSENGER to WindowArea(WindowValue.ROW_1_RIGHT),
        WindowId.REAR_LEFT to WindowArea(WindowValue.ROW_2_LEFT),
        WindowId.REAR_RIGHT to WindowArea(WindowValue.ROW_2_RIGHT),
    )
    private val wheels = listOf(WheelValue.LEFT_FRONT, WheelValue.RIGHT_FRONT, WheelValue.LEFT_REAR, WheelValue.RIGHT_REAR)
    private val tirePressure = mutableMapOf<WheelValue, Float>()
    private val tireLowThreshold = mutableMapOf<WheelValue, Float>()

    val isConnected: Boolean get() = initialized

    /** Vehicle Service에 바인딩. Pleos 에뮬레이터/차량이 아니면 false → 데모 모드. */
    fun connect(): Boolean = try {
        vehicle.initialize()
        initialized = true
        true
    } catch (t: Throwable) {
        onStatus("차량 서비스에 연결하지 못했어요 (${t.javaClass.simpleName}) — 데모 모드로 실행합니다")
        false
    }

    fun subscribe() {
        if (!initialized) return
        subscribeDoors()
        subscribeSeats()
        subscribeClimate()
        subscribeWindow()
        subscribeOtherWindows()
        subscribeFanDirection()
        subscribeWiper()
        subscribeGear()
        subscribeRange()
        subscribeTires()
    }

    fun execute(command: CareCommand) {
        if (!initialized) return
        when (command) {
            is CareCommand.SetDriverWindow -> guard("창문") {
                vehicle.getWindow().setWindowPosition(
                    driverWindow, command.position,
                    { _, value -> onStatus("운전석 창 위치 → $value") },
                    { fail("창문 제어", it) },
                )
            }
            is CareCommand.SetRecirculation -> guard("내기순환") {
                vehicle.getHVAC().setHvacRecirculation(
                    climateArea, command.on,
                    { _, value -> onStatus("내기순환 → ${if (value == true) "ON" else "OFF(외기)"}") },
                    { fail("내기순환 제어", it) },
                )
            }
            is CareCommand.SetFanSpeed -> guard("풍량") {
                val level = fanMax?.let { command.level.coerceAtMost(it) } ?: command.level
                vehicle.getHVAC().setHvacFanSpeed(
                    climateArea, level,
                    { _, value -> onStatus("풍량 → $value") },
                    { fail("풍량 제어", it) },
                )
            }
            is CareCommand.SetFanDirection -> guard("바람 방향") {
                vehicle.getHVAC().setHvacFanDirection(
                    climateArea, FanDirectionType.valueOf(command.direction.name),
                    { _, value -> value?.toCore()?.let { onStatus("바람 방향 → ${it.label}"); emit(VehicleSignal.FanDirectionChanged(it)) } },
                    { fail("바람 방향 제어", it) },
                )
            }
            is CareCommand.SetOtherWindow -> guard("창문") {
                val area = otherWindows.getValue(command.window)
                vehicle.getWindow().setWindowPosition(
                    area, command.position,
                    { _, value -> onStatus("${command.window.label} 창 위치 → $value") },
                    { fail("${command.window.label} 창 제어", it) },
                )
            }
            is CareCommand.SetAc -> guard("에어컨") {
                vehicle.getHVAC().setHvacAc(
                    climateArea, command.on,
                    { _, value -> onStatus("에어컨 → ${if (value == true) "ON" else "OFF"}") },
                    { fail("에어컨 제어", it) },
                )
            }
            is CareCommand.SetComfort -> setComfort(command.control, command.value)
            is CareCommand.Say, is CareCommand.Notice -> Unit // 화면·음성은 앱 쪽에서 처리
        }
    }

    /** R8: 배운 습관을 맞출 때 쓰는 그 밖의 실내 설정. 지원하지 않는 차는 진단에 한 번만 남기고 넘어간다. */
    private fun setComfort(control: Control, value: Int) = guard(control.label) {
        val done: (Any?) -> Unit = { v -> onStatus("${control.label} → $v") }
        val failed: (Exception) -> Unit = { fail("${control.label} 제어", it) }
        when (control) {
            Control.TEMP -> vehicle.getHVAC().setHvacTemperature(climateArea, value / 10f, TemperatureUnit.CELSIUS, { _, v -> done(v) }, failed)
            Control.AUTO -> vehicle.getHVAC().setHvacAuto(climateArea, value > 0, { _, v -> done(v) }, failed)
            Control.SEAT_HEAT -> vehicle.getSeat().setHvacSeatTemperature(driverSeat, value, { _, v -> done(v) }, failed)
            Control.SEAT_VENT -> vehicle.getSeat().setHvacSeatVentilation(driverSeat, value, { _, v -> done(v) }, failed)
            Control.WHEEL_HEAT -> vehicle.getSteeringWheel().setHvacSteeringWheelHeat(value, { v -> done(v) }, failed)
            Control.FRONT_DEFROST -> vehicle.getHVAC().setHvacDefroster(windshield, value > 0, { _, v -> done(v) }, failed)
            Control.REAR_DEFROST -> vehicle.getHVAC().setHvacDefroster(rearWindow, value > 0, { _, v -> done(v) }, failed)
            Control.MIRROR_HEAT -> vehicle.getSideMirror().setHvacSideMirrorHeat(mirrorArea, value, { _, v -> done(v) }, failed)
            Control.AMBIENT -> vehicle.getLight().setAmbientLightSwitch(
                ambientArea, if (value > 0) LightSwitch.ON else LightSwitch.OFF, { _, v -> done(v) }, failed,
            )
            Control.AMBIENT_COLOR -> vehicle.getLight().setAmbientLightBrightnessColor(ambientArea, value, { _, v -> done(v) }, failed)
            // 아래는 원래 명령이 따로 있다(Control.command가 그쪽으로 보낸다)
            Control.AC, Control.FAN, Control.FAN_DIR, Control.RECIRC, Control.DRIVER_WINDOW -> execute(control.command(value))
        }
    }

    fun disconnect() {
        unregisters.forEach { runCatching(it) }
        unregisters.clear()
        if (initialized) runCatching { vehicle.release() }
        initialized = false
    }

    // ---- 구독 ----

    private fun subscribeDoors() = doors.forEach { (id, value) ->
        val area = DoorArea(value)
        val listener = object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) {
                pos?.let { emit(VehicleSignal.Door(id, it > 0)) }
            }

            override fun onFailed(exception: Exception) = fail("문 상태", exception)
        }
        guard("문") {
            val door = vehicle.getDoor()
            door.getCurrentDoorPosition(area, { pos -> pos?.let { emit(VehicleSignal.Door(id, it > 0)) } }, { fail("문 상태", it) })
            door.registerDoorPosition(area, listener)
            unregisters += { door.unregisterDoorPosition(area, listener) }
        }
    }

    private fun subscribeSeats() = seats.forEach { (id, value) ->
        val area = SeatArea(value)
        val listener = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupied: Boolean?) {
                occupied?.let { emit(VehicleSignal.SeatOccupancy(id, it)) }
            }

            override fun onFailed(exception: Exception) = fail("착석", exception)
        }
        guard("착석") {
            val seat = vehicle.getSeat()
            seat.registerSeatOccupancy(area, listener)
            unregisters += { seat.unregisterSeatOccupancy(area, listener) }
        }
    }

    private fun subscribeClimate() {
        guard("공조") {
            val hvac = vehicle.getHVAC()
            hvac.checkHvacRecirculationCapabilities(
                { _, areas ->
                    areas.firstOrNull()?.let { climateArea = it }
                    registerClimate()
                },
                { fail("공조 지원 확인", it); registerClimate() },
            )
            hvac.checkHvacFanSpeedCapabilities(
                { _, bounds ->
                    fanMax = bounds.boundsFor(climateArea)?.maxValue
                    publishCapabilities()
                },
                { fail("풍량 범위", it) },
            )
        }
    }

    private fun registerClimate() = guard("공조") {
        val hvac = vehicle.getHVAC()
        val area = climateArea
        val recirc = object : HvacRecirculationStateListener {
            override fun onHvacAutoRecirculationStateUpdated(state: Boolean?) {
                state?.let { emit(VehicleSignal.Recirculation(it)) }
            }

            override fun onFailed(exception: Exception) = fail("내기순환", exception)
        }
        val fan = object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(speed: Int?) {
                speed?.let { emit(VehicleSignal.FanSpeed(it)) }
            }

            override fun onFailed(exception: Exception) = fail("풍량", exception)
        }
        val temp = object : HvacTemperatureListener {
            override fun onHvacTemperatureUpdated(temperature: Float?) {
                temperature?.let { emit(VehicleSignal.CabinTemperature(it)) }
            }

            override fun onFailed(exception: Exception) = fail("실내온도", exception)
        }
        hvac.getHvacRecirculationState(area, { on -> on?.let { emit(VehicleSignal.Recirculation(it)) } }, { fail("내기순환", it) })
        hvac.getHvacTemperatureCurrent(area, { t -> t?.let { emit(VehicleSignal.CabinTemperature(it)) } }, { fail("실내온도", it) })
        hvac.registerHvacRecirculationState(area, recirc)
        hvac.registerHvacFanSpeed(area, fan)
        hvac.registerHvacTemperatureCurrent(area, temp)
        unregisters += {
            hvac.unregisterHvacRecirculationState(area, recirc)
            hvac.unregisterHvacFanSpeed(area, fan)
            hvac.unregisterHvacTemperatureCurrent(area, temp)
        }
        subscribeComfort()
    }

    // ---- R8 배우는 눈치: 운전자가 직접 바꾸는 실내 설정 ----

    /** 에어컨·오토·설정온도·열선·통풍·유리 열선·미러 열선·무드등. 차마다 지원이 달라 하나씩 따로 붙인다. */
    private fun subscribeComfort() {
        val hvac = vehicle.getHVAC()
        val area = climateArea
        guard("에어컨 상태") {
            val l = object : HvacAcStateListener {
                override fun onHvacAcStateUpdated(state: Boolean?) { state?.let { comfort(Control.AC, it) } }
                override fun onFailed(exception: Exception) = fail("에어컨 상태", exception)
            }
            hvac.getHvacAcState(area, { s -> s?.let { comfort(Control.AC, it) } }, { fail("에어컨 상태", it) })
            hvac.registerHvacAcState(area, l)
            unregisters += { hvac.unregisterHvacAcState(area, l) }
        }
        guard("오토 공조") {
            val l = object : HvacAutoStateListener {
                override fun onHvacAutoStateUpdated(state: Boolean?) { state?.let { comfort(Control.AUTO, it) } }
                override fun onFailed(exception: Exception) = fail("오토 공조", exception)
            }
            hvac.getHvacAutoState(area, { s -> s?.let { comfort(Control.AUTO, it) } }, { fail("오토 공조", it) })
            hvac.registerHvacAutoState(area, l)
            unregisters += { hvac.unregisterHvacAutoState(area, l) }
        }
        guard("설정온도") {
            val l = object : HvacTemperatureListener {
                override fun onHvacTemperatureUpdated(temperature: Float?) {
                    temperature?.let { emit(VehicleSignal.Comfort(Control.TEMP, (it * 10).roundToInt())) }
                }
                override fun onFailed(exception: Exception) = fail("설정온도", exception)
            }
            hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, { t -> t?.let { emit(VehicleSignal.Comfort(Control.TEMP, (it * 10).roundToInt())) } }, { fail("설정온도", it) })
            hvac.registerHvacTemperature(area, l)
            unregisters += { hvac.unregisterHvacTemperature(area, l) }
        }
        mapOf(Control.FRONT_DEFROST to windshield, Control.REAR_DEFROST to rearWindow).forEach { (control, window) ->
            guard(control.label) {
                val l = object : HvacDefrosterStateListener {
                    override fun onHvacDefrosterStateUpdated(state: Boolean?) { state?.let { comfort(control, it) } }
                    override fun onFailed(exception: Exception) = fail(control.label, exception)
                }
                hvac.getHvacDefrosterState(window, { s -> s?.let { comfort(control, it) } }, { fail(control.label, it) })
                hvac.registerHvacDefrosterState(window, l)
                unregisters += { hvac.unregisterHvacDefrosterState(window, l) }
            }
        }
        guard("운전석 열선") {
            val seat = vehicle.getSeat()
            val l = object : HvacSeatTemperatureListener {
                override fun onHvacSeatTemperatureUpdated(level: Int?) { level?.let { emit(VehicleSignal.Comfort(Control.SEAT_HEAT, it)) } }
                override fun onFailed(exception: Exception) = fail("운전석 열선", exception)
            }
            seat.getHvacSeatTemperature(driverSeat, { v -> v?.let { emit(VehicleSignal.Comfort(Control.SEAT_HEAT, it)) } }, { fail("운전석 열선", it) })
            seat.registerHvacSeatTemperature(driverSeat, l)
            unregisters += { seat.unregisterHvacSeatTemperature(driverSeat, l) }
        }
        guard("운전석 통풍") {
            val seat = vehicle.getSeat()
            val l = object : HvacSeatVentilationListener {
                override fun onHvacSeatVentilationUpdated(level: Int?) { level?.let { emit(VehicleSignal.Comfort(Control.SEAT_VENT, it)) } }
                override fun onFailed(exception: Exception) = fail("운전석 통풍", exception)
            }
            seat.getHvacSeatVentilationState(driverSeat, { v -> v?.let { emit(VehicleSignal.Comfort(Control.SEAT_VENT, it)) } }, { fail("운전석 통풍", it) })
            seat.registerHvacSeatVentilation(driverSeat, l)
            unregisters += { seat.unregisterHvacSeatVentilation(driverSeat, l) }
        }
        guard("핸들 열선") {
            val wheel = vehicle.getSteeringWheel()
            val l = object : HvacSteeringWheelHeatStateListener {
                override fun onHvacSteeringWheelHeatStateUpdated(level: Int?) { level?.let { emit(VehicleSignal.Comfort(Control.WHEEL_HEAT, it)) } }
                override fun onFailed(exception: Exception) = fail("핸들 열선", exception)
            }
            wheel.getHvacSteeringWheelHeatState({ v -> v?.let { emit(VehicleSignal.Comfort(Control.WHEEL_HEAT, it)) } }, { fail("핸들 열선", it) })
            wheel.registerHvacSteeringWheelHeatState(l)
            unregisters += { wheel.unregisterHvacSteeringWheelHeatState(l) }
        }
        guard("사이드미러 열선") {
            vehicle.getSideMirror().checkHvacSideMirrorHeatCapabilities(
                { bounds ->
                    bounds.keys.firstOrNull()?.let { mirrorArea = it }
                    registerMirrorHeat()
                },
                { fail("사이드미러 열선 지원 확인", it) },
            )
        }
        guard("무드등") {
            vehicle.getLight().checkAmbientLightBrightnessColorCapabilities(
                { _, areas ->
                    areas.firstOrNull()?.let { ambientArea = it }
                    registerAmbient()
                },
                { fail("무드등 지원 확인", it); registerAmbient() },
            )
        }
    }

    private fun registerMirrorHeat() = guard("사이드미러 열선") {
        val mirror = vehicle.getSideMirror()
        val area = mirrorArea
        val l = object : HvacSideMirrorHeatStateListener {
            override fun onHvacSideMirrorHeatStateUpdated(level: Int?) { level?.let { emit(VehicleSignal.Comfort(Control.MIRROR_HEAT, it)) } }
            override fun onFailed(exception: Exception) = fail("사이드미러 열선", exception)
        }
        mirror.getHvacSideMirrorHeatState(area, { v -> v?.let { emit(VehicleSignal.Comfort(Control.MIRROR_HEAT, it)) } }, { fail("사이드미러 열선", it) })
        mirror.registerHvacSideMirrorHeatState(area, l)
        unregisters += { mirror.unregisterHvacSideMirrorHeatState(area, l) }
    }

    private fun registerAmbient() = guard("무드등") {
        val light = vehicle.getLight()
        val area = ambientArea
        val state = object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) { state?.let { comfort(Control.AMBIENT, it == LightState.ON) } }
            override fun onFailed(exception: Exception) = fail("무드등", exception)
        }
        val color = object : AmbientLightBrightnessColorListener {
            override fun onBrightnessColorUpdated(value: Int?) { value?.let { emit(VehicleSignal.Comfort(Control.AMBIENT_COLOR, it)) } }
            override fun onFailed(exception: Exception) = fail("무드등 색", exception)
        }
        light.getAmbientLightState(area, { s -> s?.let { comfort(Control.AMBIENT, it == LightState.ON) } }, { fail("무드등", it) })
        light.getAmbientLightBrightnessColor(area, { v -> v?.let { emit(VehicleSignal.Comfort(Control.AMBIENT_COLOR, it)) } }, { fail("무드등 색", it) })
        light.registerAmbientLightStateListener(area, state)
        light.registerAmbientLightBrightnessColorListener(area, color)
        unregisters += {
            light.unregisterAmbientLightStateListener(area, state)
            light.unregisterAmbientLightBrightnessColorListener(area, color)
        }
    }

    private fun comfort(control: Control, on: Boolean) = emit(VehicleSignal.Comfort(control, if (on) 1 else 0))

    private fun subscribeWindow() = guard("창문") {
        val window = vehicle.getWindow()
        window.checkWindowPositionCapabilities(
            { _, bounds ->
                val b = bounds.boundsFor(driverWindow)
                // '살짝' = 닫힘 바로 다음 단계. 에뮬레이터는 0..10 단계(1단계 ≈ 10%)
                windowCrackStep = b?.let { (it.minValue + 1).coerceAtMost(it.maxValue) }
                windowMax = b?.maxValue
                // 연기가 빠질 창틈 ≈ 2cm = 범위의 약 4%(최소 1단계). 에뮬레이터 0..10 → 1, 0..100 → 4
                ventWindowStep = b?.let { (it.minValue + maxOf(1, (it.maxValue - it.minValue) / 25)).coerceAtMost(it.maxValue) }
                publishCapabilities()
            },
            { fail("창문 범위", it) },
        )
        val listener = object : WindowPositionListener {
            override fun onWindowPositionUpdated(position: Int?) {
                position?.let { emit(VehicleSignal.DriverWindow(it)) }
            }

            override fun onFailed(exception: Exception) = fail("창문", exception)
        }
        window.getCurrentWindowPosition(driverWindow, { pos -> pos?.let { emit(VehicleSignal.DriverWindow(it)) } }, { fail("창문", it) })
        window.registerWindowPosition(driverWindow, listener)
        unregisters += { window.unregisterWindowPosition(driverWindow, listener) }
    }

    private fun subscribeOtherWindows() = otherWindows.forEach { (id, area) ->
        val listener = object : WindowPositionListener {
            override fun onWindowPositionUpdated(position: Int?) {
                position?.let { emit(VehicleSignal.OtherWindow(id, it)) }
            }

            override fun onFailed(exception: Exception) = fail("${id.label} 창", exception)
        }
        guard("창문") {
            val window = vehicle.getWindow()
            window.getCurrentWindowPosition(area, { pos -> pos?.let { emit(VehicleSignal.OtherWindow(id, it)) } }, { fail("${id.label} 창", it) })
            window.registerWindowPosition(area, listener)
            unregisters += { window.unregisterWindowPosition(area, listener) }
        }
    }

    /** 바람 방향은 변경 알림(리스너)이 없어 시작할 때와 환기 직전에 한 번씩 읽는다. */
    private fun subscribeFanDirection() = guard("바람 방향") {
        vehicle.getHVAC().checkHvacFanDirectionCapabilities(
            { _, areas ->
                val supported = (areas.entries.firstOrNull { it.key.sameAs(climateArea) }?.value ?: areas.values.firstOrNull())
                    .orEmpty().mapNotNull { it.toCore()?.label }
                if (supported.isNotEmpty()) onStatus("바람 방향 지원: ${supported.joinToString(", ")}")
            },
            { fail("바람 방향 지원 확인", it) },
        )
        readFanDirection()
    }

    fun readFanDirection() {
        if (!initialized) return
        guard("바람 방향") {
            vehicle.getHVAC().getHvacFanDirection(
                climateArea,
                { dir -> dir?.toCore()?.let { emit(VehicleSignal.FanDirectionChanged(it)) } },
                { fail("바람 방향", it) },
            )
        }
    }

    private fun subscribeWiper() = guard("와이퍼") {
        val wiper = vehicle.getWiper()
        val listener = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) {
                state?.let { emit(VehicleSignal.Wiper(it == WiperState.ON)) }
            }

            override fun onFailed(exception: Exception) = fail("와이퍼", exception)
        }
        wiper.registerWiperState(windshield, listener)
        unregisters += { wiper.unregisterWiperState(windshield, listener) }
        readWiperSwitch()
    }

    /**
     * 빗발 세기. 우적센서 값은 SDK·표준 속성에 없고, 와이퍼 스위치 위치만 읽힌다(바뀔 때 알림도 없음).
     * 연속 고속(2단 이상) = 심한 폭우. AUTO(우적센서가 속도를 정함)면 세기를 알 수 없어 보통 비로 본다.
     */
    fun readWiperSwitch() {
        if (!initialized) return
        guard("와이퍼 스위치") {
            vehicle.getWiper().getCurrentWiperSwitch(
                windshield,
                { sw -> sw?.let { emit(VehicleSignal.HeavyRain(it in HEAVY_RAIN)) } },
                { fail("와이퍼 스위치", it) },
            )
        }
    }

    // TODO: Pleos가 권장하는 AAOS CarPropertyManager GEAR_SELECTION으로 이전
    @Suppress("DEPRECATION")
    private fun subscribeGear() = guard("기어") {
        val gear = vehicle.getGear()
        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                gear?.let { emit(VehicleSignal.Gear(parked = it == VehicleGear.GEAR_PARK)) }
            }

            override fun onFailed(exception: Exception) = fail("기어", exception)
        }
        gear.getCurrentGearSelectionState({ g -> g?.let { emit(VehicleSignal.Gear(parked = it == VehicleGear.GEAR_PARK)) } }, { fail("기어", it) })
        gear.registerGearSelection(listener)
        unregisters += { gear.unregisterGearSelection(listener) }
    }

    // TODO: Pleos가 권장하는 AAOS CarPropertyManager RANGE_REMAINING으로 이전
    @Suppress("DEPRECATION")
    private fun subscribeRange() = guard("주행가능거리") {
        val odometer = vehicle.getOdometer()
        val listener = object : RangeRemainingListener {
            override fun onRangeRemainingChanged(range: Float?) {
                range?.let { emit(VehicleSignal.RangeRemaining(toKm(it))) }
            }

            override fun onFailed(exception: Exception) = fail("주행가능거리", exception)
        }
        odometer.getRangeRemainingDistance({ r -> r?.let { emit(VehicleSignal.RangeRemaining(toKm(it))) } }, { fail("주행가능거리", it) })
        odometer.registerRangeRemainingListener(listener)
        unregisters += { odometer.unregisterRangeRemainingListener(listener) }
    }

    private fun subscribeTires() = wheels.forEach { wheel ->
        val area = WheelArea(wheel)
        val listener = object : TirePressureListener {
            override fun onTirePressureUpdated(pressure: Float?) {
                pressure?.let {
                    tirePressure[wheel] = it
                    emitTireState()
                }
            }

            override fun onFailed(exception: Exception) = fail("타이어", exception)
        }
        guard("타이어") {
            val tire = vehicle.getTire()
            tire.getLowTirePressure(area, { th -> th?.let { tireLowThreshold[wheel] = it; emitTireState() } }, { fail("타이어 기준", it) })
            tire.registerTirePressure(area, listener)
            unregisters += { tire.unregisterTirePressure(area, listener) }
        }
    }

    // ---- 도우미 ----

    private fun emitTireState() {
        val low = wheels.any { w ->
            val p = tirePressure[w]
            val th = tireLowThreshold[w]
            p != null && th != null && p < th
        }
        emit(VehicleSignal.TireLow(low))
    }

    private fun publishCapabilities() = main.post {
        onCapabilities(
            Capabilities(
                windowCrackStep = windowCrackStep,
                ventWindowStep = ventWindowStep,
                windowMax = windowMax,
                fanMax = fanMax,
            ),
        )
    }

    private fun FanDirectionType.toCore(): FanDirection? = FanDirection.entries.firstOrNull { it.name == name }

    private companion object {
        val HEAVY_RAIN = setOf(
            WiperSwitch.CONTINUOUS_LEVEL_2, WiperSwitch.CONTINUOUS_LEVEL_3,
            WiperSwitch.CONTINUOUS_LEVEL_4, WiperSwitch.CONTINUOUS_LEVEL_5,
        )
    }

    /** VHAL 표준은 미터 단위. km로 오는 경우도 있어 크기로 판별한다. */
    private fun toKm(value: Float): Float = if (value > 2_000f) value / 1_000f else value

    private fun emit(signal: VehicleSignal) {
        main.post { onSignal(signal) }
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            report("$what: ${t.javaClass.simpleName} ${t.message.orEmpty()}")
        }
    }

    private fun fail(what: String, e: Exception) = report("$what 실패: ${e.message ?: e.javaClass.simpleName}")

    private fun report(message: String) {
        // 같은 오류는 한 번만 표시
        if (reported.add(message)) main.post { onStatus(message) }
    }

    private fun <A : Area, T> Map<A, AreaBounds<T>>.boundsFor(area: Area): AreaBounds<T>? =
        entries.firstOrNull { it.key.sameAs(area) }?.value ?: values.firstOrNull()

    private fun Area.sameAs(other: Area): Boolean {
        val a = this
        return when {
            a is WindowArea && other is WindowArea -> a.values.contentEquals(other.values)
            a is SeatArea && other is SeatArea -> a.values.contentEquals(other.values)
            a is DoorArea && other is DoorArea -> a.values.contentEquals(other.values)
            else -> a == other
        }
    }
}
