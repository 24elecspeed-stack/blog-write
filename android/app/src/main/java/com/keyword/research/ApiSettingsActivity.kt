package com.keyword.research

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class ApiSettingsActivity : AppCompatActivity() {
    private lateinit var inputSearchAdApiKey: EditText
    private lateinit var inputSearchAdSecretKey: EditText
    private lateinit var inputSearchAdCustomerId: EditText
    private lateinit var inputOpenApiClientId: EditText
    private lateinit var inputOpenApiClientSecret: EditText
    private lateinit var inputGeminiApiKey: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_settings)

        inputSearchAdApiKey = findViewById(R.id.inputSearchAdApiKey)
        inputSearchAdSecretKey = findViewById(R.id.inputSearchAdSecretKey)
        inputSearchAdCustomerId = findViewById(R.id.inputSearchAdCustomerId)
        inputOpenApiClientId = findViewById(R.id.inputOpenApiClientId)
        inputOpenApiClientSecret = findViewById(R.id.inputOpenApiClientSecret)
        inputGeminiApiKey = findViewById(R.id.inputGeminiApiKey)

        applyBottomInsetPadding()
        loadSavedKeys()
        findViewById<Button>(R.id.btnSaveKeys).setOnClickListener { saveKeys() }

        // "(키 발급)" - 각 서비스의 키/ID 발급 페이지를 브라우저로 바로 연다.
        findViewById<TextView>(R.id.linkSearchAdKey).setOnClickListener {
            openUrl("https://manage.searchad.naver.com")
        }
        findViewById<TextView>(R.id.linkOpenApiKey).setOnClickListener {
            openUrl("https://developers.naver.com/apps/#/register")
        }
        findViewById<TextView>(R.id.linkGeminiKey).setOnClickListener {
            openUrl("https://aistudio.google.com/apikey")
        }
        findViewById<TextView>(R.id.guideSearchAdKey).setOnClickListener {
            showKeyGuide(
                "네이버 검색광고 API 발급 설명서",
                SEARCH_AD_GUIDE,
                "https://manage.searchad.naver.com"
            )
        }
        findViewById<TextView>(R.id.guideOpenApiKey).setOnClickListener {
            showKeyGuide(
                "네이버 오픈API 발급 설명서",
                OPEN_API_GUIDE,
                "https://developers.naver.com/apps/#/register"
            )
        }
        findViewById<TextView>(R.id.guideGeminiKey).setOnClickListener {
            showKeyGuide(
                "Gemini API 발급 설명서",
                GEMINI_GUIDE,
                "https://aistudio.google.com/apikey"
            )
        }
        // Gemini 키는 줄바꿈으로 구분해 여러 개 넣을 수 있는 구조라, 버튼은 입력칸 끝에 새 줄을
        // 추가하고 그 줄에 커서를 놓아준다(별도 입력칸이 새로 생기는 건 아니지만 같은 효과).
        findViewById<Button>(R.id.btnAddGeminiKey).setOnClickListener {
            val current = inputGeminiApiKey.text.toString()
            val next = if (current.isBlank()) "" else if (current.endsWith("\n")) current else "$current\n"
            inputGeminiApiKey.setText(next)
            inputGeminiApiKey.requestFocus()
            inputGeminiApiKey.setSelection(inputGeminiApiKey.text.length)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "브라우저를 열 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showKeyGuide(title: String, guide: String, issueUrl: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(guide.trimIndent())
            .setNeutralButton("공식 발급 페이지") { _, _ -> openUrl(issueUrl) }
            .setPositiveButton("확인", null)
            .show()
    }

    private fun applyBottomInsetPadding() {
        val root = findViewById<android.view.View>(R.id.rootScroll)
        val basePadding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = basePadding + bars.bottom)
            insets
        }
    }

    private fun loadSavedKeys() {
        val keys = ApiKeyStore.load(this)
        inputSearchAdApiKey.setText(keys.searchAdApiKey)
        inputSearchAdSecretKey.setText(keys.searchAdSecretKey)
        inputSearchAdCustomerId.setText(keys.searchAdCustomerId)
        inputOpenApiClientId.setText(keys.openApiClientId)
        inputOpenApiClientSecret.setText(keys.openApiClientSecret)
        inputGeminiApiKey.setText(keys.geminiApiKeysText)
    }

    private fun saveKeys() {
        val keys = ApiKeyStore.Keys(
            searchAdApiKey = inputSearchAdApiKey.text.toString().trim(),
            searchAdSecretKey = inputSearchAdSecretKey.text.toString().trim(),
            searchAdCustomerId = inputSearchAdCustomerId.text.toString().trim(),
            openApiClientId = inputOpenApiClientId.text.toString().trim(),
            openApiClientSecret = inputOpenApiClientSecret.text.toString().trim(),
            geminiApiKeysText = inputGeminiApiKey.text.toString().trim()
        )
        ApiKeyStore.save(this, keys)
        Toast.makeText(this, "API 키가 저장되었습니다.", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val SEARCH_AD_GUIDE = """
            무엇에 쓰나요
            연관 키워드, PC와 모바일 검색량, 경쟁도를 가져올 때 사용합니다.

            모바일 화면에서 찾는 순서
            1. 네이버 검색광고 광고주센터에 로그인합니다.
            2. 왼쪽 위 줄 세 개 모양의 전체 메뉴를 누릅니다.
            3. 도구 항목을 펼칩니다.
            4. 목록 아래쪽의 SA API 사용 관리를 누릅니다.
            5. SA API 사용 관리 표에서 액세스라이선스 오른쪽 복사를 누릅니다.
            6. 바로 아래 비밀키 오른쪽 복사를 누릅니다.
            7. 화면 위쪽의 검색광고 Key 버튼을 누릅니다.
            8. 팝업에 나온 숫자가 CUSTOMER_ID입니다. 복사 버튼을 누릅니다.

            아직 API를 신청하지 않은 경우
            검색광고 계정을 만든 뒤 SA API 사용 관리 화면에서 API 사용 신청 또는 라이선스 발급을 먼저 진행합니다. 화면에 액세스라이선스와 비밀키가 보이면 발급된 상태입니다.

            앱에 넣는 위치
            액세스라이선스는 API Key 칸에 넣습니다.
            비밀키는 Secret Key 칸에 넣습니다.
            검색광고 Key 팝업의 숫자는 CUSTOMER_ID 칸에 넣습니다.

            확인할 점
            광고 계정의 로그인 비밀번호를 입력하는 칸이 아닙니다.
            앞뒤 공백 없이 붙여 넣고 아래 저장 버튼을 누릅니다.
            메뉴가 잘리면 브라우저를 가로로 움직이거나 데스크톱 사이트 보기로 전환합니다.
            발급 메뉴가 보이지 않으면 현재 계정의 검색광고 권한을 확인합니다.
            비밀키가 사진이나 메시지에 노출되었다면 SA API 사용 관리에서 재발급하고 새 값을 앱에 다시 저장합니다.
        """

        private val OPEN_API_GUIDE = """
            무엇에 쓰나요
            네이버 블로그의 최신 경쟁 글 제목과 요약을 가져올 때 사용합니다.

            처음 등록하는 순서
            1. 네이버 개발자 센터에 네이버 계정으로 로그인합니다.
            2. 왼쪽 위 줄 세 개 모양의 전체 메뉴를 누릅니다.
            3. Application을 펼치고 애플리케이션 등록을 누릅니다.
            4. 처음 등록한다면 약관 동의와 휴대폰 인증을 완료합니다.
            5. 애플리케이션 이름을 입력합니다. 예를 들어 키워드 리서치라고 적을 수 있습니다.
            6. 사용 API에서 검색을 추가합니다.
            7. 서비스 환경을 요구하면 Android를 선택하고 패키지 이름에 com.keyword.research를 입력합니다.
            8. 입력을 마치고 애플리케이션을 등록합니다.

            발급된 키를 다시 찾는 순서
            1. 전체 메뉴에서 Application을 펼칩니다.
            2. 내 애플리케이션 아래에 표시된 본인의 앱 이름을 누릅니다.
            3. 앱 관리 화면에서 개요 탭을 누릅니다.
            4. 애플리케이션 정보 아래의 Client ID 값을 복사합니다.
            5. Client Secret 아래의 보기 버튼을 누른 뒤 나타나는 값을 복사합니다.
            6. 검색 권한이 설정됐는지 확인하려면 위쪽 API 설정 탭을 엽니다.

            앱에 넣는 위치
            Client ID는 Client ID 칸에 넣습니다.
            Client Secret은 Client Secret 칸에 넣습니다.

            확인할 점
            네이버 검색광고 API와는 서로 다른 키입니다.
            사용 API에 검색이 빠지면 블로그 검색이 거부될 수 있습니다.
            Client ID는 개요 화면에 바로 보이지만 Client Secret은 보기 버튼을 눌러야 확인됩니다.
            Client Secret을 재발급하면 기존 값은 사용할 수 없으므로 앱에도 새 값을 저장합니다.
            Client Secret이 사진이나 메시지에 노출되면 재발급하고 새 값을 앱에 다시 저장합니다.
        """

        private val GEMINI_GUIDE = """
            무엇에 쓰나요
            앱 안에서 요청문을 바탕으로 블로그 글 초안을 생성할 때 사용합니다.

            모바일 화면에서 찾는 순서
            1. Google AI Studio에 Google 계정으로 로그인합니다.
            2. 처음 이용한다면 이용약관에 동의합니다.
            3. 왼쪽 위 줄 세 개 모양의 메뉴를 누르고 API 키 화면을 엽니다.
            4. Default Gemini Project 같은 프로젝트 카드에 Gemini API Key가 이미 보이면 키 오른쪽의 겹친 네모 모양 복사 아이콘을 누릅니다.
            5. 카드에 보이는 점 세 개와 일부 글자는 키의 일부만 표시한 것이므로 직접 옮겨 적지 않습니다.

            새 키를 만드는 순서
            1. API 키 화면 오른쪽 위의 API 키 만들기를 누릅니다.
            2. 기존 프로젝트를 선택하거나 새 프로젝트를 만든 뒤 키 생성을 완료합니다.
            3. 새로 생긴 키 카드에서 겹친 네모 모양 복사 아이콘을 누릅니다.
            4. 키가 목록에 보이지 않으면 화면 위의 프로젝트 필터가 모든 프로젝트인지 확인합니다.

            앱에 넣는 위치
            복사한 값을 Gemini API Key 칸에 한 줄로 붙여 넣습니다.
            키가 여러 개라면 키 추가를 누르고 한 줄에 하나씩 넣습니다.
            입력을 마치면 아래 저장 버튼을 누릅니다.

            확인할 점
            무료 사용량과 요청 제한은 Google 정책과 프로젝트 상태에 따라 달라질 수 있습니다.
            키를 메신저, 블로그, 화면 캡처에 공개하지 마세요.
            결제 설정은 유료 등급을 사용할 때 필요한 항목이며 무료 등급 표시가 있으면 무료 한도 안에서 이용할 수 있습니다.
            생성 권한 오류가 나면 AI Studio에서 프로젝트 권한과 Gemini API 사용 상태를 확인합니다.
        """

    }
}
