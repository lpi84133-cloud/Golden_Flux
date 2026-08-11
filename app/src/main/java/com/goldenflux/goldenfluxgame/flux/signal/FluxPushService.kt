package com.goldenflux.goldenfluxgame.flux.signal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.goldenflux.goldenfluxgame.BuildConfig
import com.goldenflux.goldenfluxgame.R
import com.goldenflux.goldenfluxgame.flux.entry.LaunchArbiter
import com.goldenflux.goldenfluxgame.flux.store.FluxStore
import com.goldenflux.goldenfluxgame.flux.util.HostGate
import com.goldenflux.goldenfluxgame.flux.util.Tracer
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Firebase Cloud Messaging service. Everything about the push contract lives
 * here (kotlin_webview.mdc §"Push URL", pitfalls #32):
 *
 *  - a URL that fails [HostGate] is dropped and only the text notification is
 *    shown (a push tap must never open a page the app cannot recognise);
 *  - a warm URL (shell alive) goes straight to [WarmSignal] and is never
 *    persisted; the notification is suppressed so no tray badge stays after
 *    delivery;
 *  - a cold URL is stored and consumed exactly once by [LaunchArbiter];
 *  - a NATIVE user keeps their game — a URL becomes a harmless notification
 *    and the tap opens the launcher instead of the WebView. Turning a NATIVE
 *    user into a WebView after the fact is a store-review problem, not a
 *    feature (kotlin_launch_flow.mdc §"Once native, stay native").
 */
class FluxPushService : FirebaseMessagingService() {

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        io.cancel()
        super.onDestroy()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FluxStore(applicationContext).fcmToken = token
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        super.onMessageReceived(msg)
        val data = msg.data
        val notif = msg.notification
        val title = data["title"] ?: notif?.title ?: return
        val body  = data["body"]  ?: notif?.body  ?: return
        val rawUrl = (data["url"] ?: data["link"] ?: "").trim()
        val imageUrl = data["image"] ?: notif?.imageUrl?.toString() ?: ""

        val urlAllowed = rawUrl.isNotEmpty() && HostGate.admits(rawUrl)
        if (rawUrl.isNotEmpty() && !urlAllowed) {
            Tracer.w(TAG, "push URL rejected by allowlist — showing text-only notification")
        }

        val store = FluxStore(applicationContext)
        val stage = store.stage

        // Warm hand-off works only for STREAM installs. NATIVE stays native
        // while foregrounded — a targeted URL only overrides on an explicit
        // notification tap (handled by LaunchArbiter's cold-push branch).
        if (urlAllowed && stage == FluxStore.Stage.STREAM && WarmSignal.onLive != null) {
            val delivered = runCatching { WarmSignal.offer(rawUrl) }.getOrDefault(false)
            if (delivered) return
        }

        // Cold-push stash: keep the URL for EVERY stage (including NATIVE),
        // so the tap-intent can flip a stale NATIVE lock and route to the
        // shell. LaunchArbiter guards the actual navigation.
        val stashUrl = if (urlAllowed) rawUrl else ""
        if (stashUrl.isNotEmpty()) store.pendingColdPush = stashUrl

        io.launch { emitNotification(title, body, stashUrl, imageUrl) }
    }

    private suspend fun emitNotification(
        title: String, body: String, url: String, imageUrl: String
    ) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val tap = Intent(ctx, LaunchArbiter::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (url.isNotBlank()) putExtra(LaunchArbiter.EXTRA_PUSH_URL, url)
            putExtra(LaunchArbiter.EXTRA_FROM_PUSH, true)
        }
        val pi = PendingIntent.getActivity(
            ctx, System.currentTimeMillis().toInt(), tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(ctx, BuildConfig.PUSH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_flux)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val bitmap = if (imageUrl.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                URL(imageUrl).openConnection().apply {
                    connectTimeout = 8_000
                    readTimeout    = 8_000
                }.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        if (bitmap != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .bigLargeIcon(null as android.graphics.Bitmap?)
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        withContext(Dispatchers.Main) {
            nm.notify(seq.getAndIncrement(), builder.build())
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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

    private companion object {
        const val TAG = "FluxPushService"
        val seq = AtomicInteger(2001)
    }
}
