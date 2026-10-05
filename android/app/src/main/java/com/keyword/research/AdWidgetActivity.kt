package com.keyword.research

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Html
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

// "광고 위젯 만들기" - 본문에 전화번호를 직접 쓰지 않고, 네이버 블로그 "위젯 직접등록"에
// 걸어두는 고정 배너(이미지+링크)를 블로그별로 만든다(사용자 요청). 전화번호 텍스트가 본문에
// 없으니 어뷰징(스팸 텍스트) 탐지를 피하고, 여러 블로그가 똑같은 링크로 연결되면 그 자체가
// "같은 운영자"라는 단서가 되므로(사용자와 상의해 확인한 내용) 블로그마다 다른 연결 URL을
// 쓴다 - 예: 6개 중 3개는 네이버 플레이스, 3개는 카카오톡 채널로 서로 다르게 연결.
class AdWidgetActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ad_widget)
        applyBottomInsetPadding()
        buildRows()
        findViewById<Button>(R.id.btnSaveAdWidgets).setOnClickListener { saveAll() }
    }

    private val rowFields = mutableListOf<Triple<BlogProfileEntry, EditText, EditText>>()

    private fun buildRows() {
        val container = findViewById<LinearLayout>(R.id.adWidgetRows)
        container.removeAllViews()
        rowFields.clear()
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        BlogProfileStore.allEntries(this).forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.studio_field)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = pad }
            }
            card.addView(TextView(this).apply {
                text = "${entry.id}번 블로그 (${entry.phone.ifBlank { "전화번호 미입력" }})"
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            val imageField = EditText(this).apply {
                hint = "배너 이미지 URL (네이버 블로그에 먼저 올려서 이미지 주소를 복사)"
                textSize = 12f
                setText(BlogWidgetStore.getImageUrl(this@AdWidgetActivity, entry.id))
            }
            val linkField = EditText(this).apply {
                hint = "연결 URL (네이버 플레이스 / 카카오톡 채널 등 - 블로그마다 다르게)"
                textSize = 12f
                setText(BlogWidgetStore.getLinkUrl(this@AdWidgetActivity, entry.id))
            }
            card.addView(TextView(this).apply { text = "배너 이미지 URL"; textSize = 11f; setPadding(0, pad / 2, 0, 0) })
            card.addView(imageField)
            card.addView(TextView(this).apply { text = "연결 URL"; textSize = 11f; setPadding(0, pad / 2, 0, 0) })
            card.addView(linkField)
            card.addView(Button(this).apply {
                text = "위젯 코드 복사"; isAllCaps = false; textSize = 12f
                setPadding(0, pad / 2, 0, 0)
                setOnClickListener { copyWidgetCode(entry, imageField.text.toString().trim(), linkField.text.toString().trim()) }
            })
            container.addView(card)
            rowFields += Triple(entry, imageField, linkField)
        }
    }

    private fun copyWidgetCode(entry: BlogProfileEntry, imageUrl: String, linkUrl: String) {
        if (imageUrl.isBlank() || linkUrl.isBlank()) {
            Toast.makeText(this, "배너 이미지 URL과 연결 URL을 둘 다 입력해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        BlogWidgetStore.setImageUrl(this, entry.id, imageUrl)
        BlogWidgetStore.setLinkUrl(this, entry.id, linkUrl)
        val html = "<a href=\"${Html.escapeHtml(linkUrl)}\" target=\"_blank\"><img src=\"${Html.escapeHtml(imageUrl)}\" style=\"width:100%;\" /></a>"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ad-widget-code", html))
        Toast.makeText(this, "${entry.id}번 블로그 위젯 코드를 복사했습니다 · 네이버 블로그 관리 > 꾸미기 설정 > 레이아웃·위젯 설정 > 위젯 직접등록에 붙여넣으세요.", Toast.LENGTH_LONG).show()
    }

    private fun saveAll() {
        rowFields.forEach { (entry, imageField, linkField) ->
            BlogWidgetStore.setImageUrl(this, entry.id, imageField.text.toString().trim())
            BlogWidgetStore.setLinkUrl(this, entry.id, linkField.text.toString().trim())
        }
        Toast.makeText(this, "전부 저장했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun applyBottomInsetPadding() {
        val root = findViewById<ScrollView>(R.id.adWidgetRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }
    }
}
