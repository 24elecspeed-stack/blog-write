package com.keyword.research

/** Counts the visible body length for display without enforcing it or calling Gemini again. */
object DraftLengthGuard {
    fun bodyLength(draft: String): Int {
        val plain = DraftFormatter.stripMarkdownSymbols(draft).trim()
        val body = plain.substringAfter('\n', "").trim()
        // Spaces count. Line-break control characters do not inflate the visible character count.
        val visible = body.filterNot { it == '\r' || it == '\n' }
        return visible.codePointCount(0, visible.length)
    }

    fun generate(
        prompt: String,
        request: (String) -> String
    ): String {
        val draft = DraftFormatter.unwrapCopyBlock(request(prompt)).trim()
        check(draft.isNotBlank()) { "Gemini가 빈 원고를 반환했습니다. 다시 생성해주세요." }
        return draft
    }
}
