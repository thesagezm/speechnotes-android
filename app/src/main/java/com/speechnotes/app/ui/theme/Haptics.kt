package com.speechnotes.app.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** App-wide haptics with a user toggle (iOS Haptics.swift parity). */
object Haptics {
    @Volatile var enabled: Boolean = true

    private fun vibrator(context: Context): Vibrator? =
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    fun tap(context: Context) = if (enabled) perform(context, 12L) else Unit
    fun press(context: Context) = if (enabled) perform(context, 22L) else Unit
    fun warning(context: Context) = if (enabled) perform(context, 35L) else Unit
    fun success(context: Context) = if (enabled) perform(context, 15L) else Unit

    private fun perform(context: Context, ms: Long) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        }
    }
}
