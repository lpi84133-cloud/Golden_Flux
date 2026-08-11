package com.goldenflux.goldenfluxgame.flux.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Connectivity across two layers, deliberately not one:
 *
 *  - [isConnected] is the instant, synchronous check — `activeNetwork` plus
 *    [NetworkCapabilities.NET_CAPABILITY_INTERNET]. What the router uses in
 *    `onCreate` to decide the first frame (kotlin_launch_flow.mdc §4).
 *  - [changes] is the live stream, from `registerDefaultNetworkCallback`, so
 *    the WebView shell reacts the instant Wi-Fi is dropped, not on the next
 *    request.
 *  - [reachesOutside] is the TCP probe used from the offline screen's Retry,
 *    when the device might report a network that does not actually route.
 */
class LinkMonitor(ctx: Context) {

    private val cm = ctx.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isConnected(): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** True if a TCP socket can reach the outside. Used for real reachability. */
    suspend fun reachesOutside(): Boolean = withContext(Dispatchers.IO) {
        // Two well-known DNS anycast IPs — either succeeding is enough.
        listOf("1.1.1.1", "8.8.8.8").any { host ->
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, 53), 3_000)
                    true
                }
            }.getOrDefault(false)
        }
    }

    val changes: Flow<Boolean> = callbackFlow {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(false) }
            override fun onUnavailable() { trySend(false) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        trySend(isConnected())
        awaitClose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }.distinctUntilChanged()
}
