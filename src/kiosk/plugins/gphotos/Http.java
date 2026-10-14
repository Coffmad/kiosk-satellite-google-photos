// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** The network calls the plugin makes. Tests replace it with canned responses. */
interface Http {
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

    /** GET, or POST when form is not null. Follows HTTPS redirects. Reads at most maxBytes. */
    Response request(String url, String form, int maxBytes) throws IOException;

    /** Abandon the request in progress, from any thread. Socket reads ignore interruption. */
    default void abort() {}

    /** HttpURLConnection client. Runs only on the plugin's worker thread. */
    final class Default implements Http {
        private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
        private volatile HttpURLConnection active;

        @Override
        public void abort() {
            HttpURLConnection connection = active;
            if (connection != null) connection.disconnect();
        }

        @Override
        public Response request(String address, String form, int maxBytes) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            active = connection;
            try {
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(20_000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
                connection.setRequestProperty("Accept", form == null ? "text/html,image/webp,image/*,*/*;q=0.8" : "*/*");
                // Skips the EU cookie consent interstitial, which would otherwise replace the album page.
                connection.setRequestProperty("Cookie", "CONSENT=YES+; SOCS=CAI");
                if (form != null) {
                    byte[] payload = form.getBytes(StandardCharsets.UTF_8);
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
                    connection.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                byte[] body = stream == null ? new byte[0] : read(stream, maxBytes);
                return new Response(status, connection.getURL().toString(), body);
            } finally {
                active = null;
                connection.disconnect();
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
