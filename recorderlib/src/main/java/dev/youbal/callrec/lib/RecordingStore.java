package dev.youbal.callrec.lib;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Looks after the folder the recorder writes to: list what is there, delete one, and clear out old ones.
 * Only files named by this library are ever touched, so pointing it at a shared folder is safe.
 */
public final class RecordingStore {

    private final File dir;

    public RecordingStore(File dir) {
        this.dir = dir;
    }

    /** Newest first. Empty when the folder does not exist yet. */
    public List<RecordingInfo> list() {
        File[] files = dir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<RecordingInfo> found = new ArrayList<>();
        for (File f : files) {
            RecordingInfo info = f.isFile() ? RecordingInfo.parse(f) : null;
            if (info != null) {
                found.add(info);
            }
        }
        Collections.sort(found, (a, b) -> Long.compare(b.startedAt, a.startedAt));
        return found;
    }

    public long totalBytes() {
        long total = 0;
        for (RecordingInfo r : list()) {
            total += r.sizeBytes;
        }
        return total;
    }

    public boolean delete(RecordingInfo recording) {
        return RecordingInfo.parse(recording.file) != null && recording.file.delete();
    }

    /**
     * Deletes recordings whose call started more than {@code days} days before {@code now}.
     * Zero or negative days means keep everything. Returns how many were deleted.
     */
    public int deleteOlderThan(int days, long now) {
        if (days <= 0) {
            return 0;
        }
        long cutoff = now - days * 24L * 60 * 60 * 1000;
        int deleted = 0;
        for (RecordingInfo r : list()) {
            if (r.startedAt < cutoff && r.file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }
}
