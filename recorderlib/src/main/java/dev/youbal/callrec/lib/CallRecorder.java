package dev.youbal.callrec.lib;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import rikka.shizuku.Shizuku;

/**
 * Call-recording facade. The host calls {@link #onCallActive} and {@link #onCallEnded}.
 *
 * <p>SHIZUKU captures the real call stream on any audio route but needs Shizuku running.
 * MIC needs only RECORD_AUDIO but hears the far end only on speakerphone.
 * AUTO uses Shizuku when ready, else the mic.
 */
public final class CallRecorder {

    public static final String IN = "in";
    public static final String OUT = "out";
    public static final String UNKNOWN = "call";

    public enum Mode { AUTO, SHIZUKU, MIC }

    public interface Listener {
        void onRecordingStarted();

        void onRecordingSaved(String path, String source, int seconds, int audiblePercent);

        /** A file was produced but is effectively silent; treat as a failed capture. */
        void onRecordingSilent(String path, String source, int seconds, int audiblePercent);

        void onShizukuUnavailable();

        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final String outputDir;
    private final Mode mode;

    private final MicRecorder mic;
    private final ShizukuRecorder shizuku;

    private RecorderEngine active;
    private boolean recording;
    private boolean busy;

    public CallRecorder(Context context, String outputDir, Listener listener) {
        this(context, outputDir, listener, Mode.AUTO);
    }

    public CallRecorder(Context context, String outputDir, Listener listener, Mode mode) {
        Context app = context.getApplicationContext();
        this.outputDir = outputDir;
        this.listener = listener;
        this.mode = mode;
        this.mic = new MicRecorder(app);
        this.shizuku = new ShizukuRecorder(app);
    }

    public boolean isReady() {
        return select() != null;
    }

    public void requestShizukuPermission(int requestCode) {
        try {
            if (Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode);
            }
        } catch (Throwable ignored) {
        }
    }

    private RecorderEngine select() {
        switch (mode) {
            case MIC:
                return mic.isReady() ? mic : null;
            case SHIZUKU:
                return shizuku.isReady() ? shizuku : null;
            case AUTO:
            default:
                if (shizuku.isReady()) {
                    return shizuku;
                }
                return mic.isReady() ? mic : null;
        }
    }

    /** Filename becomes {direction}_{number}_{timestamp}.m4a. */
    public void onCallActive(String number, String direction) {
        main.post(() -> {
            if (recording || busy) {
                return;
            }
            RecorderEngine engine = select();
            if (engine == null) {
                if (mode == Mode.MIC) {
                    listener.onError("RECORD_AUDIO not granted");
                } else {
                    listener.onShizukuUnavailable();
                }
                return;
            }
            active = engine;
            busy = true;
            new Thread(() -> {
                try {
                    engine.start(outputDir, number, direction);
                    recording = true;
                    busy = false;
                    post(listener::onRecordingStarted);
                } catch (Throwable t) {
                    busy = false;
                    active = null;
                    post(() -> listener.onError("start failed: " + t));
                }
            }, "callrec-start").start();
        });
    }

    public void onCallEnded() {
        main.post(() -> {
            if (!recording || active == null) {
                return;
            }
            recording = false;
            final RecorderEngine engine = active;
            active = null;
            new Thread(() -> {
                try {
                    report(engine.stop());
                } catch (Throwable t) {
                    post(() -> listener.onError("stop failed: " + t));
                }
            }, "callrec-stop").start();
        });
    }

    public void release() {
        mic.release();
        shizuku.release();
        active = null;
        recording = false;
        busy = false;
    }

    private void report(RecordResult r) {
        if (r == null) {
            post(() -> listener.onError("null result"));
            return;
        }
        if (r.audiblePercent < 5) {
            post(() -> listener.onRecordingSilent(r.path, r.source, r.seconds, r.audiblePercent));
        } else {
            post(() -> listener.onRecordingSaved(r.path, r.source, r.seconds, r.audiblePercent));
        }
    }

    private void post(Runnable r) {
        main.post(r);
    }
}
