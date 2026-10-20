package dev.youbal.callrec.lib;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs in the Shizuku process under the shell identity. Tries audio sources in order, drops one
 * that opens but stays silent, and encodes the winner to 16 kHz mono AAC. Audio read while probing
 * a source is kept and encoded, so the opening words are not lost.
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

    /** Peak amplitude counted as audible in the reported percentage. */
    private static final int AUDIBLE = 500;
    /** Much lower bar for accepting a source, so quiet speech does not reject a working source. */
    private static final int PROBE_AUDIBLE = 50;
    private static final long PROBE_MS = 1500;
    /** Worst case: three probes plus encoder setup. */
    private static final long START_TIMEOUT_MS = 3 * PROBE_MS + 4000;
    private static final long DRAIN_MS = 3000;
    private static final long JOIN_MS = 5000;

    public RecorderService() {
    }

    private final Context shell = new ShellContext();

    private volatile boolean stopFlag;
    private volatile AudioRecord current;
    private volatile String result;
    private volatile String startError;
    private Thread worker;
    private CountDownLatch startLatch;

    @Override
    public synchronized String start(String outputDir, String number, String direction) {
        if (worker != null) {
            if (worker.isAlive()) {
                return "error: already recording";
            }
            worker = null;
        }
        final String path = Names.build(outputDir, number, direction);
        stopFlag = false;
        result = null;
        startError = null;
        final CountDownLatch latch = new CountDownLatch(1);
        startLatch = latch;
        final Thread t = new Thread(() -> capture(path, latch), "callrec-capture");
        worker = t;
        t.start();

        boolean ready;
        try {
            ready = latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            ready = false;
        }
        if (startError != null) {
            joinQuietly(t, JOIN_MS);
            worker = t.isAlive() ? t : null;
            return "error: " + startError;
        }
        if (!ready) {
            stopFlag = true;
            interruptRead();
            joinQuietly(t, JOIN_MS);
            worker = t.isAlive() ? t : null;
            return "error: timed out starting capture";
        }
        return "started";
    }

    @Override
    public synchronized String stop() {
        stopFlag = true;
        Thread t = worker;
        if (t != null) {
            joinQuietly(t, JOIN_MS);
            if (t.isAlive()) {
                // A blocked read only returns once the record is stopped.
                interruptRead();
                joinQuietly(t, JOIN_MS);
            }
            if (t.isAlive()) {
                return "error: capture thread did not stop";
            }
            worker = null;
        }
        String r = result;
        return r != null ? r : (startError != null ? "error: " + startError : "error: no result");
    }

    /** Called by Shizuku when the user service is torn down. */
    @Override
    public void destroy() {
        stopFlag = true;
        interruptRead();
        Thread t = worker;
        if (t != null) {
            joinQuietly(t, JOIN_MS);
        }
        System.exit(0);
    }

    private static void joinQuietly(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void interruptRead() {
        AudioRecord ar = current;
        if (ar != null) {
            try {
                ar.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    private void capture(String path, CountDownLatch latch) {
        AudioRecord record = null;
        try {
            List<short[]> preroll = new ArrayList<>();
            int chosen = -1;
            for (int i = 0; i < SOURCES.length && !stopFlag; i++) {
                AudioRecord candidate = open(SOURCES[i]);
                if (candidate == null) {
                    continue;
                }
                current = candidate;
                preroll.clear();
                if (i == SOURCES.length - 1 || probeAudible(candidate, preroll)) {
                    record = candidate;
                    chosen = i;
                    break;
                }
                current = null;
                safeRelease(candidate);
            }
            if (record == null) {
                startError = stopFlag ? "stopped before capture started"
                        : "no audio source could be opened";
                return;
            }
            encode(record, path, SOURCE_NAMES[chosen], preroll, latch);
        } catch (Throwable t) {
            if (result == null && startError == null) {
                startError = String.valueOf(t);
            }
        } finally {
            current = null;
            safeRelease(record);
            latch.countDown();
        }
    }

    /** Reads for up to PROBE_MS, keeping every chunk; true as soon as the source is audible. */
    private boolean probeAudible(AudioRecord record, List<short[]> keep) {
        short[] buf = new short[1024];
        long until = System.currentTimeMillis() + PROBE_MS;
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.startRecording();
        }
        while (System.currentTimeMillis() < until && !stopFlag) {
            int n = record.read(buf, 0, buf.length);
            if (n > 0) {
                keep.add(java.util.Arrays.copyOf(buf, n));
                if (maxAmp(buf, n) >= PROBE_AUDIBLE) {
                    return true;
                }
            }
        }
        return false;
    }

    private void encode(AudioRecord record, String path, String sourceName,
                        List<short[]> preroll, CountDownLatch startedLatch) throws Exception {
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.startRecording();
        }

        MediaCodec codec = null;
        MediaMuxer muxer = null;
        int track = -1;
        boolean muxing = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] pcm = new short[1024];
        int prerollIndex = 0;
        long totalSamples = 0;
        long audibleSamples = 0;
        boolean inputDone = false;
        long drainDeadline = 0;

        try {
            MediaFormat fmt = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);

            codec = MediaCodec.createEncoderByType(MIME);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            startedLatch.countDown();

            while (true) {
                if (inputDone && System.currentTimeMillis() > drainDeadline) {
                    throw new IllegalStateException("encoder did not finish draining");
                }
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        int samples;
                        if (stopFlag) {
                            samples = -1;
                        } else if (prerollIndex < preroll.size()) {
                            short[] chunk = preroll.get(prerollIndex++);
                            samples = chunk.length;
                            System.arraycopy(chunk, 0, pcm, 0, samples);
                        } else {
                            samples = record.read(pcm, 0, pcm.length);
                        }
                        if (samples <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0,
                                    presentationUs(totalSamples),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                            drainDeadline = System.currentTimeMillis() + DRAIN_MS;
                        } else {
                            if (maxAmp(pcm, samples) >= AUDIBLE) {
                                audibleSamples += samples;
                            }
                            totalSamples += queuePcm(codec, inIndex, pcm, samples, totalSamples);
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
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                codec.release();
            }
            if (muxer != null) {
                try {
                    if (muxing) {
                        muxer.stop();
                    }
                } catch (Throwable ignored) {
                }
                muxer.release();
            }
        }

        int seconds = (int) (totalSamples / SAMPLE_RATE);
        int percent = totalSamples > 0 ? (int) (audibleSamples * 100 / totalSamples) : 0;
        result = new RecordResult(path, sourceName, seconds, percent).wire();
    }

    /** Writes native-order 16-bit PCM into the input buffer and queues it; returns samples queued. */
    private static int queuePcm(MediaCodec codec, int inIndex, short[] pcm, int samples,
                                long totalSamples) {
        ByteBuffer in = codec.getInputBuffer(inIndex);
        in.clear();
        in.order(ByteOrder.nativeOrder());
        in.asShortBuffer().put(pcm, 0, samples);
        codec.queueInputBuffer(inIndex, 0, samples * 2, presentationUs(totalSamples), 0);
        return samples;
    }

    private static long presentationUs(long samples) {
        return samples * 1_000_000L / SAMPLE_RATE;
    }

    /** Runs under the shell identity in the Shizuku process, so RECORD_AUDIO is not ours to check. */
    @SuppressLint("MissingPermission")
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
