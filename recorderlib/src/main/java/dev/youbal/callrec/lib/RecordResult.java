package dev.youbal.callrec.lib;

final class RecordResult {
    final String path;
    final String source;
    final int seconds;
    final int audiblePercent;

    RecordResult(String path, String source, int seconds, int audiblePercent) {
        this.path = path;
        this.source = source;
        this.seconds = seconds;
        this.audiblePercent = audiblePercent;
    }

    /** Wire form across AIDL: path|source|seconds|audiblePercent. */
    String wire() {
        return path + "|" + source + "|" + seconds + "|" + audiblePercent;
    }

    static RecordResult parse(String s) {
        if (s == null) {
            return null;
        }
        String[] f = s.split("\\|");
        if (f.length < 4) {
            return null;
        }
        return new RecordResult(f[0], f[1], parseInt(f[2]), parseInt(f[3]));
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
