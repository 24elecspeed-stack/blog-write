package com.keyword.research;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** "블로그 대표사진 만들기": 배경(이미지/색) + 테두리 + 자유롭게 끌어서 옮길 수 있는 텍스트 칸 3개를
 *  캔버스에 그린다. 사진 편집(밝기/확대·구도/가장자리 잘라내기)도 여기서 처리한다. */
public final class ThumbnailPreviewView extends View {
    public enum TextShadow { NONE, DEFAULT, STRONG }
    public static final int TEXT_SLOTS = 3;

    public Bitmap backgroundBitmap;
    public int backgroundColor = Color.parseColor("#FFD700");
    public boolean useBackgroundColorFill = true;
    public Integer borderColor = Color.RED;
    public final String[] texts = {"", "", ""};
    public int textColor = Color.WHITE;
    public Integer textOutlineColor = Color.BLACK;
    public TextShadow textShadow = TextShadow.STRONG;
    public final float[] textSizePx = {70f, 70f, 70f};
    public Typeface typeface;

    // 사진 편집: brightness -100~100, zoom 1~3, panX/panY -1~1, trim* 0~0.3(가장자리 비율)
    public int brightness = 0;
    public float zoom = 1f;
    public float panX = 0f;
    public float panY = 0f;
    public float trimTop = 0f, trimBottom = 0f, trimLeft = 0f, trimRight = 0f;

    // 텍스트 자유 이동: 화면 중심 기준 -1~1 비율로 저장해 미리보기와 내보내기 해상도가 달라도
    // 같은 상대 위치를 유지한다. 칸 3개가 겹치지 않도록 기본 세로 위치를 위/가운데/아래로 나눈다.
    public final float[] textOffsetX = {0f, 0f, 0f};
    public final float[] textOffsetY = {-0.32f, 0f, 0.32f};
    private final RectF[] lastTextBounds = new RectF[TEXT_SLOTS];
    private int draggingSlot = -1;
    private float lastTouchX, lastTouchY;

    // 미리보기 화면의 가로세로 비율을 실제 저장/복사 시 출력 크기(예: 1:1)와 똑같이 맞춘다.
    // 이게 안 맞으면 편집 중에는 넓적하게 보이다가 막상 내보낼 때(정사각형 등)는 구도가 다르게
    // 잘려서 "사진이 이상하게 들어간다"는 문제가 생긴다. setAspectRatio(가로/세로)를 호출해서 갱신한다.
    private float aspectRatio = 1f;
    public void setAspectRatio(float widthOverHeight) {
        if (widthOverHeight > 0f && Math.abs(widthOverHeight - aspectRatio) > 0.001f) {
            aspectRatio = widthOverHeight;
            requestLayout();
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.max(1, Math.round(width / aspectRatio));
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    public ThumbnailPreviewView(Context context, AttributeSet attrs) { super(context, attrs); }
    public ThumbnailPreviewView(Context context) { super(context); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                for (int i = TEXT_SLOTS - 1; i >= 0; i--) {
                    if (lastTextBounds[i] != null && lastTextBounds[i].contains(event.getX(), event.getY())) {
                        draggingSlot = i; lastTouchX = event.getX(); lastTouchY = event.getY();
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                }
                return super.onTouchEvent(event);
            case MotionEvent.ACTION_MOVE:
                if (draggingSlot < 0) return super.onTouchEvent(event);
                float dx = event.getX() - lastTouchX, dy = event.getY() - lastTouchY;
                lastTouchX = event.getX(); lastTouchY = event.getY();
                if (getWidth() > 0) textOffsetX[draggingSlot] = clamp(textOffsetX[draggingSlot] + dx / (getWidth() / 2f));
                if (getHeight() > 0) textOffsetY[draggingSlot] = clamp(textOffsetY[draggingSlot] + dy / (getHeight() / 2f));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                if (draggingSlot >= 0) { draggingSlot = -1; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); return true; }
        }
        return super.onTouchEvent(event);
    }
    private static float clamp(float v) { return Math.max(-1f, Math.min(1f, v)); }
    public void resetTextPosition() {
        float[] defaultY = {-0.32f, 0f, 0.32f};
        for (int i = 0; i < TEXT_SLOTS; i++) { textOffsetX[i] = 0f; textOffsetY[i] = defaultY[i]; }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawContent(canvas, getWidth(), getHeight());
    }

    public void drawContent(Canvas canvas, float w, float h) {
        if (backgroundBitmap != null) drawBitmapCover(canvas, backgroundBitmap, w, h);
        else if (useBackgroundColorFill) canvas.drawColor(backgroundColor);

        if (borderColor != null) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE); p.setColor(borderColor); p.setStrokeWidth(Math.min(w, h) * 0.02f);
            canvas.drawRect(p.getStrokeWidth() / 2, p.getStrokeWidth() / 2, w - p.getStrokeWidth() / 2, h - p.getStrokeWidth() / 2, p);
        }
        for (int i = 0; i < TEXT_SLOTS; i++) {
            if (texts[i] != null && !texts[i].trim().isEmpty()) drawCenteredText(canvas, w, h, i);
            else lastTextBounds[i] = null;
        }
    }

    private void drawBitmapCover(Canvas canvas, Bitmap bmp, float w, float h) {
        int trimLeftPx = (int) (bmp.getWidth() * clampTrim(trimLeft));
        int trimRightPx = (int) (bmp.getWidth() * clampTrim(trimRight));
        int trimTopPx = (int) (bmp.getHeight() * clampTrim(trimTop));
        int trimBottomPx = (int) (bmp.getHeight() * clampTrim(trimBottom));
        int left0 = trimLeftPx, top0 = trimTopPx;
        int right0 = Math.max(left0 + 1, bmp.getWidth() - trimRightPx);
        int bottom0 = Math.max(top0 + 1, bmp.getHeight() - trimBottomPx);
        int trimmedW = right0 - left0, trimmedH = bottom0 - top0;

        float bmpRatio = (float) trimmedW / trimmedH, viewRatio = w / h;
        int srcW, srcH;
        if (bmpRatio > viewRatio) { srcH = trimmedH; srcW = Math.max(1, (int) (trimmedH * viewRatio)); }
        else { srcW = trimmedW; srcH = Math.max(1, (int) (trimmedW / viewRatio)); }
        float z = Math.max(1f, Math.min(3f, zoom));
        srcW = Math.min(trimmedW, Math.max(1, (int) (srcW / z)));
        srcH = Math.min(trimmedH, Math.max(1, (int) (srcH / z)));

        int maxOffsetX = trimmedW - srcW, maxOffsetY = trimmedH - srcH;
        int panOffsetX = (int) (maxOffsetX / 2f * Math.max(-1f, Math.min(1f, panX)));
        int panOffsetY = (int) (maxOffsetY / 2f * Math.max(-1f, Math.min(1f, panY)));
        int left = clampInt(left0 + maxOffsetX / 2 + panOffsetX, left0, right0 - srcW);
        int top = clampInt(top0 + maxOffsetY / 2 + panOffsetY, top0, bottom0 - srcH);

        Rect src = new Rect(left, top, left + srcW, top + srcH);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (brightness != 0) {
            float b = Math.max(-100, Math.min(100, brightness)) * 2.55f;
            ColorMatrix cm = new ColorMatrix(new float[]{
                1, 0, 0, 0, b,
                0, 1, 0, 0, b,
                0, 0, 1, 0, b,
                0, 0, 0, 1, 0
            });
            paint.setColorFilter(new ColorMatrixColorFilter(cm));
        }
        canvas.drawBitmap(bmp, src, new RectF(0, 0, w, h), paint);
    }
    private static float clampTrim(float v) { return Math.max(0f, Math.min(0.45f, v)); }
    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // 글씨 크기(px)는 "가로/세로 800px로 저장할 때" 기준으로 사용자가 입력한 값이다. 미리보기 화면은
    // 실제 저장 크기보다 훨씬 작게 표시되는데(스크롤 없이 다 보이게 하려고 줄임), 이 값을 그대로
    // 픽셀 단위로 쓰면 작은 미리보기에서는 엄청 크게 보이고 실제로 저장하면 작게 나온다 - 미리보기와
    // 저장 결과가 다르게 보였던 원인. 현재 그려지는 캔버스 크기(w,h)를 기준 크기(800)와 비교한
    // 비율만큼 실제로 곱해서 그려야 미리보기에서 보이는 크기와 저장 결과가 일치한다.
    private static final float TEXT_SIZE_REFERENCE = 800f;
    private void drawCenteredText(Canvas canvas, float w, float h, int slot) {
        String[] lines = texts[slot].split("\n", -1);
        float size = textSizePx[slot] * (Math.min(w, h) / TEXT_SIZE_REFERENCE);
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setTextSize(size); if (typeface != null) strokePaint.setTypeface(typeface);
        strokePaint.setTextAlign(Paint.Align.CENTER); strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(size * 0.08f); strokePaint.setColor(textOutlineColor != null ? textOutlineColor : Color.TRANSPARENT);

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setTextSize(size); if (typeface != null) fillPaint.setTypeface(typeface);
        fillPaint.setTextAlign(Paint.Align.CENTER); fillPaint.setStyle(Paint.Style.FILL); fillPaint.setColor(textColor);
        switch (textShadow) {
            case NONE: fillPaint.clearShadowLayer(); break;
            case DEFAULT: fillPaint.setShadowLayer(4f, 2f, 2f, Color.argb(128, 0, 0, 0)); break;
            case STRONG: fillPaint.setShadowLayer(8f, 4f, 4f, Color.argb(180, 0, 0, 0)); break;
        }

        float lineHeight = fillPaint.getFontSpacing();
        float totalHeight = lineHeight * lines.length;
        float centerX = w / 2f + textOffsetX[slot] * (w / 2f);
        float centerY = h / 2f + textOffsetY[slot] * (h / 2f);
        float y = centerY - totalHeight / 2f + lineHeight * 0.75f;
        float x = centerX;
        float maxWidth = 0f;
        for (String line : lines) maxWidth = Math.max(maxWidth, fillPaint.measureText(line));
        float padding = size * 0.4f;
        lastTextBounds[slot] = new RectF(x - maxWidth / 2f - padding, centerY - totalHeight / 2f - padding, x + maxWidth / 2f + padding, centerY + totalHeight / 2f + padding);
        for (String line : lines) {
            if (textOutlineColor != null) canvas.drawText(line, x, y, strokePaint);
            canvas.drawText(line, x, y, fillPaint);
            y += lineHeight;
        }
    }

    /** 미리보기 화면 크기와 무관하게 targetW x targetH로 다시 그려서 저장/복사용 고화질 비트맵을 만든다. */
    public Bitmap renderToBitmap(int targetW, int targetH) {
        Bitmap bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawContent(canvas, targetW, targetH);
        return bitmap;
    }
}
