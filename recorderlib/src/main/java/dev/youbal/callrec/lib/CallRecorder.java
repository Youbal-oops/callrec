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

    /** AUTO prefers the Shizuku app, then built-in access ({@link BuiltinAccess}), then the microphone. */
    public enum Mode { AUTO, SHIZUKU, BUILTIN, MIC }

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
    private final BuiltinRecorder builtin;

    private RecorderEngine active;
    private boolean recording;
    private boolean busy;
    private boolean stopRequested;
    private int generation;

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
        this.builtin = new BuiltinRecorder(app);
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
            case BUILTIN:
                return builtin.isReady() ? builtin : null;
            case AUTO:
            default:
                if (shizuku.isReady()) {
                    return shizuku;
                }
                if (builtin.isReady()) {
                    return builtin;
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
            stopRequested = false;
            final int gen = generation;
            new Thread(() -> {
                try {
                    engine.start(outputDir, number, direction);
                    main.post(() -> {
                        if (gen != generation) {
                            stopQuietly(engine);
                            return;
                        }
                        busy = false;
                        recording = true;
                        if (stopRequested) {
                            // The call ended while we were still starting.
                            stopRequested = false;
                            finish();
                        } else {
                            listener.onRecordingStarted();
                        }
                    });
                } catch (Throwable t) {
                    main.post(() -> {
                        if (gen != generation) {
                            return;
                        }
                        busy = false;
                        stopRequested = false;
                        active = null;
                        listener.onError("start failed: " + t);
                    });
                }
            }, "callrec-start").start();
        });
    }

    public void onCallEnded() {
        main.post(() -> {
            if (busy) {
                stopRequested = true;
                return;
            }
            finish();
        });
    }

    /** Main thread only. */
    private void finish() {
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
    }

    private static void stopQuietly(RecorderEngine engine) {
        new Thread(() -> {
            try {
                engine.stop();
            } catch (Throwable ignored) {
            }
        }, "callrec-stop").start();
    }

    public void release() {
        main.post(() -> {
            generation++;
            mic.release();
            shizuku.release();
            builtin.release();
            active = null;
            recording = false;
            busy = false;
            stopRequested = false;
        });
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
