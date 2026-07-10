package gr.orestislef.mockpath.storage;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.annotation.NonNull;

import java.util.List;

/** Renders a small preview thumbnail of a route's shape from its {lat, lon} points. */
public final class RoutePreview {

    private RoutePreview() {
    }

    /**
     * @param points route points as {lat, lon}
     * @param size   output bitmap edge length in pixels
     * @return a square thumbnail with the route centered and aspect-preserved
     */
    @NonNull
    public static Bitmap render(@NonNull List<double[]> points, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.parseColor("#F2F4F7"));
        if (points.size() < 2) {
            return bmp;
        }

        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (double[] p : points) {
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, p[1]);
            maxLon = Math.max(maxLon, p[1]);
        }
        double spanLat = Math.max(1e-9, maxLat - minLat);
        double spanLon = Math.max(1e-9, maxLon - minLon);

        float pad = size * 0.12f;
        float avail = size - 2 * pad;
        double scale = Math.min(avail / spanLon, avail / spanLat);
        float drawW = (float) (spanLon * scale);
        float drawH = (float) (spanLat * scale);
        float offX = (size - drawW) / 2f;
        float offY = (size - drawH) / 2f;

        Path path = new Path();
        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            float x = (float) (offX + (p[1] - minLon) * scale);
            // Latitude increases north (up) -> invert y for screen space.
            float y = (float) (offY + (maxLat - p[0]) * scale);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(Color.parseColor("#1E88E5"));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, size * 0.02f));
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(path, stroke);

        // Start (green) and end (red) markers.
        float r = Math.max(3f, size * 0.03f);
        double[] first = points.get(0);
        double[] last = points.get(points.size() - 1);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.parseColor("#2E7D32"));
        canvas.drawCircle((float) (offX + (first[1] - minLon) * scale),
                (float) (offY + (maxLat - first[0]) * scale), r, dot);
        dot.setColor(Color.parseColor("#C62828"));
        canvas.drawCircle((float) (offX + (last[1] - minLon) * scale),
                (float) (offY + (maxLat - last[0]) * scale), r, dot);

        return bmp;
    }
}
