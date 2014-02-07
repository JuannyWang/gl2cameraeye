package com.android.gl2cameraeye;

import java.io.IOException;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.EditText;
import android.content.Context;
import android.util.Log;

import android.opengl.GLES20;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;

import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import android.hardware.Camera;
import android.hardware.Camera.Face;

import java.util.concurrent.locks.ReentrantLock;

class VideoCapture implements PreviewCallback, OnFrameAvailableListener {
    static class CaptureCapability {
        public int mWidth;
        public int mHeight;
        public int mDesiredFps;
    }

    // Some devices don't support YV12 format correctly, even with JELLY_BEAN or
    // newer OS. To work around the issues on those devices, we have to request
    // NV21. Some other devices have troubles with certain capture resolutions
    // under a given one: for those, the resolution is swapped with a known
    // good. Both are supposed to be temporary hacks.
    private static class BuggyDeviceHack {
        private static class IdAndSizes {
            IdAndSizes(String model, String device, int minWidth, int minHeight) {
                mModel = model;
                mDevice = device;
                mMinWidth = minWidth;
                mMinHeight = minHeight;
            }
            public final String mModel;
            public final String mDevice;
            public final int mMinWidth;
            public final int mMinHeight;
        }
        private static final IdAndSizes s_CAPTURESIZE_BUGGY_DEVICE_LIST[] = {
            new IdAndSizes("Nexus 7", "flo", 640, 480)
        };

        private static final String[] s_COLORSPACE_BUGGY_DEVICE_LIST = {
            "SAMSUNG-SGH-I747",
            "ODROID-U2",
        };

        static void applyMinDimensions(CaptureCapability capability) {
            // NOTE: this can discard requested aspect ratio considerations.
            for (IdAndSizes buggyDevice : s_CAPTURESIZE_BUGGY_DEVICE_LIST) {
                if (buggyDevice.mModel.contentEquals(android.os.Build.MODEL) &&
                        buggyDevice.mDevice.contentEquals(android.os.Build.DEVICE)) {
                    capability.mWidth = (buggyDevice.mMinWidth > capability.mWidth)
                                        ? buggyDevice.mMinWidth
                                        : capability.mWidth;
                    capability.mHeight = (buggyDevice.mMinHeight > capability.mHeight)
                                         ? buggyDevice.mMinHeight
                                         : capability.mHeight;
                }
            }
        }

        static int getImageFormat() {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN) {
                return ImageFormat.NV21;
            }

            for (String buggyDevice : s_COLORSPACE_BUGGY_DEVICE_LIST) {
                if (buggyDevice.contentEquals(android.os.Build.MODEL)) {
                    return ImageFormat.NV21;
                }
            }
            return ImageFormat.YV12;
        }
    }

    private Camera mCamera;
    public ReentrantLock mPreviewBufferLock = new ReentrantLock();
    private int mImageFormat = ImageFormat.YV12;
    private Context mContext = null;
    // True when native code has started capture.
    private boolean mIsRunning = false;
    private long mFrameCount;

    private static final int NUM_CAPTURE_BUFFERS = 3;
    private int mExpectedFrameSize = 0;
    private int mId = 0;
    // Native callback context variable.
    private long mNativeVideoCaptureDeviceAndroid = 0;

    private int[] mGlTextures = null;
    private SurfaceTexture mSurfaceTexture = null;
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;

    private int mCameraOrientation = 0;
    private int mCameraFacing = 0;
    private int mDeviceOrientation = 0;

    CaptureCapability mCurrentCapability = null;
    private SurfaceVideoCapture mSurfaceVideoCapture = null;
    private static final String TAG = "VideoCapture";


    public static VideoCapture createVideoCapture(
            Context context, int id, long nativeVideoCaptureDeviceAndroid) {
        return new VideoCapture(context, id, nativeVideoCaptureDeviceAndroid);
    }

    public VideoCapture(
            Context context, int id, long nativeVideoCaptureDeviceAndroid) {
        mContext = context;
        mId = id;
        mNativeVideoCaptureDeviceAndroid = nativeVideoCaptureDeviceAndroid;
    }

    // Returns true on success, false otherwise.

    public boolean allocate(int width, int height, int frameRate) {
        Log.d(TAG, "allocate: requested width=" + width +
              ", height=" + height + ", frameRate=" + frameRate);
        try {
            mCamera = Camera.open(mId);
        } catch (RuntimeException ex) {
            Log.e(TAG, "allocate:Camera.open: " + ex);
            return false;
        }

        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        Camera.getCameraInfo(mId, cameraInfo);
        mCameraOrientation = cameraInfo.orientation;
        mCameraFacing = cameraInfo.facing;
        mDeviceOrientation = getDeviceOrientation();
        Log.d(TAG, "allocate: device orientation=" + mDeviceOrientation +
              ", camera orientation=" + mCameraOrientation +
              ", facing=" + mCameraFacing);

        Camera.Parameters parameters = mCamera.getParameters();

        // Calculate fps.
        List<int[]> listFpsRange = parameters.getSupportedPreviewFpsRange();
        if (listFpsRange == null || listFpsRange.size() == 0) {
            Log.e(TAG, "allocate: no fps range found");
            return false;
        }
        int frameRateInMs = frameRate * 1000;
        Iterator itFpsRange = listFpsRange.iterator();
        int[] fpsRange = (int[]) itFpsRange.next();
        // Use the first range as default.
        int fpsMin = fpsRange[0];
        int fpsMax = fpsRange[1];
        int newFrameRate = (fpsMin + 999) / 1000;
        while (itFpsRange.hasNext()) {
            fpsRange = (int[]) itFpsRange.next();
            Log.d(TAG, "allocate: support " + fpsRange[0] + "-" + fpsRange[1]);

            if (fpsRange[0] <= frameRateInMs &&
                frameRateInMs <= fpsRange[1]) {
                fpsMin = fpsRange[0];
                fpsMax = fpsRange[1];
                newFrameRate = frameRate;
                break;
            }
        }
        frameRate = newFrameRate;
        Log.d(TAG, "allocate: fps set to " + frameRate);

        mCurrentCapability = new CaptureCapability();
        mCurrentCapability.mDesiredFps = frameRate;

        // Calculate size.
        List<Camera.Size> listCameraSize =
                parameters.getSupportedPreviewSizes();
        int minDiff = Integer.MAX_VALUE;
        int matchedWidth = width;
        int matchedHeight = height;
        Iterator itCameraSize = listCameraSize.iterator();
        while (itCameraSize.hasNext()) {
            Camera.Size size = (Camera.Size) itCameraSize.next();
            int diff = Math.abs(size.width - width) +
                       Math.abs(size.height - height);
            Log.d(TAG, "allocate: supported (" +
                  size.width + ", " + size.height + "), diff=" + diff);
            // TODO(wjia): Remove this hack (forcing width to be multiple
            // of 32) by supporting stride in video frame buffer.
            // Right now, VideoCaptureController requires compact YV12
            // (i.e., with no padding).
            if (diff < minDiff && (size.width % 32 == 0)) {
                minDiff = diff;
                matchedWidth = size.width;
                matchedHeight = size.height;
            }
        }
        if (minDiff == Integer.MAX_VALUE) {
            Log.e(TAG, "allocate: can not find a resolution whose width " +
                       "is multiple of 32");
            return false;
        }
        mCurrentCapability.mWidth = matchedWidth;
        mCurrentCapability.mHeight = matchedHeight;
        // Hack to avoid certain capture resolutions under a minimum one,
        // see http://crbug.com/305294
        BuggyDeviceHack.applyMinDimensions(mCurrentCapability);

        Log.d(TAG, "allocate: matched width=" + mCurrentCapability.mWidth +
              ", height=" + mCurrentCapability.mHeight);

        mImageFormat = BuggyDeviceHack.getImageFormat();

        if (parameters.isVideoStabilizationSupported()) {
            Log.d(TAG, "Image stabilization supported, currently: "
                  + parameters.getVideoStabilization() + ", setting it.");
            parameters.setVideoStabilization(true);
        } else {
            Log.d(TAG, "Image stabilization not supported.");
        }

        parameters.setPreviewSize(mCurrentCapability.mWidth,
                                  mCurrentCapability.mHeight);
        parameters.setPreviewFormat(mImageFormat);
        parameters.setPreviewFpsRange(fpsMin, fpsMax);
        mCamera.setParameters(parameters);

        //mGlTextures = new int[1];
        //GLES20.glGenTextures(1, mGlTextures, 0);
        //// Create and allocate the special texture id and associated
        //// SurfaceTexture for video capture. Special means type EXTERNAL_OES.
        //GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mGlTextures[0]);
        //GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES,
        //        GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        //GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES,
        //        GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        //GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,
        //        GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        //GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,
        //        GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        //mSurfaceTexture = new SurfaceTexture(mGlTextures[0]);

        // Create the object that will create and own all the Surfaces and
        // Textures for the capture. We retrieve the SurfaceTexture reference
        // for plugging into the camera. Note that we cannot allocate the
        // GL_TEXTURE_EXTERNAL_OES for the camera capture until this object is
        // up and running, since it needs to create an off-screen context first.
        mSurfaceVideoCapture = new SurfaceVideoCapture(
                this,
                mContext,
                mCurrentCapability.mWidth,
                mCurrentCapability.mHeight);
        mSurfaceVideoCapture.start();
        mSurfaceTexture =
                mSurfaceVideoCapture.blockAndGetCaptureSurfaceTexture();

        try {
            mCamera.setPreviewTexture(mSurfaceTexture);
        } catch (IOException ex) {
            Log.e(TAG, "allocate, setPreviewTexture: " + ex);
            return false;
        }

        int bufSize = mCurrentCapability.mWidth *
                      mCurrentCapability.mHeight *
                      ImageFormat.getBitsPerPixel(mImageFormat) / 8;
        for (int i = 0; i < NUM_CAPTURE_BUFFERS; i++) {
            byte[] buffer = new byte[bufSize];
            mCamera.addCallbackBuffer(buffer);
        }
        mExpectedFrameSize = bufSize;

        return true;
    }


    public int queryWidth() {
        return mCurrentCapability.mWidth;
    }


    public int queryHeight() {
        return mCurrentCapability.mHeight;
    }


    public int queryFrameRate() {
        return mCurrentCapability.mDesiredFps;
    }


    public int getColorspace() {
        return 0;
    }


    public int startCapture() {
        if (mCamera == null) {
            Log.e(TAG, "startCapture: camera is null");
            return -1;
        }

        mPreviewBufferLock.lock();
        try {
            if (mIsRunning) {
                return 0;
            }
            mIsRunning = true;
        } finally {
            mPreviewBufferLock.unlock();
        }
        mSurfaceTexture.setOnFrameAvailableListener(mSurfaceVideoCapture);
        mCamera.setPreviewCallbackWithBuffer(null);
        mCamera.startPreview();
        return 0;
    }


    public int stopCapture() {
        if (mCamera == null) {
            Log.e(TAG, "stopCapture: camera is null");
            return 0;
        }

        mPreviewBufferLock.lock();
        try {
            if (!mIsRunning) {
                return 0;
            }
            mIsRunning = false;
        } finally {
            mPreviewBufferLock.unlock();
        }

        mCamera.stopPreview();
        mSurfaceTexture.setOnFrameAvailableListener(null);
        mCamera.setPreviewCallbackWithBuffer(null);
        return 0;
    }


    public void deallocate() {
        if (mCamera == null)
            return;

        stopCapture();
        try {
            mCamera.setPreviewTexture(null);
            if (mGlTextures != null)
                GLES20.glDeleteTextures(1, mGlTextures, 0);
            mCurrentCapability = null;
            mCamera.release();
            mCamera = null;
        } catch (IOException ex) {
            Log.e(TAG, "deallocate: failed to deallocate camera, " + ex);
            return;
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        mPreviewBufferLock.lock();
        try {
            if (!mIsRunning) {
                return;
            }
            if (data.length == mExpectedFrameSize) {
                int rotation = getDeviceOrientation();
                if (rotation != mDeviceOrientation) {
                    mDeviceOrientation = rotation;
                    Log.d(TAG,
                          "onPreviewFrame: device orientation=" +
                          mDeviceOrientation + ", camera orientation=" +
                          mCameraOrientation);
                }
                if (mCameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    rotation = 360 - rotation;
                }
                rotation = (mCameraOrientation + rotation) % 360;
                nativeOnFrameAvailable(mNativeVideoCaptureDeviceAndroid,
                        data, mExpectedFrameSize, rotation);
            }
        } finally {
            mPreviewBufferLock.unlock();
            if (camera != null) {
                camera.addCallbackBuffer(data);
            }
        }
    }

    // TODO(wjia): investigate whether reading from texture could give better
    // performance and frame rate.
    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
      mSurfaceTexture.updateTexImage();
      mFrameCount++;
      if ((mFrameCount % 100L) == 0)
          Log.d(TAG, "onFrameAvailable: " + mFrameCount);
    }


    private native void nativeOnFrameAvailable(
            long nativeVideoCaptureDeviceAndroid,
            byte[] data,
            int length,
            int rotation);

    public int getDeviceOrientation() {
        int orientation = 0;
        if (mContext != null) {
            WindowManager wm = (WindowManager) mContext.getSystemService(
                    Context.WINDOW_SERVICE);
            switch(wm.getDefaultDisplay().getRotation()) {
                case Surface.ROTATION_90:
                    orientation = 90;
                    break;
                case Surface.ROTATION_180:
                    orientation = 180;
                    break;
                case Surface.ROTATION_270:
                    orientation = 270;
                    break;
                case Surface.ROTATION_0:
                default:
                    orientation = 0;
                    break;
            }
        }
        return orientation;
    }
}

