package com.keyword.research

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

// "키워드 검색"/"API 키 설정"/"연관 키워드 추출"/"블로그 검색 검증"은 콜라주 만들기와 같은 방식으로
// 각각 별도 화면(버튼을 눌러 들어가는 Activity)으로 분리했다. 이 화면에는 블로그 선택/글쓰기
// 스타일 선택과 최종 요청문·AI 초안 생성만 남는다. 마지막 검색 결과는 SearchState에 보관해서
// KeywordSearchActivity를 다녀와도 요청문 생성에 그대로 쓸 수 있다.
class MainActivity : AppCompatActivity() {

    private lateinit var blogSpinner: Spinner
    private lateinit var styleSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 라이선스 키 게이트는 서버/GitHub 설정이 끝날 때까지 꺼둔다(다시 켜 달라는 요청이 있을
        // 때 아래 주석을 풀면 된다) - 켜져 있으면 지금은 항상 막혀서 테스트가 안 된다.
        // if (!LicenseGate.isActivated(this)) {
        //     startActivity(Intent(this, LicenseActivity::class.java))
        //     finish()
        //     return
        // }
        setContentView(R.layout.activity_main)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        bindViews()
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        findViewById<TextView>(R.id.appVersionBadge).text = "v$versionName"
        if (BuildConfig.BUILD_TYPE == "headingTest") {
            findViewById<TextView>(R.id.appVersionBadge).apply {
                text = "TEST-01 · 샘플 시험"
                setOnClickListener {
                    startActivity(Intent().setClassName(packageName, "com.keyword.research.FullDraftTest"))
                }
            }
        }
        applyBottomInsetPadding()
        setupBlogSpinner()
        setupStyleSpinner()

        findViewById<View>(R.id.btnEditBlogPhones).setOnClickListener { showEditBlogPhonesDialog() }
        findViewById<View>(R.id.btnUsageGuide).setOnClickListener { showUsageGuideDialog() }
        // 아래는 아이콘 그리드 셀(LinearLayout)이라 Button이 아니라 View로 찾는다.
        findViewById<View>(R.id.btnOpenApiSettings).setOnClickListener { startActivity(Intent(this, ApiSettingsActivity::class.java)) }
        findViewById<View>(R.id.btnOpenKeywordAnalysis).setOnClickListener { startActivity(Intent(this, KeywordAnalysisActivity::class.java)) }
        findViewById<View>(R.id.btnOpenKeywordSearch).setOnClickListener { startActivity(Intent(this, KeywordSearchActivity::class.java)) }
        findViewById<View>(R.id.btnOpenRelatedKeyword).setOnClickListener { startActivity(Intent(this, RelatedKeywordActivity::class.java)) }
        findViewById<View>(R.id.btnOpenBlogTitle).setOnClickListener { startActivity(Intent(this, BlogTitleActivity::class.java)) }
        findViewById<View>(R.id.btnOpenLowCompetitionDong).setOnClickListener { startActivity(Intent(this, LowCompetitionDongActivity::class.java)) }
        findViewById<View>(R.id.btnOpenKeywordCaseInfo).setOnClickListener { startActivity(Intent(this, KeywordCaseInfoActivity::class.java)) }
        findViewById<View>(R.id.btnOpenPhotoWriter).setOnClickListener { startActivity(Intent(this, PhotoWriterActivity::class.java)) }
        findViewById<View>(R.id.btnOpenAdWidget).setOnClickListener { startActivity(Intent(this, AdWidgetActivity::class.java)) }
        findViewById<View>(R.id.btnOpenRichCopyTest).setOnClickListener {
            // "콘텐츠 생성"과 "키워드와 현장 정보"가 이제 각각 별도 화면에 있으므로(사용자 요청 -
            // 카드 대시보드 방식), 사례 정보는 KeywordCaseInfoActivity가 나갈 때마다 SearchState에
            // 이미 스냅샷돼 있다 - 여기서는 그대로 읽어 쓰기만 한다.
            startActivity(
                Intent(this, RichCopyTestActivity::class.java)
                    .putExtra(RichCopyTestActivity.EXTRA_DONG, SearchState.dong)
                    .putExtra(RichCopyTestActivity.EXTRA_WATERMARK, currentBlogEntry().blogId)
                    .putExtra(RichCopyTestActivity.EXTRA_WATERMARK_PHONE, currentBlogEntry().phone)
            )
        }
    }

    private fun bindViews() {
        blogSpinner = findViewById(R.id.blogSpinner)
        styleSpinner = findViewById(R.id.styleSpinner)
    }

    // Android 15(targetSdk 35)부터는 edge-to-edge가 기본 적용되어, 하단 버튼이 제스처
    // 내비게이션 바에 가려 보일 수 있다. 시스템 바 높이만큼 아래쪽 여백을 더해준다.
    // 화면 전체를 스크롤 없이 한 화면에 꽉 채우는 레이아웃으로 바꾸면서(사용자 지시 - "화면
    // 대비로 만들어"), 상태표시줄/제스처바만큼만 여백을 더한다 - 루트에 이 패딩이 더해지는
    // 만큼 weight 영역(body)이 자동으로 줄어들어 항상 화면 안에 맞는다.
    private fun applyBottomInsetPadding() {
        val root = findViewById<View>(R.id.rootScroll)
        val basePadding = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = basePadding + bars.bottom)
            insets
        }
    }

    // 앱을 열 때마다 조용히 서버에 다시 물어본다 - 관리자가 키를 회수했거나 다른 기기로 넘어간
    // 것으로 확인되면 그 자리에서 잠근다. 오프라인 등 네트워크 오류는 막지 않는다(LicenseGate 참고).
    private fun verifyLicenseInBackground() {
        Thread {
            val stillValid = LicenseGate.verifyOnline(this)
            if (!stillValid) {
                LicenseGate.clearActivation(this)
                runOnUiThread {
                    Toast.makeText(this, "라이선스가 취소되어 다시 인증이 필요합니다.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, LicenseActivity::class.java))
                    finish()
                }
            }
        }.start()
    }

    private fun blogLabel(entry: BlogProfileEntry): String =
        "${entry.id}. ${entry.phone}" +
            (if (entry.blogId.isNotBlank()) " (${entry.blogId})" else "") +
            (if (entry.custom) " (추가)" else "")

    private fun currentBlogEntry(): BlogProfileEntry {
        val entries = BlogProfileStore.allEntries(this)
        return entries.firstOrNull { it.id == SearchState.selectedBlogId } ?: entries.first()
    }

    private fun setupBlogSpinner() {
        refreshBlogSpinnerAdapter()
        blogSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val entries = BlogProfileStore.allEntries(this@MainActivity)
                val entry = entries.getOrNull(position) ?: return
                SearchState.selectedBlogId = entry.id
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("phone", entry.phone))
                Toast.makeText(this@MainActivity, "${entry.phone} 복사됨", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // 블로그 관리 다이얼로그에서 추가/삭제/전화번호 수정을 하면 이 함수를 다시 불러서 스피너
    // 목록을 최신 상태로 맞춘다. 선택했던 블로그가 삭제됐으면 첫 번째 블로그로 되돌아간다.
    private fun refreshBlogSpinnerAdapter() {
        val entries = BlogProfileStore.allEntries(this)
        if (entries.none { it.id == SearchState.selectedBlogId }) SearchState.selectedBlogId = entries.first().id
        val labels = entries.map { blogLabel(it) }
        blogSpinner.adapter = ArrayAdapter(this, R.layout.home_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        blogSpinner.setSelection(entries.indexOfFirst { it.id == SearchState.selectedBlogId }, false)
    }

    private fun setupStyleSpinner() {
        val labels = WritingStyle.entries.map { "${it.number}. ${it.label}" }
        styleSpinner.adapter = ArrayAdapter(this, R.layout.home_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        styleSpinner.setSelection(WritingStyle.entries.indexOf(SearchState.selectedStyle))
        styleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                SearchState.selectedStyle = WritingStyle.entries[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // 블로그 관리: 기존 블로그(고정 6개 + 직접 추가한 블로그)의 전화번호를 한 화면에서 편집하고,
    // 직접 추가한 블로그는 삭제할 수도 있다. 맨 아래에는 새 블로그를 추가하는 입력칸이 있다 -
    // 추가/삭제를 누르면 바로 저장하고 같은 다이얼로그를 새로고침해서 최신 목록을 다시 보여준다.
    private fun showEditBlogPhonesDialog() {
        var dialog: AlertDialog? = null
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val scroll = android.widget.ScrollView(this)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        scroll.addView(form)

        val phoneFields = BlogProfileStore.allEntries(this).map { entry ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = "${entry.id}번 전화번호" + if (entry.custom) " (직접 추가)" else ""
                textSize = 12f
            })
            val field = EditText(this).apply {
                setText(entry.phone)
                inputType = InputType.TYPE_CLASS_PHONE
            }
            col.addView(field)
            // 워터마크(블로그 아이디)는 고정 6개 블로그와 직접 추가한 블로그 모두 수정할 수 있다.
            // 칸을 비우고 저장하면 그 블로그는 워터마크를 새기지 않는다(삭제와 같은 효과).
            col.addView(TextView(this).apply {
                text = "워터마크(블로그 아이디) - 사진 가장자리에 작게 새겨집니다. 비우면 그 블로그는 워터마크를 안 새깁니다."
                textSize = 11f
                setPadding(0, pad / 4, 0, 0)
            })
            val watermarkField = EditText(this).apply {
                hint = "예: myblogid"
                setText(entry.blogId)
            }.also { col.addView(it) }
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (entry.custom) {
                row.addView(Button(this).apply {
                    text = "삭제"; isAllCaps = false; textSize = 11f
                    setOnClickListener {
                        BlogProfileStore.removeCustom(this@MainActivity, entry.id)
                        refreshBlogSpinnerAdapter()
                        Toast.makeText(this@MainActivity, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                        dialog?.dismiss()
                        showEditBlogPhonesDialog()
                    }
                })
            }
            form.addView(row)
            Triple(entry, field, watermarkField)
        }

        form.addView(TextView(this).apply {
            text = "새 블로그 추가"; textSize = 13f; setPadding(0, pad / 2, 0, pad / 4)
        })
        val addPhone = EditText(this).apply { hint = "전화번호"; inputType = InputType.TYPE_CLASS_PHONE }
        val addWatermark = EditText(this).apply { hint = "워터마크(블로그 아이디) - 사진 가장자리에 작게 새겨집니다. 비워두면 전화번호를 대신 씁니다." }
        val addTitle = EditText(this).apply { hint = "제목 표기 방식 (예: 평문으로만 써줘)" }
        val addVoice = EditText(this).apply { hint = "문체 (예: 담백하고 군더더기 없는 문장, 존댓말)" }
        val addOpening = EditText(this).apply { hint = "첫 문단 시작 방식 (예: 상황 설명으로 시작해줘)" }
        listOf(addPhone, addWatermark, addTitle, addVoice, addOpening).forEach { form.addView(it) }
        form.addView(Button(this).apply {
            text = "블로그 추가"; isAllCaps = false; textSize = 12f
            setOnClickListener {
                val phone = addPhone.text.toString().trim()
                if (phone.isBlank()) {
                    Toast.makeText(this@MainActivity, "전화번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val entry = BlogProfileStore.addCustom(
                    this@MainActivity,
                    phone,
                    addTitle.text.toString().trim().ifBlank { "제목은 기호나 이모티콘 없이 그냥 첫 줄에 평문으로만 써줘." },
                    addVoice.text.toString().trim().ifBlank { "담백하고 군더더기 없는 문장, 존댓말." },
                    addOpening.text.toString().trim().ifBlank { "첫 문단은 상황을 자연스럽게 설명하며 시작해줘." },
                    addWatermark.text.toString().trim()
                )
                SearchState.selectedBlogId = entry.id
                refreshBlogSpinnerAdapter()
                Toast.makeText(this@MainActivity, "블로그가 추가되었습니다.", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
                showEditBlogPhonesDialog()
            }
        })

        dialog = AlertDialog.Builder(this)
            .setTitle("블로그 관리 (편집 / 추가)")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                phoneFields.forEach { (entry, field, watermarkField) ->
                    // 전화번호도 워터마크와 마찬가지로 비워서 저장할 수 있다(그 번호 자체를 지우는
                    // 효과) - 이전에는 빈 값이면 저장을 아예 건너뛰어서 지울 방법이 없었다.
                    val phone = field.text.toString().trim()
                    if (phone != entry.phone) {
                        if (entry.custom) BlogProfileStore.updateCustomPhone(this, entry.id, phone)
                        else BlogProfileStore.setPhone(this, BlogProfile.byNumber(entry.id), phone)
                    }
                    val watermark = watermarkField.text.toString().trim()
                    if (watermark != entry.blogId) {
                        if (entry.custom) BlogProfileStore.updateCustomBlogId(this, entry.id, watermark)
                        else BlogProfileStore.setBlogId(this, BlogProfile.byNumber(entry.id), watermark)
                    }
                }
                refreshBlogSpinnerAdapter()
                Toast.makeText(this, "전화번호가 저장되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .create()
        dialog.show()
    }

    private fun showUsageGuideDialog() {
        // 화면 구조가 카드 대시보드 방식으로 바뀌면서(사용자 요청) 키워드/현장 정보와 콘텐츠
        // 생성이 메인 화면에서 각각 별도 화면으로 옮겨갔다 - 설명서도 그 흐름에 맞춰 갱신했다.
        val guide = """
            키워드 리서치 사용 설명서 (v${BuildConfig.VERSION_NAME} 기준)

            최근 업데이트 요약

            콘텐츠 생성(출장 사례형/정보형, AI 초안 생성)이 메인 화면에서 "블로그 반자동 복사"
            화면 안으로 옮겨졌고, 키워드와 현장 정보 입력은 별도 화면으로 독립했습니다. 화면은
            메인 화면은 스크롤 없이 메뉴와 선택 칸을 한 화면에 표시합니다.
            아이콘과 카드 위의 반투명 숫자 표시는 제거했습니다.

            00 화면 구성

            메인 화면은 위에서부터 빠른 도구(키워드 분석, 경쟁 글 검색, 연관 키워드, 블로그
            검색 검증, 지역 찾기, API 설정), 블로그 선택과 글쓰기 스타일, 키워드와 현장 정보,
            블로그 반자동 복사 순으로 배치돼 있습니다.
            1단계 키워드와 현장 정보 - 지역/증상/현장 사실 입력, 키워드 분석
            2단계 블로그 - 사용할 블로그(전화번호) 선택
            3단계 글쓰기 스타일 - 12가지 글쓰기 스타일 중 선택
            4단계 블로그 반자동 복사 - 콘텐츠 생성부터 네이버 붙여넣기까지
            "키워드와 현장 정보"와 "블로그 반자동 복사(그 안의 콘텐츠 생성 포함)"는 각각 눌러서
            들어가는 별도 화면이고, 2·3단계(블로그·글쓰기 스타일)는 메인 화면에서 바로 고릅니다.

            01 키워드 찾기

            1. 메인 화면의 1단계 "키워드와 현장 정보" 카드를 누르면 나오는 화면에서, 검색할 증상 칸에
            고객이 실제로 검색할 짧은 표현만 입력합니다.
            좋은 예시는 화장실 콘센트 전기 안됨, 주방 차단기 내려감, 거실 조명 깜빡임입니다.
            원인이나 작업 결과까지 길게 입력하면 검색 결과가 흐려질 수 있습니다.

            2. 키워드 분석 시작 버튼을 누르면 입력한 증상으로 연관 키워드와 검색량을 분석합니다.

            3. 단계별 키워드 가져오기에서 목적에 맞는 키워드를 선택합니다.
            1단계는 검색량이 비교적 크고 경쟁이 높은 키워드입니다.
            2단계는 검색량과 경쟁도가 중간인 키워드입니다.
            3단계는 검색량은 작지만 구체적인 의도가 담긴 키워드입니다.

            4. 키워드 검색 화면에서는 선택한 키워드로 네이버 최근 상위 블로그 글을 가져옵니다.
            이 결과는 제목과 구조를 베끼는 용도가 아니라 다른 글과 겹치지 않게 비교하는 참고 자료로 사용됩니다.

            5. 입력한 내용을 지우고 싶으면 "키워드와 현장 정보" 화면 안의 초기화 버튼을 누르세요.
            증상, 원인, 작업 내용, 작업 결과와 분석 결과가 모두 비워집니다(메인 화면에는 이
            초기화 버튼이 없습니다 - 반드시 해당 화면 안에서 눌러야 합니다).

            검색 결과가 맞지 않을 때

            콘센트처럼 한 단어만 입력하면 제품 판매 글과 인테리어 글이 섞일 수 있습니다.
            장소와 증상을 함께 입력해 주방 콘센트 전기 안됨처럼 구체적으로 검색하세요.
            작업명보다는 고객이 겪은 증상을 검색어로 사용하는 것이 좋습니다.
            새 증상으로 다시 검색하면 이전 단계별 키워드 결과는 초기화됩니다.

            작업 도구 버튼

            키워드 분석은 연관 키워드와 검색량을 확인합니다.
            키워드 검색은 네이버 최근 블로그 글을 수집합니다.
            연관 키워드는 선택한 주제와 함께 사용할 표현을 찾습니다.
            블로그 검색 검증은 블로그 ID의 글이 실제 네이버 검색에서 몇 위에 노출되는지, 누락됐다면
            왜 누락됐는지 확인합니다. 직접 입력한 키워드로도 같은 방식으로 확인할 수 있습니다.
            경쟁 낮은 동은 지역별 검색 결과를 비교해 글 소재로 사용할 동을 고릅니다.
            API 설정은 네이버 검색과 Gemini 글 생성에 필요한 키를 저장합니다.

            블로그 반자동 복사(화면 아래쪽 진한 파란색 카드)는 글을 만들고, 문단별로 나누고 사진을
            배치한 뒤 네이버 블로그에 붙여넣는 화면입니다. 이 화면 안에 다음 기능이 모두 들어 있습니다.
            콘텐츠 생성 - 출장 사례형/정보형 선택, Gemini로 초안 생성, 글쓰기 기준, 기본 명령문 편집.
            대표사진 만들기 - 블로그 대표 이미지를 준비합니다.
            콜라주 만들기 - 선택한 사진을 묶어 한 장의 이미지로 만듭니다.
            위치로 사진 폴더 만들기 - 카메라 폴더 사진의 위치(GPS)를 읽어 구/동/번지수 폴더로
            자동 정리합니다. 정리된 폴더에서 사진을 골라 콜라주나 사진 배치에 사용하세요.
            미리보기에 나온 사진(콜라주 포함)을 누르면 화면 전체 크기로 확대해서 확인할 수 있습니다.

            블로그 선택과 사진 워터마크

            블로그 선택 목록에는 전화번호 옆에 그 블로그의 아이디(워터마크 문구)가 함께 표시됩니다.
            블로그 프로필 관리(블로그 상자의 "관리")에서 전화번호와 워터마크를 각각 추가, 수정,
            삭제(빈 칸으로 저장)할 수 있습니다. 워터마크를 넣어두면 이후 사진 선택 자동 배치나 콜라주
            결과 불러오기로 네이버에 전달되는 모든 사진 가장자리에 그 블로그 아이디가 전화번호별로
            고정된 글꼴로 작게 새겨져 사진 도용을 막는 데 도움이 됩니다(칸을 비워두면 그 블로그는
            워터마크를 새기지 않습니다).

            고정 블로그 6개의 번호순 워터마크 기준(전화번호를 직접 바꿔도 이 아이디는 유지됩니다):
            1번 050714178239 → elecinout
            2번 050713292986 → pwrelec
            3번 050714144877 → powerelecgo
            4번 01056121456 → elecgood
            5번 05065333698 → elechoice
            6번 07047695730 → elecvee

            02 글쓰기

            1. 메인 화면의 2단계 "블로그"와 3단계 "글쓰기 스타일" 상자에서 사용할 블로그와
            스타일을 고릅니다. 선택한 블로그의 전화번호, 문체, 사진 워터마크가 함께 적용됩니다.
            후기형, 전문가형, Q&A형, 비교형 등 선택한 스타일이 글의 표현과 구성에 반영됩니다.

            2. "키워드와 현장 정보" 화면에서 출장 사례형 글은 지역과 동을 입력합니다. 무작위 채우기는 선택한 서울 또는 경기 지역 안에서 구와 동을 자동으로 넣습니다.

            3. 증상은 검색어로 사용됩니다. 확인 원인, 작업 내용, 작업 결과는 검색어에 포함되지 않고 글을 작성하는 사실 자료로만 사용됩니다.
            모르는 항목은 비워두세요. 앱은 없는 작업이나 결과를 만들지 않고 가능한 여러 원인과 조치 유형을 조건부로 설명합니다.

            4. 작업 종류 목록은 실제로 수행한 작업이 있을 때만 선택합니다. 선택한 항목은 작업 내용 칸에 들어갑니다.

            5. 블로그 반자동 복사 화면으로 이동하면 그 안의 콘텐츠 생성에서, 출장 사례 요청문
            만들기는 제공한 지역과 현장 사실을 바탕으로 사례형 명령문을 만듭니다.
            정보성 글 만들기는 지역, 업체 홍보, 전화번호를 제외하고 원인과 안전한 확인 방법을 설명하는 명령문을 만듭니다.

            6. 기본 명령문 편집에서는 매번 자동으로 들어가는 글쓰기 기준을 직접 수정하고 저장할 수 있습니다. 잘못 수정했다면 기본값 복원을 누르세요.

            7. AI 초안 생성은 저장된 Gemini API 키를 사용해 앱 안에서 글을 작성합니다. 생성 전에는 선택한 콘텐츠 유형과 입력 내용을 다시 확인하세요. 외부 AI에서는 복사 버튼용 코드블록으로 나오지만, 글 안에는 #, *, - 같은 기호를 넣지 않습니다. 앱은 바깥 백틱을 자동으로 제거합니다.

            사례형과 정보형 선택 기준

            실제 방문 지역, 확인한 원인, 수행한 작업, 확인 결과가 있다면 출장 사례형을 사용합니다.
            실제 작업 정보가 없고 독자에게 원인과 대처법을 설명하려면 정보형을 사용합니다.
            지역만 입력하고 실제 작업이 없는데 사례형으로 만들면 내용이 부족해집니다. 이때는 정보형을 선택하거나 확인된 내용만 추가하세요.

            빈 입력칸 처리

            확인 원인이 비어 있으면 AI는 한 가지 원인으로 단정하지 않고 가능한 원인을 나누어 설명합니다.
            작업 내용이 비어 있으면 실제로 작업했다고 쓰지 않고 점검 또는 조치 유형을 안내합니다.
            작업 결과가 비어 있으면 정상화되었다거나 고객이 만족했다는 표현을 만들지 않습니다.
            비용, 시간, 측정값도 직접 입력하지 않았다면 글에 임의로 넣지 않습니다.

            기본 명령문 편집 방법

            기본 명령문 편집을 누르면 모든 글에 공통으로 적용되는 규칙 원본이 열립니다.
            원하는 문장을 추가하거나 기존 규칙을 고친 뒤 저장을 누르세요.
            저장한 규칙은 다음 요청문부터 적용되며 앱을 종료해도 유지됩니다.
            출력 형식, 정보형과 사례형 구분 같은 프로그램 필수 규칙은 별도로 유지됩니다.
            규칙이 꼬이거나 글 품질이 나빠지면 기본값 복원을 누른 뒤 다시 생성하세요.

            03 복사와 네이버 작성

            1. 요청문만 Gemini나 ChatGPT에서 사용할 때는 내용 복사를 누릅니다. 외부 AI 답변은 한 번에 복사할 수 있는 코드블록으로 요청됩니다.

            2. AI 초안 생성이 끝났다면 제목, 본문, 안전 안내, 제공하지 않은 사실이 들어갔는지 확인합니다.

            3. 같은 블로그 반자동 복사 화면 안의 글 입력칸에 초안이 바로 들어와 있습니다. Gemini나
            ChatGPT에서 따로 다듬은 글은 클립보드에서 글 가져오기로 불러옵니다. 복사 박스의
            바깥 백틱과 혹시 섞인 본문 기호는 자동으로 걷어내고 가져옵니다.

            4. 사진 여러 장 선택 후 자동 배치를 누르면 촬영 날짜·시간 순서대로(오래된 사진부터) 문단 사이에 배치됩니다.
            배치가 마음에 안 들면 사진 다시 배치(촬영 시간 순)를 다시 누릅니다.

            5. 네이버에 붙여넣는 방법은 두 가지입니다.

            글과 사진 전체 한 번에 복사(추천) - 문단과 사진이 이미 순서대로 합쳐진 내용을 클립보드에
            담습니다. 네이버 블로그 편집기의 빈 글을 한 번 누른 뒤 붙여넣기 한 번이면 제목, 본문,
            사진이 한 번에 그대로 들어가고, 사진 아래를 따로 눌러줄 필요가 없습니다. 소제목은 굵게나
            큰 글씨로 구분되지 않고 줄바꿈으로만 구분됩니다(네이버가 붙여넣을 때 글자 서식을 지워서
            자동화가 불가능했습니다) - 배치가 끝나면 소제목 목록을 안내해주니 직접 선택해서 굵게(B) 처리하세요.

            네이버에 문단·사진 반자동 배치 - 제목, 본문, 사진을 한 단계씩 순서대로 자동 입력합니다.
            처음 한 번은 휴대폰 설정의 접근성에서 네이버 문단·사진 반자동 배치 서비스를 켜야 합니다.
            사진을 넣은 다음 화면에 "사진 아래 빈 줄을 한 번 눌러주세요" 안내가 뜨면 그 자리를 직접
            한 번 눌러줘야 다음 문단이 이어서 입력됩니다(네이버 편집기가 사진 뒤 커서를 자동으로
            옮겨주지 않아서, 이 한 번의 터치만큼은 자동화할 수 없었습니다).

            6. 자동 입력이 끝나면 전화번호, 지역, 작업 결과와 해시태그를 최종 확인한 뒤 먼저
            임시저장합니다. 공개 발행은 전체 내용을 검수한 다음 직접 진행합니다.

            반자동 배치가 멈추거나 안 될 때

            접근성 설정에서 서비스를 새로 켜거나 껐다 켰다면 네이버 블로그 앱을 완전히 종료했다가
            다시 켜야 새 설정이 적용됩니다.
            화면에 안내 문구(토스트)가 뜨면 그 안내대로 한 번 눌러주면 자동 입력이 이어집니다.
            계속 안 되면 글과 사진 전체 한 번에 복사로 바꿔서 네이버 편집기에 직접 붙여넣어도 됩니다.

            자주 발생하는 문제

            네이버 검색 결과가 없으면 API 설정의 네이버 Client ID와 Client Secret을 확인합니다.
            키워드 분석이 실패하면 검색광고 API 키와 고객 ID를 확인합니다.
            Gemini 초안 생성이 실패하면 실패 대화상자에 뜨는 정확한 오류(할당량인지 서버 과부하인지)를 확인합니다.
            경쟁 글이 상품 소개 위주라면 증상을 더 구체적으로 바꾸고 다시 검색합니다.
            글에 방문 후기 표현이 섞이면 정보성 글 만들기를 다시 누른 뒤 초안을 생성합니다.
            제공하지 않은 사실이 나오면 해당 문장을 삭제하고 기본 명령문에 금지 규칙이 남아 있는지 확인합니다.

            권장 입력 예시

            증상: 주방 콘센트 전기 안됨
            확인 원인: 모르면 비워두기
            작업 내용: 누전 차단기 점검
            작업 결과: 실제로 확인한 결과가 있을 때만 입력
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("키워드 찾기 · 글쓰기 · 복사 (v${BuildConfig.VERSION_NAME})")
            .setMessage(guide)
            .setPositiveButton("확인", null)
            .show()
    }

}
