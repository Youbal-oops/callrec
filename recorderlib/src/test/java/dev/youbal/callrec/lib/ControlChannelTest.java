package dev.youbal.callrec.lib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Test;

public class ControlChannelTest {

    private static File dir() throws IOException {
        return Files.createTempDirectory("callrec-ctl").toFile();
    }

    /** A stand-in for the recorder process: answers one request in a second thread. */
    private static Thread server(ControlChannel channel, String answer, int[] seen) {
        Thread t = new Thread(() -> {
            long end = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < end) {
                ControlChannel.Command c = channel.poll();
                if (c != null) { seen[0]++; channel.respond(c.id, c.verb + ":" + c.arg(0) + ":" + c.arg(1) + (answer == null ? "" : answer)); return; }
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        });
        t.start();
        return t;
    }

    @Test
    public void requestGetsTheServersAnswer() throws Exception {
        File d = dir();
        ControlChannel app = new ControlChannel(d, "secret");
        ControlChannel shell = new ControlChannel(d, "secret");
        int[] seen = {0};
        Thread t = server(shell, "", seen);
        assertEquals("START:/sdcard/x:+911234", app.request("START", 3000, "/sdcard/x", "+911234", "out"));
        t.join();
        assertEquals(1, seen[0]);
    }

    @Test
    public void multiLineAnswersSurvive() throws Exception {
        File d = dir();
        ControlChannel app = new ControlChannel(d, "secret");
        ControlChannel shell = new ControlChannel(d, "secret");
        Thread t = server(shell, "\nsecond line", new int[1]);
        assertEquals("STOP::\nsecond line", app.request("STOP", 3000));
        t.join();
    }

    @Test
    public void aWrongTokenIsIgnored() throws Exception {
        File d = dir();
        ControlChannel stranger = new ControlChannel(d, "guess");
        ControlChannel shell = new ControlChannel(d, "secret");
        try { stranger.request("START", 200, "a", "b", "c"); fail("nobody should answer"); } catch (IOException expected) { /* timed out */ }
        assertNull(shell.poll());
        // And a real request after a stranger's still works.
        ControlChannel app = new ControlChannel(d, "secret");
        int[] seen = {0};
        Thread t = server(shell, "", seen);
        assertEquals("PING::", app.request("PING", 3000));
        t.join();
        assertEquals(1, seen[0]);
    }

    @Test
    public void newlinesCannotSmuggleExtraArguments() throws Exception {
        File d = dir();
        ControlChannel app = new ControlChannel(d, "secret");
        ControlChannel shell = new ControlChannel(d, "secret");
        final String[] got = new String[1];
        Thread t = new Thread(() -> {
            long end = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < end) {
                ControlChannel.Command c = shell.poll();
                if (c != null) { got[0] = c.args.size() + "|" + c.arg(0); shell.respond(c.id, "ok"); return; }
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        });
        t.start();
        app.request("START", 3000, "dir\nSTOP\nevil");
        t.join();
        assertEquals("1|dir STOP evil", got[0]);
    }

    @Test
    public void heartbeatIsFreshThenStale() throws Exception {
        File d = dir();
        ControlChannel shell = new ControlChannel(d, "secret");
        ControlChannel app = new ControlChannel(d, "secret");
        assertFalse(app.alive(1_000_000, 3000));
        shell.beat(1_000_000);
        assertTrue(app.alive(1_002_000, 3000));
        assertFalse(app.alive(1_010_000, 3000));
        shell.clearAlive();
        assertFalse(app.alive(1_000_500, 3000));
    }
}
