package app.nunchi

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * 눈치의 한마디를 음성으로.
 *
 * 1순위: Pleos TextToSpeech SDK 온디바이스 모드 — 차에 들어 있는 음성 엔진을 쓴다(별도 승인·네트워크 불필요).
 * 2순위: 안드로이드 기본 TTS — Pleos 엔진이 없거나 실패하면.
 * 둘 다 안 되면 화면에만 표시한다. 준비·재생·오류는 [onStatus]로 진단 창에 남긴다
 * ("재생 완료"까지 찍히는데 소리가 안 나면 앱이 아니라 에뮬레이터 오디오·볼륨 문제).
 * 말을 만들고 읽는 동안은 [onSpeaking](true) — 화면 갱신을 쉬어 음성 엔진에 CPU를 양보하게 한다.
 */
class Speaker(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onSpeaking: (Boolean) -> Unit = {},
) {

    enum class Engine(val label: String) {
        NONE("준비 중"),
        PLEOS("Pleos 온디바이스"),
        ANDROID("안드로이드 기본"),
        MUTE("사용 불가"),
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var engine: Engine = Engine.NONE
        private set

    @Volatile
    var enabled = true

    private var pleos: PleosTts? = null
    private var pleosTried = false
    private var android: AndroidTts? = null
    private var androidTried = false
    private var androidReady = false
    private var pending: String? = null
    private var lastText: String? = null
    private var utterance = 0
    private var speaking = false
    private val speakingTimeout = Runnable { setSpeaking(false) }

    init {
        main.post { startPleos() }
        // Pleos 엔진이 답이 없으면(서비스 없음 등) 안드로이드 TTS를 먼저 띄워 둔다. 나중에 Pleos가 준비되면 그쪽으로 바꾼다.
        main.postDelayed({ if (engine == Engine.NONE) fallback("Pleos 음성 엔진 응답 없음") }, PLEOS_TIMEOUT_MS)
    }

    fun say(text: String) {
        if (!enabled) return
        main.post {
            lastText = text
            when (engine) {
                Engine.PLEOS -> speakPleos(text)
                Engine.ANDROID -> speakAndroid(text)
                Engine.NONE -> pending = text
                Engine.MUTE -> Unit
            }
        }
    }

    /** 진단용: 지금 엔진으로 한마디 해 본다. */
    fun test() = say("NUNCHI 음성 테스트예요. 이 말이 들리면 정상이에요.")

    /** TTS 권한을 새로 받았을 때 Pleos 엔진을 다시 시도한다. */
    fun retryPleos() = main.post {
        if (engine == Engine.PLEOS) return@post
        releasePleos()
        pleosTried = false
        startPleos()
    }

    fun shutdown() = main.post {
        releasePleos()
        runCatching { android?.shutdown() }
        android = null
    }

    // ---- Pleos TextToSpeech SDK ----

    private val pleosEvents = object : EventListener {
        override fun onStart() = report("재생 시작 (Pleos)")
        override fun onReady() = Unit
        override fun onDone() {
            report("재생 완료 (Pleos)")
            main.post { setSpeaking(false) }
        }

        override fun onStop() {
            main.post { setSpeaking(false) }
        }

        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(error: ErrorType) {
            main.post {
                setSpeaking(false)
                onPleosFailed("재생 오류($error)")
            }
        }
    }

    private fun startPleos() {
        if (pleosTried) return
        pleosTried = true
        try {
            val tts = PleosTts(appContext, Mode.ON_DEVICE)
            pleos = tts
            // 순서 주의: 엔진 객체는 initialize() 안에서 만들어진다 → 이벤트 리스너는 그 뒤에 붙인다
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        runCatching { tts.setLocale(Locale.KOREA) }
                        engine = Engine.PLEOS
                        onStatus("음성(TTS) 준비 ✓ Pleos 온디바이스")
                        flushPending()
                    }
                }

                override fun onFailure(error: ErrorType) {
                    main.post { onPleosFailed("초기화 실패($error)") }
                }
            })
            tts.addEventListener(pleosEvents)
        } catch (t: Throwable) {
            // SecurityException = pleos.car.permission.TTS_SERVICE 권한 없음, NoClassDefFoundError = Pleos 기기 아님 등
            onPleosFailed("사용 불가(${t.javaClass.simpleName}${t.message?.let { ": " + it.take(70) }.orEmpty()})")
        }
    }

    private fun speakPleos(text: String) {
        setSpeaking(true)
        runCatching { pleos?.speak(text, true) }
            .onFailure {
                setSpeaking(false)
                onPleosFailed("재생 실패(${it.javaClass.simpleName})")
            }
    }

    private fun onPleosFailed(reason: String) {
        val wasActive = engine == Engine.PLEOS
        if (wasActive) {
            engine = Engine.NONE
            pending = lastText // 못 한 한마디는 대체 엔진으로 다시
        }
        fallback("Pleos 음성 $reason")
    }

    private fun releasePleos() {
        pleos?.let { tts ->
            runCatching { tts.removeEventListener(pleosEvents) }
            runCatching { tts.release() }
        }
        pleos = null
    }

    // ---- 안드로이드 기본 TTS (대체) ----

    private fun fallback(reason: String) {
        onStatus("음성(TTS) $reason → 안드로이드 기본 TTS로 대체")
        if (androidReady) {
            if (engine != Engine.PLEOS) {
                engine = Engine.ANDROID
                flushPending()
            }
        } else {
            startAndroid()
        }
    }

    private fun startAndroid() {
        if (androidTried) return
        androidTried = true
        android = runCatching { AndroidTts(appContext) { status -> main.post { onAndroidInit(status) } } }
            .getOrElse {
                giveUp("안드로이드 TTS를 만들 수 없음(${it.javaClass.simpleName})")
                null
            }
    }

    private fun onAndroidInit(status: Int) {
        val tts = android ?: return
        if (status != AndroidTts.SUCCESS) return giveUp("안드로이드 TTS 초기화 실패(status=$status)")
        val result = tts.setLanguage(Locale.KOREA)
        if (result == AndroidTts.LANG_MISSING_DATA || result == AndroidTts.LANG_NOT_SUPPORTED) {
            return giveUp("안드로이드 TTS에 한국어 음성 데이터가 없음(result=$result)")
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = report("재생 시작 (안드로이드)")
            override fun onDone(utteranceId: String?) {
                report("재생 완료 (안드로이드)")
                main.post { setSpeaking(false) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                report("재생 오류 (안드로이드)")
                main.post { setSpeaking(false) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                report("재생 오류 (안드로이드, code=$errorCode)")
                main.post { setSpeaking(false) }
            }
        })
        androidReady = true
        if (engine != Engine.PLEOS) {
            engine = Engine.ANDROID
            onStatus("음성(TTS) 준비 ✓ 안드로이드 기본 (${tts.defaultEngine.orEmpty()})")
            flushPending()
        }
    }

    private fun speakAndroid(text: String) {
        setSpeaking(true)
        runCatching { android?.speak(text, AndroidTts.QUEUE_FLUSH, null, "nunchi-${utterance++}") }
            .onFailure {
                setSpeaking(false)
                report("재생 실패 (안드로이드, ${it.javaClass.simpleName})")
            }
    }

    private fun giveUp(reason: String) {
        onStatus("음성(TTS) 사용 불가: $reason — 한마디는 화면에만 표시")
        if (engine != Engine.PLEOS) engine = Engine.MUTE
    }

    // ---- 공통 ----

    /** 말하는 중 표시. 끝 알림이 안 오더라도 15초 뒤엔 풀어 준다. */
    private fun setSpeaking(on: Boolean) {
        main.removeCallbacks(speakingTimeout)
        if (on) main.postDelayed(speakingTimeout, SPEAKING_TIMEOUT_MS)
        if (speaking == on) return
        speaking = on
        onSpeaking(on)
    }

    private fun flushPending() {
        val text = pending ?: return
        pending = null
        say(text)
    }

    private fun report(what: String) {
        main.post { onStatus("음성(TTS) $what") }
    }

    private companion object {
        const val PLEOS_TIMEOUT_MS = 5_000L
        const val SPEAKING_TIMEOUT_MS = 15_000L
    }
}
