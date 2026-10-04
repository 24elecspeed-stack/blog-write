package com.keyword.research

import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class BlogTitleActivity : AppCompatActivity() {
    private lateinit var inputBlogId: EditText
    private lateinit var blogTitleStatusText: TextView
    private lateinit var blogTitleResultsContainer: LinearLayout
    private lateinit var inputCheckKeywords: EditText
    private lateinit var keywordResultsContainer: LinearLayout
    private lateinit var hiddenWebView: WebView

    private var lastExtractedBlogId: String = ""
    private var lastExtractedTitles: List<NaverBlogTitleApi.BlogPostTitle> = emptyList()
    private var blogTitleRankViews: List<TextView> = emptyList()
    private var isVerifyingAll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blog_title)

        inputBlogId = findViewById(R.id.inputBlogId)
        blogTitleStatusText = findViewById(R.id.blogTitleStatusText)
        blogTitleResultsContainer = findViewById(R.id.blogTitleResultsContainer)
        inputCheckKeywords = findViewById(R.id.inputCheckKeywords)
        keywordResultsContainer = findViewById(R.id.keywordResultsContainer)
        hiddenWebView = findViewById(R.id.hiddenWebView)

        applyBottomInsetPadding()
        findViewById<Button>(R.id.btnExtractTitles).setOnClickListener { onExtractTitlesClicked() }
        findViewById<Button>(R.id.btnVerifyAllTitles).setOnClickListener { onVerifyAllTitlesClicked() }
        findViewById<Button>(R.id.btnCheckKeywords).setOnClickListener { onCheckKeywordsClicked() }
    }

    private fun applyBottomInsetPadding() {
        val root = findViewById<View>(R.id.rootScroll)
        val basePadding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = basePadding + bars.bottom)
            insets
        }
    }

    private fun onExtractTitlesClicked() {
        val blogId = inputBlogId.text.toString().trim().lowercase()
        if (blogId.isBlank()) {
            Toast.makeText(this, "블로그 ID를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        blogTitleStatusText.text = "제목 추출 중..."
        blogTitleResultsContainer.removeAllViews()

        Thread {
            try {
                val titles = NaverBlogTitleApi.fetchTitles(blogId, 30)
                runOnUiThread {
                    lastExtractedBlogId = blogId
                    lastExtractedTitles = titles
                    renderBlogTitles(titles)
                    blogTitleStatusText.text = "완료 - ${titles.size}개 제목 추출"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    blogTitleStatusText.text = "제목 추출 실패: ${e.message}"
                }
            }
        }.start()
    }

    private fun renderBlogTitles(titles: List<NaverBlogTitleApi.BlogPostTitle>) {
        blogTitleResultsContainer.removeAllViews()
        if (titles.isEmpty()) {
            blogTitleResultsContainer.addView(rowText("결과가 없습니다. 블로그 ID를 확인해주세요."))
            blogTitleRankViews = emptyList()
            return
        }
        val rankViews = mutableListOf<TextView>()
        titles.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12, 10, 12, 10)
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(this).apply {
                text = item.title
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            })
            textCol.addView(TextView(this).apply {
                text = item.date
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
            })
            val rankStatus = TextView(this).apply {
                text = "미검증"
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
            }
            textCol.addView(rankStatus)
            rankViews.add(rankStatus)

            val checkBtn = Button(this).apply {
                text = "누락 검증"
                textSize = 10f
                setOnClickListener {
                    if (isVerifyingAll || isCheckingKeywords) {
                        Toast.makeText(this@BlogTitleActivity, "다른 검증이 진행 중입니다. 끝난 뒤 다시 눌러주세요.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    verifyOne(item, rankStatus) { }
                }
            }
            row.addView(textCol)
            row.addView(checkBtn)
            blogTitleResultsContainer.addView(row)
            blogTitleResultsContainer.addView(divider())
        }
        blogTitleRankViews = rankViews
    }

    // 순위 확인 하나로 끝내지 않고, "❌ 누락"이 나오면 이 글의 URL 자체를 다시 검색해서 두 가지를
    // 구분한다: URL을 검색해도 전혀 안 보이면 아직 색인 자체가 안 된 것(최근 발행 등)이고, URL은
    // 보이는데 제목으로는 안 보이면 색인은 됐지만 그 제목 표현으로는 경쟁에서 밀린 것이다. 블로거들이
    // 흔히 쓰는 "URL을 직접 검색해서 색인 여부 확인하기" 방법을 그대로 자동화한 것 - 네이버가 정확한
    // 이유를 공개하지 않아서 100% 확답은 아니지만, 현재 파악 가능한 범위의 근거 있는 추정이다.
    private fun verifyOne(item: NaverBlogTitleApi.BlogPostTitle, rankStatus: TextView, onDone: (registered: Boolean) -> Unit) {
        rankStatus.text = "검증 중..."
        NaverRankChecker.checkRanking(hiddenWebView, item.title, lastExtractedBlogId) { rank, error ->
            when {
                error != null -> {
                    rankStatus.text = "오류: $error"
                    onDone(false)
                }
                rank > 0 -> {
                    rankStatus.text = "✅ 등록 (${rank}위)"
                    onDone(true)
                }
                else -> {
                    rankStatus.text = "❌ 누락 · 이유 확인 중..."
                    NaverRankChecker.checkRanking(hiddenWebView, item.url, lastExtractedBlogId) { urlRank, urlError ->
                        rankStatus.text = formatMissingReason(item, urlRank, urlError)
                        onDone(false)
                    }
                }
            }
        }
    }

    private fun formatMissingReason(item: NaverBlogTitleApi.BlogPostTitle, urlRank: Int, urlError: String?): String {
        val ageHint = if (isVeryRecent(item.date)) "발행한 지 얼마 안 됨(아직 색인 전일 수 있음) · " else ""
        val reason = when {
            urlError != null -> "이유 확인 실패"
            urlRank > 0 -> "색인은 됐지만 이 제목으로는 노출 안 됨(경쟁이 치열하거나 표현을 바꿔볼 필요)"
            else -> "이 글 자체가 검색에서 안 보임(아직 색인 전이거나 검색 제외 상태로 보임)"
        }
        return "❌ 누락 · $ageHint$reason"
    }

    private fun isVeryRecent(dateText: String): Boolean =
        dateText.contains("분 전") || dateText.contains("시간 전")

    // 네이버는 어뷰징·저품질 판정 여부를 공식적으로 공개하지 않아서 100% 확정은 불가능하다.
    // 다만 체크한 글 대부분이 한꺼번에 누락되는 것은 블로거들 사이에서 어뷰징/저품질 제재의 대표적인
    // 관찰 신호로 알려져 있다 - 글 하나하나의 개별 사정(경쟁 키워드 등)이 아니라 블로그 전체에 걸친
    // 패턴이기 때문이다. 그래서 "확정"이 아니라 "의심됨/의심 신호 없음"으로만 표시한다.
    private fun formatAbuseSuspicion(total: Int, rate: Int): String {
        if (total < 5) return "ℹ️ 어뷰징/저품질 의심 여부는 최소 5개 이상 확인해야 판단할 수 있습니다."
        return if (rate < 30) {
            "⚠️ 어뷰징/저품질 의심됨 - 확인한 글의 노출률이 ${rate}%로 매우 낮습니다. 여러 글이 한꺼번에 " +
                "누락되는 것은 블로그 전체 제재의 신호일 수 있습니다(단, 네이버가 사유를 공개하지 않아 확정은 아닙니다)."
        } else {
            "✅ 어뷰징/저품질 의심 신호 없음 - 노출률이 정상 범위(${rate}%)입니다."
        }
    }

    // 원본 확장프로그램의 "누락 검증" 버튼과 동일한 동작: 추출된 제목 전체를 한 줄씩 순서대로
    // (WebView가 메인 스레드/한 번에 하나만 써야 해서 동시에는 못 돌린다) 자동으로 검증한다.
    private fun onVerifyAllTitlesClicked() {
        if (lastExtractedTitles.isEmpty()) {
            Toast.makeText(this, "먼저 블로그 제목을 추출해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isVerifyingAll || isCheckingKeywords) {
            Toast.makeText(this, "이미 다른 검증이 진행 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        isVerifyingAll = true
        registeredCount = 0
        missingCount = 0
        verifyTitleAt(0)
    }

    private var registeredCount = 0
    private var missingCount = 0

    private fun verifyTitleAt(index: Int) {
        if (index >= lastExtractedTitles.size) {
            isVerifyingAll = false
            val total = lastExtractedTitles.size
            val rate = if (total > 0) registeredCount * 100 / total else 0
            blogTitleStatusText.text = "✅ 전체 검증 완료 (${total}개) · 노출 ${registeredCount}개 · 누락 ${missingCount}개 · " +
                "노출률 ${rate}% - 지금 확인한 글 기준 내 블로그 전체 지수입니다.\n${formatAbuseSuspicion(total, rate)}"
            return
        }
        val item = lastExtractedTitles[index]
        blogTitleStatusText.text = "전체 검증 중... (${index + 1}/${lastExtractedTitles.size})"
        val rankStatus = blogTitleRankViews.getOrNull(index)
        if (rankStatus == null) {
            blogTitleResultsContainer.postDelayed({ verifyTitleAt(index + 1) }, 100)
            return
        }
        verifyOne(item, rankStatus) { registered ->
            if (registered) registeredCount++ else missingCount++
            // 원본과 동일하게 요청 사이에 짧은 텀을 둔다 (검색 서버에 과도한 연속 요청 방지).
            blogTitleResultsContainer.postDelayed({ verifyTitleAt(index + 1) }, 500)
        }
    }

    // === 직접 입력한 키워드로 확인 (검색량·경쟁도 + 실제 검색 순위) ===

    private var isCheckingKeywords = false

    private fun onCheckKeywordsClicked() {
        val blogId = inputBlogId.text.toString().trim().lowercase()
        if (blogId.isBlank()) {
            Toast.makeText(this, "먼저 블로그 ID를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val keywords = inputCheckKeywords.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }.take(10)
        if (keywords.isEmpty()) {
            Toast.makeText(this, "확인할 키워드를 한 줄에 하나씩 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isVerifyingAll || isCheckingKeywords) {
            Toast.makeText(this, "이미 다른 검증이 진행 중입니다. 끝난 뒤 다시 눌러주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val keys = ApiKeyStore.load(this)
        val hasAdKeys = keys.hasSearchAdKeys
        keywordResultsContainer.removeAllViews()
        blogTitleStatusText.text = "🔎 키워드 검색량을 조회하는 중입니다..."
        Thread {
            val volumes = keywords.associateWith { keyword -> if (hasAdKeys) fetchVolume(keys, keyword) else null }
            runOnUiThread {
                startKeywordVerification(blogId, keywords, volumes)
            }
        }.start()
    }

    private data class Volume(val pc: String, val mobile: String, val competition: String)

    private fun fetchVolume(keys: ApiKeyStore.Keys, keyword: String): Volume? {
        return try {
            val related = NaverSearchAdApi.fetchRelatedKeywords(keys, keyword)
            val sanitized = keyword.replace(Regex("\\s+"), "")
            related.firstOrNull { it.keyword.replace(Regex("\\s+"), "") == sanitized }
                ?.let { Volume(it.monthlyPcQc, it.monthlyMobileQc, it.compIdx) }
        } catch (e: Exception) {
            null
        }
    }

    private var keywordBlogRankViews: List<TextView> = emptyList()
    private var keywordViewRankViews: List<TextView> = emptyList()
    private var keywordIntegratedRankViews: List<TextView> = emptyList()

    private fun startKeywordVerification(blogId: String, keywords: List<String>, volumes: Map<String, Volume?>) {
        keywordResultsContainer.removeAllViews()
        val blogRankViews = mutableListOf<TextView>()
        val viewRankViews = mutableListOf<TextView>()
        val integratedRankViews = mutableListOf<TextView>()
        keywords.forEach { keyword ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 10, 12, 10)
            }
            row.addView(TextView(this).apply {
                text = keyword
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            })
            val volume = volumes[keyword]
            if (volume != null) {
                row.addView(TextView(this).apply {
                    text = "검색량 PC ${volume.pc} · 모바일 ${volume.mobile} · 경쟁도 ${volume.competition}"
                    textSize = 11f
                    setTextColor(getColor(R.color.text_secondary))
                })
            }
            val blogRankStatus = TextView(this).apply {
                text = "블로그 탭 검증: 대기 중..."
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
            }
            val viewRankStatus = TextView(this).apply {
                text = "VIEW 탭 검증: 대기 중..."
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
            }
            val integratedRankStatus = TextView(this).apply {
                text = "통합검색 검증: 대기 중..."
                textSize = 11f
                setTextColor(getColor(R.color.text_secondary))
            }
            row.addView(blogRankStatus)
            row.addView(viewRankStatus)
            row.addView(integratedRankStatus)
            blogRankViews += blogRankStatus
            viewRankViews += viewRankStatus
            integratedRankViews += integratedRankStatus
            keywordResultsContainer.addView(row)
            keywordResultsContainer.addView(divider())
        }
        keywordBlogRankViews = blogRankViews
        keywordViewRankViews = viewRankViews
        keywordIntegratedRankViews = integratedRankViews
        isCheckingKeywords = true
        verifyKeywordAt(blogId, keywords, 0)
    }

    private fun verifyKeywordAt(blogId: String, keywords: List<String>, index: Int) {
        if (index >= keywords.size) {
            isCheckingKeywords = false
            blogTitleStatusText.text = "✅ 키워드 확인 완료 (${keywords.size}개) · 실제 네이버 검색 기준"
            return
        }
        val keyword = keywords[index]
        blogTitleStatusText.text = "🔎 블로그 탭 검증 중... (${index + 1}/${keywords.size})"
        keywordBlogRankViews[index].text = "블로그 탭 검증: 확인 중..."
        keywordViewRankViews[index].text = "VIEW 탭 검증: 대기 중..."
        keywordIntegratedRankViews[index].text = "통합검색 검증: 대기 중..."
        // 블로그 탭(where=blog) · VIEW 탭(where=view) · 통합검색(where 없음)은 서로 다른 탭으로,
        // 같은 키워드에도 노출 결과가 다르게 나올 수 있다(실기기 테스트에서 확인됨 - VIEW 탭/
        // 통합검색에는 안 잡히지만 블로그 탭에는 1위로 나오는 경우 있음). 세 탭 모두 따옴표 없이
        // 사용자가 입력한 키워드 그대로 검색해서 실제 검색창에 치는 것과 동일하게 맞춘다.
        NaverRankChecker.checkRankingBlogTab(hiddenWebView, keyword, blogId) { blogRank, blogError ->
            keywordBlogRankViews[index].text = "블로그 탭 검증: " + when {
                blogError != null -> "오류 - $blogError"
                blogRank > 0 -> "🔎 ${blogRank}위 노출"
                else -> "❌ 노출 안 됨"
            }
            blogTitleStatusText.text = "🔎 VIEW 탭 검증 중... (${index + 1}/${keywords.size})"
            keywordViewRankViews[index].text = "VIEW 탭 검증: 확인 중..."
            NaverRankChecker.checkRanking(hiddenWebView, keyword, blogId, exactMatch = false) { viewRank, viewError ->
                keywordViewRankViews[index].text = "VIEW 탭 검증: " + when {
                    viewError != null -> "오류 - $viewError"
                    viewRank > 0 -> "🔎 ${viewRank}위 노출"
                    else -> "❌ 노출 안 됨"
                }
                blogTitleStatusText.text = "🔎 통합검색 검증 중... (${index + 1}/${keywords.size})"
                keywordIntegratedRankViews[index].text = "통합검색 검증: 확인 중..."
                NaverRankChecker.checkRankingIntegrated(hiddenWebView, keyword, blogId) { intRank, intError ->
                    // 통합검색 페이지는 블로그·카페·지식iN·뉴스가 섞여서 한 화면에 블로그 링크가
                    // 몇 개 안 뜨는 경우가 많다 - "N위"는 실제로 찾은 순서이고, 못 찾았을 때는 그
                    // 페이지에서 렌더링된 범위 안에 없었다는 뜻이지 "100위 밖"이라고 단정할 수는 없다.
                    keywordIntegratedRankViews[index].text = "통합검색 검증: " + when {
                        intError != null -> "오류 - $intError"
                        intRank > 0 -> "🔎 ${intRank}위 노출"
                        else -> "❌ 노출 확인 안 됨"
                    }
                    keywordResultsContainer.postDelayed({ verifyKeywordAt(blogId, keywords, index + 1) }, 500)
                }
            }
        }
    }

    private fun rowText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(12, 10, 12, 10)
        setTextColor(getColor(R.color.text_primary))
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(getColor(R.color.background))
    }
}
