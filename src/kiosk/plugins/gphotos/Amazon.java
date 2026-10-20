// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an Amazon Photos shared link through the same service as Amazon's own shared album page, which needs no
 * sign-in. It is undocumented and can change without notice. Amazon resizes photos on its servers. Download
 * addresses are temporary, so an expired one is looked up again.
 */
final class Amazon {
    private static final Pattern HOST = Pattern.compile("www\\.amazon\\.[a-z]{2,3}(?:\\.[a-z]{2})?");
    private static final Pattern PATH = Pattern.compile("^/(?:photos|clouddrive)/share/([A-Za-z0-9_-]{10,100})/?$");
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_-]{6,100}");
    static final int MAX_DEPTH = 3;
    static final int MAX_FOLDERS = 100;
    static final int MAX_PHOTOS = 20000;
    private static final int PAGE = 200;
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    private final Http http;

    Amazon(Http http) { this.http = http; }

    static boolean handles(String host) { return HOST.matcher(host).matches(); }

    /** The API base and share ID from a link such as https://www.amazon.com/photos/share/ID. */
    static String[] parts(String link) {
        try {
            URI uri = new URI(link);
            Matcher matcher = PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (handles(uri.getHost().toLowerCase(Locale.ROOT)) && matcher.matches()) {
                return new String[] {"https://" + uri.getHost().toLowerCase(Locale.ROOT) + "/drive/v1", matcher.group(1)};
            }
        } catch (URISyntaxException | NullPointerException bad) {
            // Falls through to the message below.
        }
        throw new IllegalArgumentException("For Amazon Photos, share the album with a link and paste it, such as https://www.amazon.com/photos/share/...");
    }

    /** Every photo in the share and the albums or folders inside it. Videos are left out. */
    List<Album.Photo> load(String link) throws IOException {
        String[] parts = parts(link);
        String api = parts[0], share = parts[1];
        String common = "shareId=" + share + "&resourceVersion=V2&ContentType=JSON";
        Map<?, ?> info = json(api + "/shares/" + share + "?" + common);
        String rootId = OneDrive.string(OneDrive.map(info.get("nodeInfo")).get("id"));
        if (!NODE.matcher(rootId).matches()) throw new IOException("Unexpected Amazon Photos response");
        List<Album.Photo> photos = new ArrayList<>();
        Deque<Object[]> folders = new ArrayDeque<>();
        folders.add(new Object[] {rootId, 0});
        int visited = 0;
        while (!folders.isEmpty() && visited < MAX_FOLDERS && photos.size() < MAX_PHOTOS) {
            Object[] folder = folders.poll();
            visited++;
            int depth = (Integer) folder[1];
            for (int offset = 0; offset < MAX_PHOTOS; ) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                Map<?, ?> page = json(api + "/nodes/" + folder[0] + "/children?asset=ALL&limit=" + PAGE + "&offset=" + offset
                    + "&tempLink=true&searchOnFamily=false&" + common);
                Object data = page.get("data");
                int returned = data instanceof List ? ((List<?>) data).size() : 0;
                if (data instanceof List) {
                    for (Object value : (List<?>) data) {
                        Map<?, ?> node = OneDrive.map(value);
                        String id = OneDrive.string(node.get("id"));
                        if (!NODE.matcher(id).matches()) continue;
                        if (!"FILE".equals(node.get("kind"))) {
                            if (depth < MAX_DEPTH) folders.add(new Object[] {id, depth + 1});
                            continue;
                        }
                        Album.Photo photo = photo(node, api + "/nodes/" + id + "?tempLink=true&" + common);
                        if (photo != null) photos.add(photo);
                    }
                }
                offset += returned;
                if (returned == 0 || offset >= OneDrive.integer(page.get("count"))) break;
            }
        }
        if (photos.isEmpty()) throw new IOException("No photos found in that Amazon Photos share");
        return photos;
    }

    /** A photo from a file node, or null for videos and other files. */
    static Album.Photo photo(Map<?, ?> node, String refresh) {
        Map<?, ?> content = OneDrive.map(node.get("contentProperties"));
        Map<?, ?> image = OneDrive.map(content.get("image"));
        if (image.isEmpty() || content.get("video") != null || !OneDrive.string(content.get("contentType")).startsWith("image/")) return null;
        String link = OneDrive.string(node.get("tempLink"));
        if (!downloadable(link)) return null;
        long taken = Sources.isoTime(OneDrive.string(image.get("dateTimeOriginal")));
        int width = OneDrive.integer(image.get("width")), height = OneDrive.integer(image.get("height"));
        // Orientations 5 to 8 turn the photo a quarter, so it is shown the other way up.
        int orientation = OneDrive.integer(image.get("orientation"));
        if (orientation >= 5 && orientation <= 8) { int swap = width; width = height; height = swap; }
        return new Album.Photo(Album.Source.AMAZON, "am:" + OneDrive.string(node.get("id")), link, refresh, width, height, taken);
    }

    /** The photo resized by Amazon to fit within width x height. */
    byte[] fetch(Album.Photo photo, int width, int height) throws IOException {
        String box = "?viewBox=" + Math.max(1, width) + "," + Math.max(1, height);
        Http.Response response = http.request(photo.url + box, null, null, Collections.<String, String>emptyMap(), MAX_IMAGE_BYTES);
        if (response.status == 403 || response.status == 404 || response.status == 410) {
            // The temporary address expired. A fresh one comes with the photo's details.
            String fresh = OneDrive.string(json(photo.asset).get("tempLink"));
            if (!downloadable(fresh)) throw new Sources.Expired("Amazon Photos download expired");
            response = http.request(fresh + box, null, null, Collections.<String, String>emptyMap(), MAX_IMAGE_BYTES);
        }
        if (response.status != 200) throw new IOException("Amazon Photos download returned HTTP " + response.status);
        return response.body;
    }

    /** Only Amazon's content servers are fetched. */
    static boolean downloadable(String link) {
        try {
            URI uri = new URI(link);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme()) && uri.getRawQuery() == null
                && (host.endsWith(".drive.amazonaws.com") || host.endsWith(".amazon.com") && host.startsWith("content-"));
        } catch (URISyntaxException bad) {
            return false;
        }
    }

    private Map<?, ?> json(String url) throws IOException {
        Http.Response response = http.request(url, null, null, Collections.singletonMap("Accept", "application/json"), MAX_JSON_BYTES);
        if (response.status == 404) throw new IOException("Amazon Photos link not found. Check the link and that sharing is still on");
        if (response.status == 401 || response.status == 403) throw new IOException("Amazon Photos refused the link. Share the album with a link anyone can open");
        if (response.status != 200) throw new IOException("Amazon Photos returned HTTP " + response.status);
        try {
            return OneDrive.map(Json.parse(response.text()));
        } catch (IllegalArgumentException bad) {
            throw new IOException("Unexpected Amazon Photos response", bad);
        }
    }
}
