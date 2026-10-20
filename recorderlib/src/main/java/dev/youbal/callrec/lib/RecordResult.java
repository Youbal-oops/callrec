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

    /**
     * Parses from the right so a '|' inside the path survives. Returns null on anything malformed,
     * including "error: ..." strings and non-numeric fields.
     */
    static RecordResult parse(String s) {
        if (s == null) {
            return null;
        }
        int c = s.lastIndexOf('|');
        int b = c > 0 ? s.lastIndexOf('|', c - 1) : -1;
        int a = b > 0 ? s.lastIndexOf('|', b - 1) : -1;
        if (a <= 0) {
            return null;
        }
        try {
            return new RecordResult(
                    s.substring(0, a),
                    s.substring(a + 1, b),
                    Integer.parseInt(s.substring(b + 1, c).trim()),
                    Integer.parseInt(s.substring(c + 1).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
