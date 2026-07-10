package gr.orestislef.mockpath.image;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Multithreaded Sobel edge detector. Converts an ARGB bitmap to a binary edge map
 * through grayscale conversion, a separable box blur (noise suppression) and a Sobel
 * gradient-magnitude threshold. All per-pixel passes are split into row bands and run
 * on a fixed thread pool sized to the device's CPU count.
 */
public final class EdgeDetector {

    /** Binary edge map plus its dimensions. {@code edges[y * width + x]}. */
    public static final class EdgeMap {
        public final int width;
        public final int height;
        public final boolean[] edges;

        EdgeMap(int width, int height, boolean[] edges) {
            this.width = width;
            this.height = height;
            this.edges = edges;
        }
    }

    private final ExecutorService pool;
    private final int threads;

    public EdgeDetector() {
        this.threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.pool = Executors.newFixedThreadPool(threads);
    }

    /** Releases the worker pool. Call once when finished. */
    public void shutdown() {
        pool.shutdownNow();
    }

    /**
     * @param bitmap    source image (any config; read-only)
     * @param threshold gradient magnitude cut-off, 0..255 (higher = fewer edges)
     * @param blurRadius box-blur radius in pixels (0 disables blur)
     * @return binary edge map
     */
    @NonNull
    public EdgeMap detect(@NonNull Bitmap bitmap, int threshold, int blurRadius) {
        return detect(bitmap, threshold, blurRadius, true);
    }

    /**
     * @param thin if true, apply Zhang–Suen skeletonisation so thick edges collapse to
     *             single-pixel-wide lines (cleaner tracing, fewer duplicate strokes)
     */
    @NonNull
    public EdgeMap detect(@NonNull Bitmap bitmap, int threshold, int blurRadius, boolean thin) {
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final int n = w * h;

        int[] pixels = new int[n];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        float[] gray = new float[n];
        runBands(h, (y0, y1) -> {
            for (int y = y0; y < y1; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int c = pixels[row + x];
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;
                    // Rec. 601 luma.
                    gray[row + x] = 0.299f * r + 0.587f * g + 0.114f * b;
                }
            }
        });

        float[] blurred = blurRadius > 0 ? boxBlur(gray, w, h, blurRadius) : gray;

        final float[] magnitude = new float[n];
        final float[] bandMax = new float[threads];
        runBandsIndexed(h, (band, y0, y1) -> {
            float localMax = 0f;
            for (int y = y0; y < y1; y++) {
                for (int x = 0; x < w; x++) {
                    // Zero-gradient on the border to avoid frame artifacts.
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                        continue;
                    }
                    int i = y * w + x;
                    float tl = blurred[i - w - 1], tc = blurred[i - w], tr = blurred[i - w + 1];
                    float ml = blurred[i - 1], mr = blurred[i + 1];
                    float bl = blurred[i + w - 1], bc = blurred[i + w], br = blurred[i + w + 1];
                    float gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl);
                    float gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr);
                    float mag = (float) Math.sqrt(gx * gx + gy * gy);
                    magnitude[i] = mag;
                    if (mag > localMax) {
                        localMax = mag;
                    }
                }
            }
            bandMax[band] = localMax;
        });

        float max = 0f;
        for (float m : bandMax) {
            if (m > max) {
                max = m;
            }
        }
        final float norm = max > 0f ? 255f / max : 0f;
        final float cut = threshold;

        final boolean[] edges = new boolean[n];
        runBands(h, (y0, y1) -> {
            for (int y = y0; y < y1; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    edges[row + x] = magnitude[row + x] * norm >= cut;
                }
            }
        });

        if (thin) {
            thin(edges, w, h);
        }
        return new EdgeMap(w, h, edges);
    }

    // --- Zhang–Suen thinning ---

    private void thin(@NonNull boolean[] img, int w, int h) {
        if (w < 3 || h < 3) {
            return;
        }
        final boolean[] remove = new boolean[img.length];
        final boolean[] bandChanged = new boolean[threads];
        boolean changed = true;
        int guard = 0;
        // Guard bounds the worst case; skeletons converge well before this.
        final int maxIterations = (w + h) * 2 + 16;
        while (changed && guard++ < maxIterations) {
            changed = false;
            for (int step = 0; step < 2; step++) {
                java.util.Arrays.fill(remove, false);
                java.util.Arrays.fill(bandChanged, false);
                final int s = step;
                runBandsIndexed(h, (band, y0, y1) -> {
                    boolean local = false;
                    int from = Math.max(1, y0);
                    int to = Math.min(h - 1, y1);
                    for (int y = from; y < to; y++) {
                        for (int x = 1; x < w - 1; x++) {
                            int i = y * w + x;
                            if (!img[i]) {
                                continue;
                            }
                            boolean p2 = img[i - w], p3 = img[i - w + 1], p4 = img[i + 1],
                                    p5 = img[i + w + 1], p6 = img[i + w], p7 = img[i + w - 1],
                                    p8 = img[i - 1], p9 = img[i - w - 1];
                            int b = (p2 ? 1 : 0) + (p3 ? 1 : 0) + (p4 ? 1 : 0) + (p5 ? 1 : 0)
                                    + (p6 ? 1 : 0) + (p7 ? 1 : 0) + (p8 ? 1 : 0) + (p9 ? 1 : 0);
                            if (b < 2 || b > 6) {
                                continue;
                            }
                            if (transitions(p2, p3, p4, p5, p6, p7, p8, p9) != 1) {
                                continue;
                            }
                            if (s == 0) {
                                if (p2 && p4 && p6) continue;
                                if (p4 && p6 && p8) continue;
                            } else {
                                if (p2 && p4 && p8) continue;
                                if (p2 && p6 && p8) continue;
                            }
                            remove[i] = true;
                            local = true;
                        }
                    }
                    bandChanged[band] = local;
                });
                for (int i = 0; i < img.length; i++) {
                    if (remove[i]) {
                        img[i] = false;
                    }
                }
                for (boolean bc : bandChanged) {
                    if (bc) {
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    /** Count of 0->1 transitions around the ordered 8-neighbourhood. */
    private static int transitions(boolean p2, boolean p3, boolean p4, boolean p5,
                                   boolean p6, boolean p7, boolean p8, boolean p9) {
        int a = 0;
        if (!p2 && p3) a++;
        if (!p3 && p4) a++;
        if (!p4 && p5) a++;
        if (!p5 && p6) a++;
        if (!p6 && p7) a++;
        if (!p7 && p8) a++;
        if (!p8 && p9) a++;
        if (!p9 && p2) a++;
        return a;
    }

    // --- separable box blur ---

    private float[] boxBlur(@NonNull float[] src, int w, int h, int radius) {
        float[] tmp = new float[src.length];
        float[] out = new float[src.length];
        final int r = radius;
        // Horizontal pass.
        runBands(h, (y0, y1) -> {
            for (int y = y0; y < y1; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    float sum = 0f;
                    int count = 0;
                    int from = Math.max(0, x - r);
                    int to = Math.min(w - 1, x + r);
                    for (int k = from; k <= to; k++) {
                        sum += src[row + k];
                        count++;
                    }
                    tmp[row + x] = sum / count;
                }
            }
        });
        // Vertical pass.
        runBands(h, (y0, y1) -> {
            for (int y = y0; y < y1; y++) {
                for (int x = 0; x < w; x++) {
                    float sum = 0f;
                    int count = 0;
                    int from = Math.max(0, y - r);
                    int to = Math.min(h - 1, y + r);
                    for (int k = from; k <= to; k++) {
                        sum += tmp[k * w + x];
                        count++;
                    }
                    out[y * w + x] = sum / count;
                }
            }
        });
        return out;
    }

    // --- band scheduling ---

    private interface BandTask {
        void run(int y0, int y1);
    }

    private interface IndexedBandTask {
        void run(int band, int y0, int y1);
    }

    private void runBands(int height, @NonNull BandTask task) {
        runBandsIndexed(height, (band, y0, y1) -> task.run(y0, y1));
    }

    private void runBandsIndexed(int height, @NonNull IndexedBandTask task) {
        int bands = Math.min(threads, Math.max(1, height));
        int rowsPerBand = (height + bands - 1) / bands;
        List<Callable<Void>> jobs = new ArrayList<>(bands);
        for (int b = 0; b < bands; b++) {
            final int band = b;
            final int y0 = b * rowsPerBand;
            final int y1 = Math.min(height, y0 + rowsPerBand);
            if (y0 >= y1) {
                continue;
            }
            jobs.add(() -> {
                task.run(band, y0, y1);
                return null;
            });
        }
        try {
            List<Future<Void>> futures = pool.invokeAll(jobs);
            for (Future<Void> f : futures) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Edge detection interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Edge detection failed", e.getCause());
        }
    }
}
