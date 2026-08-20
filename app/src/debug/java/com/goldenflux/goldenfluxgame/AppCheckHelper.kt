package com.goldenflux.goldenfluxgame

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

internal object AppCheckHelper {
    fun factory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
}
