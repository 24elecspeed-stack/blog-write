package com.keyword.research

import android.content.Context

// 네이버/Gemini API 키 저장소. ElectricBlogClipboard의 TitleEngine.TitleHistory와 같은 패턴:
// SharedPreferences에 평문으로 저장 (그 프로젝트도 동일한 수준의 보관 방식을 쓰고 있어 일관성 유지).
object ApiKeyStore {
    private const val PREFS = "naver_api_keys"

    private const val KEY_SEARCHAD_API_KEY = "searchad_api_key"
    private const val KEY_SEARCHAD_SECRET_KEY = "searchad_secret_key"
    private const val KEY_SEARCHAD_CUSTOMER_ID = "searchad_customer_id"
    private const val KEY_OPENAPI_CLIENT_ID = "openapi_client_id"
    private const val KEY_OPENAPI_CLIENT_SECRET = "openapi_client_secret"
    private const val KEY_GEMINI_API_KEYS = "gemini_api_keys" // 줄바꿈으로 구분된 여러 키
    private const val KEY_LAST_GEMINI_KEY_INDEX = "last_gemini_key_index"

    data class Keys(
        val searchAdApiKey: String,
        val searchAdSecretKey: String,
        val searchAdCustomerId: String,
        val openApiClientId: String,
        val openApiClientSecret: String,
        val geminiApiKeysText: String = "" // 설정 화면의 여러 줄 입력칸 원문 (줄바꿈 구분)
    ) {
        val hasSearchAdKeys: Boolean
            get() = searchAdApiKey.isNotBlank() && searchAdSecretKey.isNotBlank() && searchAdCustomerId.isNotBlank()
        val hasOpenApiKeys: Boolean
            get() = openApiClientId.isNotBlank() && openApiClientSecret.isNotBlank()
        val geminiApiKeys: List<String>
            get() = geminiApiKeysText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val hasGeminiKey: Boolean
            get() = geminiApiKeys.isNotEmpty()
    }

    fun load(context: Context): Keys {
        val p = prefs(context)
        return Keys(
            searchAdApiKey = p.getString(KEY_SEARCHAD_API_KEY, "").orEmpty(),
            searchAdSecretKey = p.getString(KEY_SEARCHAD_SECRET_KEY, "").orEmpty(),
            searchAdCustomerId = p.getString(KEY_SEARCHAD_CUSTOMER_ID, "").orEmpty(),
            openApiClientId = p.getString(KEY_OPENAPI_CLIENT_ID, "").orEmpty(),
            openApiClientSecret = p.getString(KEY_OPENAPI_CLIENT_SECRET, "").orEmpty(),
            geminiApiKeysText = p.getString(KEY_GEMINI_API_KEYS, "").orEmpty()
        )
    }

    fun save(context: Context, keys: Keys) {
        prefs(context).edit()
            .putString(KEY_SEARCHAD_API_KEY, keys.searchAdApiKey)
            .putString(KEY_SEARCHAD_SECRET_KEY, keys.searchAdSecretKey)
            .putString(KEY_SEARCHAD_CUSTOMER_ID, keys.searchAdCustomerId)
            .putString(KEY_OPENAPI_CLIENT_ID, keys.openApiClientId)
            .putString(KEY_OPENAPI_CLIENT_SECRET, keys.openApiClientSecret)
            .putString(KEY_GEMINI_API_KEYS, keys.geminiApiKeysText)
            .apply()
    }

    // Chrome 확장프로그램의 currentApiKeyIndex와 같은 역할: 마지막으로 성공한 키 인덱스부터
    // 다음 생성 시 순환을 시작해서, 매번 첫 키만 두들기다 할당량이 몰리는 걸 방지한다.
    fun lastGeminiKeyIndex(context: Context): Int = prefs(context).getInt(KEY_LAST_GEMINI_KEY_INDEX, 0)

    fun saveLastGeminiKeyIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_LAST_GEMINI_KEY_INDEX, index).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
