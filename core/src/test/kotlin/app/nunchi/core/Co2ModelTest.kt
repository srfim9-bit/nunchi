package app.nunchi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Co2ModelTest {

    @Test
    fun `2인 밀폐 내기순환 30분이면 약 5000ppm (도로공사 보정점)`() {
        val model = Co2Model()
        model.step(dtSeconds = 30 * 60.0, occupants = 2, recirculating = true)
        assertEquals(5_000.0, model.ppm, 30.0)
    }

    @Test
    fun `잘게 나눠 계산해도 한 번에 계산한 값과 같다`() {
        val coarse = Co2Model().apply { step(600.0, 2, true) }
        val fine = Co2Model().apply { repeat(600) { step(1.0, 2, true) } }
        assertEquals(coarse.ppm, fine.ppm, 0.01)
    }

    @Test
    fun `2인 기준 2000ppm까지 약 9분`() {
        val minutes = Co2Model().minutesUntil(2_000.0, occupants = 2, recirculating = true)!!
        assertEquals(8.8, minutes, 0.3)
    }

    @Test
    fun `4인이면 2인보다 두 배 가까이 빨리 탁해진다`() {
        val two = Co2Model().minutesUntil(2_000.0, 2, true)!!
        val four = Co2Model().minutesUntil(2_000.0, 4, true)!!
        assertTrue("4인 $four 분, 2인 $two 분", four < two * 0.55)
    }

    @Test
    fun `외기 90초면 1900ppm에서 1200ppm 아래로 떨어진다`() {
        val model = Co2Model(initialPpm = 1_900.0)
        model.step(90.0, occupants = 2, recirculating = false)
        assertTrue("after fresh air: ${model.ppm}", model.ppm < 1_200.0)
    }

    @Test
    fun `외기 유입 중에는 2000ppm에 도달하지 않는다`() {
        assertNull(Co2Model().minutesUntil(2_000.0, occupants = 2, recirculating = false))
    }

    @Test
    fun `문이 열리면 바깥 공기와 섞인다`() {
        val model = Co2Model(initialPpm = 2_600.0)
        model.doorOpened()
        assertEquals(1_200.0, model.ppm, 1.0)
    }
}
