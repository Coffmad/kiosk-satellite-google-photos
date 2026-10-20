// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a personal OneDrive folder shared with "Anyone with the link". Microsoft's documented shares API now
 * refuses anonymous callers, so this uses the same anonymous token and endpoints as the OneDrive website's own
 * viewer for shared links. They are undocumented and can change without notice. OneDrive resizes photos on its
 * servers, so downloads arrive at screen size.
 */
final class OneDrive {
    static final String API = "https://my.microsoftpersonalcontent.com/_api/v2.0";
    private static final String TOKEN_URL = "https://api-badgerp.svc.ms/v1.0/token";
    /** The OneDrive web viewer's application ID, which the anonymous token is issued for. */
    private static final String APP_ID = "5cbed6ac-a083-4e14-b191-b4ba07653de2";
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    /** Subfolders are included this many levels down. */
    static final int MAX_DEPTH = 3;
    static final int MAX_FOLDERS = 100;
    static final int MAX_PHOTOS = 20000;
    private static final String ITEM_FIELDS = "id,name,file,folder,image,photo,video,parentReference";
    /** Drive and item IDs, such as 0123456789ABCDEF!s0a1b2c3d4e5f. */
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9!_.-]+");

    private final Http http;
    private String token;
    private long tokenExpires;

    OneDrive(Http http) { this.http = http; }

    static boolean handles(String host) {
        return host.equals("1drv.ms") || host.equals("onedrive.live.com") || host.equals("onedrive.com")
            || host.endsWith(".onedrive.com");
    }

    /** Work and school OneDrive lives on SharePoint, which always asks viewers to sign in. */
    static boolean isBusiness(String host) {
        return host.equals("sharepoint.com") || host.endsWith(".sharepoint.com");
    }

    /** Every photo in the shared folder and its subfolders, or the shared photo itself. */
    List<Album.Photo> load(String link) throws IOException {
        String share = "u!" + Slides.base64(link.getBytes(StandardCharsets.UTF_8))
            .replace('+', '-').replace('/', '_').replace("=", "");
        Map<?, ?> root = json(API + "/shares/" + share + "/driveItem?$select=" + ITEM_FIELDS, "Shared link");
        String drive = string(map(root.get("parentReference")).get("driveId"));
        String id = string(root.get("id"));
        if (!ID.matcher(drive).matches() || !ID.matcher(id).matches()) throw new IOException("Unexpected OneDrive response");
        List<Album.Photo> photos = new ArrayList<>();
        if (root.get("folder") == null) {
            Album.Photo photo = photo(root, drive);
            if (photo == null) throw new IOException("That OneDrive link is not a photo or a folder");
            photos.add(photo);
            return photos;
        }
        Deque<Object[]> folders = new ArrayDeque<>();
        folders.add(new Object[] {id, 0});
        int visited = 0;
        while (!folders.isEmpty() && visited < MAX_FOLDERS && photos.size() < MAX_PHOTOS) {
            Object[] folder = folders.poll();
            visited++;
            int depth = (Integer) folder[1];
            String next = API + "/drives/" + drive + "/items/" + folder[0] + "/children?$top=200&$select=" + ITEM_FIELDS;
            while (next != null && photos.size() < MAX_PHOTOS) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                Map<?, ?> page = json(next, "Folder");
                Object items = page.get("value");
                if (items instanceof List) {
                    for (Object entry : (List<?>) items) {
                        Map<?, ?> item = map(entry);
                        String childId = string(item.get("id"));
                        if (item.get("folder") != null) {
                            if (depth < MAX_DEPTH && ID.matcher(childId).matches()) folders.add(new Object[] {childId, depth + 1});
                            continue;
                        }
                        Album.Photo photo = photo(item, drive);
                        if (photo != null) photos.add(photo);
                    }
                }
                Object link2 = page.get("@odata.nextLink");
                // Only follow pages on the same service, so a response cannot send the token elsewhere.
                next = link2 instanceof String && ((String) link2).startsWith(API + "/") ? (String) link2 : null;
            }
        }
        if (photos.isEmpty()) throw new IOException("No photos found in that OneDrive folder");
        return photos;
    }

    /** The photo sized by OneDrive to fit within width x height. OneDrive never enlarges. */
    byte[] fetch(Album.Photo photo, int width, int height) throws IOException {
        String url = photo.url + "c" + Math.max(1, width) + "x" + Math.max(1, height) + "/content";
        Http.Response response = authorized(url, MAX_IMAGE_BYTES);
        if (response.status != 200) throw new IOException("OneDrive photo download returned HTTP " + response.status);
        return response.body;
    }

    /** A photo from a drive item, or null for anything else such as a video or a document. */
    static Album.Photo photo(Map<?, ?> item, String drive) {
        Map<?, ?> file = map(item.get("file"));
        if (file.isEmpty() || item.get("video") != null) return null;
        Map<?, ?> image = map(item.get("image"));
        String mime = string(file.get("mimeType"));
        if (image.isEmpty() && !mime.startsWith("image/")) return null;
        String id = string(item.get("id"));
        if (!ID.matcher(id).matches()) return null;
        long taken = Sources.isoTime(string(map(item.get("photo")).get("takenDateTime")));
        return new Album.Photo(Album.Source.ONEDRIVE, "od:" + drive + ":" + id,
            API + "/drives/" + drive + "/items/" + id + "/thumbnails/0/", "",
            integer(image.get("width")), integer(image.get("height")), taken);
    }

    private Map<?, ?> json(String url, String what) throws IOException {
        Http.Response response = authorized(url, MAX_JSON_BYTES);
        if (response.status == 404) throw new IOException("OneDrive link not found. Check the link and that sharing is still on");
        if (response.status == 401 || response.status == 403) {
            throw new IOException("OneDrive refused the link. Share the folder with \"Anyone with the link\" and paste that link");
        }
        if (response.status != 200) throw new IOException(what + " returned HTTP " + response.status + " from OneDrive");
        try {
            return map(Json.parse(response.text()));
        } catch (IllegalArgumentException bad) {
            throw new IOException("Unexpected OneDrive response", bad);
        }
    }

    /** A request with the anonymous token. An expired token is replaced once. */
    private Http.Response authorized(String url, int maxBytes) throws IOException {
        Http.Response response = http.request(url, null, null, headers(false), maxBytes);
        if (response.status == 401) response = http.request(url, null, null, headers(true), maxBytes);
        return response;
    }

    private Map<String, String> headers(boolean renew) throws IOException {
        if (renew || token == null || System.currentTimeMillis() > tokenExpires) {
            Http.Response response = http.request(TOKEN_URL, "{\"appId\":\"" + APP_ID + "\"}", "application/json",
                java.util.Collections.<String, String>emptyMap(), 64 * 1024);
            if (response.status != 200) throw new IOException("OneDrive sign-in for shared links returned HTTP " + response.status);
            Map<?, ?> reply;
            try {
                reply = map(Json.parse(response.text()));
            } catch (IllegalArgumentException bad) {
                throw new IOException("Unexpected OneDrive sign-in response", bad);
            }
            token = string(reply.get("token"));
            if (token.isEmpty()) throw new IOException("OneDrive did not issue a token for shared links");
            long expires = Sources.isoTime(string(reply.get("expiryTimeUtc")));
            // Renew an hour early. Without a date, renew daily.
            tokenExpires = expires > 0 ? expires - 3_600_000L : System.currentTimeMillis() + 86_400_000L;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Badger " + token);
        headers.put("Prefer", "autoredeem");
        return headers;
    }

    static Map<?, ?> map(Object value) { return value instanceof Map ? (Map<?, ?>) value : java.util.Collections.emptyMap(); }

    static String string(Object value) { return value instanceof String ? (String) value : ""; }

    static int integer(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException bad) {
                return 0;
            }
        }
        return 0;
    }
}
