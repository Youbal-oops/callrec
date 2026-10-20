package dev.youbal.callrec.lib;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Process;

/**
 * Context that reports itself as com.android.shell so AudioRecord's app-op checks pass under the
 * shell UID. Uses hidden ActivityThread APIs via reflection; may break on future Android versions.
 */
final class ShellContext extends ContextWrapper {

    static final String SHELL_PACKAGE = "com.android.shell";

    ShellContext() {
        super(systemContext());
    }

    private static Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread;
            try {
                thread = at.getMethod("currentActivityThread").invoke(null);
                if (thread == null) {
                    thread = at.getMethod("systemMain").invoke(null);
                }
            } catch (Throwable t) {
                thread = at.getMethod("systemMain").invoke(null);
            }
            return (Context) at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            throw new RuntimeException("cannot obtain system context for shell identity", t);
        }
    }

    @Override
    public String getPackageName() {
        return SHELL_PACKAGE;
    }

    @Override
    public String getOpPackageName() {
        return SHELL_PACKAGE;
    }

    @Override
    public AttributionSource getAttributionSource() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new AttributionSource.Builder(Process.myUid())
                    .setPackageName(SHELL_PACKAGE)
                    .build();
        }
        return super.getAttributionSource();
    }
}
