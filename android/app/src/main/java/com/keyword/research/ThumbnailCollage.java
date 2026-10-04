package com.keyword.research;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 사진 2/3/4장을 콜라주 한 장으로 합친다.
 *  ImageDecoder는 JPEG/HEIC의 EXIF 회전을 자동으로 반영해서 디코드하므로 별도 회전 처리가 필요 없다.
 *  출력 캔버스는 정사각형이 아니라 폰 사진 원본에 가까운 세로 비율(3:4)을 쓴다 - 정사각형으로 만들면
 *  세로로 찍은 사진(대부분)이 2x2 칸에 들어갈 때 양옆이 많이 잘려나가는데, 세로 비율 캔버스를 쓰면
 *  4장짜리 콜라주의 칸 하나(가로/2, 세로/2)가 3:4에 가까워져 원본 사진을 거의 그대로 담을 수 있다. */
public final class ThumbnailCollage {
    public static final int WIDTH = 1600;
    public static final int HEIGHT = 2134;
    public static final int GAP = 10;
    public static final int[] ALLOWED_PER_COLLAGE = {2, 3, 4};

    /** 사진 편집(밝기/확대·구도/가장자리 잘라내기)을 모든 칸에 동일하게 적용한다. */
    public static final class PhotoEdit {
        public int brightness = 0; // -100..100
        public float zoom = 1f; // 1..3
        public float panX = 0f, panY = 0f; // -1..1
        public float trimTop = 0f, trimBottom = 0f, trimLeft = 0f, trimRight = 0f; // 0..0.3
    }

    public static Bitmap decode(Context context, Uri uri, int maxSide) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri), (decoder, info, source) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            float scale = Math.min(1f, (float) maxSide / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    /** 일반 갤러리/메신저 앱이 사진 여러 장을 묶을 때 쓰는 배치. */
    private static List<RectF> cellRects(int perCollage) {
        List<RectF> cells = new ArrayList<>();
        if (perCollage == 4) {
            for (int i = 0; i < 4; i++) {
                float left = (i % 2) * (WIDTH / 2f), top = (i / 2) * (HEIGHT / 2f);
                cells.add(new RectF(left, top, left + WIDTH / 2f, top + HEIGHT / 2f));
            }
        } else if (perCollage == 2) {
            for (int i = 0; i < 2; i++) {
                float top = i * (HEIGHT / 2f);
                cells.add(new RectF(0, top, WIDTH, top + HEIGHT / 2f));
            }
        } else if (perCollage == 3) {
            // 일반 갤러리 앱이 사진 3장을 묶을 때 흔히 쓰는 배치: 위 2장은 나란히, 아래 1장은 전체 너비.
            cells.add(new RectF(0, 0, WIDTH / 2f, HEIGHT / 2f));
            cells.add(new RectF(WIDTH / 2f, 0, WIDTH, HEIGHT / 2f));
            cells.add(new RectF(0, HEIGHT / 2f, WIDTH, HEIGHT));
        } else {
            throw new IllegalArgumentException("콜라주 1개당 사진은 2, 3, 4장 중 하나여야 합니다.");
        }
        return cells;
    }

    public static Bitmap render(Context context, List<Uri> photos, int perCollage, PhotoEdit edit) throws IOException {
        if (perCollage != 2 && perCollage != 3 && perCollage != 4) throw new IOException("콜라주 1개당 사진은 2, 3, 4장 중 하나여야 합니다.");
        if (photos.size() != perCollage) throw new IOException("콜라주 1개에는 사진 " + perCollage + "장이 필요합니다.");
        if (edit == null) edit = new PhotoEdit();
        List<RectF> cells = cellRects(perCollage);
        Bitmap output = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.WHITE);
        for (int cell = 0; cell < perCollage; cell++) {
            // 칸 하나가 최대 SIZE(1600)px까지 필요할 수 있는데(2장/3장 배치의 넓은 칸), 그보다
            // 낮은 해상도로 불러오면 칸을 채우려고 확대(업스케일)되면서 화질이 눈에 띄게 떨어진다.
            // 확대(zoom) 편집 여유까지 감안해 SIZE보다 넉넉하게 디코드한다.
            Bitmap image = decode(context, photos.get(cell), 2000);
            try { drawCover(canvas, image, cells.get(cell), edit); } finally { image.recycle(); }
        }
        return output;
    }

    /** 일반 갤러리/메신저 앱이 사진 여러 장을 그리드로 묶을 때 쓰는 방식과 같다: 칸을 여백 없이
     *  꽉 채우도록 사진을 크롭해서 채운다(cover-fit). 확대(zoom)는 이 상태에서 더 파고들어 보여주는
     *  용도이고, 가로/세로 이동으로 어느 부분을 자를지 고른다. */
    private static void drawCover(Canvas canvas, Bitmap bmp, RectF cellBounds, PhotoEdit edit) {
        float cellW = cellBounds.width() - GAP * 2, cellH = cellBounds.height() - GAP * 2;
        int trimLeftPx = (int) (bmp.getWidth() * clampTrim(edit.trimLeft));
        int trimRightPx = (int) (bmp.getWidth() * clampTrim(edit.trimRight));
        int trimTopPx = (int) (bmp.getHeight() * clampTrim(edit.trimTop));
        int trimBottomPx = (int) (bmp.getHeight() * clampTrim(edit.trimBottom));
        int left0 = trimLeftPx, top0 = trimTopPx;
        int right0 = Math.max(left0 + 1, bmp.getWidth() - trimRightPx);
        int bottom0 = Math.max(top0 + 1, bmp.getHeight() - trimBottomPx);
        int trimmedW = right0 - left0, trimmedH = bottom0 - top0;

        float bmpRatio = (float) trimmedW / trimmedH, cellRatio = cellW / cellH;
        int srcW, srcH;
        if (bmpRatio > cellRatio) { srcH = trimmedH; srcW = Math.max(1, (int) (trimmedH * cellRatio)); }
        else { srcW = trimmedW; srcH = Math.max(1, (int) (trimmedW / cellRatio)); }
        float z = Math.max(1f, Math.min(3f, edit.zoom));
        srcW = Math.min(trimmedW, Math.max(1, (int) (srcW / z)));
        srcH = Math.min(trimmedH, Math.max(1, (int) (srcH / z)));

        int maxOffsetX = trimmedW - srcW, maxOffsetY = trimmedH - srcH;
        int panOffsetX = (int) (maxOffsetX / 2f * Math.max(-1f, Math.min(1f, edit.panX)));
        int panOffsetY = (int) (maxOffsetY / 2f * Math.max(-1f, Math.min(1f, edit.panY)));
        int left = clampInt(left0 + maxOffsetX / 2 + panOffsetX, left0, right0 - srcW);
        int top = clampInt(top0 + maxOffsetY / 2 + panOffsetY, top0, bottom0 - srcH);

        Rect src = new Rect(left, top, left + srcW, top + srcH);
        RectF dest = new RectF(cellBounds.left + GAP, cellBounds.top + GAP, cellBounds.right - GAP, cellBounds.bottom - GAP);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (edit.brightness != 0) {
            float b = Math.max(-100, Math.min(100, edit.brightness)) * 2.55f;
            ColorMatrix cm = new ColorMatrix(new float[]{
                1, 0, 0, 0, b,
                0, 1, 0, 0, b,
                0, 0, 1, 0, b,
                0, 0, 0, 1, 0
            });
            paint.setColorFilter(new ColorMatrixColorFilter(cm));
        }
        canvas.drawBitmap(bmp, src, dest, paint);
    }
    private static float clampTrim(float v) { return Math.max(0f, Math.min(0.45f, v)); }
    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private ThumbnailCollage() {}
}
