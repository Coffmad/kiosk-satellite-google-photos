// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an iCloud shared album with Public Website turned on, through the web services behind Apple's public
 * album pages. They are undocumented and can change without notice. Apple only offers fixed sizes, so photos are
 * shrunk on the device.
 *
 * <p>Older links, https://www.icloud.com/sharedalbum/#B0..., use the shared streams service. Apple only offers a
 * small preview and the full photo there, and the full photo's download address expires after a few hours, so it
 * is looked up again for each download.
 *
 * <p>Links made by current iOS, https://photos.icloud.com/shared/album/033..., use CloudKit instead: the album ID
 * resolves to an anonymous access token and a zone, and a query on that zone lists each photo with its JPEG
 * versions and their signed download addresses. Those addresses expire after about half an hour, so the album is
 * listed again when one stops working. The protocol notes in the Apple Shared Album plugin by Dark Matter Design
 * (https://github.com/dark-matter-design/kiosk-satellite-plugin-apple-shared-album, Apache-2.0) were the reference
 * for this; the code here is written for this plugin.
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
    /** A current link: https://photos.icloud.com/shared/album/ followed by the album ID. */
    private static final Pattern SHARED_PATH = Pattern.compile("/shared/album/([A-Za-z0-9_-]{8,200})/?");
    private static final String CLOUDKIT = "https://ckdatabasews.icloud.com/database/1/com.apple.photos.cloud/production/";
    private static final Pattern PARTITION = Pattern.compile("https://p[0-9]{1,4}-ckdatabasews\\.icloud\\.com(:443)?/?");
    private static final String QUERY_TYPE = "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted";
    private static final int PAGE_RECORDS = 100;
    private static final int MAX_PAGES = 100;

    private final Http http;
    private final Shrinker shrinker;
    // The last full-size photo, kept while it is shrunk to successive sizes.
    private String cachedId;
    private byte[] cached;
    // The JPEG versions of each photo from a current link, by photo ID, as last listed.
    private final Map<String, Listed> listed = new HashMap<>();

    /** One downloadable version of a photo. Edge is its longer side in pixels, or 0 when unknown. */
    static final class Version {
        final String url;
        final int width;
        final int height;
        Version(String url, int width, int height) {
            this.url = url;
            this.width = width;
            this.height = height;
        }
        int edge() { return Math.max(width, height); }
    }

    /** A photo's versions and the album link that listed them. */
    private static final class Listed {
        final String link;
        final List<Version> versions;
        Listed(String link, List<Version> versions) {
            this.link = link;
            this.versions = versions;
        }
    }

    ICloud(Http http, Shrinker shrinker) {
        this.http = http;
        this.shrinker = shrinker;
    }

    static boolean handles(String host) {
        return host.equals("www.icloud.com") || host.equals("icloud.com") || host.equals("share.icloud.com")
            || host.equals("photos.icloud.com");
    }

    /**
     * The album token from a Public Website link, either https://photos.icloud.com/shared/album/033AnV5hC_-Orru or
     * the older https://www.icloud.com/sharedalbum/#B0aGWZuqDGKYgUq.
     */
    static String token(String link) {
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException bad) {
            throw new IllegalArgumentException("Not a link: " + link);
        }
        String shared = sharedId(uri);
        if (shared != null) return shared;
        String fragment = uri.getRawFragment() == null ? "" : uri.getRawFragment();
        // Some links append ";" and the first photo's ID.
        int end = fragment.indexOf(';');
        String token = end >= 0 ? fragment.substring(0, end) : fragment;
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!path.startsWith("/sharedalbum") || !TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("For iCloud, open the shared album on an iPhone, turn on Public Website and "
                + "paste that link, such as https://photos.icloud.com/shared/album/033...");
        }
        return token;
    }

    /** Whether a link is the current kind, read through CloudKit. */
    static boolean current(String link) {
        try {
            return sharedId(new URI(link)) != null;
        } catch (URISyntaxException bad) {
            return false;
        }
    }

    /** The album ID of a current link, or null. */
    private static String sharedId(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("photos.icloud.com") || uri.getPath() == null) return null;
        Matcher match = SHARED_PATH.matcher(uri.getPath());
        return match.matches() ? match.group(1) : null;
    }

    /** Every photo in the album. Videos are left out. */
    List<Album.Photo> load(String link) throws IOException {
        if (current(link)) return loadShared(link);
        String token = token(link);
        String host = FIRST_HOST;
        Http.Response response = post(host, token, "webstream", "{\"streamCtag\":null}");
        if (response.status == 330) {
            // The album lives on another partition, named in the reply.
            host = OneDrive.string(json(response).get("X-Apple-MMe-Host"));
            if (!HOST.matcher(host).matches()) throw new IOException("Unexpected iCloud response");
            response = post(host, token, "webstream", "{\"streamCtag\":null}");
        }
        if (response.status == 404 || response.status == 403 || response.status == 401) throw notFound();
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
            cached = photo.id.startsWith("ck:") ? downloadShared(photo, width, height) : download(photo);
            cachedId = photo.id;
        }
        return shrinker.shrink(cached, width, height, quality);
    }

    /** Every photo in an album from a current link, through CloudKit. */
    private List<Album.Photo> loadShared(String link) throws IOException {
        String id = token(link);
        String key = URLEncoder.encode(id, "UTF-8");
        Http.Response resolved = http.request(CLOUDKIT + "public/records/resolve?remapEnums=true&getCurrentSyncToken=true"
            + "&sharing_url_key=" + key, "{\"shortGUIDs\":[{\"value\":" + Json.quote(id) + ",\"shouldFetchRootRecord\":true}]}",
            "text/plain", origin(), MAX_JSON_BYTES);
        if (gone(resolved.status)) throw notFound();
        if (resolved.status != 200) throw new IOException("iCloud returned HTTP " + resolved.status);
        Object results = json(resolved).get("results");
        Map<?, ?> album = results instanceof List && !((List<?>) results).isEmpty() ? OneDrive.map(((List<?>) results).get(0)) : null;
        if (album == null || Boolean.TRUE.equals(album.get("requireAppleLogin")) || album.get("serverErrorCode") != null) {
            throw notFound();
        }
        Map<?, ?> access = OneDrive.map(album.get("anonymousPublicAccess"));
        String partition = OneDrive.string(access.get("databasePartition"));
        String accessToken = OneDrive.string(access.get("token"));
        String zone = zone(OneDrive.map(album.get("zoneID")));
        if (accessToken.isEmpty() || zone == null) throw notFound();
        // Only Apple's own database servers receive the token.
        if (!PARTITION.matcher(partition).matches()) throw new IOException("Unexpected iCloud response");
        String query = partition.replaceAll("(:443)?/?$", "") + "/database/1/com.apple.photos.cloud/production/shared/records/query"
            + "?remapEnums=true&getCurrentSyncToken=true&sharing_url_key=" + key
            + "&publicAccessAuthToken=" + URLEncoder.encode(accessToken, "UTF-8");

        // Each photo is a CPLAsset, with any edited versions, paired with the CPLMaster holding the original's versions.
        Map<String, Map<?, ?>> masters = new HashMap<>();
        Map<String, Map<?, ?>> assets = new LinkedHashMap<>();
        String marker = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String body = "{\"query\":{\"recordType\":\"" + QUERY_TYPE + "\",\"filterBy\":[{\"fieldName\":\"direction\","
                + "\"comparator\":\"EQUALS\",\"fieldValue\":{\"value\":\"ASCENDING\",\"type\":\"STRING\"}}]},"
                + "\"zoneID\":" + zone + ",\"resultsLimit\":" + PAGE_RECORDS
                + (marker == null ? "" : ",\"continuationMarker\":" + Json.quote(marker)) + "}";
            Http.Response response = http.request(query, body, "text/plain", origin(), MAX_JSON_BYTES);
            if (gone(response.status)) throw notFound();
            if (response.status != 200) throw new IOException("iCloud returned HTTP " + response.status);
            Map<?, ?> reply = json(response);
            Object records = reply.get("records");
            if (!(records instanceof List)) throw new IOException("Unexpected iCloud response");
            for (Object entry : (List<?>) records) {
                Map<?, ?> record = OneDrive.map(entry);
                String name = OneDrive.string(record.get("recordName"));
                Map<?, ?> fields = OneDrive.map(record.get("fields"));
                if (name.isEmpty()) continue;
                if ("CPLMaster".equals(record.get("recordType"))) masters.put(name, fields);
                else if ("CPLAsset".equals(record.get("recordType"))) assets.put(name, fields);
            }
            String next = OneDrive.string(reply.get("continuationMarker"));
            if (next.isEmpty() || next.equals(marker)) break;
            marker = next;
        }

        for (Iterator<Listed> it = listed.values().iterator(); it.hasNext(); ) if (it.next().link.equals(link)) it.remove();
        List<Album.Photo> photos = new ArrayList<>();
        for (Map.Entry<String, Map<?, ?>> asset : assets.entrySet()) {
            Map<?, ?> master = masters.get(OneDrive.string(OneDrive.map(value(asset.getValue(), "masterRef")).get("recordName")));
            if (master == null || video(master) || video(asset.getValue())) continue;
            // Edited photos carry their edited versions on the asset.
            List<Version> versions = versions(asset.getValue());
            if (versions.isEmpty()) versions = versions(master);
            if (versions.isEmpty()) continue;
            Version largest = versions.get(versions.size() - 1);
            long taken = number(value(asset.getValue(), "assetDate"));
            String photoId = "ck:" + asset.getKey();
            listed.put(photoId, new Listed(link, versions));
            photos.add(new Album.Photo(Album.Source.ICLOUD, photoId, link, "", largest.width, largest.height, taken));
        }
        if (photos.isEmpty()) throw new IOException("No photos found in that iCloud album");
        return photos;
    }

    /**
     * The JPEG versions of a CloudKit record, smallest first. These are already turned upright. HEIC originals are
     * left out, since Android only decodes them from version 9.
     */
    static List<Version> versions(Map<?, ?> fields) {
        List<Version> versions = new ArrayList<>();
        for (Map.Entry<?, ?> field : fields.entrySet()) {
            String key = String.valueOf(field.getKey());
            if (!key.startsWith("res") || !key.endsWith("Res") || key.toLowerCase(Locale.ROOT).contains("vid")) continue;
            String prefix = key.substring(0, key.length() - 3);
            if (!"public.jpeg".equals(value(fields, prefix + "FileType"))) continue;
            String url = OneDrive.string(OneDrive.map(OneDrive.map(field.getValue()).get("value")).get("downloadURL"));
            // Download addresses name the file with a template.
            url = url.replace("${f}", "photo.jpg");
            if (!downloadable(url)) continue;
            versions.add(new Version(url, (int) number(value(fields, prefix + "Width")), (int) number(value(fields, prefix + "Height"))));
        }
        Collections.sort(versions, (a, b) -> Integer.compare(a.edge(), b.edge()));
        return versions;
    }

    /** The smallest version that covers width x height, or the largest. */
    static Version choose(List<Version> versions, int width, int height) {
        int wanted = Math.max(width, height);
        for (Version version : versions) if (version.edge() >= wanted) return version;
        return versions.get(versions.size() - 1);
    }

    /** A photo from a current link. Its signed address expires, so the album is listed again when it stops working. */
    private byte[] downloadShared(Album.Photo photo, int width, int height) throws IOException {
        Listed known = listed.get(photo.id);
        Http.Response image = known == null ? null : get(choose(known.versions, width, height).url);
        if (image == null || gone(image.status)) {
            loadShared(photo.url);
            known = listed.get(photo.id);
            if (known == null) throw new Sources.Expired("That photo is no longer in the iCloud album");
            image = get(choose(known.versions, width, height).url);
        }
        if (image.status != 200) throw new IOException("iCloud photo download returned HTTP " + image.status);
        return image.body;
    }

    private Http.Response get(String url) throws IOException {
        return http.request(url, null, null, Collections.<String, String>emptyMap(), MAX_IMAGE_BYTES);
    }

    /** The zone to query, rebuilt from the plain fields of the resolved one, or null when it has no name. */
    private static String zone(Map<?, ?> zone) {
        String name = OneDrive.string(zone.get("zoneName"));
        if (name.isEmpty()) return null;
        StringBuilder out = new StringBuilder("{\"zoneName\":").append(Json.quote(name));
        String owner = OneDrive.string(zone.get("ownerRecordName"));
        if (!owner.isEmpty()) out.append(",\"ownerRecordName\":").append(Json.quote(owner));
        String type = OneDrive.string(zone.get("zoneType"));
        if (!type.isEmpty()) out.append(",\"zoneType\":").append(Json.quote(type));
        return out.append('}').toString();
    }

    /** A video or other moving item. Live Photos are photos with a video part, so they stay. */
    private static boolean video(Map<?, ?> fields) {
        String type = OneDrive.string(value(fields, "itemType")).toLowerCase(Locale.ROOT);
        return type.contains("movie") || type.contains("video") || type.contains("mpeg");
    }

    /** A CloudKit field's value. */
    private static Object value(Map<?, ?> fields, String key) {
        return OneDrive.map(fields.get(key)).get("value");
    }

    private static long number(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException bad) {
                return 0;
            }
        }
        return 0;
    }

    /** Only Apple's content servers are fetched. */
    private static boolean downloadable(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme()) && (host.equals("icloud-content.com") || host.endsWith(".icloud-content.com"));
        } catch (URISyntaxException bad) {
            return false;
        }
    }

    private static boolean gone(int status) {
        return status == 401 || status == 403 || status == 404 || status == 410;
    }

    private static IOException notFound() {
        return new IOException("iCloud album not found. Check the link and that Public Website is still on");
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
