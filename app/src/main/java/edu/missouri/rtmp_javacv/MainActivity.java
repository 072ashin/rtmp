package edu.missouri.rtmp_javacv;

import android.Manifest;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.PowerManager;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import pub.devrel.easypermissions.EasyPermissions;

import static org.bytedeco.javacpp.avcodec.AV_CODEC_ID_H264;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private final static String LOG_TAG = "MainActivity";

    private PowerManager.WakeLock mWakeLock;

    private String ffmpeg_link = "rtmp://128.206.20.110/myapp/mystream";
    //private String ffmpeg_link = Environment.getExternalStorageDirectory() + "/test.flv";

    private volatile FFmpegFrameRecorder recorder;
    boolean recording = false;
    long startTime = 0;

    private int imageWidth = 640;
    private int imageHeight = 320;
    private int frameRate = 23;


    private CameraView cameraView;
    //private IplImage yuvIplimage = null;
    private Frame yuvImage;

    private Button recordButton;
    private LinearLayout mainLayout;
    private boolean init = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_main);

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO,Manifest.permission.WRITE_EXTERNAL_STORAGE},
                1);
    }

    @Override
    protected void onResume() {
        super.onResume();

//        boolean hasPermissions = EasyPermissions.hasPermissions(this, Manifest.permission.CAMERA)
//                && EasyPermissions.hasPermissions(this, Manifest.permission.RECORD_AUDIO)
//                && EasyPermissions.hasPermissions(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
//
//        if (hasPermissions) {
            if (!init) {
                initLayout();
                init = true;
            }
//        } else {
//            Toast.makeText(this, "Please, grant all permissions in app settings", Toast.LENGTH_LONG).show();
//        }

        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, LOG_TAG);
            mWakeLock.acquire();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        recording = false;
    }


    private void initLayout() {

        mainLayout = (LinearLayout) this.findViewById(R.id.record_layout);

        recordButton = (Button) findViewById(R.id.recorder_control);
        recordButton.setText("Start");
        recordButton.setOnClickListener(this);

        cameraView = new CameraView(this);

        LinearLayout.LayoutParams layoutParam = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        mainLayout.addView(cameraView, layoutParam);
        Log.v(LOG_TAG, "added cameraView to mainLayout");
    }

    private void initRecorder() {
        Log.w(LOG_TAG,"initRecorder");

        // region
        yuvImage = new Frame(imageWidth, imageHeight, Frame.DEPTH_UBYTE, 2);
        Log.d(LOG_TAG, "IplImage.create");
        // endregion

        recorder = new FFmpegFrameRecorder(ffmpeg_link, imageWidth, imageHeight, 1);
        Log.v(LOG_TAG, "FFmpegFrameRecorder: " + ffmpeg_link + " imageWidth: " + imageWidth + " imageHeight " + imageHeight);

        //recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        //recorder.setFormat("mp4");
        recorder.setVideoCodec(AV_CODEC_ID_H264);
        recorder.setFormat("flv");
        Log.v(LOG_TAG, "recorder.setFormat(\"flv\")");
        // re-set in the surface changed method as well
        recorder.setFrameRate(frameRate);
        Log.v(LOG_TAG, "recorder.setFrameRate(frameRate)");

    }

    // Start the capture
    public void startRecord() {
        initRecorder();

        try {
            recorder.start();
            startTime = System.currentTimeMillis();
            recording = true;
        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }

    public void stopRecord() {

        if (recorder != null && recording) {
            recording = false;
            Log.v(LOG_TAG,"Finishing recording, calling stop and release on recorder");
            try {
                recorder.stop();
                recorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
            recorder = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Quit when back button is pushed
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (recording) {
                stopRecord();
            }
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onClick(View v) {
        if (!recording) {
            startRecord();
            Log.w(LOG_TAG, "Start Button Pushed");
            recordButton.setText("Stop");
        } else {
            stopRecord();
            Log.w(LOG_TAG, "Stop Button Pushed");
            recordButton.setText("Start");
        }
    }

    class CameraView extends SurfaceView implements SurfaceHolder.Callback, Camera.PreviewCallback {

        private boolean previewRunning = false;

        private SurfaceHolder holder;
        private Camera camera;

        private byte[] previewBuffer;

        long videoTimestamp = 0;

        Bitmap bitmap;
        Canvas canvas;

        public CameraView(Context _context) {
            super(_context);

            holder = this.getHolder();
            holder.addCallback(this);
            holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);

            try {
                camera.setPreviewDisplay(holder);
                camera.setPreviewCallback(this);

                Camera.Parameters currentParams = camera.getParameters();
                Log.v(LOG_TAG,"Preview Framerate: " + currentParams.getPreviewFrameRate());
                Log.v(LOG_TAG,"Preview imageWidth: " + currentParams.getPreviewSize().width + " imageHeight: " + currentParams.getPreviewSize().height);

                // Use these values
                imageWidth = currentParams.getPreviewSize().width;
                imageHeight = currentParams.getPreviewSize().height;
                frameRate = currentParams.getPreviewFrameRate();

                bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ALPHA_8);


	        	/*
				Log.v(LOG_TAG,"Creating previewBuffer size: " + imageWidth * imageHeight * ImageFormat.getBitsPerPixel(currentParams.getPreviewFormat())/8);
	        	previewBuffer = new byte[imageWidth * imageHeight * ImageFormat.getBitsPerPixel(currentParams.getPreviewFormat())/8];
				camera.addCallbackBuffer(previewBuffer);
	            camera.setPreviewCallbackWithBuffer(this);
	        	*/

                camera.startPreview();
                previewRunning = true;
            }
            catch (IOException e) {
                Log.v(LOG_TAG,e.getMessage());
                e.printStackTrace();
            }
        }

        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.v(LOG_TAG,"Surface Changed: width " + width + " height: " + height);

            // We would do this if we want to reset the camera parameters
            /*
            if (!recording) {
    			if (previewRunning){
    				camera.stopPreview();
    			}

    			try {
    				//Camera.Parameters cameraParameters = camera.getParameters();
    				//p.setPreviewSize(imageWidth, imageHeight);
    			    //p.setPreviewFrameRate(frameRate);
    				//camera.setParameters(cameraParameters);

    				camera.setPreviewDisplay(holder);
    				camera.startPreview();
    				previewRunning = true;
    			}
    			catch (IOException e) {
    				Log.e(LOG_TAG,e.getMessage());
    				e.printStackTrace();
    			}
    		}
            */

            // Get the current parameters
            Camera.Parameters currentParams = camera.getParameters();
            Log.v(LOG_TAG,"Preview Framerate: " + currentParams.getPreviewFrameRate());
            Log.v(LOG_TAG,"Preview imageWidth: " + currentParams.getPreviewSize().width + " imageHeight: " + currentParams.getPreviewSize().height);

            // Use these values
            imageWidth = currentParams.getPreviewSize().width;
            imageHeight = currentParams.getPreviewSize().height;
            frameRate = currentParams.getPreviewFrameRate();

            // Create the yuvIplimage if needed
            //yuvIplimage = IplImage.create(imageWidth, imageHeight, IPL_DEPTH_8U, 2);
            yuvImage = new Frame(imageWidth, imageHeight, Frame.DEPTH_UBYTE, 2);
            //yuvIplimage = IplImage.create(imageWidth, imageHeight, IPL_DEPTH_32S, 2);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            try {
                camera.setPreviewCallback(null);

                previewRunning = false;
                camera.release();

            } catch (RuntimeException e) {
                Log.v(LOG_TAG,e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {

            if (yuvImage != null && recording) {
                videoTimestamp = 1000 * (System.currentTimeMillis() - startTime);

                // Put the camera preview frame right into the yuvIplimage object
                //yuvIplimage.getByteBuffer().put(data);

                // region
                ((ByteBuffer)yuvImage.image[0].position(0)).put(data);
                // endregion

                // FAQ about IplImage:
                // - For custom raw processing of data, getByteBuffer() returns an NIO direct
                //   buffer wrapped around the memory pointed by imageData, and under Android we can
                //   also use that Buffer with Bitmap.copyPixelsFromBuffer() and copyPixelsToBuffer().
                // - To get a BufferedImage from an IplImage, we may call getBufferedImage().
                // - The createFrom() factory method can construct an IplImage from a BufferedImage.
                // - There are also a few copy*() methods for BufferedImage<->IplImage data transfers.

                // Let's try it..
                // This works but only on transparency
                // Need to find the right Bitmap and IplImage matching types

            	/*
            	bitmap.copyPixelsFromBuffer(yuvIplimage.getByteBuffer());
            	//bitmap.setPixel(10,10,Color.MAGENTA);

            	canvas = new Canvas(bitmap);
            	Paint paint = new Paint();
            	paint.setColor(Color.GREEN);
            	float leftx = 20;
            	float topy = 20;
            	float rightx = 50;
            	float bottomy = 100;
            	RectF rectangle = new RectF(leftx,topy,rightx,bottomy);
            	canvas.drawRect(rectangle, paint);

            	bitmap.copyPixelsToBuffer(yuvIplimage.getByteBuffer());
                */
                //Log.v(LOG_TAG,"Writing Frame");

                try {

                    // Get the correct time
                    recorder.setTimestamp(videoTimestamp);

                    // Record the image into FFmpegFrameRecorder
                    //recorder.record(yuvIplimage);

                    // region
                    recorder.record(yuvImage);
                    // endregion

                } catch (FFmpegFrameRecorder.Exception e) {
                    Log.v(LOG_TAG,e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }
}

