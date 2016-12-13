package insthync.com.simplescreenrtmp;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.octiplex.android.rtmp.AACAudioFrame;
import com.octiplex.android.rtmp.AACAudioHeader;
import com.octiplex.android.rtmp.H264VideoFrame;
import com.octiplex.android.rtmp.RtmpMuxer;
import com.octiplex.android.rtmp.RtmpConnectionListener;
import com.octiplex.android.rtmp.Time;

import java.io.IOException;

public class MainActivity extends Activity implements View.OnClickListener, RtmpConnectionListener, ScreenRecorder.ScreenRecordListener, AudioRecorder.AudioRecordListener {
    private static final int REQUEST_CODE = 1;
    // RTMP Constraints
    private static final String DEFAULT_RMTP_HOST = "192.168.1.45";
    private static final int DEFAULT_RTMP_PORT = 1935;
    private static final String DEFAULT_APP_NAME = "live";
    private static final String DEFAULT_PUBLISH_NAME = "test";
    private MediaProjectionManager mMediaProjectionManager;
    private ScreenRecorder mScreenRecorder;
    private AudioRecorder mAudioRecorder;
    private RtmpMuxer mRtmpMuxer;
    private Button mButton;
    private EditText mTextServerAddress;
    private EditText mTextServerPort;
    private EditText mTextAppName;
    private EditText mTextPublishName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mButton = (Button) findViewById(R.id.toggle);
        mButton.setOnClickListener(this);
        mButton.setText("Start recorder");
        mTextServerAddress = (EditText) findViewById(R.id.serverAddress);
        mTextServerPort = (EditText) findViewById(R.id.serverPort);
        mTextAppName = (EditText) findViewById(R.id.appName);
        mTextPublishName = (EditText) findViewById(R.id.publishName);
        mTextServerAddress.setText(DEFAULT_RMTP_HOST);
        mTextServerPort.setText(""+DEFAULT_RTMP_PORT);
        mTextAppName.setText(DEFAULT_APP_NAME);
        mTextPublishName.setText(DEFAULT_PUBLISH_NAME);
        //noinspection ResourceType
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        MediaProjection mediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e("@@", "media projection is null");
            return;
        }
        // video size
        final int width = 1280;
        final int height = 720;
        final int bitrate = 6000000;
        mScreenRecorder = new ScreenRecorder(width, height, bitrate, 1, mediaProjection, this);
        mScreenRecorder.start();
        //mAudioRecorder = new AudioRecorder(this);
        //mAudioRecorder.start();
        mButton.setText("Stop Recorder");
        Toast.makeText(this, "Screen recorder is running...", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    @Override
    public void onClick(View v) {
        final String serverAddress = mTextServerAddress.getText().toString();
        final int serverPort = Integer.parseInt(mTextServerPort.getText().toString());
        final String appName = mTextAppName.getText().toString();
        if (mRtmpMuxer != null || mScreenRecorder != null || mAudioRecorder != null) {
            release();
            mButton.setText("Start recorder");
        } else {
            // Always call start method from a background thread.

            mRtmpMuxer = new RtmpMuxer(serverAddress, serverPort, new Time()
            {
                @Override
                public long getCurrentTimestamp()
                {
                    return System.currentTimeMillis();
                }
            });

            new AsyncTask<Void, Void, Void>()
            {
                @Override
                protected Void doInBackground(Void... params)
                {
                    mRtmpMuxer.start(MainActivity.this, appName, null, null);
                    return null;
                }
            }.execute();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        release();
    }

    private void release()
    {
        if (mRtmpMuxer != null) {
            try {
                mRtmpMuxer.deleteStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mRtmpMuxer.stop();
        }
        if (mScreenRecorder != null) {
            mScreenRecorder.quit();
            mScreenRecorder = null;
        }
        if (mAudioRecorder != null) {
            mAudioRecorder.quit();
            mAudioRecorder = null;
        }
    }

    @Override
    public void onConnected() {
        // Muxer is connected to the RTMP server, you can create a stream to publish data
        final String publishName = mTextPublishName.getText().toString();
        new AsyncTask<Void, Void, Void>()
        {
            @Override
            protected Void doInBackground(Void... params)
            {
                try {
                    mRtmpMuxer.createStream(publishName);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }
        }.execute();
    }

    @Override
    public void onReadyToPublish() {
        Intent captureIntent = mMediaProjectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQUEST_CODE);
    }

    @Override
    public void onConnectionError(@NonNull IOException e) {
        release();
        mButton.setText("Start recorder");
    }

    @Override
    public void OnReceiveScreenRecordData(final boolean isHeader, final boolean isKeyframe, final long timestamp, final byte[] data) {
        // Always call postVideo method from a background thread.
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    mRtmpMuxer.postVideo(new H264VideoFrame() {
                        @Override
                        public boolean isHeader() {
                            return isHeader;
                        }

                        @Override
                        public long getTimestamp() {
                            return timestamp;
                        }

                        @NonNull
                        @Override
                        public byte[] getData() {
                            return data;
                        }

                        @Override
                        public boolean isKeyframe() {
                            return isKeyframe;
                        }
                    });
                } catch (IOException e) {
                    // An error occured while sending the video frame to the server
                }
                return null;
            }
        }.execute();
    }

    @Override
    public void OnSetAudioHeader(final int numberOfChannel, final int sampleSizeIndex, final byte[] data) {
        mRtmpMuxer.setAudioHeader(new AACAudioHeader() {
            @NonNull
            @Override
            public byte[] getData() {
                return data;
            }

            @Override
            public int getNumberOfChannels() {
                return numberOfChannel;
            }

            @Override
            public int getSampleSizeIndex() {
                return sampleSizeIndex;
            }
        });
    }

    @Override
    public void OnReceiveAudioRecordData(final long timestamp, final byte[] data) {
        // Don't call postAudio from the extracting thread.
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    mRtmpMuxer.postAudio(new AACAudioFrame() {
                        @Override
                        public long getTimestamp() {
                            return timestamp;
                        }

                        @NonNull
                        @Override
                        public byte[] getData() {
                            return data;
                        }
                    });
                } catch (IOException e) {
                    // An error occured while sending the audio frame to the server
                }

                return null;
            }
        }.execute();
    }
}