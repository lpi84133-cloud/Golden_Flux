package com.goldenflux.goldenfluxgame.flux.entry

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.goldenflux.goldenfluxgame.BuildConfig
import com.goldenflux.goldenfluxgame.GoldenFluxApp
import com.goldenflux.goldenfluxgame.MainActivity
import com.goldenflux.goldenfluxgame.flux.env.Env
import com.goldenflux.goldenfluxgame.flux.env.StageDecision
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
class LaunchArbiter : AppCompatActivity() {

    private lateinit var store: FluxStore
    private lateinit var link: LinkMonitor
    private var splash: LaunchSplash? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen MUST run before super.onCreate — the compat
        // library needs to swap the launch theme back to postSplashScreenTheme
        // before AppCompat draws its first frame. On API < 31 the call
        // degrades to a no-op after the theme swap.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        store = FluxStore(applicationContext)
        link  = LinkMonitor(applicationContext)

        val pushed = extractPushUrl(intent)

        // 0. Debug URL override — must come FIRST, before every early exit.
        //    A NATIVE-locked or warm-STREAM install still needs a way to
        //    reach the shell from a test build without clearing app data.
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

        // Warm-tap hand-off: the shell is still alive, take the user right
        // back to the page they were on and skip the splash entirely.
        if (pushed != null && store.stage == FluxStore.Stage.STREAM &&
            WarmSignal.offer(pushed)
        ) {
            Tracer.i(TAG, "warm push handed to live stage")
            finish()
            return
        }

        // NATIVE lock: once native, stay native. Two established exceptions
        // to the invariant:
        //   * DEBUG builds — developers need to retry OneLink / config
        //     without uninstalling.
        //   * A cold push URL — if the server sent a targeted URL for this
        //     install, honour it: the user tapped a notification to reach
        //     that page and dropping them into the game breaks the very
        //     contract of push routing. The stage flips to STREAM in the
        //     cold-push branch of route().
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

        // First-frame offline: link was off when the app started, no push
        // extras, install still undecided. No splash for a decision that
        // will not be made (kotlin_launch_flow.mdc §4).
        if (pushed == null &&
            store.stage == FluxStore.Stage.UNKNOWN &&
            !link.isConnected()
        ) {
            Tracer.i(TAG, "first run offline → offline screen first frame")
            startActivity(Intent(this, OfflineActivity::class.java))
            finish()
            return
        }

        val splashView = LaunchSplash(this, indeterminate = true) { /* never finishes on its own */ }
        splash = splashView
        setContentView(splashView)
        Immersive.applyTo(this)

        if (pushed != null) {
            Tracer.i(TAG, "cold push URL captured: $pushed")
            store.pendingColdPush = pushed
        }

        scope.launch { route() }
    }

    // ── State machine ───────────────────────────────────────────────────────

    private suspend fun route() {
        // debugForceUrl was already handled in onCreate. Do not duplicate here
        // or two openStage() would race.
        val cold = store.pendingColdPush
        if (!cold.isNullOrBlank()) {
            // FluxPushService already validated the URL through HostGate
            // before persisting it, so we trust it here. Re-checking with a
            // cold HostGate seed has caused hosts learned via the config
            // endpoint in a previous session to fail on cold start — the
            // user then landed on the last-known URL instead of the pushed
            // one (guide: "cold push consumed BEFORE the state machine").
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
                // Persistence gates. Only lock a user into NATIVE when we are
                // sure the answer will not change on a future launch: (i)
                // the endpoint really answered, (ii) attribution actually
                // carried data, and (iii) that data was non-Organic. An
                // Organic first read on a fresh OneLink install is a race
                // — Play Install Referrer often lands *after* our ignite
                // and the second launch will attribute correctly, so we
                // deliberately keep the decision open.
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

        val savedUrl = if (store.targetIsUsable()) store.target else null

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

    /**
     * Instant connectivity check, then a graceful wait if the link is still
     * coming up. Nothing outside this function may reorder the two.
     */
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

    // ── Navigation ──────────────────────────────────────────────────────────

    private fun handOff(go: () -> Unit) {
        val view = splash
        if (view == null) go() else view.complete { if (!isFinishing) go() }
    }

    private fun handOffToGame() = handOff {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    /**
     * The single gateway to the shell. It always consults
     * [FluxStore.shouldOfferPromo] — so the notification-permission screen
     * appears on the first STREAM decision, and again after the snooze
     * (PROMO_DEFER_SEC, ~3 days) has expired, regardless of whether the
     * launch was a normal first-run, a return-STREAM or a cold-push tap.
     * The template's `WelcomePortal.goGray` is a single gateway for the
     * same reason (kotlin_launch_flow.mdc §"Notification prompt").
     */
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

    // ── Push URL resolver (pitfalls #32) ────────────────────────────────────

    /**
     * A tap on a notification reaches this activity via one of two shapes.
     *
     * A message that carries only `data` is delivered to [FluxPushService];
     * the service builds its own PendingIntent with our extras, so the
     * `EXTRA_FROM_PUSH` flag is set and `EXTRA_PUSH_URL` is present.
     *
     * A message that carries a `notification` block is drawn by Firebase's
     * own service whenever the app is not in the foreground — our service is
     * NEVER called on that path. The tap opens the launcher's stock intent,
     * with the `data` payload arriving as raw string extras. So the router
     * has to read *both* — the flag-driven pair and the raw keys — and pass
     * the winner through [HostGate].
     */
    private fun extractPushUrl(source: Intent): String? {
        val own = if (source.getBooleanExtra(EXTRA_FROM_PUSH, false))
            source.getStringExtra(EXTRA_PUSH_URL)?.trim() else null
        val raw = (source.getStringExtra(FCM_KEY_URL)
            ?: source.getStringExtra(FCM_KEY_LINK))?.trim()
        val candidate = own?.takeIf { it.isNotBlank() }
            ?: raw?.takeIf { it.isNotBlank() }
            ?: return null
        return candidate.takeIf { HostGate.admits(it) }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        val pushed = extractPushUrl(newIntent) ?: return
        when (store.stage) {
            FluxStore.Stage.NATIVE -> {
                // Cold-push override extends to onNewIntent too: a NATIVE
                // user who taps a targeted URL still deserves to reach it.
                // Flip to STREAM and route as a fresh cold push.
                Tracer.w(TAG, "NATIVE unlocked by push tap — flipping to STREAM")
                store.stage = FluxStore.Stage.UNKNOWN
                store.pendingColdPush = pushed
                startActivity(
                    Intent(this, LaunchArbiter::class.java)
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
                // A push arrived before any decision was made. The launcher
                // is not on screen (this activity finished on the first
                // route), so we treat this as a fresh cold launch: persist
                // the URL and re-enter the router.
                store.pendingColdPush = pushed
                startActivity(
                    Intent(this, LaunchArbiter::class.java)
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
        private const val TAG = "LaunchArbiter"
        const val EXTRA_FROM_PUSH = "flux_from_push"
        const val EXTRA_PUSH_URL  = "flux_push_url"

        // Keys FCM SDK forwards verbatim on a tap when it drew the notification.
        private const val FCM_KEY_URL  = "url"
        private const val FCM_KEY_LINK = "link"
    }
}
