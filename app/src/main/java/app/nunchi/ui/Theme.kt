package app.nunchi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 기획서 그림과 같은 팔레트(네이비·블루·오렌지) — 차량 화면용 다크 톤. */
object Palette {
    val bg = Color(0xFF0E1620)
    val card = Color(0xFF16212C)
    val cardHi = Color(0xFF1C2A38)
    val line = Color(0xFF2A3A4A)
    val text = Color(0xFFE8EEF5)
    val sub = Color(0xFF93A4B5)
    val blue = Color(0xFF2A78D6)
    val orange = Color(0xFFEB6834)
    val green = Color(0xFF3BB273)
    val amber = Color(0xFFF2B134)
    val red = Color(0xFFE5484D)
    val teal = Color(0xFF2BB3A6)
    /** R8 배우는 눈치. */
    val purple = Color(0xFF9B7BEA)
}

@Composable
fun NunchiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.blue,
            secondary = Palette.orange,
            background = Palette.bg,
            surface = Palette.card,
            onBackground = Palette.text,
            onSurface = Palette.text,
        ),
        content = content,
    )
}
