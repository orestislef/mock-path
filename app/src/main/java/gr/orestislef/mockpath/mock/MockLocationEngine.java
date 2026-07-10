package gr.orestislef.mockpath.mock;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.util.List;

/**
 * Drives mock-location injection on its own worker thread. Advances a
 * {@link PathInterpolator} at a configured speed and pushes each computed fix to
 * both the platform {@link LocationManager} test providers and the fused provider
 * (so Google Maps / Timeline pick it up).
 *
 * <p>Threading model: all injection runs on the caller-supplied worker thread via
 * {@link #tick()}; lifecycle methods ({@link #start}, {@link #stop}) are safe to call
 * from any thread. State shared with the UI is published through {@code volatile} fields.
 */
public final class MockLocationEngine {

    public interface Listener {
        /** Called on the worker thread for each injected fix. */
        void onFix(double latitude, double longitude, float bearing,
                   float speedMps, double progressFraction);

        /** Called on the worker thread once the path end is reached (non-looping). */
        void onCompleted();

        /** Called on the worker thread when injection cannot continue. */
        void onError(@NonNull String message);
    }

    private static final String TAG = "MockLocationEngine";

    /** Providers we publish to via LocationManager. */
    private static final String[] TEST_PROVIDERS = {
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
    };

    private final Context appContext;
    private final LocationManager locationManager;
    private final FusedLocationProviderClient fusedClient;

    private final PathInterpolator path;
    private final float speedMps;
    private final boolean loop;
    @Nullable
    private final Listener listener;

    // Injection progress, in meters travelled from path start. Worker-thread only.
    private double travelledMeters = 0d;
    private long lastTickUptimeMs = 0L;

    private volatile boolean running = false;
    private final boolean[] addedProviders = new boolean[TEST_PROVIDERS.length];

    public MockLocationEngine(@NonNull Context context,
                              @NonNull List<double[]> latLngPoints,
                              float speedMps,
                              boolean loop,
                              @Nullable Listener listener) {
        this.appContext = context.getApplicationContext();
        this.locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        this.fusedClient = LocationServices.getFusedLocationProviderClient(appContext);
        this.speedMps = Math.max(0.1f, speedMps);
        this.loop = loop;
        this.listener = listener;

        double[] lats = new double[latLngPoints.size()];
        double[] lons = new double[latLngPoints.size()];
        for (int i = 0; i < latLngPoints.size(); i++) {
            lats[i] = latLngPoints.get(i)[0];
            lons[i] = latLngPoints.get(i)[1];
        }
        this.path = new PathInterpolator(lats, lons);
    }

    public boolean isRunning() {
        return running;
    }

    public double totalDistanceMeters() {
        return path.totalDistanceMeters();
    }

    /**
     * Registers the test providers. Must be called before {@link #tick()}.
     *
     * @return true on success; false if the app is not selected as mock-location app.
     */
    public boolean start() {
        if (running) {
            return true;
        }
        try {
            for (int i = 0; i < TEST_PROVIDERS.length; i++) {
                addTestProvider(TEST_PROVIDERS[i]);
                locationManager.setTestProviderEnabled(TEST_PROVIDERS[i], true);
                addedProviders[i] = true;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Not selected as mock location app", e);
            cleanupProviders();
            notifyError("This app is not selected as the mock location app. "
                    + "Enable it in Developer options.");
            return false;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Test provider registration failed", e);
            cleanupProviders();
            notifyError("Could not register test providers: " + e.getMessage());
            return false;
        }
        // Enable fused mock mode once; per-fix calls only push the location.
        try {
            fusedClient.setMockMode(true);
        } catch (SecurityException e) {
            Log.d(TAG, "Fused mock mode unavailable", e);
        }
        travelledMeters = 0d;
        lastTickUptimeMs = SystemClock.uptimeMillis();
        running = true;
        return true;
    }

    /**
     * Advances the simulation by the real time elapsed since the previous tick and
     * injects one fix. Call at a fixed cadence from the worker thread.
     */
    public void tick() {
        if (!running) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        double elapsedSeconds = (now - lastTickUptimeMs) / 1000d;
        lastTickUptimeMs = now;
        // Guard against clock jumps / first tick.
        if (elapsedSeconds < 0d || elapsedSeconds > 5d) {
            elapsedSeconds = 0d;
        }

        travelledMeters += speedMps * elapsedSeconds;
        double total = path.totalDistanceMeters();

        boolean finished = false;
        if (travelledMeters >= total) {
            if (loop && total > 0d) {
                travelledMeters = travelledMeters % total;
            } else {
                travelledMeters = total;
                finished = true;
            }
        }

        PathInterpolator.Position pos = path.positionAt(travelledMeters);
        boolean injected = injectLocation(pos, finished ? 0f : speedMps);
        if (injected && listener != null) {
            double fraction = total > 0d ? Math.min(1d, travelledMeters / total) : 1d;
            listener.onFix(pos.latitude, pos.longitude, pos.bearing,
                    finished ? 0f : speedMps, fraction);
        }

        if (finished) {
            running = false;
            if (listener != null) {
                listener.onCompleted();
            }
        }
    }

    /** Unregisters providers. Safe to call multiple times / from any thread. */
    public void stop() {
        running = false;
        cleanupProviders();
    }

    // --- internals ---

    @SuppressLint("MissingPermission")
    private boolean injectLocation(@NonNull PathInterpolator.Position pos, float speed) {
        Location base = buildLocation(pos, speed);
        boolean any = false;
        for (int i = 0; i < TEST_PROVIDERS.length; i++) {
            if (!addedProviders[i]) {
                continue;
            }
            Location loc = new Location(base);
            loc.setProvider(TEST_PROVIDERS[i]);
            try {
                locationManager.setTestProviderLocation(TEST_PROVIDERS[i], loc);
                any = true;
            } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
                Log.w(TAG, "setTestProviderLocation failed for " + TEST_PROVIDERS[i], e);
            }
        }
        // Push to fused provider too; Maps Timeline reads this. Failure is non-fatal.
        try {
            Location fused = new Location(base);
            fused.setProvider(LocationManager.GPS_PROVIDER);
            fusedClient.setMockLocation(fused);
        } catch (SecurityException e) {
            Log.d(TAG, "Fused mock injection unavailable", e);
        }
        return any;
    }

    private Location buildLocation(@NonNull PathInterpolator.Position pos, float speed) {
        Location loc = new Location(LocationManager.GPS_PROVIDER);
        loc.setLatitude(pos.latitude);
        loc.setLongitude(pos.longitude);
        loc.setAltitude(0d);
        loc.setAccuracy(3.0f);
        loc.setBearing(pos.bearing);
        loc.setSpeed(speed);
        loc.setBearingAccuracyDegrees(1.0f);
        loc.setSpeedAccuracyMetersPerSecond(0.5f);
        loc.setVerticalAccuracyMeters(3.0f);
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return loc;
    }

    // Pre-31 uses the legacy overload; its power/accuracy ints match ProviderProperties
    // values numerically but Criteria constants are used to stay minSdk-safe.
    @SuppressLint("WrongConstant")
    @SuppressWarnings("deprecation")
    private void addTestProvider(@NonNull String provider) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ProviderProperties props = new ProviderProperties.Builder()
                    .setHasNetworkRequirement(false)
                    .setHasSatelliteRequirement(false)
                    .setHasCellRequirement(false)
                    .setHasMonetaryCost(false)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build();
            locationManager.addTestProvider(provider, props);
        } else {
            locationManager.addTestProvider(provider,
                    false, false, false, false,
                    true, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE);
        }
    }

    @SuppressLint("MissingPermission")
    private void cleanupProviders() {
        for (int i = 0; i < TEST_PROVIDERS.length; i++) {
            if (!addedProviders[i]) {
                continue;
            }
            try {
                locationManager.setTestProviderEnabled(TEST_PROVIDERS[i], false);
            } catch (Exception ignored) {
            }
            try {
                locationManager.removeTestProvider(TEST_PROVIDERS[i]);
            } catch (Exception ignored) {
            }
            addedProviders[i] = false;
        }
        try {
            fusedClient.setMockMode(false);
        } catch (Exception ignored) {
        }
    }

    private void notifyError(@NonNull String message) {
        if (listener != null) {
            listener.onError(message);
        }
    }
}
