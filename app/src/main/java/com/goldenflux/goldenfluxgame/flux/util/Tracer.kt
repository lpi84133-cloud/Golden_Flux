package com.goldenflux.goldenfluxgame.flux.util

import android.util.Log
import com.goldenflux.goldenfluxgame.BuildConfig

/**
 * The one place a log line from the flux package is written. Guarded by
 * `BuildConfig.DEBUG`, and R8's `-assumenosideeffects` turns the `.d/.v`
 * calls into dead branches in release. What a release build keeps is only
 * warnings and errors, which the post-mortem analyst needs.
 */
internal object Tracer {
    fun i(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.i(tag, msg) }
    fun w(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.w(tag, msg) }
    fun w(tag: String, msg: String, t: Throwable) { if (BuildConfig.DEBUG) Log.w(tag, msg, t) }
    fun e(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.e(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable) { if (BuildConfig.DEBUG) Log.e(tag, msg, t) }
}
