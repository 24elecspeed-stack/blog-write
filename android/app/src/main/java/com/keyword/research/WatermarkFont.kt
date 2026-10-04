package com.keyword.research

import android.graphics.Typeface

// 워터마크 글씨체를 전화번호(블로그)마다 다르게 고정해서, 여러 블로그의 사진이 전부 같은
// 폰트·색상으로 찍히면 오히려 "자동화 도구로 일괄 처리한 사진"이라는 패턴이 드러나 어뷰징으로
// 의심받기 쉽다는 우려에서 나왔다. 외부 폰트 파일을 내려받아 앱에 넣는 대신, 안드로이드에
// 기본 내장된 4개 글꼴 계열(민무늬/명조/고정폭/기본) × 4개 스타일(보통/굵게/기울임/굵고
// 기울임)을 조합해 16가지 조합을 만든다 - 전부 무료이고 다운로드나 라이선스 걱정이 없으며,
// 어떤 기기에서도 동일하게 렌더링된다. 전화번호 문자열의 해시값으로 조합 하나를 고르므로,
// 전화번호를 바꾸지 않는 한 같은 블로그는 항상 같은 글꼴을 쓴다(무작위가 아니라 고정 매칭).
object WatermarkFont {
    private val families = listOf(
        Typeface.SANS_SERIF,
        Typeface.SERIF,
        Typeface.MONOSPACE,
        Typeface.DEFAULT
    )
    private val styles = listOf(
        Typeface.NORMAL,
        Typeface.BOLD,
        Typeface.ITALIC,
        Typeface.BOLD_ITALIC
    )

    fun forPhone(phone: String): Typeface {
        val key = phone.ifBlank { "default" }
        val index = (key.hashCode() and Int.MAX_VALUE) % (families.size * styles.size)
        val family = families[index / styles.size]
        val style = styles[index % styles.size]
        return Typeface.create(family, style)
    }
}
