package com.keyword.research

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Chrome 확장프로그램 background.js의 generateAiContent / generateAiContentWithRetry를 그대로
// 이식 (직접 읽고 확인함):
// - 단건 호출: POST .../models/{model}:generateContent?key={apiKey}, body: {"contents":[{"parts":[{"text":prompt}]}]}
// - 여러 키 등록 시: 할당량 초과(429/quota/billing)면 즉시 다음 키로 전환, 서버 과부하(503/500/overloaded)면
//   같은 키로 3초 후 재시도 후 그래도 안 되면 다음 키로 전환. 마지막 성공 키 인덱스부터 다음 호출을 시작한다.
object GeminiApi {

    private const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

    class ApiException(message: String) : Exception(message)
    class QuotaExceededError(message: String) : Exception(message)
    class TemporaryApiError(message: String) : Exception(message)

    // context가 있으면 마지막 성공 키 인덱스를 이어서 사용하고, 성공 시 그 인덱스를 저장한다.
    fun generateDraftWithRetry(
        context: android.content.Context,
        apiKeys: List<String>,
        prompt: String,
        systemInstruction: String? = null,
        model: String = DEFAULT_MODEL,
        onAttempt: ((keyIndex: Int, total: Int) -> Unit)? = null
    ): String {
        if (apiKeys.isEmpty()) throw ApiException("등록된 Gemini API 키가 없습니다.")
        val startIndex = ApiKeyStore.lastGeminiKeyIndex(context).coerceIn(0, apiKeys.size - 1)
        // 전부 실패했을 때 "할당량 초과 또는 서버 과부하"라는 애매한 문구 대신, 실제로 마지막
        // 키가 어떤 이유로 실패했는지(정확한 HTTP 코드·응답 본문)를 그대로 보여주기 위해 마지막
        // 오류를 기억해둔다 - 할당량인지 과부하인지 구분이 안 되면 원인 파악이 어렵다는 문제가
        // 실사용 중 확인됐다.
        var lastError: Exception? = null

        for (offset in apiKeys.indices) {
            val index = (startIndex + offset) % apiKeys.size
            val apiKey = apiKeys[index]
            onAttempt?.invoke(index, apiKeys.size)
            try {
                val text = generateDraft(apiKey, prompt, systemInstruction, model)
                ApiKeyStore.saveLastGeminiKeyIndex(context, index)
                return text
            } catch (e: QuotaExceededError) {
                // 이 키는 할당량 초과 - 바로 다음 키로 넘어간다.
                lastError = e
                continue
            } catch (e: TemporaryApiError) {
                // 서버 과부하 - 같은 키로 한 번 더 시도(3초 대기) 후 안 되면 다음 키로.
                lastError = e
                Thread.sleep(3000)
                try {
                    val text = generateDraft(apiKey, prompt, systemInstruction, model)
                    ApiKeyStore.saveLastGeminiKeyIndex(context, index)
                    return text
                } catch (e2: Exception) {
                    lastError = e2
                    continue
                }
            }
        }
        throw ApiException("등록된 모든 API 키로 글 생성에 실패했습니다. 마지막 오류: ${lastError?.message ?: "알 수 없음"}")
    }

    // systemInstruction: "무조건 지켜야 할 규칙"(제목 첫 줄 필수, 마크다운/이모티콘 금지 등)을
    // 여기 넣으면 일반 user 프롬프트에 섞어 넣을 때보다 모델이 훨씬 안정적으로 따른다 - Gemini
    // API가 지원하는 전용 필드다. "API로 생성하면 제목이 또 빠진다"는 문제의 실제 원인: 지금까지
    // 모든 지시를 하나의 긴 user 프롬프트 뒤쪽에 욱여넣고 있어서, 모델이 앞쪽 내용(키워드/경쟁사
    // 글/스타일)에 집중하는 사이 뒤쪽 형식 지시를 놓치고 있었다.
    fun generateDraft(apiKey: String, prompt: String, systemInstruction: String? = null, model: String = DEFAULT_MODEL): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 30000
            readTimeout = 60000
        }

        val body = JSONObject().apply {
            if (!systemInstruction.isNullOrBlank()) {
                put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(
                    JSONObject().put("text", systemInstruction)
                )))
            }
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
            // Gemini 3.x는 thinkingLevel을 안 넣으면 기본값(높은 사고 예산)을 써서 블로그 글
            // 하나 생성에도 체감상 오래 걸린다 - 공식 문서(ai.google.dev/gemini-api/docs/
            // generate-content/gemini-3) 기준 "low"가 "고처리량 응용에 지연시간 최소화" 용도라고
            // 명시돼 있어서, 이런 텍스트 생성 작업엔 이 값이 맞다. thinkingBudget(구버전 필드)과
            // 같이 보내면 요청 자체가 실패하므로 절대 같이 넣지 않는다.
            put("generationConfig", JSONObject()
                .put("maxOutputTokens", 8192)
                .put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) {
                val lower = responseText.lowercase()
                if (code == 429 || lower.contains("quota") || lower.contains("billing")) {
                    throw QuotaExceededError("API 호출 실패 (할당량 문제, $code): $responseText")
                }
                if (code == 503 || code == 500 || lower.contains("overloaded")) {
                    throw TemporaryApiError("API 호출 실패 (서버 과부하, $code): $responseText")
                }
                throw ApiException("Gemini API 오류($code): $responseText")
            }

            val json = JSONObject(responseText)
            val text = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
            return text ?: throw ApiException("AI 모델로부터 유효한 텍스트 응답을 받지 못했습니다.")
        } finally {
            conn.disconnect()
        }
    }
}
