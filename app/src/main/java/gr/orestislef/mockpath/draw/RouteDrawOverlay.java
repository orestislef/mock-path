package gr.orestislef.mockpath.draw;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.util.ArrayList;
import java.util.List;

/**
 * osmdroid overlay that captures a free-hand "signature" stroke when draw mode is
 * enabled and renders it. Screen touches are converted to {@link GeoPoint}s so the
 * stroke stays geo-anchored while the map is panned/zoomed afterwards.
 *
 * <p>All access happens on the UI thread (touch + draw callbacks), so no extra
 * synchronization is required for {@link #points}.
 */
public final class RouteDrawOverlay extends Overlay {

    public interface OnRouteChangedListener {
        void onRouteChanged(int pointCount);
    }

    /** Minimum screen-space gap between captured samples, in pixels. */
    private static final float MIN_SAMPLE_DISTANCE_PX = 6f;

    private final List<GeoPoint> points = new ArrayList<>();
    private final Path renderPath = new Path();
    private final Paint strokePaint;
    private final Paint startPaint;
    private final Paint endPaint;

    private boolean drawModeEnabled = false;
    private float lastSampleX = Float.NaN;
    private float lastSampleY = Float.NaN;

    @Nullable
    private OnRouteChangedListener listener;

    public RouteDrawOverlay() {
        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(Color.parseColor("#1E88E5"));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(10f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        startPaint.setColor(Color.parseColor("#2E7D32"));
        startPaint.setStyle(Paint.Style.FILL);

        endPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        endPaint.setColor(Color.parseColor("#C62828"));
        endPaint.setStyle(Paint.Style.FILL);
    }

    public void setOnRouteChangedListener(@Nullable OnRouteChangedListener listener) {
        this.listener = listener;
    }

    public void setDrawModeEnabled(boolean enabled) {
        this.drawModeEnabled = enabled;
    }

    public boolean isDrawModeEnabled() {
        return drawModeEnabled;
    }

    public void clear() {
        points.clear();
        renderPath.reset();
        lastSampleX = Float.NaN;
        lastSampleY = Float.NaN;
        notifyChanged();
    }

    /** Replaces the route with the given geo points (e.g. from an imported trace). */
    public void setRoute(@NonNull List<GeoPoint> newPoints) {
        points.clear();
        points.addAll(newPoints);
        lastSampleX = Float.NaN;
        lastSampleY = Float.NaN;
        notifyChanged();
    }

    public int pointCount() {
        return points.size();
    }

    /** Returns a defensive copy of the drawn route as {lat, lon} pairs. */
    @NonNull
    public List<double[]> exportLatLng() {
        List<double[]> out = new ArrayList<>(points.size());
        for (GeoPoint g : points) {
            out.add(new double[]{g.getLatitude(), g.getLongitude()});
        }
        return out;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event, MapView mapView) {
        if (!drawModeEnabled) {
            return false; // let the map handle panning/zooming
        }
        final Projection projection = mapView.getProjection();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                points.clear();
                renderPath.reset();
                addSample(projection, event.getX(), event.getY(), true);
                mapView.invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                // Historical samples give smoother strokes on fast drags.
                for (int h = 0; h < event.getHistorySize(); h++) {
                    addSample(projection, event.getHistoricalX(h), event.getHistoricalY(h), false);
                }
                addSample(projection, event.getX(), event.getY(), false);
                mapView.invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                addSample(projection, event.getX(), event.getY(), false);
                notifyChanged();
                mapView.invalidate();
                return true;
            default:
                return false;
        }
    }

    private void addSample(@NonNull Projection projection, float x, float y, boolean force) {
        if (!force && !Float.isNaN(lastSampleX)) {
            float dx = x - lastSampleX;
            float dy = y - lastSampleY;
            if (dx * dx + dy * dy < MIN_SAMPLE_DISTANCE_PX * MIN_SAMPLE_DISTANCE_PX) {
                return;
            }
        }
        GeoPoint geo = (GeoPoint) projection.fromPixels((int) x, (int) y);
        points.add(geo);
        lastSampleX = x;
        lastSampleY = y;
    }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || points.isEmpty()) {
            return;
        }
        final Projection projection = mapView.getProjection();
        renderPath.reset();
        final Point screen = new Point();

        projection.toPixels(points.get(0), screen);
        float startX = screen.x;
        float startY = screen.y;
        renderPath.moveTo(startX, startY);

        float endX = startX;
        float endY = startY;
        for (int i = 1; i < points.size(); i++) {
            projection.toPixels(points.get(i), screen);
            renderPath.lineTo(screen.x, screen.y);
            endX = screen.x;
            endY = screen.y;
        }

        canvas.drawPath(renderPath, strokePaint);
        canvas.drawCircle(startX, startY, 14f, startPaint);
        if (points.size() > 1) {
            canvas.drawCircle(endX, endY, 14f, endPaint);
        }
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onRouteChanged(points.size());
        }
    }
}
