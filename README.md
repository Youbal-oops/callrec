# callrec

Android library that lets a call app record calls. Your app reports when a call starts and ends;
the library records the audio to an `.m4a`.

```java
CallRecorder recorder = new CallRecorder(context, "/sdcard/Recordings/CallRec", listener);
recorder.onCallActive("+911234567890", CallRecorder.OUT); // call connected
recorder.onCallEnded();                                    // call ended
recorder.release();                                        // onDestroy
```

Output: `{in|out|call}_{number}_{yyyyMMdd-HHmmss}.m4a`

## Shizuku

To capture the other person's voice on any audio route (earpiece, speaker, headset), recording runs
through [Shizuku](https://github.com/RikkaApps/Shizuku-API). Shizuku must be installed and started
on each phone (wireless debugging, Android 11+), and your app must be authorised in it. It can't be
embedded; Android doesn't let an app grant itself that access.

Without Shizuku, use `CallRecorder.Mode.MIC`: it needs only `RECORD_AUDIO` but hears the other side
only on speakerphone. The default `Mode.AUTO` uses Shizuku when available, otherwise the mic.

Not yet verified on a handset. Check the local laws on call recording before use.
