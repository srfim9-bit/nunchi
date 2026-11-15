package app.nunchi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 제안에 말로 대답하기 — 네/아니요/다음부턴 알아서, 애매하면 아무것도 하지 않는다. */
class VoiceReplyTest {

    @Test
    fun `네 계열`() {
        listOf("네", "네 해 주세요", "예", "응", "그래", "좋아요", "부탁해요", "오케이", "OK").forEach {
            assertEquals(it, Reply.YES, interpretReply(it))
        }
    }

    @Test
    fun `아니요 계열 — 아니가 들어가면 무조건 아니요`() {
        listOf("아니요", "아니오", "아니, 됐어요", "아니예요", "싫어", "하지 마", "됐어", "그만", "안 해", "필요 없어요").forEach {
            assertEquals(it, Reply.NO, interpretReply(it))
        }
    }

    @Test
    fun `다음부턴 알아서`() {
        assertEquals(Reply.ALWAYS, interpretReply("다음부턴 알아서 해 줘"))
        assertEquals(Reply.ALWAYS, interpretReply("응 알아서 해"))
        assertEquals("아니가 먼저", Reply.NO, interpretReply("아니 알아서 할게"))
    }

    @Test
    fun `괜찮아요는 좋다 됐다 둘 다라 대답으로 치지 않는다`() {
        assertNull(interpretReply("괜찮아요"))
        assertNull(interpretReply("네 괜찮아요"))
    }

    @Test
    fun `알아듣지 못하면 아무것도 하지 않는다`() {
        assertNull(interpretReply(""))
        assertNull(interpretReply("음..."))
        assertNull(interpretReply("오늘 날씨 어때"))
    }
}
