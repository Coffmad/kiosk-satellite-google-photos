// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.gphotos;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * A stock screensaver mode that shows photos from public Google Photos shared albums. Callbacks only
 * record state and hand work to one worker thread, which loads albums and downloads photos without
 * holding up KS's three-second callback deadline.
 */
public final class GooglePhotosPlugin implements KioskPlugin {
    static final String KEY = "photos";
    static final String TITLE = "Google Photos";
    // The screensaver.view value while this renderer is on screen.
    static final String MODE = "plugin:google-photos-screensaver:" + KEY;
    private static final String UNKNOWN = "?";
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final long MINUTE = 60_000L;
    /** Photos within this share of square suit either screen orientation. */
    private static final double SQUARE = 0.05;

    // Setting options, as declared in the manifest.
    static final String FIT_FILL = "Fill screen (crop)";
    static final String FIT_BLUR = "Whole photo, blurred background";
    static final String FIT_PLAIN = "Whole photo, plain background";
    static final String REDUCE_QUALITY = "Reduce quality (keep resolution)";
    static final String DATE_MONTH = "Month and year";
    static final String DATE_FULL = "Full date";

    /** The validated settings that matter to the worker. */
    static final class Config {
        final String links;
        final long refreshMs;
        /** Show only photos whose orientation matches the screen's. */
        final boolean matchOrientation;
        final long intervalMs;
        final boolean shuffle;
        final boolean crossfade;
        final double fadeSeconds;
        final boolean zoom;
        final double zoomAmount;
        final Slides.Fit horizontalFit;
        final Slides.Fit verticalFit;
        /** Largest share of a photo that Fill may crop away, 0 to 1. */
        final double cropLimit;
        final int focusY;
        final String background;
        final String date;
        final int maxSize;
        final boolean reduceQuality;

        Config(Map<String, Object> settings) {
            links = String.join(" ", Album.links(string(settings.get("albumLinks"), "")));
            refreshMs = Math.round(number(settings.get("refreshHours"), 6, 1, 168) * 60 * MINUTE);
            matchOrientation = Boolean.TRUE.equals(settings.get("matchOrientation"));
            intervalMs = Math.round(number(settings.get("interval"), 30, 5, 3600) * 1000);
            shuffle = !"Album order".equals(settings.get("order"));
            crossfade = !"Cut".equals(settings.get("transition"));
            fadeSeconds = number(settings.get("fadeSeconds"), 1.5, 0.5, 10);
            zoom = Boolean.TRUE.equals(settings.get("zoom"));
            zoomAmount = number(settings.get("zoomAmount"), 10, 1, 50) / 100;
            horizontalFit = fit(settings.get("horizontalFit"), Slides.Fit.FILL);
            verticalFit = fit(settings.get("verticalFit"), Slides.Fit.BLUR);
            cropLimit = number(settings.get("cropLimit"), 30, 0, 100) / 100;
            Object focus = settings.get("cropFocus");
            focusY = "Top".equals(focus) ? 0 : "Upper part".equals(focus) ? 30 : 50;
            background = string(settings.get("backgroundColor"), "#000000");
            Object dates = settings.get("dateCaption");
            date = DATE_MONTH.equals(dates) || DATE_FULL.equals(dates) ? (String) dates : "";
            maxSize = "1280 px".equals(settings.get("maxSize")) ? 1280 : "2560 px".equals(settings.get("maxSize")) ? 2560 : 1920;
            reduceQuality = REDUCE_QUALITY.equals(settings.get("oversize"));
        }

        Slides.Style style() {
            return new Slides.Style(crossfade, fadeSeconds, zoom, zoomAmount, intervalMs / 1000.0, focusY, background);
        }

        /** Settings that change how photos are downloaded, so a prefetched photo can be reused. */
        String sizing() {
            return horizontalFit + "|" + verticalFit + "|" + cropLimit + "|" + crossfade + "|" + maxSize + "|" + reduceQuality;
        }

        private String key() {
            return links + "|" + refreshMs + "|" + matchOrientation + "|" + intervalMs + "|" + shuffle + "|" + fadeSeconds + "|" + zoom + "|"
                + zoomAmount + "|" + focusY + "|" + background + "|" + date + "|" + sizing();
        }

        @Override public boolean equals(Object other) { return other instanceof Config && key().equals(((Config) other).key()); }

        @Override public int hashCode() { return key().hashCode(); }

        private static Slides.Fit fit(Object value, Slides.Fit fallback) {
            if (FIT_FILL.equals(value)) return Slides.Fit.FILL;
            if (FIT_BLUR.equals(value)) return Slides.Fit.BLUR;
            if (FIT_PLAIN.equals(value)) return Slides.Fit.PLAIN;
            return fallback;
        }

        private static String string(Object value, String fallback) { return value instanceof String ? (String) value : fallback; }

        private static double number(Object value, double fallback, double min, double max) {
            double n = value instanceof Number ? ((Number) value).doubleValue() : fallback;
            return Double.isFinite(n) ? Math.max(min, Math.min(max, n)) : fallback;
        }
    }

    private final Http http;
    private final Random random = new Random();

    // Shared between callbacks and the worker.
    private volatile PluginHost host;
    private volatile Config config;
    private volatile boolean active;
    private volatile boolean screensaverActive;
    private volatile String view = UNKNOWN;
    private volatile boolean screenOn = true;
    private volatile int screenWidth;
    private volatile int screenHeight;
    private volatile boolean stopped;
    private volatile boolean showing;
    private ScheduledExecutorService worker;
    private ScheduledFuture<?> pending;
    private int generation;

    // Worker thread only.
    private String loadedLinks;
    private long loadedAt;
    private List<Album.Photo> photos = Collections.emptyList();
    private final List<Integer> bag = new ArrayList<>();
    private int position = -1;
    private String lastPhotoId;
    private Slides.Image shown;
    private long shownAt;
    private Album.Photo prefetchedPhoto;
    private Slides.Image prefetched;
    private String prefetchedSizing;
    private String lastDocument;
    private int failures;

    public GooglePhotosPlugin() { this(new Http.Default()); }

    GooglePhotosPlugin(Http http) { this.http = http; }

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        stopped = false;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "google-photos-screensaver");
            thread.setDaemon(true);
            return thread;
        });
        try {
            host.subscribe("screensaver.state");
            host.subscribe("screensaver.view");
            host.subscribe("screen.state");
            host.executeCommand("isScreensaverActive", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                if (ok) { screensaverActive = Boolean.TRUE.equals(data); update(); }
            });
            readScreen(host);
        } catch (RuntimeException unavailable) {
            // Without state events the slideshow simply keeps cycling.
            host.log("Screensaver state unavailable: " + unavailable.getMessage());
            active = true;
        }
        // Lists the mode right away. The worker replaces this once photos arrive.
        publish(Slides.message("Loading Google Photos...", null));
        configure(settings);
    }

    /** The screen size, read again each time the screensaver starts since the kiosk may have been turned. */
    private void readScreen(PluginHost host) {
        host.executeCommand("getDeviceInfo", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            if (!ok || !(data instanceof Map)) return;
            Object width = ((Map<?, ?>) data).get("screenWidth"), height = ((Map<?, ?>) data).get("screenHeight");
            if (width instanceof Number && height instanceof Number) {
                int oldWidth = screenWidth, oldHeight = screenHeight;
                screenWidth = ((Number) width).intValue();
                screenHeight = ((Number) height).intValue();
                // The kiosk turned: replace a photo that no longer suits it.
                Config c = config;
                if (c != null && c.matchOrientation && oldWidth > 0 && oldHeight > 0
                    && (oldWidth > oldHeight) != (screenWidth > screenHeight)) schedule(0, false);
            }
        });
    }

    @Override
    public void configure(Map<String, Object> settings) {
        Config next = new Config(settings);
        if (next.equals(config)) return;
        config = next;
        schedule(0, false);
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("next".equals(command)) schedule(0, true);
        else if ("reload".equals(command)) worker.execute(() -> { loadedLinks = null; run(false, currentGeneration()); });
        else throw new IllegalArgumentException("Unknown command: " + command);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        if (payload == null) return;
        if ("ks.screensaver.state".equals(event)) {
            screensaverActive = Boolean.TRUE.equals(payload.get("active"));
            PluginHost h = host;
            if (screensaverActive && h != null) {
                try {
                    readScreen(h);
                } catch (RuntimeException unavailable) {
                    // The size read at start still applies.
                }
            }
            update();
        } else if ("ks.screensaver.view".equals(event)) {
            Object shown = payload.get("view");
            view = shown instanceof String ? (String) shown : null;
            update();
        } else if ("ks.screen.state".equals(event)) {
            boolean on = !Boolean.FALSE.equals(payload.get("on"));
            if (on == screenOn) return;
            screenOn = on;
            if (!on) cancel();
            else if (active) schedule(0, true);
        }
    }

    /** Cycles only while this mode is on screen, not while another screensaver mode runs. */
    private void update() {
        String v = view;
        boolean now = screensaverActive && (UNKNOWN.equals(v) || MODE.equals(v));
        if (now == active) return;
        active = now;
        Config c = config;
        // The published photo is already one nobody has seen, so it stays up for a full interval.
        if (now) schedule(c == null || !showing ? 0 : c.intervalMs, true);
        // Prepare a fresh photo for next time while KS is not drawing the screensaver.
        else schedule(0, true);
    }

    @Override
    public void stop() throws InterruptedException {
        stopped = true;
        ScheduledExecutorService running = worker;
        cancel();
        if (running != null) {
            running.shutdownNow();
            http.abort();
            running.awaitTermination(2, TimeUnit.SECONDS);
        }
        worker = null;
        host = null;
    }

    /** Replace any pending run. Each run reschedules itself unless something scheduled over it meanwhile. */
    private synchronized void schedule(long delayMs, boolean advance) {
        if (stopped || worker == null) return;
        if (pending != null) pending.cancel(false);
        int mine = ++generation;
        pending = worker.schedule(() -> run(advance, mine), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    private synchronized void cancel() {
        generation++;
        if (pending != null) pending.cancel(false);
        pending = null;
    }

    private synchronized int currentGeneration() { return generation; }

    private synchronized boolean latest(int mine) { return mine == generation; }

    /** Worker thread. Load the albums when needed, then show the next photo or redraw the current one. */
    private void run(boolean advance, int mine) {
        Config c = config;
        if (stopped || c == null) return;
        long retry = -1;
        try {
            if (c.links.isEmpty()) {
                status("Add a Google Photos shared album link to start.", false);
                publish(Slides.message("Google Photos screensaver",
                    "Paste a shared album link in Plugin Manager > Google Photos Screensaver."));
                resetAlbum();
                return;
            }
            boolean changed = !c.links.equals(loadedLinks) || photos.isEmpty();
            if (changed) load(c);
            else if (System.currentTimeMillis() - loadedAt > c.refreshMs) refresh(c);
            // A different album, or a photo that no longer suits the screen, is replaced right away.
            if (advance || changed || shown == null || !suits(shown.photo, wanted(c))) showNext(c);
            else redraw(c);
            failures = 0;
        } catch (IOException | RuntimeException error) {
            if (stopped) return;
            failures++;
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            status("Could not update photos: " + message, true);
            if (shown == null) publish(Slides.message("Could not load Google Photos", message));
            retry = Math.min(30 * MINUTE, MINUTE << Math.min(failures - 1, 5));
        } finally {
            if (!stopped && latest(mine)) {
                if (retry >= 0) schedule(retry, true);
                else if (active && screenOn && !photos.isEmpty()) schedule(c.intervalMs, true);
            }
        }
    }

    /** Periodic reload. On failure the photos already loaded keep showing and it tries again later. */
    private void refresh(Config c) {
        try {
            load(c);
        } catch (IOException | RuntimeException error) {
            loadedAt = System.currentTimeMillis() - c.refreshMs + 15 * MINUTE;
            status("Album refresh failed, still showing " + photos.size() + " photos: " + error.getMessage(), true);
        }
    }

    private void load(Config c) throws IOException {
        if (shown == null) publish(Slides.message("Loading Google Photos...", null));
        List<Album.Photo> all = new ArrayList<>();
        List<String> links = Album.links(c.links);
        IOException firstError = null;
        int failed = 0;
        Album album = new Album(http);
        for (String link : links) {
            try {
                all.addAll(album.load(link));
            } catch (IOException | IllegalArgumentException error) {
                failed++;
                if (firstError == null) firstError = new IOException(error.getMessage(), error);
            }
        }
        if (all.isEmpty()) throw firstError != null ? firstError : new IOException("No photos found");
        photos = all;
        loadedLinks = c.links;
        loadedAt = System.currentTimeMillis();
        bag.clear();
        if (!c.shuffle) position = Math.max(-1, indexOf(lastPhotoId));
        if (prefetchedPhoto != null && indexOf(prefetchedPhoto.id) < 0) clearPrefetch();
        String albums = links.size() == 1 ? "1 album" : links.size() + " albums";
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        status(all.size() + " photos from " + albums + "." + matching(c) + " Updated " + time + "."
            + (failed > 0 ? " " + failed + " link" + (failed == 1 ? "" : "s") + " failed: " + firstError.getMessage() : ""), failed > 0);
    }

    private void showNext(Config c) throws IOException {
        Slides.Image image = null;
        Album.Photo photo = null;
        if (prefetched != null && sizing(c).equals(prefetchedSizing) && suits(prefetchedPhoto, wanted(c))) {
            image = prefetched;
            photo = prefetchedPhoto;
        }
        clearPrefetch();
        IOException error = null;
        // Skip a few photos that fail to download, such as ones removed from the album.
        for (int attempt = 0; image == null && attempt < 4; attempt++) {
            photo = pick(c);
            try {
                image = download(photo, c);
            } catch (IOException | IllegalArgumentException failed) {
                error = failed instanceof IOException ? (IOException) failed : new IOException(failed.getMessage(), failed);
            }
        }
        if (image == null) throw error != null ? error : new IOException("No photo could be downloaded");
        long now = System.currentTimeMillis();
        publish(Slides.slide(shown == null ? null : shot(shown, c), (now - shownAt) / 1000.0, shot(image, c), c.style()));
        shown = image;
        shownAt = now;
        showing = true;
        lastPhotoId = photo.id;
        prefetch(c);
    }

    /** Applies display settings to the current photo without moving on. */
    private void redraw(Config c) {
        publish(Slides.slide(null, (System.currentTimeMillis() - shownAt) / 1000.0, shot(shown, c), c.style()));
    }

    private Slides.Shot shot(Slides.Image image, Config c) {
        return new Slides.Shot(image, fitFor(image.photo, c), caption(image.photo, c));
    }

    /**
     * The fit chosen for the photo's orientation. Fill falls back to the blurred background when it would
     * crop away more than the crop limit, such as a vertical photo on a landscape screen.
     */
    Slides.Fit fitFor(Album.Photo photo, Config c) {
        boolean vertical = photo.width > 0 && photo.height > photo.width;
        Slides.Fit fit = vertical ? c.verticalFit : c.horizontalFit;
        if (fit != Slides.Fit.FILL || photo.width <= 0 || photo.height <= 0) return fit;
        int[] screen = box(c.maxSize);
        double photoAspect = (double) photo.width / photo.height, screenAspect = (double) screen[0] / screen[1];
        double cropped = 1 - Math.min(photoAspect / screenAspect, screenAspect / photoAspect);
        // A small tolerance keeps a 30% limit from rejecting a photo that crops 30.0001%.
        return cropped <= c.cropLimit + 1e-6 ? Slides.Fit.FILL : Slides.Fit.BLUR;
    }

    static String caption(Album.Photo photo, Config c) {
        if (c.date.isEmpty() || photo.taken <= 0) return "";
        Date taken = new Date(photo.taken);
        if (DATE_MONTH.equals(c.date)) return new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(taken);
        return DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(taken);
    }

    private void prefetch(Config c) {
        if (photos.size() < 2 || stopped) return;
        Album.Photo photo = pick(c);
        try {
            prefetched = download(photo, c);
            prefetchedPhoto = photo;
            prefetchedSizing = sizing(c);
        } catch (IOException | IllegalArgumentException skipped) {
            // showNext picks another photo if this one is gone.
            clearPrefetch();
        }
    }

    /**
     * Next photo in shuffle-bag or album order, among those that suit the screen. Every photo shows once before
     * any repeats.
     */
    private Album.Photo pick(Config c) {
        Boolean landscape = wanted(c);
        if (!c.shuffle) {
            for (int step = 0; step < photos.size(); step++) {
                position = (position + 1) % photos.size();
                if (suits(photos.get(position), landscape)) break;
            }
            return photos.get(position);
        }
        while (true) {
            if (bag.isEmpty()) {
                for (int i = 0; i < photos.size(); i++) if (suits(photos.get(i), landscape)) bag.add(i);
                Collections.shuffle(bag, random);
                // Avoid showing the same photo twice in a row across rounds.
                if (bag.size() > 1 && photos.get(bag.get(bag.size() - 1)).id.equals(lastPhotoId)) {
                    Collections.swap(bag, 0, bag.size() - 1);
                }
            }
            Album.Photo photo = photos.get(bag.remove(bag.size() - 1));
            // Photos left in the bag from before the screen turned are skipped. wanted() keeps the refill nonempty.
            if (suits(photo, landscape)) return photo;
        }
    }

    /**
     * The orientation photos must have: true for landscape, false for portrait, or null for any. Any applies when
     * the setting is off, the screen size is unknown or square, or no photo in the album matches.
     */
    Boolean wanted(Config c) {
        Boolean landscape = screenOrientation(c);
        if (landscape == null) return null;
        for (Album.Photo photo : photos) if (suits(photo, landscape)) return landscape;
        return null;
    }

    private Boolean screenOrientation(Config c) {
        int w = screenWidth, h = screenHeight;
        return !c.matchOrientation || w <= 0 || h <= 0 || w == h ? null : w > h;
    }

    /** Whether a photo suits the orientation. Square photos and unknown sizes suit both. */
    static boolean suits(Album.Photo photo, Boolean landscape) {
        if (landscape == null || photo.width <= 0 || photo.height <= 0) return true;
        double aspect = (double) photo.width / photo.height;
        return Math.abs(aspect - 1) < SQUARE || (aspect > 1) == landscape;
    }

    /** How many photos suit the screen, for the status line, or empty when the setting is off. */
    private String matching(Config c) {
        Boolean landscape = screenOrientation(c);
        if (landscape == null) return "";
        int n = 0;
        for (Album.Photo photo : photos) if (suits(photo, landscape)) n++;
        String screen = landscape ? "landscape" : "portrait";
        return n == 0 ? " None suit this " + screen + " screen, so all are shown." : " " + n + " suit this " + screen + " screen.";
    }

    /** Scale and quality pairs tried in turn until a photo fits the page. Quality 0 is Google's default. */
    static final double[][] KEEP_QUALITY = {{1, 0}, {0.8, 0}, {0.64, 0}, {0.51, 0}, {0.41, 0}, {0.33, 0}};
    static final double[][] KEEP_RESOLUTION = {{1, 0}, {1, 75}, {1, 65}, {1, 55}, {0.8, 55}, {0.64, 55}, {0.51, 55}, {0.41, 55}};

    /**
     * Downloads a photo sized for this screen. A photo too large for KS's page limit is requested again
     * smaller, or at lower quality when the user chose that.
     */
    private Slides.Image download(Album.Photo photo, Config c) throws IOException {
        int[] box = box(c.maxSize);
        int width = box[0], height = box[1];
        if (fitFor(photo, c) == Slides.Fit.FILL && photo.width > 0 && photo.height > 0) {
            // Cover the box: the short side fills the screen and the rest is cropped.
            double scale = Math.min(1, Math.max((double) width / photo.width, (double) height / photo.height));
            width = (int) Math.ceil(photo.width * scale);
            height = (int) Math.ceil(photo.height * scale);
        }
        int budget = Slides.imageBudget(c.crossfade);
        for (double[] attempt : c.reduceQuality ? KEEP_RESOLUTION : KEEP_QUALITY) {
            String url = Album.sized(photo, (int) (width * attempt[0]), (int) (height * attempt[0]), (int) attempt[1]);
            Http.Response response = http.request(url, null, MAX_IMAGE_BYTES);
            if (response.status != 200) throw new IOException("Photo download returned HTTP " + response.status);
            // Base64 grows the image by a third.
            if ((response.body.length + 2) / 3 * 4 + 32 > budget) continue;
            return new Slides.Image(response.body, photo, 20 + random.nextInt(61), 20 + random.nextInt(61));
        }
        throw new IOException("Photo is too large to display");
    }

    /** Download settings plus the screen size, which changes when the kiosk turns. */
    private String sizing(Config c) { return c.sizing() + "|" + screenWidth + "x" + screenHeight; }

    /** The download size: the screen, capped at maxSize on its long side. */
    private int[] box(int maxSize) {
        int w = screenWidth, h = screenHeight;
        if (w <= 0 || h <= 0) { w = 16; h = 9; }
        double scale = (double) maxSize / Math.max(w, h);
        if (screenWidth > 0 && scale > 1) scale = 1;
        return new int[] {(int) Math.round(w * scale), (int) Math.round(h * scale)};
    }

    private int indexOf(String id) {
        if (id == null) return -1;
        for (int i = 0; i < photos.size(); i++) if (photos.get(i).id.equals(id)) return i;
        return -1;
    }

    private void resetAlbum() {
        photos = Collections.emptyList();
        loadedLinks = null;
        loadedAt = 0;
        bag.clear();
        position = -1;
        shown = null;
        showing = false;
        clearPrefetch();
    }

    private void clearPrefetch() {
        prefetched = null;
        prefetchedPhoto = null;
        prefetchedSizing = null;
    }

    private void publish(String html) {
        PluginHost h = host;
        if (h == null || stopped || html.equals(lastDocument)) return;
        if (Slides.utf8Length(html) > Slides.MAX_DOCUMENT_BYTES) throw new IllegalStateException("Slide exceeds the screensaver size limit");
        h.publishScreensaver(KEY, TITLE, html);
        lastDocument = html;
    }

    private void status(String message, boolean error) {
        PluginHost h = host;
        if (h == null || stopped) return;
        try {
            h.status(message.length() > 1000 ? message.substring(0, 1000) : message, error);
        } catch (RuntimeException ignored) {
            // Status is informational. A revoked host is handled by the stopped flag.
        }
    }
}
