package com.newoether.agora.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPayloadPolicyTest {
    @Test fun boundedPayloadStaysInline() {
        assertFalse(ToolPayloadPolicy.shouldDefer(ToolPayloadPolicy.MAX_INLINE_JSON_CHARS))
    }

    @Test fun largePayloadIsDeferred() {
        assertTrue(ToolPayloadPolicy.shouldDefer(ToolPayloadPolicy.MAX_INLINE_JSON_CHARS + 1))
    }

    @Test fun deferredLegacyPayloadHasClickableToolPlaceholder() {
        val segment = ToolPayloadPolicy.deferredSegments().single()
        assertTrue(segment.type == "tool" && segment.payloadDeferred)
    }
}
