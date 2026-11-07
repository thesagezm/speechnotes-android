package com.speechnotes.app.ui.playback

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.speechnotes.app.logic.ScreenAwakePolicy
import com.speechnotes.app.logic.ScreenAwakeSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Android side of "Keep Screen On" (iOS ScreenAwake.swift parity,
 * commit 484e564). Reading surfaces REGISTER instead of writing the global
 * keepScreenOn flag, because onDisappear fires on every tab switch while a
 * composable stays alive in its nav stack, and two surfaces can be alive at
 * once — only set membership (ScreenAwakePolicy) makes the answer
 * independent of acquire/release order.
 */
object ScreenAwakeRegistry {

    private val _active = MutableStateFlow<Set<ScreenAwakeSurface>>(emptySet())
    val active: StateFlow<Set<ScreenAwakeSurface>> get() = _active

    private val _preferenceEnabled = MutableStateFlow(ScreenAwakePolicy.DEFAULT_ENABLED)
    val preferenceEnabled: StateFlow<Boolean> get() = _preferenceEnabled

    fun acquire(surface: ScreenAwakeSurface) {
        _active.value = _active.value + surface
    }

    fun release(surface: ScreenAwakeSurface) {
        _active.value = _active.value - surface
    }

    /** Seeds the preference flow from storage; called once at the root. */
    fun initPreference(context: Context) {
        _preferenceEnabled.value = readPreference(context)
    }

    /** The settings toggle's single entry point: persists AND publishes so
     *  the root effect applies the new decision within the same frame. */
    fun setPreference(context: Context, enabled: Boolean) {
        context.getSharedPreferences("speechnotes", Context.MODE_PRIVATE)
            .edit().putBoolean(ScreenAwakePolicy.PREFERENCE_KEY, enabled).apply()
        _preferenceEnabled.value = enabled
    }

    fun readPreference(context: Context): Boolean =
        context.getSharedPreferences("speechnotes", Context.MODE_PRIVATE)
            .getBoolean(ScreenAwakePolicy.PREFERENCE_KEY, ScreenAwakePolicy.DEFAULT_ENABLED)
}

/** A reading surface announces its presence for as long as it is composed. */
@Composable
fun KeepScreenOnSurface(surface: ScreenAwakeSurface) {
    DisposableEffect(surface) {
        ScreenAwakeRegistry.acquire(surface)
        onDispose { ScreenAwakeRegistry.release(surface) }
    }
}

/**
 * Mounted ONCE at the app root: applies the policy decision to the window.
 * Surfaces register/unregister; the toggle flips the preference flow — this
 * effect is the single writer of View.keepScreenOn.
 */
@Composable
fun KeepScreenOnEffect() {
    val view = LocalView.current
    val context = view.context
    remember { ScreenAwakeRegistry.initPreference(context) }
    val enabled by ScreenAwakeRegistry.preferenceEnabled.collectAsState()
    val active by ScreenAwakeRegistry.active.collectAsState()
    LaunchedEffect(enabled, active) {
        view.keepScreenOn = ScreenAwakePolicy.shouldKeepScreenAwake(enabled, active)
    }
}
