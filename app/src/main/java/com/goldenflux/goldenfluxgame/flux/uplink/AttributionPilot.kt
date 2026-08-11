package com.goldenflux.goldenfluxgame.flux.uplink

import android.app.Activity
import android.content.Context
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.deeplink.DeepLinkListener
import com.appsflyer.deeplink.DeepLinkResult
import com.goldenflux.goldenfluxgame.BuildConfig
import com.goldenflux.goldenfluxgame.flux.env.Env
import com.goldenflux.goldenfluxgame.flux.util.Agent
import com.goldenflux.goldenfluxgame.flux.util.Tracer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AppsFlyer attribution, in two strokes. Reference: kotlin_launch_flow.mdc.
 *
 *  * [wireUp] belongs in the Application. It subscribes the deep-link
 *    listener, calls `init(key, listener, appContext)` and registers the
 *    lifecycle callbacks the SDK uses to notice foreground. Sends nothing on
 *    the wire.
 *  * [ignite] is called from the router, on an [Activity], **after** the
 *    connectivity gate. This is the call that puts the install on the wire.
 *
 * A launch that happens offline is the single most common attribution bug in
 * this template (pitfalls #19). Defence in depth: gate before start; re-ask
 * on an empty result; ask AppsFlyer's Get Conversion Data API directly if
 * neither of the above yields anything.
 */
class AttributionPilot(private val ctx: Context) {

    @Volatile private var conversion = CompletableDeferred<Map<String, Any?>>()
    @Volatile private var lastSettled: Map<String, Any?>? = null

    private val deepLinkExtras = HashMap<String, Any?>()
    private val deepLink = CompletableDeferred<Unit>()

    private var wired = false
    private var lit   = false

    @Volatile private var askedAgain = false
    private var askedAgainAt = 0L

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val gcdHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Env.gcdTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(Env.gcdTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Called from Application.onCreate. Puts nothing on the wire. */
    fun wireUp() {
        if (wired) return
        wired = true

        val key = Env.trackerKey
        if (key.isBlank()) {
            Tracer.i(TAG, "tracker key not set — attribution completes empty")
            settle(emptyMap())
            deepLink.complete(Unit)
            return
        }

        val outcome = runCatching {
            AppsFlyerLib.getInstance().apply {
                setDebugLog(BuildConfig.DEBUG)
                subscribeForDeepLink(deepLinkHook)
                init(key, conversionHook, ctx.applicationContext)
            }
        }
        if (outcome.isFailure) {
            Tracer.w(TAG, "SDK would not wire: ${outcome.exceptionOrNull()?.message}")
            settle(emptyMap())
            deepLink.complete(Unit)
        }
    }

    /** Called from the router, after a positive connectivity check. */
    fun ignite(host: Activity) {
        wireUp()
        if (lit || Env.trackerKey.isBlank()) return
        lit = true
        val outcome = runCatching { AppsFlyerLib.getInstance().start(host) }
        if (outcome.isFailure) {
            Tracer.w(TAG, "SDK would not start: ${outcome.exceptionOrNull()?.message}")
            settle(emptyMap())
            deepLink.complete(Unit)
            return
        }
        Tracer.i(TAG, "SDK started from ${host.javaClass.simpleName}")
    }

    /** Re-ask AppsFlyer after an empty first answer (pitfalls #19a). */
    fun rekindle(host: Activity) {
        if (!lit) { ignite(host); return }
        val last = lastSettled ?: return  // still in flight
        if (last.isNotEmpty()) return     // has an answer — keep it
        lastSettled = null
        askedAgain = true
        askedAgainAt = System.currentTimeMillis()
        conversion = CompletableDeferred()
        runCatching { AppsFlyerLib.getInstance().start(host) }
        Tracer.i(TAG, "attribution asked again — link is up now")
    }

    suspend fun awaitAttribution(timeoutMs: Long): Map<String, Any?> = coroutineScope {
        val link = async { withTimeoutOrNull(Env.deferredLinkWaitMs) { deepLink.await() } }
        val data = async { awaitConversion(timeoutMs) }
        link.await()
        data.await()
    }

    private suspend fun awaitConversion(timeoutMs: Long): Map<String, Any?> {
        val window = if (askedAgain) minOf(timeoutMs, RETRACE_WAIT_MS) else timeoutMs
        val fromSdk = withTimeoutOrNull(window) { conversion.await() } ?: emptyMap()
        if (fromSdk.isNotEmpty()) return fromSdk

        if (askedAgain) {
            val waited = System.currentTimeMillis() - askedAgainAt
            if (waited < RETRACE_WAIT_MS) delay(RETRACE_WAIT_MS - waited)
        }
        val viaGcd = pullFromGcd()
        if (viaGcd.isNullOrEmpty()) return fromSdk
        Tracer.i(TAG, "attribution recovered via GCD fallback")
        lastSettled = viaGcd
        return viaGcd
    }

    private val conversionHook = object : AppsFlyerConversionListener {
        override fun onConversionDataSuccess(raw: MutableMap<String, Any?>) {
            Tracer.i(TAG, "onConversionDataSuccess")
            bg.launch {
                val status = raw["af_status"]?.toString().orEmpty()
                val resolved = if (status.equals("Organic", ignoreCase = true)) {
                    // False-Organic recheck: wait a few seconds, ask GCD, prefer its answer.
                    delay(Env.organicRecheckMs)
                    pullFromGcd() ?: raw
                } else raw
                settle(resolved)
            }
        }

        override fun onConversionDataFail(err: String?) {
            Tracer.w(TAG, "onConversionDataFail: $err")
            settle(emptyMap())
        }

        override fun onAppOpenAttribution(data: MutableMap<String, String>?) {
            data?.forEach { (k, v) -> deepLinkExtras[k] = v }
        }

        override fun onAttributionFailure(err: String?) {
            Tracer.w(TAG, "onAttributionFailure: $err")
            settle(emptyMap())
        }
    }

    private val deepLinkHook = DeepLinkListener { result ->
        if (result.status != DeepLinkResult.Status.FOUND) {
            Tracer.i(TAG, "deep link status=${result.status}")
            deepLink.complete(Unit)
            return@DeepLinkListener
        }
        runCatching {
            val click = result.deepLink.clickEvent
            click.keys().forEach { k -> deepLinkExtras[k] = click.opt(k) }
        }
        deepLink.complete(Unit)
    }

    private fun settle(data: Map<String, Any?>) {
        lastSettled = data
        if (!conversion.isCompleted) conversion.complete(data)
    }

    /** GCD (Get Conversion Data) fallback — direct HTTP to AppsFlyer. */
    private suspend fun pullFromGcd(): Map<String, Any?>? = withContext(Dispatchers.IO) {
        try {
            val uid = AppsFlyerLib.getInstance().getAppsFlyerUID(ctx) ?: return@withContext null
            val base = Env.gcdBase
            if (base.isBlank()) return@withContext null
            val req = Request.Builder()
                .url("$base${Env.bundleId}?device_id=$uid")
                .addHeader("Authorization", "Bearer ${Env.trackerKey}")
                .addHeader("User-Agent", Agent.value)
                .get()
                .build()
            gcdHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Tracer.w(TAG, "GCD HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                if (body.isBlank()) return@withContext null
                asMap(JSONObject(body))
            }
        } catch (e: Exception) {
            Tracer.w(TAG, "GCD fetch failed: ${e.message}")
            null
        }
    }

    fun appsFlyerUid(): String = AppsFlyerLib.getInstance().getAppsFlyerUID(ctx) ?: ""

    /**
     * Body for the uplink POST: conversion fields first (verbatim), then any
     * deep-link keys not already present, then device fields (which win).
     */
    fun composeRequest(
        attribution: Map<String, Any?>,
        os: String,
        locale: String,
        pushToken: String?,
        firebaseProject: String
    ): JSONObject = JSONObject().apply {
        attribution.forEach { (k, v) -> if (v != null) put(k, v.toString()) }
        deepLinkExtras.forEach { (k, v) -> if (v != null && !has(k)) put(k, v.toString()) }
        put("af_id", appsFlyerUid())
        put("bundle_id", Env.bundleId)
        put("os", os)
        put("store_id", Env.bundleId)
        put("locale", locale)
        if (!pushToken.isNullOrBlank()) put("push_token", pushToken)
        if (firebaseProject.isNotBlank()) put("firebase_project_id", firebaseProject)
        Tracer.i(TAG, "request body composed (${length()} fields)")
    }

    fun shutdown() { bg.cancel() }

    private fun asMap(json: JSONObject): Map<String, Any?> =
        json.keys().asSequence().associateWith { json.opt(it) }

    private companion object {
        const val TAG = "AttributionPilot"
        const val RETRACE_WAIT_MS = 8_000L
    }
}
