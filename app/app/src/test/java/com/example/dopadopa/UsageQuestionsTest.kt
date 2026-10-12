package com.example.dopadopa

import com.example.dopadopa.ui.UsageQuestions
import org.junit.Assert.*
import org.junit.Test

class UsageQuestionsTest {
    @Test fun everyBranchFinishesInFourChoicesAndConfirmationWithinContract() {
        fun visit(answers: List<String>) {
            if (answers.size == 4) {
                val description = UsageQuestions.description(answers)
                assertTrue(description.length <= 500)
                assertTrue(description.contains("自動的な制限を希望するものではありません"))
                assertTrue(answers.all { description.contains(it) })
            } else UsageQuestions.question(answers).choices.forEach { visit(answers + it) }
        }
        visit(emptyList())
    }
    @Test(expected = IllegalArgumentException::class) fun partialAnswersCannotBecomeConfirmedGoal() {
        UsageQuestions.description(listOf("学びや調べものに使いたい"))
    }
}
