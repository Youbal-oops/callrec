package dev.youbal.callrec.lib;

interface IRecorderService {
    /** Returns "started" or "error: ...". */
    String start(String outputDir, String number, String direction);

    /** Returns "path|source|seconds|audiblePercent" or "error: ...". */
    String stop();
}
