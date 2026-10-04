package com.keyword.research

import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 기기 1대에만 묶이는 라이선스 키.
//
// 앱은 매번(설치 직후 인증할 때도, 이후 실행할 때마다도) 먼저 GitHub의 공개 파일 하나를 "중계
// 장치"로 거친다 - 이 파일이 실제 라이선스 서버(24elecspeed.com, license-server 폴더 참고)
// 주소를 알려주고, 서비스 전체를 켜고 끄는 스위치 역할도 한다. GitHub 파일을 못 읽으면(오프라인
// 포함) 그 자체로 실행을 막는다 - "언제나 온라인이어야 실행된다"는 요구사항이다.
//
// 실제 키-기기 등록(쓰기)이 필요한 부분은 GitHub가 아니라 24elecspeed.com의 PHP+MySQL 서버가
// 전담한다. 앱 안에는 쓰기 권한이 있는 비밀(토큰)을 절대 넣지 않는다 - GitHub 파일도 공개
// 읽기 전용이라 안전하다.
object LicenseGate {
    private const val PREFS = "license_gate"
    private const val KEY_ACTIVATED = "activated"
    private const val KEY_LICENSE_KEY = "license_key"

    // ↓↓↓ GitHub에 공개 저장소를 하나 만들고, license-status.json 파일을 올린 뒤 그 raw 링크로
    // 바꿔 넣는다(license-server/license-status.json 예시 파일 참고).
    private const val RELAY_URL = "https://raw.githubusercontent.com/여기에_깃헙아이디/여기에_저장소이름/main/license-status.json"

    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    fun isActivated(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVATED, false)

    fun savedKey(context: Context): String = prefs(context).getString(KEY_LICENSE_KEY, "").orEmpty()

    fun clearActivation(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // 서버에 활성화를 요청한다. 성공하면 null, 실패하면 사용자에게 보여줄 오류 메시지를 돌려준다.
    fun activate(context: Context, key: String): String? {
        val relay = fetchRelay() ?: return "온라인 연결을 확인할 수 없습니다. 인터넷 연결 상태를 확인해주세요."
        if (!relay.enabled) return "지금은 서비스가 일시 중지되어 있습니다."
        return try {
            val result = post(context, relay.apiBase, "activate.php", key)
            if (result.getBoolean("ok")) {
                prefs(context).edit()
                    .putBoolean(KEY_ACTIVATED, true)
                    .putString(KEY_LICENSE_KEY, key)
                    .apply()
                null
            } else {
                result.optString("error", "알 수 없는 오류입니다.")
            }
        } catch (e: Exception) {
            "서버에 연결할 수 없습니다: ${e.message}"
        }
    }

    // 앱을 열 때마다 다시 확인한다. GitHub 중계 파일을 못 읽거나(오프라인 등), 서버가 "취소됨"/
    // "다른 기기"라고 답하면 잠근다 - 항상 온라인 연결이 있어야 실행되는 정책이라 네트워크 오류도
    // 통과시키지 않는다(예전에는 오프라인이면 봐줬지만, 이제는 막는다).
    fun verifyOnline(context: Context): Boolean {
        val key = savedKey(context)
        if (key.isBlank()) return false
        val relay = fetchRelay() ?: return false
        if (!relay.enabled) return false
        return try {
            val result = post(context, relay.apiBase, "check.php", key)
            result.optBoolean("ok", false)
        } catch (e: Exception) {
            false
        }
    }

    private data class RelayConfig(val enabled: Boolean, val apiBase: String)

    private fun fetchRelay(): RelayConfig? {
        return try {
            val url = URL(RELAY_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(text)
                RelayConfig(
                    enabled = json.optBoolean("service_enabled", true),
                    apiBase = json.getString("api_base")
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun post(context: Context, apiBase: String, endpoint: String, key: String): JSONObject {
        val url = URL("$apiBase/$endpoint")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
        }
        val body = "key=${URLEncoder.encode(key, "UTF-8")}&device_id=${URLEncoder.encode(deviceId(context), "UTF-8")}"
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
