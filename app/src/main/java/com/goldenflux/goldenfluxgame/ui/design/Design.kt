package com.goldenflux.goldenfluxgame.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goldenflux.goldenfluxgame.data.GameAssets
import com.goldenflux.goldenfluxgame.ui.theme.GfAmber
import com.goldenflux.goldenfluxgame.ui.theme.GfDanger
import com.goldenflux.goldenfluxgame.ui.theme.GfGold
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldDeep
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldLight
import com.goldenflux.goldenfluxgame.ui.theme.GfGoldShadow
import com.goldenflux.goldenfluxgame.ui.theme.GfInk
import com.goldenflux.goldenfluxgame.ui.theme.GfPanel
import com.goldenflux.goldenfluxgame.ui.theme.GfPanelDeep
import com.goldenflux.goldenfluxgame.ui.theme.GfPanelLight
import com.goldenflux.goldenfluxgame.ui.theme.GfTextPrimary
import kotlin.math.min

/** Draws a preloaded asset bitmap; renders empty space until it is ready. */
@Composable
fun AssetIcon(
    path: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f,
) {
    val bmp = GameAssets[path]
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
            alpha = alpha,
        )
    } else {
        Box(modifier)
    }
}

// ---------------------------------------------------------------- ornate panel

/**
 * The signature Golden Flux frame: violet glass inside a double gold border
 * with corner gems, plus an optional title ribbon riding on the top edge.
 */
@Composable
fun OrnatePanel(
    modifier: Modifier = Modifier,
    title: String? = null,
    corner: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = if (title != null) 13.dp else 0.dp)
                .clip(RoundedCornerShape(corner))
                .background(Brush.verticalGradient(listOf(GfPanelLight, GfPanel, GfPanelDeep)))
                .drawBehind { drawOrnateBorder(corner.toPx()) }
                .padding(
                    start = 16.dp, end = 16.dp,
                    top = if (title != null) 22.dp else 16.dp,
                    bottom = 16.dp,
                ),
            content = content,
        )
        if (title != null) {
            Ribbon(text = title, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun Ribbon(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Brush.verticalGradient(listOf(GfGoldLight, GfGold, GfGoldDeep)))
            .drawBehind {
                drawRoundRect(
                    color = GfGoldShadow,
                    cornerRadius = CornerRadius(13.dp.toPx()),
                    style = Stroke(width = 2f),
                )
            }
            .padding(horizontal = 22.dp, vertical = 5.dp),
    ) {
        Text(
            text.uppercase(),
            color = GfInk,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            letterSpacing = 1.2.sp,
        )
    }
}

private fun DrawScope.drawOrnateBorder(radius: Float) {
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(GfGoldLight, GfGoldDeep, GfGoldShadow)),
        cornerRadius = CornerRadius(radius),
        style = Stroke(width = 3.5f),
    )
    drawRoundRect(
        color = Color(0x55FFE9A8),
        topLeft = Offset(7f, 7f),
        size = Size(size.width - 14f, size.height - 14f),
        cornerRadius = CornerRadius(radius * 0.8f),
        style = Stroke(width = 1.5f),
    )
    val inset = radius * 0.6f
    val gem = radius * 0.2f
    listOf(
        Offset(inset, inset),
        Offset(size.width - inset, inset),
        Offset(inset, size.height - inset),
        Offset(size.width - inset, size.height - inset),
    ).forEach { drawGem(it, gem) }
}

private fun DrawScope.drawGem(center: Offset, r: Float) {
    val p = Path().apply {
        moveTo(center.x, center.y - r)
        lineTo(center.x + r, center.y)
        lineTo(center.x, center.y + r)
        lineTo(center.x - r, center.y)
        close()
    }
    drawPath(p, color = GfGold)
    drawPath(p, color = GfGoldLight, style = Stroke(width = 1.5f))
}

// ---------------------------------------------------------------- buttons

enum class GfButtonStyle { Gold, Stone, Danger }

/**
 * Chunky bevelled button: the face sinks into its rim on press and the gloss
 * fades, so a tap feels physical instead of flat.
 */
@Composable
fun GfButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: GfButtonStyle = GfButtonStyle.Gold,
    icon: String? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(70),
        label = "sink",
    )

    val face: List<Color>
    val rim: List<Color>
    val label: Color
    when {
        !enabled -> {
            face = listOf(Color(0xFF4A4256), Color(0xFF35303F))
            rim = listOf(Color(0xFF2A2533), Color(0xFF17131F))
            label = Color(0xFF9A93A8)
        }
        style == GfButtonStyle.Gold -> {
            face = listOf(GfGoldLight, GfGold, GfGoldDeep)
            rim = listOf(GfGoldDeep, GfGoldShadow)
            label = GfInk
        }
        style == GfButtonStyle.Danger -> {
            face = listOf(Color(0xFFFFA294), GfDanger, Color(0xFF9E3535))
            rim = listOf(Color(0xFF7E2A2A), Color(0xFF441414))
            label = Color(0xFF3A0C0C)
        }
        else -> {
            face = listOf(GfPanelLight, GfPanel, GfPanelDeep)
            rim = listOf(GfGoldDeep, GfGoldShadow)
            label = GfTextPrimary
        }
    }

    // Single layout node on purpose: the rim and the face are painted, not laid out,
    // so the button is exactly as wide as its label unless the caller says otherwise.
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(rim))
            .clickable(interaction, null, enabled = enabled) { onClick() }
            .padding(bottom = 3.dp)
            .drawBehind {
                val radius = CornerRadius(14.dp.toPx())
                translate(top = sink * 3f) {
                    drawRoundRect(brush = Brush.verticalGradient(face), cornerRadius = radius)
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.32f - sink * 0.22f), Color.Transparent)
                        ),
                        size = Size(size.width, size.height * 0.6f),
                        cornerRadius = radius,
                    )
                }
            }
            .padding(
                horizontal = if (compact) 14.dp else 22.dp,
                vertical = if (compact) 9.dp else 13.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.offset { IntOffset(0, (sink * 3f).toInt()) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                AssetIcon(icon, Modifier.size(if (compact) 18.dp else 22.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text,
                color = label,
                fontWeight = FontWeight.Black,
                fontSize = if (compact) 13.sp else 15.sp,
                textAlign = TextAlign.Center,
                letterSpacing = 0.3.sp,
                maxLines = 1,
            )
        }
    }
}

/** Circular framed icon button for gears, closes and quick actions. */
@Composable
fun GfIconButton(
    modifier: Modifier = Modifier,
    diameter: Dp = 42.dp,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(GfPanelLight, GfPanelDeep)))
            .drawBehind {
                drawCircle(
                    brush = Brush.verticalGradient(listOf(GfGoldLight, GfGoldDeep)),
                    radius = this.size.minDimension / 2f - 1.5f,
                    style = Stroke(width = 2.5f),
                )
            }
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

// ---------------------------------------------------------------- resources

/** Dark glass capsule with a gem-set sprite icon: the game's currency chip. */
@Composable
fun ResourcePill(
    icon: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = GfGold,
    sub: String? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(Color(0xE0281247), Color(0xE014092C))))
            .drawBehind {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(GfGoldLight, GfGoldDeep)),
                    cornerRadius = CornerRadius(22.dp.toPx()),
                    style = Stroke(width = 2f),
                )
            }
            .padding(start = 3.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.5f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) { AssetIcon(icon, Modifier.size(24.dp)) }
        Spacer(Modifier.width(5.dp))
        Column {
            Text(value, color = GfTextPrimary, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1)
            if (sub != null) {
                Text(sub, color = accent, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- toggle

/** Hand-made switch: gold rail with a polished knob, no Material look. */
@Composable
fun GfToggle(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val t by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "toggle")
    Box(
        modifier
            .size(width = 60.dp, height = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    if (checked) listOf(GfGoldDeep, GfGold)
                    else listOf(Color(0xFF241040), Color(0xFF160A2E))
                )
            )
            .drawBehind {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(GfGoldLight, GfGoldDeep)),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 2f),
                )
            }
            .clickable { onToggle() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset((4.dp.toPx() + 28.dp.toPx() * t).toInt(), 0) }
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        if (checked) listOf(Color.White, GfGoldLight)
                        else listOf(Color(0xFF9384B4), Color(0xFF554571))
                    )
                ),
        )
    }
}

// ---------------------------------------------------------------- progress

/** Left-to-right bar with a recessed track and a travelling shimmer. */
@Composable
fun GfProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 18.dp,
    colors: List<Color> = listOf(GfGoldLight, GfGold, GfAmber),
    shimmer: Boolean = true,
) {
    val f = fraction.coerceIn(0f, 1f)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(Brush.verticalGradient(listOf(Color(0xFF150B2C), Color(0xFF2A1450))))
            .drawBehind {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(GfGoldDeep, GfGoldShadow)),
                    cornerRadius = CornerRadius(size.height / 2f),
                    style = Stroke(width = 2f),
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(height / 2))
                .background(Brush.verticalGradient(colors))
                .drawWithContent {
                    drawContent()
                    if (shimmer && f > 0.03f) {
                        val w = size.width
                        val x = phase * w
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Transparent, Color(0x88FFFFFF), Color.Transparent),
                                startX = x - w * 0.2f,
                                endX = x + w * 0.2f,
                            )
                        )
                    }
                },
        )
    }
}

// ---------------------------------------------------------------- misc

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Rule(Modifier.weight(1f))
        Text(
            text.uppercase(),
            color = GfGoldLight,
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Rule(Modifier.weight(1f))
    }
}

@Composable
private fun Rule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(2.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, GfGoldDeep, Color.Transparent)))
    )
}

/** Glass row used for lists of stats and options. */
@Composable
fun GlassRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(Brush.horizontalGradient(listOf(Color(0x992B1552), Color(0x661D0F38))))
        .drawBehind {
            drawRoundRect(
                color = Color(0x66B68A2E),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = 1.5f),
            )
        }
    Box(if (onClick != null) base.clickable { onClick() } else base) { content() }
}

@Composable
fun NotifyBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFFFF8A65), GfDanger))),
        contentAlignment = Alignment.Center,
    ) {
        Text("$count", color = Color.White, fontWeight = FontWeight.Black, fontSize = 11.sp)
    }
}

/** Soft radial glow behind the core and other hero art. */
fun Modifier.glow(color: Color = GfGold, radiusScale: Float = 0.75f, alpha: Float = 0.45f) =
    drawBehind {
        val r = min(size.width, size.height) * radiusScale
        drawCircle(
            brush = Brush.radialGradient(
                listOf(color.copy(alpha = alpha), Color.Transparent),
                center = center,
                radius = r,
            ),
            radius = r,
            center = center,
        )
    }
