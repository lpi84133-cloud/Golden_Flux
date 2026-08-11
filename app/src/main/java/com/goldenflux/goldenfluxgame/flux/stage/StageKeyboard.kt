package com.goldenflux.goldenfluxgame.flux.stage

import android.content.res.Configuration
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.goldenflux.goldenfluxgame.BuildConfig
import com.goldenflux.goldenfluxgame.flux.store.FluxStore
import kotlin.math.max
import kotlin.math.min

/**
 * Slides the WebView clear of the keyboard without shortening it.
 *
 * Full background in kotlin_keyboard.mdc. In one paragraph:
 *
 *  - `adjustResize` is left in the manifest so the animation callback fires,
 *    but the IME insets are stripped before they reach the WebView (see
 *    [rebuildWithoutIme]). One party moves the page — this one — never two.
 *  - Field position comes from the page over a per-install `@JavascriptInterface`
 *    named from `BuildConfig.JS_BRIDGE_NAME`, in device pixels rather than
 *    viewport fractions.
 *  - Mid-animation IME heights are notoriously above the settled one, so the
 *    pan is clamped to a height an earlier opening actually came to rest at,
 *    remembered per orientation and persisted in [FluxStore] so even the
 *    first opening of a session is exact.
 */
class StageKeyboard(private val host: View, private val store: FluxStore) {

    private var web: WebView? = null

    private var fieldTop = -1f
    private var fieldBottom = -1f
    private var isFrame = false

    private var kbHeight = 0
    private var riding = false

    private var declared = 0
    private var settled  = 0

    private val poke = Runnable {
        val name = BuildConfig.JS_KEYBOARD_TAG + "Poll"
        web?.evaluateJavascript("window.$name && window.$name();", null)
    }

    fun install() {
        ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
            if (!riding) {
                onSettle(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                apply(animated = kbHeight > 0)
                if (kbHeight > 0) probe(SETTLE_MS)
            }
            rebuildWithoutIme(insets)
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            host,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {

                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0)
                        riding = true
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0)
                        declared = bounds.upperBound.bottom
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    running: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    if (riding) {
                        onRise(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                        apply(animated = false)
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                    riding = false
                    declared = 0
                    ViewCompat.requestApplyInsets(host)
                    if (kbHeight > 0) probe(SETTLE_MS) else apply(animated = false)
                }
            }
        )

        ViewCompat.requestApplyInsets(host)
    }

    /** Called on every WebView the shell puts on screen, replacements included. */
    fun attach(view: WebView) {
        web = view
        forget()
        view.translationY = 0f
        view.addJavascriptInterface(Bridge(), BuildConfig.JS_BRIDGE_NAME)
    }

    fun forget() {
        host.removeCallbacks(poke)
        fieldTop = -1f
        fieldBottom = -1f
        isFrame = false
        apply(animated = false)
    }

    fun rotate() {
        forget()
        settled = 0
        if (kbHeight > 0) probe(SETTLE_MS)
    }

    private fun probe(delay: Long) {
        host.removeCallbacks(poke)
        host.postDelayed(poke, delay)
    }

    private fun onSettle(px: Int) {
        kbHeight = px
        if (px <= 0 || px == settled) return
        settled = px
        store.rememberKeyboardRest(isUpright(), px)
    }

    private fun onRise(px: Int) {
        val rest = restingHeight()
        kbHeight = if (rest > 0) min(px, rest) else px
    }

    private fun restingHeight(): Int {
        if (settled <= 0) {
            val kept = store.keyboardRest(isUpright())
            if (kept in 1 until host.height) settled = kept
        }
        return if (settled > 0) settled else declared
    }

    private fun isUpright(): Boolean =
        host.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    /** Zeros out the IME inset the WebView is shown. */
    private fun rebuildWithoutIme(insets: WindowInsetsCompat): WindowInsetsCompat =
        runCatching {
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), false)
                .build()
        }.getOrDefault(insets)

    private fun apply(animated: Boolean) {
        val view = web ?: return
        val target = -offset(view)
        view.animate().cancel()
        if (animated && view.translationY != target) {
            view.animate().translationY(target).setDuration(PAN_MS).start()
        } else {
            view.translationY = target
        }
    }

    private fun offset(view: View): Float {
        val h = kbHeight
        val span = view.height
        if (h <= 0 || span <= 0 || fieldBottom < 0f) return 0f
        val aim: Float
        val ceiling: Float
        if (isFrame) {
            aim = fieldBottom
            ceiling = min(h.toFloat(), max(0f, fieldTop))
        } else {
            aim = min(fieldBottom, fieldTop + HEAD_DP * view.resources.displayMetrics.density)
            ceiling = h.toFloat()
        }
        return (aim - (span - h)).coerceIn(0f, ceiling)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun focus(frame: Boolean, top: Double, bottom: Double) {
            val view = web ?: return
            view.post {
                isFrame = frame
                fieldTop = top.toFloat()
                fieldBottom = bottom.toFloat()
                if (kbHeight > 0) apply(animated = !riding)
            }
        }
    }

    /** JS injected on every onPageFinished. */
    val script: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val tag     = BuildConfig.JS_KEYBOARD_TAG
        val docTag  = tag + "D"
        val bridge  = BuildConfig.JS_BRIDGE_NAME
        """
        (function(){
          if (window.$tag) return;
          window.$tag = true;
          function editable(el){
            if (!el) return false;
            var tag = el.tagName;
            if (tag === 'INPUT') {
              var t = (el.type || 'text').toLowerCase();
              return t !== 'checkbox' && t !== 'radio' && t !== 'button' &&
                     t !== 'submit' && t !== 'reset' && t !== 'file' &&
                     t !== 'range' && t !== 'image' && t !== 'color';
            }
            return tag === 'TEXTAREA' || el.isContentEditable === true;
          }
          function box(el, win){
            if (el.isContentEditable) {
              try {
                var sel = win.getSelection();
                if (sel && sel.rangeCount) {
                  var r = sel.getRangeAt(0).getBoundingClientRect();
                  if (r && r.height > 0) return r;
                }
              } catch(e) {}
            }
            return el.getBoundingClientRect();
          }
          function locate(){
            var el = document.activeElement, win = window, off = 0, depth = 0;
            while (el && (el.tagName === 'IFRAME' || el.tagName === 'FRAME') && depth++ < 4) {
              var outline = el.getBoundingClientRect(), doc = null;
              try { doc = el.contentDocument; } catch(e) { doc = null; }
              var inner = doc ? doc.activeElement : null;
              if (!inner || inner === doc.body) {
                return { frame: true, top: off + outline.top, bottom: off + outline.bottom };
              }
              watch(doc);
              win = el.contentWindow || win;
              off += outline.top;
              el = inner;
            }
            if (!editable(el)) return null;
            var r = box(el, win);
            return { frame: false, top: off + r.top, bottom: off + r.bottom };
          }
          function report(){
            var at = locate();
            if (!at) return;
            var vv = window.visualViewport;
            var lift = vv ? vv.offsetTop : 0;
            var zoom = (vv && vv.scale) ? vv.scale : 1;
            var px = (window.devicePixelRatio || 1) * zoom;
            try {
              $bridge.focus(at.frame, (at.top - lift) * px, (at.bottom - lift + $MARGIN_CSS) * px);
            } catch(e) {}
          }
          function kick(){ report(); setTimeout(report, 200); }
          var queued = false;
          function soon(){
            if (queued) return; queued = true;
            var run = function(){ queued = false; report(); };
            if (window.requestAnimationFrame) requestAnimationFrame(run);
            else setTimeout(run, 16);
          }
          if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', soon);
            window.visualViewport.addEventListener('scroll', soon);
          }
          function watch(doc){
            try {
              if (!doc || doc.$docTag) return;
              doc.$docTag = true;
              doc.addEventListener('focusin', kick, true);
            } catch(e) {}
          }
          window.${tag}Poll = report;
          watch(document);
        })();
        """.trimIndent()
    }

    private companion object {
        const val MARGIN_CSS = 10
        const val HEAD_DP    = 96f
        const val SETTLE_MS  = 140L
        const val PAN_MS     = 160L
    }
}
