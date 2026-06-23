package com.novelforge.android.generator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Prozessweiter Generierungs-Zustand. Der Hintergrund-Service schreibt ihn, die
 * UI (über das ViewModel) beobachtet ihn – so überlebt die Produktion das
 * Schließen der App und läuft im Foreground-Service weiter.
 */
object GenerationController {
    private val _progress = MutableStateFlow<GenProgress?>(null)
    val progress: StateFlow<GenProgress?> = _progress.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _auto = MutableStateFlow(false)
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    private val _completed = MutableStateFlow(0)
    val completed: StateFlow<Int> = _completed.asStateFlow()

    fun setProgress(p: GenProgress?) { _progress.value = p }
    fun setActive(id: String?) { _activeId.value = id }
    fun setError(e: String?) { _error.value = e }
    fun setAuto(on: Boolean) { _auto.value = on }
    fun incCompleted() { _completed.value = _completed.value + 1 }
    fun clearError() { _error.value = null }
}
