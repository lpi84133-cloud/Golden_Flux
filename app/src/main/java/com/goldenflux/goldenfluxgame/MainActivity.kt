package com.goldenflux.goldenfluxgame

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.goldenflux.goldenfluxgame.BuildConfig
import com.goldenflux.goldenfluxgame.flux.env.Env
import com.goldenflux.goldenfluxgame.flux.env.StageDecision
import com.goldenflux.goldenfluxgame.flux.entry.LaunchSplash
import com.goldenflux.goldenfluxgame.flux.link.LinkMonitor
import com.goldenflux.goldenfluxgame.flux.signal.WarmSignal
import com.goldenflux.goldenfluxgame.flux.stage.OfflineActivity
import com.goldenflux.goldenfluxgame.flux.stage.PromoActivity
import com.goldenflux.goldenfluxgame.flux.stage.StageActivity
import com.goldenflux.goldenfluxgame.flux.store.FluxStore
import com.goldenflux.goldenfluxgame.flux.uplink.UplinkClient
import com.goldenflux.goldenfluxgame.flux.util.HostGate
import com.goldenflux.goldenfluxgame.flux.util.Immersive
import com.goldenflux.goldenfluxgame.flux.util.Tracer
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Launcher activity + routing state machine. See kotlin_gray_guide.mdc for
 * the tree, kotlin_launch_flow.mdc for the *when*, and kotlin_gray_pitfalls
 * for what any given branch is defending against.
 *
 *  UNKNOWN (first launch)
 *    * offline & no push → OfflineActivity on the first frame. Nothing
 *      started, nothing persisted. The offline screen relaunches this
 *      Activity when the link is back (pitfalls #20).
 *    * has internet → ignite AppsFlyer → attribution + deep link → uplink
 *      POST → decide.
 *        ok+url    → STREAM → optional PromoActivity → StageActivity
 *        else      → the game. Persist NATIVE only when the endpoint
 *                    actually answered AND the request carried real
 *                    attribution (kotlin_launch_flow.mdc §5).
 *
 *  STREAM (was WebView last time)
 *    * cold push → StageActivity on the pushed URL.
 *    * offline → OfflineActivity with the saved URL.
 *    * else → refresh attribution → uplink; on any failure fall back to the
 *      saved URL if we still have one, otherwise OfflineActivity.
 *
 *  NATIVE (was the game last time)
 *    * The game, always. Once native, stay native (kotlin_launch_flow.mdc
 *      §invariant), including if a push URL arrives for this install.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: FluxStore
    private lateinit var link: LinkMonitor
    private var splash: LaunchSplash? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        store = FluxStore(applicationContext)
        link  = LinkMonitor(applicationContext)

        val pushed = extractPushUrl(intent)

        val forcedNow = Env.debugForceUrl
        if (BuildConfig.DEBUG && forcedNow.isNotBlank()) {
            Tracer.w(TAG, "DEBUG: forcing stage URL (bypassing all state)")
            val splashView = LaunchSplash(this, indeterminate = true) {}
            splash = splashView
            setContentView(splashView)
            Immersive.applyTo(this)
            scope.launch { openStage(forcedNow) }
            return
        }

        if (pushed != null && store.stage == FluxStore.Stage.STREAM &&
            WarmSignal.offer(pushed)
        ) {
            Tracer.i(TAG, "warm push handed to live stage")
            finish()
            return
        }

        if (store.stage == FluxStore.Stage.NATIVE && pushed == null && !BuildConfig.DEBUG) {
            Tracer.i(TAG, "returning NATIVE — straight to the game")
            setContentView(LaunchSplash(this, indeterminate = true) {})
            Immersive.applyTo(this)
            scope.launch { handOffToGame() }
            return
        }
        if (store.stage == FluxStore.Stage.NATIVE && BuildConfig.DEBUG) {
            Tracer.w(TAG, "DEBUG: NATIVE lock ignored — re-routing from scratch")
            store.stage = FluxStore.Stage.UNKNOWN
        }
        if (store.stage == FluxStore.Stage.NATIVE && pushed != null) {
            Tracer.w(TAG, "NATIVE unlocked by cold push URL — flipping to STREAM")
            store.stage = FluxStore.Stage.UNKNOWN
        }

        // The offline first-frame branch must yield to any pending push. Two
        // channels can carry it: the current tap intent (already reflected in
        // `pushed`) OR a URL that FCM stashed while we were offline. Ignoring
        // the latter reproduces the "tap notification from the No-WiFi screen
        // → land back on No-WiFi" glitch: `link.isConnected()` briefly reads
        // false while Android is validating the just-connected Wi-Fi, so
        // without this guard MainActivity re-enters OfflineActivity in a loop
        // even though a targeted URL is waiting to be honoured.
        val hasPendingCold = !store.pendingColdPush.isNullOrBlank()
        if (pushed == null && !hasPendingCold &&
            store.stage == FluxStore.Stage.UNKNOWN &&
            !link.isConnected()
        ) {
            Tracer.i(TAG, "first run offline → offline screen first frame")
            startActivity(Intent(this, OfflineActivity::class.java))
            finish()
            return
        }

        val splashView = LaunchSplash(this, indeterminate = true) {}
        splash = splashView
        setContentView(splashView)
        Immersive.applyTo(this)

        if (pushed != null) {
            Tracer.i(TAG, "cold push URL captured: $pushed")
            store.pendingColdPush = pushed
        }

        scope.launch { route() }
    }

    private suspend fun route() {
        val cold = store.pendingColdPush
        if (!cold.isNullOrBlank()) {
            Tracer.i(TAG, "cold push URL → shell directly: $cold")
            store.pendingColdPush = null
            if (store.stage == FluxStore.Stage.UNKNOWN) store.stage = FluxStore.Stage.STREAM
            openStage(cold)
            return
        }
        when (store.stage) {
            FluxStore.Stage.NATIVE  -> handOffToGame()
            FluxStore.Stage.STREAM  -> continueStream()
            FluxStore.Stage.UNKNOWN -> firstLaunch()
        }
    }

    private suspend fun firstLaunch() {
        if (!ensureLink(cold = true)) return

        val pilot = (application as GoldenFluxApp).attribution
        pilot.ignite(this)
        pilot.rekindle(this)
        val attribution = pilot.awaitAttribution(Env.attributionColdMs)

        when (val outcome = askUplink(attribution)) {
            is StageDecision.Stream -> {
                Tracer.i(TAG, "backend → STREAM: ${outcome.url}")
                store.stage       = FluxStore.Stage.STREAM
                store.target      = outcome.url
                store.validUntil  = outcome.expiresAt
                store.addTrustedHost(HostGate.remember(outcome.url))
                openStage(outcome.url)
            }
            is StageDecision.Native -> {
                val status = attribution["af_status"]?.toString().orEmpty()
                val organic = status.equals("Organic", ignoreCase = true)
                when {
                    !outcome.answered ->
                        Tracer.i(TAG, "endpoint unreachable → game, decision left open")
                    attribution.isEmpty() ->
                        Tracer.i(TAG, "no attribution behind the answer → game, decision open")
                    organic ->
                        Tracer.i(TAG, "Organic first read → game, decision open (retry next launch)")
                    else -> {
                        store.stage = FluxStore.Stage.NATIVE
                        Tracer.i(TAG, "backend → NATIVE (media_source=$status)")
                    }
                }
                handOffToGame()
            }
        }
    }

    private suspend fun continueStream() {
        if (!ensureLink(cold = false)) return

        val cold = store.takeColdPush()
        if (!cold.isNullOrBlank()) {
            openStage(cold); return
        }

        // Always keep the stored URL as a fallback even if TTL has expired.
        // TTL means "prefer a fresh answer from the gate", not "refuse the
        // cached URL as a last resort". Flutter template: readCachedLink()
        // has no TTL guard — it falls through to _toWeb(cached) on any
        // non-Stream reply, which is the only path that checks
        // shouldOfferPushInvite(). Guarding savedUrl by targetIsUsable()
        // was silently breaking the 3-day promo re-show: if the user moved
        // the clock forward (or the TTL genuinely expired) AND the gate
        // returned non-Stream, savedUrl became null → OfflineActivity was
        // launched instead of openStage(), so shouldOfferPromo() was never
        // evaluated and the promo screen was skipped entirely.
        val savedUrl = store.target

        val pilot = (application as GoldenFluxApp).attribution
        pilot.ignite(this)
        pilot.rekindle(this)
        val attribution = pilot.awaitAttribution(Env.attributionWarmMs)

        val outcome = askUplink(attribution)
        when {
            outcome is StageDecision.Stream -> {
                store.target      = outcome.url
                store.validUntil  = outcome.expiresAt
                store.addTrustedHost(HostGate.remember(outcome.url))
                openStage(outcome.url)
            }
            !savedUrl.isNullOrBlank() -> openStage(savedUrl)
            else -> handOff {
                startActivity(Intent(this, OfflineActivity::class.java))
                finish()
            }
        }
    }

    private suspend fun ensureLink(cold: Boolean): Boolean {
        if (link.isConnected()) return true

        val gate = Channel<Boolean>(Channel.CONFLATED)
        val watcher: Job = scope.launch {
            link.changes.collect { gate.trySend(it) }
        }
        val online = try {
            withTimeoutOrNull(Env.linkGraceMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val listener = scope.launch {
                        for (v in gate) if (v) { cont.resume(true); break }
                    }
                    cont.invokeOnCancellation { listener.cancel() }
                }
            } == true
        } finally {
            watcher.cancel()
            gate.close()
        }
        if (online) return true

        val savedUrl = if (!cold && store.targetIsUsable()) store.target else null
        startActivity(
            Intent(this, OfflineActivity::class.java).apply {
                if (!savedUrl.isNullOrBlank())
                    putExtra(OfflineActivity.EXTRA_RETURN_URL, savedUrl)
            }
        )
        finish()
        return false
    }

    private suspend fun askUplink(attribution: Map<String, Any?>): StageDecision {
        val pilot = (application as GoldenFluxApp).attribution
        val fcm = store.fcmToken ?: fetchFcmToken()?.also { store.fcmToken = it }
        val body = pilot.composeRequest(
            attribution = attribution,
            os = "Android",
            locale = Locale.getDefault().toLanguageTag().replace('-', '_'),
            pushToken = fcm,
            firebaseProject = Env.firebaseProject
        )
        return UplinkClient().exchange(body)
    }

    private fun handOff(go: () -> Unit) {
        val view = splash
        if (view == null) go() else view.complete { if (!isFinishing) go() }
    }

    private fun handOffToGame() = handOff {
        startActivity(
            Intent(this, GameActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun openStage(url: String) = handOff {
        val next = if (store.shouldOfferPromo()) PromoActivity::class.java
                   else StageActivity::class.java
        val extraKey = if (next == PromoActivity::class.java)
            PromoActivity.EXTRA_TARGET_URL else StageActivity.EXTRA_TARGET_URL
        startActivity(
            Intent(this, next)
                .putExtra(extraKey, url)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun extractPushUrl(source: Intent): String? {
        // Intents authored by our own FluxPushService carry EXTRA_FROM_PUSH.
        // These URLs came from our authenticated FCM backend — trust them
        // unconditionally (Flutter parity: push_hub.dart._onColdTap has no
        // gate). Also promote the host into HostGate + persistence so any
        // downstream check (WebView navigation, later pushes) admits it.
        val fromOwnService = source.getBooleanExtra(EXTRA_FROM_PUSH, false)
        val own = if (fromOwnService)
            source.getStringExtra(EXTRA_PUSH_URL)?.trim() else null
        if (fromOwnService && !own.isNullOrBlank()) {
            HostGate.remember(own)?.let { store.addTrustedHost(it) }
            return own
        }
        // Fallback: an FCM data payload delivered to a foreground activity
        // by the OS (no `notification` block). Same rationale — the sender
        // is authenticated, so trust it.
        val raw = (source.getStringExtra(FCM_KEY_URL)
            ?: source.getStringExtra(FCM_KEY_LINK))?.trim()
        if (!raw.isNullOrBlank()) {
            HostGate.remember(raw)?.let { store.addTrustedHost(it) }
            return raw
        }
        return null
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        val pushed = extractPushUrl(newIntent) ?: return
        when (store.stage) {
            FluxStore.Stage.NATIVE -> {
                Tracer.w(TAG, "NATIVE unlocked by push tap — flipping to STREAM")
                store.stage = FluxStore.Stage.UNKNOWN
                store.pendingColdPush = pushed
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
                return
            }
            FluxStore.Stage.STREAM -> {
                if (WarmSignal.offer(pushed)) { finish(); return }
                val fallback = store.target?.takeIf { store.targetIsUsable() }
                startActivity(
                    Intent(this, StageActivity::class.java)
                        .putExtra(StageActivity.EXTRA_TARGET_URL, fallback ?: pushed)
                        .putExtra(StageActivity.EXTRA_PUSH_URL, pushed)
                        .putExtra(StageActivity.EXTRA_PUSH_WARM, true)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                finish()
            }
            FluxStore.Stage.UNKNOWN -> {
                store.pendingColdPush = pushed
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                finish()
            }
        }
    }

    private suspend fun fetchFcmToken(): String? =
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_FROM_PUSH = "flux_from_push"
        const val EXTRA_PUSH_URL  = "flux_push_url"

        private const val FCM_KEY_URL  = "url"
        private const val FCM_KEY_LINK = "link"
    }
}
