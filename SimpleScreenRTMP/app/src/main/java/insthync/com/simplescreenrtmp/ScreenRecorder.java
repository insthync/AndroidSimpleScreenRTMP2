package insthync.com.simplescreenrtmp;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenRecorder extends Thread {
    private static final String TAG = "ScreenRecorder";

    private int mWidth;
    private int mHeight;
    private int mBitRate;
    private int mDpi;
    private MediaProjection mMediaProjection;
    // parameters for the encoder
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC; // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 15; // 15 fps
    private static final int IFRAME_INTERVAL = 1; // 1 seconds between I-frames
    private static final String AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int AUDIO_CHANNEL_COUNT = 1;
    private static final int AUDIO_MAX_INPUT_SIZE = 8820;
    private static final int AUDIO_RECORD_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;

    private MediaCodec mEncoder;
    private Surface mSurface;
    private AtomicBoolean mQuit = new AtomicBoolean(false);
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private VirtualDisplay mVirtualDisplay;
    private ScreenRecordListener mListener;

    private AudioRecord mAudioRecord;
    private byte[] mAudioBuffer;
    private MediaCodec mAudioEncoder;
    private MediaCodec.BufferInfo mAudioBufferInfo = new MediaCodec.BufferInfo();
    private int mAudioSampleRate;
    private int mAudioBitrate;

    // Recording to sdcard
    private String mDstPath;
    private MediaMuxer mMuxer;
    private boolean mMuxerStarted = false;
    private boolean mIsAddVideoToMuxer = false;
    private boolean mIsAddAudioToMuxer = false;
    private int mVideoTrackIndex = -1;
    private int mAudioTrackIndex = -1;
    private long mStartTime = 0;

    public ScreenRecorder(int width, int height, int bitrate, int dpi, int audioSampleRate, int audioBitrate, MediaProjection mp, String dstPath, ScreenRecordListener listener) {
        super(TAG);
        mWidth = width;
        mHeight = height;
        mBitRate = bitrate;
        mDpi = dpi;
        mAudioSampleRate = audioSampleRate;
        mAudioBitrate = audioBitrate;
        mMediaProjection = mp;
        mDstPath = dstPath;
        mListener = listener;
        mStartTime = 0;
    }

    /**
     * stop task
     */
    public final void quit() {
        mQuit.set(true);
    }

    @Override
    public void run() {
        try {
            try {
                prepareEncoder();
                prepareAudioEncoder();
                if (mDstPath != null && mDstPath.length() > 0)
                    mMuxer = new MediaMuxer(mDstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            mVirtualDisplay = mMediaProjection.createVirtualDisplay(TAG + "-display",
                    mWidth, mHeight, mDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    mSurface, null, null);
            Log.d(TAG, "created virtual display: " + mVirtualDisplay);

            int minBufferSize = AudioRecord.getMinBufferSize(mAudioSampleRate, AUDIO_CHANNEL_CONFIG, AUDIO_RECORD_FORMAT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, mAudioSampleRate, AUDIO_CHANNEL_CONFIG, AUDIO_RECORD_FORMAT, minBufferSize * 5);
            mAudioBuffer = new byte[mAudioSampleRate / 10 * 2];
            if (mAudioRecord != null && mAudioRecord.getState() == AudioRecord.STATE_INITIALIZED)
                mAudioRecord.startRecording();

            recordVirtualDisplay();
        } catch (Exception e) {
            Log.e(TAG, e.getMessage());
        } finally {
            release();
        }
    }

    private void recordVirtualDisplay() {
        while (!mQuit.get()) {

            if (mStartTime == 0)
                mStartTime = mBufferInfo.presentationTimeUs / 1000;

            long timestamp = mBufferInfo.presentationTimeUs / 1000 - mStartTime;

            if (mEncoder != null) {
                int videoOutputBufferIndex = mEncoder.dequeueOutputBuffer(mBufferInfo, 10000);
                if (videoOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Video Format changed " + mEncoder.getOutputFormat());
                    resetOutputFormat();
                } else if (videoOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Ignore
                } else if (videoOutputBufferIndex >= 0) {
                    encodeToVideoTrack(timestamp, videoOutputBufferIndex);

                    mEncoder.releaseOutputBuffer(videoOutputBufferIndex, false);
                }
            }

            if (mAudioRecord != null && mAudioRecord.getState() == AudioRecord.STATE_INITIALIZED && mAudioEncoder != null) {
                // Read audio data from recorder then write to encoder
                int size = mAudioRecord.read(mAudioBuffer, 0, mAudioBuffer.length);
                if (size > 0) {
                    int audioInputBufferIndex = mAudioEncoder.dequeueInputBuffer(10000);
                    if (audioInputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = mAudioEncoder.getInputBuffer(audioInputBufferIndex);
                        inputBuffer.position(0);
                        inputBuffer.put(mAudioBuffer, 0, mAudioBuffer.length);
                        mAudioEncoder.queueInputBuffer(audioInputBufferIndex, 0, mAudioBuffer.length, timestamp, 0);
                    }
                }

                int audioOutputBufferIndex = mAudioEncoder.dequeueOutputBuffer(mAudioBufferInfo, 10000);
                if (audioOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Audio Format changed " + mAudioEncoder.getOutputFormat());
                    //resetAudioOutputFormat();
                } else if (audioOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Ignore
                } else if (audioOutputBufferIndex >= 0) {
                    encodeToAudioTrack(timestamp, audioOutputBufferIndex);

                    mAudioEncoder.releaseOutputBuffer(audioOutputBufferIndex, false);
                }
            }
        }
    }

    private void encodeToVideoTrack(long timestamp, int index) {
        ByteBuffer encodedData = mEncoder.getOutputBuffer(index);

        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            encodedData.position(mBufferInfo.offset);
            encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

            final byte[] bytes = new byte[encodedData.remaining()];
            encodedData.get(bytes);

            if (mListener != null) {
                mListener.OnReceiveScreenRecordData(mBufferInfo, true, timestamp, bytes);
            }

            mBufferInfo.size = 0;
        }

        if (mBufferInfo.size > 0) {
            encodedData.position(mBufferInfo.offset);
            encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

            final byte[] bytes = new byte[encodedData.remaining()];
            encodedData.get(bytes);

            if (mListener != null) {
                mListener.OnReceiveScreenRecordData(mBufferInfo, false, timestamp, bytes);
            }

            if (mMuxer != null && mIsAddVideoToMuxer) {
                mMuxer.writeSampleData(mVideoTrackIndex, encodedData, mBufferInfo);
            }
        }
    }

    private void encodeToAudioTrack(long timestamp, int index) {
        ByteBuffer encodedData = mAudioEncoder.getOutputBuffer(index);

        if ((mAudioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            encodedData.position(mAudioBufferInfo.offset);
            encodedData.limit(mAudioBufferInfo.offset + mAudioBufferInfo.size);

            final byte[] bytes = new byte[encodedData.remaining()];
            encodedData.get(bytes);

            if (mListener != null) {
                mListener.OnReceiveAudioRecordData(mAudioBufferInfo, true, timestamp, bytes, AUDIO_CHANNEL_COUNT, 1);
            }

            mAudioBufferInfo.size = 0;
        }

        if (mAudioBufferInfo.size > 0) {
            encodedData.position(mAudioBufferInfo.offset);
            encodedData.limit(mAudioBufferInfo.offset + mAudioBufferInfo.size);

            final byte[] bytes = new byte[encodedData.remaining()];
            encodedData.get(bytes);

            if (mListener != null) {
                mListener.OnReceiveAudioRecordData(mAudioBufferInfo, false, timestamp, bytes, AUDIO_CHANNEL_COUNT, 1);
            }

            if (mMuxer != null && mIsAddAudioToMuxer) {
                mMuxer.writeSampleData(mAudioTrackIndex, encodedData, mAudioBufferInfo);
            }
        }
    }

    private void resetOutputFormat() {
        // should happen before receiving buffers, and should only happen once
        if (mIsAddVideoToMuxer) {
            return;
        }

        if (mMuxer != null) {
            MediaFormat newFormat = mEncoder.getOutputFormat();

            Log.i(TAG, "vidoe output format changed.\n new format: " + newFormat.toString());
            mVideoTrackIndex = mMuxer.addTrack(newFormat);
            if (!mMuxerStarted) {
                mMuxer.start();
                mMuxerStarted = true;
            }
            Log.i(TAG, "started media muxer, videoIndex=" + mVideoTrackIndex);
        }
        mIsAddVideoToMuxer = true;
    }

    private void resetAudioOutputFormat() {
        // should happen before receiving buffers, and should only happen once
        if (mIsAddAudioToMuxer) {
            return;
        }

        if (mMuxer != null) {
            MediaFormat newFormat = mAudioEncoder.getOutputFormat();

            Log.i(TAG, "audio output format changed.\n new format: " + newFormat.toString());
            mAudioTrackIndex = mMuxer.addTrack(newFormat);
            if (!mMuxerStarted) {
                mMuxer.start();
                mMuxerStarted = true;
            }
            Log.i(TAG, "started media muxer, audioIndex=" + mAudioTrackIndex);
        }
        mIsAddAudioToMuxer = true;
    }

    private void prepareEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / FRAME_RATE);

        Log.d(TAG, "created video format: " + format);
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mEncoder.createInputSurface();
        Log.d(TAG, "created input surface: " + mSurface);
        mEncoder.start();
    }

    private void prepareAudioEncoder() throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, mAudioSampleRate, AUDIO_CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mAudioBitrate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_MAX_INPUT_SIZE);

        mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        mAudioEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioEncoder.start();
    }

    private void release() {
        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord.release();
            mAudioRecord = null;
        }
        if (mMediaProjection != null) {
            mMediaProjection.stop();
            mMediaProjection = null;
        }
    }

    public interface ScreenRecordListener
    {
        void OnReceiveScreenRecordData(final MediaCodec.BufferInfo bufferInfo, boolean isHeader, long timestamp, final byte[] data);
        void OnReceiveAudioRecordData(final MediaCodec.BufferInfo bufferInfo, boolean isHeader, long timestamp, final byte[] data, final int numberOfChannel, final int sampleSizeIndex);
    }
}
