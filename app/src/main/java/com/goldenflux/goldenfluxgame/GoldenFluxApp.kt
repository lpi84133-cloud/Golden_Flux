package com.goldenflux.goldenfluxgame

import android.app.Application
import com.goldenflux.goldenfluxgame.flux.store.FluxStore
import com.goldenflux.goldenfluxgame.flux.uplink.AttributionPilot
import com.goldenflux.goldenfluxgame.flux.util.HostGate
import com.goldenflux.goldenfluxgame.flux.util.Tracer
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Two responsibilities:
 *
 *  * Wire Firebase (best-effort) and App Check so the config endpoint accepts
 *    our POSTs.
 *  * [AttributionPilot.wireUp] before any activity runs, so the AppsFlyer
 *    ActivityLifecycleCallbacks are registered *before* an activity is
 *    resumed. Deferring this to the router is one of the three ways to lose
 *    attribution (pitfalls #19).
 *
 * The game itself has no dependency on this class; the game's Activity is
 * launched by [flux.entry.LaunchArbiter] once the routing decision is made.
 */
class GoldenFluxApp : Application() {

    lateinit var attribution: AttributionPilot
        private set

    override fun onCreate() {
        super.onCreate()

        try {
            FirebaseApp.initializeApp(this)
            val factory = if (BuildConfig.DEBUG)
                DebugAppCheckProviderFactory.getInstance()
            else
                PlayIntegrityAppCheckProviderFactory.getInstance()
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(factory)
        } catch (e: Exception) {
            Tracer.w(TAG, "Firebase not initialised — uplink will still try the POST", e)
        }

        HostGate.warnIfEmpty()
        // Re-arm the campaign hosts a previous session learned from the
        // config endpoint, so a cold push to one of them clears the gate
        // even before config runs again this launch.
        HostGate.seed(FluxStore(this).trustedHosts)

        attribution = AttributionPilot(this)
        attribution.wireUp()
    }

    private companion object { const val TAG = "GoldenFluxApp" }
}
