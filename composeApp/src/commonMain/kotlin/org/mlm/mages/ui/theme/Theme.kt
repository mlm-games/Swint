package org.mlm.mages.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AppColors {
    val Purple80 = Color(0xFFD0BCFF)
    val PurpleGrey80 = Color(0xFFCCC2DC)
    val Pink80 = Color(0xFFEFB8C8)
    val Purple40 = Color(0xFF6650a4)
    val PurpleGrey40 = Color(0xFF625b71)
    val Pink40 = Color(0xFF7D5260)
}

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Purple80,
    secondary = AppColors.PurpleGrey80,
    tertiary = AppColors.Pink80,
    surface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFF242629),
    background = Color(0xFF0F1114),
    primaryContainer = Color(0xFF4F378B),
    secondaryContainer = Color(0xFF4A4458),
    tertiaryContainer = Color(0xFF633B48)
)

private val LightColorScheme = lightColorScheme(
    primary = AppColors.Purple40,
    secondary = AppColors.PurpleGrey40,
    tertiary = AppColors.Pink40,
    surface = Color(0xFFFFFBFE),
    surfaceVariant = Color(0xFFF4F3F7),
    background = Color(0xFFFAFAFC),
    primaryContainer = Color(0xFFEADDFF),
    secondaryContainer = Color(0xFFE8DEF8),
    tertiaryContainer = Color(0xFFFFD8E4)
)

val AppTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MainTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}