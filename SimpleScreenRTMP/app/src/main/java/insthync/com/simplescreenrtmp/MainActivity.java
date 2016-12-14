package insthync.com.simplescreenrtmp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.DisplayMetrics;
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

import java.io.File;
import java.io.IOException;

public class MainActivity extends Activity implements View.OnClickListener, RtmpConnectionListener, ScreenRecorder.ScreenRecordListener, AudioRecorder.AudioRecordListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_SCREEN_CAPTURE = 1;
    private static final int REQUEST_WRITE_STORAGE = 2;
    // RTMP Constraints
    private static final String DEFAULT_RMTP_HOST = "192.168.1.78";
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

        boolean hasPermission = (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
        if (!hasPermission) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
        } else {
            Log.d(TAG, "App can write storage");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode)
        {
            case REQUEST_WRITE_STORAGE: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                {
                    //reload my activity with permission granted or use the features what required the permission
                } else
                {
                    Toast.makeText(this, "The app was not allowed to write to your storage. Hence, it cannot function properly. Please consider granting it this permission", Toast.LENGTH_LONG).show();
                }
            }
        }

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        MediaProjection mediaProjection = mMediaProjectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e("@@", "media projection is null");
            return;
        }

        DisplayMetrics displaymetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);

        // video size
        final int width = displaymetrics.widthPixels;
        final int height = displaymetrics.heightPixels;
        final int bitrate = 2500;

        File file = new File(Environment.getExternalStorageDirectory(),
                "record-" + width + "x" + height + "-" + System.currentTimeMillis() + ".mp4");

        mScreenRecorder = new ScreenRecorder(width, height, bitrate, 1, mediaProjection, file.getAbsolutePath(), this);
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
        if (mScreenRecorder != null) {
            mScreenRecorder.quit();
            mScreenRecorder = null;
        }
        if (mAudioRecorder != null) {
            mAudioRecorder.quit();
            mAudioRecorder = null;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (mRtmpMuxer != null) {
                    try {
                        mRtmpMuxer.deleteStream();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    mRtmpMuxer.stop();
                    mRtmpMuxer = null;
                }
            }
        }).start();
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
        startActivityForResult(captureIntent, REQUEST_SCREEN_CAPTURE);
    }

    @Override
    public void onConnectionError(@NonNull IOException e) {
        release();
        mButton.setText("Start recorder");
    }

    @Override
    public void OnReceiveScreenRecordData(final MediaCodec.BufferInfo bufferInfo, final byte[] data) {
        final boolean isHeader = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        final boolean isKeyframe = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && !isHeader;
        final long timestamp = bufferInfo.presentationTimeUs;

        // Always call postVideo method from a background thread.
        Log.d(TAG, "OnReceiveScreenRecordData isHeader: " + isHeader + " isKeyframe: " + isKeyframe + " timestamp: " + timestamp + " data: " + data);
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