// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** The network calls the plugin makes. Tests replace it with canned responses. */
interface Http {
    String FORM = "application/x-www-form-urlencoded;charset=UTF-8";

    final class Response {
        final int status;
        /** Final URL after redirects. */
        final String url;
        final byte[] body;
        /** name=value pairs from every Set-Cookie header along the way, for services that need a session. */
        final List<String> cookies;

        Response(int status, String url, byte[] body) { this(status, url, body, Collections.<String>emptyList()); }

        Response(int status, String url, byte[] body, List<String> cookies) {
            this.status = status;
            this.url = url;
            this.body = body;
            this.cookies = cookies;
        }

        String text() { return new String(body, StandardCharsets.UTF_8); }

        /** The value of a cookie this response set, or null. */
        String cookie(String name) {
            for (String pair : cookies) if (pair.startsWith(name + "=")) return pair.substring(name.length() + 1);
            return null;
        }
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

    /** A WebDAV PROPFIND, which HttpURLConnection refuses to send. Does not follow redirects. */
    default Response propfind(String url, String body, Map<String, String> headers, int maxBytes) throws IOException {
        throw new IOException("WebDAV is unavailable");
    }

    /** Abandon the request in progress, from any thread. Socket reads ignore interruption. */
    default void abort() {}

    /** HttpURLConnection client. Runs only on the plugin's worker thread. */
    final class Default implements Http {
        private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
        private static final int MAX_REDIRECTS = 5;
        private volatile HttpURLConnection active;
        private volatile Socket activeSocket;

        @Override
        public void abort() {
            HttpURLConnection connection = active;
            if (connection != null) connection.disconnect();
            Socket socket = activeSocket;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Already closing.
                }
            }
        }

        @Override
        public Response request(String address, String body, String contentType, Map<String, String> headers, int maxBytes)
            throws IOException {
            URL url = new URL(address);
            String host = url.getHost();
            List<String> cookies = new ArrayList<>();
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
                    String to = url.getHost().toLowerCase(Locale.ROOT);
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
                    for (Map.Entry<String, List<String>> field : connection.getHeaderFields().entrySet()) {
                        if (field.getKey() != null && field.getKey().equalsIgnoreCase("Set-Cookie")) {
                            for (String value : field.getValue()) cookies.add(pair(value));
                        }
                    }
                    String location = connection.getHeaderField("Location");
                    if (status >= 301 && status <= 308 && status != 304 && status != 305 && location != null) {
                        if (redirect >= MAX_REDIRECTS) throw new IOException("Too many redirects");
                        url = new URL(url, location);
                        // Like a browser, a redirected POST becomes a GET unless the server asks to repeat it.
                        if (status != 307 && status != 308) body = null;
                        continue;
                    }
                    InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    // Some services compress replies even unasked. Android removes the header when it decompresses itself.
                    if (stream != null && "gzip".equalsIgnoreCase(connection.getContentEncoding())) stream = new GZIPInputStream(stream);
                    byte[] bytes = stream == null ? new byte[0] : read(stream, maxBytes);
                    return new Response(status, url.toString(), bytes, cookies);
                } finally {
                    active = null;
                    connection.disconnect();
                }
            }
        }

        /** A minimal HTTP/1.1 exchange over TLS, with the platform's certificate and host name checks. */
        @Override
        public Response propfind(String address, String body, Map<String, String> headers, int maxBytes) throws IOException {
            URL url = new URL(address);
            if (!"https".equals(url.getProtocol())) throw new IOException("Refusing a non-HTTPS address");
            String host = url.getHost();
            int port = url.getPort() > 0 ? url.getPort() : 443;
            Socket socket = new Socket();
            activeSocket = socket;
            try {
                socket.connect(new InetSocketAddress(host, port), 15_000);
                socket.setSoTimeout(90_000);
                // TLS over the plain socket, with the host name for SNI and certificate checks.
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
                SSLParameters parameters = tls.getSSLParameters();
                // Rejects a certificate for any other host, as HttpsURLConnection does.
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                StringBuilder request = new StringBuilder();
                request.append("PROPFIND ").append(url.getFile().isEmpty() ? "/" : url.getFile()).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(url.getPort() > 0 ? host + ":" + port : host).append("\r\n")
                    .append("User-Agent: ").append(USER_AGENT).append("\r\n")
                    .append("Content-Type: application/xml; charset=utf-8\r\n")
                    .append("Content-Length: ").append(payload.length).append("\r\n")
                    .append("Connection: close\r\n");
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    String name = header.getKey(), value = header.getValue();
                    if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                        throw new IOException("Invalid header");
                    }
                    request.append(name).append(": ").append(value).append("\r\n");
                }
                request.append("\r\n");
                OutputStream out = tls.getOutputStream();
                out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
                out.write(payload);
                out.flush();
                InputStream in = new BufferedInputStream(tls.getInputStream());
                String statusLine = line(in);
                String[] parts = statusLine.split(" ", 3);
                if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) throw new IOException("Unexpected WebDAV response");
                int status = Integer.parseInt(parts[1]);
                long length = -1;
                boolean chunked = false;
                for (String header; !(header = line(in)).isEmpty(); ) {
                    int colon = header.indexOf(':');
                    if (colon < 0) continue;
                    String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT), value = header.substring(colon + 1).trim();
                    if (name.equals("content-length")) length = Long.parseLong(value);
                    if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) chunked = true;
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (chunked) {
                    for (long size; (size = Long.parseLong(line(in).split(";", 2)[0].trim(), 16)) > 0; ) {
                        copy(in, bytes, size, maxBytes);
                        line(in);
                    }
                } else if (length >= 0) {
                    copy(in, bytes, length, maxBytes);
                } else {
                    copy(in, bytes, Long.MAX_VALUE, maxBytes);
                }
                return new Response(status, address, bytes.toByteArray());
            } catch (NumberFormatException bad) {
                throw new IOException("Unexpected WebDAV response", bad);
            } finally {
                activeSocket = null;
                socket.close();
            }
        }

        private static String line(InputStream in) throws IOException {
            StringBuilder text = new StringBuilder();
            for (int c; (c = in.read()) != '\n'; ) {
                if (c < 0) throw new IOException("Connection closed");
                if (c != '\r') text.append((char) c);
                if (text.length() > 16_384) throw new IOException("Response header too long");
            }
            return text.toString();
        }

        private static void copy(InputStream in, ByteArrayOutputStream out, long count, int maxBytes) throws IOException {
            byte[] buffer = new byte[16384];
            while (count > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, count));
                if (n < 0) {
                    // Without a length the body ends with the connection. With one, ending early is an error.
                    if (count != Long.MAX_VALUE) throw new IOException("Connection closed");
                    return;
                }
                if (out.size() + n > maxBytes) throw new IOException("Response larger than " + maxBytes + " bytes");
                out.write(buffer, 0, n);
                if (count != Long.MAX_VALUE) count -= n;
            }
        }

        /** The name=value part of a Set-Cookie header. */
        private static String pair(String header) {
            int end = header.indexOf(';');
            return (end >= 0 ? header.substring(0, end) : header).trim();
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
