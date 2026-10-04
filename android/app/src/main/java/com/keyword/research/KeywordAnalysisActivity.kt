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

// 원본 확장프로그램의 "키워드 분석"(네이버 검색광고 키워드도구 - PC/모바일 검색량, 경쟁강도) 탭을
// 이식. NaverSearchAdApi는 이미 있었지만 화면에 연결되어 있지 않았다.
class KeywordAnalysisActivity : AppCompatActivity() {
    private lateinit var inputKeyword: EditText
    private lateinit var statusText: TextView
    private lateinit var resultHeaderRow: LinearLayout
    private lateinit var resultsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyword_analysis)

        inputKeyword = findViewById(R.id.inputAnalysisKeyword)
        statusText = findViewById(R.id.statusText)
        resultHeaderRow = findViewById(R.id.resultHeaderRow)
        resultsContainer = findViewById(R.id.resultsContainer)

        applyBottomInsetPadding()
        findViewById<Button>(R.id.btnAnalyzeKeyword).setOnClickListener { onAnalyzeClicked() }

        // 키워드 검색에서 검색한 키워드를 그대로 이어받는다. 그 키워드로 이미 분석해 둔 결과가
        // 있으면 다시 부르지 않고 바로 보여주고, 없으면(또는 키워드가 바뀌었으면) 자동으로 분석을
        // 시작한다 - "키워드 검색을 하면 키워드 분석 화면 안에 그 목록이 나오게" 해달라는 요청.
        inputKeyword.setText(SearchState.lastKeyword)
        if (SearchState.lastAnalysisResults.isNotEmpty() && SearchState.lastAnalysisKeyword == SearchState.lastKeyword) {
            renderResults(SearchState.lastAnalysisResults)
            statusText.text = "완료 - ${SearchState.lastAnalysisResults.size}개 키워드"
        } else if (SearchState.lastKeyword.isNotBlank()) {
            onAnalyzeClicked()
        }
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

    private fun onAnalyzeClicked() {
        val keyword = inputKeyword.text.toString().trim()
        if (keyword.isBlank()) {
            Toast.makeText(this, "키워드를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val keys = ApiKeyStore.load(this)
        if (!keys.hasSearchAdKeys) {
            Toast.makeText(this, "네이버 검색광고 API 키를 API 키 설정에 입력해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        statusText.text = "키워드 분석 중..."
        resultsContainer.removeAllViews()
        resultHeaderRow.visibility = View.GONE

        Thread {
            try {
                val results = NaverSearchAdApi.fetchRelatedKeywords(keys, keyword)
                SearchState.lastAnalysisKeyword = keyword
                SearchState.lastAnalysisResults = results
                runOnUiThread {
                    renderResults(results)
                    statusText.text = "완료 - ${results.size}개 키워드"
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "분석 실패: ${e.message}" }
            }
        }.start()
    }

    private fun renderResults(results: List<NaverSearchAdApi.RelatedKeyword>) {
        resultsContainer.removeAllViews()
        if (results.isEmpty()) {
            resultHeaderRow.visibility = View.GONE
            resultsContainer.addView(rowText("결과가 없습니다."))
            return
        }
        resultHeaderRow.visibility = View.VISIBLE
        results.forEach { r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            val stage = KeywordStage.stageOf(r)
            val keywordLabel = if (stage != null) "${r.keyword} [${stage}단계]" else r.keyword
            row.addView(cell(keywordLabel, 2f, Gravity_START, true))
            row.addView(cell(r.monthlyPcQc, 1f, Gravity_END, false))
            row.addView(cell(r.monthlyMobileQc, 1f, Gravity_END, false))
            row.addView(cell(r.compIdx, 1f, Gravity_END, false))
            resultsContainer.addView(row)
            resultsContainer.addView(divider())
        }
    }

    private val Gravity_START = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
    private val Gravity_END = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL

    private fun cell(text: String, weight: Float, gravity: Int, padStart: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(R.color.text_primary))
        this.gravity = gravity
        if (padStart) setPadding(8.dp(), 0, 0, 0) else setPadding(0, 0, 8.dp(), 0)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
    }
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

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
