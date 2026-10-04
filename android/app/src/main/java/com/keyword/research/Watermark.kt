package com.keyword.research

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

// 사진 도용 방지용 - 가장자리에 아주 작은 글씨로 블로그 아이디를 새겨 넣는다. 글씨가 사진
// 밝기에 따라 안 보이는 걸 막으려고 흰색 테두리(스트로크)를 먼저 그리고 그 위에 반투명 흰 글씨를
// 채운다 - 어두운 사진 위에서도 테두리 덕분에 위치를 알아볼 수 있다. 위치를 매번 네 모서리 중
// 무작위로 골라서, 한쪽 구석만 잘라내면 워터마크가 지워지는 걸 막는다. typeface는 블로그(전화번호)
// 마다 다르게 고정해서(WatermarkFont 참고) 여러 블로그 사진이 전부 똑같은 글꼴·모양으로 찍히지
// 않게 한다 - 글꼴까지 동일하면 한 도구가 일괄 처리했다는 패턴이 더 쉽게 드러나기 때문이다.
object Watermark {
    fun apply(source: Bitmap, text: String, typeface: Typeface = Typeface.DEFAULT): Bitmap {
        if (text.isBlank()) return source
        // 워터마크 글자는 항상 대문자로 찍는다(사용자 지시) - 작은 글씨라 대문자가 더 잘 보인다.
        val upper = text.uppercase()
        val output = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val textSizePx = (minOf(output.width, output.height) * 0.022f).coerceAtLeast(14f)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            textSize = textSizePx
            this.typeface = typeface
            style = Paint.Style.STROKE
            strokeWidth = textSizePx * 0.14f
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 255, 255, 255)
            textSize = textSizePx
            this.typeface = typeface
            style = Paint.Style.FILL
            setShadowLayer(textSizePx * 0.3f, 0f, 0f, Color.argb(100, 0, 0, 0))
        }
        val padding = textSizePx * 0.9f
        val textWidth = fillPaint.measureText(upper)
        val left = padding
        val right = output.width - textWidth - padding
        val top = padding + textSizePx
        val bottom = output.height - padding
        val x = if (kotlin.random.Random.nextBoolean()) left else right
        val y = if (kotlin.random.Random.nextBoolean()) top else bottom
        canvas.drawText(upper, x, y, strokePaint)
        canvas.drawText(upper, x, y, fillPaint)
        return output
    }
}
