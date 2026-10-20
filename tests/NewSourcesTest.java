// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Nextcloud, Dropbox, Amazon Photos, Flickr and Synology against canned responses shaped like the real ones. */
public final class NewSourcesTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static Http.Response ok(String url, String body) { return new Http.Response(200, url, body.getBytes(StandardCharsets.UTF_8)); }

    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5};

    static final class Call {
        final String method, url, body;
        final Map<String, String> headers;
        Call(String method, String url, String body, Map<String, String> headers) {
            this.method = method; this.url = url; this.body = body; this.headers = headers;
        }
    }

    interface Answer { Http.Response answer(String method, String url, String body, Map<String, String> headers) throws IOException; }

    /** Records every call, including WebDAV ones, and answers through a lambda. */
    static final class Fake implements Http {
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final Answer answer;
        Fake(Answer answer) { this.answer = answer; }
        @Override public Response request(String url, String body, String type, Map<String, String> headers, int max) throws IOException {
            calls.add(new Call(body == null ? "GET" : "POST", url, body, headers));
            return answer.answer(body == null ? "GET" : "POST", url, body, headers);
        }
        @Override public Response propfind(String url, String body, Map<String, String> headers, int max) throws IOException {
            calls.add(new Call("PROPFIND", url, body, headers));
            return answer.answer("PROPFIND", url, body, headers);
        }
        List<Call> to(String prefix) {
            List<Call> found = new ArrayList<>();
            for (Call call : calls) if (call.url.startsWith(prefix)) found.add(call);
            return found;
        }
    }

    static final class FakeShrinker implements Shrinker {
        final List<String> calls = new ArrayList<>();
        public byte[] shrink(byte[] image, int maxWidth, int maxHeight, int quality) {
            calls.add(image.length + ":" + maxWidth + "x" + maxHeight + "@" + quality);
            return JPEG;
        }
    }

    public static void main(String[] args) throws Exception {
        routing();
        nextcloud();
        nextcloudLegacyAndErrors();
        dropbox();
        amazon();
        flickr();
        synology();
        expiredReloads();
        System.out.println("NewSourcesTest passed");
    }

    static void routing() {
        Object[][] cases = {
            {"https://cloud.example.org/s/AbCdEfGh12345", Album.Source.NEXTCLOUD},
            {"https://example.org/nextcloud/index.php/s/AbCdEfGh12345", Album.Source.NEXTCLOUD},
            {"https://www.dropbox.com/scl/fo/abcdef0123456789/AAexampleHash?rlkey=xyz&dl=0", Album.Source.DROPBOX},
            {"https://www.dropbox.com/sh/abcdef0123/AAexampleHash?dl=0", Album.Source.DROPBOX},
            {"https://www.amazon.com/photos/share/AbCdEf0123456789xyz", Album.Source.AMAZON},
            {"https://www.amazon.co.uk/photos/share/AbCdEf0123456789xyz", Album.Source.AMAZON},
            {"https://www.flickr.com/photos/example/albums/72177720300000000", Album.Source.FLICKR},
            {"https://flic.kr/s/aHBqjExample", Album.Source.FLICKR},
            {"https://nas.example.org/mo/sharing/AbCdEf123", Album.Source.SYNOLOGY},
            {"https://nas.example.org:5001/photo/mo/sharing/AbCdEf123", Album.Source.SYNOLOGY},
        };
        for (Object[] c : cases) {
            Album.Source got = Sources.sourceOf((String) c[0]);
            check(got == c[1], c[0] + " -> " + got);
        }
        for (String bad : new String[] {"https://www.dropbox.com/home/Photos", "https://www.amazon.com/dp/B000000"}) {
            try { Sources.sourceOf(bad); throw new AssertionError("Accepted " + bad); } catch (IllegalArgumentException expected) {}
        }
    }

    static String propfind(String root, String[][] entries) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\" xmlns:nc=\"http://nextcloud.org/ns\">");
        for (String[] e : entries) {
            xml.append("<d:response><d:href>").append(root).append(e[0]).append("</d:href><d:propstat><d:prop>");
            if (e[1].equals("folder")) xml.append("<d:resourcetype><d:collection/></d:resourcetype>");
            else xml.append("<d:resourcetype/><d:getcontenttype>").append(e[1]).append("</d:getcontenttype>");
            if (e.length > 2) xml.append("<nc:metadata-photos-size><width>").append(e[2]).append("</width><height>").append(e[3])
                .append("</height></nc:metadata-photos-size><nc:metadata-photos-original_date_time>").append(e[4])
                .append("</nc:metadata-photos-original_date_time>");
            xml.append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>");
        }
        return xml.append("</d:multistatus>").toString();
    }

    static void nextcloud() throws Exception {
        String base = "https://cloud.example.org", token = "AbCdEfGh12345";
        String root = "/public.php/dav/files/" + token;
        Fake http = new Fake((method, url, body, headers) -> {
            if (method.equals("PROPFIND") && url.equals(base + root + "/")) {
                return new Http.Response(207, url, propfind(root, new String[][] {
                    {"/", "folder"}, {"/Trip%20Photos/", "folder"}, {"/cover.jpg", "image/jpeg", "4032", "3024", "1705342483"},
                    {"/clip.mov", "video/quicktime"}, {"/raw.dng", "image/x-dcraw"}, {"/notes.md", "text/markdown"}}).getBytes(StandardCharsets.UTF_8));
            }
            if (method.equals("PROPFIND") && url.equals(base + root + "/Trip%20Photos/")) {
                return new Http.Response(207, url, propfind(root, new String[][] {
                    {"/Trip%20Photos/", "folder"}, {"/Trip%20Photos/beach%20day.heic", "image/heic"}}).getBytes(StandardCharsets.UTF_8));
            }
            if (url.startsWith(base + "/index.php/apps/files_sharing/publicpreview/")) {
                // No preview for HEIC on this server.
                return url.contains("heic") ? new Http.Response(404, url, new byte[0]) : new Http.Response(200, url, new byte[1000]);
            }
            if (url.startsWith(base + "/index.php/s/" + token + "/download")) return new Http.Response(200, url, new byte[3000]);
            return new Http.Response(404, url, new byte[0]);
        });
        FakeShrinker shrinker = new FakeShrinker();
        Sources sources = new Sources(http, shrinker);
        List<Album.Photo> photos = sources.load(base + "/s/" + token);
        check(photos.size() == 2, "Photos from the share and its subfolder only, got " + photos.size());
        Album.Photo cover = photos.get(0), heic = photos.get(1);
        check(cover.width == 4032 && cover.height == 3024 && cover.taken == 1705342483000L, "Size and date from Nextcloud metadata");
        check(heic.width == 0 && heic.taken == 0, "Missing metadata stays unknown");
        check("1".equals(http.to(base + root + "/").get(0).headers.get("Depth")), "One folder level per request");
        check(http.to(base + root + "/Trip%20Photos/").size() == 1, "Subfolders are listed with an encoded path");

        sources.fetch(cover, 1280, 800, 0);
        String preview = http.calls.get(http.calls.size() - 1).url;
        check(preview.equals(base + "/index.php/apps/files_sharing/publicpreview/" + token + "?a=1&file=%2Fcover.jpg&x=1280&y=800"), preview);
        check(shrinker.calls.equals(Collections.singletonList("1000:1280x800@" + Sources.DEVICE_QUALITY)), "Preview shrunk on the device");
        sources.fetch(heic, 1280, 800, 0);
        String original = http.calls.get(http.calls.size() - 1).url;
        check(original.equals(base + "/index.php/s/" + token + "/download?path=%2FTrip%20Photos&files=beach%20day.heic"), original);
        check(shrinker.calls.get(1).startsWith("3000:"), "Whole photo shrunk when there is no preview");
        int before = http.calls.size();
        sources.fetch(heic, 1024, 640, 0);
        check(http.calls.size() == before, "Smaller sizes reuse the download");
    }

    static void nextcloudLegacyAndErrors() throws Exception {
        String base = "https://owncloud.example.org/oc", token = "ZyXwVuTs9876";
        Fake http = new Fake((method, url, body, headers) -> {
            if (url.contains("/public.php/dav/")) return new Http.Response(404, url, new byte[0]);
            if (url.equals(base + "/public.php/webdav/")) {
                if (!("Basic " + Slides.base64((token + ":").getBytes(StandardCharsets.UTF_8))).equals(headers.get("Authorization"))) {
                    return new Http.Response(401, url, new byte[0]);
                }
                return new Http.Response(207, url, propfind("/oc/public.php/webdav", new String[][] {{"/", "folder"}, {"/a.jpg", "image/jpeg"}})
                    .getBytes(StandardCharsets.UTF_8));
            }
            return new Http.Response(404, url, new byte[0]);
        });
        List<Album.Photo> photos = new Sources(http, new FakeShrinker()).load(base + "/index.php/s/" + token);
        check(photos.size() == 1 && photos.get(0).url.startsWith(base + "/index.php/apps/files_sharing/publicpreview/" + token), "ownCloud and older Nextcloud");

        Fake locked = new Fake((method, url, body, headers) -> new Http.Response(401, url, new byte[0]));
        try {
            new Sources(locked, new FakeShrinker()).load("https://cloud.example.org/s/Locked12345");
            throw new AssertionError("Password share accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("password"), expected.getMessage());
        }
    }

    static void dropbox() throws Exception {
        String link = "https://www.dropbox.com/scl/fo/abcdef0123456789/AAexampleHash?rlkey=rl123&st=x&dl=0";
        String preview = "https://ucexample.previews.dropboxusercontent.com/p/thumb/EXAMPLETOKEN/p.jpeg";
        Fake http = new Fake((method, url, body, headers) -> {
            if (url.equals(link)) return new Http.Response(200, url, "<html></html>".getBytes(StandardCharsets.UTF_8),
                Arrays.asList("t=csrf123", "locale=en"));
            if (url.equals("https://www.dropbox.com/list_shared_link_folder_entries")) {
                if (!body.contains("voucher=")) {
                    return ok(url, "{\"entries\":[{\"filename\":\"a.jpg\",\"file_id\":\"id:1\",\"is_dir\":false,\"preview_type\":\"photo\","
                        + "\"thumbnail_url_tmpl\":\"" + preview + "\",\"preview\":{\"dimensions\":{\"width\":4000,\"height\":3000}}},"
                        + "{\"filename\":\"v.mp4\",\"file_id\":\"id:2\",\"is_dir\":false,\"preview_type\":\"video\",\"thumbnail_url_tmpl\":\"" + preview + "\"},"
                        + "{\"filename\":\"Sub\",\"is_dir\":true},"
                        + "{\"filename\":\"b.jpg\",\"file_id\":\"id:3\",\"is_dir\":false,\"preview_type\":\"photo\","
                        + "\"thumbnail_url_tmpl\":\"https://evil.example/p.jpeg\"}],\"has_more_entries\":true,\"next_request_voucher\":\"v2\"}");
                }
                return ok(url, "{\"entries\":[{\"filename\":\"c.jpg\",\"file_id\":\"id:4\",\"is_dir\":false,\"preview_type\":\"photo\","
                    + "\"thumbnail_url_tmpl\":\"" + preview + "\",\"preview\":{\"dimensions\":{\"width\":800,\"height\":600}}}],\"has_more_entries\":false}");
            }
            if (url.startsWith(preview + "?size=")) return new Http.Response(200, url, new byte[2000]);
            return new Http.Response(404, url, new byte[0]);
        });
        FakeShrinker shrinker = new FakeShrinker();
        Sources sources = new Sources(http, shrinker);
        List<Album.Photo> photos = sources.load(link);
        check(photos.size() == 2, "Photos across pages; no video, folder or foreign preview host. Got " + photos.size());
        check(photos.get(0).width == 4000 && photos.get(0).taken == 0, "Dropbox dimensions");
        List<Call> lists = http.to("https://www.dropbox.com/list_shared_link_folder_entries");
        String first = lists.get(0).body;
        for (String field : new String[] {"is_xhr=true", "t=csrf123", "link_key=abcdef0123456789", "link_type=c", "secure_hash=AAexampleHash", "rlkey=rl123"}) {
            check(first.contains(field), "Listing sends " + field + ": " + first);
        }
        check(lists.get(0).headers.get("Cookie").contains("t=csrf123"), "Session cookie sent");
        check(lists.size() == 2 && lists.get(1).body.contains("voucher=v2"), "Second page uses the voucher");
        sources.fetch(photos.get(0), 1280, 800, 0);
        check(http.calls.get(http.calls.size() - 1).url.equals(preview + "?size=1280x960&size_mode=2"), "Smallest preset covering 1280x800");
        sources.fetch(photos.get(1), 2560, 1600, 0);
        check(http.calls.get(http.calls.size() - 1).url.equals(preview + "?size=3200x2400&size_mode=2"), "Large screens get the 3200 preset");
        check(Dropbox.parts("https://www.dropbox.com/sh/abcdef0123/AAexampleHash?dl=0")[1].equals("s"), "Older /sh/ links use type s");
    }

    static void amazon() throws Exception {
        String api = "https://www.amazon.com/drive/v1", share = "AbCdEf0123456789xyz";
        String content = "https://content-na.drive.amazonaws.com/v2/download/presigned/EXAMPLE";
        int[] refreshed = {0};
        Fake http = new Fake((method, url, body, headers) -> {
            if (url.startsWith(api + "/shares/" + share + "?")) return ok(url, "{\"nodeInfo\":{\"id\":\"rootNode01\",\"kind\":\"SHARED_COLLECTION\"}}");
            if (url.startsWith(api + "/nodes/rootNode01/children")) {
                return ok(url, "{\"count\":1,\"data\":[{\"id\":\"albumNode1\",\"kind\":\"VISUAL_COLLECTION\"}]}");
            }
            if (url.startsWith(api + "/nodes/albumNode1/children")) {
                if (url.contains("offset=0&")) {
                    return ok(url, "{\"count\":3,\"data\":[" + file("photoNode1", "image/jpeg", 4608, 3456, "1", content + "1", false) + ","
                        + file("videoNode1", "video/mp4", 1920, 1080, "1", content + "2", true) + "]}");
                }
                return ok(url, "{\"count\":3,\"data\":[" + file("photoNode2", "image/jpeg", 3000, 4000, "6", content + "3", false) + "]}");
            }
            if (url.startsWith(api + "/nodes/photoNode1?")) { refreshed[0]++; return ok(url, "{\"tempLink\":\"" + content + "9\"}"); }
            if (url.startsWith(content + "1")) return new Http.Response(403, url, new byte[0]);
            if (url.startsWith(content)) return new Http.Response(200, url, new byte[500]);
            return new Http.Response(404, url, new byte[0]);
        });
        FakeShrinker shrinker = new FakeShrinker();
        Sources sources = new Sources(http, shrinker);
        List<Album.Photo> photos = sources.load("https://www.amazon.com/photos/share/" + share);
        check(photos.size() == 2, "Photos from the album inside the share, across pages, no video. Got " + photos.size());
        check(photos.get(0).taken == Sources.isoTime("2019-08-02T10:19:28.000Z") && photos.get(0).width == 4608, "Amazon size and date");
        check(photos.get(1).width == 4000 && photos.get(1).height == 3000, "Orientation 6 shows the photo turned");
        byte[] bytes = sources.fetch(photos.get(0), 1280, 800, 0);
        check(bytes.length == 500 && refreshed[0] == 1 && shrinker.calls.isEmpty(), "Expired address refreshed once, sized by Amazon");
        check(http.calls.get(http.calls.size() - 1).url.equals(content + "9?viewBox=1280,800"), "Fresh address with the box");
        check(!Amazon.downloadable("https://evil.example/x") && Amazon.downloadable(content), "Only Amazon's content servers");
    }

    static String file(String id, String type, int w, int h, String orientation, String link, boolean video) {
        return "{\"id\":\"" + id + "\",\"kind\":\"FILE\",\"tempLink\":\"" + link + "\",\"contentProperties\":{\"contentType\":\"" + type + "\","
            + (video ? "\"video\":{\"duration\":3}," : "")
            + "\"image\":{\"width\":" + w + ",\"height\":" + h + ",\"orientation\":\"" + orientation + "\",\"dateTimeOriginal\":\"2019-08-02T10:19:28.000Z\"}}}";
    }

    static void flickr() throws Exception {
        String link = "https://www.flickr.com/photos/example/albums/72177720300000000";
        String key = "0123456789abcdef0123456789abcdef";
        int[] keyFetches = {0};
        Fake http = new Fake((method, url, body, headers) -> {
            if (url.equals(link)) return ok(url, "<script>root.YUI_config.flickr.api.site_key = \"" + key + "\";</script>");
            if (url.startsWith("https://api.flickr.com/services/rest/?method=flickr.urls.lookupUser")) {
                return ok(url, "{\"user\":{\"id\":\"12345678@N00\"},\"stat\":\"ok\"}");
            }
            if (url.startsWith("https://api.flickr.com/services/rest/?method=flickr.photosets.getPhotos")) {
                String photo = "{\"id\":\"%s\",\"media\":\"%s\",\"datetaken\":\"2026-08-06 11:42:42\",\"o_width\":\"8256\",\"o_height\":\"5504\","
                    + "\"url_l\":\"https://live.staticflickr.com/1/%s_l.jpg\",\"width_l\":1024,\"height_l\":683,"
                    + "\"url_h\":\"https://live.staticflickr.com/1/%s_h.jpg\",\"width_h\":1600,\"height_h\":1067,"
                    + "\"url_k\":\"https://live.staticflickr.com/1/%s_k.jpg\",\"width_k\":2048,\"height_k\":1365}";
                String page = url.contains("&page=1&") ? "1" : "2";
                String list = page.equals("1")
                    ? String.format(photo, "p1", "photo", "p1", "p1", "p1") + "," + String.format(photo, "v1", "video", "v1", "v1", "v1")
                    : String.format(photo, "p2", "photo", "p2", "p2", "p2");
                return ok(url, "{\"photoset\":{\"pages\":2,\"photo\":[" + list + "]},\"stat\":\"ok\"}");
            }
            if (url.startsWith("https://live.staticflickr.com/")) return new Http.Response(200, url, new byte[800]);
            if (url.equals("https://www.flickr.com/")) { keyFetches[0]++; return ok(url, "site_key = \"" + key + "\""); }
            return new Http.Response(404, url, new byte[0]);
        });
        FakeShrinker shrinker = new FakeShrinker();
        Sources sources = new Sources(http, shrinker);
        List<Album.Photo> photos = sources.load(link);
        check(photos.size() == 2, "Album photos across two pages, no video. Got " + photos.size());
        check(photos.get(0).width == 8256 && photos.get(0).taken == Flickr.localTime("2026-08-06 11:42:42"), "Original size and local date");
        String getPhotos = http.to("https://api.flickr.com/services/rest/?method=flickr.photosets.getPhotos").get(0).url;
        check(getPhotos.contains("api_key=" + key) && getPhotos.contains("photoset_id=72177720300000000") && getPhotos.contains("user_id=12345678%40N00"),
            "Album call: " + getPhotos);
        sources.fetch(photos.get(0), 1280, 800, 0);
        check(http.calls.get(http.calls.size() - 1).url.endsWith("p1_h.jpg"), "1600 px covers a 1280x800 screen");
        sources.fetch(photos.get(1), 1000, 600, 0);
        check(http.calls.get(http.calls.size() - 1).url.endsWith("p2_l.jpg"), "1024 px covers a 1000x600 box");
        Album.Photo small = photos.get(0);
        check(new Sources(http, shrinker).fetch(small, 4000, 3000, 0) == NewSourcesTest.JPEG
            && http.calls.get(http.calls.size() - 1).url.endsWith("p1_k.jpg"), "Largest size when none covers the box");
        check(keyFetches[0] == 0, "No key refresh while the key works");
    }

    static void synology() throws Exception {
        String link = "https://nas.example.org/photo/mo/sharing/AbCdEf123";
        String api = "https://nas.example.org/photo/mo/sharing/webapi/entry.cgi";
        int[] logins = {0};
        boolean[] expire = {false};
        Fake http = new Fake((method, url, body, headers) -> {
            if (url.equals(link)) return ok(url, "<html></html>");
            if (url.equals(api) && body.contains("SYNO.Core.Sharing.Login")) {
                logins[0]++;
                return new Http.Response(200, url, ("{\"data\":{\"sharing_sid\":\"sid" + logins[0] + "\"},\"success\":true}").getBytes(StandardCharsets.UTF_8),
                    Collections.singletonList("sharing_sid=sid" + logins[0]));
            }
            if (url.equals(api) && body.contains("SYNO.Foto.Browse.Item")) {
                check(("sharing_sid=sid" + logins[0]).equals(headers.get("Cookie")) && "AbCdEf123".equals(headers.get("X-SYNO-SHARING")), "Listing session");
                return ok(url, "{\"success\":true,\"data\":{\"list\":["
                    + "{\"id\":1,\"type\":\"photo\",\"time\":1784800729,\"additional\":{\"resolution\":{\"width\":4032,\"height\":3024},"
                    + "\"orientation\":6,\"thumbnail\":{\"unit_id\":101,\"cache_key\":\"101_abc\",\"xl\":\"ready\"}}},"
                    + "{\"id\":2,\"type\":\"video\",\"time\":1784800729,\"additional\":{\"resolution\":{\"width\":720,\"height\":1280},"
                    + "\"thumbnail\":{\"unit_id\":102,\"cache_key\":\"102_abc\"}}}]}}");
            }
            if (url.startsWith("https://nas.example.org/photo/synofoto/api/v2/p/Thumbnail/get?")) {
                if (expire[0] && "sharing_sid=sid1".equals(headers.get("Cookie"))) return new Http.Response(403, url, new byte[0]);
                return new Http.Response(200, url, new byte[1500]);
            }
            return new Http.Response(404, url, new byte[0]);
        });
        FakeShrinker shrinker = new FakeShrinker();
        Sources sources = new Sources(http, shrinker);
        List<Album.Photo> photos = sources.load(link);
        check(photos.size() == 1, "Videos are left out, got " + photos.size());
        Album.Photo photo = photos.get(0);
        check(photo.width == 3024 && photo.height == 4032 && photo.taken == 1784800729000L, "Turned size and date");
        check(photo.url.equals("https://nas.example.org/photo/synofoto/api/v2/p/Thumbnail/get?type=unit&id=101&cache_key=101_abc&_sharing_id=AbCdEf123&size=xl"),
            photo.url);
        check(http.calls.get(1).body.contains("password=") && logins[0] == 1, "Public shares sign in without a password");
        expire[0] = true;
        sources.fetch(photo, 1280, 800, 0);
        check(logins[0] == 2 && shrinker.calls.size() == 1, "An expired session signs in again once");
    }

    static void expiredReloads() throws Exception {
        String preview = "https://ucexample.previews.dropboxusercontent.com/p/thumb/EXAMPLETOKEN/p.jpeg";
        Fake http = new Fake((method, url, body, headers) -> new Http.Response(410, url, new byte[0]));
        Sources sources = new Sources(http, new FakeShrinker());
        Album.Photo photo = new Album.Photo(Album.Source.DROPBOX, "db:x", preview, "", 100, 100, 0);
        try { sources.fetch(photo, 100, 100, 0); throw new AssertionError("Expired preview accepted"); } catch (Sources.Expired expected) {}
        check(sources.takeExpired() && !sources.takeExpired(), "Expiry is reported once so the albums reload");
    }
}
