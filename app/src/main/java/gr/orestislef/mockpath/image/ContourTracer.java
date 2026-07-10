package gr.orestislef.mockpath.image;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Converts a binary {@link EdgeDetector.EdgeMap} into a set of ordered polylines by
 * following connected runs of edge pixels, then simplifies each with the
 * Ramer–Douglas–Peucker algorithm. Output coordinates are in image pixel space.
 */
public final class ContourTracer {

    // 8-neighbour offsets, ordered so straight continuations are preferred first.
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DY = {0, 0, 1, -1, 1, -1, 1, -1};

    private ContourTracer() {
    }

    /**
     * @param map        binary edge map
     * @param minPoints  discard polylines with fewer raw points than this
     * @param epsilonPx  RDP tolerance in pixels (larger = fewer vertices)
     * @return list of polylines; each polyline is a list of {x, y} float pairs
     */
    @NonNull
    public static List<List<float[]>> trace(@NonNull EdgeDetector.EdgeMap map,
                                            int minPoints, float epsilonPx) {
        final int w = map.width;
        final int h = map.height;
        final boolean[] edges = map.edges;
        final boolean[] visited = new boolean[edges.length];

        List<List<float[]>> polylines = new ArrayList<>();

        // Pass 1: start from endpoints (pixels with a single edge neighbour) so open
        // strokes are traced as one continuous run. Pass 2 handles closed loops.
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    if (!edges[idx] || visited[idx]) {
                        continue;
                    }
                    if (pass == 0 && countNeighbours(edges, x, y, w, h) != 1) {
                        continue;
                    }
                    List<float[]> line = follow(edges, visited, x, y, w, h);
                    if (line.size() >= minPoints) {
                        List<float[]> simplified = simplify(line, epsilonPx);
                        if (simplified.size() >= 2) {
                            polylines.add(simplified);
                        }
                    }
                }
            }
        }
        return polylines;
    }

    private static int countNeighbours(boolean[] edges, int x, int y, int w, int h) {
        int count = 0;
        for (int k = 0; k < 8; k++) {
            int nx = x + DX[k];
            int ny = y + DY[k];
            if (nx >= 0 && ny >= 0 && nx < w && ny < h && edges[ny * w + nx]) {
                count++;
            }
        }
        return count;
    }

    /** Follows an edge run from (x, y), marking pixels visited as it goes. */
    private static List<float[]> follow(boolean[] edges, boolean[] visited,
                                        int x, int y, int w, int h) {
        List<float[]> line = new ArrayList<>();
        int cx = x;
        int cy = y;
        while (true) {
            int idx = cy * w + cx;
            visited[idx] = true;
            line.add(new float[]{cx, cy});

            int nextX = -1;
            int nextY = -1;
            for (int k = 0; k < 8; k++) {
                int nx = cx + DX[k];
                int ny = cy + DY[k];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                    continue;
                }
                int nIdx = ny * w + nx;
                if (edges[nIdx] && !visited[nIdx]) {
                    nextX = nx;
                    nextY = ny;
                    break;
                }
            }
            if (nextX < 0) {
                break;
            }
            cx = nextX;
            cy = nextY;
        }
        return line;
    }

    // --- Ramer–Douglas–Peucker simplification (iterative) ---

    @NonNull
    static List<float[]> simplify(@NonNull List<float[]> points, float epsilon) {
        int n = points.size();
        if (n < 3) {
            return new ArrayList<>(points);
        }
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;

        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] seg = stack.pop();
            int first = seg[0];
            int last = seg[1];
            float maxDist = 0f;
            int index = -1;
            float[] a = points.get(first);
            float[] b = points.get(last);
            for (int i = first + 1; i < last; i++) {
                float d = perpendicularDistance(points.get(i), a, b);
                if (d > maxDist) {
                    maxDist = d;
                    index = i;
                }
            }
            if (index != -1 && maxDist > epsilon) {
                keep[index] = true;
                stack.push(new int[]{first, index});
                stack.push(new int[]{index, last});
            }
        }

        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                out.add(points.get(i));
            }
        }
        return out;
    }

    private static float perpendicularDistance(@NonNull float[] p,
                                               @NonNull float[] a, @NonNull float[] b) {
        float dx = b[0] - a[0];
        float dy = b[1] - a[1];
        float lenSq = dx * dx + dy * dy;
        if (lenSq == 0f) {
            float ex = p[0] - a[0];
            float ey = p[1] - a[1];
            return (float) Math.sqrt(ex * ex + ey * ey);
        }
        float t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / lenSq;
        t = Math.max(0f, Math.min(1f, t));
        float projX = a[0] + t * dx;
        float projY = a[1] + t * dy;
        float ex = p[0] - projX;
        float ey = p[1] - projY;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }
}
