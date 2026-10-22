// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

/**
 * Draws native screensaver scenes: each photo filled or fitted, over its blurred copy or a plain color, slowly
 * zooming and fading in over the one before. Timing comes from the scene, so a view created halfway through a
 * photo, such as when the screensaver starts, shows it exactly as far along as it is.
 */
final class SlideshowView extends View implements Native.Display {
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint();
    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint detailPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private KsTheme theme;
    private Native.Scene scene;
    private boolean visible;
    private boolean failed;

    SlideshowView(Context context, KsTheme theme) {
        super(context);
        this.theme = theme;
        // The blurred copy is darkened to half brightness, like the HTML renderer's brightness(.5).
        ColorMatrix darker = new ColorMatrix();
        darker.setScale(0.5f, 0.5f, 0.5f, 1f);
        blurPaint.setColorFilter(new ColorMatrixColorFilter(darker));
        captionPaint.setColor(Color.WHITE);
        titlePaint.setColor(0xFFEEEEEE);
        detailPaint.setColor(0xFFBBBBBB);
        applyTheme();
    }

    void setTheme(KsTheme next) {
        theme = next;
        applyTheme();
        invalidate();
    }

    private void applyTheme() {
        captionPaint.setTypeface(theme.typeface(400));
        titlePaint.setTypeface(theme.typeface(400));
        detailPaint.setTypeface(theme.typeface(400));
    }

    /** From the worker thread. */
    @Override
    public void show(Native.Scene next) {
        post(() -> {
            scene = next;
            failed = false;
            invalidate();
        });
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // Animation stops while the screen is off or the screensaver is covered, and resumes where it should be.
        visible = visibility == VISIBLE;
        if (visible) invalidate();
    }

    /**
     * Kiosk Satellite disables a plugin whose screensaver view throws, so a drawing failure shows black instead and is
     * logged once, keeping the slideshow and the plugin running.
     */
    @Override
    protected void onDraw(Canvas canvas) {
        try {
            draw(canvas, scene);
        } catch (RuntimeException failure) {
            canvas.drawColor(Color.BLACK);
            if (!failed) {
                failed = true;
                android.util.Log.w("SharedAlbumScreensaver", "Could not draw the slide", failure);
            }
        }
    }

    private void draw(Canvas canvas, Native.Scene current) {
        int width = getWidth(), height = getHeight();
        if (current == null || width == 0 || height == 0) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        if (current.isMessage()) {
            canvas.drawColor(Color.BLACK);
            drawMessage(canvas, current, width, height);
            return;
        }
        Slides.Style style = current.style;
        int background = parse(style.background);
        canvas.drawColor(background);
        long now = System.currentTimeMillis();
        double fadeMs = style.crossfade ? style.fadeSeconds * 1000 : 0;
        double shown = current.previous == null || fadeMs <= 0 ? 1 : clamp((now - current.fadeStart) / fadeMs);
        boolean animating = false;
        if (current.previous != null && shown < 1) {
            drawLayer(canvas, current.previous, style, background, 255, now, width, height);
            animating = true;
        }
        drawLayer(canvas, current.current, style, background, (int) Math.round(255 * shown), now, width, height);
        // Redraw each frame only while something moves: a fade, or a zoom that has not reached its end.
        if (style.zoom && now - current.current.start < zoomMillis(style)) animating = true;
        if (animating && visible) postInvalidateOnAnimation();
    }

    /** A photo zooms over its whole time on screen, including both fades, like the HTML renderer. */
    private static double zoomMillis(Slides.Style style) {
        return Math.max(1, (style.intervalSeconds + (style.crossfade ? style.fadeSeconds * 2 : 0)) * 1000);
    }

    private void drawLayer(Canvas canvas, Native.Layer layer, Slides.Style style, int background, int alpha,
                           long now, int width, int height) {
        Native.Picture picture = layer.picture;
        if (picture.bitmap == null || alpha <= 0) return;
        // Each photo brings its own background, so an incoming photo covers the previous one completely.
        fillPaint.setColor(background);
        fillPaint.setAlpha(alpha);
        canvas.drawRect(0, 0, width, height, fillPaint);
        if (layer.fit == Slides.Fit.BLUR && picture.blurred != null) {
            // The blurred copy covers the screen, enlarged a fifth so its soft edges stay out of view.
            cover(picture.blurred.getWidth(), picture.blurred.getHeight(), width, height, 50);
            canvas.save();
            canvas.scale(1.2f, 1.2f, width / 2f, height / 2f);
            blurPaint.setAlpha(alpha);
            canvas.drawBitmap(picture.blurred, null, rect, blurPaint);
            canvas.restore();
        }
        if (layer.fit == Slides.Fit.FILL) cover(picture.width, picture.height, width, height, style.focusY);
        else contain(picture.width, picture.height, width, height);
        canvas.save();
        if (style.zoom) {
            float scale = (float) (1 + style.zoomAmount * clamp((now - layer.start) / zoomMillis(style)));
            canvas.scale(scale, scale, width * picture.originX / 100f, height * picture.originY / 100f);
        }
        photoPaint.setAlpha(alpha);
        canvas.drawBitmap(picture.bitmap, null, rect, photoPaint);
        canvas.restore();
        if (!layer.caption.isEmpty()) drawCaption(canvas, layer.caption, alpha, width, height);
    }

    /** The photo scaled to cover the screen, cropped around focusY: 0 keeps the top, 50 the middle. */
    private void cover(int photoWidth, int photoHeight, int width, int height, int focusY) {
        float scale = Math.max((float) width / photoWidth, (float) height / photoHeight);
        float w = photoWidth * scale, h = photoHeight * scale;
        float left = (width - w) / 2, top = (height - h) * focusY / 100f;
        rect.set(left, top, left + w, top + h);
    }

    /** The whole photo, centered. */
    private void contain(int photoWidth, int photoHeight, int width, int height) {
        float scale = Math.min((float) width / photoWidth, (float) height / photoHeight);
        float w = photoWidth * scale, h = photoHeight * scale;
        float left = (width - w) / 2, top = (height - h) / 2;
        rect.set(left, top, left + w, top + h);
    }

    /** Bottom right, like the HTML renderer: 2.6% of the short side, 3% in from the edges, with a soft shadow. */
    private void drawCaption(Canvas canvas, String caption, int alpha, int width, int height) {
        float unit = Math.min(width, height) / 100f;
        captionPaint.setTextSize(2.6f * unit);
        captionPaint.setShadowLayer(1.5f * unit, 0, 0, Color.BLACK);
        captionPaint.setAlpha((int) (alpha * 0.85f));
        float textWidth = captionPaint.measureText(caption);
        canvas.drawText(caption, width - 3 * unit - textWidth, height - 3 * unit - captionPaint.descent(), captionPaint);
    }

    private void drawMessage(Canvas canvas, Native.Scene message, int width, int height) {
        float unit = Math.min(width, height) / 100f;
        int textWidth = Math.max(1, (int) (width * 0.8f));
        titlePaint.setTextSize(4 * unit);
        detailPaint.setTextSize(2.6f * unit);
        StaticLayout title = layout(message.title, titlePaint, textWidth);
        StaticLayout detail = message.detail.isEmpty() ? null : layout(message.detail, detailPaint, textWidth);
        float total = title.getHeight() + (detail == null ? 0 : 2 * unit + detail.getHeight());
        canvas.save();
        canvas.translate((width - textWidth) / 2f, (height - total) / 2);
        title.draw(canvas);
        if (detail != null) {
            canvas.translate(0, title.getHeight() + 2 * unit);
            detail.draw(canvas);
        }
        canvas.restore();
    }

    private static StaticLayout layout(String text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0, 1.3f).build();
    }

    private static int parse(String color) {
        try {
            return Color.parseColor(color);
        } catch (IllegalArgumentException bad) {
            return Color.BLACK;
        }
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }

    /** Builds a view each time the screensaver shows and connects it to the plugin's scenes. */
    static final class Factory implements OverlayFactory {
        private final Native.Screen screen;

        Factory(Native.Screen screen) { this.screen = screen; }

        @Override public View create(Context context, KsTheme theme) {
            SlideshowView view = new SlideshowView(context, theme);
            screen.attach(view);
            return view;
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            if (view instanceof SlideshowView) ((SlideshowView) view).setTheme(theme);
        }

        @Override public void onDestroy(View view) {
            if (view instanceof SlideshowView) screen.detach((SlideshowView) view);
        }
    }
}
