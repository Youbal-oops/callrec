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

A complete, working app that uses it: [**callrec-phone**](https://github.com/Youbal-oops/callrec-phone).

## Add it

```gradle
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.Youbal-oops.callrec:recorderlib:0.2.0' }
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
