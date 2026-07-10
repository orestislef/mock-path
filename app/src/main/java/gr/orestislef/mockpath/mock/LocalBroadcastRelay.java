package gr.orestislef.mockpath.mock;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal in-process event bus for service -> UI updates. Avoids the deprecated
 * {@code LocalBroadcastManager} while keeping delivery on the main thread.
 */
public final class LocalBroadcastRelay {

    public interface Observer {
        @MainThread
        void onEvent(@NonNull Intent intent);
    }

    private static final CopyOnWriteArrayList<Observer> OBSERVERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LocalBroadcastRelay() {
    }

    public static void register(@NonNull Observer observer) {
        OBSERVERS.addIfAbsent(observer);
    }

    public static void unregister(@NonNull Observer observer) {
        OBSERVERS.remove(observer);
    }

    /** Delivers {@code intent} to all observers on the main thread. */
    public static void send(@NonNull Context context, @NonNull Intent intent) {
        MAIN.post(() -> {
            for (Observer o : OBSERVERS) {
                o.onEvent(intent);
            }
        });
    }
}
