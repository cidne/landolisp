package com.landolisp.ui.theme

import androidx.compose.ui.graphics.Color

// Brand seeds (kept in sync with res/values/colors.xml).
val BrandPrimary = Color(0xFF7C5CFF)
val BrandSecondary = Color(0xFF4DB6AC)
val BrandTertiary = Color(0xFFFFB74D)

// Dark scheme tuned for long reading sessions and code contrast.
val DarkBackground = Color(0xFF141322)
val DarkSurface = Color(0xFF1E1B2E)
val DarkSurfaceElevated = Color(0xFF272342)
val DarkOnBackground = Color(0xFFE9E7F2)

// Light scheme.
val LightBackground = Color(0xFFFBFAFF)
val LightSurface = Color(0xFFFFFFFF)
val LightOnBackground = Color(0xFF1B1A24)

// Rainbow paren nesting colors (6-cycle). Consumed by the editor (B1).
val ParenColors = listOf(
    Color(0xFFE57373),
    Color(0xFFFFB74D),
    Color(0xFFFFF176),
    Color(0xFF81C784),
    Color(0xFF64B5F6),
    Color(0xFFBA68C8),
)
