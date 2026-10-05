package com.keyword.research

import android.Manifest
import android.app.Dialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Html
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.ByteArrayOutputStream
import java.io.File

// 크롬 확장프로그램 "딸깍 SNS"의 photo-writer 기능(사진을 올리면 AI가 직접 보고 HTML 본문 사이에
// 자동 배치)을 네이티브로 재구현한 독립 화면(사용자 요청 - 키워드 현장정보와 무관하게 메인 화면에서
// 바로 들어가는 메뉴). API를 쓰지 않고 수동으로 AI를 쓰기로 했다(사용자 지시) - 요청문을 복사해
// Gemini/ChatGPT 앱에 직접 붙여넣고 사진을 순서대로 첨부한 뒤, 돌아온 HTML 응답을 다시 클립보드로
// 가져와 파싱한다. 완성 후에는 확장프로그램의 3단계 화면(최종 HTML 페이지 보기, HTML 코드 바로
// 복사, 네이버로 넘기기)과 같은 동작을 제공한다.
class PhotoWriterActivity : AppCompatActivity() {

    private lateinit var photoCountText: TextView
    private lateinit var keywordText: TextView
    private lateinit var status: TextView
    private lateinit var previewWebView: WebView

    private val selectedImages = mutableListOf<Uri>()

    // 요청문을 복사한 시점의 사진 순서를 그대로 고정해둔다 - AI 응답의 USER_IMAGE_N은 그 순간의
    // 번호(0부터 시작)를 가리키므로, 나중에 selectedImages가 바뀌어도(다시 고르기 등) 번호-사진
    // 매칭이 흔들리면 안 된다.
    private var promptImages: List<Uri> = emptyList()

    // 1단계(사진 분석) 응답을 파싱한 결과 - promptImages와 같은 순서/길이다. 원본 딸깍처럼
    // "분석 먼저, 그 다음 그 분석으로 글쓰기" 2단계로 나눴다(사용자 지적 - "우리는 하나만 하고
    // 있어, 분석 후 글쓰기를 해야 해"). 비어 있으면 아직 1단계를 안 끝낸 것이다.
    private var photoDescriptions: List<String> = emptyList()

    // "지역(구/동) 선택" 버튼으로 고른 값 - 정보성 글 제목 접두사로 쓴다(사용자 요청 -
    // "정보성글 구동을 고르게 해줘 동단위가 아니라 구동이야").
    private var selectedDistrictDong: String? = null

    // GPS EXIF를 리다크션 없이 읽으려면(MediaStore.setRequireOriginal) ACCESS_MEDIA_LOCATION
    // 런타임 권한이 있어야 한다 - RichCopyTestActivity의 "위치로 사진 폴더 만들기"가 이미 쓰는
    // 패턴을 그대로 가져왔다. 이 권한 없이 호출하면 전부 null로 조용히 실패한다(사용자가
    // 실기기로 확인 - 사진 8장 전부 "위치를 찾지 못했습니다").
    private val requestMediaLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_MEDIA_LOCATION] == true) {
            pickDistrictDongInternal()
        } else {
            Toast.makeText(this, "위치 권한이 없어 사진의 GPS 정보를 읽을 수 없습니다.", Toast.LENGTH_LONG).show()
        }
    }

    // AI가 돌려준 HTML 글 - USER_IMAGE_N 자리표시자를 실제 <img> 데이터 URI로 바꾼 최종 본문.
    private var articleTitle: String = ""
    private var articleSummary: String = ""
    private var articleTags: List<String> = emptyList()
    private var finalBodyHtml: String = ""

    // 네이버 앱에 직접 붙여넣을 때(HTML 모드가 없는 네이버 블로그 앱)는 커스텀 색상 스타일이
    // 전부 지워진다(사용자가 실기기로 확인함) - 하지만 네이버 에디터 자체가 지원하는 "인용구"
    // (blockquote)는 자체 서식이 있어 붙여넣기에서 살아남을 가능성이 높다. 그래서 소제목과
    // 콜아웃 박스만 <blockquote>로 바꾼 별도 버전을 만들어 앱 붙여넣기 전용으로 쓴다.
    private var nativePasteBodyHtml: String = ""

    // 네이버 반자동 배치용으로 미리 변환해둔 단계 목록(제목 제외) - HTML 본문을 소제목/문단/표/
    // 콜아웃/사진 블록 단위로 훑어서 만든다.
    private var naverSteps: List<AutoPasteState.Step> = emptyList()

    // "캡션 전체 클립보드에 저장" 버튼용 - 사진 순서 그대로의 캡션 목록(본문에 쓰이는 것과
    // 같은 getSmartImageCaption 결과).
    private var photoCaptionsInOrder: List<String> = emptyList()

    private var watermarkText: String = ""
    private var watermarkTypeface: android.graphics.Typeface = android.graphics.Typeface.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_writer)

        photoCountText = findViewById(R.id.photoCountText)
        keywordText = findViewById(R.id.photoKeywordText)
        status = findViewById(R.id.photoWriterStatus)
        previewWebView = findViewById(R.id.photoWriterPreview)

        val entry = currentBlogEntry()
        watermarkText = entry.blogId
        watermarkTypeface = WatermarkFont.forPhone(entry.phone)

        applyInsetPadding()

        findViewById<View>(R.id.btnPickPhotosForWriter).setOnClickListener { pickImages() }
        findViewById<Button>(R.id.btnPickDistrictDong).setOnClickListener { pickDistrictDong() }
        findViewById<Button>(R.id.btnCopyVisionPrompt).setOnClickListener { copyVisionPrompt() }
        findViewById<Button>(R.id.btnSharePhotosToAi).setOnClickListener { sharePhotosToAi() }
        findViewById<Button>(R.id.btnOpenGeminiFromPhotoWriter).setOnClickListener { openGemini() }
        findViewById<Button>(R.id.btnOpenChatGptFromPhotoWriter).setOnClickListener { openChatGpt() }
        findViewById<Button>(R.id.btnImportVisionAnalysis).setOnClickListener { importVisionAnalysis() }
        findViewById<Button>(R.id.btnCopyPhotoPromptCase).setOnClickListener { copyWritingPromptCase() }
        findViewById<Button>(R.id.btnCopyPhotoPromptInformational).setOnClickListener { copyWritingPromptInformational() }
        findViewById<Button>(R.id.btnImportPhotoDraft).setOnClickListener { importPhotoDraft() }
        findViewById<Button>(R.id.btnOpenFinalHtmlPage).setOnClickListener { openFinalHtmlPage() }
        findViewById<Button>(R.id.btnCopyHtmlCode).setOnClickListener { copyHtmlCode() }
        findViewById<Button>(R.id.btnCopyTags).setOnClickListener { copyTags() }
        findViewById<Button>(R.id.btnCopyPhotoArticle).setOnClickListener { copyRichContent() }
        findViewById<Button>(R.id.btnStartNaverAutoPasteFromPhotoWriter).setOnClickListener { startNaverAutoPaste() }
        findViewById<Button>(R.id.btnOpenNaverBlogFromPhotoWriter).setOnClickListener { openNaverBlogApp(clearPasteOnFailure = false) }
        findViewById<Button>(R.id.btnCopyCaptionsToClipboard).setOnClickListener { copyAllCaptionsToClipboard() }

        updatePhotoCount()
        updateKeywordText()
        renderPreview()
    }

    // "현장 정보 반영됨"이라고만 보여주면 실제로 확인 원인/작업 내용/작업 결과가 들어갔는지
    // 복사하기 전에는 확인할 방법이 없었다(사용자 지적 - "확인 원인만 넣었는데 명령문에는
    // 없어"). 실제로 프롬프트에 들어갈 현장정보 문구를 그대로 보여줘서 눈으로 바로 확인하게
    // 한다.
    private fun updateKeywordText() {
        val keyword = SearchState.lastKeyword.trim()
        if (keyword.isBlank()) {
            keywordText.text = "먼저 메인 화면에서 키워드를 검색해주세요."
            return
        }
        val caseInfo = SearchState.informationalCaseInfoBlock?.trim()
        val base = if (caseInfo.isNullOrBlank()) {
            "키워드: $keyword\n(현장 정보 없음 - \"키워드와 현장 정보\"에서 확인 원인/작업 내용/작업 결과를 입력하면 여기에 반영됩니다)"
        } else {
            "키워드: $keyword\n\n[실제로 포함될 현장 정보]\n$caseInfo"
        }
        keywordText.text = selectedDistrictDong?.let { "$base\n\n[제목 지역] $it" } ?: base
    }

    override fun onResume() {
        super.onResume()
        updateKeywordText()
    }

    private fun currentBlogEntry(): BlogProfileEntry {
        val entries = BlogProfileStore.allEntries(this)
        return entries.firstOrNull { it.id == SearchState.selectedBlogId } ?: entries.first()
    }

    private fun applyInsetPadding() {
        val root = findViewById<View>(R.id.photoWriterRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    private fun pickImages() {
        startActivityForResult(
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQUEST_IMAGES
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGES || resultCode != RESULT_OK || data == null) return
        val picked = mutableListOf<Uri>()
        data.clipData?.let { clips ->
            for (index in 0 until clips.itemCount) picked += clips.getItemAt(index).uri
        } ?: data.data?.let { picked += it }
        // 사진은 고른 순서가 아니라 찍은 날짜·시간 오름차순(이른 시간 -> 늦은 시간, 위에서
        // 아래로)으로 정렬한다(사용자 지시 - "빠른 시간순으로") - 현장 상황을 시간 흐름대로
        // 보여줘야 사진이 글 중간에 엉뚱하게 안 밀리고 순서대로 들어간다.
        selectedImages.clear()
        selectedImages += picked.sortedBy { photoDateTakenMillis(it) }
        updatePhotoCount()
    }

    // 사진의 촬영 시각 - 먼저 MediaStore의 DATE_TAKEN(갤러리가 이미 색인해둔 값)을 쓰고, 못 읽으면
    // EXIF DateTimeOriginal을 직접 읽는다. 둘 다 없으면 맨 뒤로 보낸다(Long.MAX_VALUE).
    private fun photoDateTakenMillis(uri: Uri): Long {
        try {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val value = cursor.getLong(0)
                    if (value > 0) return value
                }
            }
        } catch (e: Exception) {
            // MediaStore에 없는 URI(예: 다른 앱이 공유한 사진)일 수 있다 - EXIF로 폴백한다.
        }
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = androidx.exifinterface.media.ExifInterface(stream)
                val tag = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                if (!tag.isNullOrBlank()) {
                    val format = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                    return format.parse(tag)?.time ?: Long.MAX_VALUE
                }
            }
        } catch (e: Exception) {
            // 무시하고 맨 뒤로 보낸다.
        }
        return Long.MAX_VALUE
    }

    // 원본은 사진 목록에 실제 파일명(및 그걸 정제한 키워드)을 넣어 AI가 참고하게 했다
    // (sidepanel.js의 cleanFileName) - 포팅할 때 이 부분이 빠져 있었다(사용자가 실제 원본
    // 출력 전문을 붙여넣으며 지적).
    private fun displayNameFor(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: ""
        } catch (e: Exception) {
            ""
        }.orEmpty()
    }

    private fun updatePhotoCount() {
        photoCountText.text = if (selectedImages.isEmpty()) "선택된 사진 없음" else "선택된 사진 ${selectedImages.size}장"
    }

    // ① "사진 분석 요청문 복사" - 원본 딸깍처럼 글쓰기 전에 사진 분석을 먼저 끝낸다(사용자
    // 지적 - "익스텐션은 먼저 사진을 분석하고 난 뒤에 분석 글을 가지고 글을 써. 우리는 하나만
    // 하고 있어"). 사진 순서는 이 시점에 고정하고, 이전 분석 결과(다른 사진 기준)는 무효화한다.
    // 선택한 사진들의 EXIF GPS로 "구 동"을 구해서 사용자가 고르게 한다(사용자 요청 - "사진으로
    // 글쓰기는 exif로 무조건 구동만을 가져오는데 서울 경기 가져오지 말고... 화곡1동 화곡2동
    // 이렇게 가지고 오지 말고 화곡동 전체 동으로" / "정보성글 구동을 고르게 해줘 동단위가
    // 아니라 구동이야"). 원본 딸깍의 GPS 기반 지역 확정(reverseGeocodeToDistrict)과 같은
    // 역할이지만, 네트워크 API 대신 이미 검증된 PhotoLocationScanner의 안드로이드 Geocoder
    // 경로를 재사용한다.
    private fun pickDistrictDong() {
        if (selectedImages.isEmpty()) {
            Toast.makeText(this, "먼저 사진을 선택해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val mediaPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val hasLocationPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPerm) {
            requestMediaLocationPermission.launch(arrayOf(mediaPerm, Manifest.permission.ACCESS_MEDIA_LOCATION))
            return
        }
        pickDistrictDongInternal()
    }

    private fun pickDistrictDongInternal() {
        Toast.makeText(this, "사진 위치를 확인하는 중입니다...", Toast.LENGTH_SHORT).show()
        Thread {
            val candidates = linkedSetOf<String>()
            val seenCoords = linkedSetOf<String>()
            selectedImages.forEach { uri ->
                val latLng = PhotoLocationScanner.readGps(this, uri) ?: return@forEach
                // 같은 현장에서 찍은 사진은 좌표가 거의 같으므로, 한 번 조회한 좌표는 다시
                // 조회하지 않는다 - Nominatim 무료 API의 초당 1회 요청 제한 정책 때문.
                if (!seenCoords.add(NominatimReverseGeocodeApi.coordKey(latLng.first, latLng.second))) return@forEach
                NominatimReverseGeocodeApi.reverseGeocodeGuDong(latLng.first, latLng.second)?.let { candidates += it }
                Thread.sleep(250)
            }
            runOnUiThread {
                if (candidates.isEmpty()) {
                    Toast.makeText(this, "사진에서 구/동 단위 위치를 찾지 못했습니다(GPS 없음 또는 변환 실패).", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val options = candidates.toList()
                android.app.AlertDialog.Builder(this)
                    .setTitle("지역(구/동) 선택")
                    .setItems(options.toTypedArray()) { _, which ->
                        selectedDistrictDong = options[which]
                        updateKeywordText()
                        Toast.makeText(this, "\"${options[which]}\"을(를) 제목 지역으로 사용합니다.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("사용 안 함") { _, _ ->
                        selectedDistrictDong = null
                        updateKeywordText()
                    }
                    .show()
            }
        }.start()
    }

    private fun copyVisionPrompt() {
        if (selectedImages.isEmpty()) {
            Toast.makeText(this, "먼저 분석할 사진을 선택해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        promptImages = selectedImages.toList()
        photoDescriptions = emptyList()
        val prompt = PhotoWriterPromptBuilder.buildVisionAnalysisPrompt(
            photoCount = promptImages.size,
            topic = SearchState.lastKeyword.trim(),
            content = SearchState.informationalCaseInfoBlock?.trim().orEmpty(),
            photoNames = promptImages.map { displayNameFor(it) }
        )
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("photo-writer-vision-prompt", prompt))
        status.text = "① 사진 분석 요청문을 복사했습니다 · AI 앱에 붙여넣고 사진 ${promptImages.size}장을 같은 순서로 첨부해주세요."
        Toast.makeText(this, "① 사진 분석 요청문을 복사했습니다.", Toast.LENGTH_LONG).show()
    }

    // ② "사진 분석 결과 가져오기" - AI가 돌려준 JSON을 파싱해 사진별 설명을 저장한다. 이게
    // 있어야 ③ 글쓰기 요청문에 "사전 분석된 사진 목록"을 넣을 수 있다(원본과 동일한 2단계 구조).
    private fun importVisionAnalysis() {
        if (promptImages.isEmpty()) {
            Toast.makeText(this, "먼저 ①에서 사진 분석 요청문을 복사하고 AI에게 보내주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val copied = item?.coerceToText(this)?.toString().orEmpty().trim()
        if (copied.isBlank()) {
            Toast.makeText(this, "클립보드에 가져올 분석 결과가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val raw = DraftFormatter.unwrapCopyBlock(copied)
        val descriptions = parseVisionAnalysis(raw, promptImages.size)
        if (descriptions.all { it.isBlank() }) {
            Toast.makeText(this, "분석 결과를 읽어오지 못했습니다. AI 응답이 JSON 형식인지 확인해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        photoDescriptions = descriptions
        val filled = descriptions.count { it.isNotBlank() }
        status.text = "✅ ② 사진 분석 결과를 가져왔습니다 ($filled/${promptImages.size}장) · 이제 ③ 글쓰기 요청문을 복사하세요."
        Toast.makeText(this, "분석 결과를 가져왔습니다.", Toast.LENGTH_SHORT).show()
    }

    // {"analyses":[{"index":0,"visual_description":"..."}, ...]} 형태를 파싱한다. AI가 코드블록
    // 이나 설명 문구를 앞뒤에 덧붙여도 { ... } 구간만 잘라내 관대하게 처리한다.
    private fun parseVisionAnalysis(raw: String, count: Int): List<String> {
        val results = MutableList(count) { "" }
        try {
            val jsonStart = raw.indexOf('{')
            val jsonEnd = raw.lastIndexOf('}')
            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) return results
            val json = org.json.JSONObject(raw.substring(jsonStart, jsonEnd + 1))
            val analyses = json.optJSONArray("analyses") ?: return results
            for (i in 0 until analyses.length()) {
                val item = analyses.optJSONObject(i) ?: continue
                val idx = item.optInt("index", -1)
                val desc = item.optString("visual_description").ifBlank { item.optString("description") }.ifBlank { item.optString("subject") }
                if (idx in 0 until count && desc.isNotBlank()) results[idx] = desc
            }
        } catch (e: Exception) {
            // 파싱 실패하면 전부 빈 문자열로 남고, 호출부가 실패로 안내한다.
        }
        return results
    }

    // ③ "글쓰기 요청문 복사" - ①②에서 끝낸 사진 분석 결과를 "사전 분석된 사진 목록"으로 넣어
    // 글을 쓴다. 같은 대화창에서 이어 보내면 AI가 사진을 이미 봤으므로 다시 첨부할 필요가 없다
    // (원본도 같은 세션에서 1·2단계를 이어서 진행한다).
    // "반자동 글쓰기"(RichCopyTestActivity)처럼 글을 쓰기 전에 출장 사례형/정보형을 먼저
    // 고르게 한다(사용자 요청 - "반자동 글쓰기처럼 선택을 해야 글을 쓰게끔 해줘") - 이전에는
    // 항상 정보형으로 고정돼 있었다.
    private fun copyWritingPromptCase() {
        if (!SearchState.hasLocationInfo) {
            Toast.makeText(this, "출장 사례는 메인 화면에서 지역(구)과 동을 먼저 입력해주세요. 정보성 글은 '정보형'을 눌러주세요.", Toast.LENGTH_LONG).show()
            return
        }
        copyWritingPrompt(informational = false, caseInfo = SearchState.caseInfoBlock, label = "출장 사례형")
    }

    private fun copyWritingPromptInformational() {
        copyWritingPrompt(informational = true, caseInfo = SearchState.informationalCaseInfoBlock, label = "정보형")
    }

    private fun copyWritingPrompt(informational: Boolean, caseInfo: String?, label: String) {
        val keyword = SearchState.lastKeyword.trim()
        if (keyword.isBlank()) {
            Toast.makeText(this, "먼저 메인 화면 \"키워드와 현장 정보\"에서 키워드를 검색해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (promptImages.isEmpty() || photoDescriptions.size != promptImages.size) {
            Toast.makeText(this, "먼저 ①②로 사진 분석을 끝내주세요.", Toast.LENGTH_LONG).show()
            return
        }
        // 사진은 그림 자료로만 쓰고, 글 내용은 키워드 현장 정보의 사실관계를 그대로 따른다
        // (사용자 지시) - 기존 "블로그 반자동 복사"와 같은 규칙(PromptBuilder.editorialRules)을
        // 그대로 재사용해서 사실 지어내지 않기 기준을 똑같이 지킨다.
        val editorialRules = PromptBuilder.editorialRules(informational = informational, baseRules = PromptTemplateStore.load(this))
        val prompt = PhotoWriterPromptBuilder.build(
            keyword = keyword,
            caseInfo = caseInfo,
            editorialRules = editorialRules,
            blogProfile = currentBlogEntry(),
            photoDescriptions = photoDescriptions,
            districtDong = selectedDistrictDong,
            informational = informational
        )
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("photo-writer-prompt", prompt))
        status.text = "③ 글쓰기 요청문을 복사했습니다($label) · 사진 분석했던 그 대화창에 이어서 붙여넣으면 사진을 다시 첨부할 필요가 없습니다."
        Toast.makeText(this, "③ 글쓰기 요청문을 복사했습니다.", Toast.LENGTH_LONG).show()
    }

    // "사진 공유하기" - 요청문에는 사진 자체를 담을 수 없으니(순수 텍스트), 사용자가 Gemini/
    // ChatGPT 앱에서 갤러리를 다시 열어 같은 사진을 번호 순서까지 맞춰 고르는 수고를 덜어준다.
    // 안드로이드 공유 시트(ACTION_SEND_MULTIPLE)로 ①에서 고정해둔 promptImages를 그 순서
    // 그대로 넘기면, 공유 대상 앱(Gemini/ChatGPT 등)이 받은 순서대로 첨부해준다.
    private fun sharePhotosToAi() {
        if (promptImages.isEmpty()) {
            Toast.makeText(this, "먼저 \"① 사진 분석 요청문 복사\"를 눌러 사진 순서를 고정해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(promptImages))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "사진을 보낼 AI 앱 선택"))
            status.text = "공유 목록에서 Gemini나 ChatGPT를 선택하면 사진 ${promptImages.size}장이 그대로 전달됩니다. 이어서 복사해둔 분석 요청문을 붙여넣고 전송하세요."
        } catch (e: Exception) {
            Toast.makeText(this, "사진 공유를 열지 못했습니다: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openGemini() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/app")))
        } catch (e: Exception) {
            Toast.makeText(this, "Gemini 웹을 열지 못했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openChatGpt() {
        try {
            val launch = packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/"))
            startActivity(launch)
        } catch (e: Exception) {
            Toast.makeText(this, "ChatGPT 앱이나 브라우저를 열지 못했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    // "AI가 써준 글 가져오기" - AI 앱에서 복사해온 HTML 응답을 클립보드에서 읽어, TITLE/SUMMARY/
    // TAGS 메타데이터와 본문을 분리하고, USER_IMAGE_N 자리표시자를 promptImages 순서에 맞춰
    // 실제 사진으로 바꾼다.
    private fun importPhotoDraft() {
        if (promptImages.isEmpty()) {
            Toast.makeText(this, "먼저 요청문을 복사하고 AI에게 사진과 함께 보내주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val copied = item?.coerceToText(this)?.toString().orEmpty().trim()
        if (copied.isBlank()) {
            Toast.makeText(this, "클립보드에 가져올 글이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val raw = DraftFormatter.unwrapCopyBlock(copied)
        val bodyHtml = mergeStrayPhotoCaptions(stripMetaComments(raw))
        if (bodyHtml.isBlank()) {
            Toast.makeText(this, "클립보드 내용에서 글을 읽어오지 못했습니다.", Toast.LENGTH_LONG).show()
            return
        }
        articleTitle = extractMeta(raw, "TITLE").ifBlank { SearchState.lastKeyword.trim().ifBlank { "제목 없음" } }
        articleSummary = extractMeta(raw, "SUMMARY")
        articleTags = extractMeta(raw, "TAGS").split(",").map { it.trim() }.filter { it.isNotBlank() }
        finalBodyHtml = embedImages(bodyHtml, promptImages)
        renderPreview()

        // 표/콜아웃을 네이티브 뷰(TableLayout/LinearLayout)로 직접 그려서 사진으로 바꾼다 -
        // 숨겨진 WebView로 캡처하는 이전 방식은 비동기 페이지 로딩·하드웨어 가속 타이밍 문제로
        // 실기기에서 계속 빈 화면이 찍혔다(사용자가 여러 번 확인). 네이티브 뷰는 measure/layout/
        // draw가 전부 동기 호출이라 이런 타이밍 문제 자체가 없다.
        val renderedBlocks = renderStyledBlocksNatively(bodyHtml)
        // 가라(삭제할) 문단/사진을 글 맨 위(제목 바로 다음)에 둔다(사용자 지시 - "가라 글과
        // 가라 사진을 맨 위에 있어야 글과 사진이 안 밀려"). RichCopyTestActivity에서 이미
        // 검증된 안전장치를 그대로 가져왔다: 실제 사진/문단이 끝으로 밀리는 네이버 쪽 증상을
        // 막기 위한 장치로, 맨 위에 투명 사진 1장 + 삭제 안내 문단을 넣어둔다.
        val decoyUri = ensureDecoyPhotoUri()
        naverSteps = buildList {
            add(AutoPasteState.Step.Text("$DECOY_TEXT\n\n"))
            add(AutoPasteState.Step.Image(decoyUri))
            add(AutoPasteState.Step.MoveBelowImage)
            addAll(buildNaverSteps(bodyHtml, promptImages, renderedBlocks))
        }
        photoCaptionsInOrder = naverBlockRegex().findAll(bodyHtml).mapNotNull { match ->
            val idx = match.named("imgidx")?.toIntOrNull() ?: return@mapNotNull null
            val aiCaption = match.named("imgcap")?.let { stripTags(it) }?.trim().orEmpty()
            getSmartImageCaption(idx, aiCaption)
        }.toList()
        val decoyHtml = "<p>${Html.escapeHtml(DECOY_TEXT).replace("\n", "<br>")}</p>" +
            "<img src=\"${renderedBlockAsDataUri(decoyUri)}\" style=\"max-width:100%;height:auto;display:block;margin:12px 0;\" />"
        nativePasteBodyHtml = decoyHtml + embedImages(buildNativePasteHtml(bodyHtml, renderedBlocks), promptImages)
        status.text = "✅ AI가 써준 글을 가져왔습니다 · 아래 미리보기를 확인한 뒤 복사하거나 네이버로 보내세요."
        Toast.makeText(this, "글을 가져왔습니다.", Toast.LENGTH_SHORT).show()
    }

    // 이름 있는 그룹을 쓴다 - USER_IMAGE 자리표시자에 캡션(콜론 뒤 문구)을 새로 추가하면서
    // 숫자 인덱스로 각 함수(buildNaverSteps/buildNativePasteHtml/renderStyledBlocksNatively)를
    // 맞춰야 했는데, 그룹 번호가 하나씩 밀려 한 곳이라도 놓치면 표/콜아웃 감지가 조용히
    // 깨진다 - 이름으로 찾으면 그럴 일이 없다.
    private fun naverBlockRegex(): Regex = Regex(
        "(?<imgmarker><!--\\s*USER_IMAGE_(?<imgidx>\\d+)(?::\\s*(?<imgcap>.*?))?\\s*-->)" +
            "|(?<h2><h2[^>]*>.*?</h2>)" +
            "|(?<h3><h3[^>]*>.*?</h3>)" +
            "|(?<table><table[^>]*>.*?</table>)" +
            "|(?<ul><ul[^>]*>.*?</ul>)" +
            "|(?<ol><ol[^>]*>.*?</ol>)" +
            "|(?<div><div[^>]*>.*?</div>)" +
            "|(?<p><p[^>]*>.*?</p>)",
        RegexOption.DOT_MATCHES_ALL
    )

    private fun MatchResult.named(name: String): String? =
        (groups as? kotlin.text.MatchNamedGroupCollection)?.get(name)?.value

    // 네이버 앱에 붙여넣으면 표·콜아웃 박스의 커스텀 스타일이 전부 지워진다(사용자가 실기기로
    // 확인함). 글자로 흉내 내는 대신, 그 블록을 원래 스타일 그대로 네이티브 뷰로 그려서 사진
    // 한 장으로 바꿔 끼워 넣는다(사용자 요청) - 사진은 어떤 앱에 붙여넣어도 모양이 그대로
    // 보존된다. HTML 코드 복사/최종 페이지 보기 쪽은 원본 <table>/<div>를 그대로 쓰므로
    // 건드리지 않는다. measure/layout/draw는 전부 동기 호출이라 WebView 캡처와 달리 타이밍
    // 문제가 없다.
    private fun renderStyledBlocksNatively(bodyHtml: String): Map<String, Uri> {
        val rendered = mutableMapOf<String, Uri>()
        var index = 0
        naverBlockRegex().findAll(bodyHtml).forEach { match ->
            val isTable = match.named("table") != null
            val isCallout = match.named("div") != null
            if (!isTable && !isCallout) return@forEach
            if (rendered.containsKey(match.value)) return@forEach
            val bitmap = try {
                if (isTable) renderTableNative(match.value) else renderCalloutNative(match.value)
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) {
                try {
                    rendered[match.value] = saveRenderedBlock(bitmap, index)
                    index++
                } catch (e: Exception) {
                    // 저장 실패한 블록은 renderedBlocks에서 빠져 기존처럼 텍스트로 대체된다.
                }
                bitmap.recycle()
            }
        }
        return rendered
    }

    private fun renderCalloutNative(html: String): Bitmap? {
        val density = resources.displayMetrics.density
        val widthPx = (340 * density).toInt()
        val innerHtml = Regex("<div[^>]*>(.*)</div>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: html
        val spanned = Html.fromHtml(innerHtml.trim(), Html.FROM_HTML_MODE_LEGACY)
        val text = TextView(this).apply {
            text = spanned
            textSize = 14f
            // 원본 CSS는 전부 왼쪽 정렬(기본값)인데, 캡처한 사진에서는 가운데로 보였다(사용자
            // 지적 - "원본은 왼쪽인데 캡처 사진은 가운데") - TextView를 새로 만들 때 기기/테마에
            // 따라 기본 gravity가 달라질 수 있어 명시적으로 왼쪽 정렬을 못박는다.
            gravity = android.view.Gravity.START or android.view.Gravity.TOP
            setTextColor(android.graphics.Color.parseColor("#0B1F3A"))
            setPadding((12 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }
        val border = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#2F6FED"))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#F0F6FF"))
            addView(border, LinearLayout.LayoutParams((4 * density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return drawViewToBitmap(row, widthPx)
    }

    private fun renderTableNative(html: String): Bitmap? {
        val density = resources.displayMetrics.density
        val widthPx = (340 * density).toInt()
        val rowsHtml = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.groupValues[1] }.toList()
        if (rowsHtml.isEmpty()) return null
        val table = android.widget.TableLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#CCCCCC"))
        }
        rowsHtml.forEachIndexed { rowIndex, rowHtml ->
            val cellsHtml = Regex("<t[dh][^>]*>(.*?)</t[dh]>", RegexOption.DOT_MATCHES_ALL).findAll(rowHtml).map { it.groupValues[1] }.toList()
            if (cellsHtml.isEmpty()) return@forEachIndexed
            val bgColor = if (rowIndex % 2 == 0) "#f5f5f5" else "#fafafa"
            val tr = android.widget.TableRow(this).apply {
                setBackgroundColor(android.graphics.Color.parseColor(bgColor))
            }
            cellsHtml.forEachIndexed { cellIndex, cellHtml ->
                val spanned = Html.fromHtml(cellHtml.trim(), Html.FROM_HTML_MODE_LEGACY)
                val cell = TextView(this).apply {
                    text = spanned
                    textSize = 13f
                    // 원본처럼 전부 왼쪽 정렬로 못박는다(callout과 같은 이유) - 기본 gravity에
                    // 맡기면 기기별로 가운데처럼 보이는 문제가 있었다.
                    gravity = android.view.Gravity.START or android.view.Gravity.TOP
                    setTextColor(android.graphics.Color.parseColor("#132238"))
                    val pad = (8 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                // 원본 CSS는 첫 번째 칸만 white-space:nowrap(내용 길이만큼만 좁게)이고 나머지
                // 칸이 남는 폭을 다 채운다 - 모든 칸을 똑같이 50:50으로 나누면 원본과 비율이
                // 달라 보인다(사용자 지적). 첫 칸은 wrap_content, 나머지는 weight로 채운다.
                val params = if (cellIndex == 0) {
                    android.widget.TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                } else {
                    android.widget.TableRow.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                tr.addView(cell, params)
            }
            table.addView(tr, android.widget.TableLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(1, 1, 1, 1) })
        }
        return drawViewToBitmap(table, widthPx)
    }

    // 네이티브 뷰를 실제 화면에 붙이지 않고 measure/layout/draw만 동기로 호출해 비트맵으로
    // 찍는다 - 비동기 페이지 로딩이 없는 순수 뷰 트리라 WebView.draw()와 달리 타이밍 문제가
    // 없다(측정·배치·그리기가 전부 같은 호출 안에서 끝난다).
    private fun drawViewToBitmap(view: View, widthPx: Int): Bitmap? {
        return try {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val height = view.measuredHeight.coerceAtLeast(1)
            view.layout(0, 0, widthPx, height)
            val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            view.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun saveRenderedBlock(bitmap: Bitmap, index: Int): Uri {
        val target = File(File(cacheDir, "photowriter").apply { mkdirs() }, "rendered_block_${System.currentTimeMillis()}_$index.png")
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
    }

    private fun extractMeta(raw: String, key: String): String =
        Regex("<!--\\s*$key:\\s*(.*?)\\s*-->").find(raw)?.groupValues?.get(1)?.trim().orEmpty()

    private fun stripMetaComments(raw: String): String =
        raw.replace(Regex("<!--\\s*(TITLE|SUMMARY|TAGS):.*?-->\\n?"), "").trim()

    // AI가 요청대로 <!-- USER_IMAGE_N: 캡션 --> 콜론 안에 캡션을 안 쓰고, 마커 바로 다음 줄에
    // 캡션을 평범한 <p>로 따로 써버리는 경우가 있다(사용자 지적 - "캡션이 아닌 본문에 들어가고"
    // - 실제로 보면 캡션이 일반 본문 문단처럼 보였다). 마커 바로 뒤에 짧은(60자 이하) <p>가
    // 곧장 붙어 있으면 캡션으로 보고 주석 안으로 합쳐서, 콜론 캡션과 동일하게 처리되게 한다.
    private fun mergeStrayPhotoCaptions(bodyHtml: String): String {
        val pattern = Regex("<!--\\s*USER_IMAGE_(\\d+)\\s*-->\\s*<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        return pattern.replace(bodyHtml) { match ->
            val idx = match.groupValues[1]
            val captionText = stripTags(match.groupValues[2]).trim()
            if (captionText.isNotBlank() && captionText.length <= 60) {
                "<!-- USER_IMAGE_$idx: ${captionText.replace("-->", "- ")} -->"
            } else {
                match.value
            }
        }
    }

    // HTML 본문을 소제목(h2/h3)/문단(p)/목록(ul·ol)/표(table)/콜아웃(div)/사진(USER_IMAGE_N)
    // 블록 단위로 순서대로 훑어 네이버 반자동 배치용 단계로 바꾼다. 중첩 없는 평평한 블록
    // 구조라고 가정한다(요청문에서 그렇게 쓰도록 지시했다). 표·콜아웃이 renderedBlocks에
    // 있으면(= 스타일 그대로 사진으로 변환됨) 텍스트 대신 그 사진을 끼워 넣는다.
    private fun buildNaverSteps(bodyHtml: String, images: List<Uri>, renderedBlocks: Map<String, Uri>): List<AutoPasteState.Step> {
        val steps = mutableListOf<AutoPasteState.Step>()
        naverBlockRegex().findAll(bodyHtml).forEach { match ->
            val imageIndex = match.named("imgidx")?.toIntOrNull()
            if (imageIndex != null) {
                images.getOrNull(imageIndex)?.let { uri ->
                    steps += AutoPasteState.Step.Image(uri)
                    steps += AutoPasteState.Step.MoveBelowImage
                    // 캡션을 본문에 글자로 타이핑하지 않는다(사용자 지시 - "본문에 들어가는
                    // 캡션은 없애줘") - 대신 "캡션 전체 클립보드에 저장" 버튼으로 네이버의
                    // 진짜 캡션 입력칸에 직접 붙여넣는 방식을 쓴다.
                }
                return@forEach
            }
            renderedBlocks[match.value]?.let { uri ->
                steps += AutoPasteState.Step.Image(uri)
                steps += AutoPasteState.Step.MoveBelowImage
                return@forEach
            }
            val text = stripTags(match.value)
            if (text.isBlank()) return@forEach
            val isHeading = match.named("h2") != null || match.named("h3") != null
            steps += if (isHeading) AutoPasteState.Step.Heading("$text\n\n") else AutoPasteState.Step.Text("$text\n\n")
        }
        return steps
    }

    // 같은 블록 스캐너로 소제목(h2/h3)은 <blockquote>(네이버 에디터 자체 인용구 서식)로 바꾸고,
    // 표·콜아웃은 renderedBlocks에 미리 그려둔 사진으로 치환한다(사용자 요청 - "표 부분이랑 꼭
    // 알아둘 점을 사진으로 만들어서 배치"). 둘 다 커스텀 인라인 style이 네이버 앱 붙여넣기에서
    // 지워지는 문제의 우회책이다. HTML 코드 복사/최종 페이지 보기 쪽은 원본 <table>/<div>를
    // 그대로 쓰므로 건드리지 않는다.
    private fun buildNativePasteHtml(bodyHtml: String, renderedBlocks: Map<String, Uri>): String {
        return naverBlockRegex().replace(bodyHtml) { match ->
            if (match.named("imgmarker") != null) return@replace match.value
            renderedBlocks[match.value]?.let { uri ->
                return@replace "<!-- RENDERED_BLOCK:${Uri.encode(uri.toString())} -->"
            }
            val isHeading = match.named("h2") != null || match.named("h3") != null
            if (!isHeading) return@replace match.value
            val text = stripTags(match.value)
            if (text.isBlank()) "" else "<blockquote>${Html.escapeHtml(text)}</blockquote>"
        }
    }

    private fun embedImages(bodyHtml: String, images: List<Uri>): String {
        val markerRegex = Regex(
            "<!--\\s*USER_IMAGE_(?<idx>\\d+)(?::\\s*(?<cap>.*?))?\\s*-->|<!--\\s*RENDERED_BLOCK:(?<rendered>[^\\s>]+)\\s*-->"
        )
        return markerRegex.replace(bodyHtml) { match ->
            val groups = match.groups as kotlin.text.MatchNamedGroupCollection
            val renderedUri = groups["rendered"]?.value?.let { Uri.parse(Uri.decode(it)) }
            val dataUri = if (renderedUri != null) {
                // 표/콜아웃을 그려서 캡처한 사진 - 사용자 사진이 아니라 글자 그림이라 워터마크를
                // 찍지 않고, 텍스트가 흐려지지 않도록 PNG 원본 그대로 넣는다.
                try { renderedBlockAsDataUri(renderedUri) } catch (e: Exception) { null } ?: return@replace ""
            } else {
                val uri = groups["idx"]?.value?.toIntOrNull()?.let { images.getOrNull(it) } ?: return@replace ""
                try { imageAsDataUri(uri) } catch (e: Exception) { null } ?: return@replace ""
            }
            val img = "<img src=\"$dataUri\" style=\"max-width:100%;height:auto;display:block;border-radius:8px;\" />"
            // 원본 getSmartImageCaption과 동일하게, AI가 글쓰기 단계(②단계) 주석 안에 직접 쓴
            // 25자 안팎 캡션을 우선 쓴다(사용자 지적 - "지금 캡션이 엄청 길잖아" - 파싱 버그로
            // 이 캡션을 못 읽던 때를 ①단계 설명으로 덮어쓰도록 고친 게 원인이었고, 그 순서를
            // 거꾸로 돌려놓은 게 이번 수정이다. AI가 캡션을 안 쓴 게 아니었다). imageIndex가
            // 있을 때만 getSmartImageCaption으로 나머지 보완 단계(파일명 → ①단계 설명 → 기본
            // 라벨)를 적용한다.
            val imageIdx = groups["idx"]?.value?.toIntOrNull()
            val caption = imageIdx?.let { getSmartImageCaption(it, groups["cap"]?.value.orEmpty()) }.orEmpty()
            if (renderedUri != null || caption.isNullOrBlank()) {
                "<div style=\"margin:12px 0;\">$img</div>"
            } else {
                "<figure style=\"margin:12px 0;\">$img<figcaption style=\"text-align:center;font-size:13px;color:#4A5568;margin-top:6px;\">${Html.escapeHtml(caption)}</figcaption></figure>"
            }
        }
    }

    // 원본 cleanFileName(sidepanel.js:485)과 동일 - PhotoWriterPromptBuilder의 private
    // 버전과 똑같은 로직을 캡션 보완에도 써야 해서 여기 한 번 더 둔다.
    private fun cleanFileName(filename: String): String {
        if (filename.isBlank()) return ""
        return filename
            .replace(Regex("\\.[a-zA-Z0-9]+$"), "")
            .replace(Regex("^[0-9]+[_\\s.-]*"), "")
            .replace(Regex("[_\\-]+"), " ")
            .trim()
    }

    // 원본 getSmartImageCaption(sidepanel.js:2530)을 그대로 옮겼다. AI가 ②단계(글쓰기) 주석에
    // 직접 쓴 캡션을 최우선으로 쓰고("사진 1" 같은 의미 없는 값은 무시), 없으면 순서대로
    // (의미 있는 파일명 키워드 → ①단계 시각 분석 설명의 첫 구절(3~40자) → 기본 라벨)로 내려가며
    // 보완한다. 전에는 이 순서가 아니라 ①단계 설명 전체를 40자로 잘라서 항상 먼저 썼는데,
    // 그게 AI가 ②단계에 써준 캡션을 덮어써서 캡션이 과도하게 길어졌다(사용자 지적 - "지금
    // 캡션이 엄청 길잖아" / "니가 캡션 몇번 잘못해서 안나와서 니가 넣은거잖아" - AI가 캡션을
    // 안 쓴 게 아니라 Claude의 파싱 버그로 못 읽던 것을 이런 식으로 덮어써서 "고친" 것이었다).
    // 원본의 2번째 단계(사용자가 직접 입력한 사진 메모)는 이 앱에 그런 입력란이 없어서 뺐다.
    private fun getSmartImageCaption(index: Int, aiCaptionHint: String): String {
        val hint = aiCaptionHint.trim()
        if (hint.isNotBlank() && !Regex("^사진\\s*\\d+$").matches(hint)) return hint

        val name = promptImages.getOrNull(index)?.let { displayNameFor(it) }.orEmpty()
        if (name.isNotBlank()) {
            val clean = cleanFileName(name)
            if (clean.length >= 2 && !Regex("^(IMG|DSC|PHOTO|IMAGE|KakaoTalk|Screenshot|Capture)[0-9_ -]*$", RegexOption.IGNORE_CASE).matches(clean)) {
                return clean
            }
        }

        val desc = photoDescriptions.getOrNull(index)?.trim().orEmpty()
        if (desc.isNotBlank()) {
            val stripped = desc.replace(Regex("^[^:]+:\\s*"), "").replace(Regex("^[-*]\\s*"), "")
            val firstClause = Regex("[\\n,]|(?<=[가-힣a-zA-Z])\\.\\s+").split(stripped).firstOrNull()?.trim().orEmpty()
            if (firstClause.length in 3..40) return firstClause
        }

        return if (index == 0) "대표 사진" else "상세 컷 ${index + 1}"
    }

    private fun renderedBlockAsDataUri(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("변환된 이미지를 읽을 수 없습니다.")
        return "data:image/png;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    private fun stripTags(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    private fun renderPreview() {
        previewWebView.settings.defaultFontSize = 15
        previewWebView.loadDataWithBaseURL(null, buildPreviewDocument(), "text/html", "utf-8", null)
    }

    private fun buildPreviewDocument(): String {
        val body = finalBodyHtml.ifBlank { "<p style=\"color:#4A5568;\">AI가 써준 글을 가져오면 여기에 실제 HTML 스타일로 미리보기가 표시됩니다.</p>" }
        // 원본도 TAGS 주석을 자동으로 네이버에 입력하지 않고, 자기 화면에 #태그 뱃지로
        // 보여주기만 해서 사용자가 보고 직접 넣게 한다(sidepanel.js의 finalTagsContainer).
        // 여기선 articleTags가 파싱만 되고 어디에도 안 보였던 게 문제였다(사용자 지적 -
        // "태그가... 안들어가 있네") - 똑같이 미리보기 하단에 뱃지로 보여준다.
        val tagsHtml = if (articleTags.isNotEmpty()) {
            "<div style=\"margin-top:16px;\">" +
                articleTags.joinToString(" ") { "<span style=\"display:inline-block;background:#EAF1FE;color:#173B70;padding:4px 10px;border-radius:12px;font-size:12px;margin-right:4px;\">#${Html.escapeHtml(it)}</span>" } +
                "</div>"
        } else ""
        return """
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font-family:-apple-system,Roboto,sans-serif;padding:12px;color:#132238;line-height:1.6;} img{max-width:100%;height:auto;}</style>
            </head><body>
            ${if (articleTitle.isNotBlank()) "<h1 style=\"font-size:20px;\">${Html.escapeHtml(articleTitle)}</h1>" else ""}
            $body
            $tagsHtml
            </body></html>
        """.trimIndent()
    }

    // 확장프로그램의 "최종 HTML 페이지 보기 (새 창)"와 같은 동작 - 전체 화면으로 실제 스타일을
    // 그대로 보여준다.
    private fun openFinalHtmlPage() {
        if (finalBodyHtml.isBlank()) {
            Toast.makeText(this, "먼저 AI가 써준 글을 가져와주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val webView = WebView(this).apply {
            loadDataWithBaseURL(null, buildPreviewDocument(), "text/html", "utf-8", null)
        }
        val closeButton = Button(this).apply { text = "닫기" }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(container)
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // 확장프로그램의 "HTML 코드 바로 복사"와 같은 동작 - 티스토리/Blogger처럼 HTML 모드로
    // 직접 붙여넣는 에디터용으로, 렌더링된 리치 텍스트가 아니라 HTML 소스 코드 자체를 일반
    // 텍스트로 복사한다.
    private fun copyHtmlCode() {
        if (finalBodyHtml.isBlank()) {
            Toast.makeText(this, "먼저 AI가 써준 글을 가져와주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val titleComment = if (articleTitle.isNotBlank()) "<!-- TITLE: $articleTitle -->\n\n" else ""
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("photo-writer-html", titleComment + finalBodyHtml))
        status.text = "HTML 코드를 복사했습니다 · 티스토리/Blogger의 HTML 모드에 붙여넣으세요."
        Toast.makeText(this, "HTML 코드를 복사했습니다.", Toast.LENGTH_SHORT).show()
    }

    // 원본은 AI가 써준 TAGS 주석을 네이버에 자동으로 입력하지 않고 자기 화면에 #태그 뱃지로
    // 보여주기만 한다(sidepanel.js의 finalTagsContainer) - 여기서도 똑같이, 자동 입력 대신
    // 복사 버튼으로 사용자가 네이버 태그 입력칸에 직접 붙여넣게 한다(사용자 지적 - "태그가...
    // 안들어가 있네" - articleTags는 파싱만 되고 어디에도 안 쓰이고 있었다).
    private fun copyTags() {
        if (articleTags.isEmpty()) {
            Toast.makeText(this, "먼저 AI가 써준 글을 가져와주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val text = articleTags.joinToString(" ") { "#$it" }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("photo-writer-tags", text))
        Toast.makeText(this, "태그를 복사했습니다: $text", Toast.LENGTH_LONG).show()
    }

    // "글과 사진 전체 한 번에 복사" - 네이버처럼 리치 텍스트 붙여넣기를 받는 에디터용으로,
    // 클립보드에 text/html 플레이버를 함께 담아 렌더링된 상태 그대로 붙여넣히게 한다.
    private fun copyRichContent() {
        if (nativePasteBodyHtml.isBlank()) {
            Toast.makeText(this, "먼저 AI가 써준 글을 가져와주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val htmlForClip = (if (articleTitle.isNotBlank()) "<p>${Html.escapeHtml(articleTitle)}</p>" else "") + nativePasteBodyHtml
            val plain = stripTags(htmlForClip)
            val clip = ClipData(
                "사진으로 글쓰기 결과",
                arrayOf(ClipDescription.MIMETYPE_TEXT_HTML, ClipDescription.MIMETYPE_TEXT_PLAIN),
                ClipData.Item(plain, htmlForClip)
            )
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            status.text = "전체 복사 완료 · 네이버 블로그의 빈 글에 한 번 붙여넣어 사진 위치를 확인하세요."
            Toast.makeText(this, "글과 사진을 한 번에 복사했습니다.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            status.text = "복사 실패: ${error.message}"
        }
    }

    private fun startNaverAutoPaste() {
        if (articleTitle.isBlank() || naverSteps.isEmpty()) {
            Toast.makeText(this, "제목·본문과 사진이 포함된 글을 먼저 가져와주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isPasteServiceEnabled()) {
            Toast.makeText(this, "접근성 설정에서 네이버 문단·사진 반자동 배치를 켜주세요.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        status.text = "사진을 네이버 블로그에 전달할 수 있도록 준비하고 있습니다."
        val allImages = naverSteps.filterIsInstance<AutoPasteState.Step.Image>().map { it.uri }.distinct()
        Thread {
            try {
                val sharedImages = allImages.mapIndexed { index, uri -> uri to copyToSharedCache(uri, index) }.toMap()
                val steps = buildList {
                    add(AutoPasteState.Step.Title(articleTitle))
                    naverSteps.forEach { step ->
                        when (step) {
                            is AutoPasteState.Step.Image -> add(AutoPasteState.Step.Image(sharedImages.getValue(step.uri)))
                            else -> add(step)
                        }
                    }
                }
                val trimmedSteps = if (steps.lastOrNull() is AutoPasteState.Step.MoveBelowImage) steps.dropLast(1) else steps
                runOnUiThread {
                    AutoPasteState.start(trimmedSteps)
                    status.text = "열어둔 네이버 블로그 편집 화면으로 이동해 제목과 본문을 반자동으로 입력합니다."
                    Toast.makeText(this, "네이버 블로그에서 반자동 입력을 시작합니다.", Toast.LENGTH_LONG).show()
                    openNaverBlogApp(clearPasteOnFailure = true)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    AutoPasteState.clear()
                    status.text = "사진 준비 실패: ${error.message}"
                    Toast.makeText(this, "사진을 공유용으로 준비하지 못했습니다.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // 삼성 키보드의 클립보드 보드(최근 복사 기록, 최대 20개)를 활용해 캡션을 빠르게
    // 붙여넣는 방법(사용자 제안 - "캡션 20개를 미리 저장해 놓고 사진 밑에 하나씩 터치해서
    // 붙여넣기"). 접근성 서비스로 캡션 칸을 직접 찾아 넣으려던 "캡션 채우기"는 실기기에서
    // 멈추는 문제로 실패해서 뺐는데(사용자 지시 - "하지 말라고 지워"), 이 방식은 순수
    // 클립보드 쓰기만 반복하므로 실패할 지점 자체가 없다. 순서대로 복사해두면 사용자가 각
    // 사진 설명 칸을 탭하고 키보드 클립보드 아이콘에서 골라 붙이기만 하면 된다. 클립보드
    // 보드는 최근 것 위주로 최대 20개만 유지되므로, 캡션이 20개를 넘으면 앞쪽(먼저 복사한)
    // 캡션이 밀려날 수 있다고 미리 안내한다.
    private fun copyAllCaptionsToClipboard() {
        if (photoCaptionsInOrder.isEmpty()) {
            Toast.makeText(this, "먼저 ④ AI가 써준 글을 가져와주세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (photoCaptionsInOrder.size > 20) {
            Toast.makeText(this, "캡션이 20개가 넘어서(${photoCaptionsInOrder.size}개) 키보드 클립보드 보드에 먼저 복사한 일부가 밀려날 수 있습니다.", Toast.LENGTH_LONG).show()
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        photoCaptionsInOrder.forEachIndexed { index, caption ->
            handler.postDelayed({
                clipboard.setPrimaryClip(ClipData.newPlainText("사진 ${index + 1} 캡션", caption))
                if (index == photoCaptionsInOrder.size - 1) {
                    Toast.makeText(this, "캡션 ${photoCaptionsInOrder.size}개를 클립보드에 저장했습니다. 네이버에서 각 사진 설명 칸을 탭하고 키보드 클립보드 보드에서 골라 붙여넣으세요.", Toast.LENGTH_LONG).show()
                }
            }, index * 200L)
        }
    }

    private fun openNaverBlogApp(clearPasteOnFailure: Boolean) {
        val blogIntent = packageManager.getLaunchIntentForPackage("com.nhn.android.blog")
        if (blogIntent != null) {
            startActivity(blogIntent)
        } else {
            if (clearPasteOnFailure) AutoPasteState.clear()
            status.text = "네이버 블로그 앱을 찾지 못했습니다. 앱 설치 상태를 확인해주세요."
            Toast.makeText(this, "네이버 블로그 앱을 찾지 못했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isPasteServiceEnabled(): Boolean {
        val expected = ComponentName(this, NaverPasteAccessibilityService::class.java).flattenToString()
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun copyToSharedCache(source: Uri, index: Int): Uri {
        val target = File(File(cacheDir, "photowriter").apply { mkdirs() }, "photo_writer_${System.currentTimeMillis()}_$index.jpg")
        // 표/콜아웃을 그려서 캡처한 블록(rendered_block_...)은 사용자 사진이 아니라 글자
        // 그림이라 워터마크를 찍지 않는다.
        val isRenderedBlock = source.lastPathSegment?.contains("rendered_block_") == true
        if (watermarkText.isBlank() || isRenderedBlock) {
            contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "선택한 사진을 읽을 수 없습니다." }
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            val decoded = try { ThumbnailCollage.decode(this, source, 2000) } catch (e: Exception) { null }
                ?: error("선택한 사진을 읽을 수 없습니다.")
            val watermarked = Watermark.apply(decoded, watermarkText, watermarkTypeface)
            target.outputStream().use { output -> watermarked.compress(Bitmap.CompressFormat.JPEG, 92, output) }
            if (watermarked !== decoded) decoded.recycle()
            watermarked.recycle()
        }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
    }

    // 사진의 EXIF Orientation 태그를 읽어 픽셀 자체를 돌려놓는다. BitmapFactory.decodeStream은
    // EXIF를 무시하고 저장된 픽셀 그대로 읽고, Bitmap.compress()로 만든 순수 바이트 배열에는
    // EXIF를 다시 써넣을 파일이 없어 원본 방향 정보가 통째로 사라진다 - data URI로 들어간
    // 사진이 미리보기에서 90도 돌아가 보이던 원인(사용자가 실기기로 확인).
    private fun applyExifRotation(source: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            contentResolver.openInputStream(source)?.use { stream ->
                androidx.exifinterface.media.ExifInterface(stream).getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )
            } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = android.graphics.Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun imageAsDataUri(source: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 900 || bounds.outHeight / sample > 900) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decodedRaw = contentResolver.openInputStream(source).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("사진을 변환할 수 없습니다.")
        val decoded = applyExifRotation(source, decodedRaw)
        val ratio = minOf(1f, 640f / maxOf(decoded.width, decoded.height))
        val scaledRaw = if (ratio < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt(), (decoded.height * ratio).toInt(), true) else decoded
        if (scaledRaw !== decoded) decoded.recycle()
        val scaled = Watermark.apply(scaledRaw, watermarkText, watermarkTypeface)
        if (scaled !== scaledRaw) scaledRaw.recycle()
        var quality = 60
        var bytes: ByteArray
        do {
            bytes = ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                output.toByteArray()
            }
            quality -= 8
        } while (bytes.size > 60_000 && quality >= 28)
        scaled.recycle()
        return "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    // RichCopyTestActivity의 가라 사진과 동일한 장치 - 완전 투명 PNG라 실제로는 안 보인다.
    private var decoyPhotoUriCache: Uri? = null

    private fun ensureDecoyPhotoUri(): Uri {
        decoyPhotoUriCache?.let { return it }
        val size = 170
        val transparent = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        transparent.eraseColor(android.graphics.Color.TRANSPARENT)
        val target = File(File(cacheDir, "photowriter").apply { mkdirs() }, "decoy_photo.png")
        target.outputStream().use { transparent.compress(Bitmap.CompressFormat.PNG, 100, it) }
        transparent.recycle()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        decoyPhotoUriCache = uri
        return uri
    }

    companion object {
        private const val REQUEST_IMAGES = 93
        private const val DECOY_TEXT = "안녕하세요\n사진과 이 글을 지워주세요"
    }
}
