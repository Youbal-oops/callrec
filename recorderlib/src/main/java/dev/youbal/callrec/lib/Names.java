package dev.youbal.callrec.lib;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Names {

    private Names() {
    }

    /** {outputDir}/{direction}_{number}_{yyyyMMdd-HHmmss}.m4a */
    static String build(String outputDir, String number, String direction) {
        File dir = new File(outputDir);
        dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return new File(dir, safe(direction) + "_" + safe(number) + "_" + stamp + ".m4a")
                .getAbsolutePath();
    }

    private static String safe(String s) {
        if (s == null || s.isEmpty()) {
            return "unknown";
        }
        String cleaned = s.replaceAll("[^A-Za-z0-9+]", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }
}
