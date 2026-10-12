package com.example.dopadopa.collector

data class TextObservation(val text: String?, val status: String, val source: String, val observability: String) {
    companion object {
        fun missing() = TextObservation(null, "missing", "none", "unavailable")
        fun redacted() = TextObservation(null, "redacted", "none", "unavailable")
        fun fromDescription(text: String?, source: String): TextObservation =
            from(text?.lineSequence()?.firstOrNull { it.isNotBlank() }, source)
        fun from(text: String?, source: String): TextObservation {
            val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return missing()
            // Conservative minimization: do not retain likely identifiers, links or long prose.
            if (value.length > 160 || value.contains(Regex("[@\\n\\r]|https?://|\\d{6,}"))) return redacted()
            return TextObservation(value, "available", source, if (source == "clicked_node") "direct" else "inferred")
        }
    }
}
