package com.keyword.research

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class KeywordSearchActivity : AppCompatActivity() {
    private lateinit var inputKeyword: EditText
    private lateinit var statusText: TextView
    private lateinit var autocompleteResultsContainer: LinearLayout
    private lateinit var competitorPostsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyword_search)

        inputKeyword = findViewById(R.id.inputKeyword)
        statusText = findViewById(R.id.statusText)
        autocompleteResultsContainer = findViewById(R.id.autocompleteResultsContainer)
        competitorPostsContainer = findViewById(R.id.competitorPostsContainer)

        applyBottomInsetPadding()

        // "단계별 키워드 가져오기"에서 고른 키워드가 있으면 입력칸도 그 키워드로 채운다(메인의
        // "키워드 찾기" 내용이 아니라) - 경쟁사 글목록 검색도 이 목록으로 진행한다. 이 화면에서
        // "검색"을 누르면 이 목록은 지워진다(아래 onSearchClicked).
        if (SearchState.stagedKeywords.isNotEmpty()) {
            inputKeyword.setText(SearchState.stagedKeywords.first().keyword)
            runStagedBlogSearch(SearchState.stagedKeywordStage, SearchState.stagedKeywords)
        } else {
            inputKeyword.setText(SearchState.lastKeyword)
            if (SearchState.lastKeyword.isNotBlank()) {
                if (SearchState.lastSearchedKeyword == SearchState.lastKeyword) {
                    renderCompetitorPosts(SearchState.lastCompetitorPosts)
                    statusText.text = "미리 가져온 경쟁 글 ${SearchState.lastCompetitorPosts.size}개"
                } else {
                    onSearchClicked()
                }
            }
        }

        findViewById<Button>(R.id.btnSearch).setOnClickListener { onSearchClicked() }
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

    // 직접 검색해도 자동완성과 경쟁 글을 함께 가져와 메인 요청문 생성에서 바로 쓸 수 있게 한다.
    private fun onSearchClicked() {
        val keyword = inputKeyword.text.toString().trim()
        if (keyword.isBlank()) {
            Toast.makeText(this, "키워드를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        SearchState.lastKeyword = keyword
        SearchState.lastSearchedKeyword = keyword
        SearchState.stagedKeywordStage = 0
        SearchState.stagedKeywords = emptyList()
        SearchState.lastCompetitorPosts = emptyList()
        statusText.text = "검색 중..."
        competitorPostsContainer.removeAllViews()
        autocompleteResultsContainer.removeAllViews()

        Thread {
            var autocomplete: List<String> = emptyList()
            var errorMessage: String? = null
            try {
                autocomplete = NaverAutocompleteApi.fetchSuggestions(keyword)
            } catch (e: Exception) {
                errorMessage = "자동완성: ${e.message}"
            }
            var posts: List<NaverBlogSearchApi.BlogPost> = emptyList()
            try {
                posts = NaverBlogSearchApi.fetchRecentPosts(ApiKeyStore.load(this), keyword)
                SearchState.lastCompetitorPosts = posts
                SearchState.lastSearchedKeyword = keyword
            } catch (e: Exception) {
                errorMessage = listOfNotNull(errorMessage, "경쟁 글: ${e.message}").joinToString("\n")
            }

            runOnUiThread {
                renderAutocomplete(autocomplete)
                renderCompetitorPosts(posts)
                statusText.text = errorMessage ?: "완료 · 자동완성 ${autocomplete.size}개 · 경쟁 글 ${posts.size}개"
            }
        }.start()
    }

    // "단계별 키워드 가져오기"로 넘어온 키워드 목록으로 경쟁사 글목록을 검색한다. 키워드가 여러
    // 개이므로 각 키워드별로 나눠서(최대 8개) 결과를 모아 보여준다.
    private fun runStagedBlogSearch(stage: Int, keywords: List<NaverSearchAdApi.RelatedKeyword>) {
        val keys = ApiKeyStore.load(this)
        val targets = keywords.take(8)
        statusText.text = "$stage 단계 키워드 ${targets.size}개로 글목록 검색 중..."
        autocompleteResultsContainer.removeAllViews()
        competitorPostsContainer.removeAllViews()

        Thread {
            val grouped = LinkedHashMap<String, List<NaverBlogSearchApi.BlogPost>>()
            var errorMessage: String? = null
            targets.forEach { kw ->
                try {
                    grouped[kw.keyword] = NaverBlogSearchApi.fetchRecentPosts(keys, kw.keyword)
                } catch (e: Exception) {
                    errorMessage = (errorMessage?.plus("\n") ?: "") + "${kw.keyword}: ${e.message}"
                }
            }
            // 요청문 생성(PromptBuilder)이 참고하는 "최근 경쟁 블로그 글"도 이 글목록 검색 결과로
            // 채운다 - 이제 경쟁사 글 목록은 오직 이 흐름(단계별 키워드 가져오기)에서만 채워진다.
            SearchState.lastCompetitorPosts = grouped.values.flatten()
            SearchState.lastSearchedKeyword = targets.firstOrNull()?.keyword.orEmpty()

            runOnUiThread {
                renderStagedCompetitorPosts(stage, grouped)
                statusText.text = errorMessage ?: "$stage 단계 키워드 ${targets.size}개 글목록 검색 완료"
            }
        }.start()
    }

    private fun renderStagedCompetitorPosts(stage: Int, grouped: Map<String, List<NaverBlogSearchApi.BlogPost>>) {
        competitorPostsContainer.removeAllViews()
        if (grouped.isEmpty()) {
            competitorPostsContainer.addView(rowText("결과가 없습니다."))
            return
        }
        grouped.forEach { (keyword, posts) ->
            competitorPostsContainer.addView(TextView(this).apply {
                text = "[$stage 단계] $keyword"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.text_primary))
                setPadding(12, 12, 12, 4)
            })
            if (posts.isEmpty()) {
                competitorPostsContainer.addView(rowText("결과가 없습니다."))
            } else {
                posts.forEach { p -> addCompetitorPostRow(p) }
            }
            competitorPostsContainer.addView(divider())
        }
    }

    private fun renderAutocomplete(suggestions: List<String>) {
        autocompleteResultsContainer.removeAllViews()
        if (suggestions.isEmpty()) {
            autocompleteResultsContainer.addView(rowText("결과가 없습니다."))
            return
        }
        suggestions.forEach { s -> autocompleteResultsContainer.addView(rowText(s)) }
    }

    private fun renderCompetitorPosts(posts: List<NaverBlogSearchApi.BlogPost>) {
        competitorPostsContainer.removeAllViews()
        if (posts.isEmpty()) {
            competitorPostsContainer.addView(rowText("결과가 없습니다."))
        } else {
            posts.forEach { addCompetitorPostRow(it) }
        }
    }

    private fun addCompetitorPostRow(p: NaverBlogSearchApi.BlogPost) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
        }
        container.addView(TextView(this).apply {
            text = p.title
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text_primary))
        })
        if (p.description.isNotBlank()) {
            container.addView(TextView(this).apply {
                text = p.description
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            })
        }
        container.addView(TextView(this).apply {
            text = "${p.bloggerName}  ·  ${p.postDate}"
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
        })
        competitorPostsContainer.addView(container)
        competitorPostsContainer.addView(divider())
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
