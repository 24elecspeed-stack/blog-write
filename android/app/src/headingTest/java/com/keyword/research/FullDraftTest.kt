package com.keyword.research

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.FileProvider
import java.io.File

/** Test-build fixture. Requires an empty Naver editor; never publishes. */
class FullDraftTest : Activity() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        setContentView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 100, 40, 40)
            addView(android.widget.TextView(context).apply { text = "전체 글·사진 샘플 시험\n\n네이버에서 새 빈 원고를 먼저 열어주세요.\n한글 제목, 도입, 소제목 2개, 본문 2개, 사진 2장을 입력합니다.\n사진 아래 빈 줄은 기존 방식대로 한 번 눌러야 할 수 있습니다."; textSize = 20f })
            addView(android.widget.Button(context).apply { text = "전체 샘플 시작"; setOnClickListener { startFixture() } })
            addView(android.widget.Button(context).apply { text = "중지"; setOnClickListener { AutoPasteState.clear() } })
        })
    }
    private fun startFixture() {
        val app = this
        val photo = Bitmap.createBitmap(700, 500, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.rgb(218, 234, 249))
            drawText("TEST PHOTO 01", 50f, 260f, Paint().apply { color = Color.rgb(20, 40, 80); textSize = 60f })
        }
        val file = File(app.cacheDir, "richcopy/full-test.jpg").apply { parentFile?.mkdirs() }
        file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        photo.recycle()
        // Compare the system gallery URI with FileProvider when Naver ignores image paste.
        val uri = if (intent.getBooleanExtra("gallery_uri", false)) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "keyword-test-${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KeywordTest")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val media = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("시험 사진을 갤러리에 만들지 못했습니다.")
            contentResolver.openOutputStream(media)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
            contentResolver.update(media, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            media
        } else FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        run {
            AutoPasteState.start(listOf(
                AutoPasteState.Step.Title("TEST-01 전체 글 사진 서식 확인"),
                AutoPasteState.Step.Text("도입 문단입니다. 제목과 본문이 각각 다른 입력칸에 들어가는지 확인합니다.\n\n"),
                AutoPasteState.Step.Heading("첫 번째 소제목 확인\n\n"),
                AutoPasteState.Step.Text("첫 번째 본문입니다. 소제목만 크기 19와 굵게로 표시되어야 합니다.\n\n"),
                AutoPasteState.Step.Image(uri),
                AutoPasteState.Step.MoveBelowImage,
                AutoPasteState.Step.Heading("사진 다음 소제목 확인\n\n"),
                AutoPasteState.Step.Image(uri),
                AutoPasteState.Step.MoveBelowImage,
                AutoPasteState.Step.Text("마지막 본문입니다. 이 문단이 두 번째 사진보다 아래에 있어야 합니다. 소제목과 달리 본문 크기와 보통 굵기를 유지하는지 확인합니다.\n\n")
            ))
            app.startActivity(app.packageManager.getLaunchIntentForPackage("com.nhn.android.blog")!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
