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

    /** A download address that no longer works. Reloading the album issues fresh ones. */
    static final class Expired extends IOException {
        Expired(String message) { super(message); }
    }

    private final Http http;
    private final Shrinker shrinker;
    private final Album google;
    private final OneDrive oneDrive;
    private final ICloud iCloud;
    private final Nextcloud nextcloud;
    private final Dropbox dropbox;
    private final Amazon amazon;
    private final Flickr flickr;
    private final Synology synology;
    // The last download, kept while it is resized or compressed to successive sizes and qualities.
    private String cachedKey;
    private byte[] cached;
    private boolean expired;

    Sources(Http http, Shrinker shrinker) {
        this.http = http;
        this.shrinker = shrinker;
        google = new Album(http);
        oneDrive = new OneDrive(http);
        iCloud = new ICloud(http, shrinker);
        nextcloud = new Nextcloud(http);
        dropbox = new Dropbox(http);
        amazon = new Amazon(http);
        flickr = new Flickr(http);
        synology = new Synology(http);
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
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host.isEmpty()) throw new IllegalArgumentException(supported(link));
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
        if (Dropbox.handles(host)) {
            Dropbox.parts(link);
            return Album.Source.DROPBOX;
        }
        if (Amazon.handles(host)) {
            Amazon.parts(link);
            return Album.Source.AMAZON;
        }
        if (Flickr.handles(host)) return Album.Source.FLICKR;
        // Synology and Nextcloud run on people's own servers, so they are recognized by the link's shape.
        if (Synology.handles(link)) return Album.Source.SYNOLOGY;
        if (Nextcloud.handles(link)) return Album.Source.NEXTCLOUD;
        throw new IllegalArgumentException(supported(link));
    }

    private static String supported(String link) {
        return "Not a supported link: " + link + ". Use a shared album link from Google Photos, OneDrive, iCloud, Dropbox, "
            + "Amazon Photos, Flickr, Nextcloud or Synology Photos";
    }

    List<Album.Photo> load(String link) throws IOException {
        switch (sourceOf(link)) {
            case ONEDRIVE: return oneDrive.load(link);
            case ICLOUD: return iCloud.load(link);
            case NEXTCLOUD: return nextcloud.load(link);
            case DROPBOX: return dropbox.load(link);
            case AMAZON: return amazon.load(link);
            case FLICKR: return flickr.load(link);
            case SYNOLOGY: return synology.load(link);
            default: return google.load(link);
        }
    }

    /**
     * The photo within width x height. Quality 0 keeps the service's own encoding, or the device default when
     * the device shrinks the photo. Otherwise it is the JPEG quality, 1 to 100.
     */
    byte[] fetch(Album.Photo photo, int width, int height, int quality) throws IOException {
        return fetch(photo, width, height, quality, false);
    }

    /**
     * As above. Raw skips the device's own shrinking and compression, for the native screensaver, which decodes the
     * service's file itself, so the photo may be larger than width x height.
     */
    byte[] fetch(Album.Photo photo, int width, int height, int quality, boolean raw) throws IOException {
        try {
            switch (photo.source) {
                case ICLOUD:
                    if (raw) return iCloud.original(photo);
                    return iCloud.fetch(photo, width, height, quality > 0 ? quality : DEVICE_QUALITY);
                case ONEDRIVE:
                case AMAZON: {
                    // Sized by the service. Lower quality is applied on the device, since neither offers it.
                    byte[] sized = cache(photo.id + "|" + width + "x" + height, photo, width, height);
                    return quality > 0 && !raw ? shrinker.shrink(sized, width, height, quality) : sized;
                }
                case NEXTCLOUD:
                case DROPBOX:
                case FLICKR:
                case SYNOLOGY: {
                    // Fixed sizes from the service, shrunk to the screen on the device. The first, largest request is kept
                    // while smaller sizes are tried.
                    byte[] original = cache(photo.id, photo, width, height);
                    return raw ? original : shrinker.shrink(original, width, height, quality > 0 ? quality : DEVICE_QUALITY);
                }
                default: {
                    Http.Response response = http.request(Album.sized(photo, width, height, quality), null, MAX_IMAGE_BYTES);
                    if (response.status != 200) throw new IOException("Photo download returned HTTP " + response.status);
                    return response.body;
                }
            }
        } catch (Expired gone) {
            expired = true;
            throw gone;
        }
    }

    /** Whether a download address expired since the last call, so the albums should be loaded again. */
    boolean takeExpired() {
        boolean was = expired;
        expired = false;
        return was;
    }

    private byte[] cache(String key, Album.Photo photo, int width, int height) throws IOException {
        if (!key.equals(cachedKey)) {
            cached = null;
            cachedKey = null;
            cached = download(photo, width, height);
            cachedKey = key;
        }
        return cached;
    }

    private byte[] download(Album.Photo photo, int width, int height) throws IOException {
        switch (photo.source) {
            case ONEDRIVE: return oneDrive.fetch(photo, width, height);
            case AMAZON: return amazon.fetch(photo, width, height);
            case NEXTCLOUD: return nextcloud.fetch(photo, width, height);
            case DROPBOX: return dropbox.fetch(photo, width, height);
            case FLICKR: return flickr.fetch(photo, width, height);
            case SYNOLOGY: return synology.fetch(photo, width, height);
            default: throw new IOException("No download for " + photo.source);
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
