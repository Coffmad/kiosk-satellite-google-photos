// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** OneDrive and iCloud parsing, link routing and downloads, against canned responses shaped like the real ones. */
public final class SourcesTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static Http.Response ok(String url, String body) { return new Http.Response(200, url, body.getBytes(StandardCharsets.UTF_8)); }

    /** One recorded request. */
    static final class Call {
        final String url, body, type;
        final Map<String, String> headers;
        Call(String url, String body, String type, Map<String, String> headers) {
            this.url = url; this.body = body; this.type = type; this.headers = headers;
        }
    }

    /** Answers by URL prefix and records every call. */
    abstract static class Fake implements Http {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        @Override
        public Response request(String url, String body, String type, Map<String, String> headers, int max) throws IOException {
            calls.add(new Call(url, body, type, headers));
            return answer(url, body, headers);
        }
        abstract Response answer(String url, String body, Map<String, String> headers) throws IOException;
        List<Call> to(String prefix) {
            List<Call> found = new ArrayList<>();
            for (Call call : calls) if (call.url.startsWith(prefix)) found.add(call);
            return found;
        }
    }

    /** Records sizes and returns a marker JPEG naming them. */
    static final class FakeShrinker implements Shrinker {
        final List<String> calls = new ArrayList<>();
        public byte[] shrink(byte[] image, int maxWidth, int maxHeight, int quality) {
            calls.add(image.length + ":" + maxWidth + "x" + maxHeight + "@" + quality);
            byte[] out = new byte[10];
            out[0] = (byte) 0xFF; out[1] = (byte) 0xD8;
            return out;
        }
    }

    public static void main(String[] args) throws Exception {
        routing();
        isoTime();
        oneDrive();
        oneDriveTokenRenewal();
        iCloud();
        iCloudShared();
        iCloudSharedRefusals();
        System.out.println("SourcesTest passed");
    }

    static void routing() {
        check(Sources.sourceOf("https://photos.app.goo.gl/abc123") == Album.Source.GOOGLE, "Google short link");
        check(Sources.sourceOf("https://1drv.ms/f/c/0123456789abcdef/IgExampleShareToken?e=abc") == Album.Source.ONEDRIVE, "OneDrive short link");
        check(Sources.sourceOf("https://onedrive.live.com/?redeem=aHR0cHM6Ly8x") == Album.Source.ONEDRIVE, "OneDrive long link");
        check(Sources.sourceOf("https://www.icloud.com/sharedalbum/#B0aGWZuqDGKYgUq") == Album.Source.ICLOUD, "iCloud classic token");
        check(Sources.sourceOf("https://www.icloud.com/sharedalbum/#D1exampleToken_with-Dash0123") == Album.Source.ICLOUD, "iCloud token with _ and -");
        check(ICloud.token("https://www.icloud.com/sharedalbum/#B0aGWZuqDGKYgUq;CFC7E7D2") .equals("B0aGWZuqDGKYgUq"), "Photo suffix dropped");
        check(Sources.sourceOf("https://photos.icloud.com/shared/album/0A1ExampleAlbum_-xyz") == Album.Source.ICLOUD, "iCloud current link");
        check(ICloud.token("https://photos.icloud.com/shared/album/0A1ExampleAlbum_-xyz/").equals("0A1ExampleAlbum_-xyz"), "Current link ID");
        check(ICloud.current("https://photos.icloud.com/shared/album/0A1ExampleAlbum_-xyz")
            && !ICloud.current("https://www.icloud.com/sharedalbum/#B0aGWZuqDGKYgUq"), "Link kinds");
        String[][] rejected = {
            {"https://contoso.sharepoint.com/:f:/g/personal/x", "Work and school"},
            {"https://contoso-my.sharepoint.com/personal/x", "Work and school"},
            {"https://photos.icloud.com/shared/album/", "Public Website"},
            {"https://photos.icloud.com/shared/album/short", "photos.icloud.com/shared/album/"},
            {"https://photos.icloud.com/", "Public Website"},
            {"https://www.icloud.com/sharedalbum/", "Public Website"},
            {"https://example.com/album", "Use a shared album link from"},
            {"http://1drv.ms/f/s!abc", "Use a shared album link from"},
            {"https://photos.google.com/album/AF1Qip", "private"},
        };
        for (String[] bad : rejected) {
            try {
                Sources.sourceOf(bad[0]);
                throw new AssertionError("Accepted " + bad[0]);
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains(bad[1]), bad[0] + " -> " + expected.getMessage());
            }
        }
    }

    static void isoTime() {
        check(Sources.isoTime("2001-02-03T04:05:06Z") == 981173106000L, "Plain UTC time");
        check(Sources.isoTime("2001-02-03T04:05:06.58Z") == 981173106000L, "Fractional seconds");
        check(Sources.isoTime("") == 0 && Sources.isoTime(null) == 0 && Sources.isoTime("not a date at all!!") == 0, "Bad times");
    }

    static final String API = OneDrive.API;
    static final String DRIVE = "0123456789ABCDEF";
    static final String ROOT = DRIVE + "!sroot0000";

    static String image(String id, int w, int h, String taken) {
        return "{\"id\":\"" + DRIVE + "!" + id + "\",\"name\":\"" + id + ".jpg\",\"file\":{\"mimeType\":\"image/jpeg\"},"
            + "\"image\":{\"width\":" + w + ",\"height\":" + h + "}"
            + (taken == null ? "" : ",\"photo\":{\"takenDateTime\":\"" + taken + "\"}") + "}";
    }

    static void oneDrive() throws Exception {
        String link = "https://1drv.ms/f/c/0123456789abcdef/IgExampleShareToken?e=abc";
        Fake http = new Fake() {
            Response answer(String url, String body, Map<String, String> headers) {
                if (url.startsWith("https://api-badgerp.svc.ms/")) {
                    return ok(url, "{\"authScheme\":\"badger\",\"token\":\"fake-token\",\"expiryTimeUtc\":\"2099-01-01T00:00:00Z\"}");
                }
                if (!"Badger fake-token".equals(headers.get("Authorization"))) return new Response(401, url, new byte[0]);
                if (url.startsWith(API + "/shares/u!")) {
                    return ok(url, "{\"id\":\"" + ROOT + "\",\"folder\":{\"childCount\":5},\"parentReference\":{\"driveId\":\"" + DRIVE + "\"}}");
                }
                if (url.startsWith(API + "/drives/" + DRIVE + "/items/" + ROOT + "/children")) {
                    return ok(url, "{\"value\":[" + image("s001", 4000, 3000, "2019-09-12T15:26:07Z") + ","
                        + "{\"id\":\"" + DRIVE + "!ssub0000\",\"folder\":{\"childCount\":1}},"
                        + "{\"id\":\"" + DRIVE + "!svid0000\",\"file\":{\"mimeType\":\"video/mp4\"},\"video\":{\"duration\":1}},"
                        + "{\"id\":\"" + DRIVE + "!sdoc0000\",\"file\":{\"mimeType\":\"application/pdf\"}}],"
                        + "\"@odata.nextLink\":\"" + API + "/drives/" + DRIVE + "/items/" + ROOT + "/page2\"}");
                }
                if (url.startsWith(API + "/drives/" + DRIVE + "/items/" + ROOT + "/page2")) {
                    // A next page on another site must not be followed with the token.
                    return ok(url, "{\"value\":[" + image("s002", 1280, 720, null) + "],\"@odata.nextLink\":\"https://evil.example/next\"}");
                }
                if (url.startsWith(API + "/drives/" + DRIVE + "/items/" + DRIVE + "!ssub0000/children")) {
                    return ok(url, "{\"value\":[" + image("s003", 3000, 4000, "2015-01-11T23:33:26.58Z") + "]}");
                }
                if (url.contains("/thumbnails/0/")) return new Response(200, url, new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
                return new Response(404, url, new byte[0]);
            }
        };
        FakeShrinker shrinker = new FakeShrinker();
        Sources sources = new Sources(http, shrinker);
        List<Album.Photo> photos = sources.load(link);
        check(photos.size() == 3, "Two photos plus one in a subfolder; no video or document. Got " + photos.size());
        Album.Photo first = photos.get(0);
        check(first.source == Album.Source.ONEDRIVE && first.width == 4000 && first.height == 3000, "OneDrive dimensions");
        check(first.taken == Sources.isoTime("2019-09-12T15:26:07Z") && photos.get(1).taken == 0, "Dates taken");
        check(photos.get(2).height == 4000 && photos.get(2).taken > 0, "Subfolder photo");
        check(first.id.equals("od:" + DRIVE + ":" + DRIVE + "!s001"), "OneDrive IDs: " + first.id);
        check(http.to("https://evil.example").isEmpty(), "Foreign next pages are ignored");
        check(http.to("https://api-badgerp.svc.ms/").size() == 1, "One token for the whole load");
        Call token = http.to("https://api-badgerp.svc.ms/").get(0);
        check(token.type.equals("application/json") && token.body.contains("\"appId\""), "Token request");
        String share = http.to(API + "/shares/").get(0).url;
        String shareId = share.substring((API + "/shares/").length(), share.indexOf("/driveItem"));
        check(shareId.startsWith("u!aHR0cHM6Ly8xZHJ2Lm1z") && shareId.matches("u![A-Za-z0-9_-]+"), "Share ID is unpadded base64url: " + shareId);
        check("autoredeem".equals(http.to(API + "/shares/").get(0).headers.get("Prefer")), "Shares are redeemed");

        byte[] plain = sources.fetch(first, 1280, 800, 0);
        check(plain.length == 5 && shrinker.calls.isEmpty(), "OneDrive sizes photos itself");
        check(http.calls.get(http.calls.size() - 1).url.equals(API + "/drives/" + DRIVE + "/items/" + DRIVE + "!s001/thumbnails/0/c1280x800/content"),
            "Thumbnail address");
        int before = http.calls.size();
        sources.fetch(first, 1280, 800, 65);
        check(http.calls.size() == before && shrinker.calls.equals(Collections.singletonList("5:1280x800@65")),
            "Lower quality re-encodes the cached download on the device: " + shrinker.calls);
    }

    static void oneDriveTokenRenewal() throws Exception {
        int[] issued = {0};
        Fake http = new Fake() {
            Response answer(String url, String body, Map<String, String> headers) {
                if (url.startsWith("https://api-badgerp.svc.ms/")) {
                    issued[0]++;
                    return ok(url, "{\"token\":\"t" + issued[0] + "\",\"expiryTimeUtc\":\"2099-01-01T00:00:00Z\"}");
                }
                // Only the second token works, as if the first had been revoked.
                if (!"Badger t2".equals(headers.get("Authorization"))) return new Response(401, url, new byte[0]);
                return new Response(200, url, new byte[] {(byte) 0xFF, (byte) 0xD8, 9});
            }
        };
        OneDrive oneDrive = new OneDrive(http);
        Album.Photo photo = new Album.Photo(Album.Source.ONEDRIVE, "od:x", API + "/drives/D/items/I/thumbnails/0/", "", 100, 100, 0);
        check(oneDrive.fetch(photo, 10, 10).length == 3 && issued[0] == 2, "A refused token is renewed once");
    }

    static void iCloud() throws Exception {
        String token = "B0aGWZuqDGKYgUq";
        String link = "https://www.icloud.com/sharedalbum/#" + token;
        Fake http = new Fake() {
            Response answer(String url, String body, Map<String, String> headers) {
                if (url.startsWith("https://p23-sharedstreams.icloud.com/")) {
                    return new Response(330, url, "{\"X-Apple-MMe-Host\":\"p42-sharedstreams.icloud.com\"}".getBytes(StandardCharsets.UTF_8));
                }
                if (url.equals("https://p42-sharedstreams.icloud.com/" + token + "/sharedstreams/webstream")) {
                    return ok(url, "{\"streamName\":\"Album\",\"photos\":["
                        + "{\"photoGuid\":\"GUID-0001\",\"dateCreated\":\"2026-01-02T03:04:05Z\",\"derivatives\":{"
                        + "\"456\":{\"checksum\":\"small1\",\"width\":\"257\",\"height\":\"456\",\"fileSize\":\"40000\"},"
                        + "\"2208\":{\"checksum\":\"large1\",\"width\":\"1242\",\"height\":\"2208\",\"fileSize\":\"600000\"}}},"
                        + "{\"photoGuid\":\"GUID-0002\",\"mediaAssetType\":\"video\",\"derivatives\":{"
                        + "\"720p\":{\"checksum\":\"vid\",\"width\":\"1280\",\"height\":\"720\"}}},"
                        + "{\"photoGuid\":\"GUID-0003\",\"derivatives\":{"
                        + "\"2048\":{\"checksum\":\"large3\",\"width\":\"2048\",\"height\":\"1536\"}}}]}");
                }
                if (url.equals("https://p42-sharedstreams.icloud.com/" + token + "/sharedstreams/webasseturls")) {
                    String sum = body.contains("GUID-0001") ? "large1" : "large3";
                    return ok(url, "{\"items\":{\"" + sum + "\":{\"url_location\":\"cvws.icloud-content.com\",\"url_path\":\"/S/" + sum + "\"}}}");
                }
                if (url.startsWith("https://cvws.icloud-content.com/S/")) return new Response(200, url, new byte[1234]);
                return new Response(404, url, new byte[0]);
            }
        };
        FakeShrinker shrinker = new FakeShrinker();
        Sources sources = new Sources(http, shrinker);
        List<Album.Photo> photos = sources.load(link);
        check(photos.size() == 2, "Videos are left out, got " + photos.size());
        Album.Photo first = photos.get(0);
        check(first.source == Album.Source.ICLOUD && first.width == 1242 && first.height == 2208 && first.asset.equals("large1"),
            "Largest version chosen");
        check(first.taken == Sources.isoTime("2026-01-02T03:04:05Z") && photos.get(1).taken == 0, "iCloud dates");
        check(http.to("https://p42-sharedstreams.icloud.com/").size() == 1, "Follows the partition redirect once");
        Call stream = http.to("https://p23-sharedstreams.icloud.com/").get(0);
        check("{\"streamCtag\":null}".equals(stream.body) && "https://www.icloud.com".equals(stream.headers.get("Origin")), "Album request");

        sources.fetch(first, 1280, 800, 0);
        sources.fetch(first, 1024, 640, 0);
        sources.fetch(first, 1024, 640, 60);
        check(http.to("https://cvws.icloud-content.com/").size() == 1, "The full photo downloads once while it is resized");
        check(shrinker.calls.equals(java.util.Arrays.asList("1234:1280x800@" + Sources.DEVICE_QUALITY,
            "1234:1024x640@" + Sources.DEVICE_QUALITY, "1234:1024x640@60")), "Shrunk on the device: " + shrinker.calls);
        Call lookup = http.to("https://p42-sharedstreams.icloud.com/" + token + "/sharedstreams/webasseturls").get(0);
        check(lookup.body.equals("{\"photoGuids\":[\"GUID-0001\"]}"), "Address looked up per photo: " + lookup.body);
        sources.fetch(photos.get(1), 1280, 800, 0);
        check(http.to("https://cvws.icloud-content.com/").size() == 2, "Another photo downloads its own file");

        // A download address on any other site is refused.
        Fake hostile = new Fake() {
            Response answer(String url, String body, Map<String, String> headers) {
                if (url.endsWith("/webasseturls")) {
                    return ok(url, "{\"items\":{\"large1\":{\"url_location\":\"evil.example\",\"url_path\":\"/x\"}}}");
                }
                return new Response(404, url, new byte[0]);
            }
        };
        try {
            new Sources(hostile, shrinker).fetch(first, 100, 100, 0);
            throw new AssertionError("Followed a foreign download address");
        } catch (IOException expected) {
            check(hostile.to("https://evil.example").isEmpty(), "Never contacted the foreign host");
        }
    }

    static final String CK = "https://ckdatabasews.icloud.com/database/1/com.apple.photos.cloud/production/public/records/resolve";
    static final String PARTITION = "https://p77-ckdatabasews.icloud.com:443";
    static final String QUERY = "https://p77-ckdatabasews.icloud.com/database/1/com.apple.photos.cloud/production/shared/records/query";

    static String resolved(String partition) {
        return "{\"results\":[{\"shortGUID\":\"x\",\"zoneID\":{\"zoneName\":\"SharedCollection-0000\",\"ownerRecordName\":\"_owner\","
            + "\"zoneType\":\"REGULAR_CUSTOM_ZONE\"},\"anonymousPublicAccess\":{\"token\":\"tok+en/1\",\"databasePartition\":\""
            + partition + "\"},\"requireAppleLogin\":false}]}";
    }

    /** A CloudKit file field and its type, width and height fields. */
    static String version(String prefix, String type, int w, int h, String url) {
        return "\"" + prefix + "Res\":{\"type\":\"ASSETID\",\"value\":{\"size\":\"1000\",\"downloadURL\":\"" + url + "\"}},"
            + "\"" + prefix + "FileType\":{\"value\":\"" + type + "\"},\"" + prefix + "Width\":{\"value\":" + w + "},"
            + "\"" + prefix + "Height\":{\"value\":" + h + "}";
    }

    static String master(String name, String itemType, String versions) {
        return "{\"recordName\":\"" + name + "\",\"recordType\":\"CPLMaster\",\"fields\":{\"itemType\":{\"value\":\"" + itemType
            + "\"}," + versions + "}}";
    }

    static String asset(String name, String master, long date, String versions) {
        return "{\"recordName\":\"" + name + "\",\"recordType\":\"CPLAsset\",\"fields\":{\"masterRef\":{\"type\":\"REFERENCE\","
            + "\"value\":{\"recordName\":\"" + master + "\"}},\"assetDate\":{\"type\":\"TIMESTAMP\",\"value\":" + date + "}"
            + (versions.isEmpty() ? "" : "," + versions) + "}}";
    }

    static void iCloudShared() throws Exception {
        String id = "0A1ExampleAlbum_-xyz";
        String link = "https://photos.icloud.com/shared/album/" + id;
        String cdn = "https://cvws-h2.icloud-content.com/B/";
        final int[] listings = {0};
        Fake http = new Fake() {
            Response answer(String url, String body, Map<String, String> headers) {
                if (url.startsWith(CK)) return ok(url, resolved(PARTITION));
                if (url.startsWith(QUERY)) {
                    if (!body.contains("continuationMarker")) {
                        listings[0]++;
                        // Download addresses are signed per listing; the second listing issues fresh ones.
                        String sig = "sig" + listings[0];
                        return ok(url, "{\"records\":["
                            + master("M1", "public.heic", version("resJPEGThumb", "public.jpeg", 360, 480, cdn + "t1/${f}?s=" + sig) + ","
                                + version("resJPEGMed", "public.jpeg", 1536, 2048, cdn + "m1/${f}?s=" + sig) + ","
                                + version("resOriginal", "public.heic", 5712, 4284, cdn + "o1/${f}") + ","
                                + version("resVidMed", "com.apple.quicktime-movie", 654, 872, cdn + "v1/${f}")) + ","
                            + asset("A1", "M1", 1790932413878L, "") + ","
                            + master("M2", "com.apple.quicktime-movie", version("resJPEGMed", "public.jpeg", 1920, 1080, cdn + "m2/${f}")) + ","
                            + asset("A2", "M2", 1790932413000L, "")
                            + "],\"continuationMarker\":\"page2\"}");
                    }
                    return ok(url, "{\"records\":["
                        + master("M3", "public.jpeg", version("resJPEGMed", "public.jpeg", 2048, 1536, cdn + "m3/${f}") + ","
                            + version("resJPEGThumb", "public.jpeg", 480, 360, "https://evil.example/t3")) + ","
                        // An edited photo: its edited version lives on the asset and wins over the master's.
                        + asset("A3", "M3", 1790000000000L, version("resJPEGFull", "public.jpeg", 4000, 3000, cdn + "f3/${f}")) + ","
                        + asset("A4", "missing", 0, "")
                        + "]}");
                }
                if (url.startsWith(cdn + "m1/photo.jpg?s=sig1")) return new Response(403, url, new byte[0]);
                if (url.startsWith(cdn)) return new Response(200, url, new byte[2345]);
                return new Response(404, url, new byte[0]);
            }
        };
        FakeShrinker shrinker = new FakeShrinker();
        Sources sources = new Sources(http, shrinker);
        List<Album.Photo> photos = sources.load(link);
        check(photos.size() == 2, "Videos and unpaired assets are left out, got " + photos.size());
        Album.Photo first = photos.get(0), edited = photos.get(1);
        check(first.source == Album.Source.ICLOUD && first.width == 1536 && first.height == 2048, "Upright JPEG size, not the HEIC original");
        check(first.taken == 1790932413878L, "CloudKit date taken");
        check(edited.width == 4000 && edited.height == 3000, "Edited version used");
        Call resolve = http.to(CK).get(0);
        check(resolve.url.endsWith("sharing_url_key=" + id) && resolve.body.contains("\"value\":\"" + id + "\""), "Resolve request: " + resolve.body);
        List<Call> queries = http.to(QUERY);
        check(queries.size() == 2 && queries.get(1).body.contains("\"continuationMarker\":\"page2\""), "Follows the next page");
        check(queries.get(0).url.contains("&publicAccessAuthToken=tok%2Ben%2F1") && queries.get(0).url.contains("sharing_url_key=" + id),
            "Query carries the encoded token: " + queries.get(0).url);
        check(queries.get(0).body.contains("\"zoneID\":{\"zoneName\":\"SharedCollection-0000\",\"ownerRecordName\":\"_owner\","
            + "\"zoneType\":\"REGULAR_CUSTOM_ZONE\"}") && queries.get(0).body.contains("\"resultsLimit\":100"), "Query body: " + queries.get(0).body);

        // The first address has expired, so the album is listed again and the fresh address is used.
        sources.fetch(first, 1280, 800, 0);
        sources.fetch(first, 1024, 640, 60);
        List<Call> downloads = http.to(cdn);
        check(downloads.size() == 2 && downloads.get(0).url.equals(cdn + "m1/photo.jpg?s=sig1")
            && downloads.get(1).url.equals(cdn + "m1/photo.jpg?s=sig2"), "Expired address renewed once: " + downloads.size());
        check(http.to(CK).size() == 2, "Listed again for the fresh address");
        check(shrinker.calls.equals(java.util.Arrays.asList("2345:1280x800@" + Sources.DEVICE_QUALITY, "2345:1024x640@60")),
            "Shrunk on the device: " + shrinker.calls);
        sources.fetch(edited, 400, 300, 0);
        check(http.to(cdn).get(2).url.equals(cdn + "f3/photo.jpg"), "Edited version downloaded: " + http.to(cdn).get(2).url);
        check(http.to("https://evil.example").isEmpty(), "Foreign download addresses are ignored");
        // The smallest version that covers the screen, or the largest when none does.
        List<ICloud.Version> versions = java.util.Arrays.asList(new ICloud.Version("t", 360, 480),
            new ICloud.Version("m", 1536, 2048), new ICloud.Version("f", 3024, 4032));
        check(ICloud.choose(versions, 400, 300).url.equals("t") && ICloud.choose(versions, 1000, 800).url.equals("m") && ICloud.choose(versions, 1920, 1200).url.equals("m")
            && ICloud.choose(versions, 2560, 1600).url.equals("f") && ICloud.choose(versions, 5000, 3000).url.equals("f"), "Version choice");
    }

    static void iCloudSharedRefusals() throws Exception {
        String link = "https://photos.icloud.com/shared/album/0A1ExampleAlbum_-xyz";
        Fake notPublic = new Fake() {
            Response answer(String url, String body, Map<String, String> headers) {
                return ok(url, "{\"results\":[{\"shortGUID\":\"x\",\"requireAppleLogin\":true}]}");
            }
        };
        try {
            new Sources(notPublic, new FakeShrinker()).load(link);
            throw new AssertionError("Loaded an album that needs a sign-in");
        } catch (IOException expected) {
            check(expected.getMessage().contains("Public Website"), expected.getMessage());
        }
        Fake missing = new Fake() {
            Response answer(String url, String body, Map<String, String> headers) { return new Response(404, url, new byte[0]); }
        };
        try {
            new Sources(missing, new FakeShrinker()).load(link);
            throw new AssertionError("Loaded a missing album");
        } catch (IOException expected) {
            check(expected.getMessage().contains("not found"), expected.getMessage());
        }
        // The access token only goes to Apple's database servers.
        Fake hostile = new Fake() {
            Response answer(String url, String body, Map<String, String> headers) {
                if (url.startsWith(CK)) return ok(url, resolved("https://evil.example"));
                return new Response(404, url, new byte[0]);
            }
        };
        try {
            new Sources(hostile, new FakeShrinker()).load(link);
            throw new AssertionError("Sent the token to a foreign host");
        } catch (IOException expected) {
            check(hostile.calls.size() == 1, "Stopped after resolving");
        }
    }
}
