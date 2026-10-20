package dev.youbal.callrec.lib;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs in the Shizuku process under the shell identity. Tries audio sources in order, drops one
 * that opens but stays silent, and encodes the winner to 16 kHz mono AAC.
 */
public final class RecorderService extends IRecorderService.Stub {

    private static final int SAMPLE_RATE = 16000;
    private static final int BIT_RATE = 64000;
    private static final String MIME = MediaFormat.MIMETYPE_AUDIO_AAC;

    private static final int[] SOURCES = {
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
    };
    private static final String[] SOURCE_NAMES = {
            "VOICE_CALL", "VOICE_COMMUNICATION", "VOICE_RECOGNITION", "MIC"
    };

    private static final int AUDIBLE = 500;
    private static final long PROBE_MS = 3000;

    public RecorderService(Context context) {
    }

    public RecorderService() {
    }

    private final Context shell = new ShellContext();

    private volatile boolean stopFlag;
    private Thread worker;
    private volatile String result;
    private volatile String startError;
    private CountDownLatch startLatch;

    @Override
    public String start(String outputDir, String number, String direction) {
        if (worker != null) {
            return "error: already recording";
        }
        final String path = Names.build(outputDir, number, direction);
        stopFlag = false;
        result = null;
        startError = null;
        startLatch = new CountDownLatch(1);
        worker = new Thread(() -> capture(path), "callrec-capture");
        worker.start();
        try {
            startLatch.await(PROBE_MS + 4000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        if (startError != null) {
            worker = null;
            return "error: " + startError;
        }
        return "started";
    }

    @Override
    public String stop() {
        stopFlag = true;
        Thread t = worker;
        if (t != null) {
            try {
                t.join(10_000);
            } catch (InterruptedException ignored) {
            }
        }
        worker = null;
        String r = result;
        return r != null ? r : (startError != null ? "error: " + startError : "error: no result");
    }

    public void destroy() {
        stopFlag = true;
    }

    private void capture(String path) {
        AudioRecord record = null;
        String sourceName = null;
        try {
            int chosen = -1;
            for (int i = 0; i < SOURCES.length; i++) {
                AudioRecord candidate = open(SOURCES[i]);
                if (candidate == null) {
                    continue;
                }
                if (i == SOURCES.length - 1 || probeAudible(candidate)) {
                    record = candidate;
                    chosen = i;
                    break;
                }
                safeRelease(candidate);
            }
            if (record == null) {
                startError = "no audio source could be opened";
                startLatch.countDown();
                return;
            }
            sourceName = SOURCE_NAMES[chosen];
            encode(record, path, sourceName);
        } catch (Throwable t) {
            if (result == null) {
                startError = String.valueOf(t);
            }
            if (startLatch.getCount() > 0) {
                startLatch.countDown();
            }
        } finally {
            safeRelease(record);
        }
    }

    private boolean probeAudible(AudioRecord record) {
        short[] buf = new short[1024];
        long until = System.currentTimeMillis() + PROBE_MS;
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.startRecording();
        }
        while (System.currentTimeMillis() < until && !stopFlag) {
            int n = record.read(buf, 0, buf.length);
            if (n > 0 && maxAmp(buf, n) >= AUDIBLE) {
                return true;
            }
        }
        return false;
    }

    private void encode(AudioRecord record, String path, String sourceName) throws Exception {
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.startRecording();
        }

        MediaFormat fmt = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);

        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        MediaMuxer muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = -1;
        boolean muxing = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] pcm = new short[1024];
        long totalSamples = 0;
        long audibleSamples = 0;
        boolean inputDone = false;

        startLatch.countDown();

        try {
            while (true) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        int samples = stopFlag ? -1 : record.read(pcm, 0, pcm.length);
                        if (samples <= 0 || stopFlag) {
                            codec.queueInputBuffer(inIndex, 0, 0,
                                    presentationUs(totalSamples),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            in.clear();
                            for (int i = 0; i < samples; i++) {
                                in.putShort(pcm[i]);
                            }
                            codec.queueInputBuffer(inIndex, 0, samples * 2,
                                    presentationUs(totalSamples), 0);
                            if (maxAmp(pcm, samples) >= AUDIBLE) {
                                audibleSamples += samples;
                            }
                            totalSamples += samples;
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxing = true;
                } else if (outIndex >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIndex);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && muxing) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        muxer.writeSampleData(track, out, info);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            codec.release();
            try {
                if (muxing) {
                    muxer.stop();
                }
            } catch (Throwable ignored) {
            }
            muxer.release();
        }

        int seconds = (int) (totalSamples / SAMPLE_RATE);
        int percent = totalSamples > 0 ? (int) (audibleSamples * 100 / totalSamples) : 0;
        result = new RecordResult(path, sourceName, seconds, percent).wire();
    }

    private static long presentationUs(long samples) {
        return samples * 1_000_000L / SAMPLE_RATE;
    }

    private AudioRecord open(int source) {
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                min = 4096;
            }
            AudioRecord.Builder b = new AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(min * 2);
            setBuilderContext(b);
            AudioRecord ar = b.build();
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                ar.release();
                return null;
            }
            return ar;
        } catch (Throwable t) {
            return null;
        }
    }

    /** setContext is a hidden API. */
    private void setBuilderContext(AudioRecord.Builder b) {
        try {
            Method m = AudioRecord.Builder.class.getMethod("setContext", Context.class);
            m.invoke(b, shell);
        } catch (Throwable ignored) {
        }
    }

    private static int maxAmp(short[] buf, int n) {
        int max = 0;
        for (int i = 0; i < n; i++) {
            int a = Math.abs(buf[i]);
            if (a > max) {
                max = a;
            }
        }
        return max;
    }

    private static void safeRelease(AudioRecord ar) {
        if (ar != null) {
            try {
                if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    ar.stop();
                }
            } catch (Throwable ignored) {
            }
            try {
                ar.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
