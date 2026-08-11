package com.goldenflux.goldenfluxgame.flux.env

import com.goldenflux.goldenfluxgame.BuildConfig
import com.goldenflux.goldenfluxgame.flux.util.Reveal

/**
 * Read-only view over the per-install fingerprint the build encoded into
 * [BuildConfig]. Everything the rest of the flux package reads about identity,
 * timing or credentials comes from here, so those files carry no literal to
 * cluster on.
 *
 * The historical class-with-a-name is deliberately replaced by an `object`:
 * two apps in the portfolio never share the same enclosing type, and the
 * shape of the API surface can differ project to project as well as its
 * values. This project's version exposes credentials as suspending resolvers
 * rather than plain properties so the decode work does not happen on
 * class-load.
 */
object Env {

    // Identity
    val bundleId: String = BuildConfig.FLUX_BUNDLE_ID
    val appLabel: String = BuildConfig.FLUX_APP_LABEL
    val userAgentToken: String = BuildConfig.FLUX_UA_TOKEN
    val addUaAppSuffix: Boolean = BuildConfig.FLUX_UA_APP_SUFFIX

    // Timings
    val attributionColdMs: Long   = BuildConfig.ATTRIBUTION_COLD_MS
    val attributionWarmMs: Long   = BuildConfig.ATTRIBUTION_WARM_MS
    val deferredLinkWaitMs: Long  = BuildConfig.DEFERRED_LINK_WAIT_MS
    val uplinkTimeoutMs: Long     = BuildConfig.UPLINK_TIMEOUT_MS
    val organicRecheckMs: Long    = BuildConfig.ORGANIC_RECHECK_MS
    val gcdTimeoutMs: Long        = BuildConfig.GCD_TIMEOUT_MS
    val linkGraceMs: Long         = BuildConfig.LINK_GRACE_MS
    val insetReinjectMs: Long     = BuildConfig.INSET_REINJECT_MS
    val heartbeatMs: Long         = BuildConfig.HEARTBEAT_MS
    val promoDeferSeconds: Long   = BuildConfig.PROMO_DEFER_SEC
    val redirectBudget: Int       = BuildConfig.REDIRECT_BUDGET

    // Secrets — decoded on first read, cached thereafter.
    val uplinkUrl: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Reveal.string(BuildConfig.SEC_UPLINK_URL)
    }
    val trackerKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Reveal.string(BuildConfig.SEC_TRACK_KEY)
    }
    val firebaseProject: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Reveal.string(BuildConfig.SEC_FIREBASE_ID)
    }
    val gcdBase: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Reveal.string(BuildConfig.SEC_GCD_BASE)
    }

    // Debug — empty in release under all circumstances.
    val debugForceUrl: String get() = BuildConfig.DEBUG_FORCE_URL
}
