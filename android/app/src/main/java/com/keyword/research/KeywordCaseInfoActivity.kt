package com.keyword.research

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

// 메인 화면에 있던 "키워드와 현장 정보"가 이 화면으로 옮겨왔다(사용자 요청 - 카드 대시보드
// 방식으로 계속 전환하면서, 큰 입력 카드는 메인 화면을 떠나 진입 카드로 빼는 흐름). 여기서 채운
// 값(사례 정보 블록)은 화면을 나갈 때마다 SearchState에 스냅샷으로 저장해서, "블로그 반자동
// 복사"(RichCopyTestActivity)의 콘텐츠 생성이 그대로 이어받아 쓴다.
class KeywordCaseInfoActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var inputDistrict: EditText
    private lateinit var inputDong: EditText
    private lateinit var inputSymptom: EditText
    private lateinit var inputCause: EditText
    private lateinit var inputWork: EditText
    private lateinit var inputResult: EditText
    private lateinit var btnRegionSeoul: Button
    private lateinit var btnRegionGyeonggi: Button
    private lateinit var stageKeywordSpinner: Spinner

    private var selectedRegion: Region = Region.SEOUL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyword_case_info)

        statusText = findViewById(R.id.caseInfoStatusText)
        inputDistrict = findViewById(R.id.inputDistrict)
        inputDong = findViewById(R.id.inputDong)
        inputSymptom = findViewById(R.id.inputSymptom)
        inputCause = findViewById(R.id.inputCause)
        inputWork = findViewById(R.id.inputWork)
        inputResult = findViewById(R.id.inputResult)
        btnRegionSeoul = findViewById(R.id.btnRegionSeoul)
        btnRegionGyeonggi = findViewById(R.id.btnRegionGyeonggi)
        stageKeywordSpinner = findViewById(R.id.stageKeywordSpinner)

        applyInsetPadding()
        setupStageKeywordSpinner()
        updateRegionButtons()

        btnRegionSeoul.setOnClickListener { selectedRegion = Region.SEOUL; updateRegionButtons() }
        btnRegionGyeonggi.setOnClickListener { selectedRegion = Region.GYEONGGI; updateRegionButtons() }
        findViewById<Button>(R.id.btnRandomPlace).setOnClickListener {
            val place = PlacePicker.random(selectedRegion)
            inputDistrict.setText(place.district)
            inputDong.setText(place.dong)
        }
        findViewById<Button>(R.id.btnPickWorkType).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("작업 종류 선택")
                .setItems(WorkTypes.LIST.toTypedArray()) { _, which -> inputWork.setText(WorkTypes.LIST[which]) }
                .show()
        }
        findViewById<Button>(R.id.btnSearchSymptomKeyword).setOnClickListener {
            val keyword = inputSymptom.text.toString().trim()
            if (keyword.isBlank()) {
                Toast.makeText(this, "검색할 증상을 짧게 입력해주세요.", Toast.LENGTH_SHORT).show()
            } else {
                runBackgroundAnalysis(keyword)
            }
        }
        findViewById<Button>(R.id.btnResetCaseInfo).setOnClickListener {
            inputDistrict.setText("")
            inputDong.setText("")
            inputSymptom.setText("")
            inputCause.setText("")
            inputWork.setText("")
            inputResult.setText("")
            statusText.text = ""
            snapshotToSearchState()
        }
        // 입력을 끝내면 메인 화면으로 돌아가지 않고 바로 "블로그 반자동 복사"로 넘어갈 수 있게
        // 한다(사용자 요청) - 값은 항상 onPause에서 스냅샷되지만, 적용을 누르면 그 스냅샷을
        // 먼저 확실히 저장한 뒤 곧바로 이동한다.
        findViewById<Button>(R.id.btnApplyCaseInfo).setOnClickListener {
            snapshotToSearchState()
            val entry = currentBlogEntry()
            startActivity(
                Intent(this, RichCopyTestActivity::class.java)
                    .putExtra(RichCopyTestActivity.EXTRA_DONG, SearchState.dong)
                    .putExtra(RichCopyTestActivity.EXTRA_WATERMARK, entry.blogId)
                    .putExtra(RichCopyTestActivity.EXTRA_WATERMARK_PHONE, entry.phone)
            )
        }
        // "사진으로 글쓰기"로 바로 넘어가는 경로가 없어서(사용자 지적 - "적용이 반자동 복사
        // 하는데만 있어 사진으로 글쓰기는 없네"), 반자동 복사와 같은 방식으로 스냅샷을 먼저
        // 저장한 뒤 PhotoWriterActivity로 이동하는 버튼을 추가한다. PhotoWriterActivity는
        // extra 없이 SearchState를 직접 읽으므로 그대로 넘기기만 하면 된다.
        findViewById<Button>(R.id.btnApplyToPhotoWriter).setOnClickListener {
            snapshotToSearchState()
            startActivity(Intent(this, PhotoWriterActivity::class.java))
        }
    }

    private fun currentBlogEntry(): BlogProfileEntry {
        val entries = BlogProfileStore.allEntries(this)
        return entries.firstOrNull { it.id == SearchState.selectedBlogId } ?: entries.first()
    }

    // "동별 경쟁 낮은 곳 찾기" 화면에서 결과를 고르면 화면 이동 없이 SearchState에만 담아 두고
    // 그 화면을 finish()한다 - 여기로 돌아왔을 때(onResume) 지역/동 칸에 채워준다.
    override fun onResume() {
        super.onResume()
        val district = SearchState.pickedDistrict
        val dong = SearchState.pickedDong
        if (district != null) {
            inputDistrict.setText(district)
            if (dong != null) inputDong.setText(dong)
            SearchState.pickedDistrict = null
            SearchState.pickedDong = null
        }
    }

    // 이 화면을 벗어날 때마다(메인 화면으로 돌아가서 "블로그 반자동 복사"를 누르는 경우 포함)
    // 지금 채워진 값으로 사례 정보 블록을 다시 만들어 SearchState에 저장해둔다 - 그래야 이후
    // RichCopyTestActivity의 콘텐츠 생성이 항상 최신 값을 쓴다.
    override fun onPause() {
        super.onPause()
        snapshotToSearchState()
    }

    private fun snapshotToSearchState() {
        SearchState.caseInfoBlock = buildCaseInfoBlock()
        SearchState.informationalCaseInfoBlock = buildInformationalCaseInfoBlock()
        SearchState.hasLocationInfo = hasLocationInfo()
        SearchState.dong = inputDong.text.toString().trim()
    }

    // 화면 전체를 스크롤 없이 한 화면에 꽉 채우는 레이아웃으로 바꾸면서(사용자 지시 - "화면
    // 대비로 만들어"), 상태표시줄/제스처바 높이만큼만 위아래에 더해준다 - 기존처럼 큰 기본
    // 여백(20dp)을 추가로 깔면 weight로 계산해둔 공간이 넘쳐서 다시 스크롤이 생긴다.
    private fun applyInsetPadding() {
        val root = findViewById<View>(R.id.rootScroll)
        val topBase = (12 * resources.displayMetrics.density).toInt()
        val bottomBase = (10 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = topBase + bars.top, bottom = bottomBase + bars.bottom)
            insets
        }
    }

    // "작업 종류 목록에서 선택" 버튼 밑의 드롭다운 - 키워드 분석에서 검색해 둔 결과를 검색량
    // 기준으로 1/2/3단계로 나눠서 그 자리에서 목록을 보여준다.
    private fun setupStageKeywordSpinner() {
        val labels = listOf("단계별 키워드 가져오기", "1단계 키워드 가져오기", "2단계 키워드 가져오기", "3단계 키워드 가져오기")
        stageKeywordSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        stageKeywordSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in 1..3) {
                    showStageKeywordPicker(position)
                    stageKeywordSpinner.setSelection(0, false)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showStageKeywordPicker(stage: Int) {
        val staged = KeywordStage.keywordsForStage(SearchState.lastAnalysisResults, stage)
        if (staged.isEmpty()) {
            Toast.makeText(this, "먼저 키워드 찾기에서 검색해주세요. $stage 단계에 해당하는 키워드가 없습니다.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = staged.map { "${it.keyword}  (검색량 ${KeywordStage.totalVolume(it)} · 경쟁도 ${it.compIdx})" }
        AlertDialog.Builder(this)
            .setTitle("$stage 단계 키워드")
            .setItems(labels.toTypedArray()) { _, which ->
                val picked = staged[which]
                SearchState.stagedKeywordStage = stage
                SearchState.stagedKeywords = listOf(picked)
                SearchState.lastKeyword = picked.keyword
                fetchCompetitorPostsSilently(picked.keyword, ApiKeyStore.load(this))
                Toast.makeText(this, "\"${picked.keyword}\" 경쟁 글을 가져오는 중입니다.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun runBackgroundAnalysis(keyword: String) {
        val keys = ApiKeyStore.load(this)
        if (!keys.hasSearchAdKeys) {
            Toast.makeText(this, "네이버 검색광고 API 키를 API 키 설정에 입력해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        SearchState.lastKeyword = keyword
        SearchState.stagedKeywordStage = 0
        SearchState.stagedKeywords = emptyList()
        SearchState.lastAnalysisKeyword = ""
        SearchState.lastAnalysisResults = emptyList()
        SearchState.lastSearchedKeyword = ""
        SearchState.lastCompetitorPosts = emptyList()
        statusText.text = "🔎 \"$keyword\" 키워드와 경쟁 글 분석 중..."

        Thread {
            var results: List<NaverSearchAdApi.RelatedKeyword> = emptyList()
            var posts: List<NaverBlogSearchApi.BlogPost> = emptyList()
            val errors = mutableListOf<String>()
            try {
                results = NaverSearchAdApi.fetchRelatedKeywords(keys, keyword)
            } catch (e: Exception) {
                errors += "키워드 분석: ${e.message}"
            }
            try {
                posts = NaverBlogSearchApi.fetchRecentPosts(keys, keyword)
            } catch (e: Exception) {
                errors += "경쟁 글: ${e.message}"
            }
            if (SearchState.lastKeyword != keyword) return@Thread
            if (results.isNotEmpty()) {
                SearchState.lastAnalysisKeyword = keyword
                SearchState.lastAnalysisResults = results
            }
            if (errors.none { it.startsWith("경쟁 글:") }) {
                SearchState.lastSearchedKeyword = keyword
                SearchState.lastCompetitorPosts = posts
            }
            runOnUiThread {
                statusText.text = if (errors.isEmpty()) {
                    "✅ \"$keyword\" 분석 ${results.size}개 · 경쟁 글 ${posts.size}개 준비 완료"
                } else {
                    "일부 완료 · 키워드 ${results.size}개 · 경쟁 글 ${posts.size}개\n${errors.joinToString("\n")}"
                }
            }
        }.start()
    }

    private fun fetchCompetitorPostsSilently(keyword: String, keys: ApiKeyStore.Keys) {
        SearchState.lastSearchedKeyword = ""
        SearchState.lastCompetitorPosts = emptyList()
        Thread {
            try {
                val posts = NaverBlogSearchApi.fetchRecentPosts(keys, keyword)
                if (SearchState.lastKeyword == keyword) {
                    SearchState.lastSearchedKeyword = keyword
                    SearchState.lastCompetitorPosts = posts
                    runOnUiThread { statusText.text = "✅ \"$keyword\" 경쟁 글 ${posts.size}개 준비 완료" }
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "경쟁 글 검색 실패: ${e.message}" }
            }
        }.start()
    }

    private fun updateRegionButtons() {
        val selectedBg = ColorStateList.valueOf(getColor(R.color.primary))
        val unselectedBg = ColorStateList.valueOf(getColor(R.color.surface))
        fun style(button: Button, selected: Boolean) {
            button.backgroundTintList = if (selected) selectedBg else unselectedBg
            button.setTextColor(if (selected) getColor(android.R.color.white) else getColor(R.color.primary))
        }
        style(btnRegionSeoul, selectedRegion == Region.SEOUL)
        style(btnRegionGyeonggi, selectedRegion == Region.GYEONGGI)
    }

    private fun buildCaseInfoBlock(): String? {
        val district = inputDistrict.text.toString().trim()
        val dong = inputDong.text.toString().trim()
        val symptom = inputSymptom.text.toString().trim()
        val cause = inputCause.text.toString().trim()
        val work = inputWork.text.toString().trim()
        val result = inputResult.text.toString().trim()
        if (district.isBlank() && dong.isBlank() && symptom.isBlank() && cause.isBlank() && work.isBlank() && result.isBlank()) return null
        return buildString {
            append("[실제 출장 사례 자료 - 제공된 사실만 사용해]\n")
            val location = listOf(district, dong).filter { it.isNotBlank() }.joinToString(" ")
            if (location.isNotBlank()) append("지역: $location\n")
            if (symptom.isNotBlank()) append("고객이 말한 증상: $symptom\n")
            if (cause.isNotBlank()) append("확인된 원인: $cause\n")
            if (work.isNotBlank()) append("실제 작업 내용: $work\n")
            if (result.isNotBlank()) append("확인된 작업 결과: $result\n")
            if (cause.isBlank() || work.isBlank() || result.isBlank()) {
                append("비어 있는 항목은 실제 사실로 꾸며 채우지 마. 대신 증상에서 일반적으로 구분할 수 있는 여러 원인 또는 조치 유형을 조건부 표현으로 설명하고, 어떤 확인을 거쳐야 구분되는지 알려줘.\n")
            }
            append("제공되지 않은 측정값, 비용, 시간, 고객 반응은 추측하거나 만들어 쓰지 마.\n")
        }
    }

    private fun buildInformationalCaseInfoBlock(): String? {
        val symptom = inputSymptom.text.toString().trim()
        val cause = inputCause.text.toString().trim()
        val work = inputWork.text.toString().trim()
        val result = inputResult.text.toString().trim()
        if (symptom.isBlank() && cause.isBlank() && work.isBlank() && result.isBlank()) return null
        return buildString {
            append("[정보성 글 소재 - 특정 지역이나 업체를 특정하지 말고 아래 증상만 주제로 사용해줘]\n")
            if (symptom.isNotBlank()) append("검색 증상: $symptom\n")
            if (cause.isNotBlank()) append("사용자가 참고로 입력한 원인: $cause\n")
            if (work.isNotBlank()) append("사용자가 참고로 입력한 작업 유형: $work\n")
            if (result.isNotBlank()) append("사용자가 참고로 입력한 결과: $result\n")
            append("참고 정보는 특정 출장 사례처럼 서술하지 마. 비어 있는 정보는 만들지 말고, 가능한 원인과 해결 유형을 하나로 단정하지 말고 여러 유형으로 나누어 설명해줘.\n")
        }
    }

    private fun hasLocationInfo(): Boolean =
        inputDistrict.text.toString().isNotBlank() && inputDong.text.toString().isNotBlank()
}
