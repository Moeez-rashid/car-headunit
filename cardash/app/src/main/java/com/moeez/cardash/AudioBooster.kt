package com.moeez.cardash

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log

/**
 * System-wide volume boost / loudness levelling, applied to the global output
 * mix (audio session 0) so it affects Bluetooth music from the phone too.
 *
 * Primary: DynamicsProcessing (API 28+) as a compressor with make-up gain and a
 * limiter — quiet tracks are lifted a lot, loud tracks barely change, and peaks
 * can't clip. Fallback: LoudnessEnhancer (plain gain with a built-in limiter).
 *
 * The effect lives as long as CarDash's process, which as the Home app is
 * effectively always running. Needs MODIFY_AUDIO_SETTINGS.
 */
object AudioBooster {

    private const val TAG = "CarDash"
    private const val PREFS = "cardash"
    private const val KEY_STRENGTH = "boost_strength"
    private const val KEY_OLD_LEVEL = "boost_level" // v10/v11 preset, migrated once

    /** 0 = off (the unit's normal sound), 1 = maximum boost. */
    var strength: Float = 0f
        private set

    /** "dynamics", "enhancer", "off", or "unsupported" — logged for diagnosis. */
    var mode: String = "off"
        private set

    private var effect: AudioEffect? = null

    // Compressor curve for a strength s in 0..1. Below the threshold audio gets
    // +makeup dB; above it, every `ratio` dB in becomes 1 dB out (then +makeup).
    // So quiet songs rise a lot, loud songs barely move, and the limiter stops
    // anything clipping. s=1 matches the old HIGH preset.
    private fun threshold(s: Float) = -24f - 8f * s
    private fun ratio(s: Float) = 1f + 1.5f * s
    private fun makeup(s: Float) = 16f * s
    private fun enhancerMb(s: Float) = (1300f * s).toInt()

    private var restored = false

    /** Restore once per process (the activity and HubProvider both ask). */
    fun restoreOnce(context: Context) {
        if (restored) return
        restore(context)
    }

    /** Restores the saved strength (call once at startup). */
    fun restore(context: Context) {
        restored = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = if (prefs.contains(KEY_STRENGTH)) {
            prefs.getFloat(KEY_STRENGTH, 0f)
        } else when (prefs.getString(KEY_OLD_LEVEL, "OFF")) {
            "LOW" -> 0.4f
            "MED" -> 0.7f
            "HIGH" -> 1f
            else -> 0f
        }
        setStrength(context, saved)
    }

    /** Re-creates the effect if another app took control of the output mix. */
    fun ensure(context: Context) {
        if (strength <= 0f) return
        val e = effect
        val healthy = e != null && runCatching { e.hasControl() && e.enabled }.getOrDefault(false)
        if (!healthy) {
            release()
            setStrength(context, strength)
        }
    }

    /** Sets the boost; safe to call on every drag event (updates in place). */
    fun setStrength(context: Context, value: Float) {
        val s = value.coerceIn(0f, 1f)
        strength = s
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_STRENGTH, s).apply()

        if (s < 0.01f) {
            if (effect != null) Log.i(TAG, "Boost off")
            release()
            mode = "off"
            return
        }
        val e = effect
        if (e != null && update(e, s)) return
        release()
        create(s)
    }

    private fun update(e: AudioEffect, s: Float): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && e is DynamicsProcessing) {
            e.setMbcBandAllChannelsTo(0, mbcBand(s))
            true
        } else if (e is LoudnessEnhancer) {
            e.setTargetGain(enhancerMb(s))
            true
        } else false
    } catch (t: Throwable) {
        Log.w(TAG, "Boost update failed, recreating", t)
        false
    }

    private fun create(s: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                effect = createDynamics(s)
                mode = "dynamics"
                Log.i(TAG, "Boost on via DynamicsProcessing (${(s * 100).toInt()}%)")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "DynamicsProcessing on session 0 failed, trying LoudnessEnhancer", t)
                release()
            }
        }
        try {
            effect = LoudnessEnhancer(0).apply {
                setTargetGain(enhancerMb(s))
                enabled = true
            }
            mode = "enhancer"
            Log.i(TAG, "Boost on via LoudnessEnhancer (${(s * 100).toInt()}%)")
        } catch (t: Throwable) {
            Log.e(TAG, "No global audio effect available on this unit", t)
            release()
            mode = "unsupported"
        }
    }

    private fun mbcBand(s: Float) = DynamicsProcessing.MbcBand(
        /* enabled */ true,
        /* cutoffFrequency */ 20000f,
        /* attackTime ms */ 10f,
        /* releaseTime ms */ 300f,
        /* ratio */ ratio(s),
        /* threshold dB */ threshold(s),
        /* kneeWidth dB */ 8f,
        /* noiseGateThreshold dB */ -90f,
        /* expanderRatio */ 1f,
        /* preGain dB */ 0f,
        /* postGain dB */ makeup(s)
    )

    private fun createDynamics(s: Float): AudioEffect {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
            /* channelCount */ 2,
            /* preEqInUse */ false, 0,
            /* mbcInUse */ true, 1,
            /* postEqInUse */ false, 0,
            /* limiterInUse */ true
        )
            .setInputGainAllChannelsTo(0f)
            .setMbcAllChannelsTo(
                DynamicsProcessing.Mbc(true, true, 1).apply { setBand(0, mbcBand(s)) }
            )
            .setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    /* inUse */ true,
                    /* enabled */ true,
                    /* linkGroup */ 0,
                    /* attackTime ms */ 1f,
                    /* releaseTime ms */ 60f,
                    /* ratio */ 10f,
                    /* threshold dB */ -1.5f,
                    /* postGain dB */ 0f
                )
            )
            .build()
        // Highest priority so we keep control of the output mix.
        return DynamicsProcessing(Int.MAX_VALUE, 0, config).apply { enabled = true }
    }

    private fun release() {
        runCatching { effect?.enabled = false }
        runCatching { effect?.release() }
        effect = null
    }
}
