package com.goldenflux.goldenfluxgame.flux.stage

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.goldenflux.goldenfluxgame.BuildConfig
import com.goldenflux.goldenfluxgame.R
import com.goldenflux.goldenfluxgame.flux.env.Env
import com.goldenflux.goldenfluxgame.flux.link.LinkMonitor
import com.goldenflux.goldenfluxgame.flux.signal.WarmSignal
import com.goldenflux.goldenfluxgame.flux.store.FluxStore
import com.goldenflux.goldenfluxgame.flux.util.Agent
import com.goldenflux.goldenfluxgame.flux.util.Immersive
import com.goldenflux.goldenfluxgame.flux.util.Tracer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The WebView shell. Everything about how a partner page is hosted lives
 * here (kotlin_webview.mdc).
 *
 * Anything a page can do that the shell can not clean up on its own — a hard
 * back below the entry point, a renderer crash, a redirect storm, a scheme
 * the WebView cannot follow — is caught and routed either into recovery
 * (fresh WebView on the entry URL) or out to a native intent.
 */
class StageActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cover: FrameLayout
    private lateinit var store: FluxStore
    private lateinit var link: LinkMonitor
    private lateinit var kb: StageKeyboard
    private val scope = CoroutineScope(Dispatchers.Main)

    private var entryUrl: String = ""
    private var lastNav = 0L
    private var redirectsIn = 0
    private var restarting = false
    private var pickUpload: ValueCallback<Array<Uri>>? = null
    private var wentLive = false

    // Partner chains routinely exceed Chromium's built-in 20-redirect ceiling.
    // When the browser trips ERR_TOO_MANY_REDIRECTS (-9) it commits an error
    // document and the last real page vanishes from the screen. We restart
    // the load from the deepest hop we actually reached, up to [MAX_RETRIES]
    // times, keeping [cover] visible over the WebView the entire time so the
    // user never sees the black background or the browser's error page.
    private var deepestHop: String = ""
    @Volatile private var retryPending = false
    @Volatile private var errorInLoad = false
    private var retriesUsed = 0

    // Offline overlay (shown in-place instead of starting OfflineActivity so
    // the WebView instance and its cookies/DOM stay alive).
    private var offlineOverlay: FrameLayout? = null
    @Volatile private var overlayShown = false
    @Volatile private var recovering = false
    @Volatile private var wentOffline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // adjustResize so the WindowInsetsAnimation callback fires (its
        // frames are what StageKeyboard uses to move the WebView). The IME
        // inset is then stripped out of the tree before it reaches the
        // WebView, so no view actually resizes; StageKeyboard alone moves
        // the page via translationY (kotlin_keyboard.mdc §1). Two parties
        // reacting to the keyboard produce a fight; there is only one here.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Colour behind the WebView. On some GPU drivers a black WebView
        // background renders a transparent gap during scrolling — the safe
        // choice is opaque (pitfalls #4).
        window.setBackgroundDrawable(null)
        window.decorView.setBackgroundColor(Color.BLACK)

        store = FluxStore(applicationContext)
        link  = LinkMonitor(applicationContext)
        entryUrl = resolveEntryUrl() ?: run { finish(); return }

        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)
        applyCutoutMode()
        Immersive.applyTo(this)

        kb = StageKeyboard(root, store)
        kb.install()

        web = buildWebView()
        root.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        kb.attach(web)

        // Cover sits on top of the WebView. It is opaque so the black
        // background of the WebView (and any transient error document) is
        // never visible through it. It comes up now and does not drop until
        // a real page has finished loading.
        cover = buildCover()
        root.addView(cover, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        scheduleCoverTimeout()

        watchInsets()
        watchNetwork()
        startHeartbeat()
        wireWarmPush()
        installBack()

        Tracer.i(TAG, "loading entry URL")
        deepestHop = entryUrl
        web.loadUrl(entryUrl)

        val warmFromRouter = intent.getStringExtra(EXTRA_PUSH_URL)?.takeIf {
            intent.getBooleanExtra(EXTRA_PUSH_WARM, false)
        }
        if (!warmFromRouter.isNullOrBlank()) web.postDelayed({ web.loadUrl(warmFromRouter) }, 250L)
    }

    private fun resolveEntryUrl(): String? {
        val fromIntent = intent.getStringExtra(EXTRA_TARGET_URL)?.takeIf { it.isNotBlank() }
        val fromStore  = store.target?.takeIf { store.targetIsUsable() }
        return fromIntent ?: fromStore
    }

    // ── Cutout / safe-area for the WebView (portrait) ───────────────────────

    private fun applyCutoutMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        window.attributes = window.attributes.apply {
            // Landscape: DEFAULT — the display is letterboxed on the cutout
            // side and the camera hole does not bite into the WebView (the
            // brief's "WebView не должен залезать дальше выреза под камеру").
            // Portrait: SHORT_EDGES — the notch is on the short edge and the
            // page is safe to render edge-to-edge.
            layoutInDisplayCutoutMode = if (landscape)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            else
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyCutoutMode()
        kb.rotate()
        if (overlayShown) {
            // Swap portrait/landscape background image without recreating the activity.
            hideOfflineOverlay()
            overlayShown = true   // keep the state flag — we're rebuilding, not dismissing
            val ov = buildOfflineOverlay()
            offlineOverlay = ov
            root.addView(ov, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
    }

    private fun watchInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            // We use `getInsetsIgnoringVisibility` for the status/nav bars so
            // the WebView keeps a proper safe-area even under immersive-hidden
            // bars — otherwise `getInsets(systemBars())` returns 0 on
            // non-notched devices and the WebView content sits flush with the
            // very top pixel row of the screen (which is the "нет отступа"
            // report). displayCutout() already ignores visibility.
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val cut  = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

            // Native padding: portrait → top (status bar + notch), landscape →
            // left+right (side cameras). Bottom stays 0 — edge-to-edge and
            // the IME is handled by StageKeyboard (kotlin_webview.mdc
            // §"Safe area").
            val landscape =
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val topPad   = if (landscape) 0 else maxOf(bars.top,  cut.top)
            val leftPad  = if (landscape) maxOf(bars.left,  cut.left)  else 0
            val rightPad = if (landscape) maxOf(bars.right, cut.right) else 0
            v.setPadding(leftPad, topPad, rightPad, 0)

            // Page-side CSS variables use the union of both types (matches
            // what `env(safe-area-inset-*)` on the browser side sees).
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val json = "{top:${sys.top},bottom:${sys.bottom},left:${sys.left},right:${sys.right}}"
            web.postDelayed({ postSafeArea(json) }, 40L)
            web.postDelayed({ postSafeArea(json) }, Env.insetReinjectMs)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    private fun postSafeArea(insetsJson: String) {
        val name = BuildConfig.JS_SAFE_AREA_TAG
        val js = """
            try {
              var s = document.documentElement.style;
              var i = $insetsJson;
              s.setProperty('--saab-t', i.top + 'px');
              s.setProperty('--saab-b', i.bottom + 'px');
              s.setProperty('--saab-l', i.left + 'px');
              s.setProperty('--saab-r', i.right + 'px');
              window.$name = i;
              window.dispatchEvent(new Event('${name}_ready'));
            } catch(e) {}
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    // ── Network watch ──────────────────────────────────────────────────────

    private fun watchNetwork() {
        scope.launch {
            link.changes.drop(1).collect { online ->
                if (!online) goOffline("link dropped")
                else if (overlayShown && !recovering) attemptRecovery()
            }
        }
    }

    /** Active probe every heartbeatMs — covers the case where the page is
     *  already loaded and the user turns off the internet: no WebView request
     *  is in flight so onReceivedError never fires (pitfalls #11). */
    private fun startHeartbeat() {
        scope.launch {
            while (true) {
                delay(Env.heartbeatMs)
                if (wentOffline) continue
                if (!link.isConnected()) goOffline("heartbeat: no network")
            }
        }
    }

    private fun goOffline(reason: String) {
        if (wentOffline) return
        wentOffline = true
        Tracer.w(TAG, "$reason → offline overlay")
        // Stop the in-flight request but DO NOT clear the WebView content —
        // we show an in-activity overlay on top, keeping the WebView alive so
        // that when the link returns we can reload seamlessly without the user
        // seeing a fresh black cover from a brand-new Activity.
        runCatching { web.stopLoading() }
        showOfflineOverlay()
    }

    // ── In-activity offline overlay ────────────────────────────────────────

    private fun showOfflineOverlay() {
        if (overlayShown) return
        overlayShown = true
        val ov = buildOfflineOverlay()
        offlineOverlay = ov
        root.addView(ov, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun hideOfflineOverlay() {
        overlayShown = false
        offlineOverlay?.let { root.removeView(it) }
        offlineOverlay = null
    }

    private fun attemptRecovery() {
        if (recovering) return
        recovering = true
        scope.launch {
            val btn = offlineOverlay
                ?.findViewWithTag<android.widget.TextView>(TAG_RETRY_BTN)
            btn?.post { btn.text = getString(R.string.flux_connecting); btn.isEnabled = false }
            val ok = runCatching { link.reachesOutside() }.getOrDefault(false)
            recovering = false
            if (ok) {
                hideOfflineOverlay()
                wentOffline = false
                val url = deepestHop.takeIf { it.isNotBlank() && it != "about:blank" }
                    ?: entryUrl
                loadWithCover(url)
            } else {
                btn?.post { btn.text = getString(R.string.flux_retry); btn.isEnabled = true }
            }
        }
    }

    private fun buildOfflineOverlay(): FrameLayout {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val f = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
        }
        f.addView(ImageView(this).apply {
            setImageResource(
                if (land) R.drawable.flux_offline_landscape else R.drawable.flux_offline_portrait
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
        val btn = buildRetryButton()
        btn.setOnClickListener { attemptRecovery() }
        val bottomMargin = if (land) dp(24) else dp(56)
        f.addView(btn, FrameLayout.LayoutParams(
            dp(220), dp(56), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        ).apply { this.bottomMargin = bottomMargin })
        return f
    }

    private fun buildRetryButton() = TextView(this).apply {
        tag = TAG_RETRY_BTN
        text = getString(R.string.flux_retry)
        textSize = 17f
        gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.08f
        setBackgroundResource(R.drawable.flux_btn_primary)
        setTextColor(0xFF1A0523.toInt())
        setShadowLayer(2f, 0f, 1f, 0x55FFFFFF)
        setPadding(0, 0, 0, 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ── Warm push hand-off ─────────────────────────────────────────────────

    private fun wireWarmPush() {
        // `stageAttached` lives for the entire activity lifetime — it is what
        // lets [WarmSignal.offer] queue a URL from a background push while
        // this shell is off-screen (kotlin_webview.mdc §"Push URL").
        WarmSignal.stageAttached = true
    }

    /**
     * `onLive` is toggled per foreground: set in [onStart], cleared in
     * [onStop]. FluxPushService checks it to decide "hand the URL to the
     * live shell" vs. "post a notification". Keeping it set while the app
     * is in the background would silently absorb pushes the user should
     * have received as tray notifications (guide §"Push URL", kotlin_gray_pitfalls #32).
     */
    override fun onStart() {
        super.onStart()
        WarmSignal.onLive = { url ->
            web.post {
                Tracer.i(TAG, "warm push URL delivered to shell")
                loadWithCover(url)
            }
        }
    }

    override fun onStop() {
        WarmSignal.onLive = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!overlayShown) {
            wentOffline = false
            val queued = WarmSignal.drain()
            if (!queued.isNullOrBlank()) loadWithCover(queued)
        }
    }

    /**
     * A programmatic navigation (initial load, warm push, retry) should
     * always come up behind the cover — otherwise a slow first byte shows
     * a black frame between when we call loadUrl and when Chromium paints.
     */
    private fun loadWithCover(url: String) {
        raiseCover()
        deepestHop = url
        retriesUsed = 0
        errorInLoad = false
        retryPending = false
        redirectsIn = 0
        web.stopLoading()
        web.loadUrl(url)
    }

    // ── Back-navigation ────────────────────────────────────────────────────

    private fun installBack() {
        // Back policy (client brief):
        //   * Overlay showing            → swallow (user can only tap Retry).
        //   * Already on entry page      → swallow (cannot exit the shell).
        //   * Anywhere else              → snap to entry in ONE press.
        //
        // We do NOT use canGoBack()/goBack() step by step because partner
        // SPAs and redirect chains leave many entries in the back-forward
        // list that are invisible to the user — the user would have to spam
        // back dozens of times to reach the entry page, which is confusing.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (overlayShown) return          // offline overlay handles its own UX
                val current = web.url?.trim().orEmpty()
                if (current.isBlank() || current == "about:blank" ||
                    sameUrl(current, entryUrl)
                ) return                           // on entry — swallow
                Tracer.i(TAG, "back: snapping to entry")
                loadWithCover(entryUrl)
            }
        })
    }

    /** Loose URL match — ignores trailing slash and fragment so a page that
     *  redirected `entry` → `entry/` still counts as "we are home". */
    private fun sameUrl(a: String, b: String): Boolean {
        fun norm(u: String) = u.substringBefore('#').trimEnd('/')
        return norm(a).equals(norm(b), ignoreCase = true)
    }

    // ── WebView construction ───────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val v = WebView(this)
        v.setBackgroundColor(Color.BLACK)
        v.overScrollMode = View.OVER_SCROLL_NEVER
        v.isFocusable = true
        v.isFocusableInTouchMode = true

        val s: WebSettings = v.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // false = target="_blank" navigates in the same WebView; true would
        // require a working onCreateWindow which causes a crash if the parent
        // WebView is reused as the popup host (pitfalls #12, #21).
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.userAgentString = Agent.value

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)

        v.webViewClient = shellClient
        v.webChromeClient = shellChrome
        v.setDownloadListener { url, _, _, _, _ ->
            openExternally(url)
        }
        return v
    }

    // ── WebViewClient ──────────────────────────────────────────────────────

    private val shellClient = object : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // Track the newest URL we managed to reach. When the chain trips
            // Chromium's redirect ceiling, this is where we resume from.
            if (url.isNotBlank() && url != "about:blank") deepestHop = url

            // A new load attempt has started (fresh or retry). Errors reset
            // for this attempt; retryPending clears here so that a clean
            // finish of THIS load can drop the cover.
            errorInLoad = false
            retryPending = false

            val now = System.currentTimeMillis()
            redirectsIn = if (now - lastNav < REDIRECT_WINDOW_MS) redirectsIn + 1 else 0
            lastNav = now
        }

        override fun onPageFinished(view: WebView, url: String) {
            wentLive = true
            view.evaluateJavascript(kb.script, null)
            // An error page also produces onPageFinished. Do not treat that
            // as "load done" — the retry loop is still running and dropping
            // the cover here would expose the error document to the user.
            if (errorInLoad || retryPending) return
            retryPending = false
            retriesUsed = 0
            redirectsIn = 0
            dropCover()
        }

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val uri = req.url ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            return when {
                scheme == "http" || scheme == "https" -> false
                scheme == "intent" -> { openIntentUri(uri.toString()); true }
                else -> {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)); true }
                        .getOrElse { e ->
                            if (e is ActivityNotFoundException)
                                Tracer.w(TAG, "no handler for scheme=$scheme")
                            true
                        }
                }
            }
        }

        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
            if (!r.isForMainFrame) return
            val code = e.errorCode
            Tracer.w(TAG, "main frame error $code: ${e.description}")
            errorInLoad = true

            // A custom scheme already dispatched to the system — the page behind
            // it is fine; just ensure the cover is not blocking anything.
            if (code == WebViewClient.ERROR_UNSUPPORTED_SCHEME) {
                retryPending = false
                dropCover()
                return
            }

            // Network connectivity errors: go straight to the offline screen.
            // Using the connectivity-loss codes (-2 HOST_LOOKUP, -6 CONNECT,
            // -7 IO, -8 TIMEOUT as connectivity issue, -11 FILE_NOT_FOUND).
            if (code in NET_ERROR_CODES || !link.isConnected()) {
                goOffline("network error $code in WebView")
                return
            }

            // ERR_TOO_MANY_REDIRECTS (-9) and related codes: resume the chain
            // from the deepest hop reached so far (pitfalls #24, #30).
            if (code == WebViewClient.ERROR_REDIRECT_LOOP ||
                code == WebViewClient.ERROR_BAD_URL ||
                code == WebViewClient.ERROR_UNKNOWN
            ) {
                scheduleRetry(v)
                return
            }

            // Anything else: the page is what it is. Never leave the user
            // under a cover waiting on a load that already failed.
            retryPending = false
            dropCover()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            Tracer.w(TAG, "renderer gone (crash=${detail?.didCrash()}) — rebuilding")
            if (restarting) return true
            restarting = true
            raiseCover()
            root.removeView(view)
            runCatching { view.destroy() }
            web = buildWebView().also {
                // Insert the new WebView UNDER the cover so the cover
                // remains on top during recovery.
                root.addView(
                    it,
                    root.indexOfChild(cover),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                kb.attach(it)
                it.loadUrl(entryUrl)
            }
            deepestHop = entryUrl
            retriesUsed = 0
            errorInLoad = false
            retryPending = false
            restarting = false
            return true
        }
    }

    // ── Retry & loading cover ──────────────────────────────────────────────

    private fun scheduleRetry(view: WebView) {
        if (retriesUsed >= MAX_RETRIES) {
            Tracer.w(TAG, "retry budget exhausted after $retriesUsed attempts — dropping cover")
            retryPending = false
            dropCover()
            return
        }
        retriesUsed++
        retryPending = true
        raiseCover()
        val target = deepestHop.takeIf { it.isNotBlank() && it != "about:blank" } ?: entryUrl
        Tracer.i(TAG, "retry #$retriesUsed from $target")
        view.postDelayed({
            redirectsIn = 0
            errorInLoad = false
            view.stopLoading()
            view.loadUrl(target)
        }, RETRY_DELAY_MS)
    }

    private fun buildCover(): FrameLayout {
        val f = FrameLayout(this).apply {
            setBackgroundColor(COVER_BG)
            isClickable = true
            isFocusable = true
            visibility = View.VISIBLE
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COVER_ACCENT)
        }
        val size = (56 * resources.displayMetrics.density + 0.5f).toInt()
        f.addView(spinner, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        return f
    }

    /** Safety net: if a page hangs and never delivers onPageFinished or
     *  onProgressChanged(100), the cover would stay forever. Drop it
     *  after COVER_MAX_MS (pitfalls #14). */
    private fun scheduleCoverTimeout() {
        cover.postDelayed({
            if (cover.visibility == View.VISIBLE && !retryPending) {
                Tracer.w(TAG, "cover timed out — dropping")
                retryPending = false
                dropCover()
            }
        }, COVER_MAX_MS)
    }

    private fun raiseCover() {
        cover.animate().cancel()
        cover.alpha = 1f
        cover.visibility = View.VISIBLE
    }

    /**
     * The one place the cover is taken down. Guards against dropping on
     * failed loads: [retryPending] is `true` while a retry is scheduled or
     * in flight; [errorInLoad] is `true` while an error document is being
     * committed. Either signal keeps the cover up.
     */
    private fun dropCover() {
        if (retryPending || errorInLoad) return
        if (cover.visibility != View.VISIBLE) return
        cover.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                cover.alpha = 1f
                cover.visibility = View.GONE
            }
            .start()
    }

    // ── WebChromeClient ────────────────────────────────────────────────────

    private val shellChrome = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }

        /** Backstop for pages that report progress but never fire onPageFinished. */
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress == 100 && !retryPending && !errorInLoad) dropCover()
        }

        override fun onShowFileChooser(
            web: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            pickUpload?.onReceiveValue(null)
            pickUpload = filePathCallback
            val types = fileChooserParams.acceptTypes.filter { it.isNotBlank() }
            val mime = if (types.size == 1) types[0] else "*/*"
            return runCatching { uploadPicker.launch(mime); true }.getOrDefault(false)
        }
    }

    private val uploadPicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        pickUpload?.onReceiveValue(uris.toTypedArray())
        pickUpload = null
    }

    // ── External URL routing ───────────────────────────────────────────────

    /** Opens any URL outside the WebView (payment apps, market, etc.). */
    private fun openExternally(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * intent:// URIs name a target app and carry a browser_fallback_url. Try
     * the named app → generic intent → fallback URL before giving up
     * (pitfalls #22).
     */
    private fun openIntentUri(url: String) {
        val parsed = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return
        val fallback = parsed.getStringExtra("browser_fallback_url")
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
        parsed.component = null
        parsed.selector = null

        if (runCatching { startActivity(parsed) }.isSuccess) return
        parsed.`package` = null
        if (runCatching { startActivity(parsed) }.isSuccess) return
        if (!fallback.isNullOrBlank()) web.loadUrl(fallback)
    }

    // ── Lifecycle bookkeeping ──────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Swallow BACK while the first page has not committed yet — we
        // must never close the shell before it manages to draw anything.
        // OnBackPressedCallback takes over once [wentLive] flips true.
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_BACK && !wentLive
        ) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        WarmSignal.onLive = null
        WarmSignal.stageAttached = false
        kb.forget()
        scope.cancel()
        offlineOverlay = null
        runCatching {
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StageActivity"
        const val EXTRA_TARGET_URL = "flux_stage_url"
        const val EXTRA_PUSH_URL   = "flux_stage_push"
        const val EXTRA_PUSH_WARM  = "flux_stage_warm"
        private const val REDIRECT_WINDOW_MS = 1_500L
        private const val MAX_RETRIES = 6
        private const val RETRY_DELAY_MS = 60L
        private const val COVER_MAX_MS = 20_000L
        private const val COVER_BG = 0xFF0B0B0F.toInt()
        private const val COVER_ACCENT = 0xFFF2C464.toInt()
        private const val TAG_RETRY_BTN = "flux_retry_btn"
        private val NET_ERROR_CODES = setOf(-2, -6, -7, -11)
    }
}
