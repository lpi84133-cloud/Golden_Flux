package com.goldenflux.goldenfluxgame.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.goldenflux.goldenfluxgame.data.GameAssets
import com.goldenflux.goldenfluxgame.game.DeviceInstance
import com.goldenflux.goldenfluxgame.game.DeviceType
import com.goldenflux.goldenfluxgame.game.ZoneId
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Isometric layout maths for one board size. */
private class IsoGeom(val zone: ZoneId, width: Float, height: Float) {
    val tileW: Float
    val tileH: Float
    private val originX: Float
    private val originY: Float

    init {
        val cols = zone.cols
        val rows = zone.rows
        val spanUnits = (cols + rows - 2) / 2f + 1f
        val byWidth = width * 0.95f / spanUnits
        val byHeight = height * 0.82f / (spanUnits * TILE_RATIO)
        tileW = min(byWidth, byHeight)
        tileH = tileW * TILE_RATIO

        // centre the diamond bounding box inside the canvas
        val xSpread = ((cols - 1) - (rows - 1)) * tileW / 2f
        originX = width / 2f - xSpread / 2f
        val boardH = (cols + rows - 2) * tileH / 2f
        originY = (height - boardH) / 2f + tileH * 0.1f
    }

    fun center(tile: Int): Offset {
        val c = tile % zone.cols
        val r = tile / zone.cols
        return Offset(
            originX + (c - r) * tileW / 2f,
            originY + (c + r) * tileH / 2f,
        )
    }

    /** Tile under [p], using true diamond containment with a small fallback. */
    fun hitTest(p: Offset): Int? {
        var fallback: Int? = null
        var bestDist = Float.MAX_VALUE
        for (tile in 0 until zone.tiles) {
            val c = center(tile)
            val dx = abs(p.x - c.x) / (tileW / 2f)
            val dy = abs(p.y - c.y) / (tileH / 2f)
            if (dx + dy <= 1f) return tile
            val d = (p - c).getDistance()
            if (d < bestDist) { bestDist = d; fallback = tile }
        }
        return if (bestDist < tileW * 0.55f) fallback else null
    }

    /** Painter's order: far tiles first so nearer sprites overlap correctly. */
    fun drawOrder(): List<Int> =
        (0 until zone.tiles).sortedBy { (it % zone.cols) + (it / zone.cols) }

    private companion object { const val TILE_RATIO = 0.55f }
}

/**
 * The playable board. Tap an empty platform to build, tap a machine to inspect,
 * or drag a machine onto an identical one to merge it into the next tier.
 */
@Composable
fun FactoryGrid(
    zone: ZoneId,
    devices: Map<Int, DeviceInstance>,
    tileBoost: Map<Int, Double>,
    onTileTap: (Int) -> Unit,
    onMoveOrMerge: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }

    val transition = rememberInfiniteTransition(label = "board")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "phase",
    )

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(zone, devices) {
                awaitEachGesture {
                    val geom = IsoGeom(zone, size.width.toFloat(), size.height.toFloat())
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startTile = geom.hitTest(down.position)
                    var pos = down.position
                    var dragging = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        pos = change.position
                        if (change.changedToUp()) break
                        if (!dragging && startTile != null && devices.containsKey(startTile) &&
                            (pos - down.position).getDistance() > viewConfiguration.touchSlop
                        ) {
                            dragging = true
                            dragFrom = startTile
                        }
                        if (dragging) {
                            dragPos = pos
                            change.consume()
                        }
                    }

                    if (dragging) {
                        val from = dragFrom
                        val target = geom.hitTest(pos)
                        dragFrom = null
                        if (from != null && target != null && target != from) onMoveOrMerge(from, target)
                    } else if (startTile != null) {
                        onTileTap(startTile)
                    }
                }
            }
    ) {
        val geom = IsoGeom(zone, size.width, size.height)
        val dragged = dragFrom?.let { devices[it] }

        // platforms
        geom.drawOrder().forEach { tile ->
            val occupied = devices.containsKey(tile) && tile != dragFrom
            val dropTarget = dragged != null && isDropTarget(devices, dragged, tile, dragFrom)
            drawPlatform(
                center = geom.center(tile),
                tileW = geom.tileW,
                tileH = geom.tileH,
                occupied = occupied,
                boosted = tileBoost.containsKey(tile),
                highlight = dropTarget,
                phase = phase,
            )
        }

        // energy channels from amplifiers to the machines they feed
        devices.values.forEach { amp ->
            if (amp.type != DeviceType.AMPLIFIER || amp.tile == dragFrom) return@forEach
            zone.neighbours(amp.tile).forEach { n ->
                val target = devices[n] ?: return@forEach
                if (target.type == DeviceType.AMPLIFIER) return@forEach
                drawEnergyLink(geom.center(amp.tile), geom.center(n), phase)
            }
        }

        // machines
        geom.drawOrder().forEach { tile ->
            if (tile == dragFrom) return@forEach
            val device = devices[tile] ?: return@forEach
            drawDevice(device, geom.center(tile), geom.tileW, geom.tileH, phase, 1f)
        }

        // dragged machine follows the finger
        dragged?.let { d ->
            drawDevice(d, dragPos, geom.tileW * 1.12f, geom.tileH, phase, 0.9f)
        }
    }
}

private fun isDropTarget(
    devices: Map<Int, DeviceInstance>,
    dragged: DeviceInstance,
    tile: Int,
    from: Int?,
): Boolean {
    if (tile == from) return false
    val target = devices[tile] ?: return true // empty: relocation
    return target.type == dragged.type &&
        target.level == dragged.level &&
        target.level < dragged.type.maxLevel
}

private fun diamond(center: Offset, w: Float, h: Float): Path = Path().apply {
    moveTo(center.x, center.y - h / 2f)
    lineTo(center.x + w / 2f, center.y)
    lineTo(center.x, center.y + h / 2f)
    lineTo(center.x - w / 2f, center.y)
    close()
}

private fun DrawScope.drawPlatform(
    center: Offset,
    tileW: Float,
    tileH: Float,
    occupied: Boolean,
    boosted: Boolean,
    highlight: Boolean,
    phase: Float,
) {
    val depth = tileH * 0.34f

    // stone side of the platform
    drawPath(diamond(center + Offset(0f, depth), tileW, tileH), color = Color(0xFF2A1A0B))
    drawPath(
        diamond(center + Offset(0f, depth * 0.5f), tileW, tileH),
        brush = Brush.verticalGradient(listOf(Color(0xFF6B4708), Color(0xFF3A2606))),
    )

    // top face
    val face = diamond(center, tileW, tileH)
    val fill = when {
        highlight -> Brush.verticalGradient(listOf(Color(0xFFFFE9A8), Color(0xFFE0A62A)))
        occupied -> Brush.verticalGradient(listOf(Color(0xFF6A4CA8), Color(0xFF3A2168)))
        else -> Brush.verticalGradient(listOf(Color(0xFF4B2E86), Color(0xFF2A1650)))
    }
    drawPath(face, brush = fill)
    drawPath(
        face,
        color = if (highlight) Color(0xFFFFF3C4) else Color(0xFFB68A2E),
        style = Stroke(width = if (highlight) 4f else 2.5f),
    )

    if (boosted) {
        val pulse = 0.35f + 0.25f * sin(phase * TWO_PI).toFloat()
        drawPath(face, color = Color(0xFF4DD0C4).copy(alpha = pulse * 0.5f))
    }

    // build affordance on empty platforms
    if (!occupied && !highlight) {
        val r = tileW * 0.13f
        val glow = 0.35f + 0.2f * sin(phase * TWO_PI).toFloat()
        drawPath(
            diamond(center, r * 2f, r * 2f * 0.9f),
            color = Color(0xFFFFE9A8).copy(alpha = glow * 0.5f),
            style = Stroke(width = 3f),
        )
    }
}

private fun DrawScope.drawEnergyLink(from: Offset, to: Offset, phase: Float) {
    drawLine(
        brush = Brush.linearGradient(
            listOf(Color(0x00FFD54F), Color(0xCCFFD54F), Color(0x00FFD54F)),
            start = from, end = to,
        ),
        start = from,
        end = to,
        strokeWidth = 6f,
        cap = StrokeCap.Round,
    )
    // travelling spark
    val t = (phase % 1f)
    val p = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
    drawCircle(color = Color(0xFFFFF3C4), radius = 5f, center = p)
    drawCircle(color = Color(0x66FFD54F), radius = 11f, center = p)
}

private fun DrawScope.drawDevice(
    device: DeviceInstance,
    center: Offset,
    tileW: Float,
    tileH: Float,
    phase: Float,
    alpha: Float,
) {
    val bmp: ImageBitmap = GameAssets[device.type.spriteFor(device.level)] ?: return
    val targetW = tileW * 0.86f
    val targetH = targetW * (bmp.height.toFloat() / bmp.width.toFloat())
    val bob = sin(phase * TWO_PI + device.tile) .toFloat() * tileH * 0.045f
    val left = center.x - targetW / 2f
    val top = center.y - targetH + tileH * 0.42f + bob

    // contact shadow
    drawPath(
        diamond(center + Offset(0f, tileH * 0.08f), tileW * 0.7f, tileH * 0.45f),
        color = Color(0x55000000),
    )
    drawImage(
        image = bmp,
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(targetW.toInt(), targetH.toInt()),
        alpha = alpha,
    )
    drawTierPips(center, tileW, tileH, device.level, device.type.maxLevel)
}

/** Small gold pips under a machine showing its tier at a glance. */
private fun DrawScope.drawTierPips(center: Offset, tileW: Float, tileH: Float, level: Int, maxLevel: Int) {
    val r = tileW * 0.032f
    val gap = r * 3f
    val totalW = gap * (maxLevel - 1)
    val y = center.y + tileH * 0.30f
    for (i in 0 until maxLevel) {
        val x = center.x - totalW / 2f + gap * i
        val filled = i < level
        drawPath(
            diamond(Offset(x, y), r * 2f, r * 2.4f),
            color = if (filled) Color(0xFFF7C93E) else Color(0x66000000),
        )
        if (filled) {
            drawPath(
                diamond(Offset(x, y), r * 2f, r * 2.4f),
                color = Color(0xFFFFF3C4),
                style = Stroke(width = 1f),
            )
        }
    }
}

private const val TWO_PI = 6.2831855f
