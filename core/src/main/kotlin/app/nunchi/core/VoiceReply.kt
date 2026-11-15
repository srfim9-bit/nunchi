package app.nunchi.core

/** 제안에 말로 한 대답. */
enum class Reply(val label: String) {
    YES("네"),
    NO("아니요"),
    /** "다음부턴 알아서" — 허락할 수 있는 제안이면 허락, 아니면 '네'로. */
    ALWAYS("다음부턴 알아서"),
}

/**
 * 짧은 말 대답을 네 / 아니요 / 다음부턴 알아서로 알아듣는다(음성 인식 결과 한 줄).
 * - '아니'가 들어가면 무조건 아니요가 먼저("아니, 됐어요", "아니예요") — 잘못 실행하는 것보다 안 하는 쪽이 안전하다.
 * - '괜찮아요'는 좋다·됐다 둘 다로 들려 대답으로 치지 않는다(화면 버튼으로 답하시게).
 * - 알아듣지 못하면 null — 아무것도 하지 않는다.
 */
fun interpretReply(text: String): Reply? {
    val t = text.replace(" ", "").replace(",", "").replace(".", "").lowercase()
    if (t.isEmpty()) return null
    if ("괜찮" in t) return null
    if (NO_WORDS.any { it in t }) return Reply.NO
    if ("알아서" in t) return Reply.ALWAYS
    if (YES_WORDS.any { it in t }) return Reply.YES
    return null
}

private val NO_WORDS = listOf("아니", "싫어", "하지마", "됐어", "됐습니다", "그만", "안해", "안할래", "안돼", "필요없")
private val YES_WORDS = listOf("네", "예", "응", "그래", "좋아", "좋습니다", "해줘", "해주세요", "해주라", "부탁", "오케이", "ok", "맞아")
