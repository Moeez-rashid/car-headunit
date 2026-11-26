package com.moeez.cardash

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle

/**
 * Lets CarNav (signed with the same key) show a mini music player and move the
 * volume booster without duplicating either:
 *  - the booster's audio effect lives in CarDash's process, so there is only
 *    ever one effect on the output (two apps' effects would fight);
 *  - CarDash already has the notification access needed to read music info.
 *
 * Protected by the signature permission com.moeez.cardash.permission.HUB, so
 * no other app can call it. All methods go through call():
 *   "state"                         → current state
 *   "boost"     extras["value"]     → set booster 0..1, returns state
 *   "transport" arg = play|pause|next|prev → returns state
 */
class HubProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context!!.applicationContext
        // The process may have been started just for this call.
        AudioBooster.restoreOnce(ctx)
        AudioEq.restoreOnce(ctx)
        when (method) {
            "boost" -> extras?.getFloat("value", -1f)?.takeIf { it >= 0f }
                ?.let { AudioBooster.setStrength(ctx, it) }
            "transport" -> controller()?.transportControls?.let { tc ->
                when (arg) {
                    "play" -> tc.play()
                    "pause" -> tc.pause()
                    "next" -> tc.skipToNext()
                    "prev" -> tc.skipToPrevious()
                }
            }
        }
        return state()
    }

    private fun state(): Bundle = Bundle().apply {
        putFloat("boost", AudioBooster.strength)
        putBoolean("boostSupported", AudioBooster.mode != "unsupported")
        val c = controller()
        putBoolean("hasMedia", c != null)
        if (c != null) {
            val meta = c.metadata
            putString("title", meta?.getString(MediaMetadata.METADATA_KEY_TITLE))
            putString("artist", meta?.getString(MediaMetadata.METADATA_KEY_ARTIST))
            putBoolean("playing", c.playbackState?.state == PlaybackState.STATE_PLAYING)
            putLong("position", c.playbackState?.position ?: 0L)
            putLong("duration", meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        }
    }

    /** Same choice as CarDash's music screen: playing, then titled, then the car's own. */
    private fun controller(): MediaController? {
        val ctx = context ?: return null
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return null
        val list = try {
            msm.getActiveSessions(ComponentName(ctx, NotificationListener::class.java))
        } catch (e: SecurityException) {
            return null // notification access not granted to CarDash
        }
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { !it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() }
            ?: list.firstOrNull { it.packageName.startsWith("com.autochips") }
            ?: list.firstOrNull()
    }

    // Not a data provider: only call() is used.
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
