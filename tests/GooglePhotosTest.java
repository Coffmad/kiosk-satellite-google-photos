// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import me.jxl.kiosk.plugins.PluginHost;

/** Exercises album parsing, slide documents and the plugin lifecycle without a device or network. */
public final class GooglePhotosTest {
    static final String SHORT = "https://photos.app.goo.gl/abc123";
    static final String ALBUM_ID = "AF1QipAlbumIdentifier0123456789";
    static final String KEY = "Sharekey_0123-abc";
    static final String SHARE = "https://photos.google.com/share/" + ALBUM_ID + "?key=" + KEY;
    static final String LH3 = "https://lh3.googleusercontent.com/pw/";
    /** 2001-02-03 04:05:06 UTC. */
    static final long TAKEN = 981173106000L;

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static String item(String id, int width, int height) {
        // The shape Google sends today, with the date taken after the image array.
        return "[\"" + id + "\",[\"" + LH3 + id + "\"," + width + "," + height + ",null,null,null,null,null,[null,null,1],[1000000]],"
            + TAKEN + ",\"exampleHash000\",3600000,1000000000000,[\"" + id + "\"],[[2],[31,0,1]],2,{\"15\":100000}]";
    }

    static String page(String... items) {
        return "<html><head><link rel=\"canonical\" href=\"" + SHARE + "\"></head><body><script>"
            + "AF_initDataCallback({key: 'ds:1', data:[null,[" + String.join(",", items) + "],null,"
            // The owner's avatar has the same shape and must be ignored.
            + "[\"AF1QipOwnerAvatar000000\",[\"https://lh3.googleusercontent.com/a/owner\",96,96]]]});</script></body></html>";
    }

    static String rpc(String token, String... items) {
        String inner = "[null,[" + String.join(",", items) + "]," + (token == null ? "null" : Json.quote(token)) + ",[\"" + ALBUM_ID + "\",\"Trip\"]]";
        return ")]}'\n\n[[\"wrb.fr\",\"snAcKc\"," + Json.quote(inner) + ",null,null,null,\"generic\"],[\"di\",42]]";
    }

    /** A fake JPEG of the requested size: the magic bytes followed by filler. */
    static byte[] jpeg(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xFF; bytes[1] = (byte) 0xD8; bytes[2] = (byte) 0xFF;
        for (int i = 3; i < size; i++) bytes[i] = (byte) (i * 31);
        return bytes;
    }

    static final class FakeHttp implements Http {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> forms = new CopyOnWriteArrayList<>();
        String page = page(item("AF1QipPhotoOne00000000", 4000, 3000));
        List<String> rpcPages = new ArrayList<>();
        int rpcStatus = 200;
        int imageBytes = 50_000;
        /** URL that answers with an oversized image. */
        String oversized;

        @Override
        public Response request(String url, String form, int maxBytes) throws IOException {
            requests.add(url);
            if (url.equals(SHORT)) return new Response(200, SHARE, page.getBytes(StandardCharsets.UTF_8));
            if (url.startsWith("https://photos.google.com/_/PhotosUi/data/batchexecute")) {
                forms.add(form);
                if (rpcStatus != 200) return new Response(rpcStatus, url, new byte[0]);
                String body = rpcPages.isEmpty() ? rpc(null) : rpcPages.get(Math.min(forms.size() - 1, rpcPages.size() - 1));
                return new Response(200, url, body.getBytes(StandardCharsets.UTF_8));
            }
            if (url.startsWith(LH3)) {
                int size = url.equals(oversized) ? 600_000 : imageBytes;
                return new Response(200, url, jpeg(size));
            }
            return new Response(404, url, new byte[0]);
        }

        int imageRequests() {
            int n = 0;
            for (String url : requests) if (url.startsWith(LH3)) n++;
            return n;
        }
    }

    static final class Host implements PluginHost {
        final List<String> documents = new CopyOnWriteArrayList<>();
        final List<String> subscriptions = new CopyOnWriteArrayList<>();
        volatile String status;
        volatile boolean statusError;
        volatile boolean screensaverActive;
        int screenWidth = 1280, screenHeight = 800;

        @Override public void publishScreensaver(String key, String title, String html) {
            check(GooglePhotosPlugin.KEY.equals(key) && GooglePhotosPlugin.TITLE.equals(title), "Unexpected renderer");
            check(html.getBytes(StandardCharsets.UTF_8).length <= 512 * 1024, "Document over 512 KiB");
            documents.add(html);
        }
        @Override public void subscribe(String event) { subscriptions.add(event); }
        @Override public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            check(arguments.isEmpty(), "Read commands take no arguments");
            if ("isScreensaverActive".equals(command)) callback.onResult(true, screensaverActive, null);
            else if ("getDeviceInfo".equals(command)) {
                Map<String, Object> info = new HashMap<>();
                info.put("screenWidth", screenWidth); info.put("screenHeight", screenHeight);
                callback.onResult(true, info, null);
            } else callback.onResult(false, null, "Unknown command");
        }
        @Override public void status(String message, boolean error) { status = message; statusError = error; }
        @Override public void showWindow(String title, String message, String buttonLabel) { throw new AssertionError("No window expected"); }
        @Override public void hideWindow() {}
        @Override public void log(String message) {}

        String last() { return documents.isEmpty() ? "" : documents.get(documents.size() - 1); }
    }

    static Map<String, Object> settings(String links) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("albumLinks", links);
        settings.put("refreshHours", 6);
        settings.put("interval", 30);
        settings.put("order", "Shuffle");
        settings.put("transition", "Crossfade");
        settings.put("fadeSeconds", 1.5);
        settings.put("zoom", false);
        settings.put("zoomAmount", 10);
        settings.put("horizontalFit", GooglePhotosPlugin.FIT_FILL);
        settings.put("verticalFit", GooglePhotosPlugin.FIT_BLUR);
        settings.put("cropLimit", 30);
        settings.put("cropFocus", "Center");
        settings.put("backgroundColor", "#000000");
        settings.put("dateCaption", "Off");
        settings.put("maxSize", "1920 px");
        settings.put("oversize", "Reduce resolution (keep quality)");
        return settings;
    }

    static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        check(condition.getAsBoolean(), message);
    }

    static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }

    public static void main(String[] args) throws Exception {
        // Captions are checked in a fixed language and time zone.
        java.util.Locale.setDefault(java.util.Locale.US);
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        json();
        base64AndMime();
        links();
        albumPaging();
        albumFallsBackToPage();
        albumErrors();
        fitAndCaptions();
        slides();
        slideshow();
        oversizedPhotosShrink();
        emptyLink();
        System.out.println("GooglePhotosTest passed");
    }

    static void json() {
        Object parsed = Json.parse("[1,\"a\\\"b\\u00e9\",{\"k\":[true,false,null]},-2.5e1]");
        check(parsed.equals(Arrays.asList(1.0, "a\"b" + (char) 0xE9, Collections.singletonMap("k", Arrays.asList(true, false, null)), -25.0)), "JSON parse");
        String tricky = "</script>\"\\\n" + (char) 0x2028;
        check(Json.parse(Json.quote(tricky)).equals(tricky), "JSON quote round trip");
        check(!Json.quote(tricky).contains("</"), "Quoted strings cannot close a script element");
        try { Json.parse("[1,"); throw new AssertionError("Malformed JSON accepted"); } catch (IllegalArgumentException expected) {}
    }

    static void base64AndMime() {
        for (int length = 0; length < 10; length++) {
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) data[i] = (byte) (i * 77 - 3);
            check(Slides.base64(data).equals(java.util.Base64.getEncoder().encodeToString(data)), "Base64 length " + length);
        }
        check("image/jpeg".equals(Slides.mime(jpeg(10))), "JPEG");
        check("image/webp".equals(Slides.mime("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))), "WebP");
        try { Slides.mime("<html>".getBytes(StandardCharsets.UTF_8)); throw new AssertionError("HTML accepted as image"); } catch (IllegalArgumentException expected) {}
    }

    static void links() {
        check(Album.links(" https://photos.app.goo.gl/a,https://photos.app.goo.gl/b\nhttps://photos.app.goo.gl/a ")
            .equals(Arrays.asList("https://photos.app.goo.gl/a", "https://photos.app.goo.gl/b")), "Link splitting");
        check(Album.links("").isEmpty(), "Empty links");
        Album.validate(SHORT);
        Album.validate(SHARE);
        for (String bad : new String[] {"http://photos.app.goo.gl/a", "https://example.com/share/x", "https://photos.google.com/album/AF1Qip", "file:///etc/hosts"}) {
            try { Album.validate(bad); throw new AssertionError("Accepted " + bad); } catch (IllegalArgumentException expected) {}
        }
    }

    static void albumPaging() throws IOException {
        FakeHttp http = new FakeHttp();
        http.rpcPages = Arrays.asList(
            rpc("page-2", item("AF1QipPhotoOne00000000", 4000, 3000), item("AF1QipPhotoTwo00000000", 3000, 4000)),
            rpc(null, item("AF1QipPhotoTwo00000000", 3000, 4000), item("AF1QipPhotoThree000000", 1920, 1080)));
        List<Album.Photo> photos = new Album(http).load(SHORT);
        check(photos.size() == 3, "Three unique photos across pages, got " + photos.size());
        check(photos.get(0).width == 4000 && photos.get(0).height == 3000, "Photo dimensions");
        check(photos.get(2).url.equals(LH3 + "AF1QipPhotoThree000000"), "Photo URL");
        check(http.forms.size() == 2, "Two pages requested");
        String first = java.net.URLDecoder.decode(http.forms.get(0), "UTF-8");
        check(first.contains("snAcKc") && first.contains(ALBUM_ID) && first.contains(KEY) && first.contains("null"), "First page request: " + first);
        check(java.net.URLDecoder.decode(http.forms.get(1), "UTF-8").contains("page-2"), "Second page uses the token");
        check(Album.sized(photos.get(0), 1920, 1080, 0).equals(LH3 + "AF1QipPhotoOne00000000=w1920-h1080-rw"), "Sized URL");
        check(Album.sized(photos.get(0), 1920, 1080, 75).equals(LH3 + "AF1QipPhotoOne00000000=w1920-h1080-rw-l75"), "Quality URL");
        for (Album.Photo photo : photos) check(photo.taken == TAKEN, "Date taken read for " + photo.id + ": " + photo.taken);
        // An unfamiliar item shape still yields the photo, just without a date.
        Map<String, Album.Photo> bare = new LinkedHashMap<>();
        Album.collect("[\"AF1QipPhotoBare0000000\",[\"" + LH3 + "bare\",800,600]]", bare);
        check(bare.size() == 1 && bare.values().iterator().next().taken == 0, "Photo without a date");
    }

    static void fitAndCaptions() {
        GooglePhotosPlugin plugin = new GooglePhotosPlugin(new FakeHttp());
        Map<String, Object> settings = settings(SHORT);
        Album.Photo landscape = new Album.Photo("a", LH3 + "a", 4000, 3000, TAKEN);
        Album.Photo portrait = new Album.Photo("b", LH3 + "b", 3000, 4000, 0);
        Album.Photo panorama = new Album.Photo("c", LH3 + "c", 6000, 2000, TAKEN);
        Album.Photo unknown = new Album.Photo("d", LH3 + "d", 0, 0, TAKEN);
        // Without a screen size the plugin assumes 16:9. A 4:3 photo then loses 25% when filled.
        GooglePhotosPlugin.Config c = new GooglePhotosPlugin.Config(settings);
        check(plugin.fitFor(landscape, c) == Slides.Fit.FILL, "4:3 fills under a 30% limit");
        check(plugin.fitFor(panorama, c) == Slides.Fit.BLUR, "Panorama would lose 41%, so it shows whole");
        check(plugin.fitFor(portrait, c) == Slides.Fit.BLUR, "Vertical photos use their own setting");
        check(plugin.fitFor(unknown, c) == Slides.Fit.FILL, "Unknown sizes count as horizontal");
        settings.put("cropLimit", 20);
        check(plugin.fitFor(landscape, new GooglePhotosPlugin.Config(settings)) == Slides.Fit.BLUR, "20% limit rejects a 25% crop");
        settings.put("verticalFit", GooglePhotosPlugin.FIT_FILL);
        settings.put("cropLimit", 100);
        check(plugin.fitFor(portrait, new GooglePhotosPlugin.Config(settings)) == Slides.Fit.FILL, "100% always crops");
        settings.put("horizontalFit", GooglePhotosPlugin.FIT_PLAIN);
        check(plugin.fitFor(landscape, new GooglePhotosPlugin.Config(settings)) == Slides.Fit.PLAIN, "Plain background");

        check(GooglePhotosPlugin.caption(landscape, c).isEmpty(), "Captions off by default");
        settings.put("dateCaption", GooglePhotosPlugin.DATE_MONTH);
        check("February 2001".equals(GooglePhotosPlugin.caption(landscape, new GooglePhotosPlugin.Config(settings))), "Month caption");
        settings.put("dateCaption", GooglePhotosPlugin.DATE_FULL);
        String full = GooglePhotosPlugin.caption(landscape, new GooglePhotosPlugin.Config(settings));
        check(full.contains("February") && full.contains("3") && full.contains("2001"), "Full date caption: " + full);
        check(GooglePhotosPlugin.caption(portrait, new GooglePhotosPlugin.Config(settings)).isEmpty(), "No caption without a date");

        // Out-of-range or missing values fall back to safe defaults.
        Map<String, Object> odd = new HashMap<>();
        odd.put("backgroundColor", "red");
        odd.put("interval", Double.NaN);
        GooglePhotosPlugin.Config fallback = new GooglePhotosPlugin.Config(odd);
        check(fallback.intervalMs == 30_000 && fallback.style().background.equals("#000000"), "Defaults for bad values");
        check(fallback.horizontalFit == Slides.Fit.FILL && fallback.verticalFit == Slides.Fit.BLUR && !fallback.reduceQuality, "Default fits");
    }

    static void albumFallsBackToPage() throws IOException {
        FakeHttp http = new FakeHttp();
        http.rpcStatus = 500;
        http.page = page(item("AF1QipPhotoOne00000000", 4000, 3000), item("AF1QipPhotoTwo00000000", 3000, 4000));
        List<Album.Photo> photos = new Album(http).load(SHORT);
        check(photos.size() == 2, "Page fallback finds both photos and skips the avatar, got " + photos.size());
    }

    static void albumErrors() {
        FakeHttp http = new FakeHttp();
        http.rpcStatus = 500;
        http.page = "<html>nothing here</html>";
        try { new Album(http).load(SHORT); throw new AssertionError("Empty album accepted"); } catch (IOException expected) {}
        try { new Album(http).load("https://photos.app.goo.gl/missing"); throw new AssertionError("404 accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains("not found"), expected.getMessage()); }
    }

    static void slides() {
        Album.Photo photo = new Album.Photo("p", LH3 + "p", 100, 100, 0);
        Slides.Shot a = new Slides.Shot(new Slides.Image(jpeg(1000), photo, 30, 40), Slides.Fit.BLUR, "");
        Slides.Shot b = new Slides.Shot(new Slides.Image(jpeg(2000), photo, 50, 60), Slides.Fit.FILL, "February </script> 2001");
        Slides.Style fade = new Slides.Style(true, 2.5, true, 0.15, 30, 30, "#123456");
        String both = Slides.slide(a, 12, b, fade);
        check(count(both, "data:image/jpeg;base64,") == 2, "Crossfade embeds both photos");
        check(both.contains("slide('prev'") && both.contains("#next{opacity:0}") && both.contains("-elapsed"), "Crossfade markup");
        check(both.contains(",'blur',") && both.contains(",'fill',"), "Each photo keeps its own fit");
        check(both.contains("zoom=true") && both.contains("scale(1.15)") && both.contains("seconds=35.0"), "Zoom amount and duration");
        check(both.contains("opacity 2.5s") && both.contains("object-position:50% 30%") && both.contains("background:#123456"), "Fade, focus and color");
        check(both.contains("February \\u003c/script\\u003e 2001") && !both.contains("February </script>"), "Caption is escaped");
        String cut = Slides.slide(a, 12, b, new Slides.Style(false, 1.5, false, 0.1, 30, 50, "#000000"));
        check(count(cut, "data:image/jpeg;base64,") == 1 && cut.contains("#next{opacity:1}"), "Cut embeds one photo");
        check(new Slides.Style(false, 1, false, 0, 30, 50, "url(x)").background.equals("#000000"), "Colors are validated");
        String first = Slides.slide(null, 0, b, fade);
        check(count(first, "data:image/jpeg;base64,") == 1, "First slide has no previous photo");
        check(Slides.message("A <b>", "x & y").contains("A &lt;b&gt;") && Slides.message("A", "x & y").contains("x &amp; y"), "Message escaping");
        check(Slides.imageBudget(true) * 2 + Slides.OVERHEAD_BYTES <= Slides.MAX_DOCUMENT_BYTES, "Crossfade budget");
    }

    static void slideshow() throws Exception {
        FakeHttp http = new FakeHttp();
        http.page = page(item("AF1QipPhotoOne00000000", 4000, 3000), item("AF1QipPhotoTwo00000000", 3000, 4000), item("AF1QipPhotoThree000000", 1920, 1080));
        http.rpcStatus = 500;
        Host host = new Host();
        GooglePhotosPlugin plugin = new GooglePhotosPlugin(http);
        try {
            plugin.start(host, settings(SHORT));
            check(host.subscriptions.containsAll(Arrays.asList("screensaver.state", "screen.state")), "Subscribes to screensaver and screen state");
            check(host.documents.get(0).contains("Loading"), "Registers the mode immediately");
            await(() -> host.last().contains("data:image/jpeg"), "First photo published");
            await(() -> http.imageRequests() >= 2, "Next photo prefetched");
            check(host.status.startsWith("3 photos from 1 album"), "Status: " + host.status);
            check(count(host.last(), "data:image/") == 1, "First slide shows one photo");
            // The 1280x800 screen bounds the requested size.
            for (String url : http.requests) {
                // Vertical photos fit inside the screen. The 4:3 photo fills it and is cropped.
                if (url.contains("PhotoTwo")) check(url.endsWith("=w1280-h800-rw"), "Screen-sized request: " + url);
                if (url.contains("PhotoOne")) check(url.endsWith("=w1280-h960-rw"), "Fill-sized request: " + url);
            }

            int before = host.documents.size();
            int fetched = http.imageRequests();
            plugin.execute("next", Collections.<String, Object>emptyMap());
            await(() -> host.documents.size() > before, "Next photo published");
            check(count(host.last(), "data:image/") == 2, "Second slide crossfades from the first");
            await(() -> http.imageRequests() > fetched, "Prefetch after advancing");
            check(http.imageRequests() == fetched + 1, "Advancing used the prefetched photo");

            for (int i = 0; i < 3; i++) {
                int count = host.documents.size();
                plugin.execute("next", Collections.<String, Object>emptyMap());
                await(() -> host.documents.size() > count, "Photo " + i);
            }
            check(host.documents.size() >= 5, "Five slides published");

            // Display changes redraw without downloading the album again.
            int pages = count(String.join(" ", http.requests), SHORT);
            Map<String, Object> cut = settings(SHORT);
            cut.put("transition", "Cut");
            cut.put("horizontalFit", GooglePhotosPlugin.FIT_PLAIN);
            cut.put("verticalFit", GooglePhotosPlugin.FIT_PLAIN);
            int count = host.documents.size();
            plugin.configure(cut);
            await(() -> host.documents.size() > count, "Redrawn after a settings change");
            check(count(host.last(), "data:image/") == 1 && host.last().contains(",'plain',"), "Redraw uses new style");
            check(count(String.join(" ", http.requests), SHORT) == pages, "Album not reloaded for display changes");

            plugin.onEvent("ks.screensaver.state", Collections.<String, Object>singletonMap("active", true));
            plugin.onEvent("ks.screen.state", Collections.<String, Object>singletonMap("on", false));
        } finally {
            plugin.stop();
        }
        int after = host.documents.size();
        Thread.sleep(100);
        check(host.documents.size() == after, "Nothing published after stop");
        boolean running = false;
        for (Thread thread : Thread.getAllStackTraces().keySet()) running |= thread.getName().equals("google-photos-screensaver");
        check(!running, "Worker thread ends with the plugin");
    }

    static void oversizedPhotosShrink() throws Exception {
        // A 4:3 photo filling a 1280x800 screen downloads at 1280x960. That is too big for the crossfade budget.
        String full = LH3 + "AF1QipPhotoOne00000000=w1280-h960-rw";
        oversized(settings(SHORT), full, LH3 + "AF1QipPhotoOne00000000=w1024-h768-rw");
        Map<String, Object> quality = settings(SHORT);
        quality.put("oversize", "Reduce quality (keep resolution)");
        oversized(quality, full, full + "-l75");
    }

    /** The first request is oversized. Expects exactly that request followed by the retry. */
    static void oversized(Map<String, Object> settings, String first, String retry) throws Exception {
        FakeHttp http = new FakeHttp();
        http.rpcStatus = 500;
        http.oversized = first;
        Host host = new Host();
        GooglePhotosPlugin plugin = new GooglePhotosPlugin(http);
        try {
            plugin.start(host, settings);
            await(() -> host.last().contains("data:image/jpeg"), "Photo published after a retry");
            List<String> images = new ArrayList<>();
            for (String url : http.requests) if (url.startsWith(LH3)) images.add(url);
            check(images.size() >= 2 && images.get(0).equals(first) && images.get(1).equals(retry), "Retry order: " + images);
        } finally {
            plugin.stop();
        }
    }

    static void emptyLink() throws Exception {
        FakeHttp http = new FakeHttp();
        Host host = new Host();
        GooglePhotosPlugin plugin = new GooglePhotosPlugin(http);
        try {
            plugin.start(host, settings(""));
            await(() -> host.last().contains("Paste a shared album link"), "Setup message shown");
            check(http.requests.isEmpty(), "No network without a link");
            Map<String, Object> bad = settings("https://example.com/album");
            plugin.configure(bad);
            await(() -> host.statusError && host.status.contains("Google Photos share link"), "Rejects other sites: " + host.status);
            await(() -> host.last().contains("Could not load"), "Error shown on screen");
            check(http.requests.isEmpty(), "Other sites are never fetched");
        } finally {
            plugin.stop();
        }
    }
}
