// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads an iCloud shared album with Public Website turned on, through the web service behind Apple's public
 * album page. It is undocumented and can change without notice. Apple only offers fixed sizes, usually a small
 * preview and the full photo, so the full photo is shrunk on the device. Its download address expires after a
 * few hours, so it is looked up again for each download.
 */
final class ICloud {
    /** Any partition answers with the album's own partition. */
    private static final String FIRST_HOST = "p23-sharedstreams.icloud.com";
    private static final Pattern HOST = Pattern.compile("p[0-9]{1,4}-sharedstreams\\.icloud\\.com");
    /** Album tokens, such as B0aGWZuqDGKYgUq. Newer ones are longer and can contain _ and -. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{8,200}");
    private static final Pattern GUID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 40 * 1024 * 1024;

    private final Http http;
    private final Shrinker shrinker;
    // The last full-size photo, kept while it is shrunk to successive sizes.
    private String cachedId;
    private byte[] cached;

    ICloud(Http http, Shrinker shrinker) {
        this.http = http;
        this.shrinker = shrinker;
    }

    static boolean handles(String host) {
        return host.equals("www.icloud.com") || host.equals("icloud.com") || host.equals("share.icloud.com")
            || host.equals("photos.icloud.com");
    }

    /** The album token from a Public Website link, such as https://www.icloud.com/sharedalbum/#B0aGWZuqDGKYgUq. */
    static String token(String link) {
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException bad) {
            throw new IllegalArgumentException("Not a link: " + link);
        }
        String fragment = uri.getRawFragment() == null ? "" : uri.getRawFragment();
        // Some links append ";" and the first photo's ID.
        int end = fragment.indexOf(';');
        String token = end >= 0 ? fragment.substring(0, end) : fragment;
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!path.startsWith("/sharedalbum") || !TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("For iCloud, open the shared album on an iPhone, turn on Public Website and "
                + "paste that link, such as https://www.icloud.com/sharedalbum/#B0...");
        }
        return token;
    }

    /** Every photo in the album. Videos are left out. */
    List<Album.Photo> load(String link) throws IOException {
        String token = token(link);
        String host = FIRST_HOST;
        Http.Response response = post(host, token, "webstream", "{\"streamCtag\":null}");
        if (response.status == 330) {
            // The album lives on another partition, named in the reply.
            host = OneDrive.string(json(response).get("X-Apple-MMe-Host"));
            if (!HOST.matcher(host).matches()) throw new IOException("Unexpected iCloud response");
            response = post(host, token, "webstream", "{\"streamCtag\":null}");
        }
        if (response.status == 404 || response.status == 403 || response.status == 401) {
            throw new IOException("iCloud album not found. Check the link and that Public Website is still on");
        }
        if (response.status != 200) throw new IOException("iCloud returned HTTP " + response.status);
        Object list = json(response).get("photos");
        List<Album.Photo> photos = new ArrayList<>();
        if (list instanceof List) {
            String base = "https://" + host + "/" + token + "/sharedstreams/";
            for (Object entry : (List<?>) list) {
                Album.Photo photo = photo(OneDrive.map(entry), base);
                if (photo != null) photos.add(photo);
            }
        }
        if (photos.isEmpty()) throw new IOException("No photos found in that iCloud album");
        return photos;
    }

    /** The photo shrunk on the device to fit within width x height. */
    byte[] fetch(Album.Photo photo, int width, int height, int quality) throws IOException {
        if (!photo.id.equals(cachedId)) {
            cached = null;
            cachedId = null;
            cached = download(photo);
            cachedId = photo.id;
        }
        return shrinker.shrink(cached, width, height, quality);
    }

    /** A photo from an album entry: its largest version. Null for videos and unusable entries. */
    static Album.Photo photo(Map<?, ?> entry, String base) {
        if ("video".equals(entry.get("mediaAssetType"))) return null;
        String guid = OneDrive.string(entry.get("photoGuid"));
        if (!GUID.matcher(guid).matches()) return null;
        String checksum = "";
        int width = 0, height = 0;
        for (Object value : OneDrive.map(entry.get("derivatives")).values()) {
            Map<?, ?> derivative = OneDrive.map(value);
            int w = OneDrive.integer(derivative.get("width")), h = OneDrive.integer(derivative.get("height"));
            String sum = OneDrive.string(derivative.get("checksum"));
            if (!sum.isEmpty() && (long) w * h > (long) width * height) {
                width = w;
                height = h;
                checksum = sum;
            }
        }
        if (checksum.isEmpty()) return null;
        long taken = Sources.isoTime(OneDrive.string(entry.get("dateCreated")));
        return new Album.Photo(Album.Source.ICLOUD, "ic:" + guid, base, checksum, width, height, taken);
    }

    /** The full photo, through a short-lived address looked up for it. */
    private byte[] download(Album.Photo photo) throws IOException {
        String guid = photo.id.substring(3);
        String base = photo.url;
        Http.Response response = http.request(base + "webasseturls", "{\"photoGuids\":[" + Json.quote(guid) + "]}",
            "text/plain", origin(), MAX_JSON_BYTES);
        if (response.status != 200) throw new IOException("iCloud photo lookup returned HTTP " + response.status);
        Map<?, ?> item = OneDrive.map(OneDrive.map(json(response).get("items")).get(photo.asset));
        String location = OneDrive.string(item.get("url_location"));
        String path = OneDrive.string(item.get("url_path"));
        // Only Apple's content servers are fetched.
        if (!(location.equals("icloud-content.com") || location.endsWith(".icloud-content.com")) || !path.startsWith("/")) {
            throw new IOException("iCloud did not return a download address for that photo");
        }
        Http.Response image = http.request("https://" + location + path, null, null, Collections.<String, String>emptyMap(),
            MAX_IMAGE_BYTES);
        if (image.status != 200) throw new IOException("iCloud photo download returned HTTP " + image.status);
        return image.body;
    }

    private Http.Response post(String host, String token, String call, String body) throws IOException {
        return http.request("https://" + host + "/" + token + "/sharedstreams/" + call, body, "text/plain", origin(), MAX_JSON_BYTES);
    }

    private static Map<String, String> origin() {
        return Collections.singletonMap("Origin", "https://www.icloud.com");
    }

    private static Map<?, ?> json(Http.Response response) throws IOException {
        try {
            return OneDrive.map(Json.parse(response.text()));
        } catch (IllegalArgumentException bad) {
            throw new IOException("Unexpected iCloud response", bad);
        }
    }
}
