package app.nunchi

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.nunchi.core.Reply
import app.nunchi.core.interpretReply
import java.util.Locale

/**
 * 제안에 말로 대답하기(실험) — Pleos SpeechToText SDK 온디바이스 모드.
 *
 * NUNCHI가 여쭌 한 문장이 끝나면 한 번만 짧게 듣고, "네·응·좋아" = 네, "아니·싫어·됐어" = 아니요, "알아서" = 다음부턴 알아서.
 * '괜찮아요'처럼 애매한 말이나 알아듣지 못한 말은 대답으로 치지 않는다(화면 버튼은 그대로).
 * 엔진이 없거나 권한(pleos.car.permission.STT_SERVICE)이 없으면 진단에 남기고 조용히 빠진다 — 켜기 전엔 아무것도 하지 않는다.
 */
class VoiceAnswer(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onReply: (Reply) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var stt: SpeechToText? = null
    private var tried = false
    private var listening = false

    @Volatile
    var ready = false
        private set

    @Volatile
    var failed: String? = null
        private set

    /** 켤 때 엔진을 준비한다(이미 준비됐거나 준비 중이면 그대로, 실패했었으면 처음부터 다시). */
    fun prepare() = main.post {
        if (tried && failed == null) return@post
        if (tried) releaseNow()
        tried = true
        failed = null
        // 차에 음성 인식 서비스가 없으면 답이 영영 안 올 수 있다 — 5초 안에 준비되지 않으면 접는다
        main.postDelayed({ if (!ready && failed == null) fail("엔진 응답 없음") }, PREPARE_TIMEOUT_MS)
        try {
            val s = SpeechToText(appContext, Mode.ON_DEVICE)
            stt = s
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        runCatching { s.setLocale(Locale.KOREA) }
                        ready = true
                        onStatus("음성 대답(STT) 준비 ✓ Pleos 온디바이스 — 여쭌 뒤 '네/아니요'를 들어요")
                    }
                }

                override fun onFailure(error: ErrorType) {
                    main.post { fail("초기화 실패($error)") }
                }
            })
            // TTS와 같은 순서: 엔진 객체는 initialize() 안에서 만들어진다 → 리스너는 그 뒤에
            runCatching { s.addListener(results) }.onFailure { fail("리스너 연결 실패(${it.javaClass.simpleName})") }
        } catch (t: Throwable) {
            // SecurityException = STT 권한 없음, NoClassDefFoundError = Pleos 기기 아님 등
            fail("사용 불가(${t.javaClass.simpleName}${t.message?.let { ": " + it.take(70) }.orEmpty()})")
        }
    }

    /** 여쭌 말이 끝난 뒤 한 번 듣는다(최대 [LISTEN_MS]). */
    fun listen() = main.post {
        val s = stt ?: return@post
        if (!ready || listening) return@post
        listening = true
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, LISTEN_MS)
        runCatching { s.request() }.onFailure {
            listening = false
            main.removeCallbacks(timeout)
            onStatus("음성 대답 듣기 실패(${it.javaClass.simpleName})")
        }
    }

    /** 화면으로 답했거나 제안이 거둬졌으면 그만 듣는다. */
    fun cancel() = main.post {
        if (!listening) return@post
        listening = false
        main.removeCallbacks(timeout)
        runCatching { stt?.stop() }
    }

    fun release() = main.post { releaseNow() }

    private fun releaseNow() {
        main.removeCallbacks(timeout)
        listening = false
        stt?.let { s ->
            runCatching { s.removeListener(results) }
            runCatching { s.release() }
        }
        stt = null
        ready = false
    }

    private val timeout = Runnable {
        if (listening) {
            listening = false
            runCatching { stt?.stop() }
        }
    }

    private val results = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            if (!isFinal) return
            main.post {
                if (!listening) return@post
                listening = false
                main.removeCallbacks(timeout)
                val reply = interpretReply(text)
                onStatus("음성 대답: “${text.take(30)}” → ${reply?.label ?: "알아듣지 못함 — 화면으로 답해 주세요"}")
                reply?.let(onReply)
            }
        }

        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() {
            main.post { listening = false }
        }

        override fun onError(error: ErrorType) {
            main.post {
                listening = false
                onStatus("음성 대답 오류($error)")
            }
        }

        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun fail(reason: String) {
        failed = reason
        ready = false
        onStatus("음성 대답(STT) $reason — 화면 버튼으로 답해 주세요")
    }

    private companion object {
        const val LISTEN_MS = 6_000L
        const val PREPARE_TIMEOUT_MS = 5_000L
    }
}
