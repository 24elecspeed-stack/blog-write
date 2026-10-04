package com.keyword.research

import android.net.Uri

// "블로그 대표사진 만들기" 화면을 나갔다가(뒤로가기 등) 다시 들어오면 android.app.Activity는
// 매번 새 인스턴스로 만들어지기 때문에, 화면 안 필드에만 값을 들고 있으면 전부 사라진다.
// 앱이 켜져 있는 동안만 유지되는 이 싱글턴(SearchState와 같은 패턴)에 편집 내용을 저장해 뒀다가
// onCreate에서 복원한다. "초기화" 버튼을 누르면 clear()로 전부 기본값으로 되돌린다.
object ThumbnailState {
    var hasData = false

    var bgImageIndex = 0
    var bgColorIndex = 2
    var borderColorIndex = 3
    var textColorIndex = 1
    var textOutlineIndex = 1
    var shadowIndex = 2
    var fontIndex = 0
    var outputWidth = 800
    var outputHeight = 800
    var pickedImageUri: Uri? = null
    var exportedThumbnailUri: Uri? = null

    var brightness = 0
    var zoom = 1f
    var panX = 0f
    var panY = 0f
    var trimTop = 0f
    var trimBottom = 0f
    var trimLeft = 0f
    var trimRight = 0f

    var texts = arrayOf("", "", "")
    var textSizePx = floatArrayOf(70f, 70f, 70f)
    var textOffsetX = floatArrayOf(0f, 0f, 0f)
    var textOffsetY = floatArrayOf(-0.32f, 0f, 0.32f)

    fun clear() {
        hasData = false
        bgImageIndex = 0; bgColorIndex = 2; borderColorIndex = 3
        textColorIndex = 1; textOutlineIndex = 1; shadowIndex = 2; fontIndex = 0
        outputWidth = 800; outputHeight = 800
        pickedImageUri = null
        exportedThumbnailUri = null
        brightness = 0; zoom = 1f; panX = 0f; panY = 0f
        trimTop = 0f; trimBottom = 0f; trimLeft = 0f; trimRight = 0f
        texts = arrayOf("", "", "")
        textSizePx = floatArrayOf(70f, 70f, 70f)
        textOffsetX = floatArrayOf(0f, 0f, 0f)
        textOffsetY = floatArrayOf(-0.32f, 0f, 0.32f)
    }
}
