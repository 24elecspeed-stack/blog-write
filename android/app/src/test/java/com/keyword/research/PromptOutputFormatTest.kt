package com.keyword.research

import org.junit.Assert.*
import org.junit.Test

class PromptOutputFormatTest {
    @Test fun requestsCopyBlockWithPlainTextInside() {
        val profile = BlogProfile.P1
        val rules = PromptBuilder.formatRules(BlogProfileEntry(
            id = profile.number,
            phone = profile.phone,
            titleInstruction = profile.titleInstruction,
            voiceHint = profile.voiceHint,
            openingStyle = profile.openingStyle,
            custom = false,
            blogId = profile.blogId
        ))
        assertTrue(rules.contains("코드블록 하나로 감싸"))
        assertTrue(rules.contains("시작 백틱 바로 다음 줄"))
        assertTrue(rules.contains("소제목도 아무 기호 없는 평문"))
        assertFalse(rules.contains("마크다운 서식을 적극 활용"))
        assertFalse(rules.contains("소제목마다 앞에 ## 을 붙이고"))
    }
}
