package dev.youbal.callrec.lib;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/** Client for {@link RecorderService}, which runs in the Shizuku process under the shell identity. */
final class ShizukuRecorder implements RecorderEngine {

    private static final long BIND_TIMEOUT_MS = 10_000;

    private final Shizuku.UserServiceArgs args;

    private volatile IRecorderService service;
    private CountDownLatch bindLatch;

    ShizukuRecorder(Context context) {
        String pkg = context.getApplicationContext().getPackageName();
        this.args = new Shizuku.UserServiceArgs(new ComponentName(pkg, RecorderService.class.getName()))
                .daemon(false)
                .processNameSuffix("callrec")
                .version(1);
    }

    @Override
    public boolean isReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void start(String outputDir, String number, String direction) throws Exception {
        if (!isReady()) {
            throw new IllegalStateException("Shizuku not running or not authorised");
        }
        String r = ensureBound().start(outputDir, number, direction);
        if (!"started".equals(r)) {
            throw new IllegalStateException(r == null ? "null start result" : r);
        }
    }

    @Override
    public RecordResult stop() throws Exception {
        IRecorderService svc = service;
        if (svc == null) {
            throw new IllegalStateException("recorder not bound");
        }
        String r = svc.stop();
        RecordResult result = RecordResult.parse(r);
        if (result == null) {
            throw new IllegalStateException(r == null ? "null stop result" : r);
        }
        return result;
    }

    @Override
    public void release() {
        try {
            Shizuku.unbindUserService(args, connection, true);
        } catch (Throwable ignored) {
        }
        service = null;
    }

    private IRecorderService ensureBound() throws InterruptedException {
        IRecorderService svc = service;
        if (svc != null) {
            return svc;
        }
        bindLatch = new CountDownLatch(1);
        Shizuku.bindUserService(args, connection);
        if (!bindLatch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) || service == null) {
            throw new IllegalStateException("timed out binding the Shizuku recorder service");
        }
        return service;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IRecorderService.Stub.asInterface(binder);
            if (bindLatch != null) {
                bindLatch.countDown();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };
}
