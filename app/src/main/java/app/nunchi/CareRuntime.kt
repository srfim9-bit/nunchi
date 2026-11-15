package app.nunchi

import android.content.Context
import android.os.SystemClock
import app.nunchi.core.AmbientColor
import app.nunchi.core.CabinState
import app.nunchi.core.CareAction
import app.nunchi.core.CareCommand
import app.nunchi.core.CareEngine
import app.nunchi.core.CareEvent
import app.nunchi.core.Control
import app.nunchi.core.DailyReport
import app.nunchi.core.DoorId
import app.nunchi.core.FanDirection
import app.nunchi.core.Pane
import app.nunchi.core.Reply
import app.nunchi.core.LearnerView
import app.nunchi.core.RangeLevel
import app.nunchi.core.Routine
import app.nunchi.core.SeatId
import app.nunchi.core.VehicleSignal
import app.nunchi.core.WindowId
import app.nunchi.vehicle.AaosCarProperties
import app.nunchi.vehicle.PleosVehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class Connection(val label: String) {
    CONNECTING("차량 연결 중"),
    VEHICLE("Pleos 차량 연결됨"),
    DEMO("데모 모드"),
}

/** 대답을 기다리는 제안(화면용). */
data class ProposalUi(
    val routine: Routine,
    val action: CareAction,
    val ask: String,
    val reason: String,
    val voice: Boolean,
    /** "다음부턴 알아서"를 고를 수 있는가(습관 제안·약한 신호는 아님). */
    val canTrust: Boolean,
    /** 무엇을 바꿀지(화면에만). */
    val detail: String? = null,
)

data class UiState(
    val connection: Connection = Connection.CONNECTING,
    /** 실제 차량에서 받은 신호 종류(시뮬레이터 입력은 제외). */
    val receivedSignals: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val showDiagnostics: Boolean = false,
    /** 주행 중 UX 제한(운전 중 화면 단순화 필요). */
    val drivingRestricted: Boolean = false,
    val demoSpeed: Int = 1,
    /** 빨리 감기 중이지만 NUNCHI가 여쭙는 동안이라 실제 속도로 흐르는 중(대답할 시간은 그대로 1분). */
    val speedHeld: Boolean = false,
    val cabin: CabinState = CabinState(),
    val co2Ppm: Int = 600,
    val co2History: List<Float> = emptyList(),
    val r0Status: String = "",
    val r1Status: String = "",
    val freshAirCount: Int = 0,
    val rangeLevel: RangeLevel = RangeLevel.UNKNOWN,
    val rangeMarginPct: Int? = null,
    val lastSay: String? = null,
    val events: List<CareEvent> = emptyList(),
    val report: DailyReport = DailyReport.EMPTY,
    val showReport: Boolean = false,
    val frameless: Boolean = false,
    val voiceOn: Boolean = true,
    val voiceEngine: String = "준비 중",
    val windowCrackStep: Int = 1,
    /** R6 흐름 환기. */
    val venting: Boolean = false,
    val ventStatus: String = "",
    /** 차의 터널 차단(외부 공기 유입 방지) 중이라 흐름 환기가 잠시 멈춤. */
    val ventPaused: Boolean = false,
    /** 연기가 빠지는 창(그림의 '창틈' 이름표). */
    val ventOutlet: Pane = Pane.DRIVER,
    /** 조금 전에 바람길을 여쭸다가 사양·무응답이라 쉬는 중이면 남은 분(재촉하지 않기). 아니면 0. */
    val ventRestMin: Int = 0,
    /** 대답을 기다리는 제안(한 번에 하나). */
    val proposal: ProposalUi? = null,
    /** "다음부턴 알아서"라고 허락한 일. */
    val trusted: List<CareAction> = emptyList(),
    /** 연달아 사양해서 오늘은 먼저 권하지 않는 일. */
    val quiet: List<CareAction> = emptyList(),
    /** R8 배우는 눈치 — '제가 아는 당신' 카드. */
    val learner: LearnerView = LearnerView(),
    /** 시연용 시각(null = 실제 시각). */
    val hourOverride: Int? = null,
    /** 제안에 말로 대답하기(실험, Pleos STT). */
    val voiceAnswer: Boolean = false,
    val voiceAnswerStatus: String = "꺼짐",
)

/** 데모 가속 시계: 실제 1초가 speed초로 흐른다(엔진 타이머·CO2 적분 모두 이 시계를 쓴다). */
private class DemoClock {
    var speed = 1
        private set

    /**
     * NUNCHI가 여쭙는 동안은 빨리 감기를 멈추고 실제 속도로 흐른다.
     * 안 그러면 ×30에서 '1분 안에 답이 없으면 거둠'이 2초가 돼, 사람이 '네'를 누를 틈이 없다.
     */
    var hold = false
        private set
    private var lastReal = SystemClock.elapsedRealtime()
    private var sim = System.currentTimeMillis()

    fun now(): Long {
        val real = SystemClock.elapsedRealtime()
        sim += (real - lastReal) * (if (hold) 1 else speed)
        lastReal = real
        return sim
    }

    fun setSpeed(value: Int) {
        now()
        speed = value
    }

    fun setHold(value: Boolean) {
        if (hold == value) return
        now()
        hold = value
    }
}

/**
 * 눈치의 몸통: 엔진 + 차량 연결 + 음성. 화면과 분리돼 상시 서비스([CareService]) 안에서 돈다.
 * 운전 중 화면이 막혀도(UX 제한) 판단과 개입은 계속된다.
 */
class CareRuntime(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = DemoClock()
    private val engine = CareEngine(startMs = clock.now())
    private val history = ArrayDeque<Float>()
    private val received = linkedSetOf<String>()
    private val diagnostics = ArrayDeque<String>()
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var lastSay: String? = null
    private var lastSayAt = 0L
    private var tripStartMs: Long? = null
    private var started = false
    private var lastDirectionReadMs = 0L
    /** 오늘 날짜(시연 시계 기준) — 날이 바뀌면 '오늘은 먼저 권하지 않음'을 풀고 '오늘의 1cm'를 새로 센다. */
    private var dayIndex: Long? = null
    private var dayStartMs = Long.MIN_VALUE
    private val prefs = context.getSharedPreferences("nunchi", Context.MODE_PRIVATE)

    /** 음성을 만들고 읽는 동안엔 화면 갱신을 쉰다(저사양 PC·에뮬레이터에서 소리가 끊기지 않게). */
    private var speaking = false
    private var tickCount = 0L

    private val speaker = Speaker(
        context,
        onStatus = { addDiagnostic(it) },
        onSpeaking = { on ->
            speaking = on
            if (!on) {
                maybeListen() // 여쭌 말이 끝났으면 대답을 듣는다(음성 대답을 켰을 때만)
                refresh()
            }
        },
    )

    /** 제안에 말로 대답하기(실험). 기본은 꺼짐 — 켜면 여쭌 말이 끝난 뒤 한 번 '네/아니요'를 듣는다. */
    private val voiceAnswer = VoiceAnswer(
        context,
        onStatus = { addDiagnostic(it); refresh() },
        onReply = ::onVoiceReply,
    )
    private var voiceAnswerOn = prefs.getBoolean(KEY_VOICE_ANSWER, false)
    private var listenedFor: Any? = null

    private val gateway = PleosVehicleGateway(
        context = context,
        onSignal = ::fromVehicle,
        onStatus = ::addDiagnostic,
        onCapabilities = { caps ->
            engine.updateConfig { cfg ->
                cfg.copy(
                    windowCrackStep = caps.windowCrackStep ?: cfg.windowCrackStep,
                    windowMax = caps.windowMax ?: cfg.windowMax,
                    ventilationWindowStep = caps.ventWindowStep ?: cfg.ventilationWindowStep,
                    fanMax = caps.fanMax ?: cfg.fanMax,
                )
            }
            caps.windowCrackStep?.let { addDiagnostic("창문 최소 개방 단계 = $it") }
            caps.ventWindowStep?.let { addDiagnostic("환기 창틈 단계 = $it") }
            caps.fanMax?.let { addDiagnostic("바람 세기 최대 = ${it}단") }
            refresh()
        },
    )

    private val aaos = AaosCarProperties(
        context = context,
        onSignal = ::fromVehicle,
        onDiag = ::addDiagnostic,
        onDrivingRestricted = { restricted -> _ui.update { it.copy(drivingRestricted = restricted) } },
    )

    fun start() {
        if (started) return
        started = true
        loadTrust()
        loadHabits()
        if (voiceAnswerOn) voiceAnswer.prepare()
        scope.launch {
            val connected = gateway.connect()
            _ui.update { it.copy(connection = if (connected) Connection.VEHICLE else Connection.DEMO) }
            addDiagnostic(if (connected) "Pleos Vehicle Service 연결 ✓" else "Pleos Vehicle Service 없음 → 데모 모드")
            if (connected) {
                delay(800) // 서비스 바인딩 여유
                gateway.subscribe()
            }
            aaos.start()
            refresh()
        }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                onTick()
            }
        }
    }

    /** 권한 요청 결과를 받은 뒤 표준 속성 구독을 다시 시도한다. */
    fun onCarPermissionsResult(granted: Map<String, Boolean>) {
        granted.forEach { (permission, ok) -> addDiagnostic("${permission.substringAfterLast('.')} 권한 ${if (ok) "허용" else "거부"}") }
        aaos.stop()
        aaos.start()
        // 음성 권한을 이제 받았다면 Pleos 음성 엔진을 다시 시도
        val ttsGranted = granted[TTS_PERMISSION] == true
        if (ttsGranted && speaker.engine in setOf(Speaker.Engine.ANDROID, Speaker.Engine.MUTE)) speaker.retryPleos()
    }

    fun addDiagnostic(message: String) {
        if (diagnostics.any { it.endsWith(message) }) return
        diagnostics.addFirst("${LocalTime.now().format(DIAG_TIME)}  $message")
        while (diagnostics.size > 60) diagnostics.removeLast()
        _ui.update { it.copy(diagnostics = diagnostics.toList()) }
    }

    // ---- 엔진 연결 ----

    private fun fromVehicle(signal: VehicleSignal) {
        if (received.add(signalLabel(signal))) refresh()
        process(signal)
        // 와이퍼가 켜지거나 꺼지면 스위치 위치(빗발 세기)도 읽는다
        if (signal is VehicleSignal.Wiper) gateway.readWiperSwitch()
        // 바람 방향은 변경 알림이 없다 → 운전자가 공조를 만질 때 한 번씩 읽어 '원래 방향'을 최신으로
        if ((signal is VehicleSignal.FanSpeed || signal is VehicleSignal.Recirculation) && !engine.ventilation.isActive) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDirectionReadMs > DIRECTION_READ_GAP_MS) {
                lastDirectionReadMs = now
                gateway.readFanDirection()
            }
        }
    }

    private fun process(signal: VehicleSignal) {
        val wasParked = engine.state.parked
        execute(engine.onSignal(signal, clock.now()))
        syncHold()
        if (signal is VehicleSignal.Gear) onGearChanged(wasParked, signal.parked)
        refresh()
    }

    private fun onTick() {
        // 한마디는 한 번만: 20초 뒤 화면에서 조용히 내린다
        if (lastSay != null && SystemClock.elapsedRealtime() - lastSayAt > SAY_VISIBLE_MS) lastSay = null
        val now = clock.now()
        rollDay(now)
        execute(engine.tick(now))
        syncHold()
        // 음성 대답: 제안이 거둬졌으면 그만 듣고, 소리 없이(음성 꺼짐) 여쭌 제안이면 바로 듣는다
        if (engine.proposal == null && listenedFor != null) {
            listenedFor = null
            voiceAnswer.cancel()
        } else if (!speaking && (!speaker.enabled || speaker.engine == Speaker.Engine.MUTE)) {
            maybeListen()
        }
        history.addLast(engine.air.ppm.toFloat())
        while (history.size > HISTORY_POINTS) history.removeFirst()
        // 판단은 0.5초마다, 화면은 1초마다. 말하는 동안엔 화면을 멈춰 음성에 CPU를 양보
        tickCount++
        // 비 오는 동안엔 30초마다 와이퍼 스위치(빗발 세기)를 읽는다 — 스위치는 바뀔 때 알림이 없다
        if (engine.state.wiperOn && tickCount % WIPER_READ_EVERY_TICKS == 0L) gateway.readWiperSwitch()
        // 배운 것이 바뀌었으면 5초마다 한 번 저장(앱을 다시 켜도 기억하도록)
        if (tickCount % SAVE_EVERY_TICKS == 0L) saveHabits()
        if (!speaking && tickCount % UI_EVERY_TICKS == 0L) refresh()
    }

    private fun execute(commands: List<CareCommand>) {
        commands.forEach { cmd ->
            when (cmd) {
                is CareCommand.Say -> {
                    lastSay = cmd.text
                    lastSayAt = SystemClock.elapsedRealtime()
                    speaker.say(cmd.text)
                }
                // 소리 내지 않을 한 줄 — 화면에만
                is CareCommand.Notice -> {
                    lastSay = cmd.text
                    lastSayAt = SystemClock.elapsedRealtime()
                }
                else -> gateway.execute(cmd)
            }
        }
    }

    /** 주행이 끝나면(D → P) '오늘의 1cm' 카드를 띄운다 — 운전 중엔 조용히, 하루 끝에 한 장. */
    private fun onGearChanged(wasParked: Boolean, parked: Boolean) {
        val now = clock.now()
        if (wasParked && !parked) tripStartMs = now
        if (!wasParked && parked) {
            val started = tripStartMs
            tripStartMs = null
            if (started != null && engine.report(dayStartMs).total > 0) {
                _ui.update { it.copy(showReport = true) }
            }
        }
    }

    private fun refresh() {
        val state = engine.state
        _ui.update {
            it.copy(
                cabin = state,
                receivedSignals = received.toList(),
                co2Ppm = engine.air.ppm.roundToInt(),
                co2History = history.toList(),
                r0Status = engine.boarding.status,
                r1Status = engine.air.status(state, clock.now(), asking = engine.proposal?.offer?.action == CareAction.FRESH_AIR),
                freshAirCount = engine.air.bursts,
                rangeLevel = engine.range.level,
                rangeMarginPct = engine.range.marginPct,
                lastSay = lastSay,
                events = engine.log.events.takeLast(80).reversed(),
                report = engine.report(dayStartMs),
                frameless = engine.config.frameless,
                windowCrackStep = engine.config.windowCrackStep,
                voiceEngine = speaker.engine.label,
                venting = engine.ventilation.isActive,
                ventPaused = engine.ventilation.pausedForCar,
                ventStatus = engine.ventilation.status(clock.now()),
                ventOutlet = engine.ventilation.outlet,
                ventRestMin = ((engine.smoke.cooldownLeftMs(clock.now()) + 59_999) / 60_000).toInt(),
                proposal = engine.proposal?.let { p ->
                    ProposalUi(
                        routine = p.offer.routine,
                        action = p.offer.action,
                        ask = p.offer.ask,
                        reason = p.offer.reason,
                        voice = p.offer.voice,
                        canTrust = !p.offer.habit && p.offer.autoOk,
                        detail = p.offer.detail,
                    )
                },
                trusted = CareAction.entries.filter { it != CareAction.HABIT && engine.isTrusted(it) },
                quiet = CareAction.entries.filter { engine.isQuiet(it) },
                demoSpeed = clock.speed,
                speedHeld = clock.hold && clock.speed > 1,
                learner = engine.habits.view(clock.now()),
                hourOverride = engine.habits.hourOverride,
                voiceAnswer = voiceAnswerOn,
                voiceAnswerStatus = when {
                    !voiceAnswerOn -> "꺼짐"
                    voiceAnswer.ready -> "켜짐"
                    voiceAnswer.failed != null -> "사용 불가"
                    else -> "준비 중"
                },
            )
        }
    }

    // ---- R8 배우는 눈치: 저장 ----

    private var savedRevision = -1L

    private fun loadHabits() {
        prefs.getString(KEY_HABITS, null)?.let { engine.habits.import(it) }
        savedRevision = engine.habits.revision
        val n = engine.habits.view(clock.now()).habits.size
        if (n > 0) addDiagnostic("배운 습관 ${n}개 불러옴")
    }

    private fun saveHabits() {
        val rev = engine.habits.revision
        if (rev == savedRevision) return
        savedRevision = rev
        prefs.edit().putString(KEY_HABITS, engine.habits.export()).apply()
    }

    // ---- 제안 → 승인 ----

    /** '네'(always = "다음부턴 알아서"). */
    fun approve(always: Boolean) {
        val p = engine.proposal ?: return
        voiceAnswer.cancel()
        if (lastSay == p.offer.ask) lastSay = null // 대답했으니 물음은 화면에서 내린다
        val go = {
            execute(engine.approve(clock.now(), always))
            syncHold()
            saveTrust()
            saveHabits()
            refresh()
        }
        if (p.offer.action == CareAction.VENTILATION) {
            // 바람 방향·빗발 세기는 변경 알림이 없어서, 켜기 직전에 한 번 더 읽는다
            gateway.readFanDirection()
            gateway.readWiperSwitch()
            scope.launch {
                delay(FAN_DIRECTION_READ_MS)
                go()
            }
        } else {
            go()
        }
    }

    /** '아니요'. */
    fun reject() {
        voiceAnswer.cancel()
        if (lastSay == engine.proposal?.offer?.ask) lastSay = null
        execute(engine.reject(clock.now()))
        syncHold()
        saveHabits()
        refresh()
    }

    /** "알아서 하는 일"에서 빼기 — 다음부턴 다시 먼저 여쭙는다. */
    fun revokeTrust(action: CareAction) {
        engine.setTrusted(action, false)
        saveTrust()
        refresh()
    }

    /** R6 흐름 환기 끄기(켜는 건 제안으로만). */
    fun stopVentilation() {
        execute(engine.stopVentilation(clock.now()))
        refresh()
    }

    private fun loadTrust() {
        val saved = prefs.getStringSet(KEY_TRUSTED, emptySet()).orEmpty()
        val actions = CareAction.entries.filter { it.name in saved }.toSet()
        engine.updateConfig { it.copy(trusted = actions) }
        if (actions.isNotEmpty()) addDiagnostic("알아서 하는 일: ${actions.joinToString(", ") { it.label }}")
    }

    private fun saveTrust() {
        val names = CareAction.entries.filter { engine.isTrusted(it) }.map { it.name }.toSet()
        prefs.edit().putStringSet(KEY_TRUSTED, names).apply()
    }

    /** 제안에 말로 대답하기 켜기/끄기(실험). 켜 두면 앱을 다시 켜도 유지. */
    fun setVoiceAnswer(on: Boolean) {
        voiceAnswerOn = on
        prefs.edit().putBoolean(KEY_VOICE_ANSWER, on).apply()
        if (on) voiceAnswer.prepare() else voiceAnswer.cancel()
        addDiagnostic(if (on) "음성 대답 켬 — 여쭌 뒤 '네 / 아니요 / 다음부턴 알아서'를 들어요" else "음성 대답 끔")
        refresh()
    }

    /** 여쭌 말이 끝났을 때 한 번만 듣는다(같은 제안을 두 번 듣지 않음). */
    private fun maybeListen() {
        if (!voiceAnswerOn) return
        val p = engine.proposal ?: return
        if (!p.offer.voice || listenedFor === p) return
        listenedFor = p
        voiceAnswer.listen()
    }

    private fun onVoiceReply(reply: Reply) {
        val p = engine.proposal ?: return
        if (listenedFor !== p) return // 그새 다른 제안으로 바뀌었으면 듣지 않은 걸로
        when (reply) {
            Reply.YES -> approve(always = false)
            Reply.NO -> reject()
            // '다음부턴 알아서'를 허락할 수 있는 제안이면 허락, 습관 여쭘(R0 1cm 등)처럼 '네'가 곧 허락인 제안이면 그냥 네
            Reply.ALWAYS -> approve(always = !p.offer.habit && p.offer.autoOk)
        }
    }

    fun testVoice() {
        addDiagnostic("음성(TTS) 테스트 요청 — 엔진: ${speaker.engine.label}${if (speaker.enabled) "" else " (음성 꺼짐)"}")
        speaker.test()
    }

    // ---- 화면 조작 (데모 시뮬레이터: Virtual Console 대신 쓸 수 있음) ----

    fun simulate(signal: VehicleSignal) = process(signal)

    fun toggleDoor(door: DoorId) =
        simulate(VehicleSignal.Door(door, open = door !in engine.state.openDoors))

    fun setOccupants(count: Int) {
        SeatId.entries.forEachIndexed { index, seat ->
            val occupied = index < count
            if ((seat in engine.state.occupiedSeats) != occupied) simulate(VehicleSignal.SeatOccupancy(seat, occupied))
        }
    }

    fun changeRange(deltaKm: Float) {
        val current = engine.state.rangeKm ?: 120f
        simulate(VehicleSignal.RangeRemaining((current + deltaKm).coerceAtLeast(0f)))
    }

    fun setDemoSpeed(speed: Int) {
        clock.setSpeed(speed)
        refresh()
    }

    /** 가상 센서(L2 실내 카메라): 흡연 손동작. */
    fun simulateGesture(seat: SeatId) = simulate(VehicleSignal.SmokingGesture(seat))

    /** 가상 센서(실내 공기질): 깨끗한 공기에서 연기로 확 뛰는 장면. */
    fun simulateSmokeSpike() {
        simulate(VehicleSignal.CabinPm25(CLEAN_PM25))
        simulate(VehicleSignal.CabinPm25(SMOKE_PM25))
    }

    fun simulateCleanAir() = simulate(VehicleSignal.CabinPm25(CLEAN_PM25))

    /** 날씨: 0 = 맑음, 1 = 비, 2 = 심한 폭우(와이퍼 연속 고속). */
    fun simulateWeather(level: Int) {
        simulate(VehicleSignal.Wiper(level > 0))
        simulate(VehicleSignal.HeavyRain(level >= 2))
    }

    fun toggleOtherWindow(window: WindowId) {
        val open = (engine.state.otherWindows[window] ?: 0) > 0
        simulate(VehicleSignal.OtherWindow(window, if (open) 0 else 4))
    }

    fun setFrameless(frameless: Boolean) {
        engine.updateConfig { it.copy(frameless = frameless) }
        refresh()
    }

    // ---- R8 배우는 눈치: 시연 장면 (Virtual Console 대신) ----

    /** 시계를 이 시각으로 본다(null = 실제 시각). 새벽 출장 같은 '평소와 다른 날'을 만들 때. */
    fun setHour(hour: Int?) {
        engine.habits.hourOverride = hour
        engine.habits.retime(clock.now())
        refresh()
    }

    fun setOutsideTemp(celsius: Float) = simulate(VehicleSignal.OutsideTemperature(celsius))

    /** 탄다: 운전석 문 열림 → 착석 → 닫힘. 다 타면 NUNCHI가 (배운 게 있으면) 먼저 여쭙는다. */
    fun board() {
        humanPace()
        // 달리는 차에 탈 수는 없다 — 먼저 세운다
        if (!engine.state.parked) simulate(VehicleSignal.Gear(parked = true))
        // '타기' = 새 승차: 타고 있던 기록이 남아 있으면 접고 새로 시작(방금 고른 시각·날씨로 배우게)
        execute(engine.beginTrip(clock.now()))
        simulate(VehicleSignal.Door(DoorId.FRONT_LEFT, true))
        simulate(VehicleSignal.SeatOccupancy(SeatId.DRIVER, true))
        simulate(VehicleSignal.Door(DoorId.FRONT_LEFT, false))
    }

    /** 내린다: P → 운전석 문 열림 → 자리 빔 → 닫힘. 시동이 꺼지며 열선·창·온도는 처음 값으로(그건 사람 손이 아니라 배우지 않는다). */
    fun alight() {
        humanPace()
        if (!engine.state.parked) simulate(VehicleSignal.Gear(parked = true))
        simulate(VehicleSignal.Door(DoorId.FRONT_LEFT, true))
        simulate(VehicleSignal.SeatOccupancy(SeatId.DRIVER, false))
        // 같이 탄 사람도 함께 내린다
        engine.state.occupiedSeats.filter { it != SeatId.DRIVER }.forEach { simulate(VehicleSignal.SeatOccupancy(it, false)) }
        simulate(VehicleSignal.Door(DoorId.FRONT_LEFT, false))
        CAR_START_VALUES.forEach { (control, value) -> simulate(control.signal(value)) }
    }

    /** 사람이 직접 바꿈. 처음 만지는 설정이면 '지금 값'을 먼저 알려 기준으로 삼게 한다. 시뮬레이터의 직접 조작은 사람이 한 것이 확실하다. */
    fun manual(control: Control, value: Int) {
        humanPace()
        ensureKnown(control)
        execute(engine.onSignal(control.signal(value), clock.now(), human = true))
        syncHold()
        refresh()
    }

    /**
     * 타고 내리기·직접 조작은 사람 속도로 본다: 빨리 감기 중이면 ×1로 되돌린다.
     * (빨리 감기는 '20초 안에 이어서 바꾼 묶음'·'10초 안에 되돌림' 같은 기준도 함께 줄여서, 그대로 두면 습관을 잘못 가른다)
     */
    private fun humanPace() {
        if (clock.speed == 1) return
        clock.setSpeed(1)
        addDiagnostic("빨리 감기를 ×1로 되돌림 — 타고 내리기·직접 조작은 실제 속도로 봐요")
    }

    /** 여쭙는 동안은 실제 속도로(빨리 감기 중에도 대답할 시간은 1분 그대로). */
    private fun syncHold() = clock.setHold(engine.proposal != null)

    private fun rollDay(nowMs: Long) {
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val index = today.toEpochDay()
        if (index == dayIndex) return
        // 날이 바뀌었다(앱을 처음 켠 때는 아님): 오늘 사양한 일·하루 한 번 여쭌 일을 풀어 준다
        if (dayIndex != null) engine.newDay()
        dayIndex = index
        dayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** 독특한 조합 한 번에: 히터 27℃ + 핸들 열선 + 운전석 창 1cm + 무드등 주황. */
    fun uniqueCombo() {
        UNIQUE_COMBO.forEach { (control, value) -> manual(control, value) }
    }

    private var beforeGlass = FanDirection.FLOOR

    /** 차(오토 디포그)가 바람을 앞유리로 돌림. */
    fun carGlassAir() {
        humanPace()
        ensureKnown(Control.FAN_DIR)
        beforeGlass = engine.state.fanDirection?.takeIf { !it.defrost } ?: FanDirection.FLOOR
        simulate(VehicleSignal.FanDirectionChanged(FanDirection.DEFROST_AND_FLOOR))
    }

    /** 사람이 바로 원래 방향으로 되돌림. */
    fun revertGlassAir() {
        humanPace()
        simulate(VehicleSignal.FanDirectionChanged(beforeGlass))
    }

    private fun ensureKnown(control: Control) {
        if (engine.habits.knows(control)) return
        val current = engine.state.read(control) ?: CAR_START_VALUES[control] ?: 0
        simulate(control.signal(current))
    }

    fun forgetHabit(id: String) {
        engine.habits.forgetHabit(id)
        settleAfterEdit()
    }

    /** 화면에서 배운 것을 지운 뒤: 그 습관을 여쭙던 제안이 있으면 바로 거두고 저장한다. */
    private fun settleAfterEdit() {
        execute(engine.tick(clock.now()))
        syncHold()
        saveTrust()
        saveHabits()
        refresh()
    }

    fun untrustHabit(id: String) {
        engine.habits.untrustHabit(id)
        saveHabits()
        refresh()
    }

    fun forgetAversion(key: String) {
        engine.habits.forgetAversion(key)
        if (!engine.habits.dryAirEnabled) engine.setTrusted(CareAction.DRY_AIR, false)
        settleAfterEdit()
    }

    fun clearLearning() {
        engine.habits.clearAll()
        engine.setTrusted(CareAction.DRY_AIR, false)
        settleAfterEdit()
    }

    fun setVoice(on: Boolean) {
        speaker.enabled = on
        _ui.update { it.copy(voiceOn = on) }
    }

    fun showReport() = _ui.update { it.copy(showReport = true) }
    fun dismissReport() = _ui.update { it.copy(showReport = false) }
    fun toggleDiagnostics() = _ui.update { it.copy(showDiagnostics = !it.showDiagnostics) }

    fun resetDay() {
        engine.resetDay() // 오늘의 기록·사양 횟수만 지운다('알아서 하는 일' 허락은 그대로)
        history.clear()
        lastSay = null
        refresh()
    }

    fun stop() {
        scope.cancel()
        aaos.stop()
        gateway.disconnect()
        speaker.shutdown()
        voiceAnswer.release()
        started = false
    }

    private companion object {
        const val TICK_MS = 500L
        const val HISTORY_POINTS = 240
        const val SAY_VISIBLE_MS = 20_000L
        const val FAN_DIRECTION_READ_MS = 400L
        const val DIRECTION_READ_GAP_MS = 5_000L
        const val WIPER_READ_EVERY_TICKS = 60L
        const val KEY_TRUSTED = "trusted"
        const val KEY_HABITS = "habits"
        const val KEY_VOICE_ANSWER = "voice_answer"
        const val CLEAN_PM25 = 12
        const val SMOKE_PM25 = 180
        const val UI_EVERY_TICKS = 2L
        const val SAVE_EVERY_TICKS = 10L

        /** 시동이 켜질 때의 차(시연용): 설정온도 22℃, 열선·창 꺼짐, 무드등 파랑, 겨울이라 바람은 발밑. */
        val CAR_START_VALUES: Map<Control, Int> = linkedMapOf(
            Control.TEMP to 220,
            Control.SEAT_HEAT to 0,
            Control.WHEEL_HEAT to 0,
            Control.DRIVER_WINDOW to 0,
            Control.AMBIENT_COLOR to AmbientColor.BLUE.argb,
            Control.AC to 0,
            Control.FAN_DIR to FanDirection.FLOOR.ordinal,
        )

        /** 시연용 '독특한 조합': 겨울 아침에 히터를 세게 틀면서 창은 1cm, 핸들 열선, 무드등은 주황. */
        val UNIQUE_COMBO: Map<Control, Int> = linkedMapOf(
            Control.TEMP to 270,
            Control.WHEEL_HEAT to 1,
            Control.DRIVER_WINDOW to 1,
            Control.AMBIENT_COLOR to AmbientColor.ORANGE.argb,
        )
        const val TTS_PERMISSION = "pleos.car.permission.TTS_SERVICE"
        val DIAG_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        fun signalLabel(signal: VehicleSignal) = when (signal) {
            is VehicleSignal.Door -> "문"
            is VehicleSignal.SeatOccupancy -> "착석"
            is VehicleSignal.Recirculation -> "내외기"
            is VehicleSignal.FanSpeed -> "풍량"
            is VehicleSignal.CabinTemperature -> "실내온도"
            is VehicleSignal.Wiper -> "와이퍼"
            is VehicleSignal.HeavyRain -> "빗발 세기"
            is VehicleSignal.Gear -> "기어"
            is VehicleSignal.DriverWindow -> "창문"
            is VehicleSignal.OtherWindow -> "다른 창"
            is VehicleSignal.FanDirectionChanged -> "바람 방향"
            is VehicleSignal.CabinPm25 -> "실내 미세먼지"
            is VehicleSignal.SmokingGesture -> "흡연 손동작"
            is VehicleSignal.RangeRemaining -> "주행가능거리"
            is VehicleSignal.RouteRemaining -> "경로"
            is VehicleSignal.TireLow -> "타이어"
            is VehicleSignal.Comfort -> signal.control.label
            is VehicleSignal.OutsideTemperature -> "바깥 온도"
        }
    }
}
