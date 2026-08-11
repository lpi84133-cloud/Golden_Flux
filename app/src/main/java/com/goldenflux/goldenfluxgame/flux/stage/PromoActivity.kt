package com.goldenflux.goldenfluxgame.flux.stage

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.goldenflux.goldenfluxgame.R
import com.goldenflux.goldenfluxgame.flux.store.FluxStore
import com.goldenflux.goldenfluxgame.flux.util.Immersive

/**
 * Notification-permission prompt. Buttons: **ACCEPT** / **SKIP**.
 *
 *   * Accept  → request `POST_NOTIFICATIONS` (API 33+) → the system dialog.
 *   * Skip    → snooze this screen by `promoDeferSeconds` (~3 days per
 *               install, with up to 4h fingerprint jitter).
 *   * OS "deny forever" → set a permanent flag; the screen never returns
 *     (there is no way to open the system dialog again from the app).
 *
 * Both buttons sit **horizontally centred** at the bottom. No cutout
 * padding is applied — the artwork's card is already inset from the notch,
 * and forcing a side inset in landscape would visibly offset the pair from
 * the card the user is reading (client feedback in the brief).
 */
class PromoActivity : AppCompatActivity() {

    private lateinit var store: FluxStore
    private var pendingUrl: String? = null

    private val permissionAsk = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            store.promoGranted = true
        } else {
            val hardNo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (hardNo) store.promoOsBlocked = true
            else store.snoozePromo()
        }
        moveOn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = FluxStore(applicationContext)
        pendingUrl = intent.getStringExtra(EXTRA_TARGET_URL)

        // Full-bleed under the cutout. Same reason as OfflineActivity: the
        // horizontal centre of the window must equal the horizontal centre
        // of the artwork, so a Gravity.CENTER_HORIZONTAL row of ACCEPT/SKIP
        // buttons sits exactly under the card the user is reading — even
        // when the notch is on the left in landscape.
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
            R.drawable.flux_promo_landscape else R.drawable.flux_promo_portrait
        root.addView(ImageView(this).apply {
            setImageResource(bgRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })

        // Portrait: two equal buttons stacked (ACCEPT above SKIP), each
        // wide enough to hold the same word without wrapping.
        // Landscape: two equal buttons side by side, centred as a group.
        // Client brief: same visual weight, no primary being larger than
        // secondary, so a rushed tap does not land on the wrong choice.
        val landscape = isLandscape()
        val btnW = dp(if (landscape) 200 else 260)
        val btnH = dp(if (landscape) 54 else 56)
        val gap  = dp(if (landscape) 16 else 12)

        val row = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply {
                bottomMargin = dp(if (landscape) 20 else 36)
            }
        }

        val accept = buildButton(getString(R.string.flux_accept), primary = true, btnW, btnH)
        val skip   = buildButton(getString(R.string.flux_skip),   primary = false, btnW, btnH)
        accept.setOnClickListener { onAccept() }
        skip.setOnClickListener { onSkip() }

        row.addView(accept)
        row.addView(spacer(gap, vertical = !landscape))
        row.addView(skip)
        root.addView(row)

        setContentView(root)
        Immersive.applyTo(this)
    }

    private fun onAccept() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
                store.promoGranted = true
                moveOn()
            } else {
                permissionAsk.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            store.promoGranted = true
            moveOn()
        }
    }

    private fun onSkip() {
        store.snoozePromo()
        moveOn()
    }

    /**
     * Straight to the stage, not through the router: the splash has had its
     * one showing for this launch, and bringing it back after the system
     * dialog reads as the app restarting (kotlin_gray_pitfalls.mdc #26).
     */
    private fun moveOn() {
        val next = Intent(this, StageActivity::class.java).apply {
            pendingUrl?.let { putExtra(StageActivity.EXTRA_TARGET_URL, it) }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(next)
        finish()
    }

    private fun buildButton(label: String, primary: Boolean, w: Int, h: Int): TextView {
        val t = TextView(this)
        t.text = label
        // Same typography for both buttons — visual weight parity.
        t.textSize = 17f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(28), dp(14), dp(28), dp(14))
        t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        t.letterSpacing = 0.06f
        if (primary) {
            t.setBackgroundResource(R.drawable.flux_btn_primary)
            t.setTextColor(0xFF1A0523.toInt())
            t.setShadowLayer(2f, 0f, 1f, 0x55FFFFFF)
        } else {
            t.setBackgroundResource(R.drawable.flux_btn_secondary)
            t.setTextColor(0xFFF5C542.toInt())
            t.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        t.layoutParams = LinearLayout.LayoutParams(w, h)
        return t
    }

    private fun spacer(size: Int, vertical: Boolean): View = View(this).apply {
        layoutParams = if (vertical)
            LinearLayout.LayoutParams(1, size)
        else
            LinearLayout.LayoutParams(size, 1)
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }

    companion object { const val EXTRA_TARGET_URL = "flux_promo_url" }
}
