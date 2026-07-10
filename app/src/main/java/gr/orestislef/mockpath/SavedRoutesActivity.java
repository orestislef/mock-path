package gr.orestislef.mockpath;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import gr.orestislef.mockpath.storage.RoutePreview;
import gr.orestislef.mockpath.storage.RouteStore;
import gr.orestislef.mockpath.storage.SavedRoute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists persisted routes with a shape preview, name and metadata. Returns the selected
 * route id to {@link MainActivity} via {@code RESULT_OK}.
 */
public final class SavedRoutesActivity extends AppCompatActivity {

    public static final String EXTRA_ROUTE_ID = "route_id";

    private static final int PREVIEW_PX = 160;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService background;

    private RecyclerView list;
    private TextView emptyView;
    private RouteAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved_routes);
        background = Executors.newSingleThreadExecutor();

        applyWindowInsets();

        list = findViewById(R.id.saved_list);
        emptyView = findViewById(R.id.saved_empty);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RouteAdapter();
        list.setAdapter(adapter);

        reload();
    }

    private void applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);
        View root = findViewById(R.id.saved_root);
        final int top = root.getPaddingTop();
        final int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(bars.left, top + bars.top, bars.right, bottom + bars.bottom);
            return insets;
        });
    }

    private void reload() {
        background.execute(() -> {
            final List<SavedRoute> routes = RouteStore.listAll(getApplicationContext());
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                adapter.setRoutes(routes);
                emptyView.setVisibility(routes.isEmpty() ? View.VISIBLE : View.GONE);
                list.setVisibility(routes.isEmpty() ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void confirmDelete(@NonNull SavedRoute route) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.saved_delete_title)
                .setMessage(getString(R.string.saved_delete_message, route.name))
                .setPositiveButton(R.string.saved_delete, (d, w) -> background.execute(() -> {
                    RouteStore.delete(getApplicationContext(), route.id);
                    main.post(this::reload);
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void selectRoute(@NonNull SavedRoute route) {
        setResult(RESULT_OK, new android.content.Intent().putExtra(EXTRA_ROUTE_ID, route.id));
        finish();
    }

    @Override
    protected void onDestroy() {
        if (background != null) {
            background.shutdownNow();
        }
        super.onDestroy();
    }

    // --- adapter ---

    private final class RouteAdapter extends RecyclerView.Adapter<RouteViewHolder> {

        private final List<SavedRoute> routes = new ArrayList<>();
        private final Map<String, Bitmap> previewCache = new HashMap<>();

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        void setRoutes(@NonNull List<SavedRoute> newRoutes) {
            // Whole list is replaced on (re)load; a full refresh is correct here.
            routes.clear();
            routes.addAll(newRoutes);
            previewCache.clear();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RouteViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_saved_route, parent, false);
            return new RouteViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RouteViewHolder holder, int position) {
            SavedRoute route = routes.get(position);
            holder.name.setText(route.name);
            CharSequence when = route.createdAt > 0
                    ? DateUtils.getRelativeTimeSpanString(route.createdAt)
                    : "";
            holder.subtitle.setText(getString(R.string.saved_subtitle, route.pointCount(), when));

            Bitmap preview = previewCache.get(route.id);
            if (preview == null) {
                preview = RoutePreview.render(route.points, PREVIEW_PX);
                previewCache.put(route.id, preview);
            }
            holder.preview.setImageBitmap(preview);

            holder.itemView.setOnClickListener(v -> selectRoute(route));
            holder.delete.setOnClickListener(v -> confirmDelete(route));
        }

        @Override
        public int getItemCount() {
            return routes.size();
        }
    }

    private static final class RouteViewHolder extends RecyclerView.ViewHolder {
        final ImageView preview;
        final TextView name;
        final TextView subtitle;
        final ImageButton delete;

        RouteViewHolder(@NonNull View itemView) {
            super(itemView);
            preview = itemView.findViewById(R.id.item_preview);
            name = itemView.findViewById(R.id.item_name);
            subtitle = itemView.findViewById(R.id.item_subtitle);
            delete = itemView.findViewById(R.id.item_delete);
        }
    }
}
