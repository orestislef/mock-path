package gr.orestislef.mockpath.mock;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-process handoff of the drawn route from the UI to the service. A large route
 * can exceed the Binder transaction limit if passed through an Intent, so the point
 * list is staged here and the Intent carries only scalar settings.
 */
public final class RoutePayload {

    private static final Object LOCK = new Object();
    private static List<double[]> pendingPoints = Collections.emptyList();

    private RoutePayload() {
    }

    /** Stores a defensive copy of the route points ({lat, lon} pairs). */
    public static void set(@NonNull List<double[]> points) {
        List<double[]> copy = new ArrayList<>(points.size());
        for (double[] p : points) {
            copy.add(new double[]{p[0], p[1]});
        }
        synchronized (LOCK) {
            pendingPoints = copy;
        }
    }

    /** Returns the staged route (never null; empty if nothing staged). */
    @NonNull
    public static List<double[]> get() {
        synchronized (LOCK) {
            return pendingPoints;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            pendingPoints = Collections.emptyList();
        }
    }
}
