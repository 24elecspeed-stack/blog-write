package com.keyword.research

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

// 원본 딸깍의 reverseGeocodeToDistrict(ddalkkak-extracted/photo-writer/sidepanel.js:672)를
// 그대로 이식했다(사용자 지적 - "딸깍에 있는거 그대로 가져와서 구동만 나오게 하면 되는데" -
// 안드로이드 기본 Geocoder로 직접 다시 짜려다 "서울특별시 연남동"처럼 구가 빠지는 버그를
// 만들었던 전례가 있다). GPS 좌표를 OpenStreetMap Nominatim reverse geocoding API에 그대로
// 보내는 원본과 동일한 방식 - API 키 불필요.
//
// 원본은 city(시/도)+district(구)+neighbourhood(동)를 전부 합치지만, 이 앱은 "서울/경기처럼
// 너무 넓은 단위는 가져오지 말라"는 사용자 요청에 따라 city는 빼고 district+neighbourhood만
// 쓴다. 또한 "화곡1동/화곡2동이 아니라 화곡동으로 통일"해 달라는 요청에 맞춰 동 이름 끝에
// 붙는 숫자를 지운다(원본에는 없는, 사용자가 추가로 요청한 처리).
object NominatimReverseGeocodeApi {

    private val dongSuffixDigits = Regex("\\d+(?=(동|읍|면)(\\s|$))")

    fun reverseGeocodeGuDong(lat: Double, lng: Double): String? {
        val url = URL(
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&zoom=16&accept-language=ko"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // Nominatim 사용 정책상 식별 가능한 User-Agent가 필요하다.
            setRequestProperty("User-Agent", "BlogTouch-Android/1.0")
            setRequestProperty("Accept-Language", "ko")
            connectTimeout = 10000
            readTimeout = 10000
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val addr = JSONObject(body).optJSONObject("address") ?: return null
            val district = firstNonBlank(addr, "borough", "city_district", "county") ?: return null
            val neighbourhood = firstNonBlank(addr, "suburb", "neighbourhood", "village", "town", "quarter") ?: return null
            "$district ${dongSuffixDigits.replace(neighbourhood, "")}"
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String? =
        keys.map { obj.optString(it) }.firstOrNull { it.isNotBlank() }

    // 같은 장소(현장)에서 찍은 사진이 여러 장이면 좌표가 거의 같으므로, 소수점 3자리(약 100m)
    // 단위로 묶어 중복 조회를 피한다 - Nominatim 무료 API는 초당 1회 요청 제한 정책이 있다.
    fun coordKey(lat: Double, lng: Double): String = "%.3f,%.3f".format(Locale.US, lat, lng)
}
