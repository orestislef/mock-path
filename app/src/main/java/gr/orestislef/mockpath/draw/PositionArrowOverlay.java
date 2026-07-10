package gr.orestislef.mockpath.draw;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;

import androidx.annotation.Nullable;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/**
 * Draws the current mocked position as a navigation arrow oriented along the direction
 * of travel. Bearing convention matches the platform: 0° points to map-north (up) and
 * increases clockwise, so the arrow head always points where the route is heading.
 */
public final class PositionArrowOverlay extends Overlay {

    private static final float SIZE_DP = 16f;

    @Nullable
    private GeoPoint position;
    private float bearingDegrees = 0f;
    private boolean visible = false;

    private final float sizePx;
    private final Paint fillPaint;
    private final Paint strokePaint;
    private final Paint haloPaint;
    private final Path arrow = new Path();
    private final Point screen = new Point();

    public PositionArrowOverlay(float density) {
        this.sizePx = SIZE_DP * density;

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(Color.parseColor("#1E88E5"));
        fillPaint.setStyle(Paint.Style.FILL);

        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(Color.WHITE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(density * 2f);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        haloPaint.setColor(Color.parseColor("#33000000"));
        haloPaint.setStyle(Paint.Style.FILL);
    }

    /** Updates the arrow position and heading, and makes it visible. */
    public void setPosition(GeoPoint position, float bearingDegrees) {
        this.position = position;
        this.bearingDegrees = bearingDegrees;
        this.visible = true;
    }

    public void hide() {
        this.visible = false;
    }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || !visible || position == null) {
            return;
        }
        Projection projection = mapView.getProjection();
        projection.toPixels(position, screen);
        float cx = screen.x;
        float cy = screen.y;

        // Account for any map rotation so the arrow tracks true heading on screen.
        float mapOrientation = mapView.getMapOrientation();
        canvas.save();
        canvas.rotate(bearingDegrees + mapOrientation, cx, cy);

        float s = sizePx;
        canvas.drawCircle(cx, cy, s * 0.9f, haloPaint);

        arrow.reset();
        arrow.moveTo(cx, cy - s);                 // tip (forward)
        arrow.lineTo(cx - s * 0.72f, cy + s * 0.8f);
        arrow.lineTo(cx, cy + s * 0.35f);         // tail notch
        arrow.lineTo(cx + s * 0.72f, cy + s * 0.8f);
        arrow.close();

        canvas.drawPath(arrow, fillPaint);
        canvas.drawPath(arrow, strokePaint);
        canvas.restore();
    }
}
