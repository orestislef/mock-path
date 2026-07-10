package gr.orestislef.mockpath.storage;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * File-backed persistence for saved routes. Each route is one JSON file under
 * {@code filesDir/routes/}. All methods perform blocking IO and should be called off
 * the main thread.
 */
public final class RouteStore {

    private static final String TAG = "RouteStore";
    private static final String DIR = "routes";
    private static final String EXT = ".json";

    private RouteStore() {
    }

    @NonNull
    private static File dir(@NonNull Context context) {
        File d = new File(context.getFilesDir(), DIR);
        if (!d.exists() && !d.mkdirs()) {
            Log.w(TAG, "Could not create routes directory");
        }
        return d;
    }

    /** Saves a route and returns its generated id. */
    @NonNull
    public static String save(@NonNull Context context, @NonNull String name,
                              @NonNull List<double[]> points, long createdAt) {
        String id = "route_" + createdAt;
        writeFile(context, new SavedRoute(id, name, createdAt, points));
        return id;
    }

    /** Overwrites (or creates) the given route. */
    public static void writeFile(@NonNull Context context, @NonNull SavedRoute route) {
        JSONObject root = new JSONObject();
        try {
            root.put("name", route.name);
            root.put("createdAt", route.createdAt);
            JSONArray arr = new JSONArray();
            for (double[] p : route.points) {
                JSONArray pair = new JSONArray();
                pair.put(p[0]);
                pair.put(p[1]);
                arr.put(pair);
            }
            root.put("points", arr);
        } catch (JSONException e) {
            Log.w(TAG, "Serialize failed", e);
            return;
        }
        File f = new File(dir(context), route.id + EXT);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Write failed for " + route.id, e);
        }
    }

    /** Returns all saved routes, newest first. */
    @NonNull
    public static List<SavedRoute> listAll(@NonNull Context context) {
        List<SavedRoute> out = new ArrayList<>();
        File[] files = dir(context).listFiles((d, n) -> n.endsWith(EXT));
        if (files == null) {
            return out;
        }
        for (File f : files) {
            SavedRoute r = readFile(f);
            if (r != null) {
                out.add(r);
            }
        }
        Collections.sort(out, new Comparator<SavedRoute>() {
            @Override
            public int compare(SavedRoute a, SavedRoute b) {
                return Long.compare(b.createdAt, a.createdAt);
            }
        });
        return out;
    }

    @Nullable
    public static SavedRoute load(@NonNull Context context, @NonNull String id) {
        return readFile(new File(dir(context), id + EXT));
    }

    public static void delete(@NonNull Context context, @NonNull String id) {
        File f = new File(dir(context), id + EXT);
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "Delete failed for " + id);
        }
    }

    @Nullable
    private static SavedRoute readFile(@NonNull File f) {
        if (!f.exists()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "Read failed for " + f.getName(), e);
            return null;
        }
        try {
            JSONObject root = new JSONObject(sb.toString());
            String name = root.optString("name", "Route");
            long createdAt = root.optLong("createdAt", 0L);
            JSONArray arr = root.optJSONArray("points");
            List<double[]> points = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray pair = arr.optJSONArray(i);
                    if (pair != null && pair.length() >= 2) {
                        points.add(new double[]{pair.optDouble(0), pair.optDouble(1)});
                    }
                }
            }
            String id = f.getName().substring(0, f.getName().length() - EXT.length());
            return new SavedRoute(id, name, createdAt, points);
        } catch (JSONException e) {
            Log.w(TAG, "Parse failed for " + f.getName(), e);
            return null;
        }
    }
}
