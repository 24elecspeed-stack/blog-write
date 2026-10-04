package com.keyword.research

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// 전화번호 6개 고정 블로그(BlogProfile enum)에 더해, 사용자가 화면에서 직접 블로그를 추가할 수
// 있게 "custom" 프로필을 SharedPreferences에 JSON으로 저장한다. 두 종류를 합쳐 하나의
// BlogProfileEntry 목록으로 다뤄서, 화면(블로그 선택 스피너/관리 다이얼로그)과 PromptBuilder가
// 고정 블로그인지 추가한 블로그인지 신경 쓰지 않고 그대로 쓸 수 있게 한다.
data class BlogProfileEntry(
    val id: Int,
    val phone: String,
    val titleInstruction: String,
    val voiceHint: String,
    val openingStyle: String,
    val custom: Boolean,
    // 사진 워터마크용 블로그 아이디. 고정 블로그(P1~P6)는 기본값이 BlogProfile.blogId이지만
    // 사용자가 화면에서 바꾸거나 완전히 지울 수 있다(빈 값 = 그 블로그는 워터마크를 안 새긴다).
    val blogId: String
)

object BlogProfileStore {
    private const val PREFS = "blog_profile_phones"
    private const val KEY_CUSTOM = "custom_profiles"
    private const val KEY_NEXT_ID = "next_custom_id"
    private const val FIRST_CUSTOM_ID = 7

    fun getPhone(context: Context, profile: BlogProfile): String =
        prefs(context).getString(phoneKey(profile), null) ?: profile.phone

    fun setPhone(context: Context, profile: BlogProfile, phone: String) {
        prefs(context).edit().putString(phoneKey(profile), phone).apply()
    }

    // getString(key, null)은 저장된 값이 빈 문자열이면 그 빈 문자열을 그대로 돌려주고(사용자가
    // 워터마크를 일부러 지운 상태), 키 자체가 없을 때만 null이 되어 기본값(blogId)으로 대체된다 -
    // "지운 것"과 "아직 안 건드린 것"을 구분하기 위함이다.
    fun getBlogId(context: Context, profile: BlogProfile): String =
        prefs(context).getString(blogIdKey(profile), null) ?: profile.blogId

    fun setBlogId(context: Context, profile: BlogProfile, blogId: String) {
        prefs(context).edit().putString(blogIdKey(profile), blogId).apply()
    }

    // 고정 블로그 6개(번호 그대로) + 추가한 블로그(번호 7부터) 순서로 합쳐서 반환.
    fun allEntries(context: Context): List<BlogProfileEntry> =
        BlogProfile.entries.map {
            BlogProfileEntry(it.number, getPhone(context, it), it.titleInstruction, it.voiceHint, it.openingStyle, false, getBlogId(context, it))
        } + customEntries(context)

    fun customEntries(context: Context): List<BlogProfileEntry> {
        val array = JSONArray(prefs(context).getString(KEY_CUSTOM, "[]") ?: "[]")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            BlogProfileEntry(
                o.getInt("id"), o.getString("phone"), o.getString("titleInstruction"),
                o.getString("voiceHint"), o.getString("openingStyle"), true, o.optString("blogId", "")
            )
        }
    }

    fun addCustom(context: Context, phone: String, titleInstruction: String, voiceHint: String, openingStyle: String, blogId: String = ""): BlogProfileEntry {
        val id = prefs(context).getInt(KEY_NEXT_ID, FIRST_CUSTOM_ID)
        val entry = BlogProfileEntry(id, phone, titleInstruction, voiceHint, openingStyle, true, blogId)
        saveCustom(context, customEntries(context) + entry)
        prefs(context).edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return entry
    }

    fun updateCustomPhone(context: Context, id: Int, phone: String) {
        saveCustom(context, customEntries(context).map { if (it.id == id) it.copy(phone = phone) else it })
    }

    fun updateCustomBlogId(context: Context, id: Int, blogId: String) {
        saveCustom(context, customEntries(context).map { if (it.id == id) it.copy(blogId = blogId) else it })
    }

    fun removeCustom(context: Context, id: Int) {
        saveCustom(context, customEntries(context).filterNot { it.id == id })
    }

    private fun saveCustom(context: Context, list: List<BlogProfileEntry>) {
        val array = JSONArray()
        list.forEach { e ->
            array.put(
                JSONObject()
                    .put("id", e.id).put("phone", e.phone)
                    .put("titleInstruction", e.titleInstruction)
                    .put("voiceHint", e.voiceHint).put("openingStyle", e.openingStyle)
                    .put("blogId", e.blogId)
            )
        }
        prefs(context).edit().putString(KEY_CUSTOM, array.toString()).apply()
    }

    private fun phoneKey(profile: BlogProfile) = "phone_${profile.number}"
    private fun blogIdKey(profile: BlogProfile) = "blogid_${profile.number}"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
