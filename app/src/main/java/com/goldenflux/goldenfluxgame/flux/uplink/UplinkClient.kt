package com.goldenflux.goldenfluxgame.flux.uplink

import com.goldenflux.goldenfluxgame.flux.env.Env
import com.goldenflux.goldenfluxgame.flux.env.StageDecision
import com.goldenflux.goldenfluxgame.flux.util.Agent
import com.goldenflux.goldenfluxgame.flux.util.HostGate
import com.goldenflux.goldenfluxgame.flux.util.Tracer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Uplink to the config endpoint. One method: POST the attribution body,
 * return the parsed answer.
 *
 * The endpoint is our own backend, reached over TLS behind Play Integrity
 * App Check. When it answers `ok:true` with a URL, that URL is the
 * authoritative destination for this install and is loaded verbatim — it is
 * NOT re-checked against the static allowlist (doing so was the bug that
 * dropped every partner/OneLink campaign whose host was not, and could not
 * be, listed in `flux.allowedHosts` ahead of time). The destination host is
 * instead *remembered* by [HostGate] so a later push to the same campaign
 * clears the gate too. The static allowlist keeps gating cold push URLs that
 * arrive before any config answer.
 */
class UplinkClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(Env.uplinkTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(Env.uplinkTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun exchange(body: JSONObject): StageDecision = withContext(Dispatchers.IO) {
        val endpoint = Env.uplinkUrl
        if (endpoint.isBlank()) {
            Tracer.w(TAG, "endpoint is blank — nobody to ask")
            return@withContext StageDecision.Unreachable
        }
        Tracer.i(TAG, "POST uplink")
        try {
            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", Agent.value)
                .post(body.toString().toRequestBody(jsonType))
                .build()
            http.newCall(req).execute().use { resp ->
                val code = resp.code
                val raw = resp.body?.string().orEmpty()
                Tracer.i(TAG, "HTTP $code (${raw.length} chars): ${raw.take(240)}")
                if (code == 404) return@withContext StageDecision.ServerSaidNo
                if (code !in 200..299) return@withContext StageDecision.ServerSaidNo
                parse(raw)
            }
        } catch (e: Exception) {
            Tracer.w(TAG, "request never landed: ${e.message}")
            StageDecision.Unreachable
        }
    }

    private fun parse(raw: String): StageDecision {
        if (raw.isBlank()) return StageDecision.ServerSaidNo
        return try {
            val j = JSONObject(raw)
            val ok = j.optBoolean("ok", false)
            val url = j.optString("url", "")
            val exp = j.optLong("expires", 0L)
            if (ok && url.isNotBlank()) {
                // Trust the endpoint's destination verbatim and remember its
                // host for later push gating. No allowlist re-check here.
                val host = HostGate.remember(url)
                Tracer.i(TAG, "endpoint → stream (host trusted: $host)")
                StageDecision.Stream(url, exp)
            } else StageDecision.ServerSaidNo
        } catch (e: Exception) {
            Tracer.w(TAG, "JSON parse error: ${e.message}")
            StageDecision.ServerSaidNo
        }
    }

    private companion object { const val TAG = "UplinkClient" }
}
