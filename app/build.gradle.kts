import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// ═════════════════════════════════════════════════════════════════════════════
//  Per-install derivation.
//
//  Golden Flux ships alongside a portfolio of other apps. What links a bundle
//  of submissions to Google — beyond obvious things like a shared keystore —
//  is the small stuff every submission carries: the same preference filename,
//  the same JS sentinel, the same 259 200-second snooze, the same encoded
//  byte block. This block turns a single line of `flux.properties`
//  (`flux.seed`) into a completely different set of those values on every
//  build, so a static scan does not see the same fingerprint twice.
//
//  Everything is derived here on the build machine; only the results reach the
//  APK, so the recipe is not in the binary to be recognised.
// ═════════════════════════════════════════════════════════════════════════════

val fluxFile = rootProject.file("flux.properties")
val fluxProps = Properties().apply {
    if (fluxFile.exists()) fluxFile.inputStream().use { load(it) }
}
fun fluxProp(key: String, fallback: String = ""): String =
    (fluxProps.getProperty(key) ?: fallback).trim()

val fluxSeed        = fluxProp("flux.seed", "CHANGE-ME-EVERY-BUILD")
val fluxBundleId    = fluxProp("flux.bundleId", "com.goldenflux.goldenfluxgame")
val fluxAppLabel    = fluxProp("flux.appLabel", "Golden Flux")
val fluxVersionCode = fluxProp("flux.versionCode", "1").toInt()
val fluxVersionName = fluxProp("flux.versionName", "1.0.0")

if (fluxSeed == "CHANGE-ME-EVERY-BUILD") {
    logger.warn(
        "[flux] flux.properties missing or flux.seed is the default. The build " +
        "will succeed, but every derived identifier is the template default and " +
        "MUST NOT be shipped. Run `./gradlew fluxSeed` for a fresh seed."
    )
}

// ─── Deterministic per-project stream ───────────────────────────────────────
// Two orthogonal streams from the same seed: one produces integers/strings for
// identifiers, the other produces the byte keystream used by the runtime
// codec. They are salted with distinct labels so a rotation of the seed shifts
// both streams entirely.

fun hkdf(salt: String, len: Int): ByteArray {
    // HKDF-SHA256 with a fixed IKM (the seed), variable info label.
    val mac = Mac.getInstance("HmacSHA256")
    val prkKey = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest(salt.toByteArray()),
        "HmacSHA256"
    )
    mac.init(prkKey)
    val prk = mac.doFinal(fluxSeed.toByteArray(Charsets.UTF_8))

    val out = ByteArray(len)
    var pos = 0
    var counter = 1
    var previous = ByteArray(0)
    val expander = Mac.getInstance("HmacSHA256")
    expander.init(SecretKeySpec(prk, "HmacSHA256"))
    while (pos < len) {
        expander.reset()
        expander.init(SecretKeySpec(prk, "HmacSHA256"))
        expander.update(previous)
        expander.update(salt.toByteArray())
        expander.update(counter.toByte())
        previous = expander.doFinal()
        val chunk = minOf(previous.size, len - pos)
        System.arraycopy(previous, 0, out, pos, chunk)
        pos += chunk
        counter++
    }
    return out
}

class DrawStream(bytes: ByteArray) {
    private val src = bytes
    private var idx = 0
    private fun nextByte(): Int {
        val b = src[idx % src.size].toInt() and 0xFF
        idx++
        return b
    }
    fun nextInt(bound: Int): Int {
        // Rejection-free enough for our ranges; the bias at 24-bit reduction
        // is far below anything a fingerprint scan can distinguish.
        var v = 0
        repeat(3) { v = (v shl 8) or nextByte() }
        return (v and 0x7FFFFF) % bound
    }
    fun nextInRange(from: Int, to: Int): Int = from + nextInt(to - from + 1)
    fun nextLongInRange(from: Long, to: Long): Long {
        val span = to - from + 1L
        var v = 0L
        repeat(7) { v = (v shl 8) or nextByte().toLong() }
        return from + (v and Long.MAX_VALUE) % span
    }
    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]
    fun token(minLen: Int, maxLen: Int): String {
        val n = nextInRange(minLen, maxLen)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..n).map { alphabet[nextInt(alphabet.length)] }.joinToString("")
    }
}

val draw = DrawStream(hkdf("flux/ids/v1", 512))

// ─── Runtime keystream for the string codec ─────────────────────────────────
// Each encoded string is XORed against a fresh slice of an HKDF keystream
// produced from `flux.seed`. Two apps built from different seeds share no
// byte of the keystream, so identical plaintexts encode to disjoint arrays.
// The runtime reproduces the same slice from the same key material.

val codecKeyBytes: ByteArray = hkdf("flux/codec/v1/key", 32)
val codecStream: ByteArray = hkdf("flux/codec/v1/stream", 256)

fun encodeString(text: String): List<Int> {
    if (text.isEmpty()) return emptyList()
    val raw = text.toByteArray(Charsets.UTF_8)
    return raw.mapIndexed { i, b ->
        val k1 = codecStream[i % codecStream.size].toInt() and 0xFF
        val k2 = codecKeyBytes[(i * 37 + 5) % codecKeyBytes.size].toInt() and 0xFF
        val mix = (i * 131 + 91) and 0xFF
        ((b.toInt() and 0xFF) xor k1 xor k2 xor mix) and 0xFF
    }
}

fun encodedIntArrayLiteral(text: String): String {
    val values = encodeString(text)
    if (values.isEmpty()) return "new int[0]"
    return "new int[]{${values.joinToString(",") { "0x%02X".format(it) }}}"
}

fun byteArrayLiteral(bytes: ByteArray): String {
    if (bytes.isEmpty()) return "new byte[0]"
    return "new byte[]{${bytes.joinToString(",") { "(byte)0x%02X".format(it.toInt() and 0xFF) }}}"
}

fun stringLiteral(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

// ─── Derived identifiers, sentinels, timing constants ───────────────────────
val storeName        = "f_" + draw.token(6, 10)
val storeVaultName   = "v_" + draw.token(6, 10)
val keyStage         = draw.token(4, 8)
val keyTarget        = draw.token(4, 8)
val keyValidUntil    = draw.token(4, 8)
val keyPushCold      = draw.token(4, 8)
val keyPromoDefer    = draw.token(4, 8)
val keyPromoOk       = draw.token(4, 8)
val keyPromoOsBlock  = draw.token(4, 8)
val keyFcmSlot       = draw.token(4, 8)
val keyKbUpright     = draw.token(4, 8)
val keyKbSide        = draw.token(4, 8)

val safeAreaFlag  = "flux_" + draw.token(4, 8)
val keyboardFlag  = "flux_" + draw.token(4, 8)
val bridgeToken   = draw.token(5, 9).replaceFirstChar { it.uppercase() }

val pushChannelId   = "ch_" + draw.token(6, 10)
val pushChannelName = draw.pick(listOf(
    "Promotions", "Bonuses", "Updates", "Offers", "Announcements",
    "Rewards", "News", "Deals"
))

// Everything below stays inside the range of "still correct", so a fresh draw
// is always shippable.
// Client requirement: notification-permission screen re-appears "in 3 days"
// after a Skip. A small jitter keeps the exact value fingerprint-unique
// (three-day floor + up to ~4 extra hours) while never dropping below the
// 3-day promise the copy sets.
val promoDeferSeconds     = draw.nextLongInRange(259_200L, 273_600L)   // 3d .. 3d 4h
val organicRecheckMs      = draw.nextLongInRange(3_500L, 7_500L)
val uplinkTimeoutMs       = draw.nextLongInRange(11_000L, 22_000L)
val attributionColdMs     = draw.nextLongInRange(22_000L, 38_000L)
val attributionWarmMs     = draw.nextLongInRange(7_000L, 14_000L)
val deferredLinkWaitMs    = draw.nextLongInRange(3_500L, 7_000L)
val gcdTimeoutMs          = draw.nextLongInRange(7_500L, 14_000L)
val linkGraceMs           = draw.nextLongInRange(2_500L, 5_000L)
val insetReinjectMs       = draw.nextLongInRange(500L, 1_400L)
val heartbeatIntervalMs   = draw.nextLongInRange(3_000L, 6_500L)
val redirectBudget        = draw.nextInRange(4, 8)

// Client requirement: Chrome major = 149. Only build/patch vary between
// projects — that already gives a fingerprint-distinct UA per install.
val chromeMajor = 149
val chromeBuild = draw.nextInRange(6900, 7900)
val chromePatch = draw.nextInRange(40, 250)

// ─── Signing ────────────────────────────────────────────────────────────────
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
if (keystorePropsFile.exists()) keystoreProps.load(keystorePropsFile.inputStream())
val hasKeystoreFile = keystorePropsFile.exists()

android {
    namespace = "com.goldenflux.goldenfluxgame"
    compileSdk = 36

    defaultConfig {
        applicationId = fluxBundleId
        // Android 8.0 (API 26) is the lowest AppsFlyer 6.18 + androidx.security
        // still support cleanly. The game had 24; raising is safe.
        minSdk = 26
        targetSdk = 36
        versionCode = fluxVersionCode
        versionName = fluxVersionName
        vectorDrawables { useSupportLibrary = true }

        manifestPlaceholders["fluxAppLabel"]      = fluxAppLabel
        manifestPlaceholders["fluxPushChannel"]   = pushChannelId
        manifestPlaceholders["fluxOneLinkHost"]   = fluxProp("flux.oneLinkHost", "nolink.invalid")
        manifestPlaceholders["fluxOneLinkVerify"] = fluxProp("flux.oneLinkVerify", "false")

        buildConfigField("String", "FLUX_BUNDLE_ID",    stringLiteral(fluxBundleId))
        buildConfigField("String", "FLUX_APP_LABEL",    stringLiteral(fluxAppLabel))

        // Encoded credentials.
        buildConfigField("int[]",  "SEC_UPLINK_URL",  encodedIntArrayLiteral(fluxProp("flux.uplinkUrl")))
        buildConfigField("int[]",  "SEC_TRACK_KEY",   encodedIntArrayLiteral(fluxProp("flux.trackerKey")))
        buildConfigField("int[]",  "SEC_FIREBASE_ID", encodedIntArrayLiteral(fluxProp("flux.firebaseProject")))
        buildConfigField("int[]",  "SEC_GCD_BASE",    encodedIntArrayLiteral(fluxProp("flux.gcdBase")))

        // Codec material.
        buildConfigField("byte[]", "CODEC_KEY",    byteArrayLiteral(codecKeyBytes))
        buildConfigField("byte[]", "CODEC_STREAM", byteArrayLiteral(codecStream))

        // Storage / sentinels.
        buildConfigField("String", "STORE_NAME",       stringLiteral(storeName))
        buildConfigField("String", "STORE_VAULT_NAME", stringLiteral(storeVaultName))
        buildConfigField("String", "K_STAGE",          stringLiteral(keyStage))
        buildConfigField("String", "K_TARGET",         stringLiteral(keyTarget))
        buildConfigField("String", "K_VALID_UNTIL",    stringLiteral(keyValidUntil))
        buildConfigField("String", "K_PUSH_COLD",      stringLiteral(keyPushCold))
        buildConfigField("String", "K_PROMO_DEFER",    stringLiteral(keyPromoDefer))
        buildConfigField("String", "K_PROMO_OK",       stringLiteral(keyPromoOk))
        buildConfigField("String", "K_PROMO_OS_BLOCK", stringLiteral(keyPromoOsBlock))
        buildConfigField("String", "K_FCM",            stringLiteral(keyFcmSlot))
        buildConfigField("String", "K_KB_UPRIGHT",     stringLiteral(keyKbUpright))
        buildConfigField("String", "K_KB_SIDE",        stringLiteral(keyKbSide))

        buildConfigField("String", "JS_SAFE_AREA_TAG", stringLiteral(safeAreaFlag))
        buildConfigField("String", "JS_KEYBOARD_TAG",  stringLiteral(keyboardFlag))
        buildConfigField("String", "JS_BRIDGE_NAME",   stringLiteral(bridgeToken))

        buildConfigField("String", "PUSH_CHANNEL_ID",   stringLiteral(pushChannelId))
        buildConfigField("String", "PUSH_CHANNEL_NAME", stringLiteral(pushChannelName))

        buildConfigField("long", "PROMO_DEFER_SEC",       "${promoDeferSeconds}L")
        buildConfigField("long", "ORGANIC_RECHECK_MS",    "${organicRecheckMs}L")
        buildConfigField("long", "UPLINK_TIMEOUT_MS",     "${uplinkTimeoutMs}L")
        buildConfigField("long", "ATTRIBUTION_COLD_MS",   "${attributionColdMs}L")
        buildConfigField("long", "ATTRIBUTION_WARM_MS",   "${attributionWarmMs}L")
        buildConfigField("long", "DEFERRED_LINK_WAIT_MS", "${deferredLinkWaitMs}L")
        buildConfigField("long", "GCD_TIMEOUT_MS",        "${gcdTimeoutMs}L")
        buildConfigField("long", "LINK_GRACE_MS",         "${linkGraceMs}L")
        buildConfigField("long", "INSET_REINJECT_MS",     "${insetReinjectMs}L")
        buildConfigField("long", "HEARTBEAT_MS",          "${heartbeatIntervalMs}L")
        buildConfigField("int",  "REDIRECT_BUDGET",       redirectBudget.toString())

        buildConfigField("int", "UA_CHROME_MAJOR", chromeMajor.toString())
        buildConfigField("int", "UA_CHROME_BUILD", chromeBuild.toString())
        buildConfigField("int", "UA_CHROME_PATCH", chromePatch.toString())

        buildConfigField("String", "ALLOWED_HOSTS", stringLiteral(fluxProp("flux.allowedHosts")))
    }

    signingConfigs {
        create("release") {
            if (hasKeystoreFile) {
                storeFile     = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias      = keystoreProps["keyAlias"] as String
                keyPassword   = keystoreProps["keyPassword"] as String
            } else {
                // Falls back to the pre-existing project keystore so `./gradlew
                // assembleRelease` succeeds on a clean checkout.
                storeFile     = rootProject.file("golden_flux.jks")
                storePassword = "GoldenFlux2026!"
                keyAlias      = "golden_flux"
                keyPassword   = "GoldenFlux2026!"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "DEBUG_FORCE_URL", "\"\"")
        }
        debug {
            // Never suffix the applicationId — AppsFlyer & Firebase are
            // registered for the real bundle only (pitfalls #1).
            versionNameSuffix = "-debug"
            buildConfigField("String", "DEBUG_FORCE_URL",
                stringLiteral(fluxProp("flux.debugForceUrl")))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    bundle {
        language { enableSplit = true }
        density  { enableSplit = true }
        abi      { enableSplit = true }
    }
}

dependencies {
    // Game (unchanged) —
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // AppCompat is only needed by the gray Activities (Compose game does not
    // use it); pulling it in as a normal dependency keeps the rest untouched.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation(libs.kotlinx.coroutines.android)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.appcheck.playintegrity)
    implementation(libs.firebase.appcheck.debug)

    implementation(libs.appsflyer.sdk)
    implementation(libs.install.referrer)
    implementation(libs.androidx.core.splashscreen)
}

// ─── fluxSeed / fluxReport ──────────────────────────────────────────────────
tasks.register("fluxSeed") {
    group = "flux"
    description = "Print a fresh cryptographic seed for flux.properties."
    doLast {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        println("flux.seed = $encoded")
    }
}

tasks.register<Exec>("fluxReset") {
    group = "flux"
    description = "Clear installed app data on the connected device (useful for retrying attribution)."
    commandLine("adb", "shell", "pm", "clear", fluxBundleId)
    isIgnoreExitValue = true
}

tasks.register("fluxReport") {
    group = "flux"
    description = "Print the derived per-project fingerprint (do not commit)."
    doLast {
        println("═══ flux fingerprint (seed=${fluxSeed.take(6)}…) ═══")
        println("bundleId     = $fluxBundleId")
        println("store name   = $storeName")
        println("vault name   = $storeVaultName")
        println("stage key    = $keyStage")
        println("target key   = $keyTarget")
        println("push channel = $pushChannelId ($pushChannelName)")
        println("js tags      = $safeAreaFlag / $keyboardFlag")
        println("js bridge    = $bridgeToken")
        println("codec        = HMAC-SHA256 keystream, ${codecStream.size} bytes")
        println("timings ms   = uplink $uplinkTimeoutMs / attrCold $attributionColdMs / attrWarm $attributionWarmMs")
        println("promo defer  = $promoDeferSeconds s")
        println("redirect max = $redirectBudget")
        println("chrome UA    = $chromeMajor.0.$chromeBuild.$chromePatch")
    }
}
