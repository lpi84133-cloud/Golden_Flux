package com.goldenflux.goldenfluxgame.flux.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.goldenflux.goldenfluxgame.BuildConfig
import com.goldenflux.goldenfluxgame.flux.util.Tracer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Persistent state for the flux flow. Two-tier by purpose, and by
 * implementation:
 *
 *  * Flags, counters and timestamps live in an ordinary [SharedPreferences]
 *    file named from the fingerprint. Reading these is a common path and the
 *    overhead of anything else is not worth paying.
 *  * URL fields (destination, cold push, FCM token) are AES-GCM-encrypted
 *    with a key derived from the per-install [BuildConfig.CODEC_KEY], and
 *    stored Base64 in a second [SharedPreferences] file. A dumped device
 *    snapshot then reveals no URL in plain text.
 *
 * The template shipped an `EncryptedSharedPreferences` wrapper backed by the
 * Android keystore, which is stronger but binds each write to the device
 * (an install migrated to another phone loses everything). Golden Flux
 * accepts a rare `adb backup` disclosure in exchange for portable state.
 *
 * The file names, the key strings and the encryption key are all per-install.
 * The class name and method names are meant to change per project.
 */
class FluxStore(ctx: Context) {

    enum class Stage { UNKNOWN, STREAM, NATIVE }

    private val plain: SharedPreferences =
        ctx.getSharedPreferences(BuildConfig.STORE_NAME, Context.MODE_PRIVATE)

    private val vault: SharedPreferences =
        ctx.getSharedPreferences(BuildConfig.STORE_VAULT_NAME, Context.MODE_PRIVATE)

    /** AES-256 key material derived once from the per-install codec key. */
    private val aesKey: SecretKeySpec = run {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(BuildConfig.CODEC_KEY + BuildConfig.STORE_VAULT_NAME.toByteArray())
        SecretKeySpec(bytes, "AES")
    }

    // ── Sensitive fields (AES-GCM in a Base64 wrapper) ──────────────────────

    private fun readCiphered(key: String): String? {
        val blob = vault.getString(key, null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            }
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Tracer.w(TAG, "decrypt failed, clearing $key", e)
            vault.edit().remove(key).apply()
            null
        }
    }

    private fun writeCiphered(key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            vault.edit().remove(key).apply()
            return
        }
        try {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
            }
            val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
            val blob = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, blob, 0, iv.size)
            System.arraycopy(ct, 0, blob, iv.size, ct.size)
            vault.edit()
                .putString(key, Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            Tracer.w(TAG, "encrypt failed, dropping $key", e)
            vault.edit().remove(key).apply()
        }
    }

    // ── Stage (public API) ──────────────────────────────────────────────────

    var stage: Stage
        get() = runCatching {
            Stage.valueOf(plain.getString(BuildConfig.K_STAGE, null) ?: return@runCatching Stage.UNKNOWN)
        }.getOrDefault(Stage.UNKNOWN)
        set(v) { plain.edit().putString(BuildConfig.K_STAGE, v.name).apply() }

    // ── Destination URL and its expiry ──────────────────────────────────────

    var target: String?
        get() = readCiphered(BuildConfig.K_TARGET)
        set(v) { writeCiphered(BuildConfig.K_TARGET, v) }

    var validUntil: Long
        get() = plain.getLong(BuildConfig.K_VALID_UNTIL, 0L)
        set(v) { plain.edit().putLong(BuildConfig.K_VALID_UNTIL, v).apply() }

    fun targetIsUsable(): Boolean {
        val url = target
        if (url.isNullOrBlank()) return false
        val ttl = validUntil
        return ttl == 0L || System.currentTimeMillis() / 1000 < ttl
    }

    // ── Cold-start push URL ─────────────────────────────────────────────────

    var pendingColdPush: String?
        get() = readCiphered(BuildConfig.K_PUSH_COLD)
        set(v) { writeCiphered(BuildConfig.K_PUSH_COLD, v) }

    fun takeColdPush(): String? {
        val v = pendingColdPush
        if (v != null) pendingColdPush = null
        return v
    }

    // ── Notification permission book-keeping ────────────────────────────────

    var promoDeferUntil: Long
        get() = plain.getLong(BuildConfig.K_PROMO_DEFER, 0L)
        set(v) { plain.edit().putLong(BuildConfig.K_PROMO_DEFER, v).apply() }

    var promoGranted: Boolean
        get() = plain.getBoolean(BuildConfig.K_PROMO_OK, false)
        set(v) { plain.edit().putBoolean(BuildConfig.K_PROMO_OK, v).apply() }

    var promoOsBlocked: Boolean
        get() = plain.getBoolean(BuildConfig.K_PROMO_OS_BLOCK, false)
        set(v) { plain.edit().putBoolean(BuildConfig.K_PROMO_OS_BLOCK, v).apply() }

    fun shouldOfferPromo(): Boolean {
        if (promoGranted || promoOsBlocked) return false
        val now = System.currentTimeMillis() / 1000
        return now >= promoDeferUntil
    }

    fun snoozePromo() {
        val now = System.currentTimeMillis() / 1000
        promoDeferUntil = now + BuildConfig.PROMO_DEFER_SEC
    }

    // ── FCM token ───────────────────────────────────────────────────────────

    var fcmToken: String?
        get() = readCiphered(BuildConfig.K_FCM)
        set(v) { writeCiphered(BuildConfig.K_FCM, v) }

    // ── Campaign hosts learned from the config endpoint ─────────────────────
    // Persisted so push gating survives process death (a cold push to a
    // campaign host must still clear HostGate before config runs again).

    var trustedHosts: Set<String>
        get() = plain.getStringSet(KEY_TRUSTED_HOSTS, emptySet()) ?: emptySet()
        set(v) { plain.edit().putStringSet(KEY_TRUSTED_HOSTS, v).apply() }

    fun addTrustedHost(host: String?) {
        val h = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        val next = trustedHosts.toMutableSet()
        if (next.add(h)) trustedHosts = next
    }

    // ── Keyboard resting height (per orientation) ───────────────────────────

    fun keyboardRest(upright: Boolean): Int =
        plain.getInt(if (upright) BuildConfig.K_KB_UPRIGHT else BuildConfig.K_KB_SIDE, 0)

    fun rememberKeyboardRest(upright: Boolean, px: Int) {
        plain.edit()
            .putInt(if (upright) BuildConfig.K_KB_UPRIGHT else BuildConfig.K_KB_SIDE, px)
            .apply()
    }

    private companion object {
        const val TAG = "FluxStore"
        const val KEY_TRUSTED_HOSTS = "flux_trusted_hosts"
    }
}
