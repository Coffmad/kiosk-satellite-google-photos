// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.nio.charset.StandardCharsets;

/**
 * Builds the inline screensaver documents. Plugin screensaver pages cannot load anything from the
 * network, so each document embeds its photos as data URIs. KS keeps the shown document until a new
 * one has painted, and each new document starts on the previous photo and fades to the next, so
 * the slideshow never flashes black.
 */
final class Slides {
    /** KS's limit for an inline screensaver document, in UTF-8 bytes. */
    static final int MAX_DOCUMENT_BYTES = 512 * 1024;
    /** Room for the markup and script around the images. */
    static final int OVERHEAD_BYTES = 8 * 1024;

    /** How one photo sits on the screen. */
    enum Fit {
        /** Covers the screen, cropping what does not fit. */
        FILL,
        /** The whole photo over a blurred, enlarged copy of itself. */
        BLUR,
        /** The whole photo over the background color. */
        PLAIN
    }

    /** A photo ready to show, embedded in a page here or decoded for the native screensaver. */
    interface Frame {
        Album.Photo photo();
    }

    /** A downloaded photo ready to embed. */
    static final class Image implements Frame {
        final String dataUri;
        final Album.Photo photo;
        /** Ken Burns zoom origin, as CSS percentages. */
        final int originX;
        final int originY;

        Image(byte[] bytes, Album.Photo photo, int originX, int originY) {
            this.dataUri = "data:" + mime(bytes) + ";base64," + base64(bytes);
            this.photo = photo;
            this.originX = originX;
            this.originY = originY;
        }

        int bytes() { return dataUri.length(); }

        @Override public Album.Photo photo() { return photo; }
    }

    /** One photo as it appears in a document. */
    static final class Shot {
        final Image image;
        final Fit fit;
        /** Plain text under the photo, or empty. */
        final String caption;

        Shot(Image image, Fit fit, String caption) {
            this.image = image;
            this.fit = fit;
            this.caption = caption == null ? "" : caption;
        }
    }

    /** Settings shared by every photo in a document. */
    static final class Style {
        final boolean crossfade;
        final double fadeSeconds;
        final boolean zoom;
        /** Extra scale at the end of a photo's time, such as 0.1 for 10%. */
        final double zoomAmount;
        final double intervalSeconds;
        /** Vertical crop position for filled photos, 0 for the top to 100 for the bottom. */
        final int focusY;
        /** #RRGGBB behind photos that do not fill the screen. */
        final String background;

        Style(boolean crossfade, double fadeSeconds, boolean zoom, double zoomAmount, double intervalSeconds,
              int focusY, String background) {
            this.crossfade = crossfade;
            this.fadeSeconds = fadeSeconds;
            this.zoom = zoom;
            this.zoomAmount = zoomAmount;
            this.intervalSeconds = intervalSeconds;
            this.focusY = focusY;
            this.background = background != null && background.matches("#[0-9a-fA-F]{6}") ? background : "#000000";
        }
    }

    private Slides() {}

    /** Largest data URI one photo may use, so that the document stays below the limit. */
    static int imageBudget(boolean crossfade) {
        int room = MAX_DOCUMENT_BYTES - OVERHEAD_BYTES;
        return crossfade ? room / 2 : room;
    }

    /**
     * A slide showing current. With previous, the document opens on previous, which has been on screen
     * for previousElapsed seconds, and fades to current.
     */
    static String slide(Shot previous, double previousElapsed, Shot current, Style style) {
        boolean fade = style.crossfade && previous != null
            && previous.image.bytes() + current.image.bytes() + OVERHEAD_BYTES <= MAX_DOCUMENT_BYTES;
        // Each photo zooms over its whole time on screen, including both fades.
        double zoomSeconds = style.intervalSeconds + (style.crossfade ? style.fadeSeconds * 2 : 0);
        StringBuilder html = new StringBuilder(current.image.bytes() + (fade ? previous.image.bytes() : 0) + OVERHEAD_BYTES);
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
            .append("html,body{margin:0;width:100%;height:100%;overflow:hidden;background:").append(style.background).append("}")
            .append(".slide,.slide img{position:absolute;left:0;top:0;width:100%;height:100%}")
            .append(".slide{overflow:hidden;background:").append(style.background).append("}")
            .append(".photo{object-fit:contain}")
            .append(".fill .photo{object-fit:cover;object-position:50% ").append(style.focusY).append("%}")
            .append(".back{object-fit:cover;filter:blur(36px) brightness(.5);transform:scale(1.2)}")
            .append(".caption{position:absolute;right:3vmin;bottom:3vmin;color:#fff;font:400 2.6vmin sans-serif;")
            .append("text-shadow:0 0 .4vmin #000,0 0 1.5vmin #000;opacity:.85}")
            .append("#next{opacity:").append(fade ? "0" : "1").append("}")
            .append("#next.in{opacity:1;transition:opacity ").append(style.fadeSeconds).append("s ease-in-out}")
            .append("@keyframes zoom{from{transform:scale(1)}to{transform:scale(").append(1 + style.zoomAmount).append(")}}")
            .append("</style></head><body><script>(function(){")
            .append("var zoom=").append(style.zoom).append(",seconds=").append(zoomSeconds).append(";")
            .append("function slide(id,src,fit,caption,x,y,elapsed){")
            .append("var box=document.createElement('div');box.className='slide '+fit;box.id=id;")
            .append("if(fit=='blur'){var back=new Image();back.className='back';back.src=src;box.appendChild(back);}")
            .append("var photo=new Image();photo.className='photo';photo.src=src;")
            .append("if(zoom){photo.style.transformOrigin=x+'% '+y+'%';")
            .append("photo.style.animation='zoom '+seconds+'s linear '+(-elapsed)+'s forwards';}")
            .append("box.appendChild(photo);")
            .append("if(caption){var text=document.createElement('div');text.className='caption';text.textContent=caption;box.appendChild(text);}")
            .append("document.body.appendChild(box);return box;}");
        if (fade) shot(html, "prev", previous, Math.max(0, previousElapsed));
        html.append("var next=");
        shot(html, "next", current, 0);
        if (fade) {
            // KS reveals this document once it has painted. The short wait lets that swap land first.
            html.append("var photo=next.querySelector('.photo');")
                .append("function go(){setTimeout(function(){next.className+=' in';},120);}")
                .append("if(photo.complete)go();else{photo.onload=go;photo.onerror=go;}");
        }
        html.append("}());</script></body></html>");
        return html.toString();
    }

    private static void shot(StringBuilder html, String id, Shot shot, double elapsed) {
        html.append("slide('").append(id).append("',").append(Json.quote(shot.image.dataUri)).append(",'")
            .append(shot.fit.name().toLowerCase(java.util.Locale.ROOT)).append("',").append(Json.quote(shot.caption)).append(',')
            .append(shot.image.originX).append(',').append(shot.image.originY).append(',').append(elapsed).append(");");
    }

    /** A plain message, such as setup instructions or a loading error. */
    static String message(String title, String detail) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>"
            + "html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}"
            + "body{display:flex;align-items:center;justify-content:center;font-family:sans-serif;"
            + "color:#bbb;text-align:center}div{max-width:80vw}h1{font-size:4vmin;font-weight:400;margin:0 0 2vmin;color:#eee}"
            + "p{font-size:2.6vmin;margin:0;line-height:1.4}</style></head><body><div><h1>" + escape(title) + "</h1>"
            + (detail == null || detail.isEmpty() ? "" : "<p>" + escape(detail) + "</p>")
            + "</div></body></html>";
    }

    static int utf8Length(String html) { return html.getBytes(StandardCharsets.UTF_8).length; }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The image type from its leading bytes. Google may answer a WebP request with JPEG. */
    static String mime(byte[] bytes) {
        if (bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (bytes.length > 11 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
            && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        if (bytes.length > 3 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length > 2 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        throw new IllegalArgumentException("Not an image");
    }

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** java.util.Base64 needs Android 8, and android.util.Base64 is missing from the JVM tests. */
    static String base64(byte[] data) {
        char[] out = new char[(data.length + 2) / 3 * 4];
        int o = 0;
        for (int i = 0; i < data.length; i += 3) {
            int b = (data[i] & 0xFF) << 16;
            if (i + 1 < data.length) b |= (data[i + 1] & 0xFF) << 8;
            if (i + 2 < data.length) b |= data[i + 2] & 0xFF;
            out[o++] = ALPHABET[b >> 18 & 63];
            out[o++] = ALPHABET[b >> 12 & 63];
            out[o++] = i + 1 < data.length ? ALPHABET[b >> 6 & 63] : '=';
            out[o++] = i + 2 < data.length ? ALPHABET[b & 63] : '=';
        }
        return new String(out);
    }
}
