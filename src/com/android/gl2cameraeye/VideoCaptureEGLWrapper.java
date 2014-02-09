package com.android.gl2cameraeye;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.opengl.GLUtils;
import android.util.Log;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

/**
 **/
class VideoCaptureEGLWrapper extends Thread
                             implements OnFrameAvailableListener {

    private int mFboRenderTextureID;
    private SurfaceTexture mRenderSurfaceTexture;
    //private Surface mRenderSurface;
    //private ImageReader mImageReader = null;
    private final int mWidth, mHeight;

    private boolean mUpdateSurface;
    private boolean mRunning;

    VideoCaptureGLESRender mVideoCaptureGLESRender = null;

    // Following two are used to make getCaptureSurfaceTexture() wait until
    // the GLThread is correctly started. Perhaps move to timeout'ed. Reconsider
    // alloc/free in constructor/destructor.
    private Object mFinishedConfiguration = new Object();
    private boolean mIsFinishedConfiguration;

    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final String TAG = "VideoCaptureEGLWrapper";

    public VideoCaptureEGLWrapper(VideoCapture videoCapture,
                               Context context,
                               int width,
                               int height) {
        Log.d(TAG, "constructor");
        mWidth = width;
        mHeight = height;
        mVideoCaptureGLESRender = new
                VideoCaptureGLESRender(context, videoCapture, width, height);

        // If -1 means use Pixel buffer, otherwise is the ID of the texture to
        // use for the FrameBuffer Object rendering.
        mFboRenderTextureID = 2;
    }

    public void finish() {
        mVideoCaptureGLESRender.shutdown();
        shutdown();
        mRunning = false;
    }

    public SurfaceTexture blockAndGetCaptureSurfaceTexture() {
        Log.d(TAG, "blockAndGetCaptureSurfaceTexture: wait for configuration " +
                "finished");
        synchronized (mFinishedConfiguration) {
            try {
                while (!mIsFinishedConfiguration)
                    mFinishedConfiguration.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, " Couldn't notify finished configuration: " + e);
            }
        }
        Log.d(TAG, " Configuration finished");
        return mVideoCaptureGLESRender.getCaptureSurfaceTexture();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
      // SurfaceTexture calls here when it has new data available. Call may
      // come in from some random thread, so let's be safe and use synchronize.
      // (Experimentally it's been seen that Thread.currentThread().toString())
      // gives "main" as the thread calling). No OpenGL calls can be done here,
      // in particular mCaptureSurfaceTexture.updateTexImage(), we need to
      // signal to the thread that owns the off-screen rendering context.
      mUpdateSurface = true;
      Log.d(TAG, "onFrameAvailable, thread " +
              Thread.currentThread().toString());
    }

    @Override
    public void run() {
        Log.d(TAG, "run");

        // Moves the current Thread into the background
        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND);

        // Need to create contexs etc _in this run_ method. DELETEME
        if (!(createEGLContext(mFboRenderTextureID) &&
              mVideoCaptureGLESRender.init(mFboRenderTextureID)))
            return;
        synchronized (this) {
            mUpdateSurface = false;
        }

        Log.d(TAG, " EGL and GLES2 OK on thread: " +
                Thread.currentThread().toString());
        synchronized (mFinishedConfiguration) {
            try {
                mFinishedConfiguration.notify();
            } catch (IllegalMonitorStateException e) {
                Log.e(TAG, "Couldn't notify finished configuration: " + e);
            }
            mIsFinishedConfiguration = true;
        }
        mRunning = true;

        //Bla bla = new Bla();
        //HandlerThread mBla = new HandlerThread("bla");
        //mBla.start();
        //mImageReader.setOnImageAvailableListener(
        //        bla, new Handler(mBla.getLooper()));
        //Bla bla = new Bla();
        //mRenderSurfaceTexture.setOnFrameAvailableListener(bla);

        while (mRunning) {
            synchronized (this) {
                if (mUpdateSurface) {
                    guardedRun();
                }
            }
        }
        Log.d(TAG, "finish run");
    }

    public void shutdown() {
        deleteEglContext();
    }

    private void guardedRun() {
        // No need to make eglContext current or bind the FBO.

        mVideoCaptureGLESRender.render();
        mUpdateSurface = false;
    }

    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////
    static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
    static final int EGL_OPENGL_ES2_BIT = 4;

    private EGL10 mEgl;
    private EGLDisplay mEglDisplay = EGL10.EGL_NO_DISPLAY;
    private EGLConfig mEglConfig;
    private EGLContext mEglContext = EGL10.EGL_NO_CONTEXT;
    private EGLSurface mEglSurface = EGL10.EGL_NO_SURFACE;

    private boolean createEGLContext(int renderTextureID) {
        Log.d(TAG, "createEGLContext");
        mEgl = (EGL10) EGLContext.getEGL();

        ////////////////////////////////////////////////////////////////////////
        // DISPLAY create-initialize
        mEglDisplay = mEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        if (mEglDisplay == EGL10.EGL_NO_DISPLAY) {
            dumpEGLError("eglGetDisplay");
            return false;
        }

        int[] version = new int[2];
        if (!mEgl.eglInitialize(mEglDisplay, version)) {
            dumpEGLError("eglInitialize");
            return false;
        }
        Log.d(TAG, " eglInitialize version " + version[0] + "." + version[1]);

        ////////////////////////////////////////////////////////////////////////
        // Config create-search-use
        int surfaceType = (renderTextureID != -1) ? EGL10.EGL_PBUFFER_BIT
                : EGL10.EGL_WINDOW_BIT;
        int[] eglConfigSpec = {
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL10.EGL_SURFACE_TYPE, surfaceType,  // Very important
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 8,  // Very important.
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
            dumpEGLError("eglChooseConfig");
            return false;
        }
        if (configsCount[0] == 0) {
            dumpEGLError("eglChooseConfig didn't find a suitable config.");
            return false;
        }
        mEglConfig = configs[0];

        ////////////////////////////////////////////////////////////////////////
        // Create the context with the previous display & config.
        int[] eglContextAttrib =
                { EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE };
        mEglContext = mEgl.eglCreateContext(mEglDisplay,
                                            mEglConfig,
                                            EGL10.EGL_NO_CONTEXT,
                                            eglContextAttrib);
        if (mEglContext == EGL10.EGL_NO_CONTEXT) {
            dumpEGLError("eglCreateContext");
            return false;
        }

        ////////////////////////////////////////////////////////////////////////
        // Surface. For Pbuffer we need its attributes, mainly width and height.
        if (renderTextureID != -1) {
          int[] eglSurfaceAttribList = {
                  EGL10.EGL_WIDTH, 1024,
                  EGL10.EGL_HEIGHT, 1024,
                  EGL10.EGL_NONE
          };
          mEglSurface = mEgl.eglCreatePbufferSurface(mEglDisplay,
                                                     mEglConfig,
                                                     eglSurfaceAttribList);
        } else {
          int[] eglSurfaceAttribList = {
                  EGL10.EGL_NONE
          };
          // NOTE(mcasas): We need a context created before we can create and
          // configure the textures. But for the context to be created, a
          // Surface, SurfaceTexture, SurfaceHolder or SurfaceView is needed, by
          // preference the second, and to create a SurfaceTexture, a texture id
          // is needed! Circular dependency!
          // Hack: hardcode texture id to number 2, create a SurfaceTexture with
          // that id, and use it to create a context.
          // Alternative idea: Make a context with a Pbuffer and change it to
          // FBO-WindowSurface afterwards.
          mRenderSurfaceTexture = new SurfaceTexture(renderTextureID);
          mRenderSurfaceTexture.setDefaultBufferSize(1024, 1024);
          mEglSurface = mEgl.eglCreateWindowSurface(mEglDisplay,
                                                    mEglConfig,
                                                    mRenderSurfaceTexture,
                                                    eglSurfaceAttribList);
        }

        //mImageReader = ImageReader.newInstance(640,480,ImageFormat.YV12,2);
        //mRenderSurface = mImageReader.getSurface();
        //mEglSurface = mEgl.eglCreateWindowSurface(mEglDisplay,
        //                                          mEglConfig,
        //                                          mRenderSurface,
        //                                          eglSurfaceAttribList);

        if (mEglSurface == null || mEglSurface == EGL10.EGL_NO_SURFACE) {
            dumpEGLError("createPbufferSurface");
            return false;
        }

        ////////////////////////////////////////////////////////////////////////
        // Finally make the context current.
        if (!mEgl.eglMakeCurrent(mEglDisplay,
                                 mEglSurface,
                                 mEglSurface,
                                 mEglContext)) {
            dumpEGLError("eglMakeCurrent");
            return false;
        }

        if (!mEglContext.equals(mEgl.eglGetCurrentContext()) ||
            !mEglSurface.equals(mEgl.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
            dumpEGLError("eglMakeCurrent");
            return false;
        }

        return true;
    }

    private void deleteEglContext() {
        if (mEglDisplay != EGL10.EGL_NO_DISPLAY) {
            mEgl.eglDestroySurface(mEglDisplay, mEglSurface);
            mEgl.eglDestroyContext(mEglDisplay, mEglContext);
            mEgl.eglTerminate(mEglDisplay);
        }
        mEglDisplay = EGL10.EGL_NO_DISPLAY;
        mEglContext = EGL10.EGL_NO_CONTEXT;
        mEglSurface = EGL10.EGL_NO_SURFACE;
    }

    private void dumpEGLError(String op) {
        Log.e(TAG, op + " :" + GLUtils.getEGLErrorString(mEgl.eglGetError()));
    }
}

