package com.goldenflux.goldenfluxgame.flux.signal

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide relay for one-time push URLs.
 *
 * Two flavours, and they need different handling:
 *
 *  - a push that arrives while the WebView is on screen goes straight to
 *    [onLive];
 *  - a push tapped from the notification tray while the app is in the
 *    background reaches the launcher activity instead — by then [onLive] is
 *    null (the shell cleared it in `onStop`). Rather than drawing anything,
 *    the launcher [queue]s the URL and calls `finish()`; the shell drains
 *    the queue as it comes back to the foreground.
 *
 * A queued value is a single hand-off. Warm URLs are never persisted.
 */
object WarmSignal {

    @Volatile var onLive: ((String) -> Unit)? = null

    /** True between StageStack's onCreate and onDestroy, screen state aside. */
    @Volatile var stageAttached = false

    private val holdover = AtomicReference<String?>(null)

    /** @return `true` when the live shell will handle it and the caller must not route. */
    fun offer(url: String): Boolean {
        onLive?.let { deliver ->
            deliver(url)
            return true
        }
        if (!stageAttached) return false
        holdover.set(url)
        return true
    }

    fun drain(): String? = holdover.getAndSet(null)
}
