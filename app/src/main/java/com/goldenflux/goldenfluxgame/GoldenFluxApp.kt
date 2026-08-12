package com.goldenflux.goldenfluxgame

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.goldenflux.goldenfluxgame.flux.store.FluxStore
import com.goldenflux.goldenfluxgame.flux.uplink.AttributionPilot
import com.goldenflux.goldenfluxgame.flux.util.HostGate
import com.goldenflux.goldenfluxgame.flux.util.Tracer
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Responsibilities:
 *
 *  * Wire Firebase and App Check.
 *  * [AttributionPilot.wireUp] before any activity runs (pitfalls #19).
 *  * Prime FCM eagerly (channel + token). Lazy-initialising these inside
 *    [flux.signal.FluxPushService.onMessageReceived] means the very first
 *    push after a fresh install may arrive before we ever asked for a token,
 *    or before the channel exists on the device — the OS then silently drops
 *    the notification. Doing it here matches Flutter template's
 *    `PushHub.boot()` being called first in the router pipeline.
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
        HostGate.seed(FluxStore(this).trustedHosts)

        ensurePushChannel()
        primeFcmToken()

        attribution = AttributionPilot(this)
        attribution.wireUp()
    }

    /** Create the notification channel now so a background push that lands
     *  before the shell ever runs still shows up in the tray. */
    private fun ensurePushChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (nm.getNotificationChannel(BuildConfig.PUSH_CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            BuildConfig.PUSH_CHANNEL_ID,
            BuildConfig.PUSH_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableLights(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    /** Ask FCM for a token in the background. Silent no-op on failure; the
     *  next call site (uplink POST) will retry. The important part is that
     *  by the time askUplink runs, the token has usually already resolved
     *  and been cached in the store — which fixes the "no notifications
     *  after offline retry" bug (the token used to be fetched inside
     *  askUplink with a 5-second timeout, and often lost the race). */
    private fun primeFcmToken() {
        val store = FluxStore(this)
        if (!store.fcmToken.isNullOrBlank()) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                runCatching {
                    if (task.isSuccessful) {
                        val t = task.result
                        if (!t.isNullOrBlank()) store.fcmToken = t
                    } else {
                        val err = task.exception
                        if (err != null) Tracer.w(TAG, "priming FCM token failed", err)
                        else Tracer.w(TAG, "priming FCM token failed (no exception)")
                    }
                }
            }
        }
    }

    private companion object { const val TAG = "GoldenFluxApp" }
}
