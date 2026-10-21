package dev.youbal.callrec.lib;

import android.content.Context;

/** Records through the {@link ShellServer} that {@link BuiltinAccess} started, so no other app is needed. */
final class BuiltinRecorder implements RecorderEngine {

    private static final long START_TIMEOUT_MS = 20_000, STOP_TIMEOUT_MS = 20_000;

    private final Context context;

    BuiltinRecorder(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean isReady() {
        return BuiltinAccess.running(context);
    }

    @Override
    public void start(String outputDir, String number, String direction) throws Exception {
        ControlChannel channel = BuiltinAccess.channel(context);
        if (channel == null || !BuiltinAccess.running(context)) {
            throw new IllegalStateException("built-in recording access is not running");
        }
        String answer = channel.request("START", START_TIMEOUT_MS, outputDir, number, direction);
        if (!"started".equals(answer)) {
            throw new IllegalStateException(answer);
        }
    }

    @Override
    public RecordResult stop() throws Exception {
        ControlChannel channel = BuiltinAccess.channel(context);
        if (channel == null) {
            throw new IllegalStateException("built-in recording access is not available");
        }
        String answer = channel.request("STOP", STOP_TIMEOUT_MS);
        RecordResult result = RecordResult.parse(answer);
        if (result == null) {
            throw new IllegalStateException(answer.isEmpty() ? "no result" : answer);
        }
        return result;
    }

    @Override
    public void release() {
        // The server keeps running between calls on purpose; it is stopped with BuiltinAccess.stop().
    }
}
