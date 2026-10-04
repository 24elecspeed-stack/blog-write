package com.keyword.research;

import android.location.Location;
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
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import android.location.Location;
import android.location.Geocoder;
import android.location.Address;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import java.text.SimpleDateFormat;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 콜라주 만들기 - 썸네일(대표사진) 만들기와는 완전히 별개의 화면이다. 콜라주 3개를 만들고, 콜라주마다
 *  사진 장수(2/3/4장)와 사진 편집(밝기/확대/이동/가장자리 잘라내기)을 각각 따로 조절할 수 있다.
 *  기본값(확대 없음)에서는 사진이 잘리지 않고 칸 안에 전체가 다 보인다(letterbox) - 확대를 켰을 때만
 *  잘라내기가 적용된다. 저장/복사도 콜라주마다 따로 한다. */
public final class CollageActivity extends Activity {
    static final String EXTRA_WATERMARK = "watermark";
    static final String EXTRA_WATERMARK_PHONE = "watermark_phone";
    private static final int PICK_PHOTOS = 61;
    private static final int MAX_COLLAGE_COUNT = 4;
    private static final int GREEN = 0xff147d64, INK = 0xff203c34, MUTED = 0xff697a72, BG = 0xfff5f6f2;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // 콜라주 3개가 전부 같은 장수일 필요는 없다 - 콜라주별로 2/3/4장을 따로 고른다.
    private int collageCount = 3;
    private final int[] perCollageCounts = {4, 4, 4, 4};
    private final ThumbnailCollage.PhotoEdit[] edits = {new ThumbnailCollage.PhotoEdit(), new ThumbnailCollage.PhotoEdit(), new ThumbnailCollage.PhotoEdit(), new ThumbnailCollage.PhotoEdit()};
    private final List<List<Uri>> photosBySlot = new ArrayList<>(Arrays.asList(null, null, null, null));
    private final Bitmap[] collages = new Bitmap[MAX_COLLAGE_COUNT];
    private int editingSlot = 0;
    // 저장/복사 버튼은 RichCopyTestActivity의 "네이버로 전달" 단계를 거치지 않고 바로 갤러리·
    // 클립보드로 나가는 경로라서, 여기서도 도용 방지 워터마크를 직접 찍어야 빠진 사진이 없다.
    private String watermarkText = "";
    private Typeface watermarkTypeface = Typeface.DEFAULT;

    private LinearLayout root, previewRow;
    private TextView subtitle, status, editingLabel;
    private Button pickButton, applyButton;
    private final Button[][] countButtons = new Button[MAX_COLLAGE_COUNT][ThumbnailCollage.ALLOWED_PER_COLLAGE.length];
    private final Button[] collageCountButtons = new Button[MAX_COLLAGE_COUNT];
    private final Button[] slotButtons = new Button[MAX_COLLAGE_COUNT];
    private final ImageView[] previewImages = new ImageView[MAX_COLLAGE_COUNT];
    private final Button[] saveButtons = new Button[MAX_COLLAGE_COUNT];
    private final Button[] copyButtons = new Button[MAX_COLLAGE_COUNT];
    private final View[] slotConfigRows = new View[MAX_COLLAGE_COUNT];
    private final View[] previewCells = new View[MAX_COLLAGE_COUNT];

    private SeekBar seekBrightness, seekZoom, seekPanX, seekPanY;
    private ToggleButton toggleTrimTop, toggleTrimBottom, toggleTrimLeft, toggleTrimRight;
    private EditText inputOutputWidth, inputOutputHeight;
    // EXIF 맞춤 변경 관련 상수 및 상태
    
    
    
    
    
    
    private TextView tvExifBaseSummary, tvExifTargetsSummary, tvExifStatus;
    private EditText inputExifLat, inputExifLng, inputExifDate, inputExifTime;
    private Button btnPickExifBase, btnPickExifTargets, btnApplyExif;


    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(dp(3), 1); return v; }
    private void gap(LinearLayout parent, int height) { View v = new View(this); parent.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private Button button(String label, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(11); b.setTextColor(primary ? Color.WHITE : GREEN);
        b.setBackground(background(primary ? GREEN : 0xffeaf1e9, 10)); b.setMinHeight(dp(32));
        b.setPadding(dp(8), dp(2), dp(8), dp(2)); b.setOnClickListener(v -> action.run());
        return b;
    }
    private Button smallButton(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(10); b.setTextColor(GREEN);
        b.setBackground(background(0xffeaf1e9, 8)); b.setMinHeight(dp(26)); b.setMinWidth(0);
        b.setPadding(dp(4), 0, dp(4), 0); b.setOnClickListener(v -> action.run());
        b.setEnabled(false);
        return b;
    }
    private Button smallResetButton(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(10); b.setTextColor(0xffb3261e);
        b.setBackground(background(0xfff6e4e2, 8)); b.setMinHeight(dp(26)); b.setMinWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0); b.setOnClickListener(v -> action.run());
        return b;
    }
    private LinearLayout card(String eyebrow, String heading, LinearLayout parent) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(10), dp(8), dp(10), dp(8)); c.setBackground(background(Color.WHITE, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6); parent.addView(c, lp);
        TextView h = text(heading, 12, INK); h.setTypeface(null, Typeface.BOLD); c.addView(h); gap(c, 4); return c;
    }
    private SeekBar seekBar(LinearLayout parent, String label, int max, java.util.function.IntConsumer onChange) {
        parent.addView(text(label, 9, MUTED));
        SeekBar s = new SeekBar(this); s.setMax(max); s.setPadding(0, 0, 0, 0);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { if (fromUser) onChange.accept(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        parent.addView(s, new LinearLayout.LayoutParams(-1, dp(26))); return s;
    }
    private ToggleButton toggle(String on, String off, java.util.function.Consumer<Boolean> onChange) {
        ToggleButton t = new ToggleButton(this); t.setTextOn(on); t.setTextOff(off); t.setTextSize(9);
        t.setMinHeight(dp(28)); t.setPadding(0, 0, 0, 0);
        t.setOnCheckedChangeListener((b, checked) -> onChange.accept(checked));
        return t;
    }

    private int photoCount() { int sum = 0; for (int i = 0; i < collageCount; i++) sum += perCollageCounts[i]; return sum; }

    
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
@Override public void onCreate(Bundle state) {
        super.onCreate(state);
        watermarkText = getIntent().getStringExtra(EXTRA_WATERMARK) == null ? "" : getIntent().getStringExtra(EXTRA_WATERMARK).trim();
        String watermarkPhone = getIntent().getStringExtra(EXTRA_WATERMARK_PHONE) == null ? "" : getIntent().getStringExtra(EXTRA_WATERMARK_PHONE);
        watermarkTypeface = WatermarkFont.INSTANCE.forPhone(watermarkPhone);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(16) + insets.getSystemWindowInsetTop(), dp(20) + insets.getSystemWindowInsetRight(), dp(16) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        LinearLayout headerRow = new LinearLayout(this); headerRow.setOrientation(LinearLayout.HORIZONTAL); headerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("콜라주 만들기", 14, INK); brand.setTypeface(null, Typeface.BOLD);
        headerRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        headerRow.addView(smallResetButton("초기화", this::resetAll));
        root.addView(headerRow); gap(root, 2);
        subtitle = text("", 9, MUTED); root.addView(subtitle); gap(root, 6);

        LinearLayout outputCountRow = new LinearLayout(this); outputCountRow.setOrientation(LinearLayout.HORIZONTAL); outputCountRow.setGravity(Gravity.CENTER_VERTICAL);
        outputCountRow.addView(text("완성 콜라주", 10, INK), new LinearLayout.LayoutParams(dp(76), -2));
        for (int i = 0; i < MAX_COLLAGE_COUNT; i++) {
            final int count = i + 1;
            Button b = button(count + "개", count == collageCount, () -> { collageCount = count; if (editingSlot >= count) editingSlot = count - 1; updateCollageCountUi(); updateSubtitle(); });
            collageCountButtons[i] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1); if (i > 0) lp.leftMargin = dp(4); outputCountRow.addView(b, lp);
        }
        root.addView(outputCountRow); gap(root, 5);

        for (int slot = 0; slot < MAX_COLLAGE_COUNT; slot++) {
            final int s = slot;
            LinearLayout slotRow = new LinearLayout(this); slotRow.setOrientation(LinearLayout.HORIZONTAL); slotRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = text("콜라주 " + (slot + 1), 10, INK);
            slotRow.addView(label, new LinearLayout.LayoutParams(dp(48), -2));
            for (int i = 0; i < ThumbnailCollage.ALLOWED_PER_COLLAGE.length; i++) {
                final int count = ThumbnailCollage.ALLOWED_PER_COLLAGE[i];
                Button b = button(count + "장", count == perCollageCounts[s], () -> { perCollageCounts[s] = count; updateCountButtons(); updateSubtitle(); });
                countButtons[s][i] = b;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(28), 1); if (i > 0) lp.leftMargin = dp(4); slotRow.addView(b, lp);
            }
            root.addView(slotRow); gap(root, 3);
            slotConfigRows[slot] = slotRow;
        }
        gap(root, 3);

        previewRow = new LinearLayout(this); previewRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int slot = 0; slot < MAX_COLLAGE_COUNT; slot++) {
            final int previewSlot = slot;
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL);
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.FIT_CENTER); iv.setBackground(background(Color.WHITE, 10));
            // 작은 미리보기로는 콜라주 안 사진이 잘 나왔는지 확인하기 어려워서, 누르면 화면 전체
            // 크기로 확대해서 볼 수 있게 한다.
            iv.setOnClickListener(v -> showFullscreenCollage(previewSlot));
            previewImages[slot] = iv;
            cell.addView(iv, new LinearLayout.LayoutParams(-1, 0, 1));
            gap(cell, 2);
            LinearLayout btnRow = new LinearLayout(this); btnRow.setOrientation(LinearLayout.HORIZONTAL);
            final int s = slot;
            Button save = smallButton("저장", () -> saveOne(s)); Button copy = smallButton("복사", () -> copyOne(s));
            saveButtons[slot] = save; copyButtons[slot] = copy;
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1); clp.leftMargin = dp(3);
            btnRow.addView(save, slp); btnRow.addView(copy, clp);
            cell.addView(btnRow);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, -1, 1); if (slot > 0) cellLp.leftMargin = dp(4);
            previewRow.addView(cell, cellLp);
            previewCells[slot] = cell;
        }
        root.addView(previewRow, new LinearLayout.LayoutParams(-1, dp(90))); gap(root, 4);

        pickButton = button("", true, this::pickPhotos); root.addView(pickButton); gap(root, 4);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout scrollContent = new LinearLayout(this); scrollContent.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(scrollContent);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout editCard = card("PHOTO EDIT", "사진 편집 (콜라주마다 따로)", scrollContent);
        editingLabel = text("편집할 콜라주", 9, MUTED); editCard.addView(editingLabel); gap(editCard, 2);
        LinearLayout slotSelectRow = new LinearLayout(this); slotSelectRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int slot = 0; slot < MAX_COLLAGE_COUNT; slot++) {
            final int s = slot;
            Button b = button((slot + 1) + "번", slot == editingSlot, () -> { editingSlot = s; updateSlotButtons(); refreshEditControls(); });
            slotButtons[slot] = b;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(28), 1); if (slot > 0) lp.leftMargin = dp(4); slotSelectRow.addView(b, lp);
        }
        editCard.addView(slotSelectRow); gap(editCard, 4);
        seekBrightness = seekBar(editCard, "밝기", 200, p -> edits[editingSlot].brightness = p - 100);
        seekZoom = seekBar(editCard, "확대(1보다 커지면 잘림)", 100, p -> edits[editingSlot].zoom = 1f + p * 0.02f);
        seekPanX = seekBar(editCard, "가로 이동", 100, p -> edits[editingSlot].panX = (p - 50) / 50f);
        seekPanY = seekBar(editCard, "세로 이동", 100, p -> edits[editingSlot].panY = (p - 50) / 50f);
        editCard.addView(text("가장자리 잘라내기(워터마크 제거)", 9, MUTED)); gap(editCard, 2);
        LinearLayout trimRow = new LinearLayout(this); trimRow.setOrientation(LinearLayout.HORIZONTAL);
        float trim = 0.1f;
        toggleTrimTop = toggle("상단✓", "상단", c -> edits[editingSlot].trimTop = c ? trim : 0f);
        toggleTrimBottom = toggle("하단✓", "하단", c -> edits[editingSlot].trimBottom = c ? trim : 0f);
        toggleTrimLeft = toggle("좌✓", "좌측", c -> edits[editingSlot].trimLeft = c ? trim : 0f);
        toggleTrimRight = toggle("우✓", "우측", c -> edits[editingSlot].trimRight = c ? trim : 0f);
        for (ToggleButton t : new ToggleButton[]{toggleTrimTop, toggleTrimBottom, toggleTrimLeft, toggleTrimRight}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(28), 1); lp.leftMargin = dp(2); lp.rightMargin = dp(2); trimRow.addView(t, lp);
        }
        editCard.addView(trimRow); gap(editCard, 4);
        applyButton = button("이 콜라주에 편집 적용", true, this::applyEditToCurrentSlot);
        applyButton.setEnabled(false);
        editCard.addView(applyButton);

        LinearLayout sizeCard = card("OUTPUT", "출력 크기 (리사이즈)", scrollContent);
        LinearLayout sizeRow = new LinearLayout(this); sizeRow.setOrientation(LinearLayout.HORIZONTAL); sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        inputOutputWidth = new EditText(this); inputOutputWidth.setText(String.valueOf(ThumbnailCollage.WIDTH)); inputOutputWidth.setHint("가로(px)"); inputOutputWidth.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); inputOutputWidth.setTextSize(11);
        inputOutputWidth.setBackground(background(BG, 8)); inputOutputWidth.setPadding(dp(6), dp(2), dp(6), dp(2)); inputOutputWidth.setMinHeight(dp(28));
        sizeRow.addView(inputOutputWidth, new LinearLayout.LayoutParams(0, -2, 1));
        TextView x = text(" x ", 11, MUTED); x.setGravity(Gravity.CENTER); sizeRow.addView(x, new LinearLayout.LayoutParams(-2, -2));
        inputOutputHeight = new EditText(this); inputOutputHeight.setText(String.valueOf(ThumbnailCollage.HEIGHT)); inputOutputHeight.setHint("세로(px)"); inputOutputHeight.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); inputOutputHeight.setTextSize(11);
        inputOutputHeight.setBackground(background(BG, 8)); inputOutputHeight.setPadding(dp(6), dp(2), dp(6), dp(2)); inputOutputHeight.setMinHeight(dp(28));
        sizeRow.addView(inputOutputHeight, new LinearLayout.LayoutParams(0, -2, 1));
        sizeCard.addView(sizeRow);
        LinearLayout exifCard = card("EXIF CUSTOM", "기본 사진 EXIF 추출 및 위치·날짜 맞춤 변경", scrollContent);
        TextView desc = text("기본 사진 1장의 모든 카메라/촬영 메타데이터(EXIF)를 가져와서, 사용자가 위치(GPS)와 날짜/시간을 원하는 값으로 수정한 뒤 대상 사진들에 무손실로 일괄 덮어씁니다.", 10, MUTED);
        exifCard.addView(desc);
        gap(exifCard, 4);

        // 1. 기본 사진 선택
        btnPickExifBase = button("① 기본 사진 선택 (모든 EXIF 추출)", true, this::pickExifBasePhoto);
        exifCard.addView(btnPickExifBase);
        gap(exifCard, 2);

        tvExifBaseSummary = text("선택된 기본 사진 없음", 10, INK);
        tvExifBaseSummary.setBackground(background(BG, 6));
        tvExifBaseSummary.setPadding(dp(6), dp(4), dp(6), dp(4));
        exifCard.addView(tvExifBaseSummary);
        gap(exifCard, 5);

        // 2. 위치 (GPS) 수정
        exifCard.addView(text("위치 수정 (위도 / 경도)", 10, INK));
        gap(exifCard, 2);

        LinearLayout locRow = new LinearLayout(this);
        locRow.setOrientation(LinearLayout.HORIZONTAL);
        locRow.setGravity(Gravity.CENTER_VERTICAL);

        inputExifLat = new EditText(this);
        inputExifLat.setHint("위도 (예: 37.566535)");
        inputExifLat.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        inputExifLat.setTextSize(11);
        inputExifLat.setBackground(background(BG, 8));
        inputExifLat.setPadding(dp(6), dp(4), dp(6), dp(4));
        inputExifLat.setMinHeight(dp(30));
        locRow.addView(inputExifLat, new LinearLayout.LayoutParams(0, -2, 1));

        inputExifLng = new EditText(this);
        inputExifLng.setHint("경도 (예: 126.977969)");
        inputExifLng.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        inputExifLng.setTextSize(11);
        inputExifLng.setBackground(background(BG, 8));
        inputExifLng.setPadding(dp(6), dp(4), dp(6), dp(4));
        inputExifLng.setMinHeight(dp(30));
        LinearLayout.LayoutParams lngLp = new LinearLayout.LayoutParams(0, -2, 1);
        lngLp.leftMargin = dp(4);
        locRow.addView(inputExifLng, lngLp);

        exifCard.addView(locRow);
        gap(exifCard, 3);

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnSeoul = smallButton("서울시청", () -> { inputExifLat.setText("37.566535"); inputExifLng.setText("126.977969"); });
        btnSeoul.setEnabled(true);
        Button btnGangnam = smallButton("강남역", () -> { inputExifLat.setText("37.497952"); inputExifLng.setText("127.027619"); });
        btnGangnam.setEnabled(true);
        Button btnSearchPlace = smallButton("주소/장소검색", this::showLocationSearchDialog);
        btnSearchPlace.setEnabled(true);
        Button btnClearGps = smallButton("위치 미포함", () -> { inputExifLat.setText(""); inputExifLng.setText(""); });
        btnClearGps.setEnabled(true);

        LinearLayout.LayoutParams pLp1 = new LinearLayout.LayoutParams(0, -2, 1);
        LinearLayout.LayoutParams pLp2 = new LinearLayout.LayoutParams(0, -2, 1); pLp2.leftMargin = dp(3);
        LinearLayout.LayoutParams pLp3 = new LinearLayout.LayoutParams(0, -2, 1.2f); pLp3.leftMargin = dp(3);
        LinearLayout.LayoutParams pLp4 = new LinearLayout.LayoutParams(0, -2, 1); pLp4.leftMargin = dp(3);
        presetRow.addView(btnSeoul, pLp1);
        presetRow.addView(btnGangnam, pLp2);
        presetRow.addView(btnSearchPlace, pLp3);
        presetRow.addView(btnClearGps, pLp4);
        exifCard.addView(presetRow);
        gap(exifCard, 5);

        // 3. 날짜 및 시간 수정
        exifCard.addView(text("촬영 일시 수정 (YYYY:MM:DD 및 HH:MM:SS)", 10, INK));
        gap(exifCard, 2);

        LinearLayout dtRow = new LinearLayout(this);
        dtRow.setOrientation(LinearLayout.HORIZONTAL);
        dtRow.setGravity(Gravity.CENTER_VERTICAL);

        inputExifDate = new EditText(this);
        inputExifDate.setHint("날짜 (예: 2026:09:29)");
        inputExifDate.setInputType(android.text.InputType.TYPE_CLASS_DATETIME);
        inputExifDate.setTextSize(11);
        inputExifDate.setBackground(background(BG, 8));
        inputExifDate.setPadding(dp(6), dp(4), dp(6), dp(4));
        inputExifDate.setMinHeight(dp(30));
        dtRow.addView(inputExifDate, new LinearLayout.LayoutParams(0, -2, 1));

        inputExifTime = new EditText(this);
        inputExifTime.setHint("시간 (예: 14:30:00)");
        inputExifTime.setInputType(android.text.InputType.TYPE_CLASS_DATETIME);
        inputExifTime.setTextSize(11);
        inputExifTime.setBackground(background(BG, 8));
        inputExifTime.setPadding(dp(6), dp(4), dp(6), dp(4));
        inputExifTime.setMinHeight(dp(30));
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(0, -2, 1);
        timeLp.leftMargin = dp(4);
        dtRow.addView(inputExifTime, timeLp);

        exifCard.addView(dtRow);
        gap(exifCard, 3);

        LinearLayout timePresetRow = new LinearLayout(this);
        timePresetRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnPickDate = smallButton("날짜", this::showDatePicker);
        btnPickDate.setEnabled(true);
        Button btnPickTime = smallButton("시간", this::showTimePicker);
        btnPickTime.setEnabled(true);
        Button btnNow = smallButton("현재일시", () -> {
            SimpleDateFormat sdfD = new SimpleDateFormat("yyyy:MM:dd", Locale.US);
            SimpleDateFormat sdfT = new SimpleDateFormat("HH:mm:ss", Locale.US);
            Date n = new Date();
            inputExifDate.setText(sdfD.format(n));
            inputExifTime.setText(sdfT.format(n));
        });
        btnNow.setEnabled(true);

        LinearLayout.LayoutParams tpLp1 = new LinearLayout.LayoutParams(0, -2, 1);
        LinearLayout.LayoutParams tpLp2 = new LinearLayout.LayoutParams(0, -2, 1); tpLp2.leftMargin = dp(3);
        LinearLayout.LayoutParams tpLp3 = new LinearLayout.LayoutParams(0, -2, 1); tpLp3.leftMargin = dp(3);
        timePresetRow.addView(btnPickDate, tpLp1);
        timePresetRow.addView(btnPickTime, tpLp2);
        timePresetRow.addView(btnNow, tpLp3);
        exifCard.addView(timePresetRow);
        gap(exifCard, 5);

        // 4. 대상 사진들 선택
        btnPickExifTargets = button("② 변경 적용할 대상 사진들 선택 (선택 안하면 기본사진에 적용)", false, this::pickExifTargetPhotos);
        btnPickExifTargets.setEnabled(false);
        exifCard.addView(btnPickExifTargets);
        gap(exifCard, 2);

        tvExifTargetsSummary = text("선택된 대상 사진 없음 (기본 사진 자체에 적용)", 10, INK);
        tvExifTargetsSummary.setBackground(background(BG, 6));
        tvExifTargetsSummary.setPadding(dp(6), dp(4), dp(6), dp(4));
        exifCard.addView(tvExifTargetsSummary);
        gap(exifCard, 5);

        // 5. 실행 버튼
        btnApplyExif = button("저장", true, this::applyExifToTargets);
        btnApplyExif.setEnabled(true);
        btnApplyExif.setEnabled(true);
        btnApplyExif.setEnabled(true);
        btnApplyExif.setEnabled(false);
        exifCard.addView(btnApplyExif);
        gap(exifCard, 2);

        tvExifStatus = text("", 10, GREEN);
        tvExifStatus.setGravity(Gravity.CENTER);
        exifCard.addView(tvExifStatus);


        status = text("", 10, MUTED); status.setGravity(Gravity.CENTER); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, dp(4), 0, 0);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        updateCountButtons();
        updateCollageCountUi();
        updateSubtitle();
        updateSlotButtons();
        refreshEditControls();
        restoreFromState();
    }

    // 화면을 나갔다 들어오면 Activity가 새로 만들어져서 이전에 고른 사진/편집값이 사라지는 문제 -
    // CollageState(앱이 켜져 있는 동안만 유지)에 저장해 뒀다가 복원한다. 콜라주 비트맵 자체는
    // 저장해 두지 않으므로 사진이 있는 칸은 다시 렌더링한다.
    @Override protected void onPause() { super.onPause(); saveToState(); }

    private void restoreFromState() {
        if (!CollageState.INSTANCE.getHasData()) return;
        int[] savedCounts = CollageState.INSTANCE.getPerCollageCounts();
        ThumbnailCollage.PhotoEdit[] savedEdits = CollageState.INSTANCE.getEdits();
        List<List<Uri>> savedPhotos = CollageState.INSTANCE.getPhotosBySlot();
        collageCount = CollageState.INSTANCE.getCollageCount();
        for (int slot = 0; slot < MAX_COLLAGE_COUNT; slot++) {
            perCollageCounts[slot] = savedCounts[slot];
            edits[slot] = savedEdits[slot];
            photosBySlot.set(slot, savedPhotos.get(slot));
        }
        editingSlot = CollageState.INSTANCE.getEditingSlot();
        inputOutputWidth.setText(CollageState.INSTANCE.getOutputWidth());
        inputOutputHeight.setText(CollageState.INSTANCE.getOutputHeight());
        updateCountButtons(); updateSubtitle(); updateSlotButtons(); refreshEditControls();

        updateCollageCountUi();
        for (int slot = 0; slot < MAX_COLLAGE_COUNT; slot++) {
            List<Uri> photos = photosBySlot.get(slot);
            if (photos == null) continue;
            final int s = slot;
            worker.execute(() -> {
                try {
                    Bitmap result = ThumbnailCollage.render(this, photos, perCollageCounts[s], edits[s]);
                    runOnUiThread(() -> { if (!isDestroyed()) setCollage(s, result); });
                } catch (IOException ignored) {}
            });
        }
    }

    private void saveToState() {
        List<List<Uri>> photosCopy = new ArrayList<>(photosBySlot);
        CollageState.INSTANCE.save(collageCount, perCollageCounts, edits, photosCopy, editingSlot,
            inputOutputWidth.getText().toString(), inputOutputHeight.getText().toString());
    }

    private void updateCountButtons() {
        for (int slot = 0; slot < MAX_COLLAGE_COUNT; slot++) {
            for (int i = 0; i < ThumbnailCollage.ALLOWED_PER_COLLAGE.length; i++) {
                boolean selected = ThumbnailCollage.ALLOWED_PER_COLLAGE[i] == perCollageCounts[slot];
                countButtons[slot][i].setBackground(background(selected ? GREEN : 0xffeaf1e9, 14));
                countButtons[slot][i].setTextColor(selected ? Color.WHITE : GREEN);
            }
        }
    }
    private void updateSlotButtons() {
        for (int slot = 0; slot < MAX_COLLAGE_COUNT; slot++) {
            boolean selected = slot == editingSlot;
            slotButtons[slot].setBackground(background(selected ? GREEN : 0xffeaf1e9, 14));
            slotButtons[slot].setTextColor(selected ? Color.WHITE : GREEN);
        }
        editingLabel.setText("편집 중: 콜라주 " + (editingSlot + 1));
        applyButton.setEnabled(photosBySlot.get(editingSlot) != null);
    }
    private void updateCollageCountUi() {
        for (int i = 0; i < MAX_COLLAGE_COUNT; i++) {
            boolean countSelected = i + 1 == collageCount;
            collageCountButtons[i].setBackground(background(countSelected ? GREEN : 0xffeaf1e9, 14));
            collageCountButtons[i].setTextColor(countSelected ? Color.WHITE : GREEN);
            int visibility = i < collageCount ? View.VISIBLE : View.GONE;
            slotConfigRows[i].setVisibility(visibility);
            previewCells[i].setVisibility(visibility);
            slotButtons[i].setVisibility(visibility);
        }
        updateSlotButtons();
    }
    /** 편집 슬라이더/토글이 현재 editingSlot의 값을 보여주도록 맞춘다 (리스너가 다시 발동하지 않게
     *  fromUser일 때만 값을 쓰도록 되어 있다). */
    private void refreshEditControls() {
        ThumbnailCollage.PhotoEdit e = edits[editingSlot];
        seekBrightness.setProgress(e.brightness + 100);
        seekZoom.setProgress(Math.round((e.zoom - 1f) / 0.02f));
        seekPanX.setProgress(Math.round(e.panX * 50f + 50f));
        seekPanY.setProgress(Math.round(e.panY * 50f + 50f));
        toggleTrimTop.setChecked(e.trimTop > 0);
        toggleTrimBottom.setChecked(e.trimBottom > 0);
        toggleTrimLeft.setChecked(e.trimLeft > 0);
        toggleTrimRight.setChecked(e.trimRight > 0);
        applyButton.setEnabled(photosBySlot.get(editingSlot) != null);
    }
    private void updateSubtitle() {
        StringBuilder breakdown = new StringBuilder();
        for (int i = 0; i < collageCount; i++) { if (i > 0) breakdown.append(" + "); breakdown.append(perCollageCounts[i]).append("장"); }
        subtitle.setText("콜라주 " + collageCount + "개 (" + breakdown + ") · 최소 " + photoCount() + "장 필요 · 기본은 사진이 잘리지 않고 전체가 보입니다");
        pickButton.setText("사진 선택 (최소 " + photoCount() + "장)");
    }

    private void pickPhotos() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI); intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(intent, PICK_PHOTOS); } catch (ActivityNotFoundException error) { showError("사진 선택 앱을 찾을 수 없습니다."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_EXIF_BASE && result == RESULT_OK && data != null) {
            handleExifBasePicked(data);
            return;
        }
        if (request != PICK_PHOTOS || result != RESULT_OK || data == null) return;
        LinkedHashSet<Uri> selected = new LinkedHashSet<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) selected.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) selected.add(data.getData());
        if (selected.isEmpty()) return;
        final int need = photoCount();
        if (selected.size() < need) { showError("사진을 최소 " + need + "장 선택해야 합니다. (" + selected.size() + "장 선택됨)"); return; }
        for (Uri uri : selected) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
        }
        final ArrayList<Uri> all = new ArrayList<>(selected);
        final int extra = all.size() - need;
        final int[] counts = perCollageCounts.clone();
        // 새로 사진을 고르면 콜라주별 편집값은 기본값으로 되돌린다 - 이전 사진 기준 편집이 새 사진에 그대로 남아있으면 혼란스럽다.
        for (int i = 0; i < collageCount; i++) edits[i] = new ThumbnailCollage.PhotoEdit();
        status.setText("사진 " + need + "장으로 콜라주 " + collageCount + "개를 만드는 중입니다"
            + (extra > 0 ? " (나머지 " + extra + "장은 사용하지 않습니다)" : "") + "…");
        List<List<Uri>> slots = new ArrayList<>();
        int start = 0;
        for (int slot = 0; slot < collageCount; slot++) { slots.add(new ArrayList<>(all.subList(start, start + counts[slot]))); start += counts[slot]; }
        worker.execute(() -> {
            try {
                Bitmap[] results = new Bitmap[collageCount];
                for (int slot = 0; slot < collageCount; slot++) {
                    results[slot] = ThumbnailCollage.render(this, slots.get(slot), counts[slot], edits[slot]);
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    photosBySlot.clear(); photosBySlot.addAll(slots); while (photosBySlot.size() < MAX_COLLAGE_COUNT) photosBySlot.add(null);
                    for (int slot = 0; slot < collageCount; slot++) setCollage(slot, results[slot]);
                    editingSlot = 0; updateSlotButtons(); refreshEditControls();
                    status.setText("콜라주 " + collageCount + "개가 완성되었습니다. 아래에서 콜라주별로 편집하고 각각 저장/복사할 수 있습니다.");
                });
            } catch (IOException error) {
                runOnUiThread(() -> { if (!isDestroyed()) showError("콜라주를 만들지 못했습니다: " + error.getMessage()); });
            }
        });
    }

    private void applyEditToCurrentSlot() {
        List<Uri> photos = photosBySlot.get(editingSlot);
        if (photos == null) return;
        final int slot = editingSlot;
        final int per = perCollageCounts[slot];
        final ThumbnailCollage.PhotoEdit edit = edits[slot];
        status.setText("콜라주 " + (slot + 1) + "번에 편집을 적용하는 중입니다…");
        applyButton.setEnabled(false);
        worker.execute(() -> {
            try {
                Bitmap result = ThumbnailCollage.render(this, photos, per, edit);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setCollage(slot, result);
                    applyButton.setEnabled(true);
                    status.setText("콜라주 " + (slot + 1) + "번에 편집을 적용했습니다.");
                });
            } catch (IOException error) {
                runOnUiThread(() -> { if (!isDestroyed()) { applyButton.setEnabled(true); showError("편집을 적용하지 못했습니다: " + error.getMessage()); } });
            }
        });
    }
    private void setCollage(int slot, Bitmap bitmap) {
        if (collages[slot] != null && collages[slot] != bitmap) collages[slot].recycle();
        collages[slot] = bitmap;
        previewImages[slot].setImageBitmap(bitmap);
        saveButtons[slot].setEnabled(true);
        copyButtons[slot].setEnabled(true);
    }
    private void showFullscreenCollage(int slot) {
        Bitmap bitmap = collages[slot];
        if (bitmap == null) return;
        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(bitmap);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(imageView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        imageView.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    private int outputWidthOrDefault() {
        try { int v = Integer.parseInt(inputOutputWidth.getText().toString().trim()); return Math.max(50, Math.min(4000, v)); } catch (Exception e) { return ThumbnailCollage.WIDTH; }
    }
    private int outputHeightOrDefault() {
        try { int v = Integer.parseInt(inputOutputHeight.getText().toString().trim()); return Math.max(50, Math.min(4000, v)); } catch (Exception e) { return ThumbnailCollage.HEIGHT; }
    }
    private Bitmap resized(Bitmap bitmap) {
        int w = outputWidthOrDefault(), h = outputHeightOrDefault();
        if (w == bitmap.getWidth() && h == bitmap.getHeight()) return bitmap;
        return Bitmap.createScaledBitmap(bitmap, w, h, true);
    }

    private void saveOne(int slot) {
        Bitmap bitmap = collages[slot];
        if (bitmap == null) return;
        try {
            Bitmap out = Watermark.INSTANCE.apply(resized(bitmap), watermarkText, watermarkTypeface);
            String filename = "collage_" + System.currentTimeMillis() + "_" + (slot + 1) + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/전기블로그콜라주");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("저장 위치를 만들 수 없습니다.");
            try (OutputStream stream = getContentResolver().openOutputStream(uri)) { out.compress(Bitmap.CompressFormat.JPEG, 93, stream); }
            Toast.makeText(this, "콜라주 " + (slot + 1) + "번을 갤러리에 저장했습니다.", Toast.LENGTH_SHORT).show();
        } catch (Exception error) { Toast.makeText(this, "저장 실패: " + error.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private void copyOne(int slot) {
        Bitmap bitmap = collages[slot];
        if (bitmap == null) return;
        try {
            Bitmap out = Watermark.INSTANCE.apply(resized(bitmap), watermarkText, watermarkTypeface);
            File dir = new File(getCacheDir(), "thumbnails"); dir.mkdirs();
            File file = new File(dir, "collage_clip_" + System.currentTimeMillis() + "_" + (slot + 1) + ".jpg");
            try (FileOutputStream stream = new FileOutputStream(file)) { out.compress(Bitmap.CompressFormat.JPEG, 93, stream); }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), "collage", uri));
            Toast.makeText(this, "콜라주 " + (slot + 1) + "번을 클립보드에 복사했습니다.", Toast.LENGTH_SHORT).show();
        } catch (Exception error) { Toast.makeText(this, "복사 실패: " + error.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private void showError(String message) { status.setText(message); new AlertDialog.Builder(this).setTitle("확인이 필요해요").setMessage(message).setPositiveButton("확인", null).show(); }

    // 화면을 나갔다 들어와도 사진/콜라주가 그대로 남아있어서(Activity가 재사용되면) 혼란스럽다는
    // 피드백에 따라, 고른 사진·만들어진 콜라주·편집값을 전부 초기 상태로 되돌리는 버튼.
    private void resetAll() {
        collageCount = 3;
        for (int slot = 0; slot < MAX_COLLAGE_COUNT; slot++) {
            perCollageCounts[slot] = 4;
            edits[slot] = new ThumbnailCollage.PhotoEdit();
            photosBySlot.set(slot, null);
            if (collages[slot] != null) { collages[slot].recycle(); collages[slot] = null; }
            previewImages[slot].setImageBitmap(null);
            saveButtons[slot].setEnabled(false);
            copyButtons[slot].setEnabled(false);
        }
        editingSlot = 0;
        inputOutputWidth.setText(String.valueOf(ThumbnailCollage.WIDTH));
        inputOutputHeight.setText(String.valueOf(ThumbnailCollage.HEIGHT));
        updateCountButtons();
        updateCollageCountUi();
        updateSubtitle();
        updateSlotButtons();
        refreshEditControls();
        CollageState.INSTANCE.clear();
        
        baseExifAttributes.clear();
        targetExifUris.clear();
        basePhotoUri = null;
        if (tvExifBaseSummary != null) tvExifBaseSummary.setText("선택된 기본 사진 없음 (기본 사진 자체에 적용)");
        if (tvExifTargetsSummary != null) tvExifTargetsSummary.setText("선택된 대상 사진 없음 (기본 사진 자체에 적용)");
        if (inputExifLat != null) inputExifLat.setText("");
        if (inputExifLng != null) inputExifLng.setText("");
        if (inputExifDate != null) inputExifDate.setText("");
        if (inputExifTime != null) inputExifTime.setText("");
        if (tvExifStatus != null) tvExifStatus.setText("");
        if (btnPickExifTargets != null) btnPickExifTargets.setEnabled(false);
        if (btnApplyExif != null) btnApplyExif.setEnabled(false);

        status.setText("초기화되었습니다.");
    }
}
