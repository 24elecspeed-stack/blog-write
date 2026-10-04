package com.keyword.research

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 네이버 자동완성(인기 검색어) API. background.js의 fetchAutocomplete를 그대로 이식
// (직접 읽고 확인함): GET https://ac.search.naver.com/nx/ac?q=..&qry=..&q_enc=UTF-8&r_enc=UTF-8
// &r_format=json&con=1&st=111&frm=nx, 응답의 items[0] 배열이 자동완성 키워드 목록.
object NaverAutocompleteApi {

    class ApiException(message: String) : Exception(message)

    fun fetchSuggestions(query: String): List<String> {
        val params = listOf(
            "q" to query, "qry" to query, "q_enc" to "UTF-8", "r_enc" to "UTF-8",
            "r_format" to "json", "con" to "1", "st" to "111", "frm" to "nx"
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val url = URL("https://ac.search.naver.com/nx/ac?$params")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
            connectTimeout = 15000
            readTimeout = 15000
        }

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw ApiException("자동완성 API 오류($code): $body")

            val json = parseNaverAC(body) ?: return emptyList()
            val items = json.optJSONArray("items") ?: return emptyList()
            val first = items.optJSONArray(0) ?: return emptyList()
            return (0 until first.length()).mapNotNull { i ->
                val entry = first.opt(i)
                when (entry) {
                    is org.json.JSONArray -> if (entry.length() > 0) entry.optString(0) else null
                    else -> entry?.toString()
                }
            }.filter { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    // background.js의 parseNaverAC를 그대로 이식: 응답이 순수 JSON({...}로 시작)이면 바로
    // 파싱하고, "콜백함수명(...)" 형태(JSONP)로 감싸져 오면 첫 '('와 마지막 ')' 사이만
    // 잘라내서 파싱한다. 이 처리가 빠지면 JSONP 응답일 때 파싱이 조용히 실패해서 결과가
    // 통째로 비어버린다.
    private fun parseNaverAC(raw: String): JSONObject? {
        val text = raw.trim()
        if (text.startsWith("{")) {
            return try { JSONObject(text) } catch (e: Exception) { null }
        }
        val start = text.indexOf('(')
        val end = text.lastIndexOf(')')
        if (start != -1 && end != -1 && end > start) {
            val inner = text.substring(start + 1, end)
            return try { JSONObject(inner) } catch (e: Exception) { null }
        }
        return null
    }
}
