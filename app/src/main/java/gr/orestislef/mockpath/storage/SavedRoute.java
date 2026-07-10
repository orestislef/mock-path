package gr.orestislef.mockpath.storage;

import androidx.annotation.NonNull;

import java.util.List;

/** A named, persisted route: an ordered list of {lat, lon} points plus metadata. */
public final class SavedRoute {

    public final String id;
    public final String name;
    public final long createdAt;
    public final List<double[]> points;

    public SavedRoute(@NonNull String id, @NonNull String name, long createdAt,
                      @NonNull List<double[]> points) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.points = points;
    }

    public int pointCount() {
        return points.size();
    }
}
