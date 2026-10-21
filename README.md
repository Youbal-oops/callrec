# callrec

Android library that lets a call app record calls. Your app reports when a call starts and ends;
the library records the audio to an `.m4a`, and helps you list, play and clean up what it recorded.

```java
CallRecorder recorder = new CallRecorder(context, outputDir, listener);
recorder.onCallActive("+911234567890", CallRecorder.OUT); // call connected
recorder.onCallEnded();                                    // call ended
recorder.release();                                        // onDestroy
```

Output: `{in|out|call}_{number}_{yyyyMMdd-HHmmss}.m4a`

A complete, working app that uses it: [**Phone**](https://github.com/Youbal-oops/Phone).

## Add it

```gradle
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.Youbal-oops:callrec:0.3.0' }
```

`outputDir` should be the app's own folder on shared storage, `getExternalFilesDir("Recordings")`. The
Shizuku engine runs as the shell user, which can write there, and other apps cannot read it.

## Know where the user is in setup

`CallRecorderSetup` answers "what should I tell the user to do next?", so your setup screen can show the
right step instead of a generic "Shizuku not ready".

```java
switch (CallRecorderSetup.state(context)) {
    case NOT_INSTALLED:    // offer CallRecorderSetup.shizukuIntent(context); mic mode still works
    case NOT_RUNNING:      // wireless debugging, then Start in Shizuku (again after every reboot)
    case NEEDS_PERMISSION: // CallRecorderSetup.requestPermission(requestCode)
    case READY:            // call audio can be recorded
}
```

Also: `isShizukuInstalled`, `shizukuIntent` (opens Shizuku, or its download page), `guideIntent` (Shizuku's own setup guide).

## List, find and clean up recordings

`RecordingStore` reads the folder you record into. It only ever touches files named by this library, so it
is safe to point at a shared folder.

```java
RecordingStore store = new RecordingStore(outputDir);
for (RecordingInfo r : store.list()) {   // newest first
    r.direction; r.number; r.startedAt; r.sizeBytes; r.file;
}
store.totalBytes();
store.delete(recording);
store.deleteOlderThan(30, System.currentTimeMillis()); // days <= 0 keeps everything
```

## Built-in access: no other app

`BuiltinAccess` records call audio without the Shizuku app. It pairs once with the phone's own wireless debugging
(Android 11+) using the six-digit code the phone shows, then starts a small recorder process as the shell user.
`CallRecorder` talks to that process through a few files in your app's own folder.

```java
BuiltinAccess.state(context);   // UNSUPPORTED, NOT_PAIRED, STOPPED, RUNNING
BuiltinAccess.discoverPairing(context, listener);       // reports the port of the "pair with code" dialog
BuiltinAccess.pair(context, port, "123456", listener);  // pairs, then starts the recorder
BuiltinAccess.start(context, listener);                 // after a restart (wireless debugging must be on)
BuiltinAccess.stop(context);   BuiltinAccess.forget(context);
```

`CallRecorder.Mode.AUTO` prefers the Shizuku app, then built-in access, then the microphone. `Mode.BUILTIN` uses only
built-in access. The library declares `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and
`CHANGE_WIFI_MULTICAST_STATE`: Android requires `INTERNET` for any socket, even to the phone itself, and the code only
talks to `127.0.0.1` and discovers the local pairing dialog. It also brings libadb-android (Apache-2.0 option),
spake2-android (LGPL-3.0, used unmodified), Conscrypt (Apache-2.0) and Bouncy Castle (MIT).

Like Shizuku, the recorder process does not survive a restart. A host app can show a "start again" button using `state()`.
`ShellServer` is the process's entry point; the library starts it for you.

## Shizuku

To capture the other person's voice on any audio route (earpiece, speaker, headset), recording runs
through [Shizuku](https://github.com/RikkaApps/Shizuku-API). Shizuku must be installed and started
on each phone (wireless debugging, Android 11+), and your app must be authorised in it. It can't be
embedded; Android doesn't let an app grant itself that access.

Without Shizuku, use `CallRecorder.Mode.MIC`: it needs only `RECORD_AUDIO` but hears the other side
only on speakerphone. The default `Mode.AUTO` uses Shizuku when available, otherwise the mic.

Not yet verified on a handset.

Recording calls may require the consent of all parties. You are responsible for complying with applicable law.

## License

MIT. Uses [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) (MIT, Copyright (c) RikkaApps).
