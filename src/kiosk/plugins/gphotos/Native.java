// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import android.graphics.Bitmap;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The native screensaver: photos decoded on the device and drawn by an Android view, with no page size limit.
 * The worker builds immutable scenes and the screen hands each one to the views on display, which draw it on
 * the main thread. Kiosk Satellite versions before native screensavers use the HTML pages in Slides instead.
 */
final class Native {
    private Native() {}

    /** A decoded photo, upright and no larger than the screen, with a small copy for the blurred background. */
    static final class Picture implements Slides.Frame {
        final Bitmap bitmap;
        final Bitmap blurred;
        final Album.Photo photo;
        /** Slow zoom origin, as percentages of the screen. */
        final int originX;
        final int originY;
        final int width;
        final int height;

        Picture(Bitmap bitmap, Bitmap blurred, Album.Photo photo, int width, int height, int originX, int originY) {
            this.bitmap = bitmap;
            this.blurred = blurred;
            this.photo = photo;
            this.width = width;
            this.height = height;
            this.originX = originX;
            this.originY = originY;
        }

        @Override public Album.Photo photo() { return photo; }
    }

    /** One photo as it appears on screen. Start is when it first showed, which drives its slow zoom. */
    static final class Layer {
        final Picture picture;
        final Slides.Fit fit;
        final String caption;
        final long start;

        Layer(Picture picture, Slides.Fit fit, String caption, long start) {
            this.picture = picture;
            this.fit = fit;
            this.caption = caption == null ? "" : caption;
            this.start = start;
        }
    }

    /** What the screen shows: a message, or a photo fading in over the previous one from fadeStart. */
    static final class Scene {
        final String title;
        final String detail;
        final Layer previous;
        final Layer current;
        final long fadeStart;
        final Slides.Style style;

        private Scene(String title, String detail, Layer previous, Layer current, long fadeStart, Slides.Style style) {
            this.title = title;
            this.detail = detail;
            this.previous = previous;
            this.current = current;
            this.fadeStart = fadeStart;
            this.style = style;
        }

        static Scene message(String title, String detail) {
            return new Scene(title, detail == null ? "" : detail, null, null, 0, null);
        }

        static Scene photo(Layer previous, Layer current, long fadeStart, Slides.Style style) {
            return new Scene(null, null, style.crossfade ? previous : null, current, fadeStart, style);
        }

        boolean isMessage() { return current == null; }
    }

    /** Turns a downloaded photo into a picture no larger than maxWidth x maxHeight. Tests replace it. */
    interface Decoder {
        Picture decode(byte[] image, int maxWidth, int maxHeight, Album.Photo photo, int originX, int originY) throws IOException;
    }

    /** A view on screen that draws scenes. Called from the worker, so implementations hand over to the main thread. */
    interface Display {
        void show(Scene scene);
    }

    /** Keeps the latest scene and passes it to every view on display, including ones created later. Thread safe. */
    static final class Screen {
        private final List<Display> displays = new CopyOnWriteArrayList<>();
        private volatile Scene scene;

        void show(Scene next) {
            scene = next;
            for (Display display : displays) display.show(next);
        }

        Scene scene() { return scene; }

        void attach(Display display) {
            displays.add(display);
            Scene current = scene;
            if (current != null) display.show(current);
        }

        void detach(Display display) { displays.remove(display); }

        int displays() { return displays.size(); }
    }
}
