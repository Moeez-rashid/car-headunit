package com.moeez.cardash

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Built-in equalizer for the whole car audio output (audio session 0), so it
 * shapes Bluetooth music from the phone too. Replaces the third-party
 * "Music Volume EQ" app, which needed Google Play services (disabled on the
 * unit to save memory) and kept closing itself.
 *
 *  - Bass / Mid / Treble: the unit's own multi-band Equalizer, each band
 *    assigned by its centre frequency (< 250 Hz bass, 250 Hz–2 kHz mid,
 *    above 2 kHz treble). Each slider is −1..+1 = −12..+12 dB (or the
 *    unit's own limit if smaller).
 *  - Bass Boost: Android's BassBoost effect, 0..1.
 *
 * Works alongside AudioBooster (BOOST = loudness). Settings are saved and
 * re-applied at start-up; [ensure] re-creates the effects if another app
 * took the output mix over.
 */
object AudioEq {
    private const val TAG = "CarDash"
    private const val PREFS = "cardash"
    private const val MAX_MB = 1200 // ±12 dB

    data class Settings(val bass: Float = 0f, val mid: Float = 0f, val treble: Float = 0f, val bassBoost: Float = 0f) {
        val isFlat get() = bass == 0f && mid == 0f && treble == 0f && bassBoost == 0f
    }

    var settings = Settings()
        private set

    /** "ok", "partial" (one effect missing), "off", or "unsupported" — shown/logged for diagnosis. */
    var status = "off"
        private set

    private var eq: Equalizer? = null
    private var bb: BassBoost? = null
    private var restored = false

    fun restoreOnce(context: Context) {
        if (restored) return
        restored = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        apply(
            context,
            Settings(p.getFloat("eq_bass", 0f), p.getFloat("eq_mid", 0f), p.getFloat("eq_treble", 0f), p.getFloat("eq_bassboost", 0f)),
            save = false
        )
    }

    fun ensure(context: Context) {
        if (settings.isFlat) return
        val eqOk = eq?.let { runCatching { it.hasControl() && it.enabled }.getOrDefault(false) } ?: false
        val bbOk = bb?.let { runCatching { it.hasControl() && it.enabled }.getOrDefault(false) } ?: true
        if (!eqOk || !bbOk) {
            release()
            apply(context, settings, save = false)
        }
    }

    /** Applies [s] immediately (safe to call on every slider movement). */
    fun apply(context: Context, s: Settings, save: Boolean = true) {
        settings = Settings(s.bass.coerceIn(-1f, 1f), s.mid.coerceIn(-1f, 1f), s.treble.coerceIn(-1f, 1f), s.bassBoost.coerceIn(0f, 1f))
        if (save) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("eq_bass", settings.bass).putFloat("eq_mid", settings.mid)
            .putFloat("eq_treble", settings.treble).putFloat("eq_bassboost", settings.bassBoost).apply()

        if (settings.isFlat) {
            if (eq != null || bb != null) Log.i(TAG, "EQ off (flat)")
            release()
            status = "off"
            return
        }
        var eqDone = false
        var bbDone = false
        try {
            val e = eq ?: Equalizer(Int.MAX_VALUE, 0).also { eq = it }
            val range = e.bandLevelRange // millibels, e.g. [-1500, 1500]
            val limit = minOf(MAX_MB, -range[0].toInt(), range[1].toInt())
            for (band in 0 until e.numberOfBands) {
                val hz = e.getCenterFreq(band.toShort()) / 1000 // milliHertz -> Hz
                val v = when {
                    hz < 250 -> settings.bass
                    hz <= 2000 -> settings.mid
                    else -> settings.treble
                }
                e.setBandLevel(band.toShort(), (v * limit).toInt().toShort())
            }
            e.enabled = true
            eqDone = true
        } catch (t: Throwable) {
            Log.w(TAG, "Equalizer on the global output not available", t)
            runCatching { eq?.release() }
            eq = null
        }
        try {
            if (settings.bassBoost > 0f) {
                val b = bb ?: BassBoost(Int.MAX_VALUE, 0).also { bb = it }
                if (b.strengthSupported) b.setStrength((settings.bassBoost * 1000).toInt().toShort())
                b.enabled = true
            } else {
                runCatching { bb?.enabled = false; bb?.release() }
                bb = null
            }
            bbDone = true
        } catch (t: Throwable) {
            Log.w(TAG, "BassBoost on the global output not available", t)
            runCatching { bb?.release() }
            bb = null
        }
        status = when {
            eqDone && bbDone -> "ok"
            eqDone || bbDone -> "partial"
            else -> "unsupported"
        }
        Log.i(TAG, "EQ $status: bass=${pct(settings.bass)} mid=${pct(settings.mid)} treble=${pct(settings.treble)} bassBoost=${pct(settings.bassBoost)}")
    }

    /** Slider value -> label, e.g. "+6 dB". */
    fun dbLabel(v: Float): String {
        val db = Math.round(v * MAX_MB / 100f)
        return if (db > 0) "+$db dB" else "$db dB"
    }

    private fun pct(v: Float) = "${(v * 100).toInt()}%"

    private fun release() {
        listOf<AudioEffect?>(eq, bb).forEach { runCatching { it?.enabled = false; it?.release() } }
        eq = null
        bb = null
    }
}
