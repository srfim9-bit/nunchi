package app.nunchi.core

/** 차 문. Pleos DoorValue ROW_1_LEFT … ROW_2_RIGHT 에 대응. */
enum class DoorId(val label: String) {
    FRONT_LEFT("운전석"),
    FRONT_RIGHT("동승석"),
    REAR_LEFT("뒷좌석 왼쪽"),
    REAR_RIGHT("뒷좌석 오른쪽"),
}

/** 좌석. Pleos SeatValue ROW_1_LEFT … ROW_2_RIGHT 에 대응. */
enum class SeatId(val label: String) {
    DRIVER("운전석"),
    PASSENGER("동승석"),
    REAR_LEFT("뒷좌석 왼쪽"),
    REAR_RIGHT("뒷좌석 오른쪽"),
}

/** 운전석을 뺀 나머지 창. Pleos WindowValue ROW_1_RIGHT … ROW_2_RIGHT 에 대응. */
enum class WindowId(val label: String) {
    PASSENGER("동승석"),
    REAR_LEFT("뒷좌석 왼쪽"),
    REAR_RIGHT("뒷좌석 오른쪽"),
}

/** 창 네 개를 한 줄로(운전석 + 나머지). 흐름 환기의 '출구'를 고를 때 쓴다 — 담배 피우는 사람 쪽 창이 출구. */
enum class Pane(val label: String) {
    DRIVER("운전석"),
    PASSENGER("동승석"),
    REAR_LEFT("뒷좌석 왼쪽"),
    REAR_RIGHT("뒷좌석 오른쪽");

    /** 운전석이면 null(운전석 창은 따로 다룬다). */
    val window: WindowId?
        get() = when (this) {
            DRIVER -> null
            PASSENGER -> WindowId.PASSENGER
            REAR_LEFT -> WindowId.REAR_LEFT
            REAR_RIGHT -> WindowId.REAR_RIGHT
        }

    fun set(position: Int): CareCommand =
        window?.let { CareCommand.SetOtherWindow(it, position) } ?: CareCommand.SetDriverWindow(position)

    companion object {
        fun of(seat: SeatId): Pane = when (seat) {
            SeatId.DRIVER -> DRIVER
            SeatId.PASSENGER -> PASSENGER
            SeatId.REAR_LEFT -> REAR_LEFT
            SeatId.REAR_RIGHT -> REAR_RIGHT
        }

        fun of(window: WindowId): Pane = when (window) {
            WindowId.PASSENGER -> PASSENGER
            WindowId.REAR_LEFT -> REAR_LEFT
            WindowId.REAR_RIGHT -> REAR_RIGHT
        }

        /** 창 신호면 (어느 창, 위치), 아니면 null. */
        fun moved(signal: VehicleSignal): Pair<Pane, Int>? = when (signal) {
            is VehicleSignal.DriverWindow -> DRIVER to signal.position
            is VehicleSignal.OtherWindow -> of(signal.window) to signal.position
            else -> null
        }
    }
}

/** 바람 방향. Pleos FanDirectionType 과 1:1 (UNKNOWN 은 null 로 둔다). [defrost] = 앞유리로도 바람이 가는지. */
enum class FanDirection(val label: String, val defrost: Boolean = false) {
    FACE("얼굴"),
    FLOOR("발밑"),
    FACE_AND_FLOOR("얼굴+발밑"),
    DEFROST("앞유리", defrost = true),
    FACE_AND_DEFROST("얼굴+앞유리", defrost = true),
    DEFROST_AND_FLOOR("발밑+앞유리", defrost = true),
    FACE_AND_FLOOR_AND_DEFROST("전체", defrost = true),
}

/**
 * 사람이 손으로 바꾸는 실내 설정 하나 — '배우는 눈치'(R8)가 조작 묶음으로 기억하는 단위.
 * 값은 정수로 다룬다: 켜짐/꺼짐 = 1/0, 설정온도 = 0.1℃ 단위(26.5℃ → 265), 바람 방향 = [FanDirection] 순서,
 * 시트 열선 = 단계(음수면 냉방), 무드등 색 = ARGB.
 */
enum class Control(val label: String) {
    AC("에어컨"),
    AUTO("오토 공조"),
    TEMP("설정온도"),
    FAN("바람 세기"),
    FAN_DIR("바람 방향"),
    RECIRC("내기순환"),
    SEAT_HEAT("운전석 열선"),
    SEAT_VENT("운전석 통풍"),
    WHEEL_HEAT("핸들 열선"),
    FRONT_DEFROST("앞유리 열선"),
    REAR_DEFROST("뒷유리 열선"),
    MIRROR_HEAT("사이드미러 열선"),
    AMBIENT("무드등"),
    AMBIENT_COLOR("무드등 색"),
    DRIVER_WINDOW("운전석 창");

    /** 사람이 읽는 말: "설정온도 27℃", "핸들 열선 켜기", "바람 발밑으로" … */
    fun describe(value: Int): String = when (this) {
        AC -> onOff("에어컨", value)
        AUTO -> onOff("오토 공조", value)
        TEMP -> "설정온도 ${formatTenths(value)}℃"
        FAN -> if (value > 0) "바람 ${value}단" else "바람 끄기"
        FAN_DIR -> "바람 ${withRo(FanDirection.entries.getOrNull(value)?.label ?: "?")}"
        RECIRC -> if (value > 0) "내기순환" else "외기"
        SEAT_HEAT -> when {
            value > 0 -> "운전석 열선 ${value}단"
            value < 0 -> "운전석 냉방 ${-value}단"
            else -> "운전석 열선 끄기"
        }
        SEAT_VENT -> if (value > 0) "운전석 통풍 ${value}단" else "운전석 통풍 끄기"
        WHEEL_HEAT -> when {
            value == 1 -> "핸들 열선 켜기"
            value > 1 -> "핸들 열선 ${value}단"
            else -> "핸들 열선 끄기"
        }
        FRONT_DEFROST -> onOff("앞유리 열선", value)
        REAR_DEFROST -> onOff("뒷유리 열선", value)
        MIRROR_HEAT -> onOff("사이드미러 열선", value)
        AMBIENT -> onOff("무드등", value)
        AMBIENT_COLOR -> AmbientColor.of(value)?.let { "무드등 ${it.label}" } ?: "무드등 색 바꾸기"
        DRIVER_WINDOW -> when {
            value <= 0 -> "운전석 창 닫기"
            value <= 2 -> "운전석 창 살짝(1cm)"
            else -> "운전석 창 ${value}단계 열기"
        }
    }

    /** 이 설정을 이 값으로 맞추는 명령. 기존 루틴이 쓰던 명령이 있으면 그것을 쓴다. */
    fun command(value: Int): CareCommand = when (this) {
        AC -> CareCommand.SetAc(value > 0)
        FAN -> CareCommand.SetFanSpeed(value)
        FAN_DIR -> CareCommand.SetFanDirection(FanDirection.entries[value.coerceIn(0, FanDirection.entries.size - 1)])
        RECIRC -> CareCommand.SetRecirculation(value > 0)
        DRIVER_WINDOW -> CareCommand.SetDriverWindow(value)
        else -> CareCommand.SetComfort(this, value)
    }

    /** 이 설정이 바뀌었다는 신호. 기존 루틴이 듣는 신호가 있으면 그것으로(시뮬레이터·어댑터용). */
    fun signal(value: Int): VehicleSignal = when (this) {
        FAN -> VehicleSignal.FanSpeed(value)
        FAN_DIR -> VehicleSignal.FanDirectionChanged(FanDirection.entries[value.coerceIn(0, FanDirection.entries.size - 1)])
        RECIRC -> VehicleSignal.Recirculation(value > 0)
        DRIVER_WINDOW -> VehicleSignal.DriverWindow(value)
        else -> VehicleSignal.Comfort(this, value)
    }

    companion object {
        private fun onOff(name: String, value: Int) = if (value > 0) "$name 켜기" else "$name 끄기"

        fun formatTenths(tenths: Int): String =
            if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"

        /** 받침에 따라 '로/으로'. */
        fun withRo(word: String): String {
            val last = word.lastOrNull() ?: return word
            if (last !in '가'..'힣') return "${word}로"
            val jong = (last - '가') % 28
            return if (jong == 0 || jong == 8) "${word}로" else "${word}으로"
        }
    }
}

/** 무드등 색 이름(ARGB). 차가 다른 색 값을 쓰면 '색 바꾸기'로만 읽는다. */
enum class AmbientColor(val label: String, val argb: Int) {
    ORANGE("주황", 0xFFFF8A3D.toInt()),
    BLUE("파랑", 0xFF3D7BFF.toInt()),
    GREEN("초록", 0xFF3DBF7F.toInt()),
    PURPLE("보라", 0xFF9B5CFF.toInt()),
    WHITE("흰색", 0xFFFFFFFF.toInt());

    companion object {
        fun of(argb: Int): AmbientColor? = entries.firstOrNull { it.argb == argb }
    }
}

/**
 * 차가 알려 주는 것.
 * 플랫폼 어댑터(Pleos Vehicle SDK, 데모 시뮬레이터)가 자기 이벤트를 이 형태로 바꿔 넣는다.
 */
sealed interface VehicleSignal {
    data class Door(val door: DoorId, val open: Boolean) : VehicleSignal
    data class SeatOccupancy(val seat: SeatId, val occupied: Boolean) : VehicleSignal
    data class Recirculation(val on: Boolean) : VehicleSignal
    data class FanSpeed(val level: Int) : VehicleSignal
    data class CabinTemperature(val celsius: Float) : VehicleSignal
    data class Wiper(val on: Boolean) : VehicleSignal
    /** 와이퍼 스위치가 연속 고속(2단 이상) = 심한 폭우. */
    data class HeavyRain(val heavy: Boolean) : VehicleSignal
    data class Gear(val parked: Boolean) : VehicleSignal
    data class DriverWindow(val position: Int) : VehicleSignal
    data class OtherWindow(val window: WindowId, val position: Int) : VehicleSignal
    data class FanDirectionChanged(val direction: FanDirection) : VehicleSignal
    data class RangeRemaining(val km: Float) : VehicleSignal
    data class RouteRemaining(val km: Float?) : VehicleSignal
    data class TireLow(val low: Boolean) : VehicleSignal

    /** 실내 미세먼지(PM2.5, ㎍/㎥) — 공기질 센서를 단 차. 지금 Pleos SDK엔 읽는 API가 없어 데모에선 가상 센서로 넣는다. */
    data class CabinPm25(val ugm3: Int) : VehicleSignal

    /** 흡연 손동작(실내 카메라, 센서 계층 L2). 지금 SDK엔 카메라 API가 없어 데모에선 가상 센서로 넣는다. */
    data class SmokingGesture(val seat: SeatId) : VehicleSignal

    /** 그 밖의 실내 설정(에어컨·설정온도·열선·통풍·무드등 …)이 바뀜. 값의 뜻은 [Control] 참고. */
    data class Comfort(val control: Control, val value: Int) : VehicleSignal

    /** 바깥 온도(AAOS 표준 ENV_OUTSIDE_TEMPERATURE). */
    data class OutsideTemperature(val celsius: Float) : VehicleSignal

    /** 이 신호가 사람이 만질 수 있는 설정이면 (설정, 값). */
    fun control(): Pair<Control, Int>? = when (this) {
        is Recirculation -> Control.RECIRC to (if (on) 1 else 0)
        is FanSpeed -> Control.FAN to level
        is FanDirectionChanged -> Control.FAN_DIR to direction.ordinal
        is DriverWindow -> Control.DRIVER_WINDOW to position
        is Comfort -> control to value
        else -> null
    }
}

/** 눈치가 차에 부탁하는 것. 주행 제어(조향·제동·가속)는 의도적으로 없다. */
sealed interface CareCommand {
    data class SetDriverWindow(val position: Int) : CareCommand
    data class SetRecirculation(val on: Boolean) : CareCommand
    data class SetFanSpeed(val level: Int) : CareCommand
    data class SetFanDirection(val direction: FanDirection) : CareCommand
    data class SetOtherWindow(val window: WindowId, val position: Int) : CareCommand
    data class SetAc(val on: Boolean) : CareCommand
    /** 그 밖의 실내 설정(설정온도·열선·통풍·무드등 …). 값의 뜻은 [Control] 참고. */
    data class SetComfort(val control: Control, val value: Int) : CareCommand
    data class Say(val text: String) : CareCommand
    /** 말없이 화면에만 한 줄(소리 내지 않을 일). */
    data class Notice(val text: String) : CareCommand

    /** 이 명령이 바꾸는 설정이면 (설정, 값) — '배우는 눈치'가 NUNCHI 자신의 손을 사람 손으로 착각하지 않게. */
    fun control(): Pair<Control, Int>? = when (this) {
        is SetAc -> Control.AC to (if (on) 1 else 0)
        is SetFanSpeed -> Control.FAN to level
        is SetFanDirection -> Control.FAN_DIR to direction.ordinal
        is SetRecirculation -> Control.RECIRC to (if (on) 1 else 0)
        is SetDriverWindow -> Control.DRIVER_WINDOW to position
        is SetComfort -> control to value
        else -> null
    }
}

/** 눈치가 이해하고 있는 실내 상황. */
data class CabinState(
    val openDoors: Set<DoorId> = emptySet(),
    val occupiedSeats: Set<SeatId> = setOf(SeatId.DRIVER),
    val recirculating: Boolean = false,
    val fanSpeed: Int? = null,
    val fanDirection: FanDirection? = null,
    val acOn: Boolean? = null,
    val cabinTempC: Float? = null,
    val wiperOn: Boolean = false,
    val heavyRain: Boolean = false,
    val parked: Boolean = true,
    val driverWindow: Int? = 0,
    val otherWindows: Map<WindowId, Int> = emptyMap(),
    val rangeKm: Float? = null,
    val routeKm: Float? = null,
    val tireLow: Boolean = false,
    val pm25: Int? = null,
    /** 그 밖의 실내 설정(설정온도·열선·통풍·무드등 …). */
    val comfort: Map<Control, Int> = emptyMap(),
    val outsideTempC: Float? = null,
) {
    val allDoorsClosed: Boolean get() = openDoors.isEmpty()

    /** 설정 하나의 지금 값(모르면 null). */
    fun read(control: Control): Int? = when (control) {
        Control.AC -> acOn?.let { if (it) 1 else 0 }
        Control.FAN -> fanSpeed
        Control.FAN_DIR -> fanDirection?.ordinal
        Control.RECIRC -> if (recirculating) 1 else 0
        Control.DRIVER_WINDOW -> driverWindow
        else -> comfort[control]
    }

    fun windowAt(pane: Pane): Int? = when (val w = pane.window) {
        null -> driverWindow
        else -> otherWindows[w]
    }

    /** 운전자는 항상 있다고 본다(착석 센서가 없거나 꺼져 있어도 최소 1명). */
    val occupants: Int get() = occupiedSeats.size.coerceAtLeast(1)

    fun apply(signal: VehicleSignal): CabinState = when (signal) {
        is VehicleSignal.Door ->
            copy(openDoors = if (signal.open) openDoors + signal.door else openDoors - signal.door)
        is VehicleSignal.SeatOccupancy ->
            copy(occupiedSeats = if (signal.occupied) occupiedSeats + signal.seat else occupiedSeats - signal.seat)
        is VehicleSignal.Recirculation -> copy(recirculating = signal.on)
        is VehicleSignal.FanSpeed -> copy(fanSpeed = signal.level)
        is VehicleSignal.CabinTemperature -> copy(cabinTempC = signal.celsius)
        is VehicleSignal.Wiper -> copy(wiperOn = signal.on, heavyRain = heavyRain && signal.on)
        is VehicleSignal.HeavyRain -> copy(heavyRain = signal.heavy)
        is VehicleSignal.Gear -> copy(parked = signal.parked)
        is VehicleSignal.DriverWindow -> copy(driverWindow = signal.position)
        is VehicleSignal.OtherWindow -> copy(otherWindows = otherWindows + (signal.window to signal.position))
        is VehicleSignal.FanDirectionChanged -> copy(fanDirection = signal.direction)
        is VehicleSignal.RangeRemaining -> copy(rangeKm = signal.km)
        is VehicleSignal.RouteRemaining -> copy(routeKm = signal.km)
        is VehicleSignal.TireLow -> copy(tireLow = signal.low)
        is VehicleSignal.CabinPm25 -> copy(pm25 = signal.ugm3)
        is VehicleSignal.SmokingGesture -> this
        is VehicleSignal.OutsideTemperature -> copy(outsideTempC = signal.celsius)
        is VehicleSignal.Comfort -> when (signal.control) {
            Control.AC -> copy(acOn = signal.value > 0)
            Control.FAN -> copy(fanSpeed = signal.value)
            Control.FAN_DIR -> copy(fanDirection = FanDirection.entries.getOrNull(signal.value) ?: fanDirection)
            Control.RECIRC -> copy(recirculating = signal.value > 0)
            Control.DRIVER_WINDOW -> copy(driverWindow = signal.value)
            else -> copy(comfort = comfort + (signal.control to signal.value))
        }
    }

    /**
     * 명령을 보낸 직후의 낙관적 상태. 실제 차가 값을 돌려주면 [apply]가 덮어쓴다.
     * (에뮬레이터는 SET 이후 값이 바로 반영되지 않을 수 있어서 필요)
     */
    fun assume(command: CareCommand): CabinState = when (command) {
        is CareCommand.SetDriverWindow -> copy(driverWindow = command.position)
        is CareCommand.SetRecirculation -> copy(recirculating = command.on)
        is CareCommand.SetFanSpeed -> copy(fanSpeed = command.level)
        is CareCommand.SetFanDirection -> copy(fanDirection = command.direction)
        is CareCommand.SetOtherWindow -> copy(otherWindows = otherWindows + (command.window to command.position))
        is CareCommand.SetAc -> copy(acOn = command.on)
        is CareCommand.SetComfort -> copy(comfort = comfort + (command.control to command.value))
        is CareCommand.Say, is CareCommand.Notice -> this
    }
}
