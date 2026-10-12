package com.example.dopadopa.intervention

object SuggestionInterval {
    const val KEY = "suggestion_interval_minutes"
    const val DEFAULT_MINUTES = 10
    fun minutes(value: Int) = value.coerceIn(1, 20)
    fun millis(value: Int) = minutes(value) * 60_000L
}
