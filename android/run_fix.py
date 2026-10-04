import os, re

path = r"D:\keyword\android\app\src\main\java\com\keyword\research\CollageActivity.java"
if not os.path.exists(path):
    print("ERROR: File not found:", path)
    exit(1)

with open(path, "r", encoding="utf-8", errors="ignore") as f:
    code = f.read()

# 1. 392번 줄 깨끗하게 정리
code = re.sub(
    r'Button\s+btnPickDate\s*=\s*smallButton\([\s\S]*?\);[a-zA-Z0-9_\):;]*',
    'Button btnPickDate = smallButton("날짜", this::showDatePicker);',
    code
)
code = re.sub(
    r'Button\s+btnPickTime\s*=\s*smallButton\([\s\S]*?\);',
    'Button btnPickTime = smallButton("시간", this::showTimePicker);',
    code
)
code = re.sub(
    r'btnApplyExif\s*=\s*button\([\s\S]*?\);',
    'btnApplyExif = button("저장", true, this::applyExifToTargets);\n        btnApplyExif.setEnabled(true);',
    code
)

# 2. 필수 import 추가
imports = """import android.location.Location;
import android.content.ContentValues;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
"""
if "java.util.LinkedHashMap" not in code:
    pkg = re.search(r"package\s+com\.keyword\.research;\s*", code)
    if pkg:
        code = code[:pkg.end()] + imports + code[pkg.end():]

# 3. onActivityResult에 PICK_EXIF_BASE 분기 연결
if "handleExifBasePicked" not in code:
    target_act = "super.onActivityResult(request, result, data);"
    handler_act = """super.onActivityResult(request, result, data);
        if (request == PICK_EXIF_BASE && result == RESULT_OK && data != null) {
            handleExifBasePicked(data);
            return;
        }"""
    if target_act in code:
        code = code.replace(target_act, handler_act, 1)

# 4. 기존에 잘못된 위치에 있던 메서드들 말끔히 제거
methods_to_clean = [
    'showDatePicktePicker', 'showLocationSearchDialog', 'showDatePicker',
    'showTimePicker', 'pickExifTargetPhotos', 'pickExifBasePhoto',
    'handleExifBasePicked', 'handleExifTargetsPicked', 'applyExifToTargets'
]
for m in methods_to_clean:
    pattern = r'private\s+void\s+' + m + r'\s*\([^)]*\)\s*\{'
    match = re.search(pattern, code)
    while match:
        start = match.start()
        depth = 0
        end = -1
        for i in range(match.end() - 1, len(code)):
            if code[i] == '{': depth += 1
            elif code[i] == '}':
                depth -= 1
                if depth == 0:
                    end = i + 1
                    break
        if end != -1:
            code = code[:start] + code[end:]
        else:
            break
        match = re.search(pattern, code)

# 기존 필드 정리
code = re.sub(r'private\s+static\s+final\s+int\s+PICK_EXIF_BASE[^;]+;', '', code)
code = re.sub(r'private\s+static\s+final\s+int\s+PICK_EXIF_TARGETS[^;]+;', '', code)
code = re.sub(r'private\s+static\s+final\s+String\[\]\s+BASE_EXIF_TAGS[\s\S]*?\};', '', code)
code = re.sub(r'private\s+Uri\s+basePhotoUri[^;]+;', '', code)
code = re.sub(r'private\s+final\s+Map<String,\s*String>\s+baseExifAttributes[^;]+;', '', code)
code = re.sub(r'private\s+final\s+List<Uri>\s+targetExifUris[^;]+;', '', code)

# 5. 클래스 최상단에 필드 및 6개 메서드 직행 삽입! (자바 컴파일러가 100% 인식)
top_block = """
    private static final int PICK_EXIF_BASE = 71;
    private static final int PICK_EXIF_TARGETS = 72;
    private static final String[] BASE_EXIF_TAGS = {
        androidx.exifinterface.media.ExifInterface.TAG_MAKE,
        androidx.exifinterface.media.ExifInterface.TAG_MODEL,
        androidx.exifinterface.media.ExifInterface.TAG_DATETIME,
        androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL,
        androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED,
        androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH,
        androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER,
        androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME,
        androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        androidx.exifinterface.media.ExifInterface.TAG_WHITE_BALANCE,
        androidx.exifinterface.media.ExifInterface.TAG_FLASH,
        androidx.exifinterface.media.ExifInterface.TAG_LENS_MAKE,
        androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL,
        androidx.exifinterface.media.ExifInterface.TAG_SOFTWARE
    };
    private Uri basePhotoUri = null;
    private final Map<String, String> baseExifAttributes = new LinkedHashMap<>();
    private final List<Uri> targetExifUris = new ArrayList<>();

    private void showLocationSearchDialog() {
        final String[] SEOUL_GU = {
            "무작위 랜덤 (서울 25개 구 중 임의)",
            "강남구", "강동구", "강북구", "강서구", "관악구", "광진구", "구로구", "금천구",
            "노원구", "도봉구", "동대문구", "동작구", "마포구", "서대문구", "서초구", "성동구",
            "성북구", "송파구", "양천구", "영등포구", "용산구", "은평구", "종로구", "중구", "중랑구"
        };
        final double[][] COORDS = {
            {37.5665, 126.9780},
            {37.5172, 127.0473}, {37.5301, 127.1238}, {37.6369, 127.0277}, {37.5509, 126.8495},
            {37.4784, 126.9516}, {37.5385, 127.0823}, {37.4954, 126.8874}, {37.4570, 126.8956},
            {37.6542, 127.0768}, {37.6542, 127.0568}, {37.5838, 127.0507}, {37.5124, 126.9393},
            {37.5509, 126.9087}, {37.5791, 126.9368}, {37.4837, 127.0324}, {37.5459, 127.0371},
            {37.5891, 127.0182}, {37.5145, 127.1066}, {37.5170, 126.8665}, {37.5264, 126.8962},
            {37.5326, 126.9904}, {37.6027, 126.9291}, {37.5732, 126.9793}, {37.5635, 126.9754},
            {37.6065, 127.0927}
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle("서울 위치 선택")
            .setItems(SEOUL_GU, (dialog, which) -> {
                Random rnd = new Random();
                int idx = (which == 0) ? (1 + rnd.nextInt(25)) : which;
                double lat = COORDS[idx][0] + (rnd.nextDouble() - 0.5) * 0.01;
                double lng = COORDS[idx][1] + (rnd.nextDouble() - 0.5) * 0.01;
                if (inputExifLat != null) inputExifLat.setText(String.format(java.util.Locale.US, "%.6f", lat));
                if (inputExifLng != null) inputExifLng.setText(String.format(java.util.Locale.US, "%.6f", lng));
            })
            .show();
    }

    private void showDatePicker() {
        Calendar c = Calendar.getInstance();
        new android.app.DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            if (inputExifDate != null) {
                inputExifDate.setText(String.format(java.util.Locale.US, "%04d:%02d:%02d", year, month + 1, dayOfMonth));
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void showTimePicker() {
        Calendar c = Calendar.getInstance();
        new android.app.TimePickerDialog(this, (view, hourOfDay, minute) -> {
            if (inputExifTime != null) {
                inputExifTime.setText(String.format(java.util.Locale.US, "%02d:%02d:%02d", hourOfDay, minute, 0));
            }
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show();
    }

    private void pickExifTargetPhotos() {
        Toast.makeText(this, "위에서 만든 콜라주 4장에 자동 반영되므로 대상 선택은 필요 없습니다.", Toast.LENGTH_SHORT).show();
    }

    private void handleExifTargetsPicked(Intent data) {
    }

    private void pickExifBasePhoto() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, PICK_EXIF_BASE);
        } catch (ActivityNotFoundException e) {
            showError("갤러리 앱을 찾을 수 없습니다.");
        }
    }

    private void handleExifBasePicked(Intent data) {
        Uri uri = data.getData();
        if (uri == null && data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            uri = data.getClipData().getItemAt(0).getUri();
        }
        if (uri == null) return;
        basePhotoUri = uri;
        baseExifAttributes.clear();

        try {
            getContentResolver().takePersistableUriPermission(basePhotoUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}

        InputStream is = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    is = getContentResolver().openInputStream(MediaStore.setRequireOriginal(basePhotoUri));
                } catch (Exception ignored) {}
            }
            if (is == null) {
                is = getContentResolver().openInputStream(basePhotoUri);
            }
            if (is != null) {
                androidx.exifinterface.media.ExifInterface exif = new androidx.exifinterface.media.ExifInterface(is);
                for (String tag : BASE_EXIF_TAGS) {
                    String val = exif.getAttribute(tag);
                    if (val != null) baseExifAttributes.put(tag, val);
                }
                double[] latLong = exif.getLatLong();
                if (latLong != null && inputExifLat != null && inputExifLng != null) {
                    inputExifLat.setText(String.format(java.util.Locale.US, "%.6f", latLong[0]));
                    inputExifLng.setText(String.format(java.util.Locale.US, "%.6f", latLong[1]));
                }
                String dt = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL);
                if (dt == null) dt = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME);
                if (dt != null && dt.length() >= 19 && inputExifDate != null && inputExifTime != null) {
                    inputExifDate.setText(dt.substring(0, 10));
                    inputExifTime.setText(dt.substring(11, 19));
                }
            }
            if (tvExifBaseSummary != null) {
                tvExifBaseSummary.setText("기본 사진 선택 완료 (" + baseExifAttributes.size() + "개 태그)");
            }
            if (btnApplyExif != null) btnApplyExif.setEnabled(true);
            Toast.makeText(this, "기본 사진 EXIF 추출 완료 (" + baseExifAttributes.size() + "개)", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            showError("기본 사진 읽기 오류: " + e.getMessage());
        } finally {
            if (is != null) { try { is.close(); } catch (Exception ignored) {} }
        }
    }

    private void applyExifToTargets() {
        final java.util.List<Bitmap> bitmapsToSave = new java.util.ArrayList<>();
        for (int i = 0; i < collageCount; i++) {
            if (collages[i] != null && !collages[i].isRecycled()) {
                bitmapsToSave.add(collages[i]);
            }
        }

        if (bitmapsToSave.isEmpty()) {
            Toast.makeText(this, "상단에서 사진을 선택하여 콜라주를 먼저 생성해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        final String latStr = (inputExifLat != null) ? inputExifLat.getText().toString().trim() : "";
        final String lngStr = (inputExifLng != null) ? inputExifLng.getText().toString().trim() : "";
        final String dateStr = (inputExifDate != null) ? inputExifDate.getText().toString().trim() : "";
        final String timeStr = (inputExifTime != null) ? inputExifTime.getText().toString().trim() : "";

        if (tvExifStatus != null) tvExifStatus.setText("콜라주 저장 중...");
        Toast.makeText(this, "콜라주 저장을 시작합니다...", Toast.LENGTH_SHORT).show();

        worker.execute(() -> {
            int success = 0;
            Random rnd = new Random();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US);
            SimpleDateFormat fnFmt = new SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.KOREA);

            Date baseDate = new Date();
            if (!dateStr.isEmpty() && !timeStr.isEmpty()) {
                try { baseDate = sdf.parse(dateStr + " " + timeStr); } catch (Exception ignored) {}
            }

            int accumulatedMinutes = 0;

            for (int i = 0; i < bitmapsToSave.size(); i++) {
                Bitmap bmp = bitmapsToSave.get(i);
                try {
                    if (i == 0) {
                        accumulatedMinutes = 0;
                    } else if (i == 1) {
                        accumulatedMinutes += 2 + rnd.nextInt(3);
                    } else if (i == 2) {
                        accumulatedMinutes += 3 + rnd.nextInt(4);
                    } else {
                        accumulatedMinutes += 3 + rnd.nextInt(5);
                    }

                    Calendar cal = Calendar.getInstance();
                    cal.setTime(baseDate);
                    cal.add(Calendar.MINUTE, accumulatedMinutes);
                    cal.add(Calendar.SECOND, rnd.nextInt(50));
                    String targetDt = sdf.format(cal.getTime());
                    String fileName = fnFmt.format(cal.getTime()) + ".jpg";

                    File tmp = new File(getCacheDir(), "save_temp_" + System.currentTimeMillis() + "_" + i + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                        fos.flush();
                    }

                    androidx.exifinterface.media.ExifInterface targetExif = new androidx.exifinterface.media.ExifInterface(tmp.getAbsolutePath());
                    for (Map.Entry<String, String> entry : baseExifAttributes.entrySet()) {
                        targetExif.setAttribute(entry.getKey(), entry.getValue());
                    }

                    if (!latStr.isEmpty() && !lngStr.isEmpty()) {
                        try {
                            double lat = Double.parseDouble(latStr) + (rnd.nextDouble() - 0.5) * 0.0015;
                            double lng = Double.parseDouble(lngStr) + (rnd.nextDouble() - 0.5) * 0.0015;
                            Location loc = new Location("exif");
                            loc.setLatitude(lat);
                            loc.setLongitude(lng);
                            targetExif.setGpsInfo(loc);
                        } catch (Exception ignored) {}
                    }

                    targetExif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, targetDt);
                    targetExif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, targetDt);
                    targetExif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED, targetDt);
                    targetExif.saveAttributes();

                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/전기블로그EXIF맞춤");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                    }
                    Uri outUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (outUri != null) {
                        try (InputStream fin = new FileInputStream(tmp);
                             OutputStream fout = getContentResolver().openOutputStream(outUri)) {
                            byte[] buf = new byte[8192];
                            int l;
                            while ((l = fin.read(buf)) > 0) fout.write(buf, 0, l);
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            cv.clear();
                            cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                            getContentResolver().update(outUri, cv, null, null);
                        }
                        success++;
                    }
                    tmp.delete();
                } catch (Exception ignored) {}
            }

            final int done = success;
            runOnUiThread(() -> {
                if (tvExifStatus != null) {
                    tvExifStatus.setText("총 " + done + "장 저장 완료! (Pictures/전기블로그EXIF맞춤)");
                }
                Toast.makeText(this, done + "장 콜라주 저장 완료! (랜덤 시차 적용)", Toast.LENGTH_SHORT).show();
            });
        });
    }
"""

class_match = re.search(r'public\s+class\s+CollageActivity\s+extends[^{]*\{', code)
if class_match:
    code = code[:class_match.end()] + top_block + code[class_match.end():]

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("SUCCESS: 클래스 최상단에 6개 메서드 직행 삽입 완료!")
