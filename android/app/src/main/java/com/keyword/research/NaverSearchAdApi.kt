package com.keyword.research

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

// 네이버 검색광고 API(키워드도구) 연동. 서명 방식은 Chrome 확장프로그램 background.js의
// generateSignature(timestamp, method, uri, secretKey) 구현을 그대로 이식한 것 (직접 읽고 확인함):
// message = "{timestamp}.{method}.{uri}" -> HMAC-SHA256(secretKey) -> Base64.
object NaverSearchAdApi {

    data class RelatedKeyword(
        val keyword: String,
        val monthlyPcQc: String,
        val monthlyMobileQc: String,
        val compIdx: String
    )

    class ApiException(message: String) : Exception(message)

    private const val HOST = "https://api.naver.com"
    private const val URI = "/keywordstool"

    fun fetchRelatedKeywords(keys: ApiKeyStore.Keys, hintKeyword: String): List<RelatedKeyword> {
        if (!keys.hasSearchAdKeys) throw ApiException("네이버 검색광고 API 키(API Key/Secret Key/CUSTOMER_ID)를 설정에 입력해주세요.")

        // 네이버 검색광고 API는 hintKeywords에 공백이 있으면 400(code 11001)을 반환한다.
        // 확장프로그램 background.js도 이 API로 보내기 직전에만 공백을 제거한다 (블로그 검색 쪽은 원문 유지).
        val sanitizedKeyword = hintKeyword.replace(Regex("\\s+"), "")
        val timestamp = System.currentTimeMillis().toString()
        val signature = generateSignature(timestamp, "GET", URI, keys.searchAdSecretKey)
        val query = "hintKeywords=${URLEncoder.encode(sanitizedKeyword, "UTF-8")}&showDetail=1"
        val url = URL("$HOST$URI?$query")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-Timestamp", timestamp)
            setRequestProperty("X-API-KEY", keys.searchAdApiKey)
            setRequestProperty("X-Customer", keys.searchAdCustomerId)
            setRequestProperty("X-Signature", signature)
            connectTimeout = 15000
            readTimeout = 15000
        }

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw ApiException("검색광고 API 오류($code): $body")

            val json = JSONObject(body)
            val list = json.optJSONArray("keywordList") ?: return emptyList()
            return (0 until list.length()).map { i ->
                val item = list.getJSONObject(i)
                RelatedKeyword(
                    keyword = item.optString("relKeyword"),
                    monthlyPcQc = item.optString("monthlyPcQcCnt"),
                    monthlyMobileQc = item.optString("monthlyMobileQcCnt"),
                    compIdx = item.optString("compIdx")
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun generateSignature(timestamp: String, method: String, uri: String, secretKey: String): String {
        val message = "$timestamp.$method.$uri"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }
}
