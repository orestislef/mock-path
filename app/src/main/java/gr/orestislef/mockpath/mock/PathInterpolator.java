package gr.orestislef.mockpath.mock;

import androidx.annotation.NonNull;

/**
 * Immutable, thread-safe interpolator over a polyline of WGS84 coordinates.
 * Pre-computes cumulative segment distances so {@link #positionAt(double)}
 * resolves any travelled distance to a lat/lon/bearing in O(log n).
 */
public final class PathInterpolator {

    /** Interpolated point on the path. */
    public static final class Position {
        public final double latitude;
        public final double longitude;
        public final float bearing;

        Position(double latitude, double longitude, float bearing) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.bearing = bearing;
        }
    }

    private static final double EARTH_RADIUS_M = 6_371_000d;

    private final double[] latitudes;
    private final double[] longitudes;
    private final double[] cumulativeMeters;
    private final double totalMeters;

    /**
     * @param latitudes  path latitudes, degrees; length must equal {@code longitudes} and be >= 2
     * @param longitudes path longitudes, degrees
     */
    public PathInterpolator(@NonNull double[] latitudes, @NonNull double[] longitudes) {
        if (latitudes.length != longitudes.length) {
            throw new IllegalArgumentException("latitude/longitude arrays differ in length");
        }
        if (latitudes.length < 2) {
            throw new IllegalArgumentException("path needs at least 2 points");
        }
        this.latitudes = latitudes.clone();
        this.longitudes = longitudes.clone();
        this.cumulativeMeters = new double[latitudes.length];
        double sum = 0d;
        for (int i = 1; i < latitudes.length; i++) {
            sum += haversineMeters(latitudes[i - 1], longitudes[i - 1], latitudes[i], longitudes[i]);
            cumulativeMeters[i] = sum;
        }
        this.totalMeters = sum;
    }

    public double totalDistanceMeters() {
        return totalMeters;
    }

    /**
     * Returns the position after travelling {@code distanceMeters} from the path start.
     * Values are clamped to the path bounds.
     */
    @NonNull
    public Position positionAt(double distanceMeters) {
        if (distanceMeters <= 0d) {
            return new Position(latitudes[0], longitudes[0],
                    bearingDegrees(latitudes[0], longitudes[0], latitudes[1], longitudes[1]));
        }
        int last = latitudes.length - 1;
        if (distanceMeters >= totalMeters) {
            return new Position(latitudes[last], longitudes[last],
                    bearingDegrees(latitudes[last - 1], longitudes[last - 1], latitudes[last], longitudes[last]));
        }

        int segmentEnd = binarySearchSegment(distanceMeters);
        int segmentStart = segmentEnd - 1;
        double segmentLength = cumulativeMeters[segmentEnd] - cumulativeMeters[segmentStart];
        double fraction = segmentLength <= 0d
                ? 0d
                : (distanceMeters - cumulativeMeters[segmentStart]) / segmentLength;

        double lat = latitudes[segmentStart] + (latitudes[segmentEnd] - latitudes[segmentStart]) * fraction;
        double lon = longitudes[segmentStart] + (longitudes[segmentEnd] - longitudes[segmentStart]) * fraction;
        float bearing = bearingDegrees(latitudes[segmentStart], longitudes[segmentStart],
                latitudes[segmentEnd], longitudes[segmentEnd]);
        return new Position(lat, lon, bearing);
    }

    /** Smallest index i (>= 1) with cumulativeMeters[i] >= distance. */
    private int binarySearchSegment(double distance) {
        int lo = 1;
        int hi = cumulativeMeters.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulativeMeters[mid] < distance) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static float bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2)
                - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLon);
        double degrees = Math.toDegrees(Math.atan2(y, x));
        return (float) ((degrees + 360d) % 360d);
    }
}
