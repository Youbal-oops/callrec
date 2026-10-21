package dev.youbal.callrec.lib;

import android.os.Looper;

import java.io.File;

/**
 * The recorder process for built-in access. It is started over this phone's own wireless debugging, so it runs
 * as the shell user, which is what lets it capture call audio. It does the same job as the Shizuku user service
 * (it runs {@link RecorderService}) but is reached through {@link ControlChannel} files instead of binder.
 *
 * <p>Started like this (see {@link BuiltinAccess}):
 * <pre>CLASSPATH=base.apk app_process /system/bin --nice-name=callrec_server dev.youbal.callrec.lib.ShellServer DIR TOKEN APK</pre>
 *
 * <p>It exits by itself when the app is uninstalled, and when it is told QUIT. It does not survive a reboot.
 */
public final class ShellServer {

    private static final long BEAT_MS = 1000, APK_CHECK_MS = 15_000, IDLE_MS = 100;

    private ShellServer() {
    }

    /** args: control folder, token, path of the app's apk. */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("usage: ShellServer <control dir> <token> <apk>");
            return;
        }
        final File dir = new File(args[0]);
        final File apk = new File(args[2]);
        final ControlChannel channel = new ControlChannel(dir, args[1]);
        dir.mkdirs();
        if (channel.alive(System.currentTimeMillis(), 3000)) {
            return; // One is already running.
        }
        // The shell-identity context needs a main looper in this process.
        Looper.prepareMainLooper();

        Thread beat = new Thread(() -> {
            while (true) {
                channel.beat(System.currentTimeMillis());
                sleep(BEAT_MS);
            }
        }, "callrec-beat");
        beat.setDaemon(true);
        beat.start();

        new Thread(() -> serve(channel, apk), "callrec-server").start();
        Looper.loop();
    }

    private static void serve(ControlChannel channel, File apk) {
        RecorderService service = null;
        String creationError = null;
        long lastApkCheck = System.currentTimeMillis();
        try {
            while (true) {
                long now = System.currentTimeMillis();
                if (now - lastApkCheck >= APK_CHECK_MS) {
                    if (!apk.exists()) {
                        break; // Uninstalled or replaced: nothing left to serve.
                    }
                    lastApkCheck = now;
                }
                ControlChannel.Command command = channel.poll();
                if (command == null) {
                    sleep(IDLE_MS);
                    continue;
                }
                if ("QUIT".equals(command.verb)) {
                    channel.respond(command.id, "bye");
                    break;
                }
                if ("PING".equals(command.verb)) {
                    channel.respond(command.id, "pong");
                    continue;
                }
                if (service == null && creationError == null) {
                    try {
                        service = new RecorderService();
                    } catch (Throwable t) {
                        creationError = "error: could not set up the shell identity: " + t;
                    }
                }
                if (service == null) {
                    channel.respond(command.id, creationError);
                    continue;
                }
                String answer;
                try {
                    switch (command.verb) {
                        case "START":
                            answer = service.start(command.arg(0), command.arg(1), command.arg(2));
                            break;
                        case "STOP":
                            answer = service.stop();
                            break;
                        default:
                            answer = "error: unknown command " + command.verb;
                    }
                } catch (Throwable t) {
                    answer = "error: " + t;
                }
                channel.respond(command.id, answer);
            }
        } finally {
            channel.clearAlive();
            System.exit(0);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // Keep serving.
        }
    }
}
