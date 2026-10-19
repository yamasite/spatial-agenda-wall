package com.bytedance.spatialagendawall.ui.theme

import androidx.compose.ui.graphics.Color

/** App-owned palettes. They intentionally do not replace PicoTheme's native ColorScheme. */
data class AgendaPalette(
    val panelSurface: Color,
    val fieldSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val noteSurface: Color,
    val noteInk: Color,
    val noteHighlight: Color,
    val accent: Color,
)

object AgendaWallColors {
    val midnight = AgendaPalette(
        panelSurface = Color(0xFF171A20), fieldSurface = Color(0xFF292E38), primaryText = Color(0xFFFFFFFF), secondaryText = Color(0xFFD5DCE8), noteSurface = Color(0xFFFFD166), noteInk = Color(0xFF2B2118), noteHighlight = Color(0x66FFF3A6), accent = Color(0xFF9CCBFF), // design-style: fixed-figma-color midnight palette
    )
    val warm = AgendaPalette(
        panelSurface = Color(0xFF332A25), fieldSurface = Color(0xFF5A4840), primaryText = Color(0xFFFFF8F0), secondaryText = Color(0xFFE7D8CB), noteSurface = Color(0xFFFFC857), noteInk = Color(0xFF2B2118), noteHighlight = Color(0x66FFF0A6), accent = Color(0xFFFFB56B), // design-style: fixed-figma-color warm palette
    )
    val ocean = AgendaPalette(
        panelSurface = Color(0xFF102A36), fieldSurface = Color(0xFF1E4556), primaryText = Color(0xFFF2FCFF), secondaryText = Color(0xFFC6E4EC), noteSurface = Color(0xFF8BD3DD), noteInk = Color(0xFF08242D), noteHighlight = Color(0x66FFF3A6), accent = Color(0xFF7DE2D1), // design-style: fixed-figma-color ocean palette
    )
}
