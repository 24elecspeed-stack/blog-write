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

class RelatedKeywordActivity : AppCompatActivity() {
    private lateinit var inputRelatedKeyword: EditText
    private lateinit var statusText: TextView
    private lateinit var keywordResultsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_related_keyword)

        inputRelatedKeyword = findViewById(R.id.inputRelatedKeyword)
        statusText = findViewById(R.id.statusText)
        keywordResultsContainer = findViewById(R.id.keywordResultsContainer)

        applyBottomInsetPadding()
        findViewById<Button>(R.id.btnExtractRelatedKeywords).setOnClickListener { onExtractRelatedKeywordsClicked() }
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

    // 확장프로그램의 실제 "연관 키워드 추출"(getMultiSourceRelatedKeywords)을 참고해 네이버 +
    // 구글 + 유튜브 자동완성을 합쳐서 보여준다. 셋 다 안정적인 JSON API라(화면 렌더링·WebView
    // 불필요) 한 백그라운드 스레드에서 순서대로 호출한다.
    private fun onExtractRelatedKeywordsClicked() {
        val keyword = inputRelatedKeyword.text.toString().trim()
        if (keyword.isBlank()) {
            Toast.makeText(this, "키워드를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = "연관 키워드 추출 중..."
        keywordResultsContainer.removeAllViews()

        Thread {
            val naverList = try { NaverRelatedKeywordApi.fetchNaverSuggestions(keyword) } catch (e: Exception) { emptyList() }
            val googleList = try { NaverRelatedKeywordApi.fetchGoogleSuggestions(keyword) } catch (e: Exception) { emptyList() }
            val youtubeList = try { NaverRelatedKeywordApi.fetchYoutubeSuggestions(keyword) } catch (e: Exception) { emptyList() }
            val result = NaverRelatedKeywordApi.MultiSourceResult(naverList, googleList, youtubeList)
            runOnUiThread {
                renderRelatedKeywords(result)
                statusText.text = "완료 - 네이버 ${naverList.size}개, 구글 ${googleList.size}개, 유튜브 ${youtubeList.size}개 (합계 ${result.all.size}개)"
            }
        }.start()
    }

    private fun renderRelatedKeywords(result: NaverRelatedKeywordApi.MultiSourceResult) {
        keywordResultsContainer.removeAllViews()
        if (result.all.isEmpty()) {
            keywordResultsContainer.addView(rowText("결과가 없습니다."))
            return
        }
        result.naver.forEach { keywordResultsContainer.addView(rowText("[네이버] $it")) }
        result.google.forEach { keywordResultsContainer.addView(rowText("[구글] $it")) }
        result.youtube.forEach { keywordResultsContainer.addView(rowText("[유튜브] $it")) }
    }

    private fun rowText(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(12, 10, 12, 10)
        setTextColor(getColor(R.color.text_primary))
    }
}
