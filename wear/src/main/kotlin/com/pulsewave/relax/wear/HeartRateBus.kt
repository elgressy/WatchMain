package com.pulsewave.relax.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process bridge between HeartRateForegroundService (which owns the
 * Health Services measurement + the Data Layer send) and the Compose UI.
 */
object HeartRateBus {
    private val _bpm = MutableStateFlow<Double?>(null)
    val bpm: StateFlow<Double?> = _bpm.asStateFlow()

    private val _phoneConnected = MutableStateFlow(false)
    val phoneConnected: StateFlow<Boolean> = _phoneConnected.asStateFlow()

    fun updateBpm(value: Double) {
        _bpm.value = value
    }

    fun clearBpm() {
        _bpm.value = null
    }

    fun updateConnected(value: Boolean) {
        _phoneConnected.value = value
    }
}
