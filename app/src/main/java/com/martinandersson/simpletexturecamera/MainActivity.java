package com.martinandersson.simpletexturecamera;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    public static final String TAG = MainActivity.class.getSimpleName();

    public static final int VIDEO_ENCODING_BIT_RATE = 4000000; // This affects the video size
    public static final float MAX_ZOOM_GESTURE_SIZE = 2.5f; // This affects the pinch to zoom gesture

    public static final int MEDIA_TYPE_IMAGE = 1;
    public static final int MEDIA_TYPE_VIDEO = 2;
    public static final int FLASH_OFF = 1;
    public static final int FLASH_ON = 2;
    public static final int FLASH_AUTO = 3;

    private int mCameraId;
    private MediaRecorder mMediaRecorder;
    private Camera mCamera;
    private int mFlashMode = FLASH_OFF;
    private boolean mIsRecording = false;
    private boolean mIsCameraDirectionFront = false;
    private boolean mIsVideoMode = false;

    private Uri mVideoUri;
    private int mDeviceOrientation;
    private ScaleGestureDetector mScaleGestureDetector;
    private int mSurfaceTextureWidth;
    private int mSurfaceTextureHeight;
    private float mSavedScaleFactor = 1.0f;
    private int mMaxZoom;

    @Bind(R.id.layout_background)
    RelativeLayout mLayoutBackground;

    @Bind(R.id.texture_view)
    TextureView mTextureView;

    @Bind(R.id.start_recording_button)
    ImageView mStartRecordingButton;

    @Bind(R.id.stop_recording_button)
    ImageView mStopRecordingButton;

    @Bind(R.id.take_picture_button)
    ImageView mTakePictureButton;

    @Bind(R.id.change_flash_mode_button)
    ImageView mToggleFlash;

    @Bind(R.id.toggle_picture_or_video_button)
    ImageView mTogglePictureOrVideoButton;

    @Bind(R.id.toggle_camera_direction_button)
    ImageView mToggleCameraDirectionButton;

    @Bind(R.id.progress_bar)
    ProgressBar mProgressBar;

    private SurfaceTexture mSurfaceTexture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);

        mTextureView.setSurfaceTextureListener(this);
        mProgressBar.setVisibility(View.GONE);

        // Scale gesture detector is used to capture pinch to zoom
        mScaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.OnScaleGestureListener() {

            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                setCameraZoom(detector.getScaleFactor() * mSavedScaleFactor);
                return false;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                // Set saved scale factor and make sure it's within legal range
                mSavedScaleFactor = mSavedScaleFactor * detector.getScaleFactor();
                if (mSavedScaleFactor < 1.0f) {
                    mSavedScaleFactor = 1.0f;
                } else if (mSavedScaleFactor > MAX_ZOOM_GESTURE_SIZE + 1) {
                    mSavedScaleFactor = MAX_ZOOM_GESTURE_SIZE + 1;
                }

                setCameraZoom(mSavedScaleFactor);
            }
        });

        mLayoutBackground.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return mScaleGestureDetector.onTouchEvent(event);
            }
        });

    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "onSurfaceTextureAvailable");
        mSurfaceTexture = surface;
        mSurfaceTextureWidth = width;
        mSurfaceTextureHeight = height;
        setupCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // Ignored, Camera does all the work for us
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.d(TAG, "onSurfaceTextureDestroyed");
        releaseMediaRecorder();
        releaseCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Invoked every time there's a new Camera preview frame
    }

    @OnClick(R.id.toggle_picture_or_video_button)
    public void togglePictureOrVideo() {
        mIsVideoMode = !mIsVideoMode;
        Log.d(TAG, "togglePictureOrVideo: " + mIsVideoMode);
        updateUI();
    }

    @OnClick(R.id.change_flash_mode_button)
    public void changeFlashMode() {
        mFlashMode++;
        if (mFlashMode > FLASH_AUTO) {
            mFlashMode = FLASH_OFF;
        }
        Log.d(TAG, "changeFlashMode: " + mFlashMode);
        updateFlashMode();
        updateUI();
    }

    @OnClick(R.id.toggle_camera_direction_button)
    public void toggleCameraDirection() {
        mIsCameraDirectionFront = !mIsCameraDirectionFront;
        Log.d(TAG, "toggleCameraDirection: " + mIsCameraDirectionFront);
        updateUI();

        // Restart camera
        releaseMediaRecorder();
        releaseCamera();
        new Handler().post(new Runnable() {
            @Override
            public void run() {
                setupCamera();
            }
        });
    }

    @OnClick(R.id.start_recording_button)
    public void startRecording() {
        Log.d(TAG, "startRecording");
        if (prepareVideoRecorder()) {
            mIsRecording = true;
            updateUI();
            mMediaRecorder.start();
        } else {
            releaseMediaRecorder();
            releaseCamera();
            mIsRecording = false;
            updateUI();
        }
    }

    @OnClick(R.id.stop_recording_button)
    public void stopRecording() {
        if (!mIsRecording) {
            return;
        }

        Log.d(TAG, "stopRecording");
        // Stop recording
        if (mMediaRecorder != null) {
            try {
                mMediaRecorder.stop();
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop recording: " + e.getMessage());
            }
        }
        mIsRecording = false;
        handleCapturedPictureOrRecordedVideo(mVideoUri, true);
    }

    @OnClick(R.id.take_picture_button)
    public void takePicture() {
        Log.d(TAG, "takePicture");

        mProgressBar.setVisibility(View.VISIBLE);

        mDeviceOrientation = getResources().getConfiguration().orientation;
        mCamera.takePicture(null, null, new Camera.PictureCallback() {

            @Override
            public void onPictureTaken(byte[] data, Camera camera) {

                // Freeze the image as soon as the picture is taken in order to inform the user of success.
                mCamera.stopPreview();

                File pictureFile = getOutputMediaFile(MEDIA_TYPE_IMAGE);
                if (pictureFile == null) {
                    Log.w(TAG, "Error creating media file, check storage permissions");
                    return;
                }

                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(pictureFile);
                    fos.write(data);
                    Log.d(TAG, "takePicture ---> " + pictureFile.getAbsolutePath());
                } catch (FileNotFoundException e) {
                    Log.d(TAG, "File not found: " + e.getMessage());
                } catch (IOException e) {
                    Log.d(TAG, "Error accessing file: " + e.getMessage());
                } finally {
                    try {
                        fos.close();
                    } catch (Exception e) {
                        Log.w(TAG, "takePicture - Failed to close file");
                    }
                }

                RotateBitmapTask task = new RotateBitmapTask(pictureFile, true, mIsCameraDirectionFront);
                task.execute();

            }
        });

    }

    /**
     * Update the UI to show the correct buttons
     */
    private void updateUI() {
        // Flash mode auto, on or off
        mToggleFlash.setVisibility(!mIsVideoMode && !mIsRecording ? View.VISIBLE : View.GONE);
        switch (mFlashMode) {
            case FLASH_AUTO:
                mToggleFlash.setImageResource(R.drawable.ic_flash_auto_white_24dp);
                break;
            case FLASH_ON:
                mToggleFlash.setImageResource(R.drawable.ic_flash_on_white_24dp);
                break;
            case FLASH_OFF:
                mToggleFlash.setImageResource(R.drawable.ic_flash_off_white_24dp);
                break;
            default:
                mToggleFlash.setImageResource(R.drawable.ic_flash_auto_white_24dp);
                Log.w(TAG, "Invalid state for flash: " + mFlashMode);
                break;
        }

        // Picture of video
        mTogglePictureOrVideoButton.setImageResource(mIsVideoMode ? R.drawable.ic_switch_video_white_24dp : R.drawable.ic_switch_camera_white_24dp);
        mTogglePictureOrVideoButton.setVisibility(!mIsRecording ? View.VISIBLE : View.GONE);

        // Camera direction
        mToggleCameraDirectionButton.setImageResource(mIsCameraDirectionFront ? R.drawable.ic_camera_front_white_24dp : R.drawable.ic_camera_rear_white_24dp);
        mToggleCameraDirectionButton.setVisibility(!mIsRecording ? View.VISIBLE : View.GONE);

        // Take picture, start recording or stop recording
        mTakePictureButton.setVisibility(!mIsVideoMode && !mIsRecording ? View.VISIBLE : View.GONE);
        mStartRecordingButton.setVisibility(mIsVideoMode && !mIsRecording ? View.VISIBLE : View.GONE);
        mStopRecordingButton.setVisibility(mIsVideoMode && mIsRecording ? View.VISIBLE : View.GONE);

    }

    private void setupCamera() {
        if (mCamera != null) {
            return;
        }
        Log.d(TAG, "setupCamera");

        if (Camera.getNumberOfCameras() > 1 && mIsCameraDirectionFront) {
            mCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
        } else {
            mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
        }

        // Setup the camera
        try {
            mCamera = Camera.open(mCameraId);
        } catch (Exception e) {
            Log.e(TAG, "Unable to open camera " + mCameraId + ": " + e.getMessage());
        }

        if (mCamera == null) {
            // Camera is unavailable and probably still in use by another app.
            Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Get camera parameters to modify
        Camera.Parameters parameters = mCamera.getParameters();

        // Set auto focus if supported
        List<String> focusModes = parameters.getSupportedFocusModes();
        if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        }

        // Picture size
        Camera.Size size = getBiggestPictureSize();
        parameters.setPictureSize(size.width, size.height);

        // Check what video sizes are supported and use that for preview size
        CamcorderProfile camcorderProfile = getCamcorderProfile();
        int previewWidth = camcorderProfile.videoFrameWidth;
        int previewHeight = camcorderProfile.videoFrameHeight;

        // Calculate how we need to scale the preview
        // This is done so we can have a 'center crop' effect on the preview
        float ratioSurface = mSurfaceTextureWidth > mSurfaceTextureHeight ? (float) mSurfaceTextureWidth / mSurfaceTextureHeight : (float) mSurfaceTextureHeight / mSurfaceTextureWidth;
        float ratioPreview = (float) previewWidth / previewHeight;
        int scaledHeight;
        int scaledWidth;
        float scaleX = 1f;
        float scaleY = 1f;
        boolean isPortrait = false;
        Display display = getWindowManager().getDefaultDisplay();
        if (previewWidth > 0 && previewHeight > 0) {
            parameters.setPreviewSize(previewWidth, previewHeight);

            if (display.getRotation() == Surface.ROTATION_0 || display.getRotation() == Surface.ROTATION_180) {
                mCamera.setDisplayOrientation(display.getRotation() == Surface.ROTATION_0 ? 90 : 270);
                isPortrait = true;
            } else if (display.getRotation() == Surface.ROTATION_90 || display.getRotation() == Surface.ROTATION_270) {
                mCamera.setDisplayOrientation(display.getRotation() == Surface.ROTATION_90 ? 0 : 180);
                isPortrait = false;
            }
            if (isPortrait && ratioPreview > ratioSurface) {
                scaledHeight = (int) (((float) previewWidth / previewHeight) * mSurfaceTextureWidth);
                scaleX = 1f;
                scaleY = (float) scaledHeight / mSurfaceTextureHeight;
            } else if (isPortrait && ratioPreview < ratioSurface) {
                scaledWidth = (int) (mSurfaceTextureHeight / ((float) previewWidth / previewHeight));
                scaleX = (float) scaledWidth / mSurfaceTextureWidth;
                scaleY = 1f;
            } else if (!isPortrait && ratioPreview < ratioSurface) {
                scaledHeight = (int) (mSurfaceTextureWidth / ((float) previewWidth / previewHeight));
                scaleX = 1f;
                scaleY = (float) scaledHeight / mSurfaceTextureHeight;
            } else if (!isPortrait && ratioPreview > ratioSurface) {
                scaledWidth = (int) (((float) previewWidth / previewHeight) * mSurfaceTextureWidth);
                scaleX = (float) scaledWidth / mSurfaceTextureWidth;
                scaleY = 1f;
            }
        }

        if (parameters.isZoomSupported()) {
            mMaxZoom = parameters.getMaxZoom();
        } else {
            mMaxZoom = 0;
        }

        // Reset zoom level
        mSavedScaleFactor = 1.0f;
        parameters.setZoom(0);

        // Set modified camera parameters
        mCamera.setParameters(parameters);

        // Create transformation matrix
        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY);
        mTextureView.setTransform(matrix);
        try {
            mCamera.setPreviewTexture(mSurfaceTexture);
            mCamera.startPreview();
        } catch (Exception e) {
            Log.w(TAG, "setupCamera failed: " + e.getMessage());
        }

        updateFlashMode();
        updateUI();
    }

    private CamcorderProfile getCamcorderProfile() {
        CamcorderProfile camcorderProfile;
        if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_1080P)) {
            camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_1080P);
        } else if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_720P)) {
            camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_720P);
        } else if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_480P)) {
            camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_480P);
        } else {
            camcorderProfile = CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_LOW);
        }
        return camcorderProfile;
    }

    private void setCameraZoom(float zoomScaleFactor) {
        // Convert gesture to camera zoom value
        int zoom = (int) ((zoomScaleFactor - 1) * mMaxZoom / MAX_ZOOM_GESTURE_SIZE);

        // Sanity check for zoom level
        if (zoom > mMaxZoom) {
            zoom = mMaxZoom;
        } else if (zoom < 0) {
            zoom = 0;
        }

        // Update the camera with the new zoom if it is supported
        Camera.Parameters parameters = mCamera.getParameters();
        if (parameters.isZoomSupported()) {
            parameters.setZoom(zoom);
            mCamera.setParameters(parameters);
        }
    }

    private void updateFlashMode() {
        // Update camera parameters with flash
        try {
            Camera.Parameters parameters = mCamera.getParameters();
            switch (mFlashMode) {
                case FLASH_AUTO:
                    parameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
                    break;
                case FLASH_ON:
                    parameters.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
                    break;
                case FLASH_OFF:
                    parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                    break;
                default:
                    parameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
                    break;
            }
            mCamera.setParameters(parameters);
        } catch (Exception e) {
            Log.w(TAG, "updateFlashMode failed: " + e.getMessage());
            mFlashMode = FLASH_OFF;
            Toast.makeText(this, "Flash not supported", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint("InlinedApi")
    private boolean prepareVideoRecorder() {
        if (mCamera == null) {
            return false;
        }

        mMediaRecorder = new MediaRecorder();
        mMediaRecorder.setOrientationHint(mIsCameraDirectionFront ? 270 : 90);

        // Unlock and set camera to MediaRecorder
        mCamera.unlock();
        mMediaRecorder.setCamera(mCamera);

        // Set audio and video sources
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        // Set a CamcorderProfile
        mMediaRecorder.setProfile(getCamcorderProfile());
        mMediaRecorder.setVideoEncodingBitRate(VIDEO_ENCODING_BIT_RATE);

        // Set output file
        File mediaFile = getOutputMediaFile(MEDIA_TYPE_VIDEO);
        mMediaRecorder.setOutputFile(mediaFile.toString());
        mVideoUri = Uri.fromFile(mediaFile);

        // Prepare configured MediaRecorder
        try {
            mMediaRecorder.prepare();
        } catch (IllegalStateException e) {
            Log.d(TAG, "IllegalStateException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        } catch (IOException e) {
            Log.d(TAG, "IOException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        }
        return true;
    }

    private void releaseMediaRecorder() {
        if (mMediaRecorder != null) {
            mMediaRecorder.reset(); // clear recorder configuration
            mMediaRecorder.release(); // release the recorder object
            mMediaRecorder = null;
            if (mCamera != null) {
                mCamera.lock(); // lock camera for later use
            }
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release(); // release the camera for other applications
            mCamera = null;
        }
    }

    /**
     * Find the biggest supported picture size.
     *
     * @return biggest supported picture size.
     */
    private Camera.Size getBiggestPictureSize() {

        Camera.Parameters p = mCamera.getParameters();
        List<Camera.Size> supportedPictureSizes = p.getSupportedPictureSizes();

        // Set the first value as the current max size.
        Camera.Size maxSize = supportedPictureSizes.get(0);
        int maxSizeArea = maxSize.width * maxSize.height;

        // Loop through all Sizes in order to find the biggest.
        for (Camera.Size size : supportedPictureSizes) {

            // Determine what the area for the new Size is.
            int sizeArea = size.width * size.height;

            if (sizeArea > maxSizeArea) {
                // The new Size's area is bigger - set it as the max Size.
                maxSize = size;
                maxSizeArea = size.width * size.height;
            }
        }
        return maxSize;
    }

    /**
     * Create a File for saving an image or video
     */
    private File getOutputMediaFile(int type) {
        // To be safe, you should check that the SDCard is mounted using Environment.getExternalStorageState() before doing this.

        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SimpleTextureCamera");
        // This location works best if you want the created images to be shared between applications and persist after your app has been uninstalled.

        // Create the storage directory if it does not exist
        if (!mediaStorageDir.exists() && !mediaStorageDir.mkdirs()) {
            Log.w(TAG, "getOutputMediaFile - failed to create directory");
            return null;
        }

        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File mediaFile;
        if (type == MEDIA_TYPE_IMAGE) {
            mediaFile = new File(mediaStorageDir.getPath() + File.separator + "IMG_" + timeStamp + ".jpg");
        } else if (type == MEDIA_TYPE_VIDEO) {
            mediaFile = new File(mediaStorageDir.getPath() + File.separator + "VID_" + timeStamp + ".mp4");
        } else {
            return null;
        }

        return mediaFile;
    }

    /**
     * Process our bitmap to get correct rotation.
     */
    private final class RotateBitmapTask extends AsyncTask {
        File tempFile;
        boolean fromCamera;
        boolean frontFacing;

        private RotateBitmapTask(File tempFile, Boolean fromCamera, Boolean frontFacing) {
            this.tempFile = tempFile;
            this.fromCamera = fromCamera;
            this.frontFacing = frontFacing;
        }

        @Override
        protected Object doInBackground(Object[] objects) {

            Log.d(TAG, "RotateBitmapTask: doInBackground");
            try {
                Bitmap originalImage = BitmapFactory.decodeFile(tempFile.getAbsolutePath());
                if (fromCamera) {

                    if (mCameraId == 0 && mDeviceOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                        originalImage = rotateBitmap(originalImage, 270);
                    } else if (mCameraId == 0 && mDeviceOrientation == Configuration.ORIENTATION_PORTRAIT) {
                        originalImage = rotateBitmap(originalImage, 90);
                    } else if (mCameraId == 1 && mDeviceOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                        originalImage = rotateBitmap(originalImage, 90);
                    } else if (mCameraId == 1 && mDeviceOrientation == Configuration.ORIENTATION_PORTRAIT) {
                        originalImage = rotateBitmap(originalImage, 270);
                    }

                }

                writeBitmapToFile(originalImage, tempFile);
            } catch (Exception e) {
                Log.e(TAG, "RotateBitmapTask failed:  " + e.getMessage());
            }

            return null;
        }

        @Override
        protected void onPostExecute(Object o) {
            super.onPostExecute(o);
            handleCapturedPictureOrRecordedVideo(Uri.fromFile(tempFile), false);
        }
    }

    private void writeBitmapToFile(Bitmap bitmap, File file) {
        if (file == null) {
            return;
        }

        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    Log.e(TAG, "writeBitmapToFile - IOException: " + e.getMessage());
                }
            }

        }
    }

    private Bitmap rotateBitmap(Bitmap bitmap, int rotationDegree) {
        Log.d(TAG, "rotateBitmap: " + rotationDegree);
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegree);
        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        return rotatedBitmap;
    }

    private void handleCapturedPictureOrRecordedVideo(Uri contentUri, boolean isVideo) {
        Log.d(TAG, "handleCapturedPictureOrRecordedVideo: " + isVideo + ": " + contentUri.getPath());
        mProgressBar.setVisibility(View.GONE);
        galleryAddPic(contentUri);
        Toast.makeText(MainActivity.this, (isVideo ? "Video" : "Picture") + " added to gallery", Toast.LENGTH_SHORT).show();

        // TODO, here we could send our picture/video to our server, Cloudinary or do whatever...
        finish();
    }

    private void galleryAddPic(Uri contentUri) {
        Log.d(TAG, "galleryAddPic: " + contentUri.getPath());
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        mediaScanIntent.setData(contentUri);
        this.sendBroadcast(mediaScanIntent);
    }

}
