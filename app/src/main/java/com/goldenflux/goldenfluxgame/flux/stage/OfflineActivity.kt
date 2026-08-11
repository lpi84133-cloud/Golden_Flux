package com.goldenflux.goldenfluxgame.flux.stage

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.goldenflux.goldenfluxgame.R
import com.goldenflux.goldenfluxgame.MainActivity
import com.goldenflux.goldenfluxgame.flux.link.LinkMonitor
import com.goldenflux.goldenfluxgame.flux.util.Immersive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "No internet" screen. Full-bleed WebP background with a Retry button
 * horizontally centred at the bottom.
 *
 * The card art already accounts for the side gutters, so this Activity
 * deliberately draws its background across the full window (no cutout
 * padding). What that costs is the round-off between screen aspect ratios;
 * what it saves is the button being visibly off-centre relative to the card
 * on landscape devices with a notch on one side.
 *
 * Auto-retry watches [LinkMonitor.changes] and moves on the moment the link
 * returns.
 */
class OfflineActivity : AppCompatActivity() {

    private lateinit var link: LinkMonitor
    private val scope = CoroutineScope(Dispatchers.Main)
    private var retryBtn: TextView? = null
    private var returnUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = LinkMonitor(applicationContext)
        returnUrl = intent.getStringExtra(EXTRA_RETURN_URL)

        // Full-bleed under the cutout so the artwork's true centre = window's
        // true centre. If the window were letterboxed on the notch side, a
        // Gravity.CENTER_HORIZONTAL button would sit off-centre relative to
        // the visible art (client brief: horizontal center must not shift).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = false
        }

        val bgRes = if (isLandscape())
            R.drawable.flux_offline_landscape else R.drawable.flux_offline_portrait
        val bg = ImageView(this).apply {
            setImageResource(bgRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(bg)

        val btn = buildRetry()
        retryBtn = btn
        btn.setOnClickListener { attempt() }
        val lp = FrameLayout.LayoutParams(
            dp(220), dp(56),
            Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        ).apply { bottomMargin = dp(if (isLandscape()) 24 else 56) }
        btn.layoutParams = lp
        root.addView(btn)

        setContentView(root)
        Immersive.applyTo(this)

        scope.launch {
            link.changes.collect { online -> if (online) attempt() }
        }
    }

    private fun attempt() {
        val btn = retryBtn ?: return
        btn.text = getString(R.string.flux_connecting)
        btn.isEnabled = false
        scope.launch {
            val ok = link.reachesOutside()
            if (ok) {
                val next = if (!returnUrl.isNullOrBlank()) {
                    Intent(this@OfflineActivity, StageActivity::class.java)
                        .putExtra(StageActivity.EXTRA_TARGET_URL, returnUrl)
                        .setFlags(FLAG_ACTIVITY_CLEAR_TOP)
                } else {
                    // No page to return to means the launch never finished
                    // its first decision. Sending the router through from
                    // the top is the only path that gets AppsFlyer asked
                    // properly (pitfalls #19a, #20).
                    Intent(this@OfflineActivity, MainActivity::class.java)
                        .setFlags(FLAG_ACTIVITY_CLEAR_TASK or FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(next)
                finish()
            } else {
                btn.text = getString(R.string.flux_retry)
                btn.isEnabled = true
            }
        }
    }

    private fun buildRetry(): TextView = TextView(this).apply {
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

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val EXTRA_RETURN_URL = "flux_return_url" }
}
