package gr.orestislef.mockpath.mock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import gr.orestislef.mockpath.MainActivity;
import gr.orestislef.mockpath.R;

import java.util.List;

/**
 * Foreground service that runs the {@link MockLocationEngine} on a dedicated
 * {@link HandlerThread}, independent of the UI thread. Broadcasts progress back to
 * any registered UI via {@link LocalBroadcastRelay}.
 */
public final class MockLocationService extends Service implements MockLocationEngine.Listener {

    public static final String ACTION_START = "gr.orestislef.mockpath.action.START";
    public static final String ACTION_STOP = "gr.orestislef.mockpath.action.STOP";

    public static final String EXTRA_SPEED_MPS = "speed_mps";
    public static final String EXTRA_LOOP = "loop";

    /** Broadcast actions consumed by the UI. */
    public static final String BROADCAST_FIX = "gr.orestislef.mockpath.broadcast.FIX";
    public static final String BROADCAST_COMPLETED = "gr.orestislef.mockpath.broadcast.COMPLETED";
    public static final String BROADCAST_ERROR = "gr.orestislef.mockpath.broadcast.ERROR";
    public static final String BROADCAST_STOPPED = "gr.orestislef.mockpath.broadcast.STOPPED";

    public static final String KEY_LAT = "lat";
    public static final String KEY_LON = "lon";
    public static final String KEY_BEARING = "bearing";
    public static final String KEY_SPEED = "speed";
    public static final String KEY_FRACTION = "fraction";
    public static final String KEY_MESSAGE = "message";

    private static final String TAG = "MockLocationService";
    private static final String CHANNEL_ID = "mock_path_channel";
    private static final int NOTIFICATION_ID = 42;
    private static final long TICK_INTERVAL_MS = 1000L;

    private static volatile boolean serviceRunning = false;

    private HandlerThread workerThread;
    private Handler workerHandler;
    private Handler mainHandler;
    @Nullable
    private MockLocationEngine engine;
    @Nullable
    private PowerManager.WakeLock wakeLock;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            MockLocationEngine e = engine;
            if (e == null || !e.isRunning()) {
                return;
            }
            e.tick();
            // Re-check: tick() may have completed the route and nulled running.
            if (e.isRunning()) {
                workerHandler.postDelayed(this, TICK_INTERVAL_MS);
            }
        }
    };

    public static boolean isRunning() {
        return serviceRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(getMainLooper());
        workerThread = new HandlerThread("mock-location-worker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) {
            stopEverything();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        final float speed = intent.getFloatExtra(EXTRA_SPEED_MPS, 8.33f);
        final boolean loop = intent.getBooleanExtra(EXTRA_LOOP, false);
        final List<double[]> points = RoutePayload.get();

        if (points.size() < 2) {
            broadcastError("Draw a route with at least two points first.");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundSafely();
        acquireWakeLock();

        // Build and start the engine on the worker thread so provider registration
        // never blocks the UI thread.
        workerHandler.post(() -> {
            if (engine != null) {
                engine.stop();
            }
            MockLocationEngine newEngine = new MockLocationEngine(
                    getApplicationContext(), points, speed, loop, MockLocationService.this);
            if (!newEngine.start()) {
                // start() already reported the error via onError(); shut down.
                mainHandler.post(MockLocationService.this::stopEverything);
                return;
            }
            engine = newEngine;
            workerHandler.removeCallbacks(tickRunnable);
            workerHandler.post(tickRunnable);
        });

        serviceRunning = true;
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopEngineAndProviders();
        releaseWakeLock();
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        serviceRunning = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- MockLocationEngine.Listener (invoked on worker thread) ---

    @Override
    public void onFix(double latitude, double longitude, float bearing,
                      float speedMps, double progressFraction) {
        Intent i = new Intent(BROADCAST_FIX);
        i.putExtra(KEY_LAT, latitude);
        i.putExtra(KEY_LON, longitude);
        i.putExtra(KEY_BEARING, bearing);
        i.putExtra(KEY_SPEED, speedMps);
        i.putExtra(KEY_FRACTION, progressFraction);
        LocalBroadcastRelay.send(getApplicationContext(), i);
        updateNotification((int) Math.round(progressFraction * 100));
    }

    @Override
    public void onCompleted() {
        LocalBroadcastRelay.send(getApplicationContext(), new Intent(BROADCAST_COMPLETED));
        mainHandler.post(this::stopEverything);
    }

    @Override
    public void onError(@NonNull String message) {
        broadcastError(message);
        mainHandler.post(this::stopEverything);
    }

    // --- internals ---

    private void stopEverything() {
        stopEngineAndProviders();
        releaseWakeLock();
        LocalBroadcastRelay.send(getApplicationContext(), new Intent(BROADCAST_STOPPED));
        serviceRunning = false;
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopEngineAndProviders() {
        if (workerHandler != null) {
            workerHandler.removeCallbacks(tickRunnable);
        }
        final MockLocationEngine e = engine;
        engine = null;
        if (e != null) {
            // Stop on the worker thread to serialize with tick(); fall back to inline.
            if (workerHandler != null && workerThread != null && workerThread.isAlive()) {
                workerHandler.post(e::stop);
            } else {
                e.stop();
            }
        }
    }

    private void broadcastError(@NonNull String message) {
        Log.w(TAG, message);
        Intent i = new Intent(BROADCAST_ERROR);
        i.putExtra(KEY_MESSAGE, message);
        LocalBroadcastRelay.send(getApplicationContext(), i);
    }

    private void startForegroundSafely() {
        Notification notification = buildNotification(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(int progressPercent) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, MockLocationService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text, progressPercent))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .addAction(0, getString(R.string.action_stop), stopPending)
                .setProgress(100, progressPercent, false)
                .build();
    }

    private void updateNotification(int progressPercent) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(progressPercent));
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mockpath:playback");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(2 * 60 * 60 * 1000L /* 2h safety cap */);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    /** Convenience starters. */
    public static void start(@NonNull Context context, float speedMps, boolean loop) {
        Intent i = new Intent(context, MockLocationService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SPEED_MPS, speedMps)
                .putExtra(EXTRA_LOOP, loop);
        context.startForegroundService(i);
    }

    public static void stop(@NonNull Context context) {
        Intent i = new Intent(context, MockLocationService.class).setAction(ACTION_STOP);
        context.startService(i);
    }
}
