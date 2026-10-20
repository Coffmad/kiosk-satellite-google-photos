// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** The network calls the plugin makes. Tests replace it with canned responses. */
interface Http {
    String FORM = "application/x-www-form-urlencoded;charset=UTF-8";

    final class Response {
        final int status;
        /** Final URL after redirects. */
        final String url;
        final byte[] body;

        Response(int status, String url, byte[] body) {
            this.status = status;
            this.url = url;
            this.body = body;
        }

        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    /**
     * GET, or POST when body is not null. Follows HTTPS redirects, dropping the extra headers when a redirect
     * leaves the original host so that a token never reaches a download server. Reads at most maxBytes.
     */
    Response request(String url, String body, String contentType, Map<String, String> headers, int maxBytes) throws IOException;

    /** GET, or a form POST when form is not null. */
    default Response request(String url, String form, int maxBytes) throws IOException {
        return request(url, form, FORM, Collections.<String, String>emptyMap(), maxBytes);
    }

    /** Abandon the request in progress, from any thread. Socket reads ignore interruption. */
    default void abort() {}

    /** HttpURLConnection client. Runs only on the plugin's worker thread. */
    final class Default implements Http {
        private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
        private static final int MAX_REDIRECTS = 5;
        private volatile HttpURLConnection active;

        @Override
        public void abort() {
            HttpURLConnection connection = active;
            if (connection != null) connection.disconnect();
        }

        @Override
        public Response request(String address, String body, String contentType, Map<String, String> headers, int maxBytes)
            throws IOException {
            URL url = new URL(address);
            String host = url.getHost();
            for (int redirect = 0; ; redirect++) {
                if (!"https".equals(url.getProtocol())) throw new IOException("Refusing a non-HTTPS address");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                active = connection;
                try {
                    connection.setConnectTimeout(15_000);
                    // Apple's album list can take half a minute for a large album.
                    connection.setReadTimeout(90_000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", USER_AGENT);
                    connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
                    connection.setRequestProperty("Accept", body == null ? "text/html,image/webp,image/*,*/*;q=0.8" : "*/*");
                    String to = url.getHost().toLowerCase(java.util.Locale.ROOT);
                    if (to.equals("google.com") || to.endsWith(".google.com")) {
                        // Skips the EU cookie consent interstitial, which would otherwise replace the album page.
                        connection.setRequestProperty("Cookie", "CONSENT=YES+; SOCS=CAI");
                    }
                    if (url.getHost().equalsIgnoreCase(host)) {
                        for (Map.Entry<String, String> header : headers.entrySet()) {
                            connection.setRequestProperty(header.getKey(), header.getValue());
                        }
                    }
                    if (body != null) {
                        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                        connection.setRequestMethod("POST");
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", contentType);
                        connection.setFixedLengthStreamingMode(payload.length);
                        try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
                    }
                    int status = connection.getResponseCode();
                    String location = connection.getHeaderField("Location");
                    if (status >= 301 && status <= 308 && status != 304 && status != 305 && location != null) {
                        if (redirect >= MAX_REDIRECTS) throw new IOException("Too many redirects");
                        url = new URL(url, location);
                        // Like a browser, a redirected POST becomes a GET unless the server asks to repeat it.
                        if (status != 307 && status != 308) body = null;
                        continue;
                    }
                    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    byte[] bytes = stream == null ? new byte[0] : read(stream, maxBytes);
                    return new Response(status, url.toString(), bytes);
                } finally {
                    active = null;
                    connection.disconnect();
                }
            }
        }

        private static byte[] read(InputStream stream, int maxBytes) throws IOException {
            try (InputStream in = stream) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                    if (out.size() + n > maxBytes) throw new IOException("Response larger than " + maxBytes + " bytes");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        }
    }
}
