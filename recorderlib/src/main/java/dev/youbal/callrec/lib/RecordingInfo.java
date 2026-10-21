package dev.youbal.callrec.lib;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One recording on disk, described by the file name the library gives it: {in|out|call}_{number}_{yyyyMMdd-HHmmss}.m4a */
public final class RecordingInfo {

    private static final Pattern NAME = Pattern.compile("^(in|out|call)_([A-Za-z0-9+]+)_(\\d{8}-\\d{6})\\.m4a$");

    public final File file;
    /** {@link CallRecorder#IN}, {@link CallRecorder#OUT} or {@link CallRecorder#UNKNOWN}. */
    public final String direction;
    /** Digits and a leading + as given by the host app, or "unknown". */
    public final String number;
    /** Call start as recorded in the name, in epoch millis. */
    public final long startedAt;
    public final long sizeBytes;

    RecordingInfo(File file, String direction, String number, long startedAt, long sizeBytes) {
        this.file = file;
        this.direction = direction;
        this.number = number;
        this.startedAt = startedAt;
        this.sizeBytes = sizeBytes;
    }

    /** Null when the file is not one of ours (other names are left alone by {@link RecordingStore}). */
    public static RecordingInfo parse(File file) {
        Matcher m = NAME.matcher(file.getName());
        if (!m.matches()) {
            return null;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
            format.setLenient(false);
            long when = format.parse(m.group(3)).getTime();
            return new RecordingInfo(file, m.group(1), m.group(2), when, file.length());
        } catch (ParseException e) {
            return null;
        }
    }
}
