// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Synology Photos share link from any Synology NAS through the same web API as Synology's own share page.
 * It is undocumented and can change without notice. The share page needs a short session, which starts without a
 * password for public shares. Synology's largest preview is about 1280 pixels, so photos show at up to that size.
 */
final class Synology {
    private static final Pattern LINK = Pattern.compile("^(.*)/([a-z]{2})/sharing/([A-Za-z0-9]{4,64})/?$");
    private static final int PAGE = 5000;
    private static final int MAX_PAGES = 10;
    private static final int MAX_JSON_BYTES = 32 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;

    private final Http http;
    /** Session cookie per share, from its sign-in. */
    private final Map<String, String> sessions = new HashMap<>();

    Synology(Http http) { this.http = http; }

    /** Whether the link looks like https://NAS/mo/sharing/ID or https://NAS/photo/mo/sharing/ID. */
    static boolean handles(String link) {
        try {
            URI uri = new URI(link);
            return uri.getRawQuery() == null && uri.getPath() != null && LINK.matcher(uri.getPath()).matches();
        } catch (URISyntaxException bad) {
            return false;
        }
    }

    /** Every photo in the shared album. Videos are left out. */
    List<Album.Photo> load(String link) throws IOException {
        // QuickConnect links redirect to the NAS or a relay, which then serves the API.
        Http.Response page = http.request(link, null, null, Collections.<String, String>emptyMap(), 4 * 1024 * 1024);
        if (page.status == 404) throw new IOException("Synology share not found. Check the link and that sharing is still on");
        if (page.status != 200) throw new IOException("Synology returned HTTP " + page.status);
        String[] parts = parts(page.url);
        String base = parts[0], api = parts[1], share = parts[2];
        String session = signIn(api, share);
        List<Album.Photo> photos = new ArrayList<>();
        for (int number = 0; number < MAX_PAGES; number++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
            Map<?, ?> reply = post(api, share, session, "api=SYNO.Foto.Browse.Item&method=list&version=4&offset=" + (number * PAGE)
                + "&limit=" + PAGE + "&sort_by=takentime&sort_direction=asc&additional=" + encode("[\"thumbnail\",\"resolution\",\"orientation\"]"));
            Object list = OneDrive.map(reply.get("data")).get("list");
            int returned = list instanceof List ? ((List<?>) list).size() : 0;
            if (list instanceof List) {
                for (Object entry : (List<?>) list) {
                    Album.Photo photo = photo(OneDrive.map(entry), base, api, share);
                    if (photo != null) photos.add(photo);
                }
            }
            if (returned < PAGE) break;
        }
        if (photos.isEmpty()) throw new IOException("No photos found in that Synology share");
        return photos;
    }

    /**
     * A photo from an album item, or null for videos and items without a preview. Its asset holds the API address
     * and share ID, for signing in again when the session expires.
     */
    static Album.Photo photo(Map<?, ?> item, String base, String api, String share) {
        if (!"photo".equals(item.get("type"))) return null;
        Map<?, ?> additional = OneDrive.map(item.get("additional"));
        Map<?, ?> thumbnail = OneDrive.map(additional.get("thumbnail"));
        String unit = OneDrive.string(thumbnail.get("unit_id"));
        if (unit.isEmpty() && thumbnail.get("unit_id") instanceof Number) unit = String.valueOf(OneDrive.integer(thumbnail.get("unit_id")));
        String cacheKey = OneDrive.string(thumbnail.get("cache_key"));
        if (unit.isEmpty() || cacheKey.isEmpty()) return null;
        Map<?, ?> resolution = OneDrive.map(additional.get("resolution"));
        int width = OneDrive.integer(resolution.get("width")), height = OneDrive.integer(resolution.get("height"));
        // Orientations 5 to 8 turn the photo a quarter, and Synology's previews are already upright.
        int orientation = OneDrive.integer(additional.get("orientation"));
        if (orientation >= 5 && orientation <= 8) { int swap = width; width = height; height = swap; }
        long time = item.get("time") instanceof Number ? ((Number) item.get("time")).longValue() * 1000 : 0;
        String preview = base + "/synofoto/api/v2/p/Thumbnail/get?type=unit&id=" + encode(unit) + "&cache_key=" + encode(cacheKey)
            + "&_sharing_id=" + encode(share) + "&size=xl";
        return new Album.Photo(Album.Source.SYNOLOGY, "sy:" + base + "/" + share + "/" + unit, preview, api + " " + share, width, height, time);
    }

    /** Synology's large preview, about 1280 pixels on the long side. */
    byte[] fetch(Album.Photo photo, int width, int height) throws IOException {
        String api = photo.asset.substring(0, photo.asset.indexOf(' ')), share = photo.asset.substring(photo.asset.indexOf(' ') + 1);
        Http.Response response = download(photo, share);
        if (response.status == 401 || response.status == 403) {
            // The share session expired. Sign in again once.
            sessions.remove(share);
            signIn(api, share);
            response = download(photo, share);
        }
        if (response.status != 200) throw new IOException("Synology photo download returned HTTP " + response.status);
        return response.body;
    }

    private Http.Response download(Album.Photo photo, String share) throws IOException {
        String session = sessions.get(share);
        Map<String, String> headers = new LinkedHashMap<>();
        if (session != null) headers.put("Cookie", "sharing_sid=" + session);
        headers.put("X-SYNO-SHARING", share);
        return http.request(photo.url, null, null, headers, MAX_IMAGE_BYTES);
    }

    /** The site base, API address and share ID from a share link. */
    static String[] parts(String link) {
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException bad) {
            throw new IllegalArgumentException("Not a link: " + link);
        }
        Matcher matcher = LINK.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !matcher.matches()) {
            throw new IllegalArgumentException("For Synology Photos, share the album with a public link and paste it, such as https://NAS/mo/sharing/ID");
        }
        String base = "https://" + uri.getRawAuthority() + matcher.group(1);
        return new String[] {base, base + "/" + matcher.group(2) + "/sharing/webapi/entry.cgi", matcher.group(3)};
    }

    private String signIn(String api, String share) throws IOException {
        String known = sessions.get(share);
        if (known != null) return known;
        Http.Response response = http.request(api, "api=SYNO.Core.Sharing.Login&method=login&version=1&sharing_id=" + encode(share)
            + "&password=", Http.FORM, Collections.<String, String>emptyMap(), 64 * 1024);
        Map<?, ?> reply = parse(response);
        if (!Boolean.TRUE.equals(reply.get("success"))) {
            throw new IOException("That Synology share has a password or is not public. Share it with a public link and no password");
        }
        String session = response.cookie("sharing_sid");
        if (session == null) session = OneDrive.string(OneDrive.map(reply.get("data")).get("sharing_sid"));
        if (session.isEmpty()) throw new IOException("Synology did not start a session for the share");
        sessions.put(share, session);
        return session;
    }

    private Map<?, ?> post(String api, String share, String session, String form) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", "sharing_sid=" + session);
        headers.put("X-SYNO-SHARING", share);
        Map<?, ?> reply = parse(http.request(api, form, Http.FORM, headers, MAX_JSON_BYTES));
        if (!Boolean.TRUE.equals(reply.get("success"))) {
            throw new IOException("Synology refused the album listing (error " + OneDrive.integer(OneDrive.map(reply.get("error")).get("code")) + ")");
        }
        return reply;
    }

    private static Map<?, ?> parse(Http.Response response) throws IOException {
        if (response.status != 200) throw new IOException("Synology returned HTTP " + response.status);
        try {
            return OneDrive.map(Json.parse(response.text()));
        } catch (IllegalArgumentException bad) {
            throw new IOException("Unexpected Synology response", bad);
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
