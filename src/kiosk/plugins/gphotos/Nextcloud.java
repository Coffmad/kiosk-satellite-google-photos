// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads a Nextcloud or ownCloud public share link through the documented public WebDAV endpoint, which needs no
 * sign-in. Works with any server. Subfolders are included. Nextcloud's previews round up to fixed sizes, so they
 * are shrunk on the device, and photos without a preview, such as HEIC on many servers, are downloaded whole.
 */
final class Nextcloud {
    private static final Pattern LINK = Pattern.compile("^(.*?)/(?:index\\.php/)?s/([A-Za-z0-9]{8,64})/?$");
    private static final String DAV = "DAV:";
    private static final String NC = "http://nextcloud.org/ns";
    private static final String PROPFIND = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\" xmlns:nc=\"http://nextcloud.org/ns\">"
        + "<d:prop><d:getcontenttype/><d:resourcetype/><nc:metadata-photos-size/><nc:metadata-photos-original_date_time/>"
        + "</d:prop></d:propfind>";
    static final int MAX_DEPTH = 4;
    static final int MAX_FOLDERS = 200;
    static final int MAX_PHOTOS = 20000;
    private static final int MAX_LISTING_BYTES = 16 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 40 * 1024 * 1024;

    private final Http http;

    Nextcloud(Http http) { this.http = http; }

    /** Whether the link looks like a public share: https://server/s/TOKEN or https://server/index.php/s/TOKEN. */
    static boolean handles(String link) {
        try {
            URI uri = new URI(link);
            return uri.getRawQuery() == null && uri.getPath() != null && LINK.matcher(uri.getPath()).matches();
        } catch (URISyntaxException bad) {
            return false;
        }
    }

    /** Every photo in the share and its subfolders. Videos, RAW files and documents are left out. */
    List<Album.Photo> load(String link) throws IOException {
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException bad) {
            throw new IllegalArgumentException("Not a link: " + link);
        }
        Matcher matcher = LINK.matcher(uri.getPath());
        if (!matcher.matches()) throw new IllegalArgumentException("Not a Nextcloud share link: " + link);
        String base = "https://" + uri.getRawAuthority() + matcher.group(1);
        String token = matcher.group(2);
        // Nextcloud 29 and later have /public.php/dav. Older Nextcloud and ownCloud use /public.php/webdav with the token as user name.
        String root = base + "/public.php/dav/files/" + token;
        Map<String, String> headers = Collections.singletonMap("Depth", "1");
        Http.Response first = http.propfind(root + "/", PROPFIND, headers, MAX_LISTING_BYTES);
        if (first.status != 207) {
            root = base + "/public.php/webdav";
            headers = new java.util.LinkedHashMap<>();
            headers.put("Depth", "1");
            headers.put("Authorization", "Basic " + Slides.base64((token + ":").getBytes(StandardCharsets.UTF_8)));
            first = http.propfind(root + "/", PROPFIND, headers, MAX_LISTING_BYTES);
        }
        if (first.status == 401) throw new IOException("That share has a password. Remove the password or share a different folder");
        if (first.status == 404) throw new IOException("Share not found. Check the link and that sharing is still on");
        if (first.status != 207) throw new IOException("Share returned HTTP " + first.status);
        String rootPath = pathOf(root);

        List<Album.Photo> photos = new ArrayList<>();
        Deque<Object[]> folders = new ArrayDeque<>();
        folders.add(new Object[] {"/", 0, first});
        int visited = 0;
        while (!folders.isEmpty() && visited < MAX_FOLDERS && photos.size() < MAX_PHOTOS) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
            Object[] folder = folders.poll();
            visited++;
            String path = (String) folder[0];
            int depth = (Integer) folder[1];
            Http.Response response = folder[2] != null ? (Http.Response) folder[2]
                : http.propfind(root + encodePath(path), PROPFIND, headers, MAX_LISTING_BYTES);
            if (response.status != 207) continue;
            for (Entry entry : parse(response.body)) {
                String relative = relative(entry.href, rootPath);
                if (relative == null || relative.equals(path) || relative.isEmpty() || relative.equals("/")) continue;
                if (entry.folder) {
                    if (depth < MAX_DEPTH) folders.add(new Object[] {relative.endsWith("/") ? relative : relative + "/", depth + 1, null});
                } else if (photo(entry.type)) {
                    String preview = base + "/index.php/apps/files_sharing/publicpreview/" + token + "?a=1&file=" + query(relative);
                    int slash = relative.lastIndexOf('/');
                    String original = base + "/index.php/s/" + token + "/download?path=" + query(relative.substring(0, Math.max(1, slash)))
                        + "&files=" + query(relative.substring(slash + 1));
                    photos.add(new Album.Photo(Album.Source.NEXTCLOUD, "nc:" + base + "/" + token + relative, preview, original,
                        entry.width, entry.height, entry.taken));
                    if (photos.size() >= MAX_PHOTOS) break;
                }
            }
        }
        if (photos.isEmpty()) throw new IOException("No photos found in that share");
        return photos;
    }

    /** The server's preview near width x height, or the whole photo when the server has no preview for it. */
    byte[] fetch(Album.Photo photo, int width, int height) throws IOException {
        Http.Response preview = http.request(photo.url + "&x=" + Math.max(1, width) + "&y=" + Math.max(1, height), null, null,
            Collections.<String, String>emptyMap(), MAX_IMAGE_BYTES);
        if (preview.status == 200) return preview.body;
        Http.Response original = http.request(photo.asset, null, null, Collections.<String, String>emptyMap(), MAX_IMAGE_BYTES);
        if (original.status != 200) throw new IOException("Photo download returned HTTP " + original.status);
        return original.body;
    }

    /** Image types Android can show. RAW camera files and videos are skipped. */
    static boolean photo(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return t.equals("image/jpeg") || t.equals("image/png") || t.equals("image/webp") || t.equals("image/heic")
            || t.equals("image/heif") || t.equals("image/gif") || t.equals("image/bmp");
    }

    /** One file or folder from a PROPFIND reply. */
    static final class Entry {
        String href = "";
        String type = "";
        boolean folder;
        int width;
        int height;
        long taken;
    }

    static List<Entry> parse(byte[] xml) throws IOException {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            for (String feature : new String[] {"http://apache.org/xml/features/disallow-doctype-decl",
                "http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities"}) {
                try {
                    factory.setFeature(feature, !feature.contains("external"));
                } catch (Exception unsupported) {
                    // Android's parser never fetches external entities anyway.
                }
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            document = builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception bad) {
            throw new IOException("Unexpected response from the share", bad);
        }
        List<Entry> entries = new ArrayList<>();
        NodeList responses = document.getElementsByTagNameNS(DAV, "response");
        for (int i = 0; i < responses.getLength(); i++) {
            Element response = (Element) responses.item(i);
            Entry entry = new Entry();
            entry.href = text(response, DAV, "href");
            entry.type = text(response, DAV, "getcontenttype");
            NodeList types = response.getElementsByTagNameNS(DAV, "resourcetype");
            entry.folder = types.getLength() > 0 && ((Element) types.item(0)).getElementsByTagNameNS(DAV, "collection").getLength() > 0;
            NodeList sizes = response.getElementsByTagNameNS(NC, "metadata-photos-size");
            if (sizes.getLength() > 0) {
                Element size = (Element) sizes.item(0);
                entry.width = OneDrive.integer(child(size, "width"));
                entry.height = OneDrive.integer(child(size, "height"));
            }
            String taken = text(response, NC, "metadata-photos-original_date_time");
            try {
                entry.taken = taken.isEmpty() ? 0 : Long.parseLong(taken.trim()) * 1000;
            } catch (NumberFormatException bad) {
                entry.taken = 0;
            }
            entries.add(entry);
        }
        return entries;
    }

    private static String text(Element parent, String ns, String name) {
        NodeList nodes = parent.getElementsByTagNameNS(ns, name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    /** A child element's text by local name, whatever its namespace. */
    private static String child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            String local = node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(local)) return node.getTextContent().trim();
        }
        return "";
    }

    /** The decoded path inside the share, starting with /, or null for anything outside it. */
    static String relative(String href, String rootPath) {
        String path = href;
        int scheme = path.indexOf("://");
        if (scheme >= 0) {
            int slash = path.indexOf('/', scheme + 3);
            path = slash >= 0 ? path.substring(slash) : "/";
        }
        if (!path.startsWith(rootPath)) return null;
        String rest = decode(path.substring(rootPath.length()));
        if (!rest.startsWith("/")) rest = "/" + rest;
        // A share never contains .. segments. Refuse them rather than resolve them.
        if (rest.contains("/../") || rest.endsWith("/..")) return null;
        return rest;
    }

    private static String pathOf(String url) {
        try {
            return new URI(url).getRawPath();
        } catch (URISyntaxException bad) {
            throw new IllegalArgumentException(bad);
        }
    }

    /** Each segment percent-encoded for a URL path. */
    static String encodePath(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) out.append('/');
            out.append(query(segments[i]));
        }
        return out.toString();
    }

    private static String query(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException bad) {
            return value;
        }
    }
}
