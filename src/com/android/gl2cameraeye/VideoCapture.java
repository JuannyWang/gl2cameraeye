// Copyright 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.media;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.opengl.GLSurfaceView;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

//import org.chromium.base.CalledByNative;  (GL2CameraEye)
//import org.chromium.base.JNINamespace;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** This class implements the listener interface for receiving copies of preview
 * frames from the camera, plus a series of methods to manipulate camera and its
 * capture from the C++ side. Objects of this class are created via
 * createVideoCapture() and are explicitly owned by the creator. All methods
 * are invoked by this owner, including the callback OnPreviewFrame().
 **/
//@JNINamespace("media")
public class VideoCapture implements PreviewCallback {
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

    private static final int NUM_CAPTURE_BUFFERS = 3;
    private int mExpectedFrameSize = 0;
    private int mId = 0;
    // Native callback context variable.
    private long mNativeVideoCaptureDeviceAndroid = 0;

    private int mCameraOrientation = 0;
    private int mCameraFacing = 0;
    private int mDeviceOrientation = 0;

    CaptureCapability mCurrentCapability = null;
    private VideoCaptureGlThread mVideoCaptureGlThread = null;
    private Looper mLooper = null;
    private static final String TAG = "VideoCapture";

    //@CalledByNative
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
    //@CalledByNative
    public boolean allocate(int width, int height, int frameRate) {
        Log.d(TAG, "allocate: requested (" + width + "x" + height + ")@" +
                 frameRate + "fps");
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
        Log.d(TAG, "allocate: orientation dev=" + mDeviceOrientation +
                  ", cam=" + mCameraOrientation + ", facing=" + mCameraFacing);

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
            Log.d(TAG, "allocate: support resolution (" +
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
            Log.e(TAG, "allocate: can not find a multiple-of-32 resolution");
            return false;
        }
        mCurrentCapability.mWidth = matchedWidth;
        mCurrentCapability.mHeight = matchedHeight;
        // Hack to avoid certain capture resolutions under a minimum one,
        // see http://crbug.com/305294
        BuggyDeviceHack.applyMinDimensions(mCurrentCapability);

        Log.d(TAG, "allocate: matched (" + mCurrentCapability.mWidth + "x" +
                  mCurrentCapability.mHeight + ")");

        mImageFormat = BuggyDeviceHack.getImageFormat();

        if (parameters.isVideoStabilizationSupported()) {
            Log.d(TAG, "Image stabilization supported, currently: "
                  + parameters.getVideoStabilization() + ", setting it.");
            parameters.setVideoStabilization(true);
        } else {
            Log.d(TAG, "Image stabilization not supported.");
        }

        // Allocate a VideoCaptureGlThread, that will create an off-screen
        // context and an associated GLThread. It will also create the GLES GLSL
        // renderer to capture from the camera. Do not create here the special
        // texture (GL_TEXTURE_EXTERNAL_OES) id and SurfaceTexture for plugging
        // into the Camera, since these need to be owned by the thread owning
        // the Egl context (unless the contexts are shared correctly).Finally,
        // Looper.prepare() must have been called before creating the
        // VideoCaptureGlThread.
        if (Looper.myLooper() == null) {
            //Looper.prepare();               (GL2CameraEye)
        }
        mVideoCaptureGlThread = new VideoCaptureGlThread(mContext,
                this,
                mCamera,
                mCurrentCapability.mWidth,
                mCurrentCapability.mHeight);
        mLooper = Looper.myLooper();

        parameters.setPreviewSize(mCurrentCapability.mWidth,
                                  mCurrentCapability.mHeight);
        parameters.setPreviewFormat(mImageFormat);
        parameters.setPreviewFpsRange(fpsMin, fpsMax);
        mCamera.setParameters(parameters);

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

    //@CalledByNative
    public int queryWidth() {
        return mCurrentCapability.mWidth;
    }

    //@CalledByNative
    public int queryHeight() {
        return mCurrentCapability.mHeight;
    }

    //@CalledByNative
    public int queryFrameRate() {
        return mCurrentCapability.mDesiredFps;
    }

    //@CalledByNative
    public int getColorspace() {
        return ImageFormat.YV12;
/*      switch (mImageFormat) {                                   (GL2CameraEye)
            case ImageFormat.YV12:
                return AndroidImageFormatList.ANDROID_IMAGEFORMAT_YV12;
            case ImageFormat.NV21:
                return AndroidImageFormatList.ANDROID_IMAGEFORMAT_NV21;
            case ImageFormat.UNKNOWN:
            default:
                return AndroidImageFormatList.ANDROID_IMAGEFORMAT_UNKNOWN;
        }
*/
    }

    public GLSurfaceView getVideoCaptureGlThread() {  // (GL2CameraEye)
        return mVideoCaptureGlThread;
    }

    //@CalledByNative
    public int startCapture() {
        Log.d(TAG, "startCapture");
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
        mCamera.setPreviewCallbackWithBuffer(null);
        mCamera.startPreview();

        // Someone needs to notify the |mVideoCaptureGlThread| about underlying
        // surface being ready. Since this is an off-screen rendering, we
        // assume everything is ready right now.
        // Removed for (GL2CameraEye), this is exercised from the Activity.
        //mVideoCaptureGlThread.surfaceCreated(this);
        //mVideoCaptureGlThread.surfaceChanged(this,
        //        0,
        //        mCurrentCapability.mWidth,
        //        mCurrentCapability.mHeight);

        return 0;
    }

    //@CalledByNative
    public int stopCapture() {
        Log.d(TAG, "stopCapture");
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
        mCamera.setPreviewCallbackWithBuffer(null);
        return 0;
    }

    //@CalledByNative
    public void deallocate() {
        Log.d(TAG, "deallocate");
        if (mCamera == null)
            return;

        stopCapture();
        mCurrentCapability = null;
        mCamera.release();
        mCamera = null;
        mVideoCaptureGlThread.onPause();
        //mLooper.quit();  // Don't quit if we're the main loop (GL2CameraEye).
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
    // performance and frame rate, using onFrameAvailable().

    public void onCaptureFrameAsBuffer(byte[] data, int data_size) {
        mPreviewBufferLock.lock();
        try {  // Nobody receives the buffer (GL2CameraEye)
            //nativeOnFrameAvailable(
            //        mNativeVideoCaptureDeviceAndroid, data, data_size, 0);
        } finally {
            mPreviewBufferLock.unlock();
        }
    }

    private static class ChromiumCameraInfo {
        private final int mId;
        private final Camera.CameraInfo mCameraInfo;

        private ChromiumCameraInfo(int index) {
            mId = index;
            mCameraInfo = new Camera.CameraInfo();
            Camera.getCameraInfo(index, mCameraInfo);
        }

        //@CalledByNative("ChromiumCameraInfo")
        private static int getNumberOfCameras() {
            return Camera.getNumberOfCameras();
        }

        //@CalledByNative("ChromiumCameraInfo")
        private static ChromiumCameraInfo getAt(int index) {
            return new ChromiumCameraInfo(index);
        }

        //@CalledByNative("ChromiumCameraInfo")
        private int getId() {
            return mId;
        }

        //@CalledByNative("ChromiumCameraInfo")
        private String getDeviceName() {
            return  "camera " + mId + ", facing " +
                    (mCameraInfo.facing ==
                     Camera.CameraInfo.CAMERA_FACING_FRONT ? "front" : "back");
        }

        //@CalledByNative("ChromiumCameraInfo")
        private int getOrientation() {
            return mCameraInfo.orientation;
        }
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
