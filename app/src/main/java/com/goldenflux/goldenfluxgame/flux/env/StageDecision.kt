package com.goldenflux.goldenfluxgame.flux.env

/**
 * Outcome of a config-endpoint call.
 *
 * [answered] separates two kinds of "no". A server that replied — 404, an
 * empty body, `ok:false`, anything with a status line — has ruled on this
 * install, and that ruling is final. A request that never landed has ruled
 * on nothing, and the run must not be recorded as NATIVE on the strength of
 * it (kotlin_launch_flow.mdc §5).
 */
sealed class StageDecision {

    abstract val answered: Boolean

    data class Stream(val url: String, val expiresAt: Long) : StageDecision() {
        override val answered: Boolean = true
    }

    data class Native(override val answered: Boolean) : StageDecision()

    companion object {
        val Unreachable: StageDecision = Native(answered = false)
        val ServerSaidNo: StageDecision = Native(answered = true)
    }
}
