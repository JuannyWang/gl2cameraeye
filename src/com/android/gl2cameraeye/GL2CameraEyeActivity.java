package com.android.gl2cameraeye;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.EditText;
import android.content.Context;
import android.util.Log;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
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


public class GL2CameraEyeActivity extends Activity {
    private static final String TAG = "GL2CameraEyeActivity";
    private Camera.CameraInfo mCameraInfo = null;
    private int chosenCameraId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "constructor ");

        if (chosenCameraId == -1){
            List<String> cameraList = new ArrayList<String>();
            mCameraInfo = new Camera.CameraInfo();
            for (int i=0; i < Camera.getNumberOfCameras(); ++i) {
                Camera.getCameraInfo(i, mCameraInfo);
                cameraList.add( "Cam " + i + ":" + (mCameraInfo.facing ==
                        Camera.CameraInfo.CAMERA_FACING_FRONT ? "front"
                        : "back"));
            }
            cameraList.add( "Depth");

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Choose camera");
            builder.setItems(cameraList.toArray(
                    new CharSequence[cameraList.size()]),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            // The 'which' argument contains the index position
                            // of the selected item
                            chosenCameraId = which;
                            createContextAndStartCamera(chosenCameraId);
                        }
                    });
            AlertDialog alert = builder.create();
            alert.show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause ");
        //if (mglview != null)
        //    mGLView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume ");
        //if (mGLView != null)
        //    mGLView.onResume();
    }

    protected void createContextAndStartCamera(int cameraId){
        Log.d(TAG, "createContextAndStartCamera: " + cameraId);
        mVideoCapture = VideoCapture.createVideoCapture(this, cameraId, 0);

        mVideoCapture.allocate(1280,720,30);
        mVideoCapture.startCapture();
    }

    private VideoCapture mVideoCapture = null;
}

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
    private byte[] mColorPlane = null;
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

        try {
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

            // This needs to be done BEFORE dealing with the SurfaceTexture.
            if (!makeMeAnEglContextBaby())
                return false;
            if (CompileAndLoadGles20Shaders()==false)
                return false;

            // Set SurfaceTexture.
            mGlTextures = new int[1];
            // Generate one texture pointer and bind it as an external texture.
            GLES20.glGenTextures(1, mGlTextures, 0);
            GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mGlTextures[0]);
            // No mip-mapping with camera source.
            GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            // Clamp to edge is only option.
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            mSurfaceTexture = new SurfaceTexture(mGlTextures[0]);
            mSurfaceTexture.setOnFrameAvailableListener(null);

            mCamera.setPreviewTexture(mSurfaceTexture);

            int bufSize = mCurrentCapability.mWidth *
                          mCurrentCapability.mHeight *
                          ImageFormat.getBitsPerPixel(mImageFormat) / 8;
            for (int i = 0; i < NUM_CAPTURE_BUFFERS; i++) {
                byte[] buffer = new byte[bufSize];
                mCamera.addCallbackBuffer(buffer);
            }
            mExpectedFrameSize = bufSize;
        } catch (IOException ex) {
            Log.e(TAG, "allocate: " + ex);
            return false;
        }

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
        mSurfaceTexture.setOnFrameAvailableListener(this);
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

    private int getDeviceOrientation() {
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

    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////


    boolean CompileAndLoadGles20Shaders() {

        // Set up alpha blending and an Android background color.
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.643f, 0.776f, 0.223f, 1.0f);

        // Set up shaders and handles to their variables.
        mProgram = createProgram(mVertexShader, mFragmentShader);
        if (mProgram == 0) {
            return false;
        }
        return true;
    }


    private int mProgram;
    private final String mVertexShader =
        "uniform mat4 uMVPMatrix;\n" +
        "uniform mat4 uSTMatrix;\n" +
        "uniform float uCRatio;\n" +
        "attribute vec4 aPosition;\n" +
        "attribute vec4 aTextureCoord;\n" +
        "varying vec2 vTextureCoord;\n" +
        "void main() {\n" +
        "  vec4 scaledPos = aPosition;\n" +
        "  scaledPos.x = scaledPos.x * uCRatio;\n" +
        "  gl_Position = uMVPMatrix * scaledPos;\n" +
        "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
        "}\n";
    private final String mFragmentShader =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTextureCoord;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
        "}\n";


    private int createProgram(String vertexSource, String fragmentSource) {
        Log.d(TAG, "createProgram");
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0)
            return 0;
        Log.d(TAG, "createProgram");
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0)
            return 0;

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }  else {
                Log.d(TAG, GLES20.glGetProgramInfoLog(program));
            }
        }
        return program;
    }

    private int loadShader(int shaderType, String source) {
        Log.d(TAG, "loadShader " + shaderType);
        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            } else {
                Log.d(TAG, GLES20.glGetShaderInfoLog(shader));
            }
        } else {
            Log.e(TAG, "Could not create shader " + shaderType + ":");
        }
        return shader;
    }

    private void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, op + ": glError " + error);
            throw new RuntimeException(op + ": glError " + error);
        }
    }






    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////

    static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
    static final int EGL_OPENGL_ES2_BIT = 4;

    private volatile boolean mFinished;

    private EGL10 mEgl;
    private EGLDisplay mEglDisplay;
    private EGLConfig mEglConfig;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;



    private boolean makeMeAnEglContextBaby() {
        mEgl = (EGL10) EGLContext.getEGL();

        ////////////////////////////////////////////////////////////////////////
        // DISPLAY create-initialize
        mEglDisplay = mEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL10.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed: " + GLUtils.getEGLErrorString(mEgl.eglGetError()) );
            return false;
        }

        int[] version = new int[2];
        if (!mEgl.eglInitialize(mEglDisplay, version)) {
            Log.e(TAG, "eglInitialize failed: " + GLUtils.getEGLErrorString(mEgl.eglGetError()) );
            return false;
        }
        Log.d(TAG, "eglInitialize " + version[0] + "." + version[1]);

        ////////////////////////////////////////////////////////////////////////
        // Config create-search-use
        int[] eglConfigSpec = {
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,  // Very important
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 0,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_NONE
        };
        int[] configsCount = new int[1];
        EGLConfig[] configs = new EGLConfig[1];
        if (!mEgl.eglChooseConfig(mEglDisplay, 
                                  eglConfigSpec, 
                                  configs, 
                                  1, 
                                  configsCount)) {
            Log.e(TAG, "eglChooseConfig failed: " + GLUtils.getEGLErrorString(mEgl.eglGetError()) );
            return false;
        }
        if (configsCount[0] == 0) {
            Log.e(TAG, "eglChooseConfig didn't find a suitable config.");
            return false;
        }
        mEglConfig = configs[0];

        ////////////////////////////////////////////////////////////////////////
        // Create the context with the previous display & config.
        int[] eglContextAttrib = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE };
        mEglContext = mEgl.eglCreateContext(mEglDisplay,
                                            mEglConfig,
                                            EGL10.EGL_NO_CONTEXT,
                                            eglContextAttrib);
        if (mEglContext == EGL10.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed: " + GLUtils.getEGLErrorString(mEgl.eglGetError()) );
            return false;
        }

        ////////////////////////////////////////////////////////////////////////
        // Surface. For this we need its attributes, mainly width and height.
        int[] eglSurfaceAttribList = {EGL10.EGL_WIDTH, 640,
                                      EGL10.EGL_HEIGHT, 480,
                                      EGL10.EGL_NONE
        };
        //mEglSurface = mEgl.eglCreatePbufferSurface(mEglDisplay, 
        //                                          mEglConfig, 
        //                                          eglSurfaceAttribList);
        SurfaceTexture sft = new SurfaceTexture(1);
        sft.setDefaultBufferSize(640, 480);
        mEglSurface = mEgl.eglCreateWindowSurface(mEglDisplay, 
                                                  mEglConfig, 
                                                  sft,
                                                  eglSurfaceAttribList);

        if (mEglSurface == null || mEglSurface == EGL10.EGL_NO_SURFACE) {
            Log.e(TAG, "createPbufferSurface failed: " + GLUtils.getEGLErrorString(mEgl.eglGetError()) );
            return false;
        }

        ////////////////////////////////////////////////////////////////////////
        // Finally make the context current.
        if (!mEgl.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            Log.e(TAG, "eglMakeCurrent failed: " + GLUtils.getEGLErrorString(mEgl.eglGetError()) );
            return false;
        }

        if (!mEglContext.equals(mEgl.eglGetCurrentContext()) ||
            !mEglSurface.equals(mEgl.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
            Log.e(TAG, "eglMakeCurrent failed: " + GLUtils.getEGLErrorString(mEgl.eglGetError()) );
            return false;
        }

        return true;
    }

}
