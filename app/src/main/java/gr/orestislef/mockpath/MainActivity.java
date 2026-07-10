package gr.orestislef.mockpath;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.provider.ProviderProperties;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import gr.orestislef.mockpath.draw.PositionArrowOverlay;
import gr.orestislef.mockpath.draw.RouteDrawOverlay;
import gr.orestislef.mockpath.image.TraceHandoff;
import gr.orestislef.mockpath.mock.LocalBroadcastRelay;
import gr.orestislef.mockpath.mock.MockLocationService;
import gr.orestislef.mockpath.mock.RoutePayload;
import gr.orestislef.mockpath.storage.RouteStore;
import gr.orestislef.mockpath.storage.SavedRoute;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity implements LocalBroadcastRelay.Observer {

    private static final double DEFAULT_LAT = 40.6401;   // Thessaloniki fallback
    private static final double DEFAULT_LON = 22.9444;
    private static final double DEFAULT_ZOOM = 16.0;

    private MapView mapView;
    private RouteDrawOverlay drawOverlay;
    private PositionArrowOverlay arrowOverlay;

    private MaterialButton drawButton;
    private MaterialButton clearButton;
    private MaterialButton playButton;
    private MaterialButton importButton;
    private MaterialButton saveButton;
    private MaterialButton savedButton;
    private Slider speedSlider;
    private SwitchMaterial loopSwitch;
    private android.widget.TextView statusText;
    private android.widget.TextView speedLabel;
    private View rootView;
    private View panelView;

    private final ExecutorService storageExecutor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> traceLauncher;
    private ActivityResultLauncher<Intent> savedRoutesLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // osmdroid must be configured before the MapView is inflated.
        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx,
                ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.map);
        drawButton = findViewById(R.id.btn_draw);
        clearButton = findViewById(R.id.btn_clear);
        playButton = findViewById(R.id.btn_play);
        importButton = findViewById(R.id.btn_import);
        saveButton = findViewById(R.id.btn_save);
        savedButton = findViewById(R.id.btn_saved);
        speedSlider = findViewById(R.id.slider_speed);
        loopSwitch = findViewById(R.id.switch_loop);
        statusText = findViewById(R.id.text_status);
        speedLabel = findViewById(R.id.label_speed);
        rootView = findViewById(R.id.root);
        panelView = findViewById(R.id.panel_content);

        applyWindowInsets();
        setupMap();
        setupControls();
        registerPermissionLauncher();
        registerTraceLauncher();
        registerSavedRoutesLauncher();
        requestRuntimePermissions();
        updateControls(MockLocationService.isRunning());
    }

    private void applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        // Dark icons over the light map tiles and light control panel.
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);

        final int statusBaseTop = ((ViewGroup.MarginLayoutParams) statusText.getLayoutParams()).topMargin;
        final int panelBaseBottom = panelView.getPaddingBottom();
        final int panelBaseLeft = panelView.getPaddingLeft();
        final int panelBaseRight = panelView.getPaddingRight();
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) statusText.getLayoutParams();
            lp.topMargin = statusBaseTop + bars.top;
            statusText.setLayoutParams(lp);
            panelView.setPadding(panelBaseLeft + bars.left, panelView.getPaddingTop(),
                    panelBaseRight + bars.right, panelBaseBottom + bars.bottom);
            return insets;
        });
    }

    private void setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapView.setTilesScaledToDpi(true);
        mapView.getController().setZoom(DEFAULT_ZOOM);
        mapView.getController().setCenter(new GeoPoint(DEFAULT_LAT, DEFAULT_LON));

        drawOverlay = new RouteDrawOverlay();
        drawOverlay.setOnRouteChangedListener(count -> runOnUiThread(() -> {
            if (!MockLocationService.isRunning() && !drawOverlay.isDrawModeEnabled()) {
                statusText.setText(count >= 2
                        ? getString(R.string.status_points, count)
                        : getString(R.string.status_draw_hint));
            }
            updateControls();
        }));
        mapView.getOverlays().add(drawOverlay);

        arrowOverlay = new PositionArrowOverlay(getResources().getDisplayMetrics().density);
        mapView.getOverlays().add(arrowOverlay);
    }

    private void setupControls() {
        drawButton.setOnClickListener(v -> toggleDrawMode());
        clearButton.setOnClickListener(v -> {
            drawOverlay.clear();
            arrowOverlay.hide();
            mapView.invalidate();
        });
        playButton.setOnClickListener(v -> {
            if (MockLocationService.isRunning()) {
                MockLocationService.stop(this);
            } else {
                startPlayback();
            }
        });
        importButton.setOnClickListener(v -> {
            if (MockLocationService.isRunning()) {
                return;
            }
            traceLauncher.launch(new Intent(this, ImageTraceActivity.class));
        });
        saveButton.setOnClickListener(v -> showSaveDialog());
        savedButton.setOnClickListener(v -> {
            if (!MockLocationService.isRunning()) {
                savedRoutesLauncher.launch(new Intent(this, SavedRoutesActivity.class));
            }
        });

        speedSlider.addOnChangeListener((slider, value, fromUser) -> updateSpeedLabel(value));
        updateSpeedLabel(speedSlider.getValue());
    }

    private void toggleDrawMode() {
        boolean enabled = !drawOverlay.isDrawModeEnabled();
        drawOverlay.setDrawModeEnabled(enabled);
        // Freeze map gestures while drawing so the stroke doesn't pan the map.
        mapView.setMultiTouchControls(!enabled);
        if (enabled) {
            statusText.setText(R.string.status_draw_active);
        } else {
            statusText.setText(drawOverlay.pointCount() >= 2
                    ? getString(R.string.status_points, drawOverlay.pointCount())
                    : getString(R.string.status_draw_hint));
        }
        updateControls();
    }

    private void updateSpeedLabel(float kmh) {
        speedLabel.setText(getString(R.string.label_speed_value, Math.round(kmh)));
    }

    private void startPlayback() {
        List<double[]> route = drawOverlay.exportLatLng();
        if (route.size() < 2) {
            Toast.makeText(this, R.string.toast_need_route, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isMockLocationAppSelected()) {
            promptEnableMockApp();
            return;
        }
        if (drawOverlay.isDrawModeEnabled()) {
            toggleDrawMode();
        }
        RoutePayload.set(route);
        float speedMps = speedSlider.getValue() / 3.6f;
        MockLocationService.start(this, speedMps, loopSwitch.isChecked());
        updateControls(true);
        statusText.setText(R.string.status_playing);
    }

    // --- lifecycle ---

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        LocalBroadcastRelay.register(this);
        updateControls(MockLocationService.isRunning());
    }

    @Override
    protected void onPause() {
        LocalBroadcastRelay.unregister(this);
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        storageExecutor.shutdownNow();
        if (mapView != null) {
            mapView.onDetach();
        }
        super.onDestroy();
    }

    // --- broadcast handling ---

    @Override
    public void onEvent(@NonNull Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        switch (action) {
            case MockLocationService.BROADCAST_FIX:
                double lat = intent.getDoubleExtra(MockLocationService.KEY_LAT, 0);
                double lon = intent.getDoubleExtra(MockLocationService.KEY_LON, 0);
                float bearing = intent.getFloatExtra(MockLocationService.KEY_BEARING, 0);
                double fraction = intent.getDoubleExtra(MockLocationService.KEY_FRACTION, 0);
                updatePositionMarker(lat, lon, bearing);
                statusText.setText(getString(R.string.status_progress, (int) Math.round(fraction * 100)));
                break;
            case MockLocationService.BROADCAST_COMPLETED:
                Toast.makeText(this, R.string.toast_completed, Toast.LENGTH_SHORT).show();
                updateControls(false);
                statusText.setText(R.string.status_completed);
                break;
            case MockLocationService.BROADCAST_ERROR:
                String msg = intent.getStringExtra(MockLocationService.KEY_MESSAGE);
                updateControls(false);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.dialog_error_title)
                        .setMessage(msg != null ? msg : getString(R.string.error_generic))
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.action_open_dev, (d, w) -> openDeveloperSettings())
                        .show();
                break;
            case MockLocationService.BROADCAST_STOPPED:
                updateControls(false);
                statusText.setText(R.string.status_stopped);
                break;
            default:
                break;
        }
    }

    private void updatePositionMarker(double lat, double lon, float bearing) {
        GeoPoint p = new GeoPoint(lat, lon);
        arrowOverlay.setPosition(p, bearing);
        mapView.getController().animateTo(p);
        mapView.invalidate();
    }

    private void updateControls() {
        updateControls(MockLocationService.isRunning());
    }

    /**
     * Enables/labels controls from the current state. {@code running} is passed
     * explicitly because {@link MockLocationService#isRunning()} flips asynchronously
     * right after start/stop.
     */
    private void updateControls(boolean running) {
        int points = drawOverlay != null ? drawOverlay.pointCount() : 0;
        boolean drawing = drawOverlay != null && drawOverlay.isDrawModeEnabled();
        boolean hasRoute = points >= 2;

        playButton.setText(running ? R.string.action_stop_playback : R.string.action_start_playback);
        playButton.setEnabled(running || hasRoute);
        // Nothing to clear until something is drawn; never while mocking.
        clearButton.setEnabled(!running && points > 0);
        importButton.setEnabled(!running);
        drawButton.setEnabled(!running);
        saveButton.setEnabled(!running && hasRoute);
        savedButton.setEnabled(!running);
        speedSlider.setEnabled(!running);
        loopSwitch.setEnabled(!running);

        if (drawing) {
            drawButton.setText(R.string.action_draw_on);
        } else {
            drawButton.setText(hasRoute ? R.string.action_redraw : R.string.action_draw_off);
        }
    }

    // --- imported image trace ---

    private void registerTraceLauncher() {
        traceLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        applyTracedRoute();
                    }
                });
    }

    private void applyTracedRoute() {
        final List<double[]> normalized = TraceHandoff.consume();
        if (normalized == null || normalized.size() < 2) {
            return;
        }
        // Map view may not be laid out yet on return; defer if needed.
        if (mapView.getWidth() == 0 || mapView.getHeight() == 0) {
            mapView.post(() -> placeNormalizedRoute(normalized));
        } else {
            placeNormalizedRoute(normalized);
        }
    }

    /**
     * Fits the normalized ({@code [0,1]} uniform-scale) trace into the current map
     * viewport, centered, preserving aspect, and converts screen positions to geo
     * points via the live projection.
     */
    private void placeNormalizedRoute(@NonNull List<double[]> normalized) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : normalized) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        double boxW = Math.max(1e-6, maxX - minX);
        double boxH = Math.max(1e-6, maxY - minY);

        int vw = mapView.getWidth();
        int vh = mapView.getHeight();
        double availW = vw * 0.85;
        double availH = vh * 0.85;
        double scale = Math.min(availW / boxW, availH / boxH);

        double drawW = boxW * scale;
        double drawH = boxH * scale;
        double left = (vw - drawW) / 2.0;
        double top = (vh - drawH) / 2.0;

        Projection projection = mapView.getProjection();
        List<GeoPoint> geoPoints = new ArrayList<>(normalized.size());
        for (double[] p : normalized) {
            int sx = (int) Math.round(left + (p[0] - minX) * scale);
            int sy = (int) Math.round(top + (p[1] - minY) * scale);
            GeoPoint g = (GeoPoint) projection.fromPixels(sx, sy);
            geoPoints.add(g);
        }

        if (drawOverlay.isDrawModeEnabled()) {
            toggleDrawMode();
        }
        drawOverlay.setRoute(geoPoints);
        arrowOverlay.hide();
        mapView.getController().animateTo(geoPoints.get(0));
        mapView.invalidate();
        updateControls(false);
        statusText.setText(R.string.toast_trace_applied);
    }

    // --- saving / loading routes ---

    private void showSaveDialog() {
        final List<double[]> route = drawOverlay.exportLatLng();
        if (route.size() < 2) {
            Toast.makeText(this, R.string.toast_save_needs_route, Toast.LENGTH_SHORT).show();
            return;
        }
        final TextInputEditText input = new TextInputEditText(this);
        input.setHint(R.string.dialog_save_hint);
        input.setSingleLine(true);
        int pad = Math.round(getResources().getDisplayMetrics().density * 20f);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_save_title)
                .setView(wrap)
                .setPositiveButton(R.string.action_save_route, (d, w) -> {
                    CharSequence text = input.getText();
                    String name = text != null ? text.toString().trim() : "";
                    if (name.isEmpty()) {
                        name = defaultRouteName();
                    }
                    saveRoute(name, route);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String defaultRouteName() {
        CharSequence stamp = DateFormat.format("MMM d, HH:mm", System.currentTimeMillis());
        return getString(R.string.default_route_name, stamp);
    }

    private void saveRoute(@NonNull String name, @NonNull List<double[]> route) {
        final long now = System.currentTimeMillis();
        storageExecutor.execute(() -> {
            RouteStore.save(getApplicationContext(), name, route, now);
            runOnUiThread(() ->
                    Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show());
        });
    }

    private void registerSavedRoutesLauncher() {
        savedRoutesLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        return;
                    }
                    String id = result.getData().getStringExtra(SavedRoutesActivity.EXTRA_ROUTE_ID);
                    if (id != null) {
                        loadRoute(id);
                    }
                });
    }

    private void loadRoute(@NonNull String id) {
        storageExecutor.execute(() -> {
            final SavedRoute route = RouteStore.load(getApplicationContext(), id);
            runOnUiThread(() -> {
                if (route == null || route.points.size() < 2) {
                    Toast.makeText(this, R.string.toast_route_load_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                applyLoadedRoute(route.points);
            });
        });
    }

    private void applyLoadedRoute(@NonNull List<double[]> latLng) {
        List<GeoPoint> geoPoints = new ArrayList<>(latLng.size());
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (double[] p : latLng) {
            geoPoints.add(new GeoPoint(p[0], p[1]));
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, p[1]);
            maxLon = Math.max(maxLon, p[1]);
        }
        if (drawOverlay.isDrawModeEnabled()) {
            toggleDrawMode();
        }
        drawOverlay.setRoute(geoPoints);
        arrowOverlay.hide();

        final BoundingBox box = new BoundingBox(maxLat, maxLon, minLat, minLon);
        mapView.post(() -> mapView.zoomToBoundingBox(box, true, 80));
        mapView.invalidate();
        updateControls(false);
        statusText.setText(R.string.toast_route_loaded);
    }

    // --- mock-location app selection ---

    @SuppressLint("WrongConstant")
    @SuppressWarnings("deprecation")
    private boolean isMockLocationAppSelected() {
        // Best-effort probe: register + remove a test provider. Throws SecurityException
        // if this app is not the selected mock-location app.
        android.location.LocationManager lm =
                (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return false;
        }
        final String probe = android.location.LocationManager.GPS_PROVIDER;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ProviderProperties props = new ProviderProperties.Builder().build();
                lm.addTestProvider(probe, props);
            } else {
                lm.addTestProvider(probe,
                        false, false, false, false,
                        true, true, true,
                        android.location.Criteria.POWER_LOW,
                        android.location.Criteria.ACCURACY_FINE);
            }
            lm.removeTestProvider(probe);
            return true;
        } catch (SecurityException e) {
            return false;
        } catch (IllegalArgumentException e) {
            // Provider already existed from a live run; treat as selected.
            return true;
        }
    }

    private void promptEnableMockApp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_mock_title)
                .setMessage(R.string.dialog_mock_message)
                .setPositiveButton(R.string.action_open_dev, (d, w) -> openDeveloperSettings())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openDeveloperSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- permissions ---

    private void registerPermissionLauncher() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    Boolean fine = result.get(Manifest.permission.ACCESS_FINE_LOCATION);
                    if (fine != null && !fine) {
                        Toast.makeText(this, R.string.toast_location_needed, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void requestRuntimePermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }
}
