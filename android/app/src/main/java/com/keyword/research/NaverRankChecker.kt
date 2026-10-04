package com.keyword.research

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLEncoder

// "누락 검증" - 특정 글 제목이 네이버 검색(VIEW 탭)에 노출되는지 확인한다.
// background.js의 performNaverRankCheck를 그대로 이식 (직접 읽고 확인함): 단순 HTTP GET으로
// HTML을 받아 파싱하는 방식이 아니라, 실제 브라우저 탭에서 검색 결과 페이지를 완전히 렌더링한
// 뒤 DOM에서 blog.naver.com 링크를 찾는 방식이다 (검색 결과가 JS로 그려지는 부분이 있어
// 단순 HTML 파싱으로는 링크가 안 잡히기 때문으로 보임). 이 앱에서는 숨겨진 WebView로 동일하게
// 재현한다 - 원본이 겪던 "에러/누락 오탐" 문제가 바로 이 렌더링 이슈였을 가능성이 높다.
object NaverRankChecker {

    // targetUrlFragment: 결과 링크 href에 포함되어야 하는 문자열 (보통 blogId, 예: "powerelecgo")
    // exactMatch=true면 title을 따옴표로 감싸 정확한 문구 매칭으로 검색한다("누락 검증"의 원래 용도 -
    // 글 제목 전체가 그대로 색인됐는지 확인). exactMatch=false면 따옴표 없이 검색어 그대로 검색한다
    // (사용자가 입력한 키워드로 확인할 때 - 따옴표 정확 매칭은 띄어쓰기/표현이 조금만 달라도
    // 결과가 0으로 나오는 오탐을 일으키기 때문에 키워드 검색에는 맞지 않는다).
    fun checkRanking(
        webView: WebView,
        title: String,
        targetUrlFragment: String,
        timeoutMs: Long = 20000,
        exactMatch: Boolean = true,
        onResult: (rank: Int, error: String?) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        var finished = false

        fun finish(rank: Int, error: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            onResult(rank, error)
        }

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 원본과 동일하게 페이지 완료 신호 후에도 2초 더 기다려 늦게 그려지는 결과를 포함시킨다.
                handler.postDelayed({
                    if (finished) return@postDelayed
                    val safeFragment = org.json.JSONObject.quote(targetUrlFragment)
                    val js = """
                        (function(){
                            var links = document.querySelectorAll("a[href*='blog.naver.com']");
                            var target = $safeFragment;
                            for (var i = 0; i < links.length; i++) {
                                if (links[i].href.indexOf(target) !== -1) return i + 1;
                            }
                            return -1;
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js) { resultStr ->
                        val rank = resultStr?.trim()?.toIntOrNull() ?: -1
                        finish(rank, null)
                    }
                }, 2000)
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                finish(-1, "페이지 로딩 실패: $description")
            }
        }

        handler.postDelayed({
            finish(-1, "20초 내에 검색 결과 페이지 로딩에 실패했습니다.")
        }, timeoutMs)

        val query = URLEncoder.encode(if (exactMatch) "\"$title\"" else title, "UTF-8")
        webView.loadUrl("https://search.naver.com/search.naver?where=view&sm=tab_jum&query=$query")
    }

    // 블로그 탭(where=blog) 기준으로 노출 순위를 확인한다. VIEW 탭과는 서로 다른 별도의 탭으로,
    // 네이버 검색 결과가 탭마다 다르게 나올 수 있다(실기기 테스트에서 VIEW 탭/통합검색에는 안
    // 잡히지만 블로그 탭에는 1위로 나오는 경우가 확인됨). keyword는 사용자가 입력한 검색어
    // 그대로, 따옴표 없이 검색한다.
    fun checkRankingBlogTab(
        webView: WebView,
        keyword: String,
        targetUrlFragment: String,
        timeoutMs: Long = 20000,
        onResult: (rank: Int, error: String?) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        var finished = false

        fun finish(rank: Int, error: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            onResult(rank, error)
        }

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                handler.postDelayed({
                    if (finished) return@postDelayed
                    val safeFragment = org.json.JSONObject.quote(targetUrlFragment)
                    val js = """
                        (function(){
                            var links = document.querySelectorAll("a[href*='blog.naver.com']");
                            var target = $safeFragment;
                            for (var i = 0; i < links.length; i++) {
                                if (links[i].href.indexOf(target) !== -1) return i + 1;
                            }
                            return -1;
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js) { resultStr ->
                        val rank = resultStr?.trim()?.toIntOrNull() ?: -1
                        finish(rank, null)
                    }
                }, 2000)
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                finish(-1, "페이지 로딩 실패: $description")
            }
        }

        handler.postDelayed({
            finish(-1, "20초 내에 검색 결과 페이지 로딩에 실패했습니다.")
        }, timeoutMs)

        val query = URLEncoder.encode(keyword, "UTF-8")
        webView.loadUrl("https://search.naver.com/search.naver?where=blog&query=$query")
    }

    // 통합검색(where 파라미터 없음 - 네이버 기본 검색 결과) 기준으로 노출 순위를 확인한다.
    // VIEW 탭과 달리 블로그/카페/뉴스/지식iN 등이 섞인 화면이라 "정확히 N위"의 의미가 VIEW 탭만큼
    // 깔끔하지 않지만, 사용자가 실제로 네이버에 검색했을 때 보는 화면과 동일하다.
    // title(제목 전체, 따옴표로 정확히 매칭)이 아니라 keyword(사용자가 입력한 검색어 그대로,
    // 실제 검색창에 치는 것과 동일하게 따옴표 없이)를 받는다.
    fun checkRankingIntegrated(
        webView: WebView,
        keyword: String,
        targetUrlFragment: String,
        timeoutMs: Long = 20000,
        onResult: (rank: Int, error: String?) -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())
        var finished = false

        fun finish(rank: Int, error: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            onResult(rank, error)
        }

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                handler.postDelayed({
                    if (finished) return@postDelayed
                    val safeFragment = org.json.JSONObject.quote(targetUrlFragment)
                    val js = """
                        (function(){
                            var links = document.querySelectorAll("a[href*='blog.naver.com']");
                            var target = $safeFragment;
                            for (var i = 0; i < links.length; i++) {
                                if (links[i].href.indexOf(target) !== -1) return i + 1;
                            }
                            return -1;
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(js) { resultStr ->
                        val rank = resultStr?.trim()?.toIntOrNull() ?: -1
                        finish(rank, null)
                    }
                }, 2000)
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                finish(-1, "페이지 로딩 실패: $description")
            }
        }

        handler.postDelayed({
            finish(-1, "20초 내에 검색 결과 페이지 로딩에 실패했습니다.")
        }, timeoutMs)

        val query = URLEncoder.encode(keyword, "UTF-8")
        webView.loadUrl("https://search.naver.com/search.naver?query=$query")
    }
}
