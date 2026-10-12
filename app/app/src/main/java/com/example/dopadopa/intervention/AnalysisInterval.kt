package com.example.dopadopa.intervention

/** Automatic analysis period. Suggestion spacing and the 10-minute pause stay separate. */
object AnalysisInterval {
    const val KEY = "analysis_interval_minutes"
    const val DEFAULT_MINUTES = 1
    fun minutes(value: Int) = value.coerceIn(1, 20)
    fun millis(value: Int) = minutes(value) * 60_000L
}
