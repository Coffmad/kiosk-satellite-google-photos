// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a public Flickr album or photostream through Flickr's official API, using the public key that Flickr's own
 * pages carry for the same calls, so no API key of your own is needed. Flickr offers fixed sizes, and the
 * smallest one that covers the screen is shrunk on the device.
 */
final class Flickr {
    private static final String API = "https://api.flickr.com/services/rest/";
    private static final Pattern SITE_KEY = Pattern.compile("site_key\\s*[=:]\\s*\"([0-9a-f]{32})\"");
    private static final Pattern ALBUM = Pattern.compile("^/photos/([^/]+)/(?:albums|sets)/([0-9]{5,25})/?.*$");
    private static final Pattern STREAM = Pattern.compile("^/photos/([^/]+)/?$");
    /** Size suffixes from small to large: 800, 1024, 1600, 2048 and 3072 pixels, then the original. */
    static final String[] SIZES = {"c", "l", "h", "k", "3k", "o"};
    private static final String EXTRAS = "url_c,url_l,url_h,url_k,url_3k,url_o,o_dims,date_taken,media";
    private static final int PER_PAGE = 500;
    private static final int MAX_PAGES = 40;
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 30 * 1024 * 1024;

    private final Http http;
    private String key;

    Flickr(Http http) { this.http = http; }

    static boolean handles(String host) {
        return host.equals("www.flickr.com") || host.equals("flickr.com") || host.equals("m.flickr.com") || host.equals("flic.kr");
    }

    /** Every photo in the album or photostream. Videos are left out. */
    List<Album.Photo> load(String link) throws IOException {
        // The page resolves flic.kr short links and carries the public key.
        Http.Response page = http.request(link, null, null, Collections.<String, String>emptyMap(), 8 * 1024 * 1024);
        if (page.status == 404) throw new IOException("Flickr page not found. Check the link and that the album is public");
        if (page.status != 200) throw new IOException("Flickr returned HTTP " + page.status);
        Matcher siteKey = SITE_KEY.matcher(page.text());
        if (siteKey.find()) key = siteKey.group(1);
        if (key == null) throw new IOException("Flickr's page did not include its public key");
        String path;
        try {
            path = new URI(page.url).getPath();
        } catch (URISyntaxException bad) {
            throw new IOException("Unexpected Flickr address", bad);
        }
        Matcher album = ALBUM.matcher(path == null ? "" : path);
        Matcher stream = STREAM.matcher(path == null ? "" : path);
        if (!album.matches() && !stream.matches()) {
            throw new IOException("Use a public Flickr album or photostream link, such as https://www.flickr.com/photos/NAME/albums/123");
        }
        String user = OneDrive.string(OneDrive.map(call("flickr.urls.lookupUser", "url=" + encode(page.url)).get("user")).get("id"));
        if (user.isEmpty()) throw new IOException("Flickr account not found");
        List<Album.Photo> photos = new ArrayList<>();
        for (int number = 1; number <= MAX_PAGES; number++) {
            Map<?, ?> reply = album.matches()
                ? OneDrive.map(call("flickr.photosets.getPhotos", "photoset_id=" + album.group(2) + "&user_id=" + encode(user)
                    + "&extras=" + EXTRAS + "&per_page=" + PER_PAGE + "&page=" + number).get("photoset"))
                : OneDrive.map(call("flickr.people.getPublicPhotos", "user_id=" + encode(user)
                    + "&extras=" + EXTRAS + "&per_page=" + PER_PAGE + "&page=" + number).get("photos"));
            Object list = reply.get("photo");
            if (list instanceof List) {
                for (Object entry : (List<?>) list) {
                    Album.Photo photo = photo(OneDrive.map(entry));
                    if (photo != null) photos.add(photo);
                }
            }
            if (number >= OneDrive.integer(reply.get("pages"))) break;
        }
        if (photos.isEmpty()) throw new IOException("No public photos found at that Flickr link");
        return photos;
    }

    /** A photo with its available sizes, smallest first, as width,height,url lines. Null for videos. */
    static Album.Photo photo(Map<?, ?> entry) {
        if (!"photo".equals(entry.get("media"))) return null;
        StringBuilder sizes = new StringBuilder();
        int width = 0, height = 0;
        for (String size : SIZES) {
            String url = OneDrive.string(entry.get("url_" + size));
            int w = OneDrive.integer(entry.get("width_" + size)), h = OneDrive.integer(entry.get("height_" + size));
            if (url.isEmpty() || w <= 0 || h <= 0 || !downloadable(url)) continue;
            sizes.append(w).append(',').append(h).append(',').append(url).append('\n');
            width = w;
            height = h;
        }
        if (sizes.length() == 0) return null;
        int ow = OneDrive.integer(entry.get("o_width")), oh = OneDrive.integer(entry.get("o_height"));
        if (ow > 0 && oh > 0) { width = ow; height = oh; }
        return new Album.Photo(Album.Source.FLICKR, "fl:" + OneDrive.string(entry.get("id")), "", sizes.toString(),
            width, height, localTime(OneDrive.string(entry.get("datetaken"))));
    }

    /** The smallest size that covers width x height, or the largest. */
    byte[] fetch(Album.Photo photo, int width, int height) throws IOException {
        String chosen = null;
        for (String line : photo.asset.split("\n")) {
            String[] parts = line.split(",", 3);
            if (parts.length < 3) continue;
            chosen = parts[2];
            int w = Integer.parseInt(parts[0]), h = Integer.parseInt(parts[1]);
            // Covers the box at the photo's own shape, so shrinking only ever reduces it.
            double scale = Math.min((double) width / w, (double) height / h);
            if (scale <= 1) break;
        }
        if (chosen == null) throw new IOException("No Flickr size for that photo");
        Http.Response response = http.request(chosen, null, null, Collections.<String, String>emptyMap(), MAX_IMAGE_BYTES);
        if (response.status != 200) throw new IOException("Flickr photo download returned HTTP " + response.status);
        return response.body;
    }

    private Map<?, ?> call(String method, String arguments) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Http.Response response = http.request(API + "?method=" + method + "&api_key=" + key + "&" + arguments
                + "&format=json&nojsoncallback=1", null, null, Collections.<String, String>emptyMap(), MAX_JSON_BYTES);
            if (response.status != 200) throw new IOException("Flickr returned HTTP " + response.status);
            Map<?, ?> reply;
            try {
                reply = OneDrive.map(Json.parse(response.text()));
            } catch (IllegalArgumentException bad) {
                throw new IOException("Unexpected Flickr response", bad);
            }
            if ("ok".equals(reply.get("stat"))) return reply;
            int code = OneDrive.integer(reply.get("code"));
            // Flickr rotates the page key. 100 means it changed, so fetch it again once.
            if (code == 100 && attempt == 0 && refreshKey()) continue;
            if (code == 1) throw new IOException("Flickr album not found or not public");
            throw new IOException("Flickr: " + OneDrive.string(reply.get("message")));
        }
    }

    private boolean refreshKey() throws IOException {
        Http.Response page = http.request("https://www.flickr.com/", null, null, Collections.<String, String>emptyMap(), 8 * 1024 * 1024);
        Matcher matcher = SITE_KEY.matcher(page.text());
        if (!matcher.find()) return false;
        key = matcher.group(1);
        return true;
    }

    /** Only Flickr's image servers are fetched. */
    static boolean downloadable(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme()) && host.endsWith(".staticflickr.com");
        } catch (URISyntaxException bad) {
            return false;
        }
    }

    /** Flickr gives the date taken in the photo's own local time, such as 2026-08-06 11:42:42. */
    static long localTime(String text) {
        if (text == null || text.length() < 19) return 0;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        format.setLenient(false);
        try {
            return format.parse(text.substring(0, 19)).getTime();
        } catch (ParseException bad) {
            return 0;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
