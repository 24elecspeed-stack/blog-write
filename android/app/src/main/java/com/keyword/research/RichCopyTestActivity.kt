package com.keyword.research

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
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
import android.content.ComponentName
import android.text.Html
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File
import java.io.ByteArrayOutputStream
import android.util.Base64

class RichCopyTestActivity : AppCompatActivity() {
    // 메인 화면에 있던 "콘텐츠 생성"이 이 화면으로 옮겨왔다(사용자 요청 - 생성 결과 상자가
    // 두 화면에 중복돼 있어서 하나로 합침). 블로그/글쓰기 스타일 선택과 지역·증상 등 입력칸은
    // 그대로 메인 화면에 남아있고, 그 값은 SearchState에 담겨 여기로 넘어온다.
    private enum class ContentMode { CASE, INFORMATIONAL }
    private var selectedContentMode: ContentMode = ContentMode.CASE

    private val selectedImages = mutableListOf<Uri>()
    private val imagePositions = linkedMapOf<Int, MutableList<Uri>>()
    private lateinit var articleText: EditText
    private lateinit var previewContainer: LinearLayout
    private lateinit var status: TextView
    private var pendingLocationAction: (() -> Unit)? = null
    // 사진 도용 방지용 워터마크 문구 - 메인 화면에서 선택된 블로그의 아이디(전화번호별로 고정)를
    // Intent로 넘겨받는다. "사진 선택 후 자동 배치"와 "콜라주 결과 불러오기"를 포함해, 최종적으로
    // 네이버에 전달되는 모든 사진에 이 문구를 새긴다.
    private var watermarkText: String = ""
    // 워터마크 글꼴 - 블로그(전화번호)마다 다르게 고정한다(WatermarkFont 참고).
    private var watermarkTypeface: android.graphics.Typeface = android.graphics.Typeface.DEFAULT

    private val requestMediaPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val action = pendingLocationAction
        pendingLocationAction = null
        if (hasMediaPermission()) {
            action?.invoke()
        } else {
            Toast.makeText(this, "사진 접근 권한이 있어야 위치로 가져올 수 있습니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rich_copy_test)
        articleText = findViewById(R.id.inputRichArticle)
        enableArticleEditorScrolling()
        previewContainer = findViewById(R.id.richPreviewContainer)
        status = findViewById(R.id.richCopyStatus)
        articleText.setText(intent.getStringExtra(EXTRA_DRAFT).orEmpty())
        targetDong = intent.getStringExtra(EXTRA_DONG).orEmpty().trim()
        watermarkText = intent.getStringExtra(EXTRA_WATERMARK).orEmpty().trim()
        watermarkTypeface = WatermarkFont.forPhone(intent.getStringExtra(EXTRA_WATERMARK_PHONE).orEmpty())
        applyTopInsetPadding()
        findViewById<Button>(R.id.btnGeneratePrompt).setOnClickListener { onGeneratePromptClicked() }
        findViewById<Button>(R.id.btnGenerateInformationalPrompt).setOnClickListener { onGenerateInformationalPromptClicked() }
        findViewById<Button>(R.id.btnGenerateDraft).setOnClickListener { onGenerateDraftClicked() }
        findViewById<Button>(R.id.btnWritingGuide).setOnClickListener { showWritingGuideDialog() }
        findViewById<Button>(R.id.btnEditBasePrompt).setOnClickListener { showEditBasePromptDialog() }
        findViewById<Button>(R.id.btnCopyPrompt).setOnClickListener { onCopyClicked() }
        findViewById<Button>(R.id.btnOpenChatGpt).setOnClickListener { onOpenChatGptClicked() }
        findViewById<Button>(R.id.btnResetPrompt).setOnClickListener { onResetPromptClicked() }
        findViewById<Button>(R.id.btnImportClipboardDraft).setOnClickListener { importDraftFromClipboard() }
        findViewById<View>(R.id.btnPickRichImages).setOnClickListener { pickImages() }
        findViewById<View>(R.id.btnImportByLocation).setOnClickListener { ensureMediaPermission { pickDistrictThenMakeFolders() } }
        findViewById<View>(R.id.btnOpenCollageFromRich).setOnClickListener {
            startActivity(
                Intent(this, CollageActivity::class.java)
                    .putExtra(CollageActivity.EXTRA_WATERMARK, watermarkText)
                    .putExtra(CollageActivity.EXTRA_WATERMARK_PHONE, intent.getStringExtra(EXTRA_WATERMARK_PHONE).orEmpty())
            )
        }
        findViewById<View>(R.id.btnOpenThumbnailFromRich).setOnClickListener { startActivity(Intent(this, ThumbnailActivity::class.java)) }
        findViewById<View>(R.id.btnLoadCollages).setOnClickListener { loadCollageResults() }
        findViewById<View>(R.id.btnLoadThumbnail).setOnClickListener { loadRepresentativeThumbnail() }
        findViewById<View>(R.id.btnShuffleRichImages).setOnClickListener { arrangeImagesRandomly() }
        findViewById<Button>(R.id.btnCopyRichContent).setOnClickListener { copyRichContent() }
        findViewById<Button>(R.id.btnStartNaverAutoPaste).setOnClickListener { startNaverAutoPaste() }
        findViewById<Button>(R.id.btnOpenNaverBlog).setOnClickListener { openNaverBlogApp(clearPasteOnFailure = false) }
        renderPreview()
    }

    private fun currentBlogEntry(): BlogProfileEntry {
        val entries = BlogProfileStore.allEntries(this)
        return entries.firstOrNull { it.id == SearchState.selectedBlogId } ?: entries.first()
    }

    // 글 입력칸이 화면 전체 ScrollView 안에 있어서 손가락 이동을 바깥 화면이 먼저 가져가면
    // 긴 원고를 편집할 때 입력칸 내부가 움직이지 않는다. 입력칸을 누르고 움직이는 동안에는
    // 부모가 터치를 가로채지 못하게 하고, 손을 떼면 다시 전체 화면 스크롤을 허용한다.
    private fun enableArticleEditorScrolling() {
        articleText.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    view.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun onGeneratePromptClicked() {
        if (SearchState.lastKeyword.isBlank()) {
            Toast.makeText(this, "먼저 메인 화면 키워드 찾기에서 키워드를 검색해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SearchState.hasLocationInfo) {
            Toast.makeText(this, "메인 화면에서 지역(구)과 동을 입력하거나 무작위로 채워주세요.", Toast.LENGTH_LONG).show()
            return
        }
        selectedContentMode = ContentMode.CASE
        val prompt = PromptBuilder.build(
            SearchState.lastKeyword, SearchState.lastCompetitorPosts, SearchState.selectedStyle, currentBlogEntry(),
            SearchState.caseInfoBlock, baseRules = PromptTemplateStore.load(this)
        )
        articleText.setText(prompt)
        status.text = "출장 사례 기준 적용됨 · 제공한 현장 사실만 사용합니다."
    }

    private fun onGenerateInformationalPromptClicked() {
        if (SearchState.lastKeyword.isBlank()) {
            Toast.makeText(this, "먼저 메인 화면 키워드 찾기에서 키워드를 검색해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        selectedContentMode = ContentMode.INFORMATIONAL
        val prompt = PromptBuilder.build(
            SearchState.lastKeyword, SearchState.lastCompetitorPosts, SearchState.selectedStyle, currentBlogEntry(),
            SearchState.informationalCaseInfoBlock, informational = true,
            baseRules = PromptTemplateStore.load(this)
        )
        articleText.setText(prompt)
        status.text = "정보성 글 기준 적용됨 · 답 먼저, 홍보 문구 없이 작성합니다."
    }

    // 확장프로그램의 실제 "글 생성" 버튼과 동일한 동작: 프롬프트를 Gemini API에 직접 보내서
    // 진짜 초안을 받아온다.
    private fun onGenerateDraftClicked() {
        if (SearchState.lastKeyword.isBlank()) {
            Toast.makeText(this, "먼저 메인 화면 키워드 찾기에서 키워드를 검색해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val keys = ApiKeyStore.load(this)
        if (!keys.hasGeminiKey) {
            Toast.makeText(this, "Gemini API 키를 설정에 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val blogEntry = currentBlogEntry()
        if (selectedContentMode == ContentMode.CASE && !SearchState.hasLocationInfo) {
            Toast.makeText(this, "출장 사례는 메인 화면에서 지역(구)과 동을 먼저 입력해주세요. 정보성 글은 '정보형'을 먼저 눌러주세요.", Toast.LENGTH_LONG).show()
            return
        }
        val informational = selectedContentMode == ContentMode.INFORMATIONAL
        val sourceInfo = if (informational) SearchState.informationalCaseInfoBlock else SearchState.caseInfoBlock
        val prompt = PromptBuilder.build(
            SearchState.lastKeyword, SearchState.lastCompetitorPosts, SearchState.selectedStyle, blogEntry,
            sourceInfo, informational = informational,
            baseRules = PromptTemplateStore.load(this)
        )
        val systemInstruction = PromptBuilder.systemInstruction(
            blogEntry, informational, PromptTemplateStore.load(this)
        )
        val generateButton = findViewById<Button>(R.id.btnGenerateDraft)
        if (!generateButton.isEnabled) return
        generateButton.isEnabled = false
        generateButton.text = "Gemini 초안 생성 중"
        status.text = "AI 초안 생성 중 · 완성된 글을 한 번에 받습니다"

        Thread {
            try {
                val draft = DraftLengthGuard.generate(prompt, request = { requestPrompt -> GeminiApi.generateDraftWithRetry(
                    context = this,
                    apiKeys = keys.geminiApiKeys,
                    prompt = requestPrompt,
                    systemInstruction = systemInstruction,
                    onAttempt = { keyIndex, total ->
                        if (total > 1) runOnUiThread {
                            status.text = "🤖 AI 글 초안 생성 중... (키 #${keyIndex + 1}/$total 시도)"
                        }
                    }
                ) })
                runOnUiThread {
                    articleText.setText(insertDecoyParagraph(DraftFormatter.normalizeParagraphSpacing(draft)))
                    applyDecoyIfActive()
                    renderPreview()
                    status.text = "AI 초안 생성 완료 · 본문 ${DraftLengthGuard.bodyLength(draft)}자 (공백 포함)"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "글 생성 실패: ${e.message}"
                    AlertDialog.Builder(this)
                        .setTitle("글 생성 실패")
                        .setMessage(e.message ?: "알 수 없는 오류")
                        .setPositiveButton("확인", null)
                        .show()
                }
            } finally {
                runOnUiThread {
                    generateButton.isEnabled = true
                    generateButton.text = "Gemini로 초안 생성"
                }
            }
        }.start()
    }

    private fun onResetPromptClicked() {
        articleText.setText("")
        selectedContentMode = ContentMode.CASE
        decoyActive = false
        status.text = "사진을 선택하면 문단 사이에 자동 배치됩니다."
        renderPreview()
    }

    private fun onCopyClicked() {
        val text = articleText.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "먼저 글을 만들어주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("prompt", text))
        Toast.makeText(this, "클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
        scrollToPreview()
    }

    // "배치 미리보기"(및 그 아래 복사/붙여넣기 버튼들)는 처음엔 화면에서 아예 숨겨둔다
    // (activity_rich_copy_test.xml의 previewSection, 기본 visibility="gone") - 스크롤해서
    // 우연히 보이는 게 아니라, "내용 복사"를 눌러야만 그 자리로 화면이 넘어가게(사용자 요청:
    // "복사을 눌러야 배치 미리보기로 넘어가게") 한다. 글 작성 → 복사 → 사진 배치 확인이
    // 순서대로 이어지는 흐름이 되도록.
    private fun scrollToPreview() {
        val previewSection = findViewById<View>(R.id.previewSection)
        val scrollView = findViewById<ScrollView>(R.id.rootScroll)
        previewSection.visibility = View.VISIBLE
        previewSection.post {
            scrollView.smoothScrollTo(0, previewSection.top)
        }
    }

    // "내용 복사" 옆에 나란히 둔 버튼 - 클립보드에 복사한 뒤 곧바로 ChatGPT 앱(없으면 웹)을
    // 열어서 이어서 다듬을 수 있게 한다(사용자 요청).
    private fun onOpenChatGptClicked() {
        val text = articleText.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "먼저 글을 만들어주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("prompt", text))
        Toast.makeText(this, "클립보드에 복사했습니다. ChatGPT에 붙여넣어주세요.", Toast.LENGTH_LONG).show()
        try {
            val launch = packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/"))
            startActivity(launch)
        } catch (e: Exception) {
            Toast.makeText(this, "ChatGPT 앱이나 브라우저를 열지 못했습니다. 내용은 이미 복사됐습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showWritingGuideDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val sections = listOf(
            "제목과 도입" to listOf(
                "1. 고객 질문 하나에만 답합니다.",
                "2. 제목에는 핵심 키워드, 구체적 상황, 얻을 답을 담습니다.",
                "3. 첫 150자 안에 핵심 답과 안전 조치를 먼저 씁니다."
            ),
            "본문 구성" to listOf(
                "4. 사례형은 증상, 확인 원인, 실제 작업, 확인 결과를 구분합니다.",
                "5. 정보형은 문제, 가능한 원인, 확인법, 해결법, 주의사항을 구분합니다.",
                "9. 본문은 내용에 맞는 소제목 4~6개로 나눕니다."
            ),
            "표현과 용어" to listOf(
                "7. 전문 용어는 초보자도 이해하도록 쉬운 말로 풉니다.",
                "8. 키워드를 억지로 반복하지 않습니다."
            ),
            "하지 않는 것" to listOf(
                "6. 입력하지 않은 수치, 비용, 시간, 원인, 결과, 고객 반응은 만들지 않습니다.",
                "10. 출장 사례는 입력한 현장 사실만 사용합니다.",
                "11. 정보성 글은 출장 후기처럼 꾸미거나 홍보하지 않습니다.",
                "12. 위험한 자가 수리를 권하지 않습니다."
            ),
            "마무리와 검수" to listOf(
                "13. 사례형의 마지막 행동은 전화, 예약, 상담 중 하나만 안내합니다.",
                "14. 생성 후 사실성, 문맥, 반복, 과장을 자동 검수합니다."
            )
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        for ((title, rules) in sections) {
            val arrow = TextView(this).apply {
                text = "▸"
                textSize = 13f
                setTextColor(resources.getColor(R.color.text_secondary, theme))
            }
            val headerLabel = TextView(this).apply {
                text = "$title (${rules.size})"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(resources.getColor(R.color.text_primary, theme))
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                isFocusable = true
                addView(arrow, LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(headerLabel)
            }
            val body = TextView(this).apply {
                text = rules.joinToString("\n")
                textSize = 13f
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                setPadding(dp(20), 0, 0, dp(12))
                visibility = View.GONE
            }
            header.setOnClickListener {
                val expand = body.visibility == View.GONE
                body.visibility = if (expand) View.VISIBLE else View.GONE
                arrow.text = if (expand) "▾" else "▸"
            }
            container.addView(header)
            container.addView(body)
            container.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                setBackgroundColor(resources.getColor(R.color.border, theme))
            })
        }

        container.addView(TextView(this).apply {
            text = "키워드 검색에는 증상 칸만 사용합니다. 확인 원인, 작업 내용, 작업 결과는 글의 사실 근거로만 사용합니다. 모르는 항목은 비워두면 가능한 여러 유형을 조건부로 설명합니다."
            textSize = 12f
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            setPadding(0, dp(12), 0, 0)
        })

        val scroll = ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("글쓰기 기준")
            .setView(scroll)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun showEditBasePromptDialog() {
        val density = resources.displayMetrics.density
        val editor = EditText(this).apply {
            setText(PromptTemplateStore.load(this@RichCopyTestActivity))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 8
            maxLines = 12
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setHorizontallyScrolling(false)
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (340 * density).toInt()
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("기본 명령문 편집")
            .setMessage("여기에 저장한 규칙은 이후 모든 요청문과 Gemini 글 생성에 자동 적용됩니다.")
            .setView(editor)
            .setNeutralButton("기본값 복원", null)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val rules = editor.text.toString().trim()
                if (rules.isBlank()) {
                    Toast.makeText(this, "기본 명령문을 입력해주세요.", Toast.LENGTH_SHORT).show()
                } else {
                    PromptTemplateStore.save(this, rules)
                    Toast.makeText(this, "기본 명령문이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                PromptTemplateStore.reset(this)
                editor.setText(PromptBuilder.defaultEditableRules())
                Toast.makeText(this, "기본 명령문을 초기값으로 복원했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    // === 위치(구/동/번지수)로 사진 폴더 만들기 ===
    // 갤러리 사진의 GPS를 읽어 먼저 구/동으로 묶고, 사용자가 동 하나를 고르면 그 동 안의 사진을
    // 다시 구/동/번지수 단위(예: "마포구 공덕동 2-545")로 더 잘게 나눠 카메라 폴더 안에 각각 실제
    // 폴더를 만들어 옮긴다. 문단에 자동으로 배치하지는 않는다 - 정리된 폴더에서 사용자가 직접
    // 사진을 고르거나 콜라주를 만든다.
    // 메인 화면 "키워드와 현장 정보"에서 이미 동을 입력해뒀다면(이 글이 그 동에 관한 글이라는 뜻),
    // 목록에서 다시 고르게 하지 않고 그 동에 해당하는 사진을 바로 찾아 쓴다.
    private var targetDong: String = ""

    private fun hasMediaPermission(): Boolean {
        val imagePerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, imagePerm) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureMediaPermission(action: () -> Unit) {
        if (hasMediaPermission()) {
            action()
            return
        }
        pendingLocationAction = action
        val imagePerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        requestMediaPermissions.launch(arrayOf(imagePerm, Manifest.permission.ACCESS_MEDIA_LOCATION))
    }

    private fun pickDistrictThenMakeFolders() {
        status.text = "🔎 카메라 폴더 사진의 위치정보를 분석하는 중입니다..."
        Thread {
            try {
                val photos = PhotoLocationScanner.scanRecentPhotos(this)
                val (groups, diagnostics) = PhotoLocationScanner.groupByDistrict(this, photos)
                runOnUiThread {
                    if (groups.isEmpty()) {
                        status.text = "❌ 위치로 묶을 수 있는 사진을 찾지 못했습니다 · " +
                            "카메라 폴더 사진 ${diagnostics.totalScanned}장 중 GPS 있는 사진 ${diagnostics.withGps}장, " +
                            "주소 변환 성공 ${diagnostics.geocoded}장" +
                            (diagnostics.lastError?.let { "\n실패 원인: $it" } ?: "")
                        return@runOnUiThread
                    }
                    if (targetDong.isBlank()) {
                        showDistrictPicker(groups)
                        return@runOnUiThread
                    }
                    // "번동"이 "녹번동" 안에 그대로 들어있는 것처럼, 동 이름은 서로 다른데 한쪽이 다른
                    // 쪽 글자를 통째로 포함하는 경우가 있다(번동/녹번동, 신정동/신정1동 등). 단순
                    // 포함(contains) 비교로는 이런 완전히 다른 동을 잘못 골라올 수 있어, "구 동"
                    // 라벨을 공백으로 나눈 토큰 중 동 이름이 정확히 같을 때만 매칭한다.
                    val autoMatch = groups.firstOrNull { group -> group.label.split(' ').any { it == targetDong } }
                    if (autoMatch != null) {
                        status.text = "🔎 메인 화면에 입력된 \"$targetDong\"과 일치하는 위치를 찾아 자동으로 사용합니다."
                        selectDistrictAndMakeFolders(autoMatch)
                    } else {
                        // 요청한 동이 스캔된 사진 어디에도 없으면, 목록을 조용히 보여주지 않고
                        // 먼저 "그런 동은 없다"는 것부터 바로 알려준다.
                        status.text = "❌ \"$targetDong\"과 일치하는 위치의 사진을 찾지 못했습니다. 아래 목록에서 직접 골라주세요."
                        Toast.makeText(this, "\"$targetDong\"과 일치하는 사진이 없습니다.", Toast.LENGTH_LONG).show()
                        showDistrictPicker(groups)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "❌ 위치 분석 실패: ${e.message}" }
            }
        }.start()
    }

    private fun showDistrictPicker(groups: List<PhotoLocationScanner.DistrictGroup>) {
        // 그룹을 나누는 기준(구+동)은 그대로 두되, 목록에는 구를 반복해서 보여줄 필요 없이 동만
        // 표시한다(동이 안 잡혀서 구만 있는 경우는 구를 그대로 보여준다).
        val labels = groups.map { "${it.label.substringAfterLast(' ')} (${it.photos.size}장)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("사진 폴더로 만들 위치 선택")
            .setItems(labels) { _, which -> selectDistrictAndMakeFolders(groups[which]) }
            .show()
    }

    private fun selectDistrictAndMakeFolders(group: PhotoLocationScanner.DistrictGroup) {
        val targets = HashMap<Uri, String>()
        group.addressGroups.forEach { addressGroup ->
            val relativePath = "DCIM/Camera/${sanitizeFolderName(addressGroup.label)}"
            addressGroup.photos.forEach { photo -> targets[photo.uri] = relativePath }
        }
        if (targets.isEmpty()) {
            status.text = "❌ 옮길 사진이 없습니다."
            return
        }
        status.text = "\"${group.label.substringAfterLast(' ')}\" 사진 ${targets.size}장을 " +
            "번지수별 폴더 ${group.addressGroups.size}개로 옮기는 중입니다..."
        moveUrisToFolders(targets)
    }

    private fun sanitizeFolderName(label: String): String = label.replace('/', '_').replace('\\', '_').trim()

    // === 사진을 각자의 위치별 폴더로 실제로 옮기기 ===
    // Android 11(API 30) 이상에서는 이 앱이 만들지 않은 MediaStore 사진의 경로를 바꾸려면
    // createWriteRequest로 사용자 동의를 한 번 받아야 한다. uri별로 옮길 폴더(RELATIVE_PATH)가
    // 서로 다를 수 있어 uri→폴더 매핑을 들고 있다가, 동의가 끝나면 그대로 적용한다.
    private var pendingMoveTargets: Map<Uri, String> = emptyMap()

    private val moveWriteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val targets = pendingMoveTargets
        pendingMoveTargets = emptyMap()
        if (result.resultCode == RESULT_OK) {
            performPhotoMove(targets)
        } else {
            Toast.makeText(this, "사진 정리 권한 요청이 취소되어 폴더를 옮기지 못했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun moveUrisToFolders(targets: Map<Uri, String>) {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "이 안드로이드 버전에서는 사진을 자동으로 옮길 수 없습니다(수동으로 정리해주세요).", Toast.LENGTH_LONG).show()
            return
        }
        pendingMoveTargets = targets
        try {
            val pendingIntent = MediaStore.createWriteRequest(contentResolver, targets.keys.toList())
            moveWriteRequestLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } catch (e: Exception) {
            pendingMoveTargets = emptyMap()
            Toast.makeText(this, "사진 정리 권한 요청 실패: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun performPhotoMove(targets: Map<Uri, String>) {
        Thread {
            var moved = 0
            val folders = HashSet<String>()
            targets.forEach { (uri, relativePath) ->
                try {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    }
                    contentResolver.update(uri, values, null, null)
                    moved++
                    folders += relativePath
                } catch (e: Exception) {
                    // 이 사진은 건너뛰고 계속 진행한다.
                }
            }
            runOnUiThread {
                status.text = "사진 ${moved}장을 위치별 폴더 ${folders.size}개로 정리했습니다."
                Toast.makeText(this, "사진 ${moved}장을 위치별 폴더 ${folders.size}개로 정리했습니다.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun importDraftFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val copied = item?.coerceToText(this)?.toString().orEmpty().trim()
        if (copied.isBlank()) {
            Toast.makeText(this, "클립보드에 가져올 글이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val cleaned = DraftFormatter.fromMarkdown(
            copied
                .removePrefix("```").removeSuffix("```").trim()
                .removePrefix("markdown\n").trim()
        )
        articleText.setText(insertDecoyParagraph(cleaned))
        articleText.setSelection(0)
        applyDecoyIfActive()
        renderPreview()
        status.text = "클립보드에서 글을 가져왔습니다."
        Toast.makeText(this, "글을 가져왔습니다.", Toast.LENGTH_SHORT).show()
    }

    // 가라(삭제할) 문단/사진 - 사진/문단이 글 끝으로 밀리는 증상이 재발할 때를 대비한 안전장치.
    // 사용자 요청: 문단은 "안녕하세요"(본문과 무관, 겹치지 않음), 사진은 원래 사진(앱 내장
    // 배경 이미지)을 170 크기로 줄이고 "전기수리" 글자를 새겨 진짜 파일로 만든다. 클립보드
    // 가져오기나 제미나이 초안 생성으로 글이 만들어지는 시점에 제목(1번째 문단) 바로 다음
    // 문단으로 실제 텍스트에 삽입하고, 사진도 selectedImages/imagePositions에 실제로 추가해서
    // "배치 미리보기"에 항상 보이게 한다 - 나중에 사진을 새로 고르거나 다시 랜덤 배치해도
    // (arrangeImagesRandomly에서 재적용) 계속 유지된다. 실제 데이터에 포함되므로 "글과 사진
    // 전체 한 번에 복사"든 "네이버에 문단·사진 반자동 배치"든 어느 버튼을 눌러도 똑같이 나간다.
    private var decoyPhotoUriCache: Uri? = null
    private var decoyActive = false

    private fun insertDecoyParagraph(text: String): String {
        val parts = text.split(Regex("\\n\\s*\\n"), limit = 2)
        val title = parts.getOrNull(0)?.trim().orEmpty()
        val rest = parts.getOrNull(1)?.trim().orEmpty()
        decoyActive = true
        return if (rest.isBlank()) "$title\n\n$DECOY_TEXT" else "$title\n\n$DECOY_TEXT\n\n$rest"
    }

    private fun ensureDecoyPhotoUri(): Uri {
        decoyPhotoUriCache?.let { return it }
        // 삭제 자동화 대신(위험 부담 큼 - 예전에 좌표 탭/제스처 자동화가 실기기에서 반복
        // 실패했던 전례가 있음) 사진 자체를 완전 투명하게 만들어 안 보이게 한다 - 안 보이면
        // 굳이 지울 필요가 없다는 사용자 판단. JPEG는 투명을 지원하지 않아 PNG로 저장한다.
        val size = 170
        val transparent = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        transparent.eraseColor(android.graphics.Color.TRANSPARENT)
        val target = File(File(cacheDir, "richcopy").apply { mkdirs() }, "decoy_photo.png")
        target.outputStream().use { transparent.compress(Bitmap.CompressFormat.PNG, 100, it) }
        transparent.recycle()
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        decoyPhotoUriCache = uri
        return uri
    }

    private fun applyDecoyIfActive() {
        if (!decoyActive) return
        val uri = ensureDecoyPhotoUri()
        if (uri !in selectedImages) selectedImages.add(uri)
        val blocks = paragraphs()
        val position = safeBodyPosition(1.coerceAtMost(blocks.lastIndex), blocks)
        val list = imagePositions.getOrPut(position) { mutableListOf() }
        if (uri !in list) list.add(0, uri)
    }

    // 외부 AI의 코드블록은 복사 버튼을 위한 바깥 포장이고 실제 글은 평문이다. 사진 배치,
    // 미리보기, 네이버로 나가는 모든 경로는 이 함수를 거치므로 바깥 백틱을 벗긴다. 혹시 AI가
    // 지시를 어기고 #, **, - 같은 기호를 섞어도 stripMarkdownSymbols()가 방어적으로 걷어낸다.
    // normalizeParagraphSpacing까지
    // 포함된 fromMarkdown()은 쓰지 않는다 - 문단이 이미 나뉜 글을 읽을 때마다 다시 재구성되면
    // 사진 위치가 어긋나는 문제(v2.87에서 확인된 근본 원인)가 있어서, 문단 재구성 없이 기호만
    // 걷어낸다.
    private fun paragraphs(): List<String> = DraftFormatter.stripMarkdownSymbols(articleText.text.toString())
        .split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }

    private fun pickImages() {
        startActivityForResult(
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQUEST_IMAGES
        )
    }

    private fun loadRepresentativeThumbnail() {
        val uri = ThumbnailState.exportedThumbnailUri
        if (uri == null) {
            Toast.makeText(this, "먼저 대표사진 만들기에서 대표사진을 완성해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        selectedImages.remove(uri)
        selectedImages.add(uri)
        arrangeImagesRandomly()
        status.text = "대표사진을 가져와 문단 뒤에 랜덤 배치했습니다."
    }

    private fun loadCollageResults() {
        if (!CollageState.hasData || CollageState.photosBySlot.none { it != null }) {
            Toast.makeText(this, "먼저 콜라주 만들기에서 콜라주를 완성해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "콜라주 결과를 불러오는 중입니다."
        Thread {
            try {
                val loaded = CollageState.photosBySlot.take(CollageState.collageCount).mapIndexedNotNull { index, photos ->
                    photos ?: return@mapIndexedNotNull null
                    val bitmap = ThumbnailCollage.render(this, photos, CollageState.perCollageCounts[index], CollageState.edits[index])
                    saveBitmapForRichCopy(bitmap, index).also { bitmap.recycle() }
                }
                runOnUiThread {
                    selectedImages.clear()
                    selectedImages.addAll(loaded)
                    arrangeImagesRandomly()
                    status.text = "콜라주 ${loaded.size}개를 불러와 문단 뒤에 랜덤 배치했습니다."
                }
            } catch (error: Exception) {
                runOnUiThread { status.text = "콜라주 불러오기 실패: ${error.message}" }
            }
        }.start()
    }

    private fun saveBitmapForRichCopy(bitmap: Bitmap, index: Int): Uri {
        val target = File(File(cacheDir, "richcopy").apply { mkdirs() }, "collage_import_${System.currentTimeMillis()}_$index.jpg")
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGES || resultCode != RESULT_OK || data == null) return
        val picked = mutableListOf<Uri>()
        data.clipData?.let { clips ->
            for (index in 0 until clips.itemCount) picked += clips.getItemAt(index).uri
        } ?: data.data?.let { picked += it }
        // 사진은 고른 순서가 아니라 찍은 날짜·시간 오름차순(이른 시간 -> 늦은 시간)으로
        // 정렬해둔다(사용자 지시 - "빠른 시간순으로") - arrangeImagesRandomly()가 이 순서
        // 그대로 위에서 아래로 배치해서, 현장 상황이 시간 흐름대로 보이고 사진이 엉뚱한
        // 자리로 밀리지 않게 한다.
        selectedImages.clear()
        selectedImages += picked.sortedBy { photoDateTakenMillis(it) }
        arrangeImagesRandomly()
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

    private fun arrangeImagesRandomly() {
        val blocks = paragraphs()
        // 가라 사진(decoyPhotoUriCache)은 selectedImages에 실제로 들어있지만, 배치 대상이
        // 아니라 항상 1번 문단(제목 다음)에 고정돼야 한다 - 섞이면 같은 가라 사진이 다른
        // 자리에도 또 들어가거나, 반대로 제목 자리(0번)에 진짜 사진이 배치돼 가라보다 먼저
        // 나오는 문제가 재발한다(사용자 지적: "사진이 맨위에 올라왔다"). 그래서 진짜 사진만
        // 따로 골라 배치하고, 자리 후보에서도 0번(제목)을 제외해 가라가 항상 문서에서 가장
        // 먼저 나오는 사진 자리를 지키게 한다.
        val decoyUri = if (decoyActive) decoyPhotoUriCache else null
        val realImages = if (decoyUri != null) selectedImages.filter { it != decoyUri } else selectedImages
        if (blocks.isEmpty() || realImages.isEmpty()) {
            Toast.makeText(this, "문단이 나뉜 글과 사진을 먼저 준비해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        imagePositions.clear()
        // 사진은 촬영 날짜·시간 순(selectedImages에 이미 그 순서로 정렬돼 있다)으로, 본문
        // 자리도 위에서 아래로 순서대로 채운다(사용자 지시 - "날짜 시간별로 위에서 아래로") -
        // 랜덤으로 섞으면 실제 작업 순서와 사진 순서가 어긋나 보일 수 있다. 사진 수가 문단
        // 수보다 많으면 자리 목록을 순환(cycle)해서 끝까지 고르게 채운다.
        // 소제목 바로 아래에는 사진을 넣지 않는다. 제목과 소제목을 제외한 실제 본문 블록만
        // 사진 위치 후보로 사용해서, 사진은 반드시 본문 문단을 쓴 뒤에 들어가게 한다.
        val candidatePositions = blocks.indices.drop(1)
            .filter { index -> !isLikelyHeading(blocks[index]) }
            .ifEmpty { listOf(0) }
        realImages.forEachIndexed { i, uri ->
            val position = candidatePositions[i % candidatePositions.size]
            imagePositions.getOrPut(position) { mutableListOf() }.add(uri)
        }
        applyDecoyIfActive()
        enforceNoPhotoBelowHeading(blocks)
        renderPreview()
        status.text = "사진 ${realImages.size}장을 촬영 시간 순서대로 본문 문단 뒤에 배치했습니다."
    }

    /**
     * 사용자가 사진 배치 뒤에 글을 직접 수정해 소제목 위치가 바뀌어도, 사진이 소제목 바로
     * 아래에 남지 않도록 가까운 본문 문단 뒤로 옮긴다. 미리보기와 두 복사 경로 모두 보정한다.
     */
    private fun enforceNoPhotoBelowHeading(blocks: List<String>) {
        if (blocks.isEmpty() || imagePositions.isEmpty()) return
        val safePositions = linkedMapOf<Int, MutableList<Uri>>()
        imagePositions.forEach { (position, images) ->
            val safe = safeBodyPosition(position, blocks)
            safePositions.getOrPut(safe) { mutableListOf() }.addAll(images)
        }
        imagePositions.clear()
        imagePositions.putAll(safePositions)
    }

    private fun safeBodyPosition(position: Int, blocks: List<String>): Int {
        if (blocks.isEmpty()) return 0
        val start = position.coerceIn(0, blocks.lastIndex)
        if (start > 0 && !isLikelyHeading(blocks[start])) return start
        val nextBody = ((start + 1)..blocks.lastIndex).firstOrNull { !isLikelyHeading(blocks[it]) }
        if (nextBody != null) return nextBody
        return (start - 1 downTo 1).firstOrNull { !isLikelyHeading(blocks[it]) } ?: 0
    }

    private fun renderPreview() {
        previewContainer.removeAllViews()
        val blocks = paragraphs()
        if (blocks.isEmpty()) {
            previewContainer.addView(label("생성된 글을 가져오거나 문단을 입력해주세요."))
            return
        }
        enforceNoPhotoBelowHeading(blocks)
        blocks.forEachIndexed { index, paragraph ->
            previewContainer.addView(label(paragraph).apply { setPadding(dp(10), dp(10), dp(10), dp(8)) })
            imagePositions[index].orEmpty().forEach { uri ->
                // setImageURI는 원본 해상도 그대로 디코딩해서, "위치로 가져오기"처럼 사진이 한 번에
                // 많이(수십 장) 들어오면 메모리 부족으로 앱이 죽었다가 다시 켜지는 것처럼 보이는
                // 문제가 실사용 중 있었다. 미리보기 크기에 맞게 축소해서 디코딩한다.
                val bitmap = try {
                    Watermark.apply(ThumbnailCollage.decode(this, uri, dp(340)), watermarkText, watermarkTypeface)
                } catch (e: Exception) {
                    null
                }
                if (bitmap == null) {
                    previewContainer.addView(label("사진을 불러오지 못했습니다."))
                    return@forEach
                }
                // 실제 글에서는 사진이 잘리지 않고 가로 폭에 맞춰 원본 비율 그대로 들어간다 -
                // 미리보기도 고정 높이로 잘라서 보여주지 않고 같은 방식(꽉 찬 가로 폭 + 원본 비율)으로
                // 보여줘야 실제로 어떻게 배치될지 미리 정확히 확인할 수 있다.
                previewContainer.addView(ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    contentDescription = "${index + 1}번째 문단 뒤 사진"
                    setOnClickListener { showFullscreenPreview(uri) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(8), dp(4), dp(8), dp(8))
                })
            }
        }
    }

    // 미리보기 사진(콜라주 포함)을 눌렀을 때 화면 전체 크기로 확대해서 보여준다 - 콜라주 안에
    // 작은 사진들이 실제로 잘 나왔는지 미리보기의 작은 크기로는 확인하기 어렵기 때문.
    private fun showFullscreenPreview(uri: Uri) {
        val bitmap = try {
            Watermark.apply(ThumbnailCollage.decode(this, uri, 2000), watermarkText, watermarkTypeface)
        } catch (e: Exception) {
            Toast.makeText(this, "사진을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(imageView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        imageView.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun copyRichContent() {
        val blocks = paragraphs()
        if (blocks.isEmpty() || selectedImages.isEmpty() || imagePositions.isEmpty()) {
            Toast.makeText(this, "글과 사진을 준비한 뒤 랜덤 배치해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        enforceNoPhotoBelowHeading(blocks)
        try {
            status.text = "사진을 붙여넣기용 데이터로 변환하는 중입니다."
            val embedded = selectedImages.associateWith { uri -> imageAsDataUri(uri) }
            // 일반 텍스트에는 "[사진 N]" 같은 자리표시자를 넣지 않는다 - 네이버가 HTML 대신 이
            // 일반 텍스트를 그대로 받아들이는 경우가 있어(실사용 중 확인됨), 그때 이 표시가 사진
            // 없이 글자로만 그대로 남아 보이는 문제가 있었다.
            val plain = blocks.joinToString("\n\n") { it }.trim()
            // 소제목 글자 크기·굵기 서식을 인라인 스타일, <font size>, <h3> 세 가지 방식으로 시도했지만
            // 전부 네이버가 붙여넣을 때 지워버렸다(실사용 중 확인) - 사진처럼 <img> 구조는 살아남지만
            // 글자 서식은 어떤 태그로도 안 남는 것으로 보고 포기했다. 소제목도 본문과 같은 순수
            // 텍스트(<p>)로만 넣는다.
            val html = buildString {
                append("<div>")
                blocks.forEachIndexed { index, paragraph ->
                    val escaped = Html.escapeHtml(paragraph).replace("\n", "<br>")
                    append("<p>$escaped</p>")
                    imagePositions[index].orEmpty().forEach { uri ->
                        append("<p><img src=\"").append(embedded.getValue(uri)).append("\" alt=\"사진\"></p>")
                    }
                }
                append("</div>")
            }
            // content:// URI는 네이버 편집기에 들어간 뒤 깨진 이미지로 표시됐다. 사진을 작게
            // 압축한 data URI로 HTML 자체에 포함해 외부 파일 접근 없이 한 번에 붙여넣는 3차 시험.
            val clip = ClipData(
                "문단별 글과 랜덤 사진",
                arrayOf(ClipDescription.MIMETYPE_TEXT_HTML, ClipDescription.MIMETYPE_TEXT_PLAIN),
                ClipData.Item(plain, html)
            )
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            status.text = "전체 복사 완료 · 네이버 블로그의 빈 글에 한 번 붙여넣어 사진 위치를 확인하세요."
            Toast.makeText(this, "문단과 사진을 한 번에 복사했습니다.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            status.text = "복사 실패: ${error.message}"
        }
    }

    private fun startNaverAutoPaste() {
        val blocks = paragraphs()
        if (blocks.size < 2 || imagePositions.isEmpty()) {
            Toast.makeText(this, "첫 줄 제목과 본문 문단, 사진을 먼저 준비해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        enforceNoPhotoBelowHeading(blocks)
        val title = blocks.first().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val bodyBlocks = blocks.drop(1)
        if (title.isBlank() || bodyBlocks.isEmpty()) {
            Toast.makeText(this, "글과 사진을 먼저 준비하고 배치해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isPasteServiceEnabled()) {
            Toast.makeText(this, "접근성 설정에서 네이버 문단·사진 반자동 배치를 켜주세요.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        status.text = "사진을 네이버 블로그에 전달할 수 있도록 준비하고 있습니다."
        Thread {
            try {
                val sharedImages = selectedImages.mapIndexed { index, uri ->
                    uri to copyToSharedCache(uri, index)
                }.toMap()
                val steps = buildList {
                    add(AutoPasteState.Step.Title(title))
                    bodyBlocks.forEachIndexed { bodyIndex, paragraph ->
                        if (isLikelyHeading(paragraph)) {
                            // 소제목 뒤에 줄바꿈이 없으면 바로 다음 본문이 같은 줄에 그대로 붙어버린다
                            // (실사용 중 확인됨) - 일반 문단과 똑같이 끝에 빈 줄을 넣어 구분한다.
                            add(AutoPasteState.Step.Heading("$paragraph\n\n"))
                        } else {
                            add(AutoPasteState.Step.Text("$paragraph\n\n"))
                        }
                        val originalIndex = bodyIndex + 1
                        val originals = buildList {
                            // 기존 랜덤 배치에서 제목 뒤로 잡힌 사진은 첫 본문 뒤로 이동한다.
                            if (bodyIndex == 0) addAll(imagePositions[0].orEmpty())
                            addAll(imagePositions[originalIndex].orEmpty())
                        }
                        originals.forEach { original ->
                            add(AutoPasteState.Step.Image(sharedImages.getValue(original)))
                            add(AutoPasteState.Step.MoveBelowImage)
                        }
                    }
                }
                // 글의 맨 마지막 단계가 MoveBelowImage(사진 아래 탭 대기)로 끝나면, 그 뒤에 이어
                // 쓸 내용이 실제로는 없는데도 계속 사용자의 탭을 기다리며 멈춰 있게 되고, 그 때문에
                // "끝났습니다" 안내도 영영 뜨지 않는 문제가 있었다(실사용 중 확인됨). 마지막 사진
                // 뒤에는 더 쓸 내용이 없으니 그 대기 단계는 아예 빼고 끝낸다.
                val trimmedSteps = if (steps.lastOrNull() is AutoPasteState.Step.MoveBelowImage) {
                    steps.dropLast(1)
                } else steps
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

    private fun isLikelyHeading(text: String): Boolean = DraftFormatter.isLikelyHeading(text)

    private fun openNaverBlogApp(clearPasteOnFailure: Boolean) {
        // Android 11 이상에서는 manifest의 queries 선언이 없으면 설치된 앱도 찾지 못한다.
        // 블로그 앱만 직접 열고 브라우저로는 이동하지 않는다.
        val blogIntent = packageManager.getLaunchIntentForPackage("com.nhn.android.blog")
        if (blogIntent != null) {
            startActivity(blogIntent)
        } else {
            if (clearPasteOnFailure) AutoPasteState.clear()
            status.text = "네이버 블로그 앱을 찾지 못했습니다. 앱 설치 상태를 확인해주세요."
            Toast.makeText(this, "네이버 블로그 앱을 찾지 못했습니다. 크롬은 열지 않습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isPasteServiceEnabled(): Boolean {
        val expected = ComponentName(this, NaverPasteAccessibilityService::class.java).flattenToString()
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun copyToSharedCache(source: Uri, index: Int): Uri {
        val target = File(File(cacheDir, "richcopy").apply { mkdirs() }, "rich_copy_${System.currentTimeMillis()}_$index.jpg")
        // 워터마크가 없으면 원본 그대로 복사(빠르고 화질 손실 없음). 워터마크가 있으면 그려 넣어야
        // 해서 디코드 후 다시 압축해 저장한다.
        if (watermarkText.isBlank()) {
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

    private fun imageAsDataUri(source: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 900 || bounds.outHeight / sample > 900) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = contentResolver.openInputStream(source).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("사진을 변환할 수 없습니다.")
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

    private fun label(value: String) = TextView(this).apply { text = value; textSize = 13f; setTextColor(getColor(R.color.text_primary)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // Android 15(targetSdk 35)부터는 edge-to-edge가 기본 적용되어, 맨 위 제목이 상태 표시줄
    // (시계) 밑에 가려 보일 수 있다. 상태 표시줄 높이만큼 위쪽 여백을 더해준다.
    private fun applyTopInsetPadding() {
        val root = findViewById<View>(R.id.rootScroll)
        val panel = findViewById<View>(R.id.generationPanel)
        val density = resources.displayMetrics.density
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // Keep title through copy/reset in the first viewport; photo tools follow below.
            val available = root.height - root.paddingTop - root.paddingBottom - (24 * density).toInt()
            val height = available.coerceAtLeast((420 * density).toInt())
            if (panel.layoutParams.height != height) {
                panel.layoutParams = panel.layoutParams.apply { this.height = height }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    companion object {
        const val EXTRA_DRAFT = "draft"
        const val EXTRA_DONG = "dong"
        const val EXTRA_WATERMARK = "watermark"
        const val EXTRA_WATERMARK_PHONE = "watermark_phone"
        private const val REQUEST_IMAGES = 91
        private const val DECOY_TEXT = "안녕하세요\n사진과 이 글을 지워주세요"
    }
}
