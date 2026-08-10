package com.goldenflux.goldenfluxgame.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

enum class Sfx(val file: String) {
    CLICK("sfx/button_click.mp3"),
    MENU_OPEN("sfx/menu_open.mp3"),
    MENU_CLOSE("sfx/menu_close.mp3"),
    BUILD("sfx/energy_generator_activation.mp3"),
    UPGRADE("sfx/upgrade_complete.mp3"),
    COLLECT("sfx/resource_collection.mp3"),
    CHEST("sfx/chest_opening.mp3"),
    CORE("sfx/golden_flux_core_power_up.mp3"),
    ZONE("sfx/portal_activation.mp3"),
    COMPLETE("sfx/level_complete.mp3"),
}

/** Loads short SFX into a [SoundPool] and streams ambient music via [MediaPlayer]. */
class SoundManager(private val context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = HashMap<Sfx, Int>()
    private var ambient: MediaPlayer? = null

    @Volatile var sfxEnabled: Boolean = true
    @Volatile var musicEnabled: Boolean = true
    @Volatile var vibrationEnabled: Boolean = true

    /** Preloads all SFX. Safe to call on a background thread. */
    fun loadAll() {
        Sfx.entries.forEach { sfx ->
            runCatching {
                context.assets.openFd(sfx.file).use { afd ->
                    ids[sfx] = pool.load(afd, 1)
                }
            }
        }
    }

    fun play(sfx: Sfx) {
        if (!sfxEnabled) return
        ids[sfx]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
    }

    fun startAmbient() {
        if (!musicEnabled) return
        if (ambient != null) {
            runCatching { if (!ambient!!.isPlaying) ambient!!.start() }
            return
        }
        runCatching {
            val afd = context.assets.openFd("sfx/background_ambient.mp3")
            ambient = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(0.5f, 0.5f)
                prepare()
                start()
            }
        }
    }

    fun pauseAmbient() {
        runCatching { ambient?.let { if (it.isPlaying) it.pause() } }
    }

    fun applyMusicSetting() {
        if (musicEnabled) startAmbient() else pauseAmbient()
    }

    /** Haptics must never break gameplay, so every failure here is swallowed. */
    fun tick() {
        if (!vibrationEnabled) return
        val vib = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(18)
            }
        }.onFailure { vibrationEnabled = false }
    }

    private val vibrator: Vibrator? by lazy {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            v?.takeIf { it.hasVibrator() }
        }.getOrNull()
    }

    fun release() {
        runCatching { ambient?.release() }
        ambient = null
        pool.release()
    }
}
