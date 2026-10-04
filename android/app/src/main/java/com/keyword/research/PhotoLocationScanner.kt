package com.keyword.research

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

// "위치로 사진 폴더 만들기" - 갤러리 사진의 GPS(EXIF) 위치를 읽어 구/동으로 묶고, 그 안을 다시
// 구/동/번지수 단위로 더 잘게 나눈다. 안드로이드 기본 Geocoder(키·가입 불필요)만 사용한다.
object PhotoLocationScanner {

    data class PhotoInfo(val uri: Uri, val dateTakenMillis: Long, val lat: Double?, val lng: Double?)
    // label: "구 동 번지수" 단위(예: "마포구 공덕동 2-545") - 번지수까지 못 구했으면 동 단위 label과 같다.
    data class AddressGroup(val label: String, val photos: List<PhotoInfo>)
    // label: "구 동" 단위(예: "마포구 공덕동") - 기존 목록 선택 단계에서 쓰는 단위, 그대로 유지.
    data class DistrictGroup(val label: String, val photos: List<PhotoInfo>, val addressGroups: List<AddressGroup>)
    // 화면에 "왜 안 되는지" 보여주기 위한 진단 정보 - 사진이 아예 없는 것과, GPS가 없는 것과,
    // GPS는 있는데 주소 변환이 실패하는 것은 원인이 전혀 다르다.
    data class ScanDiagnostics(val totalScanned: Int, val withGps: Int, val geocoded: Int, val lastError: String?)

    // 사용자가 실제로 쓰는 사진 폴더는 "카메라"(DCIM/Camera)뿐이라, 갤러리 전체가 아니라 이 폴더만
    // 스캔한다 - 다른 앱이 저장한 사진(다운로드, 카카오톡 등)이 섞여 나오는 것도 막는다.
    fun scanRecentPhotos(context: Context, limit: Int = 1500): List<PhotoInfo> {
        val results = mutableListOf<PhotoInfo>()
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        // sortOrder 문자열에 "LIMIT n"을 직접 붙이는 방식은 일부 기기(제조사 MediaProvider)에서
        // "Invalid token LIMIT" 오류를 낸다(실기기에서 확인됨) - LIMIT은 빼고, 커서를 도는 동안
        // 직접 개수를 세어 끊는다.
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?",
            arrayOf("Camera"),
            "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (cursor.moveToNext() && results.size < limit) {
                val id = cursor.getLong(idCol)
                val dateTaken = cursor.getLong(dateCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                val latLng = readGps(context, uri)
                results += PhotoInfo(uri, dateTaken, latLng?.first, latLng?.second)
            }
        }
        return results
    }

    private fun readGps(context: Context, uri: Uri): Pair<Double, Double>? {
        return try {
            // Android 10+에서는 ACCESS_MEDIA_LOCATION 권한 + setRequireOriginal로 감싼 URI라야
            // EXIF의 GPS 값이 리다크션(삭제) 없이 그대로 나온다.
            val originalUri = try { MediaStore.setRequireOriginal(uri) } catch (e: Exception) { uri }
            context.contentResolver.openInputStream(originalUri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) latLong[0].toDouble() to latLong[1].toDouble() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // 위치정보가 있는 사진은 지오코딩해서 구/동/번지수로 묶고, 위치정보가 없는 사진은 같은 날짜(연중
    // 일수 기준)에 찍힌 위치정보 있는 사진이 있으면 그 동 그룹에 같이 묶는다("답사 같은 날 찍었지만
    // 실내라 GPS가 안 찍힌 사진"을 구제하기 위함, 이 경우 번지수까지는 알 수 없어 동 단위로만 묶인다).
    fun groupByDistrict(context: Context, photos: List<PhotoInfo>): Pair<List<DistrictGroup>, ScanDiagnostics> {
        val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.KOREA) else null
        val dongByUri = HashMap<Uri, String>()
        val addressByUri = HashMap<Uri, String>()
        val dayToGeo = HashMap<String, GeoAddress>()
        val withGps = photos.count { it.lat != null && it.lng != null }

        // 같은 장소(현장)에서 찍은 사진은 좌표가 거의 같으므로, 소수점 3자리(약 100m) 단위로 묶어
        // 서로 다른 장소끼리만 변환한다(한 현장에서 사진을 수십 장 찍어도 그 현장은 한 번만 조회).
        // 서로 다른 장소가 여러 곳이면(여러 현장을 답사한 경우) 그 조회들을 동시에(병렬로) 실행해서,
        // 순서대로 하나씩 기다릴 때보다 전체 대기 시간을 크게 줄인다.
        val distinctCoords = LinkedHashMap<String, Pair<Double, Double>>()
        photos.forEach { photo ->
            val lat = photo.lat
            val lng = photo.lng
            if (lat != null && lng != null) {
                distinctCoords.putIfAbsent("%.3f,%.3f".format(Locale.US, lat, lng), lat to lng)
            }
        }
        val geoByCoord = ConcurrentHashMap<String, GeoAddress?>()
        // 변환이 실패해도 그냥 null 처리하고 넘어가면 "0장 실패"라고만 보이고 왜 실패했는지 알 수가
        // 없다. 실제 예외 메시지를 하나 잡아뒀다가 화면에 그대로 보여준다.
        val lastError = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(minOf(6, maxOf(1, distinctCoords.size)))
        try {
            val tasks = distinctCoords.map { (coordKey, latLng) ->
                pool.submit {
                    val (lat, lng) = latLng
                    val geo = try {
                        geocoder?.let { reverseGeocodeAndroid(it, lat, lng) }
                    } catch (e: Exception) {
                        lastError.set(e.message ?: e.toString())
                        null
                    }
                    // ConcurrentHashMap은 값에 null을 넣으면 예외를 던진다(HashMap과 다름) - 실패한
                    // 경우(geo == null)는 그냥 map에 안 넣는다. 나중에 get()이 없으면 null이 돌아와
                    // "실패"로 똑같이 처리되니 동작은 같다.
                    if (geo != null) geoByCoord[coordKey] = geo
                }
            }
            tasks.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        photos.forEach { photo ->
            val lat = photo.lat
            val lng = photo.lng
            if (lat != null && lng != null) {
                val geo = geoByCoord["%.3f,%.3f".format(Locale.US, lat, lng)]
                if (geo != null) {
                    dongByUri[photo.uri] = geo.dong
                    addressByUri[photo.uri] = geo.address
                    dayToGeo.putIfAbsent(dayKey(photo.dateTakenMillis), geo)
                }
            }
        }
        photos.forEach { photo ->
            if (!dongByUri.containsKey(photo.uri)) {
                dayToGeo[dayKey(photo.dateTakenMillis)]?.let { geo ->
                    dongByUri[photo.uri] = geo.dong
                    // GPS 없이 같은 날짜로만 묶인 사진은 정확한 번지수를 알 수 없으니 동 단위까지만 쓴다.
                    addressByUri[photo.uri] = geo.dong
                }
            }
        }

        val groups = photos.filter { dongByUri.containsKey(it.uri) }
            .groupBy { dongByUri.getValue(it.uri) }
            .map { (dong, list) ->
                val sorted = list.sortedBy { it.dateTakenMillis }
                val addressGroups = sorted.groupBy { addressByUri[it.uri] ?: dong }
                    .map { (address, addressPhotos) -> AddressGroup(address, addressPhotos) }
                    .sortedByDescending { it.photos.size }
                DistrictGroup(dong, sorted, addressGroups)
            }
            .sortedByDescending { it.photos.size }
        val diagnostics = ScanDiagnostics(photos.size, withGps, dongByUri.size, lastError.get())
        return groups to diagnostics
    }

    private fun dayKey(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    // 동/읍/면으로 끝나는 한글 단어를 찾는다(예: "합정동", "구성읍", "장기면"). 구 이름은 "구"로
    // 끝나서 여기 안 걸리고, 도로명("~로", "~길")도 안 걸린다.
    private val dongPattern = Regex("[가-힣]{2,}(동|읍|면)(?![가-힣])")
    // 동/읍/면 이름 바로 뒤에 오는 번지수(예: "2-545", "산15")를 찾는다.
    private val lotPattern = Regex("^\\s*(산\\s*\\d+(-\\d+)?)")

    @Suppress("DEPRECATION")
    private fun reverseGeocodeAndroid(geocoder: Geocoder, lat: Double, lng: Double): GeoAddress? {
        return try {
            val address = geocoder.getFromLocation(lat, lng, 1)?.firstOrNull() ?: return null
            val gu = address.subAdminArea ?: address.adminArea
            // 안드로이드 기본 Geocoder는 한국 주소에서 subLocality(동)를 잘 안 채워주는 경우가
            // 많다(실사용 중 확인됨 - 구까지만 나옴). 구조화된 필드에 없으면 전체 주소 줄
            // (addressLine)에서 "동/읍/면"으로 끝나는 단어를 직접 찾아 보정한다.
            val lines = (0 until address.maxAddressLineIndex + 1).mapNotNull { address.getAddressLine(it) }
            // subLocality 필드만으로는 뒤에 오는 번지수를 알 수 없어, 번지수까지 뽑으려면 항상
            // addressLine 전체 문자열에서 동 위치를 다시 찾아야 한다.
            val lineWithDong = lines.firstNotNullOfOrNull { line ->
                dongPattern.findAll(line).lastOrNull()?.let { line to it }
            }
            val dongWord = lineWithDong?.second?.value ?: address.subLocality ?: address.thoroughfare
            val dong = listOfNotNull(gu, dongWord).joinToString(" ").ifBlank { null } ?: return null
            // 동 단어 바로 뒤에 이어지는 번지수(공백 하나 정도만 허용)를 찾아 "구 동 번지수" 형태로 만든다.
            val lot = lineWithDong?.let { (line, match) ->
                val rest = line.substring(match.range.last + 1)
                lotPattern.find(rest)?.groupValues?.get(1)?.trim()
                    ?: Regex("^\\s*(\\d+(-\\d+)?)").find(rest)?.groupValues?.get(1)
            }
            val full = if (lot != null) "$dong $lot" else dong
            GeoAddress(dong = dong, address = full)
        } catch (e: Exception) {
            null
        }
    }
}
