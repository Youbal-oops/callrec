package dev.youbal.callrec.lib;

interface IRecorderService {
    /** Shizuku calls this when the user service is torn down; transaction id is fixed by Shizuku. */
    void destroy() = 16777114;

    /** Returns "started" or "error: ...". */
    String start(String outputDir, String number, String direction) = 1;

    /** Returns "path|source|seconds|audiblePercent" or "error: ...". */
    String stop() = 2;
}
