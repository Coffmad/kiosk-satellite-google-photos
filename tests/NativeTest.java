// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.PluginHost;

/** The native screensaver: how the plugin chooses it, what it downloads and the scenes it hands to the views. */
public final class NativeTest {
    static final String SHORT = "https://photos.app.goo.gl/abc123";
    static final String LH3 = "https://lh3.googleusercontent.com/pw/";

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        check(condition.getAsBoolean(), message);
    }

    static String item(String id, int width, int height) {
        return "[\"" + id + "\",[\"" + LH3 + id + "\"," + width + "," + height + ",null,null,null,null,null,[null,null,1],[1000000]],"
            + "981173106000,\"exampleHash000\",3600000]";
    }

    /** A Google album of three photos whose downloads are far beyond the HTML page limit. */
    static final class FakeHttp implements Http {
        final List<String> requests = new CopyOnWriteArrayList<>();
        /** Far beyond an HTML page's limit unless a test lowers it. */
        int imageBytes = 900_000;
        @Override public Response request(String url, String body, String type, Map<String, String> headers, int max) {
            requests.add(url);
            if (url.equals(SHORT)) {
                String page = "<html><head><link rel=\"canonical\" href=\"https://photos.google.com/share/AF1QipAlbumIdentifier0123456789?key=Sharekey_0123-abc\">"
                    + "</head><script>AF_initDataCallback({data:[null,[" + item("AF1QipPhotoOne00000000", 4000, 3000) + ","
                    + item("AF1QipPhotoTwo00000000", 3000, 4000) + "," + item("AF1QipPhotoThree000000", 1920, 1080) + "]]});</script></html>";
                return new Response(200, "https://photos.google.com/share/AF1QipAlbumIdentifier0123456789?key=Sharekey_0123-abc",
                    page.getBytes(StandardCharsets.UTF_8));
            }
            if (url.startsWith("https://photos.google.com/_/PhotosUi/data/batchexecute")) return new Response(500, url, new byte[0]);
            if (url.startsWith(LH3)) {
                byte[] image = new byte[imageBytes];
                image[0] = (byte) 0xFF; image[1] = (byte) 0xD8;
                return new Response(200, url, image);
            }
            return new Response(404, url, new byte[0]);
        }
    }

    /** Stands in for Android's decoder, which cannot run off a device. */
    static final class FakeDecoder implements Native.Decoder {
        final List<String> calls = new CopyOnWriteArrayList<>();
        @Override public Native.Picture decode(byte[] image, int maxWidth, int maxHeight, Album.Photo photo, int originX, int originY) {
            calls.add(image.length + ":" + maxWidth + "x" + maxHeight);
            return new Native.Picture(null, null, photo, maxWidth, maxHeight, originX, originY);
        }
    }

    static class Host implements PluginHost {
        volatile OverlayFactory factory;
        volatile String nativeKey;
        final List<String> pages = new CopyOnWriteArrayList<>();
        volatile String status;
        @Override public void publishScreensaverView(String key, String title, OverlayFactory factory) {
            check(GooglePhotosPlugin.KEY.equals(key) && GooglePhotosPlugin.TITLE.equals(title), "Same key and title as the HTML renderer");
            this.nativeKey = key;
            this.factory = factory;
        }
        @Override public void publishScreensaver(String key, String title, String html) { pages.add(html); }
        @Override public void subscribe(String event) {}
        @Override public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            if ("getDeviceInfo".equals(command)) {
                Map<String, Object> info = new HashMap<>();
                info.put("screenWidth", 2560); info.put("screenHeight", 1600);
                callback.onResult(true, info, null);
            } else callback.onResult(true, false, null);
        }
        @Override public void status(String message, boolean error) { status = message; }
        @Override public void showWindow(String title, String message, String buttonLabel) {}
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
    }

    /** A Kiosk Satellite build from before native screensavers, whose interface lacks the method. */
    static final class OldHost extends Host {
        @Override public void publishScreensaverView(String key, String title, OverlayFactory factory) {
            throw new AbstractMethodError("publishScreensaverView");
        }
    }

    static Map<String, Object> settings(String links) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("albumLinks", links);
        settings.put("interval", 30);
        settings.put("transition", "Crossfade");
        settings.put("fadeSeconds", 1.5);
        settings.put("horizontalFit", GooglePhotosPlugin.FIT_FILL);
        settings.put("verticalFit", GooglePhotosPlugin.FIT_BLUR);
        settings.put("maxSize", "2560 px");
        settings.put("dateCaption", "Off");
        return settings;
    }

    /** Records scenes as a view would receive them. */
    static final class Recorder implements Native.Display {
        final List<Native.Scene> scenes = new CopyOnWriteArrayList<>();
        @Override public void show(Native.Scene scene) { scenes.add(scene); }
        Native.Scene last() { return scenes.isEmpty() ? null : scenes.get(scenes.size() - 1); }
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.US);
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        nativeSlideshow();
        fallbackOnOlderHosts();
        screenKeepsLatestScene();
        System.out.println("NativeTest passed");
    }

    /** The Screen inside the plugin, through the factory's constructor argument. */
    static Native.Screen screenOf(OverlayFactory factory) throws Exception {
        java.lang.reflect.Field field = SlideshowView.Factory.class.getDeclaredField("screen");
        field.setAccessible(true);
        return (Native.Screen) field.get(factory);
    }

    static void nativeSlideshow() throws Exception {
        FakeHttp http = new FakeHttp();
        FakeDecoder decoder = new FakeDecoder();
        Host host = new Host();
        GooglePhotosPlugin plugin = new GooglePhotosPlugin(http, new SourcesTest.FakeShrinker(), decoder);
        try {
            plugin.start(host, settings(SHORT));
            check(host.factory instanceof SlideshowView.Factory, "Native renderer published");
            Native.Screen screen = screenOf(host.factory);
            Recorder view = new Recorder();
            screen.attach(view);
            await(() -> view.last() != null && !view.last().isMessage(), "First photo shown natively");
            check(host.pages.isEmpty(), "No HTML page ever replaces the native renderer");
            check(view.scenes.get(0).isMessage() && "Loading photos...".equals(view.scenes.get(0).title), "Loading message first");

            Native.Scene first = view.last();
            check(first.previous == null && first.current.picture.photo != null, "First photo has nothing to fade from");
            // 900 KB downloads would not fit an HTML page. Natively they are decoded as they are, with no retries.
            check(decoder.calls.get(0).startsWith("900000:"), "Full download decoded: " + decoder.calls);
            for (String url : http.requests) {
                if (url.startsWith(LH3)) check(!url.contains("-l"), "Requested at the service's full quality: " + url);
                // The 4:3 photo fills the 2560x1600 screen, so it is requested to cover it.
                if (url.contains("PhotoOne")) check(url.endsWith("=w2560-h1920-rw"), "Cover size for a filled photo: " + url);
                if (url.contains("PhotoTwo")) check(url.endsWith("=w2560-h1600-rw"), "Screen size for a whole photo: " + url);
            }
            int lh3 = 0;
            for (String url : http.requests) if (url.startsWith(LH3)) lh3++;
            await(() -> decoder.calls.size() >= 2, "Next photo prefetched");
            check(lh3 <= 2, "One download per photo, no size retries: " + lh3);

            int before = view.scenes.size();
            plugin.execute("next", Collections.<String, Object>emptyMap());
            await(() -> view.scenes.size() > before, "Next photo");
            Native.Scene second = view.last();
            check(second.previous != null && second.previous.picture == first.current.picture, "Fades from the photo before");
            check(second.previous.start == first.current.start, "The previous photo keeps its zoom progress");
            check(second.fadeStart == second.current.start && second.current.start >= first.current.start, "Fade starts with the new photo");
            check(second.style.crossfade && second.style.fadeSeconds == 1.5, "Style carried in the scene");

            Map<String, Object> cut = settings(SHORT);
            cut.put("transition", "Cut");
            cut.put("horizontalFit", GooglePhotosPlugin.FIT_PLAIN);
            cut.put("verticalFit", GooglePhotosPlugin.FIT_PLAIN);
            int count = view.scenes.size();
            plugin.configure(cut);
            await(() -> view.scenes.size() > count, "Redrawn after a settings change");
            Native.Scene redrawn = view.last();
            check(redrawn.previous == null && redrawn.current.picture == second.current.picture, "Same photo redrawn");
            check(redrawn.current.start == second.current.start, "Redraw keeps the photo's zoom progress");
            check(redrawn.current.fit == Slides.Fit.PLAIN && !redrawn.style.crossfade, "New style applied");

            plugin.configure(settings(""));
            await(() -> view.last().isMessage() && view.last().detail.contains("Paste a shared album link"), "Setup message drawn natively");
            check(host.pages.isEmpty(), "Still no HTML pages");
        } finally {
            plugin.stop();
        }
    }

    static void fallbackOnOlderHosts() throws Exception {
        FakeHttp http = new FakeHttp();
        http.imageBytes = 50_000;
        FakeDecoder decoder = new FakeDecoder();
        OldHost host = new OldHost();
        GooglePhotosPlugin plugin = new GooglePhotosPlugin(http, new SourcesTest.FakeShrinker(), decoder);
        try {
            plugin.start(host, settings(SHORT));
            await(() -> !host.pages.isEmpty() && host.pages.get(host.pages.size() - 1).contains("data:image"), "HTML slideshow on an older host");
            check(decoder.calls.isEmpty(), "No native decoding on an older host");
        } finally {
            plugin.stop();
        }
    }

    static void screenKeepsLatestScene() {
        Native.Screen screen = new Native.Screen();
        screen.show(Native.Scene.message("One", ""));
        screen.show(Native.Scene.message("Two", "detail"));
        Recorder late = new Recorder();
        screen.attach(late);
        check(late.scenes.size() == 1 && "Two".equals(late.last().title), "A view created later gets the latest scene at once");
        screen.show(Native.Scene.message("Three", ""));
        check(late.scenes.size() == 2, "Attached views get new scenes");
        screen.detach(late);
        screen.show(Native.Scene.message("Four", ""));
        check(late.scenes.size() == 2 && screen.displays() == 0, "Destroyed views get nothing more");

        Album.Photo photo = new Album.Photo("p", LH3 + "p", 100, 100, 0);
        Native.Picture a = new Native.Picture(null, null, photo, 100, 100, 50, 50);
        Native.Layer previous = new Native.Layer(a, Slides.Fit.FILL, "", 1);
        Native.Layer current = new Native.Layer(a, Slides.Fit.BLUR, null, 2);
        Slides.Style cut = new Slides.Style(false, 1.5, false, 0.1, 30, 50, "#000000");
        check(Native.Scene.photo(previous, current, 2, cut).previous == null, "Cut drops the previous photo");
        check("".equals(current.caption), "No caption is an empty caption");
    }
}
