package com.example.dopadopa.intervention

/** Fixed analysis log lines. Free text, IDs, and tokens never enter the line. */
object AnalysisDiagnostics {
    private val clock = Regex("\\d{2}:\\d{2}:\\d{2}")
    private val statuses = setOf("completed", "insufficient_data", "failed", "discarded", "no_observations", "cancelled", "skipped")
    private val stages = setOf(
        "configuration", "service_credentials", "engine_transport", "engine_http_rejection", "engine_output",
        "engine_model_execution", "engine_model_deadline", "engine_result_validation", "model_execution",
        "model_deadline", "result_validation", "generation_validation", "trace_validation", "local_runner",
        "device_transport", "discarded", "no_observations", "cancelled", "skipped", "unknown")
    private val alignments = setOf("unknown", "aligned", "possibly_diverged")
    private val tools = setOf("get_user_goals", "get_timeline", "get_click_events", "get_past_feedback")
    private val toolPattern = tools.joinToString("|")
    private val line = Regex("\\d{2}:\\d{2}:\\d{2} status=(${statuses.joinToString("|")}) stage=(${stages.joinToString("|")}) alignment=(${alignments.joinToString("|")}) tools=(none|invalid|(?:$toolPattern)(?:,(?:$toolPattern))*)")

    fun serverStage(raw: String?): String = if (raw != null && raw in stages && raw !in setOf("device_transport", "discarded", "no_observations", "cancelled", "skipped")) raw else "unknown"

    fun tools(names: List<String>): String {
        if (names.isEmpty()) return "none"
        if (names.size > 12 || names.any { it !in tools }) return "invalid"
        return names.joinToString(",")
    }

    fun line(clock: String, status: String, stage: String, alignment: String = "unknown", tools: String = "none"): String {
        val text = "${if (clock.matches(this.clock)) clock else "00:00:00"} status=${if (status in statuses) status else "failed"} stage=${if (stage in stages) stage else "unknown"} alignment=${if (alignment in alignments) alignment else "unknown"} tools=${if (tools == "none" || tools == "invalid" || tools.split(",").all { it in this.tools }) tools else "invalid"}"
        return if (validLine(text)) text else "00:00:00 status=failed stage=unknown alignment=unknown tools=invalid"
    }

    fun validLine(value: String): Boolean = line.matches(value)
}
