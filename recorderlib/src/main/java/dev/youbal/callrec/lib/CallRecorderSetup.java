package dev.youbal.callrec.lib;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import rikka.shizuku.Shizuku;

/**
 * Tells an app where the user is in setting up Shizuku, so the app can show the right next step
 * instead of a generic "Shizuku not ready". All calls are safe to make on the main thread.
 */
public final class CallRecorderSetup {

    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    public static final String SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/";
    public static final String SHIZUKU_GUIDE = "https://shizuku.rikka.app/guide/setup/";

    private CallRecorderSetup() {
    }

    /** The next thing the user has to do, in order. */
    public enum State {
        /** The Shizuku app is not on the phone. Mic recording still works. */
        NOT_INSTALLED,
        /** Installed but not started: wireless debugging, then Start. Needed again after a reboot. */
        NOT_RUNNING,
        /** Running, but this app has not been authorised in it yet. */
        NEEDS_PERMISSION,
        /** Call audio can be recorded. */
        READY
    }

    public static State state(Context context) {
        try {
            if (Shizuku.pingBinder()) {
                return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                        ? State.READY : State.NEEDS_PERMISSION;
            }
        } catch (Throwable ignored) {
            // Shizuku classes can throw while the binder is dying; treat as not running.
        }
        return isShizukuInstalled(context) ? State.NOT_RUNNING : State.NOT_INSTALLED;
    }

    public static boolean isShizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Asks Shizuku to show its "allow this app" prompt. No-op unless State is NEEDS_PERMISSION. */
    public static void requestPermission(int requestCode) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Opens the Shizuku app, or its download page if it is not installed. */
    public static Intent shizukuIntent(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        return launch != null ? launch : new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD));
    }

    /** Shizuku's own step-by-step guide for starting it over wireless debugging. */
    public static Intent guideIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_GUIDE));
    }
}
