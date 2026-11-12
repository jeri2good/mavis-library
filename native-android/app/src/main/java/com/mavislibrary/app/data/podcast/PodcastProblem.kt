package com.mavislibrary.app.data.podcast

import com.mavislibrary.app.data.api.ApiException
import kotlinx.serialization.SerializationException
import java.io.IOException

/** Why a podcast request failed, so screens can react to the kind instead of comparing message text. */
enum class PodcastProblemKind {
    /** No session or owner code. */
    SIGN_IN,
    /** Signed in, but the account has no podcast access. */
    ACCESS,
    /** The request never got an answer: phone offline or Mavis unreachable. */
    OFFLINE,
    /** The gateway answered but the studio didn't (asleep, waking, or down). */
    STUDIO_DOWN,
    /** The studio is busy and asked us to come back later. */
    STUDIO_BUSY,
    /** This phone sent too many requests in a minute. */
    SLOW_DOWN,
    DAILY_LIMIT,
    ACTIVE_LIMIT,
    /** The podcast isn't at the studio (deleted, or never existed). */
    NOT_FOUND,
    /** The server has no studio set up. */
    NOT_CONFIGURED,
    /** An answer arrived but couldn't be understood. */
    BAD_RESPONSE,
    /** The request itself was rejected (bad source, too long, ...). */
    INVALID,
    OTHER,
}

data class PodcastProblem(val kind: PodcastProblemKind, val message: String, val retryAfterSec: Int? = null) {
    /** Fixed only in Settings (sign in or request access); retrying on its own won't help. */
    val needsSettings get() = kind == PodcastProblemKind.SIGN_IN || kind == PodcastProblemKind.ACCESS
    /** Label for the button that opens Settings, when [needsSettings]. */
    val settingsLabel get() = when (kind) {
        PodcastProblemKind.SIGN_IN -> "Sign in in Settings"
        PodcastProblemKind.ACCESS -> "Request access"
        else -> null
    }
    /** Worth trying again later without the user doing anything. */
    val temporary get() = kind in setOf(PodcastProblemKind.OFFLINE, PodcastProblemKind.STUDIO_DOWN, PodcastProblemKind.STUDIO_BUSY,
        PodcastProblemKind.SLOW_DOWN, PodcastProblemKind.BAD_RESPONSE, PodcastProblemKind.OTHER)
}

fun Throwable.toPodcastProblem(): PodcastProblem {
    val api = this as? ApiException
    if (api != null) {
        val kind = when {
            api.status == 401 -> PodcastProblemKind.SIGN_IN
            api.status == 403 -> PodcastProblemKind.ACCESS
            api.status == 404 -> PodcastProblemKind.NOT_FOUND
            api.status == 429 -> when (api.code) {
                "studio_busy" -> PodcastProblemKind.STUDIO_BUSY
                "active_limit" -> PodcastProblemKind.ACTIVE_LIMIT
                "rate_limited" -> PodcastProblemKind.SLOW_DOWN
                else -> PodcastProblemKind.DAILY_LIMIT
            }
            api.code == "studio_not_configured" -> PodcastProblemKind.NOT_CONFIGURED
            api.code == "studio_bad_response" || api.code == "bad_response" -> PodcastProblemKind.BAD_RESPONSE
            api.status in setOf(502, 503, 504) -> PodcastProblemKind.STUDIO_DOWN
            api.status == 400 || api.status == 413 || api.status == 422 -> PodcastProblemKind.INVALID
            api.offline || api.status == null -> PodcastProblemKind.OFFLINE
            else -> PodcastProblemKind.OTHER
        }
        val message = when (kind) {
            PodcastProblemKind.SIGN_IN -> "Sign in to use Podcast Lounge"
            PodcastProblemKind.ACCESS -> "Your account doesn't have podcast access yet."
            PodcastProblemKind.NOT_FOUND -> "That podcast is no longer at the studio."
            PodcastProblemKind.NOT_CONFIGURED -> "The podcast studio isn't set up yet."
            else -> api.message
        }
        return PodcastProblem(kind, message, api.retryAfterSec)
    }
    return when (this) {
        is SerializationException -> PodcastProblem(PodcastProblemKind.BAD_RESPONSE, "The studio sent something this app couldn't read. Try again.")
        is IOException -> PodcastProblem(PodcastProblemKind.OFFLINE, "Can't reach Mavis. Check your connection.")
        is IllegalArgumentException, is IllegalStateException -> PodcastProblem(PodcastProblemKind.INVALID, message ?: "That request can't be sent.")
        else -> PodcastProblem(PodcastProblemKind.OTHER, message ?: "The studio couldn't complete that request.")
    }
}
