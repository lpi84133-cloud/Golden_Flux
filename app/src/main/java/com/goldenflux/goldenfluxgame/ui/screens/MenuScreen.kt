package com.goldenflux.goldenfluxgame.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.goldenflux.goldenfluxgame.data.GameAssets
import com.goldenflux.goldenfluxgame.game.GameViewModel
import com.goldenflux.goldenfluxgame.ui.Route
import com.goldenflux.goldenfluxgame.ui.design.AssetIcon
import com.goldenflux.goldenfluxgame.ui.design.GfButton
import com.goldenflux.goldenfluxgame.ui.design.GlassRow
import com.goldenflux.goldenfluxgame.ui.design.glow
import com.goldenflux.goldenfluxgame.ui.theme.GfAmber
import com.goldenflux.goldenfluxgame.ui.theme.GfGold
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldDeep
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfTextDim
import com.goldenflux.goldenfluxgame.util.formatCompact
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private const val MENU_BG = "game/bg/bg_golden_archive.webp"

@Composable
fun MenuScreen(gameVm: GameViewModel, onOpen: (Route) -> Unit) {
    val state by gameVm.state.collectAsState()
    val stats by gameVm.stats.collectAsState()

    val transition = rememberInfiniteTransition(label = "menu")
    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val drift by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Restart),
        label = "time",
    )

    Box(Modifier.fillMaxSize().background(Color(0xFF120727))) {
        GameAssets[MENU_BG]?.let { bg ->
            Image(
                bitmap = bg,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(drift),
                contentScale = ContentScale.Crop,
            )
        }
        // Scrim: keeps the art visible in the middle, but guarantees readable text
        // at the top and a solid base for the buttons.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xF21A0B36),
                        0.35f to Color(0x99160829),
                        0.62f to Color(0xB3130722),
                        1f to Color(0xFA0B0418),
                    )
                )
        )
        MoteField(Modifier.fillMaxSize(), time)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GlassRow(onClick = { gameVm.click(); onOpen(Route.PROFILE) }) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(state.profile.avatarPath, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.profile.name,
                            color = GfGoldLight,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp,
                        )
                        Text(
                            "Core Lv ${state.coreLevel}  ·  ${state.deviceCount} machines",
                            color = GfTextDim,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(0.45f))
            AssetIcon(
                "game/logo.webp",
                Modifier.fillMaxWidth().height(116.dp),
                contentScale = ContentScale.Fit,
            )
            Box(contentAlignment = Alignment.Center) {
                RayHalo(Modifier.size(300.dp), time)
                AssetIcon(
                    "game/sprites/core.png",
                    Modifier
                        .size(188.dp)
                        .scale(pulse)
                        .glow(GfGold, radiusScale = 0.62f, alpha = 0.5f),
                )
            }
            Text(
                "+${formatCompact(stats.energyPerSec)} energy / sec",
                color = GfGoldLight,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))

            GfButton(
                text = if (state.deviceCount == 0) "Start the Flux" else "Continue",
                modifier = Modifier.fillMaxWidth(0.78f),
            ) { gameVm.click(); onOpen(Route.FACTORY) }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MenuChip("Stats") { gameVm.click(); onOpen(Route.STATS) }
                MenuChip("Codex") { gameVm.click(); onOpen(Route.COLLECTION) }
                MenuChip("Settings") { gameVm.click(); onOpen(Route.SETTINGS) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MenuChip("Privacy Policy") { gameVm.click(); onOpen(Route.PRIVACY) }
                MenuChip("Support") { gameVm.click(); onOpen(Route.SUPPORT) }
            }
        }
    }
}

/** Slow beams of light turning behind the Core. */
@Composable
private fun RayHalo(modifier: Modifier, time: Float) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        rotate(degrees = time * 360f, pivot = center) {
            repeat(12) { i ->
                val angle = i * 30f
                rotate(degrees = angle, pivot = center) {
                    val path = Path().apply {
                        moveTo(center.x, center.y)
                        lineTo(center.x - radius * 0.11f, center.y - radius)
                        lineTo(center.x + radius * 0.11f, center.y - radius)
                        close()
                    }
                    drawPath(
                        path = path,
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, GfAmber.copy(alpha = 0.10f)),
                            startY = center.y - radius,
                            endY = center.y,
                        ),
                    )
                }
            }
        }
        drawCircle(
            brush = Brush.radialGradient(
                listOf(GfGold.copy(alpha = 0.16f), Color.Transparent),
                center = center,
                radius = radius * 0.75f,
            ),
            radius = radius * 0.75f,
            center = center,
        )
    }
}

private class Mote(val x: Float, val phase: Float, val speed: Float, val r: Float, val sway: Float)

/** Golden dust rising through the hall; purely decorative, ~22 primitives. */
@Composable
private fun MoteField(modifier: Modifier, time: Float) {
    val motes = remember {
        val rnd = Random(7)
        List(22) {
            Mote(
                x = rnd.nextFloat(),
                phase = rnd.nextFloat(),
                speed = 0.6f + rnd.nextFloat(),
                r = 1.4f + rnd.nextFloat() * 2.6f,
                sway = 8f + rnd.nextFloat() * 26f,
            )
        }
    }
    Canvas(modifier) {
        motes.forEach { m ->
            val p = (m.phase + time * m.speed) % 1f
            val y = size.height * (1f - p)
            val x = size.width * m.x + sin((p * 2f * PI * 2f).toFloat() + m.phase * 6f) * m.sway
            val fade = if (p < 0.15f) p / 0.15f else if (p > 0.85f) (1f - p) / 0.15f else 1f
            drawCircle(
                color = GfGoldLight.copy(alpha = 0.5f * fade),
                radius = m.r,
                center = Offset(x, y),
            )
        }
    }
}

@Composable
fun Avatar(path: String?, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF4B2E86), Color(0xFF241040))))
            .drawBehind {
                drawCircle(
                    brush = Brush.verticalGradient(listOf(GfGoldLight, GfGoldDeep)),
                    radius = this.size.minDimension / 2f - 1.5f,
                    style = Stroke(width = 3f),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            AsyncImage(
                model = File(path),
                contentDescription = "Avatar",
                modifier = Modifier.size(size * 0.88f).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = GfGoldLight,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

@Composable
private fun MenuChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Brush.verticalGradient(listOf(Color(0xCC3E2071), Color(0xCC1D0F38))))
            .drawBehind {
                drawRoundRect(
                    color = Color(0x88B68A2E),
                    cornerRadius = CornerRadius(13.dp.toPx()),
                    style = Stroke(width = 1.5f),
                )
            }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, color = GfTextDim, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}
