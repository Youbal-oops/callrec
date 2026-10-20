package dev.youbal.callrec.lib;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * Records the device mic in-process. Hears the far end only on speakerphone; headsets bypass the mic.
 */
final class MicRecorder implements RecorderEngine {

    private static final int AUDIBLE = 1500;
    private static final long SAMPLE_MS = 250;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaRecorder recorder;
    private String path;
    private long startedAt;
    private int samples;
    private int audibleSamples;

    MicRecorder(Context context) {
        this.ctx = context.getApplicationContext();
    }

    @Override
    public boolean isReady() {
        return ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void start(String outputDir, String number, String direction) throws Exception {
        if (!isReady()) {
            throw new IllegalStateException("RECORD_AUDIO not granted");
        }
        path = Names.build(outputDir, number, direction);
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioChannels(1);
        recorder.setAudioSamplingRate(16000);
        recorder.setAudioEncodingBitRate(64000);
        recorder.setOutputFile(path);
        recorder.prepare();
        recorder.start();
        startedAt = System.currentTimeMillis();
        samples = 0;
        audibleSamples = 0;
        main.postDelayed(sampler, SAMPLE_MS);
    }

    private final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            MediaRecorder r = recorder;
            if (r == null) {
                return;
            }
            try {
                samples++;
                if (r.getMaxAmplitude() >= AUDIBLE) {
                    audibleSamples++;
                }
            } catch (Throwable ignored) {
            }
            main.postDelayed(this, SAMPLE_MS);
        }
    };

    @Override
    public RecordResult stop() throws Exception {
        main.removeCallbacks(sampler);
        MediaRecorder r = recorder;
        recorder = null;
        if (r == null) {
            throw new IllegalStateException("not recording");
        }
        int seconds = (int) ((System.currentTimeMillis() - startedAt) / 1000);
        try {
            r.stop();
        } catch (RuntimeException tooShort) {
            // MediaRecorder throws if stopped before capturing anything.
            r.release();
            new File(path).delete();
            throw new IllegalStateException("recording too short or no audio captured");
        }
        r.release();
        int percent = samples > 0 ? (audibleSamples * 100 / samples) : 0;
        return new RecordResult(path, "MIC", seconds, percent);
    }

    @Override
    public void release() {
        main.removeCallbacks(sampler);
        MediaRecorder r = recorder;
        recorder = null;
        if (r != null) {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
