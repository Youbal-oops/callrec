package dev.youbal.callrec.lib;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * How the app talks to the recorder process it started as the shell user: a few small files in a folder
 * both can reach. No sockets, no binder, no hidden APIs. One request is in flight at a time.
 *
 * <pre>
 * cmd    token / id / verb / args...   written by the app, deleted by the server when it takes it
 * res    id / answer...                written by the server
 * alive  a time in millis              rewritten by the server every second
 * </pre>
 *
 * The token is a secret kept in the app's private storage and handed to the server on its command line, so
 * another app that can see this folder still cannot give it orders.
 */
final class ControlChannel {

    /** One request as the server sees it. */
    static final class Command {
        final String id, verb;
        final List<String> args;
        Command(String id, String verb, List<String> args) { this.id = id; this.verb = verb; this.args = args; }
        String arg(int i) { return i < args.size() ? args.get(i) : ""; }
    }

    private static final String CMD = "cmd", RES = "res", ALIVE = "alive";

    private final File dir;
    private final String token;

    ControlChannel(File dir, String token) {
        this.dir = dir;
        this.token = token;
    }

    // ---- app side ------------------------------------------------------------------------------------------------

    /** Sends one request and waits for its answer. Throws if the server does not answer in time. */
    synchronized String request(String verb, long timeoutMs, String... args) throws IOException, InterruptedException {
        dir.mkdirs();
        String id = Long.toString(System.nanoTime(), 36);
        List<String> lines = new ArrayList<>();
        lines.add(token); lines.add(id); lines.add(verb);
        for (String a : args) lines.add(clean(a));
        File res = new File(dir, RES);
        res.delete();
        write(new File(dir, CMD), lines);
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            List<String> answer = read(res);
            if (answer != null && !answer.isEmpty() && answer.get(0).equals(id)) {
                res.delete();
                return String.join("\n", answer.subList(1, answer.size()));
            }
            Thread.sleep(40);
        }
        new File(dir, CMD).delete();
        throw new IOException("the recorder process did not answer");
    }

    /** True when the server has written its heartbeat within the last maxAgeMs. */
    boolean alive(long now, long maxAgeMs) {
        List<String> lines = read(new File(dir, ALIVE));
        if (lines == null || lines.isEmpty()) return false;
        try { long beat = Long.parseLong(lines.get(0).trim()); return now - beat <= maxAgeMs && beat <= now + 60_000; }
        catch (NumberFormatException e) { return false; }
    }

    // ---- server side ---------------------------------------------------------------------------------------------

    /** The waiting request, or null. A request with the wrong token is deleted and ignored. */
    Command poll() {
        File file = new File(dir, CMD);
        List<String> lines = read(file);
        if (lines == null) return null;
        file.delete();
        if (lines.size() < 3 || !lines.get(0).equals(token)) return null;
        return new Command(lines.get(1), lines.get(2), new ArrayList<>(lines.subList(3, lines.size())));
    }

    void respond(String id, String answer) {
        List<String> lines = new ArrayList<>();
        lines.add(id);
        for (String line : (answer == null ? "" : answer).split("\n", -1)) lines.add(line);
        try { write(new File(dir, RES), lines); } catch (IOException ignored) { /* The app will time out and report it. */ }
    }

    void beat(long now) {
        List<String> lines = new ArrayList<>(); lines.add(Long.toString(now));
        try { write(new File(dir, ALIVE), lines); } catch (IOException ignored) { /* Shown as not running. */ }
    }

    void clearAlive() { new File(dir, ALIVE).delete(); }

    // ---- files ---------------------------------------------------------------------------------------------------

    private static String clean(String value) { return value == null ? "" : value.replace('\r', ' ').replace('\n', ' '); }

    /** Written beside and renamed into place, so a reader never sees half a file. */
    private static void write(File target, List<String> lines) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        Files.write(temp.toPath(), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        if (!temp.renameTo(target)) {
            target.delete();
            if (!temp.renameTo(target)) throw new IOException("could not write " + target.getName());
        }
    }

    private static List<String> read(File file) {
        try {
            if (!file.isFile()) return null;
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n", -1)) lines.add(line);
            return lines;
        } catch (IOException e) {
            return null;
        }
    }
}
