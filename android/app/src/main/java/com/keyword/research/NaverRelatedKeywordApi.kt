package com.keyword.research

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// background.js의 getMultiSourceRelatedKeywords를 참고해 네이버/구글/유튜브 3개 소스에서
// 연관 검색어를 모아 합친다. 네이버는 원래 검색결과 페이지의 "연관검색어" 박스를 WebView로
// 렌더링해서 긁는 방식이었는데, 네이버가 마크업을 바꿀 때마다 셀렉터가 깨져서 실사용 중
// "네이버만 계속 0개로 나온다"는 문제가 반복 확인됐다(구글/유튜브는 안정적인 JSON API라
// 항상 잘 됨). 그래서 네이버도 검색창 자동완성이 쓰는 공개 JSON API(ac.search.naver.com)로
// 바꿨다 - 화면 마크업에 의존하지 않는 안정적인 API라 구글/유튜브와 같은 방식이 된다.
object NaverRelatedKeywordApi {

    data class MultiSourceResult(
        val naver: List<String>,
        val google: List<String>,
        val youtube: List<String>
    ) {
        val all: List<String> get() = (naver + google + youtube).distinct()
    }

    class ApiException(message: String) : Exception(message)

    // 네이버 검색창 자동완성 API(검색창에 실제로 타이핑할 때 쓰이는 그 API). 응답은
    // {"items":[[["제안1",0],["제안2",0],...]], ...} 형태가 기본이지만, 네이버가 필드를
    // 조금씩 바꿔온 이력이 있어(원소가 문자열 하나뿐이거나 배열로 감싸져 있는 경우 등) 두
    // 형태 모두 안전하게 처리한다. HTML 렌더링이 필요 없는 순수 API라 마크업 변경에
    // 영향받지 않는다(구글/유튜브 자동완성과 같은 성격).
    fun fetchNaverSuggestions(keyword: String): List<String> {
        val url = URL(
            "https://ac.search.naver.com/nx/ac?q=${URLEncoder.encode(keyword, "UTF-8")}" +
                "&con=0&frm=nv&ans=2&r_format=json&r_enc=UTF-8&r_unicode=0&t_koreng=1&run=2&rev=4&q_enc=UTF-8&st=100"
        )
        val body = httpGet(url)
        return try {
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            val group = items.optJSONArray(0) ?: return emptyList()
            (0 until group.length()).mapNotNull { i ->
                when (val entry = group.opt(i)) {
                    is String -> entry
                    is JSONArray -> entry.optString(0).takeIf { it.isNotBlank() }
                    else -> null
                }
            }.filter { it.isNotBlank() && !it.equals(keyword, ignoreCase = true) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 구글 자동완성. background.js 그대로: GET suggestqueries.google.com/complete/search?client=firefox
    // 응답은 ["검색어", ["제안1","제안2",...]] 형태의 순수 JSON 배열.
    fun fetchGoogleSuggestions(keyword: String): List<String> {
        val url = URL("https://suggestqueries.google.com/complete/search?client=firefox&hl=ko&q=${URLEncoder.encode(keyword, "UTF-8")}")
        val body = httpGet(url)
        return try {
            val arr = JSONArray(body)
            val suggestions = arr.optJSONArray(1) ?: return emptyList()
            (0 until suggestions.length()).map { suggestions.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // 유튜브 자동완성. background.js 그대로: GET clients1.google.com/complete/search?client=youtube
    // 응답이 "window.google...(...)" 식 JSONP라 첫 '('와 마지막 ')' 사이만 잘라 파싱한다.
    // 결과는 [["제안1", ...], ["제안2", ...], ...] 형태라 각 항목의 첫 값만 취한다.
    fun fetchYoutubeSuggestions(keyword: String): List<String> {
        val url = URL("https://clients1.google.com/complete/search?client=youtube&hl=ko&gs_ri=youtube&ds=yt&q=${URLEncoder.encode(keyword, "UTF-8")}")
        val body = httpGet(url)
        val start = body.indexOf('(')
        val end = body.lastIndexOf(')')
        if (start == -1 || end == -1 || end <= start) return emptyList()
        return try {
            val arr = JSONArray(body.substring(start + 1, end))
            val suggestions = arr.optJSONArray(1) ?: return emptyList()
            (0 until suggestions.length()).mapNotNull { i ->
                suggestions.optJSONArray(i)?.optString(0)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun httpGet(url: URL): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // 진짜 브라우저처럼 보이게 User-Agent를 지정한다 - 기본 Java UA로는 구글이 다른
            // 응답(차단/다른 포맷)을 줄 수 있다. Accept-Encoding은 직접 건드리지 않는다
            // (안드로이드가 자동으로 압축 협상+해제를 해주는데, 이전에 이 헤더를 직접
            // "identity"로 강제했다가 그 자동 처리가 꺼지면서 서버가 그래도 압축해서 보낸
            // 응답을 압축 해제 없이 읽어 글자가 깨졌었다).
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            val code = conn.responseCode
            val rawStream = if (code in 200..299) conn.inputStream else conn.errorStream
            // 만에 하나 서버가 Accept-Encoding 협상과 무관하게 압축해서 보내는 경우에 대비해
            // Content-Encoding을 직접 확인해서 필요하면 수동으로 압축을 해제한다.
            val contentEncoding = conn.contentEncoding?.lowercase().orEmpty()
            val stream = if (contentEncoding.contains("gzip")) {
                java.util.zip.GZIPInputStream(rawStream)
            } else {
                rawStream
            }
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw ApiException("API 오류($code): $body")
            return body
        } finally {
            conn.disconnect()
        }
    }
}
