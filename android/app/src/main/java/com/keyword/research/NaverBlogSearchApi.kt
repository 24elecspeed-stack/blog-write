package com.keyword.research

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 네이버 오픈API 블로그 검색. Chrome 확장프로그램 background.js의 callNaverBlogAPI와 동일한
// 엔드포인트/인증 방식 (직접 읽고 확인함): X-Naver-Client-Id/Secret 헤더만 필요, 서명 불필요.
// "경쟁사가 어떤 글을 쓰는지"는 title/description 스니펫으로 1차 파악 - 본문 전체 크롤링은 범위 밖.
object NaverBlogSearchApi {

    data class BlogPost(
        val title: String,
        val description: String,
        val bloggerName: String,
        val postDate: String,
        val link: String
    )

    class ApiException(message: String) : Exception(message)

    private const val ENDPOINT = "https://openapi.naver.com/v1/search/blog"

    fun fetchRecentPosts(keys: ApiKeyStore.Keys, query: String, display: Int = 20, sort: String = "date", start: Int = 1): List<BlogPost> {
        if (!keys.hasOpenApiKeys) throw ApiException("네이버 오픈API 키(Client ID/Secret)를 설정에 입력해주세요.")

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("$ENDPOINT?query=$encodedQuery&display=$display&start=$start&sort=$sort")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-Naver-Client-Id", keys.openApiClientId)
            setRequestProperty("X-Naver-Client-Secret", keys.openApiClientSecret)
            connectTimeout = 15000
            readTimeout = 15000
        }

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw ApiException("오픈API 오류($code): $body")

            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                BlogPost(
                    title = stripHtmlTags(item.optString("title")),
                    description = stripHtmlTags(item.optString("description")),
                    bloggerName = item.optString("bloggername"),
                    postDate = item.optString("postdate"),
                    link = item.optString("link")
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    // 오픈API 응답의 title/description에는 검색어 강조용 <b> 태그가 섞여 있어 제거한다.
    private fun stripHtmlTags(text: String): String =
        text.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
}
