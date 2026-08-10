package com.goldenflux.goldenfluxgame.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.goldenflux.goldenfluxgame.game.DeviceType
import com.goldenflux.goldenfluxgame.game.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded game art, kept in memory for the whole session.
 *
 * Every asset is decoded at the size it is actually drawn at: the source art is
 * far larger than any on-screen element, and decoding it full size was the main
 * source of memory pressure and jank.
 */
object GameAssets {

    private data class Spec(val path: String, val maxDim: Int, val opaque: Boolean = false)

    private val images = HashMap<String, ImageBitmap>()

    operator fun get(path: String): ImageBitmap? = images[path]

    private fun specs(): List<Spec> {
        val list = ArrayList<Spec>()
        DeviceType.entries.forEach { t ->
            for (lvl in 1..t.maxLevel) list.add(Spec(t.spriteFor(lvl), 220))
        }
        list.add(Spec("game/sprites/core.png", 420))
        list.add(Spec("game/sprites/final_reactor.png", 260))
        list.add(Spec("game/sprites/portal.png", 240))
        list.add(Spec("game/sprites/chest.png", 260))
        list.add(Spec("game/sprites/upgrade_crystal.png", 160))
        list.add(Spec("game/sprites/crystal_1.png", 128))
        list.add(Spec("game/sprites/crystal_2.png", 128))
        list.add(Spec("game/sprites/symbol_1.png", 128))
        list.add(Spec("game/sprites/artifact_1.png", 128))
        list.add(Spec("game/sprites/fruit_1.png", 128))
        list.add(Spec("game/sprites/mechanism_1.png", 160))
        ZoneId.entries.map { it.background }.distinct().forEach {
            list.add(Spec(it, 1024, opaque = true))
        }
        list.add(Spec("game/logo.webp", 720))
        return list.distinctBy { it.path }
    }

    private fun decode(context: Context, spec: Spec): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(spec.path).use { BitmapFactory.decodeStream(it, null, bounds) }

        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (largest / (sample * 2) >= spec.maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (spec.opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        context.assets.open(spec.path).use { input ->
            BitmapFactory.decodeStream(input, null, opts)?.asImageBitmap()
        }
    }.getOrNull()

    /**
     * Decodes every asset on [Dispatchers.IO], reporting fractional progress
     * (0f..1f) after each item; 1f means every bitmap is ready to draw.
     */
    suspend fun preload(
        context: Context,
        onProgress: (fraction: Float, label: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val list = specs()
        list.forEachIndexed { index, spec ->
            if (!images.containsKey(spec.path)) {
                decode(context, spec)?.let { images[spec.path] = it }
            }
            onProgress((index + 1).toFloat() / list.size, labelFor(spec.path))
        }
    }

    private fun labelFor(path: String): String = when {
        path.contains("/bg/") -> "Waking the ancient halls"
        path.contains("core") || path.contains("reactor") -> "Charging the Golden Core"
        path.contains("logo") -> "Polishing the gold"
        path.contains("sprites") -> "Assembling machines"
        else -> "Preparing the Flux"
    }
}
