package com.moeez.carnav

import android.content.Context
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What CarDash reports about music and the booster. */
data class MusicState(
    val hasMedia: Boolean,
    val title: String?,
    val artist: String?,
    val playing: Boolean,
    val boost: Float,          // 0 = off … 1 = max
    val boostSupported: Boolean
)

/**
 * Talks to CarDash's HubProvider. CarDash owns the booster's audio effect and
 * the notification access needed to read music, so CarNav just asks it.
 * Every call returns null if CarDash isn't installed, is too old, or isn't
 * signed with the same key — the mini player then simply isn't shown.
 */
class MusicHub(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val uri = Uri.parse("content://com.moeez.cardash.hub")

    suspend fun state(): MusicState? = call("state")

    suspend fun setBoost(value: Float): MusicState? =
        call("boost", extras = Bundle().apply { putFloat("value", value.coerceIn(0f, 1f)) })

    /** action: play, pause, next or prev. */
    suspend fun transport(action: String): MusicState? = call("transport", arg = action)

    private suspend fun call(method: String, arg: String? = null, extras: Bundle? = null): MusicState? =
        withContext(Dispatchers.IO) {
            try {
                resolver.call(uri, method, arg, extras)?.let { b ->
                    MusicState(
                        hasMedia = b.getBoolean("hasMedia"),
                        // Shown exactly as the phone sends them (same as CarDash).
                        title = b.getString("title")?.takeIf { it.isNotBlank() },
                        artist = b.getString("artist")?.takeIf { it.isNotBlank() },
                        playing = b.getBoolean("playing"),
                        boost = b.getFloat("boost"),
                        boostSupported = b.getBoolean("boostSupported", true)
                    )
                }
            } catch (e: Exception) {
                null // CarDash missing / no permission / provider error
            }
        }
}
