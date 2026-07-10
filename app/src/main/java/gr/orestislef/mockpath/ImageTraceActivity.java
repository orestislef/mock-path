package gr.orestislef.mockpath;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;
import gr.orestislef.mockpath.editor.PathEditorView;
import gr.orestislef.mockpath.image.ContourTracer;
import gr.orestislef.mockpath.image.EdgeDetector;
import gr.orestislef.mockpath.image.ImageLoader;
import gr.orestislef.mockpath.image.TraceHandoff;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Screen for importing an image/logo, edge-detecting it into a path, and editing the
 * result before handing it back to the map. Detection and tracing run on a background
 * executor; the multithreaded {@link EdgeDetector} parallelises the per-pixel work.
 */
public final class ImageTraceActivity extends AppCompatActivity {

    private static final int MAX_IMAGE_DIM = 1000;
    private static final int BLUR_RADIUS = 1;
    private static final int MIN_TRACE_POINTS = 6;
    private static final long RETRACE_DEBOUNCE_MS = 250L;

    private PathEditorView editor;
    private ProgressBar progress;
    private TextView hint;
    private Slider thresholdSlider;
    private Slider detailSlider;
    private MaterialButtonToggleGroup modeGroup;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService background;
    private EdgeDetector edgeDetector;
    private final AtomicLong traceToken = new AtomicLong(0);
    private int pendingJobs = 0;

    @Nullable
    private Bitmap sourceBitmap;

    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia;

    private final Runnable retraceRunnable = this::retrace;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_trace);

        editor = findViewById(R.id.editor);
        progress = findViewById(R.id.progress);
        hint = findViewById(R.id.trace_hint);
        thresholdSlider = findViewById(R.id.slider_threshold);
        detailSlider = findViewById(R.id.slider_detail);
        modeGroup = findViewById(R.id.mode_group);

        background = Executors.newSingleThreadExecutor();
        edgeDetector = new EdgeDetector();

        applyWindowInsets();
        setupControls();
        registerPicker();

        if (sourceBitmap == null) {
            launchPicker();
        }
    }

    private void applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightNavigationBars(true);

        View root = findViewById(R.id.trace_root);
        View panel = findViewById(R.id.trace_panel_content);
        final int panelBase = panel.getPaddingBottom();
        final int panelLeft = panel.getPaddingLeft();
        final int panelRight = panel.getPaddingRight();
        final int hintBase = ((ViewGroup.MarginLayoutParams) hint.getLayoutParams()).topMargin;
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            panel.setPadding(panelLeft + bars.left, panel.getPaddingTop(),
                    panelRight + bars.right, panelBase + bars.bottom);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) hint.getLayoutParams();
            lp.topMargin = hintBase + bars.top;
            hint.setLayoutParams(lp);
            return insets;
        });
    }

    private void setupControls() {
        modeGroup.check(R.id.btn_mode_draw);
        modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            if (checkedId == R.id.btn_mode_erase) {
                editor.setMode(PathEditorView.MODE_ERASE);
                hint.setText(R.string.trace_hint_erase);
            } else if (checkedId == R.id.btn_mode_move) {
                editor.setMode(PathEditorView.MODE_MOVE);
                hint.setText(R.string.trace_hint_move);
            } else {
                editor.setMode(PathEditorView.MODE_DRAW);
                hint.setText(R.string.trace_hint_draw);
            }
        });

        findViewById(R.id.btn_undo).setOnClickListener(v -> editor.undo());
        findViewById(R.id.btn_clear_edit).setOnClickListener(v -> editor.clearAll());
        findViewById(R.id.btn_repick).setOnClickListener(v -> launchPicker());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        findViewById(R.id.btn_use_path).setOnClickListener(v -> confirmPath());

        Slider.OnChangeListener retraceOnChange = (slider, value, fromUser) -> {
            if (fromUser) {
                scheduleRetrace();
            }
        };
        thresholdSlider.addOnChangeListener(retraceOnChange);
        detailSlider.addOnChangeListener(retraceOnChange);
    }

    private void registerPicker() {
        pickMedia = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), uri -> {
                    if (uri != null) {
                        loadImage(uri);
                    } else if (sourceBitmap == null) {
                        // User backed out before choosing any image.
                        finish();
                    }
                });
    }

    private void launchPicker() {
        pickMedia.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void loadImage(@NonNull Uri uri) {
        showBusy(true);
        beginJob();
        background.execute(() -> {
            final Bitmap bmp = ImageLoader.loadDownscaled(getApplicationContext(), uri, MAX_IMAGE_DIM);
            mainHandler.post(() -> {
                endJob();
                if (isFinishing() || isDestroyed()) {
                    if (bmp != null) {
                        bmp.recycle();
                    }
                    return;
                }
                if (bmp == null) {
                    showBusy(false);
                    Toast.makeText(this, R.string.toast_image_failed, Toast.LENGTH_LONG).show();
                    if (sourceBitmap == null) {
                        finish();
                    }
                    return;
                }
                if (sourceBitmap != null && sourceBitmap != bmp) {
                    sourceBitmap.recycle();
                }
                sourceBitmap = bmp;
                editor.setBitmap(bmp);
                retrace();
            });
        });
    }

    private void scheduleRetrace() {
        mainHandler.removeCallbacks(retraceRunnable);
        mainHandler.postDelayed(retraceRunnable, RETRACE_DEBOUNCE_MS);
    }

    private void retrace() {
        final Bitmap bmp = sourceBitmap;
        if (bmp == null) {
            return;
        }
        final int threshold = Math.round(thresholdSlider.getValue());
        // Detail slider: higher = more detail = smaller RDP epsilon.
        final float epsilon = 11f - detailSlider.getValue();
        final long token = traceToken.incrementAndGet();

        showBusy(true);
        beginJob();
        background.execute(() -> {
            List<List<float[]>> polylines = null;
            try {
                EdgeDetector.EdgeMap map = edgeDetector.detect(bmp, threshold, BLUR_RADIUS);
                polylines = ContourTracer.trace(map, MIN_TRACE_POINTS, epsilon);
            } catch (RuntimeException e) {
                // Detector interrupted on shutdown, or OOM; drop this run.
            }
            final List<List<float[]>> result = polylines;
            mainHandler.post(() -> {
                endJob();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                // Ignore stale runs superseded by a newer request.
                if (token != traceToken.get()) {
                    return;
                }
                if (result != null) {
                    editor.setStrokes(result);
                }
                showBusy(false);
            });
        });
    }

    private void confirmPath() {
        List<double[]> route = editor.exportNormalizedRoute();
        if (route.size() < 2) {
            Toast.makeText(this, R.string.toast_trace_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        TraceHandoff.set(route);
        setResult(RESULT_OK);
        finish();
    }

    // --- busy tracking (multiple overlapping jobs) ---

    private void beginJob() {
        pendingJobs++;
    }

    private void endJob() {
        if (pendingJobs > 0) {
            pendingJobs--;
        }
    }

    private void showBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(retraceRunnable);
        if (background != null) {
            background.shutdownNow();
        }
        if (edgeDetector != null) {
            edgeDetector.shutdown();
        }
        if (sourceBitmap != null) {
            sourceBitmap.recycle();
            sourceBitmap = null;
        }
        super.onDestroy();
    }
}
