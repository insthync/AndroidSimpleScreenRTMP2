package insthync.com.simplescreenrtmp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioRecorder extends Thread {
    private static final String TAG = "AudioRecorder";

    // Parameters for recorder
    private static final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;
    private static final int OUTPUT_FORMAT = MediaRecorder.OutputFormat.AAC_ADTS;
    private static final int AUDIO_ENCODER = MediaRecorder.AudioEncoder.AAC;
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_COUNT = 1;
    private static final int BIT_RATE = 128000;
    private static final int MAX_INPUT_SIZE = 16384;
    private static final String LOCAL_SOCKET_ADDRESS_MIC = "microphoneCapture";

    private MediaCodec mEncoder;
    private MediaRecorder mMediaRecorder;
    private LocalSocket mLocalSocket;
    private AtomicBoolean mQuit = new AtomicBoolean(false);
    private boolean mHeaderSent;
    private AudioRecordListener mListener;

    public AudioRecorder(AudioRecordListener listener) {
        super(TAG);
        mListener = listener;
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
                prepareRecorder();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            recordAudio();
            mMediaRecorder.start();
        } finally {
            release();
        }
    }

    private void recordAudio() {
        while (!mQuit.get()) {
            // TODO parse AAC: This sample doesn't provide AAC extracting complete method since it's not the purpose of this repository.

            if (!mHeaderSent) {
                // TODO extract header data
                final byte[] aacHeader = new byte[0];
                final int numberOfChannel = 0;
                final int sampleSizeIndex = 0;

                if (mListener != null)
                    mListener.OnSetAudioHeader(numberOfChannel, sampleSizeIndex, aacHeader);
                mHeaderSent = true;
            }

            // TODO extract frame data
            final byte[] aacData = new byte[0];
            final long timestamp = 0;
            if (mListener != null)
                mListener.OnReceiveAudioRecordData(timestamp, aacData);
        }
    }

    public void prepareRecorder() throws IOException {
        mHeaderSent = false;

        mLocalSocket = new LocalSocket();
        mLocalSocket.connect(new LocalSocketAddress(LOCAL_SOCKET_ADDRESS_MIC));

        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setAudioSource(AUDIO_SOURCE);
        mMediaRecorder.setOutputFormat(OUTPUT_FORMAT);
        mMediaRecorder.setAudioEncoder(AUDIO_ENCODER);
        mMediaRecorder.setOutputFile(mLocalSocket.getFileDescriptor());
        mMediaRecorder.prepare();

        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MIME_TYPE);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNEL_COUNT);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE);

        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();
    }

    private void release() {
        if (mMediaRecorder != null) {
            mMediaRecorder.stop();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
        if (mLocalSocket != null) {
            try {
                mLocalSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mLocalSocket = null;
        }
    }

    public interface AudioRecordListener
    {
        void OnSetAudioHeader(final int numberOfChannel, final int sampleSizeIndex, final byte[] data);
        void OnReceiveAudioRecordData(final long timestamp, final byte[] data);
    }
}
