package com.keyword.research

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 특정 네이버 블로그(blogId)의 게시글 제목 목록을 가져온다. background.js의 extractNaverTitles를
// 그대로 이식 (직접 읽고 확인함): GET https://blog.naver.com/PostTitleListAsync.naver?blogId=..
// &currentPage=..&countPerPage=30 - 응답이 유효하지 않은 백슬래시가 섞인 JSON이라 파싱 전에
// 이스케이프를 보정한다. 원본은 로그인 쿠키를 함께 보내지만, 이 앱은 브라우저 세션이 없으므로
// 공개 블로그 기준으로 쿠키 없이 호출한다(비공개 블로그는 조회 실패할 수 있음).
object NaverBlogTitleApi {

    data class BlogPostTitle(val index: Int, val title: String, val date: String, val url: String, val logNo: String)

    class ApiException(message: String) : Exception(message)

    fun fetchTitles(blogId: String, maxCount: Int): List<BlogPostTitle> {
        val results = mutableListOf<BlogPostTitle>()
        val seenLogNo = mutableSetOf<String>()
        var page = 1

        while (results.size < maxCount) {
            val body = fetchPage(blogId, page)

            // 원본과 동일한 보정: 유효한 이스케이프(", \, /, b,f,n,r,t, u+4자리)가 아닌 단독
            // 백슬래시를 이중 백슬래시로 바꿔 JSON 파싱이 깨지지 않게 한다. 람다 치환을 써서
            // 치환 문자열의 백슬래시 이스케이프 해석 문제를 피한다(리터럴 그대로 삽입됨).
            val invalidEscape = Regex("""\\(?!["\\/bfnrt]|u[0-9a-fA-F]{4})""")
            val sanitized = invalidEscape.replace(body) { "\\\\" }

            val postList: JSONArray = try {
                org.json.JSONObject(sanitized).optJSONArray("postList") ?: JSONArray()
            } catch (e: Exception) {
                throw ApiException("블로그 제목 응답을 해석하지 못했습니다: ${e.message}")
            }

            if (postList.length() == 0) break

            if (page > 1) Thread.sleep(300) // 페이지를 너무 빠르게 연속 요청하면 429(요청 제한)를 유발할 수 있음

            for (i in 0 until postList.length()) {
                if (results.size >= maxCount) break
                val item = postList.getJSONObject(i)
                val logNo = item.optString("logNo")
                if (logNo.isBlank() || !seenLogNo.add(logNo)) continue

                val rawTitle = item.optString("title")
                val title = try {
                    java.net.URLDecoder.decode(rawTitle, "UTF-8")
                } catch (e: Exception) {
                    rawTitle
                }.replace('+', ' ').replace(Regex("<[^>]*>"), "")

                val date = formatNaverDate(item.opt("addDate"))
                results.add(BlogPostTitle(
                    index = results.size + 1,
                    title = title,
                    date = date,
                    url = "https://m.blog.naver.com/$blogId/$logNo",
                    logNo = logNo
                ))
            }
            page++
        }
        return results
    }

    // 네이버가 요청을 너무 자주 보낸 것으로 보고 429(요청 제한)를 주는 경우가 있다 - 실제 blogId가
    // 잘못된 것과는 다른 상황이라, 한 번은 잠깐 대기 후 자동 재시도하고 그래도 안 되면 "blogId를
    // 확인해달라"는 오해의 소지가 있는 문구 대신 정확한 원인을 안내한다.
    private fun fetchPage(blogId: String, page: Int, retryOn429: Boolean = true): String {
        val url = URL("https://blog.naver.com/PostTitleListAsync.naver?blogId=${URLEncoder.encode(blogId, "UTF-8")}&currentPage=$page&countPerPage=30")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            setRequestProperty("Referer", "https://blog.naver.com/PostList.naver?blogId=${URLEncoder.encode(blogId, "UTF-8")}")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            connectTimeout = 15000
            readTimeout = 15000
        }
        try {
            val code = conn.responseCode
            if (code == 429 && retryOn429) {
                conn.disconnect()
                Thread.sleep(2500)
                return fetchPage(blogId, page, retryOn429 = false)
            }
            if (code == 429) throw ApiException("네이버가 일시적으로 요청을 제한하고 있습니다(429). blogId 문제가 아니라 너무 자주 요청해서 생기는 문제입니다 - 잠시(1~2분) 기다렸다가 다시 시도해주세요.")
            if (code !in 200..299) throw ApiException("블로그 제목 조회 오류($code) - blogId를 확인해주세요.")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun formatNaverDate(raw: Any?): String {
        val str = raw?.toString().orEmpty()
        val millis = str.toLongOrNull()
        if (millis != null && str.length > 5) {
            return SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).format(Date(millis))
        }
        return str
    }
}
