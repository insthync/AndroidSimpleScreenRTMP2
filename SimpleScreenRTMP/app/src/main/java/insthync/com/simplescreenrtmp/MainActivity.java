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

public class MainActivity extends Activity implements View.OnClickListener, RtmpConnectionListener, ScreenRecorder.ScreenRecordListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_SCREEN_CAPTURE = 1;
    private static final int REQUEST_STREAM = 2;
    private static String[] PERMISSIONS_STREAM = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.CAPTURE_VIDEO_OUTPUT,
            Manifest.permission.CAPTURE_AUDIO_OUTPUT,
    };
    // RTMP Constraints
    private static final String DEFAULT_RMTP_HOST = "188.166.191.129";
    private static final int DEFAULT_RTMP_PORT = 1935;
    private static final String DEFAULT_APP_NAME = "live";
    private static final String DEFAULT_PUBLISH_NAME = "test";
    private MediaProjectionManager mMediaProjectionManager;
    private ScreenRecorder mScreenRecorder;
    private RtmpMuxer mRtmpMuxer;
    private Button mButton;
    private EditText mTextServerAddress;
    private EditText mTextServerPort;
    private EditText mTextAppName;
    private EditText mTextPublishName;
    private boolean authorized;

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

        verifyPermissions();
    }

    public void verifyPermissions() {
        for (String permission : PERMISSIONS_STREAM) {
            int permissionResult = ActivityCompat.checkSelfPermission(MainActivity.this, permission);
            if (permissionResult != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        MainActivity.this,
                        PERMISSIONS_STREAM,
                        REQUEST_STREAM
                );
                authorized = false;
                return;
            }
        }
        authorized = true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STREAM) {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    authorized = false;
                    return;
                }
            }
            authorized = true;
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
        final int width = 640;
        final int height = 480;
        final int bitrate = 1000000;
        final int audioSampleRate = 44100;
        final int audioBitrate = 32000;

        File file = new File(Environment.getExternalStorageDirectory(),
                "record-" + width + "x" + height + "-" + System.currentTimeMillis() + ".mp4");

        mScreenRecorder = new ScreenRecorder(width, height, bitrate, 1, audioSampleRate, audioBitrate, mediaProjection, file.getAbsolutePath(), this);
        mScreenRecorder.start();
        mButton.setText("Stop Recorder");
        Toast.makeText(this, "Screen recorder is running...", Toast.LENGTH_SHORT).show();
        //moveTaskToBack(true);
    }

    @Override
    public void onClick(View v) {
        final String serverAddress = mTextServerAddress.getText().toString();
        final int serverPort = Integer.parseInt(mTextServerPort.getText().toString());
        final String appName = mTextAppName.getText().toString();
        if (mRtmpMuxer != null || mScreenRecorder != null) {
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
    public void OnReceiveScreenRecordData(final MediaCodec.BufferInfo bufferInfo, final boolean isHeader, final long timestamp, final byte[] data) {

        // Always call postVideo method from a background thread.
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
                    return !isHeader;
                }
            });
        } catch (IOException e) {
            // An error occured while sending the video frame to the server
        }
    }

    @Override
    public void OnReceiveAudioRecordData(final MediaCodec.BufferInfo bufferInfo, final boolean isHeader, final long timestamp, final byte[] data, final int numberOfChannel, final int sampleSizeIndex) {
        if (isHeader) {
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
        } else {
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
        }
    }
}
