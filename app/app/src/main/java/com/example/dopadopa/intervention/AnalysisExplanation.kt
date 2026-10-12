package com.example.dopadopa.intervention

/** User-facing evidence summary, held in runtime memory; never send to diagnostic logs. */
object AnalysisExplanation {
    data class Tool(val name: String, val count: Int, val elapsedMs: Long)
    data class Claim(val description: String, val evidenceIds: List<String>)
    data class Observation(val id: String, val time: String, val type: String, val text: String?, val status: String)
    private fun clean(text: String, limit: Int = 350) = text.filterNot { it.isISOControl() }.take(limit)

    fun render(goal: String, alignment: String, tools: List<Tool>, claims: List<Claim>,
               observations: List<Observation>, uncertainty: String): String {
        val names = mapOf("get_user_goals" to "本人のゴール", "get_timeline" to "操作の時系列",
            "get_content_events" to "クリック・表示コンテンツ観測", "get_click_events" to "クリック観測",
            "get_past_feedback" to "本人の過去回答")
        val facts = observations.associateBy { it.id }
        return buildString {
            appendLine("分析の説明（AIの仮説・内部思考の記録ではありません）")
            appendLine("ゴール: ${clean(goal)}")
            appendLine("調べた操作:")
            tools.take(12).forEach { appendLine("・${names[it.name] ?: "不明なTool"} / ${it.count}件 / ${it.elapsedMs}ms") }
            appendLine("判断: " + when (alignment) {
                "aligned" -> "ゴールに沿っている可能性"
                "possibly_diverged" -> "ゴールから離れた可能性"
                else -> "観測からは判断できない"
            })
            if (claims.isEmpty()) appendLine("判断の説明: 根拠付きの区間・転換はありません")
            claims.take(6).forEach { claim ->
                appendLine("仮説: ${clean(claim.description)}")
                claim.evidenceIds.distinct().take(4).forEach { id ->
                    val event = facts[id]
                    if (event == null) appendLine("・根拠観測を確認できません")
                    else {
                        val text = when (event.status) {
                            "available" -> event.text?.let { clean(it, 160) } ?: "文言なし"
                            "redacted" -> "機密除外（文言は表示しません）"
                            else -> "文言取得不能"
                        }
                        appendLine("・観測 ${clean(event.time, 20)} ${clean(event.type, 25)}: $text")
                    }
                }
            }
            if (claims.size > 6) appendLine("ほか${claims.size - 6}件の仮説（表示は先頭6件）")
            append("不確実性: ${clean(uncertainty, 600)}")
        }
    }
}
