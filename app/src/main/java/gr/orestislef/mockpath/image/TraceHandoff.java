package gr.orestislef.mockpath.image;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * In-process handoff of the edited trace route from the editor screen back to the map
 * screen. Points are normalised ({@code px / max(width, height)}) so aspect ratio is
 * preserved and the map screen can fit them into the current viewport.
 */
public final class TraceHandoff {

    @Nullable
    private static List<double[]> route;

    private TraceHandoff() {
    }

    public static void set(@NonNull List<double[]> normalizedRoute) {
        List<double[]> copy = new ArrayList<>(normalizedRoute.size());
        for (double[] p : normalizedRoute) {
            copy.add(new double[]{p[0], p[1]});
        }
        route = copy;
    }

    /** Returns and clears the staged route, or null if none. */
    @Nullable
    public static List<double[]> consume() {
        List<double[]> r = route;
        route = null;
        return r;
    }
}
