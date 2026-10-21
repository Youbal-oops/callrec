package dev.youbal.callrec.lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

public class RecordingStoreTest {

    private static File dir() throws IOException {
        return Files.createTempDirectory("callrec").toFile();
    }

    private static File make(File dir, String name, int bytes) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), new byte[bytes]);
        return f;
    }

    @Test
    public void parsesTheLibrarysOwnNames() {
        RecordingInfo r = RecordingInfo.parse(new File("out_+911234567890_20261021-215900.m4a"));
        assertNotNull(r);
        assertEquals("out", r.direction);
        assertEquals("+911234567890", r.number);
        assertNull(RecordingInfo.parse(new File("holiday.m4a")));
        assertNull(RecordingInfo.parse(new File("in_123_20261301-000000.txt")));
    }

    @Test
    public void listsNewestFirstAndIgnoresOtherFiles() throws IOException {
        File d = dir();
        make(d, "in_111_20260101-100000.m4a", 10);
        make(d, "out_222_20260301-100000.m4a", 20);
        make(d, "notes.txt", 5);
        List<RecordingInfo> all = new RecordingStore(d).list();
        assertEquals(2, all.size());
        assertEquals("222", all.get(0).number);
        assertEquals(30, new RecordingStore(d).totalBytes());
    }

    @Test
    public void deletesOldOnesButNeverForeignFiles() throws IOException {
        File d = dir();
        File old = make(d, "in_111_20200101-100000.m4a", 1);
        File fresh = make(d, "in_111_20990101-100000.m4a", 1);
        File foreign = make(d, "old-photo-20200101.jpg", 1);
        RecordingStore store = new RecordingStore(d);
        assertEquals(0, store.deleteOlderThan(0, System.currentTimeMillis()));
        assertEquals(1, store.deleteOlderThan(30, System.currentTimeMillis()));
        assertFalse(old.exists());
        assertTrue(fresh.exists());
        assertTrue(foreign.exists());
        assertFalse(store.delete(RecordingInfo.parse(foreign) == null ? new RecordingInfo(foreign, "call", "x", 0, 1) : null));
        assertTrue(foreign.exists());
    }

    @Test
    public void missingFolderIsJustEmpty() throws IOException {
        File gone = new File(dir(), "nope");
        assertTrue(new RecordingStore(gone).list().isEmpty());
    }
}
