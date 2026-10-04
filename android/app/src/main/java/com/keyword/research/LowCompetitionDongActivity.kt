package com.keyword.research

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

// "동별 경쟁 낮은 곳 찾기" - 증상/키워드와 구·동을 조합해서(예: "화곡동차단기내려감") 네이버 검색광고
// API로 검색량을 하나씩 확인하고, 검색량이 가장 낮은(=경쟁이 낮은) 곳을 찾아준다. 동 수가 많아서
// 전부 훑으면 API 호출이 너무 많아지므로, ① 구 하나를 고르면 그 구 안 동들만, ② 전체 구 단위로
// 대표 비교(구 이름 자체로 검색) 두 가지 범위 중 골라서 실행한다. 서울/경기는 항상 따로 비교한다
// (한 번에 섞어서 비교하지 않는다). 다른 화면들과 같은 스타일이 제대로 적용되도록
// AppCompatActivity를 쓴다(일반 Activity로 하면 Material3 버튼 스타일이 깨진다).
class LowCompetitionDongActivity : AppCompatActivity() {
    private lateinit var inputKeyword: EditText
    private lateinit var statusText: TextView
    private lateinit var btnRegionSeoul: Button
    private lateinit var btnRegionGyeonggi: Button
    private var selectedRegion: Region = Region.SEOUL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_low_competition_dong)

        inputKeyword = findViewById(R.id.inputKeyword)
        statusText = findViewById(R.id.statusText)
        btnRegionSeoul = findViewById(R.id.btnRegionSeoul)
        btnRegionGyeonggi = findViewById(R.id.btnRegionGyeonggi)

        applyBottomInsetPadding()
        updateRegionButtons()
        btnRegionSeoul.setOnClickListener { selectedRegion = Region.SEOUL; updateRegionButtons() }
        btnRegionGyeonggi.setOnClickListener { selectedRegion = Region.GYEONGGI; updateRegionButtons() }

        findViewById<Button>(R.id.btnScanDongsInDistrict).setOnClickListener {
            withValidKeywordAndKeys { keys, symptom -> showDistrictPickerForDongScan(keys, symptom) }
        }
        findViewById<Button>(R.id.btnScanDistricts).setOnClickListener {
            withValidKeywordAndKeys { keys, symptom -> scanDistricts(keys, symptom) }
        }
    }

    private fun applyBottomInsetPadding() {
        val root = findViewById<View>(R.id.rootScroll)
        val basePadding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = basePadding + bars.bottom)
            insets
        }
    }

    private fun updateRegionButtons() {
        val selectedBg = android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
        val unselectedBg = android.content.res.ColorStateList.valueOf(getColor(R.color.surface))
        fun style(button: Button, selected: Boolean) {
            button.backgroundTintList = if (selected) selectedBg else unselectedBg
            button.setTextColor(if (selected) getColor(android.R.color.white) else getColor(R.color.primary))
        }
        style(btnRegionSeoul, selectedRegion == Region.SEOUL)
        style(btnRegionGyeonggi, selectedRegion == Region.GYEONGGI)
    }

    private fun withValidKeywordAndKeys(action: (ApiKeyStore.Keys, String) -> Unit) {
        val symptom = inputKeyword.text.toString().trim()
        if (symptom.isBlank()) {
            Toast.makeText(this, "먼저 증상/키워드를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val keys = ApiKeyStore.load(this)
        if (!keys.hasSearchAdKeys) {
            Toast.makeText(this, "네이버 검색광고 API 키를 API 키 설정에 입력해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        action(keys, symptom)
    }

    private fun showDistrictPickerForDongScan(keys: ApiKeyStore.Keys, symptom: String) {
        val groups = PlacePicker.districtGroups(selectedRegion)
        val labels = groups.map { "${it.district} (${it.dongs.size}개 동)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("구 선택 (${selectedRegion.label})")
            .setItems(labels) { _, which -> scanDongsInDistrict(keys, groups[which], symptom) }
            .show()
    }

    private data class ScanResult(val volume: Int, val hasData: Boolean)

    // hintKeyword로 검색한 결과 목록에서 정확히 그 조합 자체의 검색량을 찾는다 - 못 찾으면(=너무
    // 적어서 네이버가 별도로 집계하지 않는 경우) 검색량이 사실상 매우 낮다는 뜻으로 취급한다.
    private fun lookupExactVolume(keys: ApiKeyStore.Keys, combined: String): ScanResult {
        val sanitized = combined.replace(Regex("\\s+"), "")
        val results = NaverSearchAdApi.fetchRelatedKeywords(keys, combined)
        val match = results.firstOrNull { it.keyword.replace(Regex("\\s+"), "") == sanitized }
        return if (match != null) ScanResult(KeywordStage.totalVolume(match), true) else ScanResult(0, false)
    }

    private fun scanDongsInDistrict(keys: ApiKeyStore.Keys, group: DistrictGroup, symptom: String) {
        statusText.text = "🔎 ${group.district} 동 ${group.dongs.size}개 비교 중..."
        Thread {
            val results = ArrayList<Pair<String, ScanResult>>()
            var errorCount = 0
            group.dongs.forEach { dong ->
                try {
                    results.add(dong to lookupExactVolume(keys, "${group.district}$dong$symptom"))
                } catch (e: Exception) {
                    errorCount++
                }
            }
            val sorted = results.sortedBy { it.second.volume }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (sorted.isEmpty()) {
                    statusText.text = "✅ ${group.district} 비교 완료" + (if (errorCount > 0) " (실패 $errorCount 건 제외)" else "")
                    Toast.makeText(this, "비교할 수 있는 결과가 없습니다.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val lowest = sorted.first()
                val highest = sorted.last()
                statusText.text = "✅ ${group.district} 비교 완료 · 최저 ${lowest.first}(${lowest.second.volume}) · 최고 ${highest.first}(${highest.second.volume})" +
                    (if (errorCount > 0) " (실패 $errorCount 건 제외)" else "")
                // 목록 맨 위(최저)와 맨 아래(최고)에 표시를 붙여서 스크롤 없이도 바로 눈에 띄게 한다.
                val labels = sorted.mapIndexed { index, (dong, r) ->
                    val tag = when (index) { 0 -> "🔽최저  "; sorted.lastIndex -> "🔼최고  "; else -> "" }
                    if (r.hasData) "$tag$dong  (검색량 ${r.volume})" else "$tag$dong  (검색량 매우 적음)"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("${group.district} - 검색량 낮은 순")
                    .setItems(labels) { _, which ->
                        SearchState.pickedDistrict = group.district
                        SearchState.pickedDong = sorted[which].first
                        Toast.makeText(this, "메인 화면 지역/동에 채워집니다.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .show()
            }
        }.start()
    }

    private fun scanDistricts(keys: ApiKeyStore.Keys, symptom: String) {
        val groups = PlacePicker.districtGroups(selectedRegion)
        statusText.text = "🔎 ${selectedRegion.label} 구 ${groups.size}개 비교 중..."
        Thread {
            val results = ArrayList<Pair<String, ScanResult>>()
            var errorCount = 0
            groups.forEach { group ->
                try {
                    results.add(group.district to lookupExactVolume(keys, "${group.district}$symptom"))
                } catch (e: Exception) {
                    errorCount++
                }
            }
            val sorted = results.sortedBy { it.second.volume }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (sorted.isEmpty()) {
                    statusText.text = "✅ ${selectedRegion.label} 구 비교 완료" + (if (errorCount > 0) " (실패 $errorCount 건 제외)" else "")
                    Toast.makeText(this, "비교할 수 있는 결과가 없습니다.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val lowest = sorted.first()
                val highest = sorted.last()
                statusText.text = "✅ ${selectedRegion.label} 구 비교 완료 · 최저 ${lowest.first}(${lowest.second.volume}) · 최고 ${highest.first}(${highest.second.volume})" +
                    (if (errorCount > 0) " (실패 $errorCount 건 제외)" else "")
                val labels = sorted.mapIndexed { index, (district, r) ->
                    val tag = when (index) { 0 -> "🔽최저  "; sorted.lastIndex -> "🔼최고  "; else -> "" }
                    if (r.hasData) "$tag$district  (검색량 ${r.volume})" else "$tag$district  (검색량 매우 적음)"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("${selectedRegion.label} 구 - 검색량 낮은 순")
                    .setItems(labels) { _, which ->
                        SearchState.pickedDistrict = sorted[which].first
                        SearchState.pickedDong = null
                        Toast.makeText(this, "메인 화면 지역에 채워집니다.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .show()
            }
        }.start()
    }
}
