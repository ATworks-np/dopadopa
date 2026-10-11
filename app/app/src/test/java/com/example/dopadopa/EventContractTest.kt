package com.example.dopadopa

import com.example.dopadopa.data.InteractionEvent
import org.junit.Test

class EventContractTest {
    private fun event() = InteractionEvent("e1", "s1", "other-app", "org.other", 1, "CLICK", null,
        "missing", "none", "unavailable", "real", "v1")
    @Test fun acceptsGenericAppAndMissing() { event().validate() }
    @Test(expected = IllegalArgumentException::class) fun rejectsHiddenRawText() { event().copy(text = "private").validate() }
    @Test(expected = IllegalArgumentException::class) fun rejectsInventedObservability() { event().copy(observability = "direct").validate() }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownOrigin() { event().copy(dataOrigin = "demo").validate() }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidTime() { event().copy(timestamp = -1).validate() }
}
