package dev.youbal.callrec.lib;

import android.content.Context;
import android.os.Build;

import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AdbMdns;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;

/**
 * Call-audio access with no other app. The phone's own wireless debugging (Android 11+) is paired with once, using the
 * six-digit code it shows; after that this class connects to it, starts the small {@link ShellServer} process as
 * the shell user, and {@link CallRecorder} records through it. Like Shizuku, it has to be started again after a
 * reboot, and wireless debugging has to be on (with Wi-Fi connected) while that happens.
 *
 * <p>Uses only the loopback address of this phone. Needs INTERNET, ACCESS_WIFI_STATE and CHANGE_WIFI_MULTICAST_STATE
 * (for finding the pairing dialog), all declared by the host app.
 */
public final class BuiltinAccess {

    public enum State {
        /** Android 10 or older, or no shared storage to talk through. Use Shizuku or the microphone. */
        UNSUPPORTED,
        /** Needs the one-time pairing code from Developer options → Wireless debugging. */
        NOT_PAIRED,
        /** Paired, but the recorder process is not running (after a reboot, or never started). */
        STOPPED,
        /** Call audio can be recorded. */
        RUNNING
    }

    /** Progress and the final result of {@link #pair} and {@link #start}. Called on a background thread. */
    public interface Listener {
        void onStatus(String message);

        void onDone(boolean ok, String message);
    }

    /** Called when the phone's "pair with code" dialog opens or closes. */
    public interface PairingListener {
        /** The dialog is open and listening on this port. */
        void onFound(int port);

        void onLost();
    }

    private static final String TOKEN_FILE = "callrec_token";
    private static final String PAIRED_FILE = "callrec_paired";
    private static final String LOOPBACK = "127.0.0.1";

    private BuiltinAccess() {
    }

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    public static State state(Context context) {
        if (!supported() || controlDir(context) == null) {
            return State.UNSUPPORTED;
        }
        if (running(context)) {
            return State.RUNNING;
        }
        return paired(context) ? State.STOPPED : State.NOT_PAIRED;
    }

    public static boolean running(Context context) {
        ControlChannel channel = channel(context);
        return channel != null && channel.alive(System.currentTimeMillis(), 4000);
    }

    public static boolean paired(Context context) {
        return new File(context.getFilesDir(), PAIRED_FILE).isFile();
    }

    // ---- pairing -------------------------------------------------------------------------------------------------

    /**
     * Watches for the phone's "Pair device with pairing code" dialog and reports the port it is listening on.
     * Close the result when done.
     */
    public static AutoCloseable discoverPairing(Context context, PairingListener listener) {
        AdbMdns mdns = new AdbMdns(context.getApplicationContext(), AdbMdns.SERVICE_TYPE_TLS_PAIRING,
                (address, port) -> {
                    if (port > 0) {
                        listener.onFound(port);
                    } else {
                        listener.onLost();
                    }
                });
        mdns.start();
        return mdns::stop;
    }

    /** Pairs with the dialog on {@code port} using its code, then starts the recorder process. */
    public static void pair(Context context, int port, String code, Listener listener) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                listener.onStatus("Pairing…");
                boolean ok = AdbManager.get(app).pair(LOOPBACK, port, code.trim());
                if (!ok) {
                    listener.onDone(false, "That code was not accepted. Check it and try again.");
                    return;
                }
                markPaired(app);
                listener.onStatus("Paired. Starting recording access…");
            } catch (Exception e) {
                listener.onDone(false, "Pairing failed: " + describe(e));
                return;
            }
            start(app, listener);
        }, "callrec-pair").start();
    }

    // ---- starting and stopping -----------------------------------------------------------------------------------

    /** Connects to wireless debugging and starts the recorder process. Safe to call when it is already running. */
    public static void start(Context context, Listener listener) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            AdbManager manager = null;
            try {
                if (running(app)) {
                    listener.onDone(true, "Recording access is on.");
                    return;
                }
                if (controlDir(app) == null) {
                    listener.onDone(false, "No shared storage to talk through.");
                    return;
                }
                manager = AdbManager.get(app);
                listener.onStatus("Connecting to wireless debugging…");
                if (!manager.isConnected() && !manager.autoConnect(app, 12_000)) {
                    listener.onDone(false, "Could not reach wireless debugging. Turn it on in Developer options, with Wi-Fi connected, then try again.");
                    return;
                }
                listener.onStatus("Starting the recorder…");
                run(manager, launchCommand(app));
                long end = System.currentTimeMillis() + 10_000;
                while (!running(app) && System.currentTimeMillis() < end) {
                    Thread.sleep(200);
                }
                if (running(app)) {
                    listener.onDone(true, "Recording access is on.");
                } else {
                    listener.onDone(false, "The recorder did not start. Turn wireless debugging off and on, then try again.");
                }
            } catch (AdbPairingRequiredException e) {
                clearPaired(app);
                listener.onDone(false, "This phone no longer trusts the app. Pair again.");
            } catch (Exception e) {
                listener.onDone(false, "Could not start: " + describe(e));
            } finally {
                if (manager != null) {
                    try {
                        manager.disconnect();
                    } catch (Exception ignored) {
                        // Already disconnected.
                    }
                }
            }
        }, "callrec-start").start();
    }

    /** Stops the recorder process. It can be started again with {@link #start}. */
    public static void stop(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            ControlChannel channel = channel(app);
            if (channel != null) {
                try {
                    channel.request("QUIT", 3000);
                } catch (Exception ignored) {
                    // Not running anyway.
                }
            }
        }, "callrec-stop-server").start();
    }

    /** Forgets the pairing and the saved identity. Pairing is needed again. */
    public static void forget(Context context) {
        stop(context);
        AdbManager.forget(context.getApplicationContext());
        clearPaired(context.getApplicationContext());
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    static File controlDir(Context context) {
        File base = context.getExternalFilesDir(null);
        return base == null ? null : new File(base, ".callrec");
    }

    static ControlChannel channel(Context context) {
        File dir = controlDir(context);
        if (dir == null) {
            return null;
        }
        try {
            return new ControlChannel(dir, token(context));
        } catch (IOException e) {
            return null;
        }
    }

    private static synchronized String token(Context context) throws IOException {
        File file = new File(context.getFilesDir(), TOKEN_FILE);
        if (file.isFile()) {
            String saved = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            if (saved.length() >= 32) {
                return saved;
            }
        }
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        Files.write(file.toPath(), hex.toString().getBytes(StandardCharsets.UTF_8));
        return hex.toString();
    }

    private static String launchCommand(Context context) throws IOException {
        String apk = context.getApplicationInfo().sourceDir;
        String dir = controlDir(context).getAbsolutePath();
        String token = token(context);
        // setsid + nohup keep it alive after this wireless-debugging session closes.
        return "CLASSPATH='" + apk + "' nohup setsid app_process /system/bin --nice-name=callrec_server "
                + ShellServer.class.getName() + " '" + dir + "' " + token + " '" + apk + "' </dev/null >/dev/null 2>&1 &";
    }

    /** Runs a shell command and waits for it to finish; the recorder itself keeps running in the background. */
    private static void run(AdbManager manager, String command) throws Exception {
        AdbStream stream = manager.openStream("shell:" + command);
        try {
            InputStream in = stream.openInputStream();
            byte[] buffer = new byte[256];
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end && !stream.isClosed()) {
                if (in.available() > 0) {
                    if (in.read(buffer) < 0) {
                        break;
                    }
                } else {
                    Thread.sleep(50);
                }
            }
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // The shell has already ended it.
            }
        }
    }

    private static void markPaired(Context context) {
        try {
            new File(context.getFilesDir(), PAIRED_FILE).createNewFile();
        } catch (IOException ignored) {
            // Shown as not paired; pairing again fixes it.
        }
    }

    private static void clearPaired(Context context) {
        new File(context.getFilesDir(), PAIRED_FILE).delete();
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
