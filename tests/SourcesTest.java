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
        System.out.println("SourcesTest passed");
    }

    static void routing() {
        check(Sources.sourceOf("https://photos.app.goo.gl/abc123") == Album.Source.GOOGLE, "Google short link");
        check(Sources.sourceOf("https://1drv.ms/f/c/0123456789abcdef/IgExampleShareToken?e=abc") == Album.Source.ONEDRIVE, "OneDrive short link");
        check(Sources.sourceOf("https://onedrive.live.com/?redeem=aHR0cHM6Ly8x") == Album.Source.ONEDRIVE, "OneDrive long link");
        check(Sources.sourceOf("https://www.icloud.com/sharedalbum/#B0aGWZuqDGKYgUq") == Album.Source.ICLOUD, "iCloud classic token");
        check(Sources.sourceOf("https://www.icloud.com/sharedalbum/#D1exampleToken_with-Dash0123") == Album.Source.ICLOUD, "iCloud token with _ and -");
        check(ICloud.token("https://www.icloud.com/sharedalbum/#B0aGWZuqDGKYgUq;CFC7E7D2") .equals("B0aGWZuqDGKYgUq"), "Photo suffix dropped");
        String[][] rejected = {
            {"https://contoso.sharepoint.com/:f:/g/personal/x", "Work and school"},
            {"https://contoso-my.sharepoint.com/personal/x", "Work and school"},
            {"https://photos.icloud.com/shared/album/EXAMPLE/", "Public Website"},
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
}
