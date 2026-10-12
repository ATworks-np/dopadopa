package com.example.dopadopa

import com.example.dopadopa.collector.TextObservation
import org.junit.Assert.*
import org.junit.Test

class TextObservationTest {
    @Test fun missingIsNeverInvented() { assertEquals("missing", TextObservation.from(" ", "clicked_node").status) }
    @Test fun contextIsInferred() { assertEquals("inferred", TextObservation.from("Kotlin", "descendant").observability) }
    @Test fun directIsObserved() { assertEquals("direct", TextObservation.from("Kotlin", "clicked_node").observability) }
    @Test fun descriptionKeepsOnlyTheFirstNonEmptyLabelLine() {
        assertEquals("Kotlin tutorial", TextObservation.fromDescription("\nKotlin tutorial\nChannel and metadata", "clicked_node").text)
        assertEquals("missing", TextObservation.fromDescription("\n  \n", "clicked_node").status)
        assertEquals("redacted", TextObservation.fromDescription("me@example.com\nKotlin", "clicked_node").status)
    }
    @Test fun sensitiveStringsAreNotRetained() {
        for (value in listOf("me@example.com", "https://example.com", "123456789", "x".repeat(161))) {
            val result = TextObservation.from(value, "clicked_node")
            assertEquals("redacted", result.status)
            assertNull(result.text)
        }
    }
}
