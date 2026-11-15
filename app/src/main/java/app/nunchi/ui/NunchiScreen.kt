package app.nunchi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nunchi.Connection
import app.nunchi.NunchiViewModel
import app.nunchi.ProposalUi
import app.nunchi.UiState
import app.nunchi.core.AmbientColor
import app.nunchi.core.CabinState
import app.nunchi.core.CareAction
import app.nunchi.core.CareEvent
import app.nunchi.core.Control
import app.nunchi.core.DailyReport
import app.nunchi.core.DoorId
import app.nunchi.core.HabitView
import app.nunchi.core.LearnerView
import app.nunchi.core.RangeLevel
import app.nunchi.core.Pane
import app.nunchi.core.Routine
import app.nunchi.core.SeatId
import app.nunchi.core.WindowId
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToInt

private const val HISTORY_MAX = 240
private const val FLOW_FRAME_MS = 125L
private const val CONFIRM_MS = 3_000L
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/** 이 폭(dp)에 맞춰 설계했다. 차량 화면이 더 넓으면 전체를 비례 확대한다. */
private const val DESIGN_WIDTH_DP = 1180f

/** 데모 시뮬레이터 서랍의 폭(dp) — 화면 폭 [SIM_BASE_SCREEN_DP]dp 기준, 더 넓은 화면에선 비례해 커진다. */
private const val SIM_WIDTH_DP = 292f
private const val SIM_BASE_SCREEN_DP = 1280f

@Composable
fun NunchiScreen(vm: NunchiViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // 시뮬레이터는 본 화면 옆의 서랍 — 누른 것(원인)과 NUNCHI의 반응(결과)이 한 화면에 함께 보이게
    var simOpen by rememberSaveable { mutableStateOf(true) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.bg)) {
        val base = LocalDensity.current
        val screenDp = maxWidth.value
        if (ui.drivingRestricted) {
            // 운전 중엔 시뮬레이터도 메뉴도 없다 — 한눈에 읽히는 화면만
            val scale = (screenDp / DESIGN_WIDTH_DP).coerceIn(1f, 2.2f)
            CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                DrivingScreen(ui, onApprove = { vm.approve() }, onReject = vm::reject, onStopVent = vm::stopVentilation)
            }
        } else {
            val simScale = (screenDp / SIM_BASE_SCREEN_DP).coerceIn(0.85f, 2.2f)
            Row(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    // 서랍이 열려 있으면 본 화면은 남은 폭에 맞춰 통째로 조금 작아진다(배치는 그대로)
                    val scale = (maxWidth.value / DESIGN_WIDTH_DP).coerceIn(0.7f, 2.2f)
                    CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
                        NunchiContent(ui, vm, simOpen = simOpen, onOpenSim = { simOpen = true })
                    }
                }
                if (simOpen) {
                    Box(Modifier.width((SIM_WIDTH_DP * simScale).dp).fillMaxHeight()) {
                        CompositionLocalProvider(LocalDensity provides Density(base.density * simScale, base.fontScale)) {
                            SimulatorDrawer(ui, vm, onClose = { simOpen = false })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NunchiContent(ui: UiState, vm: NunchiViewModel, simOpen: Boolean, onOpenSim: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Header(
                ui, simOpen = simOpen, onOpenSim = onOpenSim,
                onDiagnostics = vm::toggleDiagnostics, onVoiceTest = vm::testVoice, onVoiceAnswer = vm::setVoiceAnswer,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val proposal = ui.proposal
                    if (proposal != null) {
                        ProposalBar(
                            proposal,
                            onApprove = { vm.approve() },
                            onAlways = { vm.approve(always = true) },
                            onReject = vm::reject,
                        )
                    } else {
                        SayBanner(ui.lastSay)
                    }
                    Row(Modifier.fillMaxWidth().height(290.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BoardingCard(ui, Modifier.weight(1f).fillMaxHeight())
                        AirCard(ui, Modifier.weight(1.3f).fillMaxHeight())
                        RangeCard(ui, Modifier.weight(1f).fillMaxHeight())
                    }
                    LogCard(ui.events, simOpen, Modifier.fillMaxWidth().weight(1f))
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LearnerCard(
                        ui.learner,
                        asking = ui.proposal?.routine == Routine.R8,
                        onForget = vm::forgetHabit,
                        onUntrust = vm::untrustHabit,
                        onForgetAversion = vm::forgetAversion,
                    )
                    VentCard(ui, onStop = vm::stopVentilation)
                    TrustCard(ui, onRevoke = vm::revokeTrust)
                    TodayCard(ui.report, onOpen = vm::showReport)
                }
            }
        }
        if (ui.showReport) ReportOverlay(ui.report, onDismiss = vm::dismissReport)
        if (ui.showDiagnostics) DiagnosticsOverlay(ui.diagnostics, onDismiss = vm::toggleDiagnostics)
    }
}

// ---------- 운전 중 화면 ----------

/**
 * 주행 중 UX 제한이 걸리면 보여 주는 화면: 스크롤 없이 한눈에 읽히는 정보만.
 * 버튼은 NUNCHI가 여쭐 때(네/아니요)와 흐름 환기 중(끄기)에만 나타난다 — 찾아서 누를 메뉴는 없다.
 * 눈치는 이 동안에도 서비스에서 계속 판단하고, 필요한 말은 음성으로 한다.
 */
@Composable
private fun DrivingScreen(ui: UiState, onApprove: () -> Unit, onReject: () -> Unit, onStopVent: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("운전 중 · NUNCHI가 조용히 살피는 중", color = Palette.sub, fontSize = 22.sp)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%,d".format(ui.co2Ppm), color = co2Color(ui.co2Ppm), fontSize = 96.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text("ppm", color = Palette.sub, fontSize = 28.sp, modifier = Modifier.padding(bottom = 22.dp))
        }
        Text(ui.r1Status, color = Palette.text, fontSize = 26.sp)
        Spacer(Modifier.height(36.dp))
        val proposal = ui.proposal
        val say = ui.lastSay
        if (proposal != null) {
            Text(
                "“${proposal.ask}”",
                color = Palette.orange,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            proposal.detail?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Palette.sub, fontSize = 20.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // '괜찮아요'는 '좋다'로도 '됐다'로도 읽혀서 쓰지 않는다
                BigButton("네", accent = Palette.teal, onClick = onApprove, modifier = Modifier.width(280.dp).height(88.dp), fontSize = 32.sp)
                BigButton("아니요", accent = Palette.cardHi, onClick = onReject, modifier = Modifier.width(220.dp).height(88.dp), fontSize = 28.sp)
            }
            Spacer(Modifier.height(28.dp))
        } else if (say != null) {
            Text(
                "“$say”",
                color = Palette.orange,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(28.dp))
        }
        val range = ui.cabin.rangeKm?.roundToInt()
        if (range != null) {
            Text("주행가능 ${range}km · ${ui.rangeLevel.label}", color = Palette.sub, fontSize = 22.sp)
        }
        if (ui.venting) {
            Spacer(Modifier.height(32.dp))
            Text(ui.ventStatus, color = Palette.teal, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            BigButton("흐름 환기 끄기", accent = Palette.orange, onClick = onStopVent, modifier = Modifier.width(400.dp).height(80.dp), fontSize = 26.sp)
        }
    }
}

// ---------- 머리말 ----------

@Composable
private fun Header(
    ui: UiState,
    simOpen: Boolean,
    onOpenSim: () -> Unit,
    onDiagnostics: () -> Unit,
    onVoiceTest: () -> Unit,
    onVoiceAnswer: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("NUNCHI", color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("말하기 전에 챙기는 차량 수행비서", color = Palette.sub, fontSize = 14.sp)
        }
        ConnectionChip(ui.connection)
        Spacer(Modifier.width(10.dp))
        Pill("진단 ${ui.diagnostics.size}", onClick = onDiagnostics)
        Spacer(Modifier.width(10.dp))
        Pill("음성 테스트 · ${ui.voiceEngine}", onClick = onVoiceTest)
        Spacer(Modifier.width(10.dp))
        // 실험: 제안에 말로 '네/아니요' (Pleos 음성 인식)
        Pill("말로 대답 · ${ui.voiceAnswerStatus}", selected = ui.voiceAnswer) { onVoiceAnswer(!ui.voiceAnswer) }
        if (!simOpen) {
            Spacer(Modifier.width(10.dp))
            Pill("시뮬레이터 열기", selected = true, accent = Palette.purple, onClick = onOpenSim)
        }
    }
    val line = when {
        ui.connection == Connection.VEHICLE && ui.receivedSignals.isNotEmpty() ->
            "실차 신호 ${ui.receivedSignals.size}종 수신 중: " + ui.receivedSignals.joinToString(" · ")
        ui.connection == Connection.VEHICLE -> "차량 신호를 기다리는 중…"
        ui.connection == Connection.DEMO && simOpen -> "데모 모드 — 오른쪽 시뮬레이터로 상황을 만들어 보세요"
        ui.connection == Connection.DEMO -> "데모 모드 — ‘시뮬레이터 열기’를 눌러 상황을 만들어 보세요"
        else -> "차량 서비스에 연결하는 중…"
    }
    Text(line, color = Palette.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun DiagnosticsOverlay(lines: List<String>, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 820.dp).fillMaxHeight(0.82f).clip(RoundedCornerShape(20.dp)).background(Palette.card)
                .border(1.dp, Palette.line, RoundedCornerShape(20.dp))
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("진단", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Pill("닫기", selected = true, onClick = onDismiss)
            }
            Text("차량 서비스 연결·구독 결과와 오류 (최신순)", color = Palette.sub, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(lines) { line ->
                    Text(line, color = Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun ConnectionChip(connection: Connection) {
    val color = when (connection) {
        Connection.VEHICLE -> Palette.green
        Connection.DEMO -> Palette.amber
        Connection.CONNECTING -> Palette.sub
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(connection.label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SayBanner(text: String?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.cardHi)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("NUNCHI", color = Palette.orange, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(14.dp))
        Text(
            text ?: "필요할 때만 한마디 합니다.",
            color = if (text == null) Palette.sub else Palette.text,
            fontSize = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * NUNCHI가 여쭙는 중 — 네 / 아니요 / 다음부턴 알아서. 1분 안에 답이 없으면 조용히 거둔다.
 * '괜찮아요'는 '좋다'로도 '됐다'로도 읽혀서 쓰지 않는다.
 */
@Composable
private fun ProposalBar(p: ProposalUi, onApprove: () -> Unit, onAlways: () -> Unit, onReject: () -> Unit) {
    val accent = routineColor(p.routine)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.cardHi)
            .border(1.5.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CodeTag(p.routine)
            Spacer(Modifier.width(10.dp))
            Text(
                if (p.voice) "NUNCHI가 여쭤봐요" else "NUNCHI 제안 (화면만)",
                color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            Text(p.reason, color = Palette.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(p.ask, color = Palette.text, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        p.detail?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Palette.sub, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            BigButton("네, 해 주세요", accent = accent, onClick = onApprove, modifier = Modifier.width(160.dp).height(46.dp))
            BigButton("아니요", accent = Palette.line, onClick = onReject, modifier = Modifier.width(110.dp).height(46.dp))
            if (p.canTrust) {
                Pill("다음부턴 묻지 말고 알아서", onClick = onAlways)
            }
        }
    }
}

// ---------- 루틴 카드 ----------

@Composable
private fun BoardingCard(ui: UiState, modifier: Modifier) {
    Panel(modifier) {
        RoutineTitle(Routine.R0, "1cm의 배려")
        Spacer(Modifier.height(4.dp))
        // 차 그림은 앞이 위 — 왼쪽 위가 운전석
        CarTopView(ui.cabin, Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(6.dp))
        val window = ui.cabin.driverWindow ?: 0
        Text(
            when {
                window <= 0 -> "운전석 창: 닫힘"
                window == 1 -> "운전석 창: 살짝 열림"
                else -> "운전석 창: 열림 (${window}단계)"
            },
            color = if (window > 0) Palette.orange else Palette.sub,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(ui.r0Status, color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AirCard(ui: UiState, modifier: Modifier) {
    Panel(modifier) {
        RoutineTitle(Routine.R1, "숨 쉬는 차 · 가상 CO2")
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%,d".format(ui.co2Ppm), color = co2Color(ui.co2Ppm), fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("ppm (추정)", color = Palette.sub, fontSize = 14.sp, modifier = Modifier.padding(bottom = 9.dp))
        }
        Text(ui.r1Status, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Co2Sparkline(ui.co2History, Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(6.dp))
        Text(
            "${if (ui.cabin.recirculating) "내기순환" else "외기"} · 탑승 ${ui.cabin.occupants}명 · 공기 교체 ${ui.freshAirCount}회",
            color = Palette.sub,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun RangeCard(ui: UiState, modifier: Modifier) {
    Panel(modifier) {
        RoutineTitle(Routine.R2, "마지막 20km")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                ui.cabin.rangeKm?.roundToInt()?.toString() ?: "—",
                color = Palette.text,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Text("km 주행가능", color = Palette.sub, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(
            ui.cabin.routeKm?.let { "목적지까지 ${it.roundToInt()}km" } ?: "목적지 없음 — 20km 기준",
            color = Palette.sub,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(10.dp))
        LevelChip(ui.rangeLevel, ui.rangeMarginPct, ui.cabin.rangeKm, ui.cabin.routeKm)
        Spacer(Modifier.weight(1f))
        if (ui.cabin.tireLow) Text("타이어 공기압 낮음", color = Palette.amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LevelChip(level: RangeLevel, marginPct: Int?, rangeKm: Float?, routeKm: Float?) {
    val color = when (level) {
        RangeLevel.OK -> Palette.green
        RangeLevel.TIGHT -> Palette.amber
        RangeLevel.SHORT -> Palette.red
        RangeLevel.UNKNOWN -> Palette.sub
    }
    // 음수 여유율(-100%) 대신 '몇 km 모자란지'로 보여 준다
    val text = when {
        level == RangeLevel.UNKNOWN || marginPct == null -> level.label
        marginPct < 0 && rangeKm != null && routeKm != null -> "${level.label} · ${(routeKm - rangeKm).roundToInt()}km 모자람"
        else -> "${level.label} · 여유 $marginPct%"
    }
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * R6 흐름 환기 — 켜는 버튼은 없다. 연기·냄새·흡연 신호를 보면 NUNCHI가 먼저 "바람길 열어 드릴까요?"라고 여쭙는다.
 * 켜진 뒤엔 언제든 끌 수 있다(바꾼 것만 원래대로).
 */
@Composable
private fun VentCard(ui: UiState, onStop: () -> Unit) {
    val asking = ui.proposal?.action == CareAction.VENTILATION
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoutineTitle(Routine.R6, "흐름 환기")
            Spacer(Modifier.weight(1f))
            val (label, color) = when {
                ui.ventPaused -> "터널 — 잠시 멈춤" to Palette.amber
                ui.venting -> "작동 중" to Palette.teal
                asking -> "여쭤보는 중" to Palette.orange
                CareAction.VENTILATION in ui.quiet -> "오늘은 조용히" to Palette.sub
                // 조금 전에 여쭸는데 사양·무응답 — 재촉하지 않고 쉬는 중임을 알린다(눌러도 반응이 없는 까닭)
                ui.ventRestMin > 0 -> "조금 전에 여쭘 · ${ui.ventRestMin}분 쉼" to Palette.sub
                else -> "살피는 중" to Palette.sub
            }
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 그림은 앞이 위 — 실제 출구(피우는 사람 쪽 창)를 그대로 그린다
            VentFlowView(active = ui.venting, outlet = ui.ventOutlet, modifier = Modifier.width(158.dp).height(150.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (ui.venting) {
                    Text(ui.ventStatus, color = Palette.text, fontSize = 14.sp, lineHeight = 19.sp)
                } else {
                    Text("연기·냄새가 느껴지면 먼저 여쭤봐요", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("· 달리다 창을 살짝 열 때 (지금 SDK)", color = Palette.sub, fontSize = 12.sp, lineHeight = 17.sp)
                    Text("· 실내 미세먼지가 확 뛸 때, 흡연 손동작 (센서 장착 시)", color = Palette.sub, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "‘네’ 하시면: 외기 · 발밑 바람 · 피우는 사람 쪽 창틈 · 다른 창 닫음 · 7분 뒤 원래대로",
                    color = Palette.sub, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }
        if (ui.venting) {
            Spacer(Modifier.height(8.dp))
            BigButton("끄기 (바꾼 것만 원래대로)", accent = Palette.orange, onClick = onStop, modifier = Modifier.fillMaxWidth().height(46.dp))
        }
    }
}

/**
 * R8 배우는 눈치 — '제가 아는 당신'. 직접 하신 조작에서 익힌 것을 쉬운 말로 보여 주고, 지울 수 있게 한다.
 * (말없이 학습하는 자동화의 한계 — 무엇을 배웠는지 알 수 없고 고치기도 어렵다 — 를 화면 한 장으로 푼다)
 */
@Composable
private fun LearnerCard(
    learner: LearnerView,
    asking: Boolean,
    onForget: (String) -> Unit,
    onUntrust: (String) -> Unit,
    onForgetAversion: (String) -> Unit,
) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoutineTitle(Routine.R8, "제가 아는 당신")
            Spacer(Modifier.weight(1f))
            val (label, color) = when {
                asking -> "여쭤보는 중" to Palette.orange
                learner.recording != null -> "지켜보는 중" to Palette.purple
                else -> "배우는 중" to Palette.sub
            }
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "직접 하신 조작에서 배워요. 같은 상황에서 세 번(조작 셋 이상이 같으면 두 번) 반복되면, 다음엔 손대시기 전에 먼저 여쭤볼게요.",
            color = Palette.sub, fontSize = 12.sp, lineHeight = 17.sp,
        )
        learner.today?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = if (it.startsWith("오늘은 평소와 다른")) Palette.amber else Palette.sub, fontSize = 13.sp, lineHeight = 18.sp)
        }
        learner.recording?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Palette.purple, fontSize = 13.sp, lineHeight = 18.sp)
        }
        learner.usual?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Palette.sub, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        if (learner.habits.isEmpty() && learner.aversions.isEmpty()) {
            Text("아직 배운 게 없어요 — 평소처럼 쓰시면 조용히 지켜보며 배워요", color = Palette.sub, fontSize = 14.sp)
        }
        learner.habits.forEach { h -> HabitRow(h, onForget = onForget, onUntrust = onUntrust) }
        if (learner.aversions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("불편해하시는 것", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            learner.aversions.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.text, color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Pill("잊기") { onForgetAversion(a.key) }
                }
            }
        }
        if (learner.dryAir) {
            Spacer(Modifier.height(6.dp))
            Text(
                "비 오는 날 김 서리기 전 습기 미리 빼기 — 켜짐 (시야가 먼저: 차가 김을 막으려 앞유리로 보내는 바람은 그대로 둬요)",
                color = Palette.teal, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun HabitRow(h: HabitView, onForget: (String) -> Unit, onUntrust: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Palette.cardHi)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                h.title, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            val color = when {
                h.muted -> Palette.sub
                h.note != null -> Palette.amber
                h.trusted -> Palette.teal
                h.ready -> Palette.purple
                else -> Palette.sub
            }
            Text(h.status, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        h.note?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, color = Palette.amber, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text(h.items, color = Palette.text, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (h.trusted) Pill("알아서 해제") { onUntrust(h.id) }
            if (h.muted) Pill("다시 권해도 돼요") { onUntrust(h.id) }
            Pill("잊기") { onForget(h.id) }
        }
    }
}

/** "다음부턴 알아서"라고 허락한 일 — 여기서 빼면 다시 먼저 여쭙는다. 허락은 제안 화면에서만 준다. */
@Composable
private fun TrustCard(ui: UiState, onRevoke: (CareAction) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Text("알아서 하는 일", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("기본은 먼저 여쭤보기. '다음부턴 알아서'라고 하신 일만 바로 해요.", color = Palette.sub, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (ui.trusted.isEmpty()) {
            Text("아직 없어요", color = Palette.sub, fontSize = 14.sp)
        } else {
            ui.trusted.forEach { action ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    CodeTag(action.routine)
                    Spacer(Modifier.width(8.dp))
                    Text(action.label, color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Pill("해제") { onRevoke(action) }
                }
            }
        }
    }
}

// ---------- 로그와 오늘의 1cm ----------

@Composable
private fun LogCard(events: List<CareEvent>, simOpen: Boolean, modifier: Modifier) {
    Panel(modifier) {
        Text("케어 기록", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (events.isEmpty()) {
            Text(
                "아직 개입한 일이 없어요. ${if (simOpen) "오른쪽 시뮬레이터" else "‘시뮬레이터 열기’"}나 Pleos Virtual Console로 상황을 만들어 보세요.",
                color = Palette.sub,
                fontSize = 14.sp,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(events) { event -> LogRow(event) }
            }
        }
    }
}

@Composable
private fun LogRow(event: CareEvent) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(timeFormat.format(Instant.ofEpochMilli(event.atMs)), color = Palette.sub, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        CodeTag(event.routine)
        Spacer(Modifier.width(8.dp))
        Text(event.message, color = if (event.kind.counted) Palette.text else Palette.sub, fontSize = 14.sp)
    }
}

@Composable
private fun TodayCard(report: DailyReport, onOpen: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("오늘의 1cm", color = Palette.orange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Pill("카드 보기", accent = Palette.orange, onClick = onOpen)
        }
        Spacer(Modifier.height(4.dp))
        Text("운전 중엔 조용히, 하루 끝에 한 장으로 보고합니다.", color = Palette.sub, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Text(report.summary, color = Palette.text, fontSize = 16.sp)
        report.peakCo2Ppm?.let {
            Spacer(Modifier.height(4.dp))
            Text("오늘 최고 CO2(추정) %,dppm".format(it), color = Palette.sub, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ReportOverlay(report: DailyReport, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(24.dp)).background(Palette.card)
                .border(1.dp, Palette.orange.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .padding(32.dp),
        ) {
            Text("오늘의 1cm", color = Palette.orange, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("오늘 NUNCHI가 조용히 챙긴 것", color = Palette.sub, fontSize = 16.sp)
            Spacer(Modifier.height(20.dp))
            if (report.total == 0) {
                Text(report.summary, color = Palette.text, fontSize = 18.sp)
            } else {
                report.counts.forEach { (kind, count) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(kind.label, color = Palette.text, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        Text("${count}회", color = Palette.orange, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            report.peakCo2Ppm?.let { peak ->
                Spacer(Modifier.height(14.dp))
                val note = if (peak < 2_000) " — 졸음 농도(2,000ppm) 전에 관리" else ""
                Text("최고 CO2(추정) %,dppm".format(peak) + note, color = Palette.sub, fontSize = 15.sp)
            }
            Spacer(Modifier.height(24.dp))
            Text("오늘도 수고하셨어요.", color = Palette.text, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            Pill("닫기", selected = true, accent = Palette.orange, onClick = onDismiss)
        }
    }
}

// ---------- 데모 시뮬레이터(서랍) ----------

/** 시뮬레이터 탭 = 해 볼 수 있는 장면. 왼쪽 카드와 같은 이름·색을 써서 어느 카드가 반응하는지 바로 알 수 있게 한다. */
private enum class SimTab(val code: String, val title: String) {
    R0("R0", "1cm의 배려"),
    R1("R1", "숨 쉬는 차"),
    R2("R2", "마지막 20km"),
    R6("R6", "흐름 환기"),
    R8("R8", "배우는 눈치"),
    SET("설정", "데모 설정");

    val color: Color
        get() = when (this) {
            R0 -> Palette.orange
            R1 -> Palette.blue
            R2 -> Palette.green
            R6 -> Palette.teal
            R8 -> Palette.purple
            SET -> Palette.sub
        }
}

/**
 * 데모 시뮬레이터 — Virtual Console 없이도 차에서 일어나는 상황을 만들어 본다.
 * 본 화면 옆에 붙는 서랍이라 누른 것과 NUNCHI의 반응이 함께 보이고, 장면(루틴)마다 탭 하나에 '이렇게 해 보세요'와
 * 그 장면에 쓰는 버튼만 모았다. 버튼은 줄이 넘치면 다음 줄로 내려가므로 잘리지 않는다.
 */
@Composable
private fun SimulatorDrawer(ui: UiState, vm: NunchiViewModel, onClose: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(SimTab.R0) }
    Row(Modifier.fillMaxSize().background(Palette.card)) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.line))
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("데모 시뮬레이터", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("차에서 일어나는 상황을 여기서 만들어요", color = Palette.sub, fontSize = 11.sp)
                }
                SimPill("닫기", onClick = onClose)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SimTab.entries.forEach { t -> TabChip(t, selected = t == tab, modifier = Modifier.weight(1f)) { tab = t } }
            }
            Spacer(Modifier.height(10.dp))
            // 탭마다 맨 위에서 시작
            key(tab) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        if (tab == SimTab.SET) tab.title else "${tab.code} ${tab.title}",
                        color = if (tab == SimTab.SET) Palette.text else tab.color, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    )
                    when (tab) {
                        SimTab.R0 -> SimBoarding(ui, vm)
                        SimTab.R1 -> SimAir(ui, vm)
                        SimTab.R2 -> SimRange(ui, vm)
                        SimTab.R6 -> SimVent(ui, vm)
                        SimTab.R8 -> SimHabit(ui, vm)
                        SimTab.SET -> SimSettings(ui, vm)
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun SimBoarding(ui: UiState, vm: NunchiViewModel) {
    SimHint(
        Palette.orange,
        "기어 P에서 문을 하나 열었다 닫아 보세요. 다 타면 ‘창을 1cm 내려 둘까요?’라고 한 번 여쭙고, " +
            "‘네’ 하신 뒤부터는 문이 열릴 때마다 말없이 운전석 창을 1cm 내렸다가 올려요.",
    )
    GearGroup(ui, vm)
    SimBlock("문", "차 모양 그대로 — 누르면 열리고, 한 번 더 누르면 닫혀요") {
        @Composable
        fun door(door: DoorId, modifier: Modifier) =
            SimPill(doorShort(door), selected = door in ui.cabin.openDoors, accent = Palette.orange, modifier = modifier) { vm.toggleDoor(door) }
        CarPad(
            frontLeft = { door(DoorId.FRONT_LEFT, it) },
            frontRight = { door(DoorId.FRONT_RIGHT, it) },
            rearLeft = { door(DoorId.REAR_LEFT, it) },
            rearRight = { door(DoorId.REAR_RIGHT, it) },
        )
    }
    WeatherGroup(ui, vm, "비가 오면 창은 그대로 둬요")
    SimGroup("실내 온도", "28℃ 이상이면 다 탄 뒤 ‘30초만 열기를 뺄까요?’") {
        val hot = (ui.cabin.cabinTempC ?: 0f) >= 28f
        SimPill("22℃", selected = ui.cabin.cabinTempC != null && !hot) { vm.setCabinTemp(22f) }
        SimPill("33℃", selected = hot, accent = Palette.orange) { vm.setCabinTemp(33f) }
    }
    SimGroup("차종", "프레임리스 도어는 문이 스스로 압력을 빼서 창을 건드리지 않아요") {
        SimPill("일반 도어", selected = !ui.frameless) { vm.setFrameless(false) }
        SimPill("프레임리스", selected = ui.frameless) { vm.setFrameless(true) }
    }
}

@Composable
private fun SimAir(ui: UiState, vm: NunchiViewModel) {
    SimHint(
        Palette.blue,
        "탑승 2명 · 내기순환 · D 주행으로 두고 빨리 감기 ×30을 누르세요. 추정 CO2가 1,900ppm에 닿으면 " +
            "‘공기를 한 번 바꿀까요?’라고 여쭤봐요. 3명 이상이면 달린 지 2분 뒤 ‘외기로 둘까요?’",
    )
    SimGroup("탑승 인원") {
        (1..4).forEach { n -> SimPill("${n}명", selected = ui.cabin.occupants == n) { vm.setOccupants(n) } }
    }
    SimGroup("공기") {
        SimPill("내기순환", selected = ui.cabin.recirculating) { vm.setRecirculation(true) }
        SimPill("외기", selected = !ui.cabin.recirculating) { vm.setRecirculation(false) }
    }
    GearGroup(ui, vm)
    SpeedGroup(ui, vm)
}

@Composable
private fun SimRange(ui: UiState, vm: NunchiViewModel) {
    val cabin = ui.cabin
    SimHint(
        Palette.green,
        "목적지를 100km로 두고 주행가능거리를 줄여 보세요. 여유가 20% 아래면 ‘에어컨을 잠시 쉴까요?’, " +
            "모자라면 충전 계획을 알려요. 목적지가 없으면 20km·10km가 기준이에요.",
    )
    SimGroup("목적지까지") {
        SimPill("없음", selected = cabin.routeKm == null) { vm.setRoute(null) }
        SimPill("100km", selected = cabin.routeKm == 100f) { vm.setRoute(100f) }
    }
    SimGroup("주행가능거리", "지금 ${cabin.rangeKm?.let { "${it.roundToInt()}km" } ?: "모름(누르면 120km에서 시작)"}") {
        SimPill("−10km") { vm.changeRange(-10f) }
        SimPill("+10km") { vm.changeRange(10f) }
    }
    SimGroup("타이어") {
        SimPill("공기압 낮음", selected = cabin.tireLow, accent = Palette.amber) { vm.setTireLow(!cabin.tireLow) }
    }
}

@Composable
private fun SimVent(ui: UiState, vm: NunchiViewModel) {
    val cabin = ui.cabin
    SimHint(
        Palette.teal,
        "D 주행 중에 ‘흡연 손동작’이나 ‘미세먼지 급상승’을 누르면 ‘바람길 열어 드릴까요?’라고 여쭤봐요. " +
            "‘네’ 하면 발밑 바람이 피우는 사람 쪽 창틈으로 빠지고, R6 카드의 ‘끄기’를 누르면 바꾼 것만 원래대로.",
    )
    if (ui.ventRestMin > 0 && !ui.venting && ui.proposal?.action != CareAction.VENTILATION) {
        Text(
            "조금 전에 여쭤봐서 ${ui.ventRestMin}분 동안은 다시 권하지 않아요(재촉하지 않기). 바로 다시 보려면 ‘설정 → 오늘 기록 초기화’",
            color = Palette.amber, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp),
        )
    }
    GearGroup(ui, vm)
    SimGroup("가상 센서", "실내 카메라·공기질 센서는 지금 SDK에 없어 가상으로 넣어요") {
        SimPill("운전석 흡연 손동작") { vm.simulateGesture(SeatId.DRIVER) }
        SimPill("동승석 흡연 손동작") { vm.simulateGesture(SeatId.PASSENGER) }
        SimPill("미세먼지 급상승", accent = Palette.orange, selected = (cabin.pm25 ?: 0) >= 76) { vm.simulateSmokeSpike() }
        SimPill("공기 맑음") { vm.simulateCleanAir() }
    }
    WindowGroup(ui, vm, "달리다 운전석 창을 1cm 열면, 말없이 화면에만 권해요")
    WeatherGroup(ui, vm, "비가 오면 창틈을 가장 작게, 심한 폭우엔 창을 닫아요")
}

@Composable
private fun SimHabit(ui: UiState, vm: NunchiViewModel) {
    val cabin = ui.cabin
    SimHint(
        Palette.purple,
        "시각 ‘아침 8시’, 바깥 ‘영하 2℃’로 두고 타기 → 독특한 조합 → 내리기를 두 번 하세요. " +
            "세 번째로 타면 ‘늘 하시던 대로 맞춰 드릴까요?’라고 먼저 여쭤봐요.",
    )
    SimGroup("시각") {
        SimPill("실제", selected = ui.hourOverride == null) { vm.setHour(null) }
        SimPill("새벽 4시", selected = ui.hourOverride == 4, accent = Palette.purple) { vm.setHour(4) }
        SimPill("아침 8시", selected = ui.hourOverride == 8, accent = Palette.purple) { vm.setHour(8) }
        SimPill("저녁 7시", selected = ui.hourOverride == 19, accent = Palette.purple) { vm.setHour(19) }
    }
    SimGroup("바깥 온도") {
        val outside = cabin.outsideTempC
        SimPill("영하 2℃", selected = outside == -2f, accent = Palette.purple) { vm.setOutsideTemp(-2f) }
        SimPill("12℃", selected = outside == 12f, accent = Palette.purple) { vm.setOutsideTemp(12f) }
        SimPill("30℃", selected = outside == 30f, accent = Palette.purple) { vm.setOutsideTemp(30f) }
    }
    // 승차 기록이 있으면(타는 중) '타기'가 켜져 보인다
    val onboard = ui.learner.today != null
    SimGroup("타고 내리기", if (onboard) "지금: 타고 있어요" else "지금: 내려 있어요") {
        SimPill("타기", selected = onboard, accent = Palette.purple) { vm.board() }
        SimPill("내리기 (시동 끔)") { vm.alight() }
    }
    SimGroup("한 번에", "27℃ · 핸들 열선 · 운전석 창 1cm · 무드등 주황을 한꺼번에") {
        SimPill("독특한 조합", accent = Palette.purple) { vm.uniqueCombo() }
    }
    SimGroup("직접 조작", "20초 안에 이어서 바꾼 것이 한 묶음이 돼요") {
        val temp = cabin.comfort[Control.TEMP]
        SimPill("27℃", selected = temp == 270, accent = Palette.purple) { vm.manual(Control.TEMP, 270) }
        // 맞춰 드린 27℃를 25℃로 = '달라진 취향'(22℃로 되돌리면 '원치 않음')
        SimPill("25℃", selected = temp == 250, accent = Palette.purple) { vm.manual(Control.TEMP, 250) }
        SimPill("22℃", selected = temp == 220, accent = Palette.purple) { vm.manual(Control.TEMP, 220) }
        val wheel = (cabin.comfort[Control.WHEEL_HEAT] ?: 0) > 0
        SimPill("핸들 열선", selected = wheel, accent = Palette.purple) { vm.manual(Control.WHEEL_HEAT, if (wheel) 0 else 1) }
        val seat = cabin.comfort[Control.SEAT_HEAT] ?: 0
        SimPill("시트 열선 2단", selected = seat == 2, accent = Palette.purple) { vm.manual(Control.SEAT_HEAT, if (seat == 2) 0 else 2) }
        val window = (cabin.driverWindow ?: 0) > 0
        SimPill("운전석 창 1cm", selected = window, accent = Palette.purple) { vm.manual(Control.DRIVER_WINDOW, if (window) 0 else 1) }
        val color = cabin.comfort[Control.AMBIENT_COLOR]
        SimPill("무드등 주황", selected = color == AmbientColor.ORANGE.argb, accent = Palette.purple) {
            vm.manual(Control.AMBIENT_COLOR, AmbientColor.ORANGE.argb)
        }
        SimPill("무드등 파랑", selected = color == AmbientColor.BLUE.argb, accent = Palette.purple) {
            vm.manual(Control.AMBIENT_COLOR, AmbientColor.BLUE.argb)
        }
        SimPill("무드등 보라", selected = color == AmbientColor.PURPLE.argb, accent = Palette.purple) {
            vm.manual(Control.AMBIENT_COLOR, AmbientColor.PURPLE.argb)
        }
        val ac = cabin.acOn == true
        SimPill("에어컨", selected = ac, accent = Palette.purple) { vm.manual(Control.AC, if (ac) 0 else 1) }
    }
    SimGroup("앞유리 바람", "차가 김을 막으려 바람을 앞유리로 돌렸을 때, 곧바로 되돌리기를 두 번 하면 더 나은 방법을 여쭤봐요") {
        SimPill("차가 앞유리로 돌림", selected = cabin.fanDirection?.defrost == true, accent = Palette.amber) { vm.carGlassAir() }
        SimPill("운전자가 되돌림") { vm.revertGlassAir() }
    }
    SimGroup("배운 것") {
        ConfirmPill("모두 지우기") { vm.clearLearning() }
    }
}

@Composable
private fun SimSettings(ui: UiState, vm: NunchiViewModel) {
    SpeedGroup(ui, vm)
    SimGroup("음성", "NUNCHI가 여쭙는 말을 소리로 낼지") {
        SimPill("켬", selected = ui.voiceOn) { vm.setVoice(true) }
        SimPill("끔", selected = !ui.voiceOn) { vm.setVoice(false) }
    }
    SimGroup("오늘 기록", "케어 기록과 ‘오늘은 먼저 권하지 않음’을 지워요 — 사양한 일도 다시 여쭤봐요") {
        ConfirmPill("초기화") { vm.resetDay() }
    }
    SimGroup("배운 것", "‘제가 아는 당신’ 카드의 습관을 모두 지워요") {
        ConfirmPill("모두 지우기") { vm.clearLearning() }
    }
}

@Composable
private fun GearGroup(ui: UiState, vm: NunchiViewModel) {
    SimGroup("기어") {
        SimPill("P 정차", selected = ui.cabin.parked) { vm.setParked(true) }
        SimPill("D 주행", selected = !ui.cabin.parked) { vm.setParked(false) }
    }
}

@Composable
private fun WeatherGroup(ui: UiState, vm: NunchiViewModel, note: String) {
    val cabin = ui.cabin
    SimGroup("날씨", note) {
        SimPill("맑음", selected = !cabin.wiperOn) { vm.setWeather(0) }
        SimPill("비", selected = cabin.wiperOn && !cabin.heavyRain) { vm.setWeather(1) }
        SimPill("심한 폭우", selected = cabin.heavyRain) { vm.setWeather(2) }
    }
}

/** 창 네 개를 차 모양대로: 운전석은 1cm 틈(직접 조작으로 배우는 그 창), 나머지는 절반쯤. 주황 = 열림. */
@Composable
private fun WindowGroup(ui: UiState, vm: NunchiViewModel, note: String) {
    val cabin = ui.cabin
    SimBlock("창", note) {
        @Composable
        fun window(window: WindowId, modifier: Modifier) {
            val open = (cabin.otherWindows[window] ?: 0) > 0
            SimPill(windowShort(window), selected = open, accent = Palette.orange, modifier = modifier) { vm.toggleOtherWindow(window) }
        }
        val driverOpen = (cabin.driverWindow ?: 0) > 0
        CarPad(
            frontLeft = {
                SimPill("운전석 1cm", selected = driverOpen, accent = Palette.orange, modifier = it) {
                    vm.manual(Control.DRIVER_WINDOW, if (driverOpen) 0 else 1)
                }
            },
            frontRight = { window(WindowId.PASSENGER, it) },
            rearLeft = { window(WindowId.REAR_LEFT, it) },
            rearRight = { window(WindowId.REAR_RIGHT, it) },
        )
    }
}

@Composable
private fun SpeedGroup(ui: UiState, vm: NunchiViewModel) {
    val note = when {
        ui.speedHeld -> "여쭤보는 동안은 실제 속도(×1)로 흘러요 — 대답하시면 다시 ×${ui.demoSpeed}"
        else -> "CO2가 차오르는 시간처럼 기다려야 하는 것을 빨리 돌려요"
    }
    SimGroup("빨리 감기", note) {
        listOf(1, 10, 30).forEach { speed ->
            SimPill("×$speed", selected = ui.demoSpeed == speed) { vm.setDemoSpeed(speed) }
        }
    }
}

@Composable
private fun TabChip(tab: SimTab, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier.testTag("sim-tab-${tab.name}").clip(shape)
            .background(if (selected) tab.color else Palette.cardHi)
            .border(1.dp, if (selected) tab.color else Palette.line, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            tab.code,
            color = if (selected) Color.White else if (tab == SimTab.SET) Palette.text else tab.color,
            fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

@Composable
private fun SimHint(color: Color, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("이렇게 해 보세요", color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(text, color = Palette.text, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** 이름표 한 줄 + 버튼들. 버튼은 폭이 모자라면 다음 줄로 내려간다(오른쪽이 잘리지 않게). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimGroup(label: String, note: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        note?.let { Text(it, color = Palette.sub, fontSize = 11.sp, lineHeight = 15.sp) }
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

/** 이름표 한 줄 + 자리가 정해진 내용(차 모양 버튼판 등). */
@Composable
private fun SimBlock(label: String, note: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        note?.let { Text(it, color = Palette.sub, fontSize = 11.sp, lineHeight = 15.sp) }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun SimPill(
    text: String,
    selected: Boolean = false,
    accent: Color = Palette.blue,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape)
            .background(if (selected) accent else Palette.cardHi)
            .border(1.dp, if (selected) accent else Palette.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Color.White else Palette.text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** 되돌릴 수 없는 버튼(지우기·초기화)은 두 번 눌러야 한다 — 잘못 눌러 배운 것을 날리지 않게. */
@Composable
private fun ConfirmPill(text: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    if (armed) {
        LaunchedEffect(Unit) {
            delay(CONFIRM_MS)
            armed = false
        }
    }
    SimPill(if (armed) "한 번 더 누르면 지워요" else text, selected = armed, accent = Palette.red) {
        if (armed) {
            armed = false
            onConfirm()
        } else {
            armed = true
        }
    }
}

/**
 * 차 모양대로 놓은 버튼 네 개(앞이 위): 왼쪽 위 = 운전석, 오른쪽 위 = 동승석, 아래 두 개 = 뒷좌석.
 * 한 줄로 늘어놓으면 어느 문·어느 창인지 매번 헷갈려서, 가운데 작은 차 그림을 두고 자리 그대로 누르게 했다.
 */
@Composable
private fun CarPad(
    frontLeft: @Composable (Modifier) -> Unit,
    frontRight: @Composable (Modifier) -> Unit,
    rearLeft: @Composable (Modifier) -> Unit,
    rearRight: @Composable (Modifier) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            frontLeft(Modifier.fillMaxWidth())
            rearLeft(Modifier.fillMaxWidth())
        }
        Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("앞", color = Palette.sub, fontSize = 9.sp)
            Canvas(Modifier.width(22.dp).height(52.dp)) {
                val w = size.width
                val h = size.height
                drawRoundRect(
                    Palette.sub.copy(alpha = 0.7f), size = Size(w, h),
                    cornerRadius = CornerRadius(w * 0.32f), style = Stroke(width = 1.5.dp.toPx()),
                )
                drawLine(
                    Palette.blue.copy(alpha = 0.8f), Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.2f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
                // 운전대 자리(왼쪽 앞)
                drawCircle(Palette.sub, radius = w * 0.11f, center = Offset(w * 0.32f, h * 0.40f))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            frontRight(Modifier.fillMaxWidth())
            rearRight(Modifier.fillMaxWidth())
        }
    }
}

private fun windowShort(window: WindowId) = when (window) {
    WindowId.PASSENGER -> "동승석"
    WindowId.REAR_LEFT -> "뒤 왼쪽"
    WindowId.REAR_RIGHT -> "뒤 오른쪽"
}

private fun doorShort(door: DoorId) = when (door) {
    DoorId.FRONT_LEFT -> "운전석"
    DoorId.FRONT_RIGHT -> "동승석"
    DoorId.REAR_LEFT -> "뒤 왼쪽"
    DoorId.REAR_RIGHT -> "뒤 오른쪽"
}

// ---------- 공통 조각 ----------

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Palette.card)
            .border(1.dp, Palette.line, RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun RoutineTitle(routine: Routine, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CodeTag(routine)
        Spacer(Modifier.width(8.dp))
        Text(title, color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CodeTag(routine: Routine) {
    val accent = routineColor(routine)
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(routine.code, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Pill(text: String, selected: Boolean = false, accent: Color = Palette.blue, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .background(if (selected) accent else Palette.cardHi)
            .border(1.dp, if (selected) accent else Palette.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Color.White else Palette.text,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun BigButton(text: String, accent: Color, onClick: () -> Unit, modifier: Modifier, fontSize: TextUnit = 17.sp) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold)
    }
}

private fun routineColor(routine: Routine) = when (routine) {
    Routine.R0 -> Palette.orange
    Routine.R1 -> Palette.blue
    Routine.R2 -> Palette.green
    Routine.R6 -> Palette.teal
    Routine.R8 -> Palette.purple
}

private fun co2Color(ppm: Int) = when {
    ppm < 1_000 -> Palette.green
    ppm < 1_900 -> Palette.amber
    else -> Palette.red
}

// ---------- 그림 ----------

/**
 * 위에서 내려다본 차 — 앞이 위(운전석에 앉아 보는 방향 그대로)라 왼쪽 위가 운전석이다.
 * 주황 = 열린 문(바깥으로 벌어짐)·열린 창(문 옆 점선), 점 = 사람이 앉은 자리.
 */
@Composable
private fun CarTopView(cabin: CabinState, modifier: Modifier) {
    val driverWindow = (cabin.driverWindow ?: 0) > 0
    fun windowOpen(w: WindowId) = (cabin.otherWindows[w] ?: 0) > 0
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val top = h * 0.14f
            val bottom = h * 0.98f
            val bh = bottom - top
            val bw = min(w * 0.38f, bh * 0.52f)
            val left = (w - bw) / 2f
            val right = left + bw
            val radius = CornerRadius(bw * 0.30f)
            drawRoundRect(Palette.cardHi, topLeft = Offset(left, top), size = Size(bw, bh), cornerRadius = radius)
            drawRoundRect(
                Palette.line, topLeft = Offset(left, top), size = Size(bw, bh), cornerRadius = radius,
                style = Stroke(width = 2.dp.toPx()),
            )
            // 앞유리(위, 파랑) · 뒷유리(아래)
            drawLine(
                Palette.blue.copy(alpha = 0.75f),
                Offset(left + bw * 0.18f, top + bh * 0.19f), Offset(right - bw * 0.18f, top + bh * 0.19f),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
            )
            drawLine(
                Palette.sub.copy(alpha = 0.4f),
                Offset(left + bw * 0.24f, top + bh * 0.89f), Offset(right - bw * 0.24f, top + bh * 0.89f),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
            // 자리: 앉았으면 채운 점
            val frontY = top + bh * 0.41f
            val rearY = top + bh * 0.68f
            val lx = left + bw * 0.29f
            val rx = right - bw * 0.29f
            val seatR = bw * 0.115f
            fun seat(id: SeatId, c: Offset) {
                if (id in cabin.occupiedSeats) {
                    drawCircle(Palette.text.copy(alpha = 0.9f), seatR, c)
                } else {
                    drawCircle(Palette.sub.copy(alpha = 0.45f), seatR, c, style = Stroke(width = 1.5.dp.toPx()))
                }
            }
            seat(SeatId.DRIVER, Offset(lx, frontY))
            seat(SeatId.PASSENGER, Offset(rx, frontY))
            seat(SeatId.REAR_LEFT, Offset(lx, rearY))
            seat(SeatId.REAR_RIGHT, Offset(rx, rearY))
            // 운전대(운전석 앞) — 어느 쪽이 운전석인지 한눈에
            drawArc(
                Palette.sub, startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(lx - seatR * 1.25f, frontY - seatR * 2.6f), size = Size(seatR * 2.5f, seatR * 2.0f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
            // 문·창: 좌우 변을 따라. 위쪽이 앞문, 아래쪽이 뒷문
            val y0 = top + bh * 0.27f
            val yMid = top + bh * 0.545f
            val y1 = top + bh * 0.82f
            val inset = bh * 0.025f
            fun side(door: DoorId, window: Boolean, x: Float, from: Float, to: Float, out: Float) {
                if (door in cabin.openDoors) {
                    // 경첩은 앞쪽(위) — 뒤쪽 끝이 바깥으로 벌어진다
                    drawLine(
                        Palette.orange, Offset(x, from + inset), Offset(x + out * bw * 0.30f, to - inset - (to - from) * 0.16f),
                        strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round,
                    )
                } else {
                    drawLine(
                        Palette.sub.copy(alpha = 0.5f), Offset(x, from + inset), Offset(x, to - inset),
                        strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
                if (window) {
                    // 열린 창: 문 바깥쪽에 주황 점선('틈')
                    val gx = x + out * 6.dp.toPx()
                    drawLine(
                        Palette.orange, Offset(gx, from + inset * 2f), Offset(gx, to - inset * 2f),
                        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx())),
                    )
                }
            }
            side(DoorId.FRONT_LEFT, driverWindow, left, y0, yMid, -1f)
            side(DoorId.FRONT_RIGHT, windowOpen(WindowId.PASSENGER), right, y0, yMid, 1f)
            side(DoorId.REAR_LEFT, windowOpen(WindowId.REAR_LEFT), left, yMid, y1, -1f)
            side(DoorId.REAR_RIGHT, windowOpen(WindowId.REAR_RIGHT), right, yMid, y1, 1f)
        }
        // 이름표도 차 모양 그대로 네 귀퉁이에: 왼쪽 위 운전석, 오른쪽 위 동승석, 아래가 뒷좌석(문·창이 열린 자리는 주황)
        Text("앞", color = Palette.sub, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopCenter))
        CarTag("운전석", DoorId.FRONT_LEFT in cabin.openDoors || driverWindow, Modifier.align(BiasAlignment(-1f, -0.42f)))
        CarTag("동승석", DoorId.FRONT_RIGHT in cabin.openDoors || windowOpen(WindowId.PASSENGER), Modifier.align(BiasAlignment(1f, -0.42f)))
        CarTag("뒤 왼쪽", DoorId.REAR_LEFT in cabin.openDoors || windowOpen(WindowId.REAR_LEFT), Modifier.align(BiasAlignment(-1f, 0.82f)))
        CarTag("뒤 오른쪽", DoorId.REAR_RIGHT in cabin.openDoors || windowOpen(WindowId.REAR_RIGHT), Modifier.align(BiasAlignment(1f, 0.82f)))
    }
}

@Composable
private fun CarTag(text: String, on: Boolean, modifier: Modifier) {
    Text(
        text,
        color = if (on) Palette.orange else Palette.sub,
        fontSize = 10.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * 위에서 내려다본 실내(앞이 위): 앞자리 발밑에서 나온 바람이 바닥을 타고 돌아, 피우는 사람 쪽 창틈으로 빠지는 한 줄기 흐름.
 * 켜져 있을 때만 초당 8번 흐름을 움직이고(매 프레임 다시 그리지 않음), 꺼져 있으면 멈춘 그림으로 원리만 보여 준다.
 */
@Composable
private fun VentFlowView(active: Boolean, outlet: Pane, modifier: Modifier) {
    var flow by remember { mutableFloatStateOf(0f) }
    if (active) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(FLOW_FRAME_MS)
                flow = (flow + 3f) % 24f
            }
        }
    }
    val leftSide = outlet == Pane.DRIVER || outlet == Pane.REAR_LEFT
    val frontRow = outlet == Pane.DRIVER || outlet == Pane.PASSENGER
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val top = h * 0.12f
            val bottom = h * 0.98f
            val bh = bottom - top
            val bw = min(w * 0.42f, bh * 0.58f)
            val left = (w - bw) / 2f
            val right = left + bw
            val stroke = 2.dp.toPx()
            // 실내 윤곽 + 앞유리(위)
            drawRoundRect(
                Palette.line, topLeft = Offset(left, top), size = Size(bw, bh),
                cornerRadius = CornerRadius(bw * 0.30f), style = Stroke(width = stroke),
            )
            drawLine(
                Palette.blue.copy(alpha = 0.6f),
                Offset(left + bw * 0.18f, top + bh * 0.15f), Offset(right - bw * 0.18f, top + bh * 0.15f),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
            )
            // 출구 창틈: 그 자리의 옆선을 끊어 '틈'으로
            val dir = if (leftSide) -1f else 1f
            val ex = if (leftSide) left else right
            val ey = top + bh * (if (frontRow) 0.42f else 0.68f)
            val half = bh * 0.075f
            drawLine(Palette.card, Offset(ex, ey - half), Offset(ex, ey + half), strokeWidth = stroke * 3)
            val gapColor = if (active) Palette.teal else Palette.sub.copy(alpha = 0.5f)
            drawLine(gapColor, Offset(ex - 5.dp.toPx(), ey - half), Offset(ex + 5.dp.toPx(), ey - half), strokeWidth = stroke)
            drawLine(gapColor, Offset(ex - 5.dp.toPx(), ey + half), Offset(ex + 5.dp.toPx(), ey + half), strokeWidth = stroke)
            // 앞자리 발밑 바람구멍 → 바닥을 타고 뒤로 갔다가 창틈으로 감아 나가는 흐름 세 줄
            val color = if (active) Palette.teal else Palette.sub.copy(alpha = 0.35f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), if (active) 24f - flow else 0f)
            listOf(0.27f, 0.50f, 0.73f).forEachIndexed { i, fx ->
                // 출구에 가까운 줄이 안쪽으로 돌아야 서로 엇갈리지 않는다
                val rank = if (leftSide) i else 2 - i
                val start = Offset(left + bw * fx, top + bh * 0.26f)
                val turnY = if (frontRow) top + bh * (0.66f + 0.10f * rank) else top + bh * (0.80f + 0.05f * rank)
                drawCircle(color, radius = 3.5.dp.toPx(), center = start)
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    cubicTo(start.x, turnY, ex - dir * bw * (0.10f + 0.06f * rank), turnY, ex - dir * 3.dp.toPx(), ey)
                    lineTo(ex + dir * w * 0.11f, ey - bh * 0.03f)
                }
                drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
            }
        }
        Text("앞", color = Palette.sub, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopCenter))
        // 발밑 바람은 출구 반대쪽에, 창틈 이름은 출구 쪽에
        Text(
            "발밑\n바람", color = Palette.sub, fontSize = 10.sp, lineHeight = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(BiasAlignment(if (leftSide) 1f else -1f, -0.45f)),
        )
        Text(
            "${outlet.label}\n창틈", color = if (active) Palette.teal else Palette.sub, fontSize = 10.sp, lineHeight = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center,
            modifier = Modifier.align(BiasAlignment(if (leftSide) -1f else 1f, if (frontRow) -0.05f else 0.42f)),
        )
    }
}

@Composable
private fun Co2Sparkline(values: List<Float>, modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val minV = 400f
            val maxV = 2_600f
            fun y(v: Float) = size.height * (1f - ((v - minV) / (maxV - minV)).coerceIn(0f, 1f))
            drawLine(
                Palette.red.copy(alpha = 0.7f), Offset(0f, y(2_000f)), Offset(size.width, y(2_000f)),
                strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
            if (values.size < 2) return@Canvas
            val step = size.width / (HISTORY_MAX - 1)
            val startX = size.width - step * (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = startX + step * i
                if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
            }
            drawPath(path, Palette.blue, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Text(
            "2,000ppm 졸음 유발",
            color = Palette.red.copy(alpha = 0.85f),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}
