package com.keyword.research

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

// 기기 1대당 라이선스 키 1개만 허용하는 잠금 화면. MainActivity가 시작될 때 아직 인증되지 않은
// 상태면(또는 서버가 취소했다고 확인되면) 이 화면으로 보낸다.
class LicenseActivity : AppCompatActivity() {
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_license)
        input = findViewById(R.id.inputLicenseKey)
        status = findViewById(R.id.licenseStatus)
        button = findViewById(R.id.btnActivate)
        applyTopInsetPadding()

        button.setOnClickListener { activate() }
    }

    private fun activate() {
        val key = input.text.toString().trim().uppercase()
        if (key.isBlank()) {
            Toast.makeText(this, "라이선스 키를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        button.isEnabled = false
        status.text = "확인하는 중..."
        Thread {
            val error = LicenseGate.activate(this, key)
            runOnUiThread {
                button.isEnabled = true
                if (error == null) {
                    Toast.makeText(this, "인증되었습니다.", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                } else {
                    status.text = "❌ $error"
                }
            }
        }.start()
    }

    private fun applyTopInsetPadding() {
        val root = findViewById<android.view.View>(R.id.rootScroll)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            insets
        }
    }
}
