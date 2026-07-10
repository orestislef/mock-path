package gr.orestislef.mockpath.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import gr.orestislef.mockpath.image.RouteChainer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Interactive editor for a traced path over its source image. Strokes are stored in
 * image pixel space and rendered through a fit matrix, so edits stay aligned with the
 * bitmap regardless of view size. Supports free-hand add, radial erase, and undo.
 *
 * <p>All state is touched only on the UI thread.
 */
public final class PathEditorView extends View {

    public static final int MODE_DRAW = 0;
    public static final int MODE_ERASE = 1;
    public static final int MODE_MOVE = 2;

    public interface OnEditListener {
        void onEdited(int strokeCount);
    }

    private static final float MIN_SAMPLE_BMP_PX = 2f;
    private static final float ERASE_RADIUS_VIEW_PX = 22f;
    private static final float GRAB_RADIUS_VIEW_PX = 26f;
    private static final int MAX_UNDO = 24;

    @Nullable
    private Bitmap bitmap;
    private final List<List<float[]>> strokes = new ArrayList<>();
    private final Deque<List<List<float[]>>> undoStack = new ArrayDeque<>();

    private final Matrix fitMatrix = new Matrix();
    private final Matrix inverseMatrix = new Matrix();
    private float fitScale = 1f;

    private int mode = MODE_DRAW;

    private final Paint bitmapPaint;
    private final Paint strokePaint;
    private final Paint nodePaint;
    private final Paint erasePaint;
    private final Paint handleRingPaint;
    private final Path renderPath = new Path();
    private final float[] drawPoint = new float[2];

    @Nullable
    private List<float[]> activeStroke;
    @Nullable
    private float[] draggingPoint;
    private float lastX = Float.NaN;
    private float lastY = Float.NaN;
    private float eraseCx = Float.NaN;
    private float eraseCy = Float.NaN;
    private boolean showEraseCursor = false;

    @Nullable
    private OnEditListener editListener;

    public PathEditorView(Context context) {
        this(context, null);
    }

    public PathEditorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(Color.parseColor("#1E88E5"));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(5f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        nodePaint.setColor(Color.parseColor("#FF7043"));
        nodePaint.setStyle(Paint.Style.FILL);

        erasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        erasePaint.setColor(Color.parseColor("#55C62828"));
        erasePaint.setStyle(Paint.Style.FILL);

        handleRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handleRingPaint.setColor(Color.WHITE);
        handleRingPaint.setStyle(Paint.Style.STROKE);
        handleRingPaint.setStrokeWidth(3f);
    }

    public void setOnEditListener(@Nullable OnEditListener listener) {
        this.editListener = listener;
    }

    public void setBitmap(@NonNull Bitmap bitmap) {
        this.bitmap = bitmap;
        recomputeMatrix();
        invalidate();
    }

    /** Replaces all strokes (e.g. from edge tracing). Clears undo history. */
    public void setStrokes(@NonNull List<List<float[]>> newStrokes) {
        strokes.clear();
        for (List<float[]> s : newStrokes) {
            strokes.add(deepCopyStroke(s));
        }
        undoStack.clear();
        notifyEdited();
        invalidate();
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    public int getMode() {
        return mode;
    }

    public int strokeCount() {
        return strokes.size();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        List<List<float[]>> prev = undoStack.pop();
        strokes.clear();
        strokes.addAll(prev);
        notifyEdited();
        invalidate();
    }

    public void clearAll() {
        if (strokes.isEmpty()) {
            return;
        }
        pushUndo();
        strokes.clear();
        notifyEdited();
        invalidate();
    }

    /**
     * Chains all strokes into one route and normalises to {@code px / max(w, h)}.
     * @return normalized route as {x, y} pairs, or empty if nothing drawn / no bitmap
     */
    @NonNull
    public List<double[]> exportNormalizedRoute() {
        List<double[]> out = new ArrayList<>();
        if (bitmap == null) {
            return out;
        }
        List<float[]> chained = RouteChainer.chain(strokes);
        float denom = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (denom <= 0f) {
            return out;
        }
        for (float[] p : chained) {
            out.add(new double[]{p[0] / denom, p[1] / denom});
        }
        return out;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeMatrix();
    }

    private void recomputeMatrix() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float bw = bitmap.getWidth();
        float bh = bitmap.getHeight();
        fitScale = Math.min(getWidth() / bw, getHeight() / bh);
        float dx = (getWidth() - bw * fitScale) / 2f;
        float dy = (getHeight() - bh * fitScale) / 2f;
        fitMatrix.reset();
        fitMatrix.postScale(fitScale, fitScale);
        fitMatrix.postTranslate(dx, dy);
        fitMatrix.invert(inverseMatrix);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) {
            return;
        }
        canvas.drawBitmap(bitmap, fitMatrix, bitmapPaint);

        final float[] pt = drawPoint;
        for (List<float[]> stroke : strokes) {
            if (stroke.size() < 2) {
                continue;
            }
            renderPath.reset();
            mapPoint(stroke.get(0), pt);
            renderPath.moveTo(pt[0], pt[1]);
            for (int i = 1; i < stroke.size(); i++) {
                mapPoint(stroke.get(i), pt);
                renderPath.lineTo(pt[0], pt[1]);
            }
            canvas.drawPath(renderPath, strokePaint);
            mapPoint(stroke.get(0), pt);
            canvas.drawCircle(pt[0], pt[1], 5f, nodePaint);
            mapPoint(stroke.get(stroke.size() - 1), pt);
            canvas.drawCircle(pt[0], pt[1], 5f, nodePaint);

            // In move mode, show every vertex as a draggable handle.
            if (mode == MODE_MOVE) {
                for (float[] vertex : stroke) {
                    mapPoint(vertex, pt);
                    canvas.drawCircle(pt[0], pt[1], 8f, handleRingPaint);
                    canvas.drawCircle(pt[0], pt[1], 5f, nodePaint);
                }
            }
        }

        if (showEraseCursor && mode == MODE_ERASE
                && !Float.isNaN(eraseCx)) {
            canvas.drawCircle(eraseCx, eraseCy, ERASE_RADIUS_VIEW_PX, erasePaint);
        }
    }

    private void mapPoint(@NonNull float[] bmpPoint, @NonNull float[] out) {
        out[0] = bmpPoint[0];
        out[1] = bmpPoint[1];
        fitMatrix.mapPoints(out);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return false;
        }
        float vx = event.getX();
        float vy = event.getY();
        float[] bmp = viewToBitmap(vx, vy);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pushUndo();
                if (mode == MODE_DRAW) {
                    activeStroke = new ArrayList<>();
                    activeStroke.add(bmp);
                    strokes.add(activeStroke);
                    lastX = bmp[0];
                    lastY = bmp[1];
                } else if (mode == MODE_MOVE) {
                    draggingPoint = findNearestPoint(bmp);
                    if (draggingPoint != null) {
                        draggingPoint[0] = bmp[0];
                        draggingPoint[1] = bmp[1];
                    }
                } else {
                    eraseCx = vx;
                    eraseCy = vy;
                    showEraseCursor = true;
                    eraseAt(bmp);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (mode == MODE_DRAW && activeStroke != null) {
                    for (int h = 0; h < event.getHistorySize(); h++) {
                        addDrawSample(viewToBitmap(event.getHistoricalX(h), event.getHistoricalY(h)));
                    }
                    addDrawSample(bmp);
                } else if (mode == MODE_MOVE && draggingPoint != null) {
                    draggingPoint[0] = bmp[0];
                    draggingPoint[1] = bmp[1];
                } else if (mode == MODE_ERASE) {
                    eraseCx = vx;
                    eraseCy = vy;
                    for (int h = 0; h < event.getHistorySize(); h++) {
                        eraseAt(viewToBitmap(event.getHistoricalX(h), event.getHistoricalY(h)));
                    }
                    eraseAt(bmp);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                performClick();
                // fall through to shared release handling
            case MotionEvent.ACTION_CANCEL:
                if (mode == MODE_DRAW && activeStroke != null) {
                    if (activeStroke.size() < 2) {
                        strokes.remove(activeStroke);
                    }
                    activeStroke = null;
                }
                draggingPoint = null;
                showEraseCursor = false;
                lastX = Float.NaN;
                lastY = Float.NaN;
                notifyEdited();
                invalidate();
                return true;

            default:
                return false;
        }
    }

    /** Finds the route vertex nearest the touch, within a grab radius (bitmap space). */
    @Nullable
    private float[] findNearestPoint(@NonNull float[] target) {
        float grab = GRAB_RADIUS_VIEW_PX / (fitScale <= 0f ? 1f : fitScale);
        float bestDistSq = grab * grab;
        float[] best = null;
        for (List<float[]> stroke : strokes) {
            for (float[] p : stroke) {
                float dx = p[0] - target[0];
                float dy = p[1] - target[1];
                float d = dx * dx + dy * dy;
                if (d <= bestDistSq) {
                    bestDistSq = d;
                    best = p;
                }
            }
        }
        return best;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void addDrawSample(@NonNull float[] bmp) {
        if (activeStroke == null) {
            return;
        }
        if (!Float.isNaN(lastX)) {
            float dx = bmp[0] - lastX;
            float dy = bmp[1] - lastY;
            if (dx * dx + dy * dy < MIN_SAMPLE_BMP_PX * MIN_SAMPLE_BMP_PX) {
                return;
            }
        }
        activeStroke.add(bmp);
        lastX = bmp[0];
        lastY = bmp[1];
    }

    /** Removes points within the erase radius, splitting strokes as needed. */
    private void eraseAt(@NonNull float[] center) {
        float radiusBmp = ERASE_RADIUS_VIEW_PX / (fitScale <= 0f ? 1f : fitScale);
        float r2 = radiusBmp * radiusBmp;
        List<List<float[]>> rebuilt = new ArrayList<>();
        for (List<float[]> stroke : strokes) {
            List<float[]> current = new ArrayList<>();
            for (float[] p : stroke) {
                float dx = p[0] - center[0];
                float dy = p[1] - center[1];
                if (dx * dx + dy * dy > r2) {
                    current.add(p);
                } else {
                    if (current.size() >= 2) {
                        rebuilt.add(current);
                    }
                    current = new ArrayList<>();
                }
            }
            if (current.size() >= 2) {
                rebuilt.add(current);
            }
        }
        strokes.clear();
        strokes.addAll(rebuilt);
    }

    private float[] viewToBitmap(float vx, float vy) {
        float[] pt = {vx, vy};
        inverseMatrix.mapPoints(pt);
        return pt;
    }

    private void pushUndo() {
        List<List<float[]>> snapshot = new ArrayList<>(strokes.size());
        for (List<float[]> s : strokes) {
            snapshot.add(deepCopyStroke(s));
        }
        undoStack.push(snapshot);
        while (undoStack.size() > MAX_UNDO) {
            undoStack.removeLast();
        }
    }

    private static List<float[]> deepCopyStroke(@NonNull List<float[]> stroke) {
        List<float[]> copy = new ArrayList<>(stroke.size());
        for (float[] p : stroke) {
            copy.add(new float[]{p[0], p[1]});
        }
        return copy;
    }

    private void notifyEdited() {
        if (editListener != null) {
            editListener.onEdited(strokes.size());
        }
    }
}
