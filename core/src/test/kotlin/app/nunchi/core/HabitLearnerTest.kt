package app.nunchi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R8 배우는 눈치 — 정해 둔 규칙 없이 운전자의 직접 조작에서 습관을 익히고, 먼저 여쭙고, 대답과 되돌림에서 다시 배운다. */
class HabitLearnerTest {

    private var hour = 8
    private val day = 24 * 3_600_000L
    private val orange = AmbientColor.ORANGE.argb
    private val blue = AmbientColor.BLUE.argb

    /** 바깥 영하 2℃의 아침. 차가 처음 알려 주는 값은 '기준'이라 사람의 조작으로 치지 않는다. */
    private fun engine(): CareEngine = CareEngine(CareConfig(), startMs = 0L, hourOf = { hour }, isWeekend = { false }).also { e ->
        e.onSignal(VehicleSignal.OutsideTemperature(-2f), 0)
        resetCar(e, 0)
    }

    /** 시동이 켜질 때의 차: 설정온도 22℃, 열선·창 꺼짐, 무드등 파랑, 얼굴 바람. */
    private fun resetCar(e: CareEngine, t: Long) {
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), t)
        e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 0), t)
        e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 0), t)
        e.onSignal(VehicleSignal.Comfort(Control.AMBIENT_COLOR, blue), t)
        e.onSignal(VehicleSignal.DriverWindow(0), t)
        e.onSignal(VehicleSignal.FanDirectionChanged(FanDirection.FACE), t)
    }

    /** 탄다(운전석 문 열림 → 착석 → 닫힘). 3초 뒤까지 지켜본 명령을 돌려준다. */
    private fun board(e: CareEngine, t: Long, passenger: Boolean = false): List<CareCommand> {
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), t)
        e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, true), t + 300)
        e.onSignal(VehicleSignal.SeatOccupancy(SeatId.PASSENGER, passenger), t + 400)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), t + 600)
        return e.tick(t + 3_000)
    }

    /** 독특한 조합: 히터 27℃ + 핸들 열선 + 운전석 창 1cm + 무드등 주황(20초 안에 연달아). */
    private fun combo(e: CareEngine, t: Long) {
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), t)
        e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), t + 2_000)
        e.onSignal(VehicleSignal.DriverWindow(1), t + 4_000)
        e.onSignal(VehicleSignal.Comfort(Control.AMBIENT_COLOR, orange), t + 6_000)
    }

    /** 내린다(P → 운전석 문 열림 → 자리 빔 → 닫힘). 시동이 꺼지며 열선·창은 원래대로. */
    private fun alight(e: CareEngine, t: Long) {
        e.onSignal(VehicleSignal.Gear(parked = true), t)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, true), t + 300)
        e.onSignal(VehicleSignal.SeatOccupancy(SeatId.DRIVER, false), t + 600)
        e.onSignal(VehicleSignal.SeatOccupancy(SeatId.PASSENGER, false), t + 700)
        e.onSignal(VehicleSignal.Door(DoorId.FRONT_LEFT, false), t + 900)
        resetCar(e, t + 1_200)
    }

    /** 하루: 타서 → 조합 → 출발 → 내림. */
    private fun dayWithCombo(e: CareEngine, d: Int, passenger: Boolean = false) {
        val t = d * day
        board(e, t, passenger)
        combo(e, t + 10_000)
        e.onSignal(VehicleSignal.Gear(parked = false), t + 40_000)
        e.tick(t + 70_000)
        alight(e, t + 600_000)
    }

    private fun CareEngine.messages() = log.events.map { it.message }

    private fun CareEngine.askedHabit(): Boolean = proposal?.offer?.action == CareAction.HABIT

    @Test
    fun `같은 상황에서 같은 묶음을 3번 하면 4번째 탈 때 먼저 여쭙고, 네 하면 그대로 맞춘다`() {
        val e = engine()
        repeat(3) { dayWithCombo(e, it) }
        assertTrue(e.messages().any { it.startsWith("습관으로 인식") })

        val t = 3 * day
        val asked = board(e, t)
        val offer = e.proposal?.offer
        assertTrue("다 탄 뒤 손대기 전에 여쭙는다", e.askedHabit())
        assertEquals("추운 아침엔 늘 하시던 대로 맞춰 드릴까요?", offer?.ask)
        assertTrue(asked.contains(CareCommand.Say("추운 아침엔 늘 하시던 대로 맞춰 드릴까요?")))
        assertEquals("설정온도 27℃ · 핸들 열선 켜기 · 운전석 창 살짝(1cm) · 무드등 주황", offer?.detail)

        assertEquals(
            listOf(
                CareCommand.SetComfort(Control.TEMP, 270),
                CareCommand.SetComfort(Control.WHEEL_HEAT, 1),
                CareCommand.SetDriverWindow(1),
                CareCommand.SetComfort(Control.AMBIENT_COLOR, orange),
            ),
            e.approve(t + 5_000),
        )
        assertEquals(1, e.report().counts[CareKind.HABIT])
    }

    @Test
    fun `조작 셋 이상이 두 번 똑같으면 두 번 만에 알아본다 — 우연일 리 없으니`() {
        val e = engine()
        repeat(2) { dayWithCombo(e, it) }
        assertTrue(e.messages().any { it.contains("두 번 같아 우연이 아니라고 판단") })
        board(e, 2 * day)
        assertTrue(e.askedHabit())
    }

    @Test
    fun `조작 한두 개짜리는 세 번 봐야 여쭙는다`() {
        val e = engine()
        repeat(2) { d ->
            board(e, d * day)
            e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), d * day + 10_000)
            e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 2), d * day + 12_000)
            alight(e, d * day + 600_000)
        }
        board(e, 2 * day)
        assertFalse(e.askedHabit())
        assertEquals("배우는 중 (2/3)", e.habits.view(0).habits.single().status)
    }

    @Test
    fun `가끔 하나 빠져도 같은 습관 — 세 번 중 두 번 이상 나온 조작만 맞춘다`() {
        val e = engine()
        dayWithCombo(e, 0)
        // 둘째 날은 무드등을 빼먹음
        val t = day
        board(e, t)
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), t + 10_000)
        e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), t + 12_000)
        e.onSignal(VehicleSignal.DriverWindow(1), t + 14_000)
        e.onSignal(VehicleSignal.Gear(parked = false), t + 40_000)
        e.tick(t + 70_000)
        alight(e, t + 600_000)
        dayWithCombo(e, 2)

        board(e, 3 * day)
        assertTrue(e.askedHabit())
        assertEquals("설정온도 27℃ · 핸들 열선 켜기 · 운전석 창 살짝(1cm) · 무드등 주황", e.proposal?.offer?.detail)
        assertEquals(1, e.habits.view(0).habits.size)
    }

    @Test
    fun `다음부턴 알아서면 묻지 않고 맞추고, 1분 안에 되돌리면 다시 묻기로`() {
        val e = engine()
        repeat(3) { dayWithCombo(e, it) }
        board(e, 3 * day)
        e.approve(3 * day + 5_000, always = true)
        alight(e, 3 * day + 600_000)

        val t = 4 * day
        val auto = board(e, t)
        assertFalse("허락받은 습관은 묻지 않는다", e.askedHabit())
        assertTrue(auto.contains(CareCommand.Say("추운 아침 세팅으로 맞춰 둘게요.")))
        assertTrue(auto.contains(CareCommand.SetComfort(Control.TEMP, 270)))

        // 맞춘 걸 20초 만에 직접 원래 값(22℃)으로 되돌림 = 분명한 '원치 않음'
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), t + 23_000)
        assertTrue(e.messages().any { it.contains("다시 여쭤보기로") })
        alight(e, t + 600_000)

        board(e, 5 * day)
        assertTrue("다시 여쭙는다", e.askedHabit())
        assertEquals("추운 아침엔 늘 하시던 대로 맞춰 드릴까요?", e.proposal?.offer?.ask)
    }

    /** 3일 배우고 4일째 '다음부턴 알아서'를 허락받은 차. 5일째 아침 = 묻지 않고 맞추는 날. */
    private fun trustedEngine(): CareEngine = engine().also { e ->
        repeat(3) { dayWithCombo(e, it) }
        board(e, 3 * day)
        e.approve(3 * day + 5_000, always = true)
        alight(e, 3 * day + 600_000)
    }

    @Test
    fun `알아서 맞춘 걸 다른 값으로 바꾸시면 '원치 않음'이 아니라 '달라진 취향' — 다음에 바꾸신 대로 여쭙고, 네 하면 습관을 고친다`() {
        val e = trustedEngine()
        val t = 4 * day
        assertTrue(board(e, t).contains(CareCommand.SetComfort(Control.TEMP, 270)))
        // 27℃로 맞춰 드렸더니 25℃로, 무드등도 보라로 바꾸심(원래 값 22℃·파랑으로 되돌린 게 아님)
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 250), t + 23_000)
        e.onSignal(VehicleSignal.Comfort(Control.AMBIENT_COLOR, AmbientColor.PURPLE.argb), t + 25_000)
        assertFalse(e.messages().any { it.contains("다시 여쭤보기로") })
        alight(e, t + 600_000)
        assertTrue(e.messages().any { it.contains("취향이 달라지신 것 같아요(설정온도 27℃ → 25℃ · 무드등 주황 → 보라)") })
        val noticed = e.habits.view(0).habits.single()
        assertEquals("달라진 취향 확인 중", noticed.status)
        assertEquals("취향이 달라지신 것 같아요: 설정온도 27℃ → 25℃ · 무드등 주황 → 보라 — 다음에 여쭤볼게요", noticed.note)

        // 다음 날: 알아서 하던 습관이지만 달라진 게 있으니 이번엔 여쭙는다 — 달라진 값으로
        val t2 = 5 * day
        board(e, t2)
        val offer = e.proposal?.offer
        assertTrue("말없이 옛 값으로 맞추지 않는다", e.askedHabit())
        assertEquals("지난번에 바꾸신 대로 맞춰 드릴까요?", offer?.ask)
        assertEquals(250, offer?.changes?.get(Control.TEMP))
        assertEquals(AmbientColor.PURPLE.argb, offer?.changes?.get(Control.AMBIENT_COLOR))
        assertTrue(offer?.detail!!.endsWith("(달라진 것: 설정온도 27℃ → 25℃ · 무드등 주황 → 보라)"))
        val applied = e.approve(t2 + 5_000)
        assertTrue(applied.contains(CareCommand.SetComfort(Control.TEMP, 250)))
        assertFalse(applied.contains(CareCommand.SetComfort(Control.TEMP, 270)))
        assertTrue(e.messages().any { it.startsWith("달라진 취향 반영") })
        alight(e, t2 + 600_000)

        // 습관이 바로 고쳐져, 그다음부턴 다시 묻지 않고 새 값으로
        val habit = e.habits.view(0).habits.single()
        assertEquals("설정온도 25℃ · 핸들 열선 켜기 · 운전석 창 살짝(1cm) · 무드등 보라", habit.items)
        assertEquals("알아서 맞춰 드려요", habit.status)
        assertNull(habit.note)
        val auto = board(e, 6 * day)
        assertFalse(e.askedHabit())
        assertTrue(auto.contains(CareCommand.SetComfort(Control.TEMP, 250)))
    }

    @Test
    fun `바꾸신 대로 할지 여쭸는데 아니요면 그날만의 일 — 늘 하시던 대로 기억한다`() {
        val e = trustedEngine()
        val t = 4 * day
        board(e, t)
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 250), t + 23_000)
        alight(e, t + 600_000)

        board(e, 5 * day)
        assertEquals("지난번에 바꾸신 대로 맞춰 드릴까요?", e.proposal?.offer?.ask)
        e.reject(5 * day + 5_000)
        assertTrue(e.messages().any { it.startsWith("지난번 변경은 그날만의 일로") })
        alight(e, 5 * day + 600_000)

        val auto = board(e, 6 * day)
        assertFalse("허락은 그대로 — 늘 하던 대로 알아서", e.askedHabit())
        assertTrue(auto.contains(CareCommand.SetComfort(Control.TEMP, 270)))
    }

    @Test
    fun `맞추자마자 곧바로 내려도(시동 끔이 10초 안) 시동 끔을 '되돌림'으로 오해하지 않는다`() {
        val e = engine()
        // 시연처럼 빠르게: 타서 3초 만에 조합, 그 직후 내림 → 시동이 꺼지며 온도·열선·무드등이 처음 값으로
        repeat(2) { d ->
            val t = d * day
            board(e, t)
            combo(e, t + 3_000)
            alight(e, t + 12_000)
        }
        val view = e.habits.view(0)
        assertTrue("시동 끔은 '원치 않음'이 아니다: ${view.aversions}", view.aversions.isEmpty())
        assertEquals("설정온도 27℃ · 핸들 열선 켜기 · 운전석 창 살짝(1cm) · 무드등 주황", view.habits.single().items)
        board(e, 2 * day)
        assertEquals("설정온도 27℃ · 핸들 열선 켜기 · 운전석 창 살짝(1cm) · 무드등 주황", e.proposal?.offer?.detail)
    }

    @Test
    fun `달리고 와서 세운 뒤 시동을 끄며 꺼지는 열선은 습관으로도 '원치 않음'으로도 배우지 않는다`() {
        val e = engine()
        repeat(3) { d ->
            val t = d * day
            board(e, t)
            e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 2), t + 10_000)
            e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), t + 12_000)
            e.onSignal(VehicleSignal.Gear(parked = false), t + 30_000)
            e.tick(t + 300_000)
            e.onSignal(VehicleSignal.Gear(parked = true), t + 900_000)
            // 아직 앉아 있는데 시동을 끄자 열선이 꺼진다(차가 한 일)
            e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 0), t + 905_000)
            e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 0), t + 905_050)
            e.tick(t + 1_000_000)
            alight(e, t + 1_100_000)
        }
        val view = e.habits.view(0)
        assertEquals("탈 때 습관 하나만", 1, view.habits.size)
        assertEquals("운전석 열선 2단 · 핸들 열선 켜기", view.habits.single().items)
        assertTrue(view.aversions.isEmpty())
    }

    @Test
    fun `한 번 곧바로 되돌렸던 값도 다시 직접 맞추고 그대로 두면 '불편해하시는 것'에서 지운다`() {
        val e = engine()
        board(e, 1_000)
        e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 3), 11_000)
        e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 0), 14_000) // 잘못 눌러 바로 끔
        assertEquals(1, e.habits.view(0).aversions.size)
        e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 3), 60_000) // 이번엔 켜고 그대로
        e.tick(300_000)
        assertTrue("다시 맞추고 두셨으면 불편해하신 게 아니었다", e.habits.view(0).aversions.isEmpty())
    }

    @Test
    fun `NUNCHI가 맞춘 직후 옛 값이 오면 실차에선 반향으로 거르지만, 사람의 조작이 확실하면(시뮬레이터) 되돌림으로 본다`() {
        // 실차 신호: 맞춘 지 0.5초 만에 옛 값(22℃) = 에뮬레이터가 옛 값을 한 번 더 보내는 것일 수 있다 → 거른다
        val car = trustedEngine()
        board(car, 4 * day)
        car.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), 4 * day + 3_500)
        assertFalse(car.messages().any { it.contains("다시 여쭤보기로") })

        // 시뮬레이터 직접 조작: 사람이 누른 게 확실하다 → 곧바로 되돌림(원치 않음)
        val sim = trustedEngine()
        board(sim, 4 * day)
        sim.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), 4 * day + 3_500, human = true)
        assertTrue(sim.messages().any { it.contains("다시 여쭤보기로") })
    }

    @Test
    fun `달라진 취향을 확인하는 중에도 저장했다 읽으면 기억한다`() {
        val e = trustedEngine()
        board(e, 4 * day)
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 250), 4 * day + 23_000)
        alight(e, 4 * day + 600_000)

        val copy = CareEngine(CareConfig(), startMs = 0L, hourOf = { hour }, isWeekend = { false })
        copy.habits.import(e.habits.export())
        assertEquals(e.habits.view(0).habits.single().status, copy.habits.view(0).habits.single().status)
    }

    @Test
    fun `같은 습관을 2번 연달아 사양하시면 더는 권하지 않는다`() {
        val e = engine()
        repeat(3) { dayWithCombo(e, it) }
        board(e, 3 * day)
        e.reject(3 * day + 5_000)
        alight(e, 3 * day + 600_000)
        board(e, 4 * day)
        assertTrue(e.askedHabit())
        e.reject(4 * day + 5_000)
        assertTrue(e.messages().any { it.contains("이제 먼저 권하지 않을게요") })
        alight(e, 4 * day + 600_000)

        board(e, 5 * day)
        assertFalse(e.askedHabit())
        assertEquals("먼저 권하지 않아요 (연달아 사양)", e.habits.view(0).habits.single().status)
    }

    @Test
    fun `동승자가 있을 때의 습관은 혼자일 때 권하지 않는다`() {
        val e = engine()
        repeat(3) { dayWithCombo(e, it, passenger = true) }

        board(e, 3 * day, passenger = false)
        assertFalse(e.askedHabit())
        alight(e, 3 * day + 600_000)

        board(e, 4 * day, passenger = true)
        assertEquals("추운 아침, 동승자가 있을 땐 늘 하시던 대로 맞춰 드릴까요?", e.proposal?.offer?.ask)
    }

    @Test
    fun `평소와 다른 시간엔 알아서 하던 것도 여쭙기만 하고, 그날 조작은 습관에 넣지 않는다`() {
        val e = engine()
        repeat(3) { dayWithCombo(e, it) }
        board(e, 3 * day)
        e.approve(3 * day + 5_000, always = true)
        alight(e, 3 * day + 600_000)
        board(e, 4 * day)
        alight(e, 4 * day + 600_000)

        // 예고 없는 새벽 출장
        hour = 4
        val t = 5 * day
        board(e, t)
        assertTrue(e.messages().any { it.startsWith("평소와 다른 시간(새벽)") })
        val offer = e.proposal?.offer
        assertEquals("평소보다 이른 시간이네요. 추운 아침처럼 맞춰 드릴까요?", offer?.ask)
        assertFalse("허락받았어도 이날은 묻는다", offer?.autoOk ?: true)

        e.reject(t + 5_000)
        assertTrue("오늘 사양은 평소 습관에 대한 '원치 않음'이 아니다", e.messages().any { it.startsWith("평소와 다른 날이라 오늘 사양하신 것은") })
        e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 3), t + 10_000)
        e.tick(t + 240_000) // 탈 때 세팅(3분)이 끝나야 한 묶음으로 정리된다
        assertTrue(e.messages().any { it.startsWith("평소와 다른 날이라 습관에 넣지 않음") })
        assertTrue(e.habits.view(t).today!!.startsWith("오늘은 평소와 다른 시간(새벽)"))
        alight(e, t + 600_000)

        // 다음 날 평소대로 아침에 타면, 허락받은 습관을 그대로 알아서 맞춘다
        hour = 8
        val auto = board(e, 6 * day)
        assertFalse(e.askedHabit())
        assertTrue(auto.contains(CareCommand.SetComfort(Control.TEMP, 270)))
        assertEquals("알아서 맞춰 드려요", e.habits.view(0).habits.single().status)
    }

    @Test
    fun `평소와 다른 시간인데 비슷한 습관도 없으면 화면에만 조용히 알린다`() {
        val e = engine()
        repeat(5) { d ->
            board(e, d * day)
            alight(e, d * day + 600_000)
        }
        hour = 4
        val out = board(e, 5 * day)
        assertTrue(out.contains(CareCommand.Notice("평소와 다른 시간이네요. 오늘은 따로 맞춰 두지 않을게요.")))
        assertFalse(out.any { it is CareCommand.Say })
    }

    @Test
    fun `NUNCHI가 보낸 명령의 반향은 사람의 조작으로 배우지 않는다`() {
        val e = engine()
        repeat(3) { dayWithCombo(e, it) }
        val t = 3 * day
        board(e, t)
        e.approve(t + 5_000)
        val before = e.messages().count { it.startsWith("조작 묶음 기억") }
        // 차가 바뀐 값을 돌려줌(반향) — 창은 한 칸씩 움직인다
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), t + 5_300) // 보낸 직후 옛 값 한 번
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), t + 5_600)
        e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), t + 5_600)
        e.onSignal(VehicleSignal.DriverWindow(1), t + 6_500)
        e.onSignal(VehicleSignal.Comfort(Control.AMBIENT_COLOR, orange), t + 5_700)
        e.tick(t + 240_000)
        assertEquals(before, e.messages().count { it.startsWith("조작 묶음 기억") })
        assertFalse(e.messages().any { it.contains("되돌리심") })
    }

    @Test
    fun `켰다가 곧바로 끈 건 습관이 아니라 불편해하시는 것으로 기억한다`() {
        val e = engine()
        val t = 0L + 1_000
        board(e, t)
        e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 3), t + 10_000)
        e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 0), t + 14_000)
        e.tick(t + 240_000)
        assertFalse(e.messages().any { it.startsWith("조작 묶음 기억") })
        assertEquals("운전석 열선 3단 — 1번 곧바로 되돌리심", e.habits.view(t).aversions.single().text)
    }

    @Test
    fun `차가 앞유리로 돌린 바람을 두 번 곧바로 되돌리면, 김이 서리기 전에 습기를 미리 빼 둘지 여쭙는다`() {
        val e = engine()
        val t = 1_000L
        board(e, t)
        e.onSignal(VehicleSignal.FanDirectionChanged(FanDirection.FLOOR), t + 5_000) // 평소 발밑
        repeat(2) { i ->
            val at = t + 30_000 + i * 30_000L
            e.onSignal(VehicleSignal.FanDirectionChanged(FanDirection.DEFROST_AND_FLOOR), at) // 차(오토 디포그)
            e.onSignal(VehicleSignal.FanDirectionChanged(FanDirection.FLOOR), at + 4_000) // 사람이 되돌림
        }
        assertEquals("앞유리 쪽 바람 — 2번 곧바로 되돌리심", e.habits.view(t).aversions.single().text)
        e.tick(t + 100_000)
        val offer = e.proposal?.offer
        assertEquals(CareAction.DRY_AIR, offer?.action)
        assertEquals("앞유리 쪽 바람이 불편하신 것 같아요. 다음부터는 김이 서리기 전에 습기를 미리 빼 둘까요? 바람은 발밑으로 보낼게요.", offer?.ask)
        assertFalse("앞유리 쪽 바람은 습관으로 배우지 않는다", e.habits.view(t).habits.any { it.items.contains("앞유리") })

        e.approve(t + 105_000)
        assertTrue(e.isTrusted(CareAction.DRY_AIR))
        alight(e, t + 600_000)

        // 비 오는 날, 젖은 우산을 들고 탐 → 김이 서리기 전에 알아서
        val t2 = day
        e.onSignal(VehicleSignal.Wiper(on = true), t2 - 1_000)
        val out = board(e, t2)
        assertTrue(out.contains(CareCommand.Say("김이 서리기 전에 습기를 미리 뺄게요.")))
        assertTrue(out.contains(CareCommand.SetAc(true)))
        assertTrue(out.contains(CareCommand.SetFanDirection(FanDirection.FLOOR)))
        assertEquals(1, e.report().counts[CareKind.DRY_AIR])
    }

    @Test
    fun `차가 이미 앞유리로 김을 막고 있으면 그 바람은 그대로 둔다(시야가 먼저)`() {
        val e = engine()
        val t = 1_000L
        board(e, t)
        repeat(2) { i ->
            val at = t + 30_000 + i * 30_000L
            e.onSignal(VehicleSignal.FanDirectionChanged(FanDirection.DEFROST), at)
            e.onSignal(VehicleSignal.FanDirectionChanged(FanDirection.FACE), at + 3_000)
        }
        e.tick(t + 100_000)
        e.approve(t + 105_000)
        alight(e, t + 600_000)

        val t2 = day
        e.onSignal(VehicleSignal.Wiper(on = true), t2 - 2_000)
        e.onSignal(VehicleSignal.FanDirectionChanged(FanDirection.DEFROST_AND_FLOOR), t2 - 1_000)
        val out = board(e, t2)
        assertTrue(out.contains(CareCommand.SetAc(true)))
        assertFalse(out.any { it is CareCommand.SetFanDirection })
        assertTrue(e.messages().any { it.contains("차가 김을 막는 중이라 그대로") })
    }

    @Test
    fun `차가 터널 앞에서 한 내기·창 닫기는 사람 습관으로 배우지 않는다`() {
        val e = engine()
        val t = 1_000L
        board(e, t)
        e.onSignal(VehicleSignal.Gear(parked = false), t + 5_000)
        e.onSignal(VehicleSignal.DriverWindow(4), t + 10_000) // 사람이 창을 엶
        e.tick(t + 40_000)
        e.onSignal(VehicleSignal.Recirculation(true), t + 60_000) // 차: 내기
        e.onSignal(VehicleSignal.DriverWindow(0), t + 61_000) // 차: 창 닫음
        assertTrue(e.carGuard.active)
        e.tick(t + 300_000)
        assertFalse(e.messages().any { it.startsWith("조작 묶음 기억") && it.contains("내기순환") })
        assertFalse(e.messages().any { it.startsWith("조작 묶음 기억") && it.contains("운전석 창 닫기") })
    }

    @Test
    fun `내린 뒤 시동이 꺼지며 바뀐 것은 배우지 않는다`() {
        val e = engine()
        dayWithCombo(e, 0)
        e.tick(day - 1_000)
        // 조합 한 번만 기억 — 내린 뒤 원래대로 돌아간 건 묶음이 아니다
        assertEquals(1, e.messages().count { it.startsWith("조작 묶음 기억") })
    }

    @Test
    fun `저장했다가 다시 읽어도 배운 걸 기억한다`() {
        val first = engine()
        repeat(3) { dayWithCombo(first, it) }
        val saved = first.habits.export()

        val second = engine()
        second.habits.import(saved)
        assertEquals(saved, first.habits.view(0).habits, second.habits.view(0).habits)
        val t = 3 * day
        board(second, t)
        // 새로 켠 앱이라 R0가 먼저 1cm 습관을 여쭙는다 — 한 번에 하나씩, 대답한 뒤에 R8이 여쭙는다
        assertEquals(CareAction.WINDOW_CRACK, second.proposal?.offer?.action)
        second.reject(t + 4_000)
        second.tick(t + 4_500)
        assertTrue(second.askedHabit())
    }

    @Test
    fun `지우면 잊는다`() {
        val e = engine()
        repeat(3) { dayWithCombo(e, it) }
        val id = e.habits.view(0).habits.single().id
        e.habits.forgetHabit(id)
        board(e, 3 * day)
        assertFalse(e.askedHabit())
        assertNull(e.proposal)
    }

    @Test
    fun `탈 때 쉬엄쉬엄 해도(중간에 25초 멈춤) 탈 때 세팅 하나로 배운다`() {
        val e = engine()
        repeat(3) { d ->
            val t = d * day
            board(e, t)
            e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), t + 10_000)
            e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), t + 12_000)
            e.tick(t + 30_000)
            e.onSignal(VehicleSignal.DriverWindow(1), t + 37_000) // 25초 멈칫
            e.onSignal(VehicleSignal.Comfort(Control.AMBIENT_COLOR, orange), t + 39_000)
            e.tick(t + 300_000)
            alight(e, t + 600_000)
        }
        assertEquals(1, e.habits.view(0).habits.size)
        board(e, 3 * day)
        assertEquals("설정온도 27℃ · 핸들 열선 켜기 · 운전석 창 살짝(1cm) · 무드등 주황", e.proposal?.offer?.detail)
    }

    @Test
    fun `맞춰 드린 뒤 더하신 조작도 같은 습관으로 익혀, 습관이 조각나지 않는다`() {
        val e = engine()
        repeat(3) { dayWithCombo(e, it) }
        // 이제 맞춰 드리면, 사람은 늘 시트 열선 2단을 더 켠다
        repeat(3) { i ->
            val t = (3 + i) * day
            board(e, t)
            e.approve(t + 4_000)
            e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 2), t + 15_000)
            alight(e, t + 600_000)
        }
        val habits = e.habits.view(0).habits
        assertEquals("새 습관을 만들지 않는다", 1, habits.size)
        assertTrue(habits.single().items.contains("운전석 열선 2단"))
        board(e, 6 * day)
        assertTrue(e.proposal?.offer?.detail!!.contains("운전석 열선 2단"))
    }

    @Test
    fun `추운 저녁과 쌀쌀한 저녁에 같은 조작이면 한 습관으로 넓히되, 더운 날까지 번지지 않는다`() {
        val e = engine()
        hour = 19
        listOf(-2f, 8f, -1f).forEachIndexed { d, outside ->
            e.onSignal(VehicleSignal.OutsideTemperature(outside), d * day)
            board(e, d * day + 1_000)
            e.onSignal(VehicleSignal.Comfort(Control.SEAT_HEAT, 1), d * day + 10_000)
            e.onSignal(VehicleSignal.Comfort(Control.AMBIENT_COLOR, AmbientColor.PURPLE.argb), d * day + 12_000)
            alight(e, d * day + 600_000)
        }
        val habit = e.habits.view(0).habits.single()
        assertEquals("추운·쌀쌀한 저녁 · 혼자 · 탈 때", habit.title)

        e.onSignal(VehicleSignal.OutsideTemperature(9f), 3 * day)
        board(e, 3 * day + 1_000)
        assertEquals("쌀쌀한 저녁엔 늘 하시던 대로 맞춰 드릴까요?", e.proposal?.offer?.ask)
        e.reject(3 * day + 5_000)
        alight(e, 3 * day + 600_000)

        e.onSignal(VehicleSignal.OutsideTemperature(30f), 4 * day)
        board(e, 4 * day + 1_000)
        assertFalse("더운 저녁엔 권하지 않는다", e.askedHabit())
    }

    @Test
    fun `설정온도처럼 남아 있는 값은 이미 그대로면 '그대로 두셨다'로 세어 습관에서 빠지지 않는다`() {
        val e = engine()
        // 첫날만 27℃로 올리고, 그 뒤로는 27℃가 남아 있어 손댈 필요가 없다(내릴 때 온도는 그대로)
        repeat(4) { d ->
            val t = d * day
            board(e, t)
            if (d == 0) e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), t + 8_000)
            e.onSignal(VehicleSignal.Comfort(Control.WHEEL_HEAT, 1), t + 10_000)
            alight(e, t + 600_000)
            e.onSignal(VehicleSignal.Comfort(Control.TEMP, 270), t + 601_300) // 시동 끔에도 온도는 남음
        }
        assertTrue(e.habits.view(0).habits.single().items.contains("설정온도 27℃"))
        // 누가 22℃로 바꿔 놓은 날 → 온도까지 맞춰 드릴지 여쭙는다
        val t = 4 * day
        e.onSignal(VehicleSignal.Comfort(Control.TEMP, 220), t - 1_000)
        board(e, t)
        assertEquals("설정온도 27℃ · 핸들 열선 켜기", e.proposal?.offer?.detail)
    }

    @Test
    fun `주말 나들이는 주말끼리 견주어, 평소와 다른 날로 오해하지 않는다`() {
        var weekend = false
        val e = CareEngine(CareConfig(), startMs = 0L, hourOf = { hour }, isWeekend = { weekend })
        e.onSignal(VehicleSignal.OutsideTemperature(-2f), 0)
        repeat(6) { d ->
            board(e, d * day)
            alight(e, d * day + 600_000)
        }
        weekend = true
        hour = 11
        board(e, 6 * day)
        assertFalse(e.messages().any { it.startsWith("평소와 다른 시간") })
    }

    @Test
    fun `오토 디포그가 끝나 차가 바람을 원래대로 돌려도 습관으로 배우지 않는다`() {
        val e = engine()
        val t = 1_000L
        board(e, t)
        e.onSignal(VehicleSignal.Gear(parked = false), t + 5_000)
        repeat(3) { i ->
            val at = t + 300_000 + i * 300_000L
            e.onSignal(VehicleSignal.FanDirectionChanged(FanDirection.DEFROST_AND_FLOOR), at) // 차: 김 막기
            e.onSignal(VehicleSignal.FanDirectionChanged(FanDirection.FACE), at + 120_000) // 차: 2분 뒤 원래대로
        }
        e.tick(t + 1_500_000)
        assertTrue(e.habits.view(t).habits.isEmpty())
        assertTrue("2분 뒤 돌아온 건 '불편해하시는 것'도 아니다", e.habits.view(t).aversions.isEmpty())
    }

    @Test
    fun `말은 받침에 맞게`() {
        assertEquals("바람 발밑으로", Control.FAN_DIR.describe(FanDirection.FLOOR.ordinal))
        assertEquals("바람 얼굴로", Control.FAN_DIR.describe(FanDirection.FACE.ordinal))
        assertEquals("바람 앞유리로", Control.FAN_DIR.describe(FanDirection.DEFROST.ordinal))
        assertEquals("설정온도 26.5℃", Control.TEMP.describe(265))
        assertEquals("운전석 냉방 2단", Control.SEAT_HEAT.describe(-2))
        assertEquals("비 오는 추운 아침", Situation(TimeBand.MORNING, TempBand.COLD, rain = true, company = false).phrase)
    }
}
