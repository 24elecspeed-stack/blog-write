package com.keyword.research

import android.net.Uri

// "콜라주 만들기"도 대표사진 만들기와 같은 이유(Activity가 화면을 나갔다 들어오면 새로 만들어짐)로
// 앱이 켜져 있는 동안만 유지되는 싱글턴에 편집 내용을 저장해 뒀다가 onCreate에서 복원한다.
// 콜라주 비트맵 자체는 저장하지 않고 사진 목록+편집값만 저장해서, 복원할 때 다시 렌더링한다
// (Bitmap을 정적 필드에 오래 들고 있지 않기 위함). "초기화" 버튼을 누르면 clear()로 되돌린다.
object CollageState {
    var hasData = false

    var collageCount = 3
    val perCollageCounts = intArrayOf(4, 4, 4, 4)
    var edits = arrayOf(ThumbnailCollage.PhotoEdit(), ThumbnailCollage.PhotoEdit(), ThumbnailCollage.PhotoEdit(), ThumbnailCollage.PhotoEdit())
    var photosBySlot: MutableList<List<Uri>?> = mutableListOf(null, null, null, null)
    var editingSlot = 0
    var outputWidth = "1600"
    var outputHeight = "1600"

    fun save(collageCount: Int, counts: IntArray, edits: Array<ThumbnailCollage.PhotoEdit>, photos: List<List<Uri>?>, editingSlot: Int, outputWidth: String, outputHeight: String) {
        this.collageCount = collageCount
        for (i in counts.indices) perCollageCounts[i] = counts[i]
        this.edits = edits.clone()
        photosBySlot = photos.toMutableList()
        this.editingSlot = editingSlot
        this.outputWidth = outputWidth
        this.outputHeight = outputHeight
        hasData = true
    }

    fun clear() {
        hasData = false
        collageCount = 3
        perCollageCounts.fill(4)
        edits = arrayOf(ThumbnailCollage.PhotoEdit(), ThumbnailCollage.PhotoEdit(), ThumbnailCollage.PhotoEdit(), ThumbnailCollage.PhotoEdit())
        photosBySlot = mutableListOf(null, null, null, null)
        editingSlot = 0
        outputWidth = "1600"
        outputHeight = "1600"
    }
}
