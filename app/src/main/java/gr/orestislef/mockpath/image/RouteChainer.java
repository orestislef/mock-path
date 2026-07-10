package gr.orestislef.mockpath.image;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Flattens a set of disconnected polylines into a single continuous route suitable
 * for location playback. Uses a greedy nearest-endpoint heuristic: repeatedly appends
 * the remaining polyline whose nearest end is closest to the current route tail,
 * reversing it if its far end is nearer.
 */
public final class RouteChainer {

    private RouteChainer() {
    }

    @NonNull
    public static List<float[]> chain(@NonNull List<List<float[]>> polylines) {
        List<float[]> route = new ArrayList<>();
        if (polylines.isEmpty()) {
            return route;
        }
        List<List<float[]>> remaining = new ArrayList<>();
        for (List<float[]> p : polylines) {
            if (p.size() >= 2) {
                remaining.add(new ArrayList<>(p));
            }
        }
        if (remaining.isEmpty()) {
            return route;
        }

        // Start with the longest polyline for a stable backbone.
        int startIdx = 0;
        for (int i = 1; i < remaining.size(); i++) {
            if (remaining.get(i).size() > remaining.get(startIdx).size()) {
                startIdx = i;
            }
        }
        route.addAll(remaining.remove(startIdx));

        while (!remaining.isEmpty()) {
            float[] tail = route.get(route.size() - 1);
            int bestIdx = -1;
            boolean bestReverse = false;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                List<float[]> poly = remaining.get(i);
                double dHead = distSq(tail, poly.get(0));
                double dTail = distSq(tail, poly.get(poly.size() - 1));
                if (dHead < bestDist) {
                    bestDist = dHead;
                    bestIdx = i;
                    bestReverse = false;
                }
                if (dTail < bestDist) {
                    bestDist = dTail;
                    bestIdx = i;
                    bestReverse = true;
                }
            }
            List<float[]> next = remaining.remove(bestIdx);
            if (bestReverse) {
                java.util.Collections.reverse(next);
            }
            route.addAll(next);
        }
        return route;
    }

    private static double distSq(@NonNull float[] a, @NonNull float[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        return dx * dx + dy * dy;
    }
}
