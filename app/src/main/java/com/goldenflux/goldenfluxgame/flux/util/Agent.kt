package com.goldenflux.goldenfluxgame.flux.util

import android.os.Build
import com.goldenflux.goldenfluxgame.BuildConfig

/**
 * The single User-Agent builder. Everything on the wire — the WebView, the
 * uplink POST, the GCD GET — reads from here, so no partner ever sees two
 * different UA strings from the same install (kotlin_user_agent.mdc §1).
 *
 * The Chrome major/build/patch tuple is per-install and reaches runtime
 * through `BuildConfig`. The scaffolding (`Mozilla/5.0`, `AppleWebKit/537.36`,
 * `Mobile Safari/537.36`) is left as literals because every real Chrome on
 * Android ships those substrings verbatim — they are what a billion honest
 * apps also contain, not a portfolio marker.
 */
internal object Agent {

    val value: String by lazy(LazyThreadSafetyMode.PUBLICATION) { build() }

    private fun build(): String {
        val version = Build.VERSION.RELEASE
        val brand   = printableAscii(Build.BRAND)
        val model   = printableAscii(Build.MODEL).replace(' ', '_')
        val buildId = printableAscii(Build.ID)
        val chrome  = "${BuildConfig.UA_CHROME_MAJOR}.0.${BuildConfig.UA_CHROME_BUILD}.${BuildConfig.UA_CHROME_PATCH}"

        val base = buildString {
            append("Mozilla/5.0 (Linux; Android ")
            append(version).append("; ").append(brand).append(' ').append(model)
            if (buildId.isNotBlank()) append(" Build/").append(buildId)
            append(") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/")
            append(chrome).append(" Mobile Safari/537.36")
        }

        return base
    }

    private fun printableAscii(s: String?): String =
        s?.filter { it in ' '..'~' } ?: ""
}
