package com.goldenflux.goldenfluxgame.flux.util

import com.goldenflux.goldenfluxgame.BuildConfig

/**
 * Runtime decoder for strings the build script encoded into `BuildConfig`.
 *
 * The template shipped a family of XOR variants selected by an integer, so a
 * static reviewer only had to try three shapes to break the codec. This
 * version uses a keystream derived through HMAC-SHA256 during the build:
 * every project has its own [BuildConfig.CODEC_KEY] plus a much longer
 * [BuildConfig.CODEC_STREAM], and the same slice is reproduced here.
 *
 * The class name and file path are meant to differ per project — the
 * portfolio's next app puts its decoder elsewhere.
 */
internal object Reveal {

    /** @param obscured produced by the build's `encodedIntArrayLiteral`. */
    fun string(obscured: IntArray): String {
        if (obscured.isEmpty()) return ""
        val stream = BuildConfig.CODEC_STREAM
        val key    = BuildConfig.CODEC_KEY
        val out = ByteArray(obscured.size)
        for (i in obscured.indices) {
            val k1 = stream[i % stream.size].toInt() and 0xFF
            val k2 = key[((i * 37) + 5) % key.size].toInt() and 0xFF
            val mix = (i * 131 + 91) and 0xFF
            out[i] = ((obscured[i] and 0xFF) xor k1 xor k2 xor mix).toByte()
        }
        return String(out, Charsets.UTF_8)
    }
}
