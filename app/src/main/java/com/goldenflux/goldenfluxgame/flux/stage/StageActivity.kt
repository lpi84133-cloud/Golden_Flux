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
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.goldenflux.goldenfluxgame.BuildConfig
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

        watchInsets()
        watchNetwork()
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
    }

    private fun watchInsets() {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val json = "{top:${sys.top},bottom:${sys.bottom},left:${sys.left},right:${sys.right}}"
            web.postDelayed({ postSafeArea(json) }, 40L)
            web.postDelayed({ postSafeArea(json) }, Env.insetReinjectMs)
            insets
        }
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
                if (!online) {
                    Tracer.w(TAG, "link dropped — offline screen with return URL")
                    startActivity(
                        Intent(this@StageActivity, OfflineActivity::class.java)
                            .putExtra(OfflineActivity.EXTRA_RETURN_URL, entryUrl)
                    )
                    finish()
                }
            }
        }
    }

    // ── Warm push hand-off ─────────────────────────────────────────────────

    private fun wireWarmPush() {
        WarmSignal.stageAttached = true
        WarmSignal.onLive = { url ->
            web.post {
                Tracer.i(TAG, "warm push URL delivered to shell")
                loadWithCover(url)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val queued = WarmSignal.drain()
        if (!queued.isNullOrBlank()) loadWithCover(queued)
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else { moveTaskToBack(true) }
            }
        })
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
        s.setSupportMultipleWindows(true)
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
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
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
            // No mid-chain intervention here. Chromium's own 20-redirect
            // ceiling terminates the chain via onReceivedError, which is
            // where we handle recovery. Intervening in onPageStarted based
            // on a per-install budget was the source of the black flash.
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
            val url = req.url ?: return false
            return route(url)
        }

        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
            if (!r.isForMainFrame) return
            val code = e.errorCode
            Tracer.w(TAG, "main frame error $code: ${e.description}")
            errorInLoad = true

            // ERROR_REDIRECT_LOOP (Chromium -9, ERR_TOO_MANY_REDIRECTS) is
            // the expected outcome for partner chains longer than 20 hops.
            // Some devices also surface -12 (ERROR_BAD_URL) or a bare -9 on
            // certain WebView versions. Recover from any main-frame failure
            // by resuming from the deepest hop we managed to reach.
            if (code == WebViewClient.ERROR_REDIRECT_LOOP ||
                code == WebViewClient.ERROR_TIMEOUT ||
                code == WebViewClient.ERROR_BAD_URL ||
                code == WebViewClient.ERROR_UNKNOWN
            ) {
                scheduleRetry(v)
            } else {
                // Non-recoverable error (bad host, TLS failure, etc.). Drop
                // the cover so the user sees whatever Chromium put up rather
                // than an eternal spinner.
                retryPending = false
                dropCover()
            }
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

        private fun route(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme == "http" || scheme == "https") return false
            return runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            }.getOrElse { e ->
                if (e is ActivityNotFoundException)
                    Tracer.w(TAG, "no handler for scheme=$scheme")
                true
            }
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
            isClickable = true       // swallow taps so the user cannot pat
            isFocusable = true       // the error page beneath the cover
            visibility = View.VISIBLE
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COVER_ACCENT)
        }
        val size = (56 * resources.displayMetrics.density + 0.5f).toInt()
        f.addView(
            spinner,
            FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        )
        return f
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

    // ── Lifecycle bookkeeping ──────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A hardware back before the shell has produced its first frame is
        // typically a user impatiently escaping the launch — take them out
        // of the app instead of leaving them on a black WebView.
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_BACK && !wentLive
        ) {
            moveTaskToBack(true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        WarmSignal.onLive = null
        WarmSignal.stageAttached = false
        kb.forget()
        scope.cancel()
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
        private const val COVER_BG = 0xFF0B0B0F.toInt()
        private const val COVER_ACCENT = 0xFFF2C464.toInt()
    }
}
