// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a Dropbox shared folder link through the same listing call as Dropbox's own shared folder page, which
 * needs a session cookie from that page but no sign-in. It is undocumented and can change without notice.
 * Previews come in fixed sizes and are shrunk on the device. Subfolders are not included.
 */
final class Dropbox {
    private static final String LIST = "https://www.dropbox.com/list_shared_link_folder_entries";
    /** Preview sizes Dropbox serves, smallest first. */
    static final int[][] PRESETS = {{640, 480}, {800, 600}, {1024, 768}, {1280, 960}, {1600, 1200}, {2048, 1536}, {3200, 2400}};
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{4,64}");
    private static final int MAX_PAGES = 100;
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    private final Http http;

    Dropbox(Http http) { this.http = http; }

    static boolean handles(String host) { return host.equals("www.dropbox.com") || host.equals("dropbox.com"); }

    /** The link's key, type and secure hash, from /scl/fo/KEY/HASH or the older /sh/KEY/HASH. */
    static String[] parts(String link) {
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException bad) {
            throw new IllegalArgumentException("Not a link: " + link);
        }
        String[] segments = uri.getPath() == null ? new String[0] : uri.getPath().replaceAll("^/+", "").split("/");
        if (segments.length >= 3 && segments[0].equals("scl") && segments[1].equals("fo") && KEY.matcher(segments[2]).matches()) {
            return new String[] {segments[2], "c", segments.length > 3 ? segments[3] : "", param(uri, "rlkey")};
        }
        if (segments.length >= 2 && segments[0].equals("sh") && KEY.matcher(segments[1]).matches()) {
            return new String[] {segments[1], "s", segments.length > 2 ? segments[2] : "", param(uri, "rlkey")};
        }
        throw new IllegalArgumentException("For Dropbox, share a folder and paste its link, such as https://www.dropbox.com/scl/fo/...");
    }

    /** Every photo in the shared folder. Videos and other files are left out. */
    List<Album.Photo> load(String link) throws IOException {
        String[] key = parts(link);
        Http.Response page = http.request(link, null, null, java.util.Collections.<String, String>emptyMap(), 4 * 1024 * 1024);
        if (page.status == 404 || page.status == 410) throw new IOException("Dropbox link not found. Check the link and that sharing is still on");
        if (page.status != 200) throw new IOException("Dropbox returned HTTP " + page.status);
        String csrf = page.cookie("t");
        if (csrf == null) throw new IOException("Dropbox did not start a session for the shared folder");
        StringBuilder cookie = new StringBuilder();
        for (String pair : page.cookies) cookie.append(cookie.length() == 0 ? "" : "; ").append(pair);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", cookie.toString());
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Origin", "https://www.dropbox.com");
        headers.put("Referer", link);

        List<Album.Photo> photos = new ArrayList<>();
        String voucher = null;
        for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
            StringBuilder form = new StringBuilder("is_xhr=true&t=").append(encode(csrf))
                .append("&link_key=").append(encode(key[0])).append("&link_type=").append(key[1])
                .append("&secure_hash=").append(encode(key[2])).append("&sub_path=");
            if (!key[3].isEmpty()) form.append("&rlkey=").append(encode(key[3]));
            if (voucher != null) form.append("&voucher=").append(encode(voucher));
            Http.Response response = http.request(LIST, form.toString(), Http.FORM, headers, MAX_JSON_BYTES);
            if (response.status != 200) throw new IOException("Dropbox folder listing returned HTTP " + response.status);
            Map<?, ?> reply;
            try {
                reply = OneDrive.map(Json.parse(response.text()));
            } catch (IllegalArgumentException bad) {
                throw new IOException("Unexpected Dropbox response", bad);
            }
            Object entries = reply.get("entries");
            if (entries instanceof List) {
                for (Object entry : (List<?>) entries) {
                    Album.Photo photo = photo(OneDrive.map(entry));
                    if (photo != null) photos.add(photo);
                }
            }
            voucher = OneDrive.string(reply.get("next_request_voucher"));
            if (!Boolean.TRUE.equals(reply.get("has_more_entries")) || voucher.isEmpty()) break;
        }
        if (photos.isEmpty()) throw new IOException("No photos found in that Dropbox folder. Photos in subfolders are not shown");
        return photos;
    }

    /** A photo from a folder entry. Null for folders, videos and other files. */
    static Album.Photo photo(Map<?, ?> entry) {
        if (Boolean.TRUE.equals(entry.get("is_dir")) || !"photo".equals(entry.get("preview_type"))) return null;
        String template = OneDrive.string(entry.get("thumbnail_url_tmpl"));
        URI uri;
        try {
            uri = new URI(template);
        } catch (URISyntaxException bad) {
            return null;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        // Only Dropbox's preview servers are fetched.
        if (!"https".equals(uri.getScheme()) || !host.endsWith(".previews.dropboxusercontent.com") || uri.getRawQuery() != null) return null;
        Map<?, ?> dimensions = OneDrive.map(OneDrive.map(entry.get("preview")).get("dimensions"));
        String id = OneDrive.string(entry.get("file_id"));
        if (id.isEmpty()) id = OneDrive.string(entry.get("href"));
        return new Album.Photo(Album.Source.DROPBOX, "db:" + id, template, "",
            OneDrive.integer(dimensions.get("width")), OneDrive.integer(dimensions.get("height")), 0);
    }

    /** The smallest preview that covers width x height, or the largest. */
    byte[] fetch(Album.Photo photo, int width, int height) throws IOException {
        int[] size = PRESETS[PRESETS.length - 1];
        for (int[] preset : PRESETS) {
            if (preset[0] >= Math.max(width, height) && preset[1] >= Math.min(width, height)) {
                size = preset;
                break;
            }
        }
        Http.Response response = http.request(photo.url + "?size=" + size[0] + "x" + size[1] + "&size_mode=2", null, null,
            java.util.Collections.<String, String>emptyMap(), MAX_IMAGE_BYTES);
        // Preview addresses can expire. Reloading the folder issues new ones.
        if (response.status == 403 || response.status == 404 || response.status == 410) throw new Sources.Expired("Dropbox preview expired");
        if (response.status != 200) throw new IOException("Dropbox photo download returned HTTP " + response.status);
        return response.body;
    }

    private static String param(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (UnsupportedEncodingException | IllegalArgumentException bad) {
                    return "";
                }
            }
        }
        return "";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
