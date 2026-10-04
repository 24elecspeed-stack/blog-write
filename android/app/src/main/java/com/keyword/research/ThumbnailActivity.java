package com.keyword.research;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.util.*;

/** 블로그 대표사진(썸네일) 만들기. 모아블로그(com.blogauto.moablog) MainActivity.java와 같은 스타일로
 *  구성했다 - XML 없이 코드로 직접 그리는 화면, 01/02/03 탭 내비게이션, 카드/버튼 UI, AlertDialog
 *  목록으로 값 선택. 미리보기는 상태가 살아있는 실시간 편집 캔버스라 탭과 무관하게 항상 보이게 두고,
 *  그 아래 탭 내용만 바뀐다. 모든 값은 preview(ThumbnailPreviewView) 또는 이 액티비티의 필드에
 *  저장되므로 탭을 오가며 입력창이 다시 만들어져도 값이 유지된다. 사진 여러 장을 합치는 콜라주
 *  기능은 이 화면과 무관한 별도 기능이라 {@link CollageActivity}로 분리했다. */
public final class ThumbnailActivity extends Activity {
    private static final int PICK_BG = 51;
    private static final int GREEN = 0xff147d64, INK = 0xff203c34, MUTED = 0xff697a72, BG = 0xfff5f6f2;

    private static final class Named { final String label; final Integer value; Named(String l, Integer v) { label = l; value = v; } }
    private final List<Named> colorOptions = Arrays.asList(
        new Named("검정색", Color.BLACK), new Named("흰색", Color.WHITE), new Named("황금색", Color.parseColor("#FFD700")),
        new Named("빨간색", Color.RED), new Named("파란색", Color.BLUE), new Named("초록색", Color.parseColor("#57F711")), new Named("노란색", Color.YELLOW)
    );
    private final List<Named> colorOptionsWithNone = new ArrayList<Named>() {{ add(new Named("없음", null)); addAll(colorOptions); }};
    private final String[] bgImageLabels = {"배경 없음", "1. 노란색 버스트 배경", "2. 노란색 버스트 배경", "3. 노란색 버스트 배경", "4. 초록색 버스트 배경", "5. 초록색 버스트 배경", "6. 초록색 버스트 배경"};
    private final int[] bgImageRes = {0, R.drawable.bg1, R.drawable.bg2, R.drawable.bg3, R.drawable.bg4, R.drawable.bg5, R.drawable.bg6};
    private final String[] fontLabels = {
        "일반체", "손글씨체 (나눔손글씨 펜)",
        "검은고딕", "도현체", "주아체", "구기체",
        "나눔고딕", "나눔명조", "나눔손글씨 붓"
    };
    private final int[] fontRes = {
        R.font.hmkmrhd, R.font.nanum_pen_script,
        R.font.black_han_sans, R.font.do_hyeon, R.font.jua, R.font.gugi,
        R.font.nanum_gothic, R.font.nanum_myeongjo, R.font.nanum_brush_script
    };
    private final String[] shadowLabels = {"없음", "기본 그림자", "강한 그림자"};
    private final ThumbnailPreviewView.TextShadow[] shadowValues = {ThumbnailPreviewView.TextShadow.NONE, ThumbnailPreviewView.TextShadow.DEFAULT, ThumbnailPreviewView.TextShadow.STRONG};

    private int bgImageIndex = 0, bgColorIndex = 2, borderColorIndex = 3, textColorIndex = 1, textOutlineIndex = 1, shadowIndex = 2, fontIndex = 0;
    private int outputWidth = 800, outputHeight = 800;
    private int tab = 0;
    private final Button[] tabs = new Button[3];

    private ThumbnailPreviewView preview;
    private LinearLayout content;
    private TextView status;
    private Uri pickedImageUri;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(dp(3), 1); return v; }
    private void gap(LinearLayout parent, int height) { View v = new View(this); parent.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private Button button(String label, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(12); b.setTextColor(primary ? Color.WHITE : GREEN);
        b.setBackground(background(primary ? GREEN : 0xffeaf1e9, 10)); b.setMinHeight(dp(34));
        b.setPadding(dp(10), dp(2), dp(10), dp(2)); b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(34)); lp.topMargin = dp(4); b.setLayoutParams(lp); return b;
    }
    private Button smallResetButton(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(10); b.setTextColor(0xffb3261e);
        b.setBackground(background(0xfff6e4e2, 8)); b.setMinHeight(dp(26)); b.setMinWidth(0);
        b.setPadding(dp(8), 0, dp(8), 0); b.setOnClickListener(v -> action.run());
        return b;
    }
    private LinearLayout card(String eyebrow, String heading) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(10), dp(8), dp(10), dp(8)); c.setBackground(background(Color.WHITE, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6); content.addView(c, lp);
        TextView h = text(heading, 13, INK); h.setTypeface(null, Typeface.BOLD); c.addView(h); gap(c, 4); return c;
    }
    private SeekBar seekBar(LinearLayout parent, String label, int max, int progress, java.util.function.IntConsumer onChange) {
        parent.addView(text(label, 10, MUTED));
        SeekBar s = new SeekBar(this); s.setMax(max); s.setProgress(progress);
        s.setPadding(0, 0, 0, 0);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) { onChange.accept(p); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(28));
        parent.addView(s, slp); return s;
    }
    private ToggleButton toggle(String on, String off, boolean checked, java.util.function.Consumer<Boolean> onChange) {
        ToggleButton t = new ToggleButton(this); t.setTextOn(on); t.setTextOff(off); t.setTextSize(9); t.setChecked(checked);
        t.setMinHeight(dp(30)); t.setPadding(0, 0, 0, 0);
        t.setOnCheckedChangeListener((b, isChecked) -> onChange.accept(isChecked));
        return t;
    }
    private EditText numberField(LinearLayout parent, String label, int value, java.util.function.IntConsumer onChange) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label, 10, INK); row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        EditText e = new EditText(this); e.setText(String.valueOf(value)); e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setTextColor(INK); e.setTextSize(11);
        e.setBackground(background(BG, 8)); e.setPadding(dp(8), dp(2), dp(8), dp(2)); e.setMinHeight(dp(30));
        e.addTextChangedListener(watcher(() -> { Integer v = toIntOrNull(e.getText().toString()); if (v != null) onChange.accept(v); }));
        row.addView(e, new LinearLayout.LayoutParams(dp(70), -2));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); gap(parent, 4); return e;
    }
    private Button pickerRow(LinearLayout parent, String label, String initialValue, Runnable onClick) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label, 10, MUTED); row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        Button b = new Button(this); b.setText(initialValue); b.setAllCaps(false); b.setTextSize(11); b.setTextColor(GREEN);
        b.setBackground(background(0xffeaf1e9, 8)); b.setMinHeight(dp(30)); b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(8), 0, dp(8), 0); b.setOnClickListener(v -> onClick.run());
        row.addView(b, new LinearLayout.LayoutParams(dp(150), dp(30)));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); gap(parent, 4);
        return b;
    }
    private interface IntCallback { void run(int index); }
    private void openPicker(String title, String[] labels, int current, IntCallback onPick) {
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, current, (d, which) -> { onPick.run(which); d.dismiss(); }).setNegativeButton("취소", null).show();
    }
    private void openColorPicker(List<Named> options, int current, IntCallback onPick) {
        String[] labels = new String[options.size()]; for (int i = 0; i < options.size(); i++) labels[i] = options.get(i).label;
        openPicker("색상 선택", labels, current, onPick);
    }
    private android.text.TextWatcher watcher(Runnable onChanged) {
        return new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { onChanged.run(); }
        };
    }
    private Integer toIntOrNull(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; } }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(12), dp(20), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(12) + insets.getSystemWindowInsetTop(), dp(20) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout headerRow = new LinearLayout(this); headerRow.setOrientation(LinearLayout.HORIZONTAL); headerRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("블로그 대표사진 만들기", 15, INK); brand.setTypeface(null, Typeface.BOLD);
        headerRow.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        headerRow.addView(smallResetButton("초기화", this::resetAll));
        root.addView(headerRow); gap(root, 6);

        LinearLayout nav = new LinearLayout(this);
        String[] names = {"01 배경", "02 텍스트", "03 저장"};
        for (int i = 0; i < 3; i++) {
            final int index = i; tabs[i] = button(names[i], i == tab, () -> { tab = index; render(); });
            tabs[i].setTextSize(11); tabs[i].setMinHeight(dp(32));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1); if (i > 0) lp.leftMargin = dp(4); nav.addView(tabs[i], lp);
        }
        root.addView(nav); gap(root, 6);

        // 미리보기를 실제 출력 크기(가로/세로)와 같은 비율로 보여준다(WYSIWYG). 화면을 다 차지하면
        // 편집 옵션이 아래로 밀려 스크롤해야 하므로, 폭을 작게 고정해서 편집 영역을 넉넉히 남긴다.
        preview = new ThumbnailPreviewView(this);
        preview.setAspectRatio(outputWidth / (float) outputHeight);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(dp(210), -2);
        previewLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(preview, previewLp); gap(root, 4);
        Button resetBtn = button("텍스트 가운데로", false, () -> preview.resetTextPosition());
        resetBtn.setTextSize(10); resetBtn.setMinHeight(dp(26));
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-2, dp(26)); resetLp.gravity = Gravity.CENTER_HORIZONTAL; resetLp.topMargin = 0;
        resetBtn.setLayoutParams(resetLp);
        root.addView(resetBtn); gap(root, 4);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        status = text("", 10, MUTED); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); status.setPadding(0, dp(4), 0, dp(2)); root.addView(status);
        setContentView(root);

        applyPreviewStyle();
        restoreFromState();
        render();
    }

    // 화면을 나갔다 들어오면 Activity가 새로 만들어져서(필드가 초기화됨) 이전 편집 내용이
    // 사라지는 문제 - ThumbnailState(앱이 켜져 있는 동안만 유지)에 저장해 뒀다가 복원한다.
    @Override protected void onPause() { super.onPause(); saveToState(); cacheThumbnailForImport(); }

    private void restoreFromState() {
        if (!ThumbnailState.INSTANCE.getHasData()) return;
        bgImageIndex = ThumbnailState.INSTANCE.getBgImageIndex();
        bgColorIndex = ThumbnailState.INSTANCE.getBgColorIndex();
        borderColorIndex = ThumbnailState.INSTANCE.getBorderColorIndex();
        textColorIndex = ThumbnailState.INSTANCE.getTextColorIndex();
        textOutlineIndex = ThumbnailState.INSTANCE.getTextOutlineIndex();
        shadowIndex = ThumbnailState.INSTANCE.getShadowIndex();
        fontIndex = Math.max(0, Math.min(ThumbnailState.INSTANCE.getFontIndex(), fontRes.length - 1));
        outputWidth = ThumbnailState.INSTANCE.getOutputWidth();
        outputHeight = ThumbnailState.INSTANCE.getOutputHeight();
        pickedImageUri = ThumbnailState.INSTANCE.getPickedImageUri();

        applyPreviewStyle();
        preview.setAspectRatio(outputWidth / (float) outputHeight);
        preview.brightness = ThumbnailState.INSTANCE.getBrightness();
        preview.zoom = ThumbnailState.INSTANCE.getZoom();
        preview.panX = ThumbnailState.INSTANCE.getPanX();
        preview.panY = ThumbnailState.INSTANCE.getPanY();
        preview.trimTop = ThumbnailState.INSTANCE.getTrimTop();
        preview.trimBottom = ThumbnailState.INSTANCE.getTrimBottom();
        preview.trimLeft = ThumbnailState.INSTANCE.getTrimLeft();
        preview.trimRight = ThumbnailState.INSTANCE.getTrimRight();
        String[] savedTexts = ThumbnailState.INSTANCE.getTexts();
        float[] savedSizes = ThumbnailState.INSTANCE.getTextSizePx();
        float[] savedOffX = ThumbnailState.INSTANCE.getTextOffsetX();
        float[] savedOffY = ThumbnailState.INSTANCE.getTextOffsetY();
        for (int i = 0; i < ThumbnailPreviewView.TEXT_SLOTS; i++) {
            preview.texts[i] = savedTexts[i];
            preview.textSizePx[i] = savedSizes[i];
            preview.textOffsetX[i] = savedOffX[i];
            preview.textOffsetY[i] = savedOffY[i];
        }

        if (pickedImageUri != null) {
            try {
                preview.backgroundBitmap = ThumbnailCollage.decode(this, pickedImageUri, 3200);
                preview.useBackgroundColorFill = false;
            } catch (IOException error) {
                pickedImageUri = null;
                applyBackgroundFromIndex();
            }
        } else {
            applyBackgroundFromIndex();
        }
        preview.invalidate();
    }

    private void applyBackgroundFromIndex() {
        int res = bgImageRes[bgImageIndex];
        if (res != 0) { preview.backgroundBitmap = BitmapFactory.decodeResource(getResources(), res); preview.useBackgroundColorFill = false; }
        else { preview.backgroundBitmap = null; preview.useBackgroundColorFill = true; }
    }

    private void saveToState() {
        ThumbnailState.INSTANCE.setBgImageIndex(bgImageIndex);
        ThumbnailState.INSTANCE.setBgColorIndex(bgColorIndex);
        ThumbnailState.INSTANCE.setBorderColorIndex(borderColorIndex);
        ThumbnailState.INSTANCE.setTextColorIndex(textColorIndex);
        ThumbnailState.INSTANCE.setTextOutlineIndex(textOutlineIndex);
        ThumbnailState.INSTANCE.setShadowIndex(shadowIndex);
        ThumbnailState.INSTANCE.setFontIndex(fontIndex);
        ThumbnailState.INSTANCE.setOutputWidth(outputWidth);
        ThumbnailState.INSTANCE.setOutputHeight(outputHeight);
        ThumbnailState.INSTANCE.setPickedImageUri(pickedImageUri);
        ThumbnailState.INSTANCE.setBrightness(preview.brightness);
        ThumbnailState.INSTANCE.setZoom(preview.zoom);
        ThumbnailState.INSTANCE.setPanX(preview.panX);
        ThumbnailState.INSTANCE.setPanY(preview.panY);
        ThumbnailState.INSTANCE.setTrimTop(preview.trimTop);
        ThumbnailState.INSTANCE.setTrimBottom(preview.trimBottom);
        ThumbnailState.INSTANCE.setTrimLeft(preview.trimLeft);
        ThumbnailState.INSTANCE.setTrimRight(preview.trimRight);
        ThumbnailState.INSTANCE.setTexts(preview.texts.clone());
        ThumbnailState.INSTANCE.setTextSizePx(preview.textSizePx.clone());
        ThumbnailState.INSTANCE.setTextOffsetX(preview.textOffsetX.clone());
        ThumbnailState.INSTANCE.setTextOffsetY(preview.textOffsetY.clone());
        ThumbnailState.INSTANCE.setHasData(true);
    }

    // 배경/텍스트/편집값을 전부 기본값으로 되돌리고 저장해 둔 상태도 지운다.
    private void resetAll() {
        bgImageIndex = 0; bgColorIndex = 2; borderColorIndex = 3;
        textColorIndex = 1; textOutlineIndex = 1; shadowIndex = 2; fontIndex = 0;
        outputWidth = 800; outputHeight = 800;
        pickedImageUri = null;
        applyPreviewStyle();
        preview.setAspectRatio(outputWidth / (float) outputHeight);
        preview.brightness = 0; preview.zoom = 1f; preview.panX = 0f; preview.panY = 0f;
        preview.trimTop = 0f; preview.trimBottom = 0f; preview.trimLeft = 0f; preview.trimRight = 0f;
        for (int i = 0; i < ThumbnailPreviewView.TEXT_SLOTS; i++) preview.texts[i] = "";
        preview.textSizePx[0] = 70f; preview.textSizePx[1] = 70f; preview.textSizePx[2] = 70f;
        preview.resetTextPosition();
        applyBackgroundFromIndex();
        preview.invalidate();
        ThumbnailState.INSTANCE.clear();
        status.setText("초기화되었습니다.");
        render();
    }

    private void render() {
        content.removeAllViews();
        for (int i = 0; i < 3; i++) { tabs[i].setBackground(background(i == tab ? GREEN : 0xffeaf1e9, 14)); tabs[i].setTextColor(i == tab ? Color.WHITE : GREEN); }
        if (tab == 0) renderBackgroundTab();
        else if (tab == 1) renderTextTab();
        else renderSaveTab();
    }

    private void renderBackgroundTab() {
        LinearLayout bgCard = card("BACKGROUND", "배경 · 테두리");
        bgCard.addView(button("이미지 선택 (갤러리)", true, this::pickImage));
        pickerRow(bgCard, "배경 이미지", bgImageLabels[bgImageIndex], () -> openPicker("배경 이미지", bgImageLabels, bgImageIndex, i -> {
            bgImageIndex = i; pickedImageUri = null;
            int res = bgImageRes[i];
            if (res != 0) { preview.backgroundBitmap = BitmapFactory.decodeResource(getResources(), res); preview.useBackgroundColorFill = false; }
            else { preview.backgroundBitmap = null; preview.useBackgroundColorFill = true; }
            preview.invalidate(); render();
        }));
        pickerRow(bgCard, "배경 색상", colorOptions.get(bgColorIndex).label, () -> openColorPicker(colorOptions, bgColorIndex, i -> {
            bgColorIndex = i; preview.backgroundColor = colorOptions.get(i).value; preview.invalidate(); render();
        }));
        pickerRow(bgCard, "테두리 색상", colorOptions.get(borderColorIndex).label, () -> openColorPicker(colorOptions, borderColorIndex, i -> {
            borderColorIndex = i; preview.borderColor = colorOptions.get(i).value; preview.invalidate(); render();
        }));

        LinearLayout editCard = card("PHOTO EDIT", "사진 편집");
        seekBar(editCard, "밝기", 200, preview.brightness + 100, p -> { preview.brightness = p - 100; preview.invalidate(); });
        seekBar(editCard, "확대(구도 조정)", 100, Math.round((preview.zoom - 1f) / 0.02f), p -> { preview.zoom = 1f + p * 0.02f; preview.invalidate(); });
        seekBar(editCard, "가로 이동", 100, Math.round(preview.panX * 50f + 50f), p -> { preview.panX = (p - 50) / 50f; preview.invalidate(); });
        seekBar(editCard, "세로 이동", 100, Math.round(preview.panY * 50f + 50f), p -> { preview.panY = (p - 50) / 50f; preview.invalidate(); });
        editCard.addView(text("가장자리 잘라내기 (워터마크 제거)", 10, MUTED)); gap(editCard, 2);
        LinearLayout trimRow = new LinearLayout(this); trimRow.setOrientation(LinearLayout.HORIZONTAL);
        float trim = 0.1f;
        ToggleButton tTop = toggle("상단 ✓", "상단", preview.trimTop > 0, checked -> { preview.trimTop = checked ? trim : 0f; preview.invalidate(); });
        ToggleButton tBottom = toggle("하단 ✓", "하단", preview.trimBottom > 0, checked -> { preview.trimBottom = checked ? trim : 0f; preview.invalidate(); });
        ToggleButton tLeft = toggle("좌측 ✓", "좌측", preview.trimLeft > 0, checked -> { preview.trimLeft = checked ? trim : 0f; preview.invalidate(); });
        ToggleButton tRight = toggle("우측 ✓", "우측", preview.trimRight > 0, checked -> { preview.trimRight = checked ? trim : 0f; preview.invalidate(); });
        for (ToggleButton t : new ToggleButton[]{tTop, tBottom, tLeft, tRight}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.leftMargin = dp(4); lp.rightMargin = dp(4); trimRow.addView(t, lp);
        }
        editCard.addView(trimRow);
    }

    private void renderTextTab() {
        LinearLayout textCard = card("TEXT", "텍스트 (칸 3개 · 각각 위치·크기 독립 조절)");
        String[] textLabels = {"텍스트 1 (기본 위치: 위)", "텍스트 2 (기본 위치: 가운데)", "텍스트 3 (기본 위치: 아래)"};
        for (int slot = 0; slot < ThumbnailPreviewView.TEXT_SLOTS; slot++) {
            final int i = slot;
            textCard.addView(text(textLabels[i], 10, MUTED));
            EditText input = new EditText(this); input.setText(preview.texts[i]); input.setHint("텍스트 입력"); input.setMinLines(1); input.setTextSize(12);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setBackground(background(BG, 8)); input.setPadding(dp(8), dp(6), dp(8), dp(6));
            input.addTextChangedListener(watcher(() -> { preview.texts[i] = input.getText().toString(); preview.invalidate(); }));
            textCard.addView(input, new LinearLayout.LayoutParams(-1, -2)); gap(textCard, 2);
            numberField(textCard, "이 텍스트만 글씨 크기(px)", Math.round(preview.textSizePx[i]), v -> { preview.textSizePx[i] = v; preview.invalidate(); });
        }
        pickerRow(textCard, "글씨체", fontLabels[fontIndex], () -> openPicker("글씨체", fontLabels, fontIndex, i -> {
            fontIndex = i; preview.typeface = getResources().getFont(fontRes[i]); preview.invalidate(); render();
        }));
        pickerRow(textCard, "글씨 색상", colorOptions.get(textColorIndex).label, () -> openColorPicker(colorOptions, textColorIndex, i -> {
            textColorIndex = i; preview.textColor = colorOptions.get(i).value; preview.invalidate(); render();
        }));
        pickerRow(textCard, "글씨 테두리", colorOptionsWithNone.get(textOutlineIndex).label, () -> openColorPicker(colorOptionsWithNone, textOutlineIndex, i -> {
            textOutlineIndex = i; preview.textOutlineColor = colorOptionsWithNone.get(i).value; preview.invalidate(); render();
        }));
        pickerRow(textCard, "그림자", shadowLabels[shadowIndex], () -> openPicker("그림자", shadowLabels, shadowIndex, i -> {
            shadowIndex = i; preview.textShadow = shadowValues[i]; preview.invalidate(); render();
        }));
    }

    private void renderSaveTab() {
        LinearLayout sizeCard = card("OUTPUT", "출력 크기 (리사이즈)");
        LinearLayout sizeRow = new LinearLayout(this); sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText widthInput = new EditText(this); widthInput.setText(String.valueOf(outputWidth)); widthInput.setHint("가로(px)"); widthInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        widthInput.setBackground(background(BG, 10)); widthInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        widthInput.addTextChangedListener(watcher(() -> { Integer v = toIntOrNull(widthInput.getText().toString()); if (v != null) { outputWidth = v; preview.setAspectRatio(outputWidth / (float) outputHeight); } }));
        sizeRow.addView(widthInput, new LinearLayout.LayoutParams(0, -2, 1));
        TextView x = text(" x ", 14, MUTED); x.setGravity(Gravity.CENTER); sizeRow.addView(x, new LinearLayout.LayoutParams(-2, -2));
        EditText heightInput = new EditText(this); heightInput.setText(String.valueOf(outputHeight)); heightInput.setHint("세로(px)"); heightInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        heightInput.setBackground(background(BG, 10)); heightInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        heightInput.addTextChangedListener(watcher(() -> { Integer v = toIntOrNull(heightInput.getText().toString()); if (v != null) { outputHeight = v; preview.setAspectRatio(outputWidth / (float) outputHeight); } }));
        sizeRow.addView(heightInput, new LinearLayout.LayoutParams(0, -2, 1));
        sizeCard.addView(sizeRow);

        LinearLayout saveCard = card("SAVE", "저장");
        saveCard.addView(button("저장 (갤러리)", true, this::saveThumbnail));
        Button copyBtn = button("클립보드에 복사", false, this::copyThumbnail);
        copyBtn.setBackground(background(0xffeaf1e9, 14));
        saveCard.addView(copyBtn);
    }

    private void applyPreviewStyle() {
        preview.textColor = colorOptions.get(textColorIndex).value;
        preview.borderColor = colorOptions.get(borderColorIndex).value;
        preview.textOutlineColor = colorOptionsWithNone.get(textOutlineIndex).value;
        preview.textShadow = shadowValues[shadowIndex];
        preview.typeface = getResources().getFont(fontRes[fontIndex]);
        preview.backgroundColor = colorOptions.get(bgColorIndex).value;
        preview.useBackgroundColorFill = true;
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI); intent.setType("image/*");
        try { startActivityForResult(intent, PICK_BG); } catch (ActivityNotFoundException error) { showError("사진 선택 앱을 찾을 수 없습니다."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || request != PICK_BG || data.getData() == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {}
        try {
            // 출력 크기(최대 4000px)보다 낮은 해상도로 불러오면 내보낼 때 확대(업스케일)되면서
            // 화질이 떨어진다. 여유 있게 큰 해상도로 디코드해서 어떤 출력 크기를 설정해도 흐려지지
            // 않게 한다.
            Bitmap bmp = ThumbnailCollage.decode(this, uri, 3200);
            pickedImageUri = uri; bgImageIndex = 0;
            preview.backgroundBitmap = bmp; preview.useBackgroundColorFill = false; preview.invalidate();
            render();
        } catch (IOException error) { showError("이미지를 읽지 못했습니다: " + error.getMessage()); }
    }

    private Bitmap renderExportBitmap() {
        int width = Math.max(50, Math.min(4000, outputWidth));
        int height = Math.max(50, Math.min(4000, outputHeight));
        return preview.renderToBitmap(width, height);
    }

    private void saveThumbnail() {
        Bitmap bitmap = renderExportBitmap();
        try {
            String filename = "thumbnail_" + System.currentTimeMillis() + ".png";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/전기블로그썸네일");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("저장 위치를 만들 수 없습니다.");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
            ThumbnailState.INSTANCE.setExportedThumbnailUri(uri);
            Toast.makeText(this, "갤러리에 저장되었습니다.", Toast.LENGTH_SHORT).show();
        } catch (Exception error) { Toast.makeText(this, "저장 실패: " + error.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private void copyThumbnail() {
        Bitmap bitmap = renderExportBitmap();
        try {
            File dir = new File(getCacheDir(), "thumbnails"); dir.mkdirs();
            File file = new File(dir, "clip_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            ThumbnailState.INSTANCE.setExportedThumbnailUri(uri);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), "thumbnail", uri));
            Toast.makeText(this, "클립보드에 이미지가 복사되었습니다.", Toast.LENGTH_SHORT).show();
        } catch (Exception error) { Toast.makeText(this, "복사 실패: " + error.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private void cacheThumbnailForImport() {
        try {
            Bitmap bitmap = renderExportBitmap();
            File dir = new File(getCacheDir(), "thumbnails"); dir.mkdirs();
            File file = new File(dir, "latest_for_article.png");
            try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
            bitmap.recycle();
            ThumbnailState.INSTANCE.setExportedThumbnailUri(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file));
        } catch (Exception ignored) {}
    }
    private void showError(String message) { status.setText(message); new AlertDialog.Builder(this).setTitle("확인이 필요해요").setMessage(message).setPositiveButton("확인", null).show(); }
}
