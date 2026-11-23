package io.github.lbondoc.postmarked.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lbondoc.postmarked.resources.Res
import io.github.lbondoc.postmarked.resources.barlowcondensed_600semibold
import io.github.lbondoc.postmarked.resources.barlowcondensed_700bold
import io.github.lbondoc.postmarked.resources.caveat_600semibold
import io.github.lbondoc.postmarked.resources.inter_400regular
import io.github.lbondoc.postmarked.resources.inter_500medium
import io.github.lbondoc.postmarked.resources.inter_600semibold
import kotlin.math.PI
import kotlin.math.sin
import org.jetbrains.compose.resources.Font

// Day: warm paper and charcoal ink, postal red, highway-sign green, mustard.
private val Day = lightColorScheme(
    primary = Color(0xFFB5452F), onPrimary = Color.White,
    primaryContainer = Color(0xFFF2D7CC), onPrimaryContainer = Color(0xFF4A1A10),
    secondary = Color(0xFF2F5D50), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFE3DA), onSecondaryContainer = Color(0xFF0F2A22),
    tertiary = Color(0xFF8A6417), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3DFB0), onTertiaryContainer = Color(0xFF2A1E05),
    background = Color(0xFFF4EFE4), onBackground = Color(0xFF23201C),
    surface = Color(0xFFF4EFE4), onSurface = Color(0xFF23201C),
    surfaceVariant = Color(0xFFE6DFD0), onSurfaceVariant = Color(0xFF4F4A42),
    surfaceContainerLowest = Color(0xFFFBF8F1), surfaceContainerLow = Color(0xFFF7F2E8),
    surfaceContainer = Color(0xFFF1EBDF), surfaceContainerHigh = Color(0xFFEBE4D7),
    surfaceContainerHighest = Color(0xFFE6DFD0),
    outline = Color(0xFF7D766B), outlineVariant = Color(0xFFCFC7B8),
)

// Night drive: charcoal with warm amber, like a dashboard after dark.
private val Night = darkColorScheme(
    primary = Color(0xFFF0A830), onPrimary = Color(0xFF2A1C00),
    primaryContainer = Color(0xFF5A3F00), onPrimaryContainer = Color(0xFFFFDDA6),
    secondary = Color(0xFF8CC5B3), onSecondary = Color(0xFF0B2C23),
    secondaryContainer = Color(0xFF244A3E), onSecondaryContainer = Color(0xFFCFE3DA),
    tertiary = Color(0xFFE38B6F), onTertiary = Color(0xFF3A0E02),
    background = Color(0xFF1C1B19), onBackground = Color(0xFFEDE6D6),
    surface = Color(0xFF1C1B19), onSurface = Color(0xFFEDE6D6),
    surfaceVariant = Color(0xFF34312C), onSurfaceVariant = Color(0xFFCFC6B6),
    surfaceContainerLowest = Color(0xFF161513), surfaceContainerLow = Color(0xFF201F1C),
    surfaceContainer = Color(0xFF242320), surfaceContainerHigh = Color(0xFF2E2B28),
    surfaceContainerHighest = Color(0xFF34312C),
    outline = Color(0xFF978F82), outlineVariant = Color(0xFF4A463F),
)

/** Bold condensed road-sign lettering, for headings and stats. */
@Composable
fun headingFont() = FontFamily(
    Font(Res.font.barlowcondensed_600semibold, FontWeight.SemiBold),
    Font(Res.font.barlowcondensed_700bold, FontWeight.Bold),
)

/** Handwriting, for the Film album theme's captions. */
@Composable
fun handFont() = FontFamily(Font(Res.font.caveat_600semibold, FontWeight.SemiBold))

@Composable
private fun bodyFont() = FontFamily(
    Font(Res.font.inter_400regular, FontWeight.Normal),
    Font(Res.font.inter_500medium, FontWeight.Medium),
    Font(Res.font.inter_600semibold, FontWeight.SemiBold),
)

@Composable
private fun typography(): Typography {
    val heading = headingFont()
    val body = bodyFont()
    val base = Typography()
    fun TextStyle.sign(weight: FontWeight = FontWeight.Bold) = copy(fontFamily = heading, fontWeight = weight, letterSpacing = 0.3.sp)
    fun TextStyle.plain() = copy(fontFamily = body)
    return Typography(
        displayLarge = base.displayLarge.sign(), displayMedium = base.displayMedium.sign(), displaySmall = base.displaySmall.sign(),
        headlineLarge = base.headlineLarge.sign(), headlineMedium = base.headlineMedium.sign(), headlineSmall = base.headlineSmall.sign(),
        titleLarge = base.titleLarge.sign(), titleMedium = base.titleMedium.sign(FontWeight.SemiBold), titleSmall = base.titleSmall.sign(FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.plain(), bodyMedium = base.bodyMedium.plain(), bodySmall = base.bodySmall.plain(),
        labelLarge = base.labelLarge.plain(), labelMedium = base.labelMedium.plain(), labelSmall = base.labelSmall.plain(),
    )
}

/** The app-wide old-school road-trip look; follows the phone's light or dark setting. */
@Composable
fun PostmarkedTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Night else Day,
        typography = typography(),
        // Squarer than Material's default, like tickets and stamps.
        shapes = Shapes(
            extraSmall = RoundedCornerShape(2.dp), small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(6.dp),
            large = RoundedCornerShape(10.dp), extraLarge = RoundedCornerShape(14.dp),
        ),
        content = content,
    )
}

/**
 * The Postmarked logo: a round postmark with the name across it, and wavy cancellation lines
 * trailing off to the right, as a post office stamp leaves on an envelope.
 */
@Composable
fun PostmarkLogo(size: Dp = 56.dp, colour: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Postmarked" }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val r = this.size.minDimension / 2
                drawCircle(colour, r - 2.dp.toPx(), style = Stroke(2.5.dp.toPx()))
                drawCircle(colour, r - 6.dp.toPx(), style = Stroke(1.dp.toPx()))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("POSTMARKED", color = colour, fontFamily = headingFont(), fontWeight = FontWeight.Bold, fontSize = (size.value / 6.2).sp, letterSpacing = 0.5.sp)
            }
        }
        Canvas(Modifier.size(width = size * 0.9f, height = size * 0.5f)) {
            // Four sine waves: the cancellation marks.
            for (line in 0 until 4) {
                val y0 = this.size.height * (line + 0.5f) / 4
                val path = Path()
                var x = 0f
                path.moveTo(0f, y0)
                while (x <= this.size.width) {
                    path.lineTo(x, y0 + sin(x / this.size.width * 4 * PI).toFloat() * this.size.height / 14)
                    x += 2f
                }
                drawPath(path, colour, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/**
 * The postmark stamped across the screen when a trip is created: it lands big and tilted, then
 * settles, the way a rubber stamp hits paper. Calls [onDone] once it has had a moment to be seen.
 */
@Composable
fun StampOverlay(onDone: () -> Unit) {
    val landing = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        landing.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        delay(650)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        PostmarkLogo(
            size = 140.dp,
            modifier = Modifier.graphicsLayer {
                val t = landing.value
                alpha = t
                scaleX = 1.8f - 0.8f * t
                scaleY = 1.8f - 0.8f * t
                rotationZ = -14f
            },
        )
    }
}
