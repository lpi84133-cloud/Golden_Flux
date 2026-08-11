package com.goldenflux.goldenfluxgame.flux.util

import android.net.Uri
import com.goldenflux.goldenfluxgame.BuildConfig
import java.util.Collections

/**
 * Host allowlist for URLs handed to the app from outside — the config
 * endpoint's answer and push payloads. Two tiers:
 *
 *  1. **Static suffixes** from `flux.allowedHosts` (BuildConfig). Fixed at
 *     build time. This is the floor a push URL must clear on a cold start,
 *     before the config endpoint has run in this process.
 *
 *  2. **Runtime-trusted hosts** learned from the config endpoint. The config
 *     endpoint is *our* backend, reached over TLS behind Play Integrity App
 *     Check; whatever destination host it names is, by definition, a host we
 *     chose to send the user to. Its answer is therefore trusted verbatim
 *     (see [remember]) and, so a push to the same campaign host also passes
 *     later, its host is added here. The set is seeded from persistence at
 *     startup ([seed]) so it survives process death.
 *
 * This gate deliberately does NOT apply to in-WebView navigation: affiliate
 * chains legitimately cross hosts nobody can enumerate in advance, and gating
 * them breaks the product.
 */
internal object HostGate {

    private val suffixes: List<String> = BuildConfig.ALLOWED_HOSTS
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }

    private val trusted: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf<String>())

    val hasWhitelist: Boolean = suffixes.isNotEmpty()

    /** Load persisted campaign hosts (from a previous session's config answer). */
    fun seed(hosts: Collection<String>) {
        val clean = hosts.mapNotNull { it.trim().lowercase().takeIf(String::isNotBlank) }
        synchronized(trusted) { trusted.addAll(clean) }
    }

    fun snapshot(): Set<String> = synchronized(trusted) { trusted.toSet() }

    /**
     * Records the host of a config-endpoint URL as trusted and returns it.
     * Call this the moment the endpoint hands back a `Stream` URL — the
     * endpoint is the authority on where the user goes, so its host is
     * trusted for any subsequent gating (e.g. warm/cold push to the campaign).
     */
    fun remember(url: String?): String? {
        val host = hostOf(url) ?: return null
        synchronized(trusted) { trusted.add(host) }
        return host
    }

    fun admits(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        if (suffixes.isEmpty() && synchronized(trusted) { trusted.isEmpty() }) return true
        if (suffixes.any { host == it || host.endsWith(".$it") }) return true
        return synchronized(trusted) {
            trusted.any { host == it || host.endsWith(".$it") }
        }
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
    }

    fun warnIfEmpty() {
        if (suffixes.isEmpty())
            Tracer.w("HostGate", "flux.allowedHosts is empty — every incoming URL will be accepted")
    }
}
