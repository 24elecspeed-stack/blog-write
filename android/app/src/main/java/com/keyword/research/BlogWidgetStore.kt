package com.keyword.research

import android.content.Context

// "광고 위젯 만들기" 화면용 - 블로그(전화번호)별로 네이버 블로그 "위젯 직접등록"에 넣을 배너
// 이미지 URL과 연결 URL을 저장한다(사용자 요청 - 본문에 전화번호를 직접 쓰는 대신 블로그
// 위젯으로 빼서, 6개 블로그가 같은 URL로 연결되면 오히려 계정들이 묶여 있다는 단서가 되니까
// 블로그마다 다른 URL을 쓰기로 함 - 3개는 네이버 플레이스, 3개는 카카오톡 채널). 전화번호/
// 워터마크와 같은 저장 방식(BlogProfileStore)을 그대로 따라 blogId(entry.id) 기준으로 둔다.
object BlogWidgetStore {
    private const val PREFS = "blog_widget_links"

    fun getLinkUrl(context: Context, entryId: Int): String =
        prefs(context).getString(linkKey(entryId), null).orEmpty()

    fun setLinkUrl(context: Context, entryId: Int, url: String) {
        prefs(context).edit().putString(linkKey(entryId), url).apply()
    }

    fun getImageUrl(context: Context, entryId: Int): String =
        prefs(context).getString(imageKey(entryId), null).orEmpty()

    fun setImageUrl(context: Context, entryId: Int, url: String) {
        prefs(context).edit().putString(imageKey(entryId), url).apply()
    }

    private fun linkKey(entryId: Int) = "link_$entryId"
    private fun imageKey(entryId: Int) = "image_$entryId"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
