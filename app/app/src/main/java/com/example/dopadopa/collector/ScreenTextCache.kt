package com.example.dopadopa.collector

/** Sanitized labels only. A screen change is never recorded as a click. */
class ScreenTextCache {
    data class Target(val window: Int, val id: String, val bounds: String, val kind: String, val identity: Int = 0)
    private var capturedAt = 0L
    private var labels = emptyMap<Target, TextObservation>()

    fun replace(at: Long, values: Map<Target, TextObservation>) {
        capturedAt = at
        labels = values.filterValues { it.status == "available" }
    }

    fun clear() { labels = emptyMap(); capturedAt = 0 }

    fun lookup(target: Target, now: Long): TextObservation {
        if (now < capturedAt || now - capturedAt > 5_000) return TextObservation.missing()
        val label = labels[target] ?: return TextObservation.missing()
        // The label came from the last screen, rather than the click event itself.
        return label.copy(source = "parent_context", observability = "inferred")
    }
}
