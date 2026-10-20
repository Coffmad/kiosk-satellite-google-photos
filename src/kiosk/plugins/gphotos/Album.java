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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the photo list of a public Google Photos shared album. Google has no API for this since the
 * Library API stopped reading albums an app did not create, so this follows the album's public web
 * page and the paging call that page makes. Either can change without notice.
 */
final class Album {
    /** Where a photo comes from, which decides how it is downloaded. */
    enum Source { GOOGLE, ONEDRIVE, ICLOUD }

    /**
     * One photo. Width and height are the original's, or 0 when unknown. Taken is epoch milliseconds, or 0. The
     * url is the source's own reference: Google's image base, OneDrive's thumbnail address or iCloud's album
     * address, with asset naming the iCloud file.
     */
    static final class Photo {
        final Source source;
        final String id;
        final String url;
        final String asset;
        final int width;
        final int height;
        final long taken;

        Photo(String id, String url, int width, int height, long taken) {
            this(Source.GOOGLE, id, url, "", width, height, taken);
        }

        Photo(Source source, String id, String url, String asset, int width, int height, long taken) {
            this.source = source;
            this.id = id;
            this.url = url;
            this.asset = asset;
            this.width = width;
            this.height = height;
            this.taken = taken;
        }
    }

    static final int MAX_PAGES = 100;
    private static final int MAX_PAGE_BYTES = 8 * 1024 * 1024;
    private static final String RPC_ID = "snAcKc";
    private static final String RPC_URL = "https://photos.google.com/_/PhotosUi/data/batchexecute?rpcids=" + RPC_ID;
    /**
     * A media item: ["id",["https://lh3.googleusercontent.com/...",width,height,...],takenMillis. Matches both the
     * page and the paging call. The date is optional, so a change there still finds the photos.
     */
    private static final Pattern ITEM = Pattern.compile(
        "\\[\"([A-Za-z0-9_-]{16,})\",\\[\"(https://lh3\\.googleusercontent\\.com/[A-Za-z0-9_/-]+)\",(\\d+),(\\d+)"
            + "(?:(?:[^\\[\\]\"]|\\[[^\\[\\]]*\\])*\\],(\\d{9,14})[,\\]])?");
    private static final Pattern CANONICAL = Pattern.compile("<link\\s+rel=\"canonical\"\\s+href=\"([^\"]+)\"");
    private static final Pattern SHARE_PATH = Pattern.compile("/share/([A-Za-z0-9_-]+)");
    private static final Pattern KEY = Pattern.compile("[?&]key=([A-Za-z0-9_-]+)");

    private final Http http;

    Album(Http http) { this.http = http; }

    /** Split the setting into album links. Accepts spaces, commas or new lines between them. */
    static List<String> links(String setting) {
        List<String> links = new ArrayList<>();
        if (setting == null) return links;
        for (String part : setting.trim().split("[\\s,]+")) {
            if (!part.isEmpty() && !links.contains(part)) links.add(part);
        }
        return links;
    }

    /** Only Google Photos share links are fetched. */
    static void validate(String link) {
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException bad) {
            throw new IllegalArgumentException("Not a link: " + link);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
            || !(host.equals("photos.app.goo.gl") || host.equals("goo.gl") || host.equals("photos.google.com"))) {
            throw new IllegalArgumentException("Use a Google Photos share link such as https://photos.app.goo.gl/...");
        }
        if (host.equals("photos.google.com") && !uri.getPath().startsWith("/share/")) {
            throw new IllegalArgumentException("That album is private. In Google Photos, choose Share > Create link and paste that link");
        }
    }

    /** Every photo in the album, in album order. */
    List<Photo> load(String link) throws IOException {
        validate(link);
        Http.Response page = http.request(link, null, MAX_PAGE_BYTES);
        if (page.url.contains("consent.google.") || page.url.contains("accounts.google.")) {
            throw new IOException("Google asked to sign in or accept cookies. Check that the album is shared by link");
        }
        if (page.status == 404) throw new IOException("Album not found. Check the link and that sharing is still on");
        if (page.status != 200) throw new IOException("Google Photos returned HTTP " + page.status);
        String html = page.text();

        Map<String, Photo> photos = new LinkedHashMap<>();
        String canonical = first(CANONICAL, html);
        String shareUrl = canonical != null ? canonical.replace("&amp;", "&") : page.url;
        String albumId = first(SHARE_PATH, shareUrl);
        String key = first(KEY, shareUrl);
        if (key == null) key = first(KEY, page.url);
        IOException pagingError = null;
        if (albumId != null && key != null) {
            try {
                String token = null;
                for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                    String[] next = new String[1];
                    int added = collect(rpcPage(albumId, key, token, next), photos);
                    token = next[0];
                    if (token == null || token.isEmpty() || added == 0) break;
                }
            } catch (IOException | RuntimeException error) {
                pagingError = error instanceof IOException ? (IOException) error : new IOException(error.getMessage(), error);
            }
        }
        // The page itself carries the first batch, which covers small albums if paging changes.
        if (photos.isEmpty()) collect(html, photos);
        if (photos.isEmpty()) {
            if (pagingError != null) throw pagingError;
            throw new IOException("No photos found. Check that the link opens the album in a private browser window");
        }
        return new ArrayList<>(photos.values());
    }

    /** One page from the album's paging call. Returns its decoded payload text and stores the next page token. */
    private String rpcPage(String albumId, String key, String token, String[] next) throws IOException {
        String args = "[" + Json.quote(albumId) + "," + (token == null ? "null" : Json.quote(token)) + ",null," + Json.quote(key) + "]";
        String request = "[[[" + Json.quote(RPC_ID) + "," + Json.quote(args) + ",null,\"generic\"]]]";
        Http.Response response = http.request(RPC_URL, "f.req=" + encode(request), MAX_PAGE_BYTES);
        if (response.status != 200) throw new IOException("Album paging returned HTTP " + response.status);
        String payload = rpcPayload(response.text());
        Object parsed = Json.parse(payload);
        if (parsed instanceof List) {
            List<?> result = (List<?>) parsed;
            if (result.size() > 2 && result.get(2) instanceof String) next[0] = (String) result.get(2);
        }
        return payload;
    }

    /** Extracts the snAcKc result string from a batchexecute response. */
    static String rpcPayload(String body) throws IOException {
        int start = body.indexOf('[');
        if (start < 0) throw new IOException("Unexpected album paging response");
        Object parsed;
        try {
            parsed = Json.parse(body.substring(start).trim());
        } catch (IllegalArgumentException bad) {
            throw new IOException("Unexpected album paging response", bad);
        }
        if (parsed instanceof List) {
            for (Object entry : (List<?>) parsed) {
                if (!(entry instanceof List)) continue;
                List<?> call = (List<?>) entry;
                if (call.size() > 2 && "wrb.fr".equals(call.get(0)) && RPC_ID.equals(call.get(1)) && call.get(2) instanceof String) {
                    return (String) call.get(2);
                }
            }
        }
        throw new IOException("Album paging returned no photos");
    }

    /** Adds the media items found in text. Returns how many were new. */
    static int collect(String text, Map<String, Photo> photos) {
        int added = 0;
        Matcher matcher = ITEM.matcher(text);
        while (matcher.find()) {
            String id = matcher.group(1);
            String url = matcher.group(2);
            // Profile pictures live under /a/. Album media is under /pw/ or the older bare path.
            if (url.startsWith("https://lh3.googleusercontent.com/a/") || photos.containsKey(id)) continue;
            long taken = matcher.group(5) == null ? 0 : Long.parseLong(matcher.group(5));
            photos.put(id, new Photo(id, url, parse(matcher.group(3)), parse(matcher.group(4)), taken));
            added++;
        }
        return added;
    }

    /**
     * The image URL with Google's sizing options: within width x height, WebP when the server allows it. Quality 0
     * keeps Google's default encoding. Otherwise it asks for that quality, 1 to 100.
     */
    static String sized(Photo photo, int width, int height, int quality) {
        return photo.url + "=w" + Math.max(1, width) + "-h" + Math.max(1, height) + "-rw" + (quality > 0 ? "-l" + quality : "");
    }

    private static int parse(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLarge) {
            return 0;
        }
    }

    private static String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? decode(matcher.group(1)) : null;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException bad) {
            return value;
        }
    }
}
