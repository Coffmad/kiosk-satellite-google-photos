// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Routes each shared link to its service and downloads photos at a requested size. Worker thread only. */
final class Sources {
    /** JPEG quality for photos the device shrinks itself, when no lower quality was asked for. */
    static final int DEVICE_QUALITY = 85;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;

    private final Http http;
    private final Shrinker shrinker;
    private final Album google;
    private final OneDrive oneDrive;
    private final ICloud iCloud;
    // The last OneDrive download, kept while it is compressed to successive qualities.
    private String cachedKey;
    private byte[] cached;

    Sources(Http http, Shrinker shrinker) {
        this.http = http;
        this.shrinker = shrinker;
        google = new Album(http);
        oneDrive = new OneDrive(http);
        iCloud = new ICloud(http, shrinker);
    }

    /** The service a link belongs to. Throws IllegalArgumentException, with what to paste instead, for anything else. */
    static Album.Source sourceOf(String link) {
        URI uri;
        try {
            uri = new URI(link);
        } catch (URISyntaxException bad) {
            throw new IllegalArgumentException("Not a link: " + link);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException(supported(link));
        if (host.equals("photos.app.goo.gl") || host.equals("goo.gl") || host.equals("photos.google.com")) {
            Album.validate(link);
            return Album.Source.GOOGLE;
        }
        if (OneDrive.handles(host)) return Album.Source.ONEDRIVE;
        if (OneDrive.isBusiness(host)) {
            throw new IllegalArgumentException("Work and school OneDrive links always need a sign-in, so they cannot be shown. "
                + "Use a personal OneDrive folder shared with \"Anyone with the link\"");
        }
        if (ICloud.handles(host)) {
            ICloud.token(link);
            return Album.Source.ICLOUD;
        }
        throw new IllegalArgumentException(supported(link));
    }

    private static String supported(String link) {
        return "Not a supported link: " + link + ". Use a Google Photos, OneDrive or iCloud shared album link";
    }

    List<Album.Photo> load(String link) throws IOException {
        switch (sourceOf(link)) {
            case ONEDRIVE: return oneDrive.load(link);
            case ICLOUD: return iCloud.load(link);
            default: return google.load(link);
        }
    }

    /**
     * The photo within width x height. Quality 0 keeps the service's own encoding, or the device default when
     * the device shrinks the photo. Otherwise it is the JPEG quality, 1 to 100.
     */
    byte[] fetch(Album.Photo photo, int width, int height, int quality) throws IOException {
        switch (photo.source) {
            case ONEDRIVE: {
                String key = photo.id + "|" + width + "x" + height;
                if (!key.equals(cachedKey)) {
                    cached = null;
                    cachedKey = null;
                    cached = oneDrive.fetch(photo, width, height);
                    cachedKey = key;
                }
                // OneDrive has no quality option, so lower quality is applied on the device.
                return quality > 0 ? shrinker.shrink(cached, width, height, quality) : cached;
            }
            case ICLOUD:
                return iCloud.fetch(photo, width, height, quality > 0 ? quality : DEVICE_QUALITY);
            default: {
                Http.Response response = http.request(Album.sized(photo, width, height, quality), null, MAX_IMAGE_BYTES);
                if (response.status != 200) throw new IOException("Photo download returned HTTP " + response.status);
                return response.body;
            }
        }
    }

    /** Epoch milliseconds from an ISO 8601 UTC time such as 2019-09-12T15:26:07.58Z, or 0. java.time needs Android 8. */
    static long isoTime(String text) {
        if (text == null || text.length() < 19) return 0;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        try {
            return format.parse(text.substring(0, 19)).getTime();
        } catch (ParseException bad) {
            return 0;
        }
    }
}
