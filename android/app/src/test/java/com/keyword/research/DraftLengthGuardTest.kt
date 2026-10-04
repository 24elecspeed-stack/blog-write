package com.keyword.research

import org.junit.Assert.*
import org.junit.Test

class DraftLengthGuardTest {
    @Test fun countsSpacesButNotLineBreakControls() {
        assertEquals(7, DraftLengthGuard.bodyLength("# 제목\n\n**가나다**  \n\n 라"))
        assertEquals(0, DraftLengthGuard.bodyLength("제목만 있는 응답"))
    }

    @Test fun unwrapsCopyBlockAndRemovesInnerMarkdownDefensively() {
        val value = DraftFormatter.stripMarkdownSymbols("```\n# 제목\n\n## 소제목\n\n**본문**\n```")
        assertEquals("제목\n\n소제목\n\n본문", value)
        assertFalse(value.contains("```"))
    }

    @Test fun acceptsAnyNonEmptyDraftWithOneRequest() {
        var fullCalls = 0
        val draft = "제목\n\n짧지만 완성된 본문"
        assertEquals(draft, DraftLengthGuard.generate(
            prompt = "원본 요청",
            request = { fullCalls++; draft }
        ))
        assertEquals(1, fullCalls)
    }

    @Test fun rejectsOnlyAnEmptyResponse() {
        try {
            DraftLengthGuard.generate(
                prompt = "요청",
                request = { "   " }
            )
            fail("Empty draft must not be accepted")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("빈 원고"))
        }
    }

    @Test fun propagatesNetworkFailureWithoutCallingContinuation() {
        try {
            DraftLengthGuard.generate(
                prompt = "요청",
                request = { throw java.io.IOException("offline") }
            )
            fail("Network exception must be propagated")
        } catch (_: java.io.IOException) { }
    }
}
