// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.media;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLSurfaceView;
import android.util.Log;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/** VideoCaptureGlSurfaceView is a special type of GLSurfaceView: it provides an
 * EGL context allowing off-screen rendering on an Pixel Buffer (pbuffer). For
 * that, it implements its own EglConfigChooser(), EglContextFactory() and
 * EglWindowSurfaceFactory().
 **/
class VideoCaptureGlSurfaceView extends GLSurfaceView {
    private static int mWidth, mHeight;
    private VideoCaptureGlRender mVideoCaptureGlRender = null;
    private static int mFboRenderTextureID;
    private static SurfaceTexture mRenderSurfaceTexture = null;
    private static final String TAG = "VideoCaptureGlSurfaceView";

    public VideoCaptureGlSurfaceView(Context context,
            VideoCaptureGlRender.OnCapturedFrameListener frameListener,
            Camera camera,
            int width,
            int height) {
        super(context);
        Log.d(TAG, "constructor");
        mWidth = width;
        mHeight = height;

        // All of these methods must be called before setRenderer().
        setEGLConfigChooser(new EglConfigChooser());
        setEGLContextFactory(new EglContextFactory());
        setEGLWindowSurfaceFactory(new EglWindowSurfaceFactory());
        setDebugFlags(DEBUG_CHECK_GL_ERROR | DEBUG_LOG_GL_CALLS);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);

        // mFboRenderTextureID == -1 means use Pixel buffer, otherwise is the
        // ID of the texture to use for the FrameBuffer Object rendering.
        mFboRenderTextureID = -1;
        mVideoCaptureGlRender = new VideoCaptureGlRender(context,
                frameListener,
                camera,
                this,
                mWidth,
                mHeight,
                mFboRenderTextureID);
        setRenderer(mVideoCaptureGlRender);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
        // OnPause() is a bit like killing: we should destrou the renderer and
        // tear off everything. Correspondingly, onResume should create the
        // whole shebang.
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        // See comments on onPause();
    }

    ///////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////
    ///////////////////////////////////////////////////////////////////////////

    private static class EglConfigChooser implements
            GLSurfaceView.EGLConfigChooser {
        static final int EGL_OPENGL_ES2_BIT = 4;

        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            Log.d(TAG, "chooseConfig " +
                    ((mFboRenderTextureID != -1) ? "FBO" : "Window"));

            int surfaceType = (mFboRenderTextureID != -1) ?
                    EGL10.EGL_WINDOW_BIT : EGL10.EGL_WINDOW_BIT;  //(GL2CameraEye)
            int[] eglConfigSpec = {
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_SURFACE_TYPE, surfaceType,  // Very important
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,  // Very important.
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_STENCIL_SIZE, 0,
                    EGL10.EGL_NONE
            };

            int[] configsCount = new int[1];
            EGLConfig[] configs = new EGLConfig[1];
            if (!egl.eglChooseConfig(display,
                    eglConfigSpec,
                    configs,
                    1,
                    configsCount)) {
                return null;
            }
            if (configsCount[0] == 0)
                return null;
            return configs[0];
        }
    }

    private static class EglContextFactory implements
            GLSurfaceView.EGLContextFactory {
        static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;

        public EGLContext createContext(EGL10 egl,
                EGLDisplay display,
                EGLConfig eglConfig) {
            Log.d(TAG, "createContext");
            int[] eglContextAttrib =
                { EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE };
            EGLContext context = egl.eglCreateContext(display,
                    eglConfig,
                    EGL10.EGL_NO_CONTEXT,
                    eglContextAttrib);
            if (context == EGL10.EGL_NO_CONTEXT)
                return null;
            return context;

        }

        public void destroyContext(EGL10 egl,
                EGLDisplay display,
                EGLContext context) {
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            if (display != EGL10.EGL_NO_DISPLAY)
                egl.eglDestroyContext(display, context);
        }
    }

    private static class EglWindowSurfaceFactory implements
            GLSurfaceView.EGLWindowSurfaceFactory {

        public EGLSurface createWindowSurface(EGL10 egl, EGLDisplay display,
                EGLConfig config, Object nativeWindow) {
            Log.d(TAG, "createWindowSurface");
            if (mFboRenderTextureID == -1) {

                //int width_texels = Integer.highestOneBit(mWidth) << 1; // (GL2CameraEye)
                //int height_texels = Integer.highestOneBit(mHeight) << 1;
                int[] eglSurfaceAttribList = {
                        //EGL10.EGL_WIDTH, width_texels,         (GL2CameraEye)
                        //EGL10.EGL_HEIGHT, height_texels,
                        EGL10.EGL_NONE
                };
                //return egl.eglCreatePbufferSurface(            (GL2CameraEye)
                //        display, config, eglSurfaceAttribList);
                return egl.eglCreateWindowSurface(
                        display, config, nativeWindow, eglSurfaceAttribList);
            } else {
                int[] eglSurfaceAttribList = {
                        EGL10.EGL_NONE
                };
                // NOTE(mcasas): We need a context created before we can create
                // and configure the textures. But for the context to be
                // created, a Surface, SurfaceTexture, SurfaceHolder or
                // SurfaceView/TextureView is needed, by preference the second,
                // and to create a SurfaceTexture, a texture id is needed!
                // Circular dependency!
                // Hack: hardcode texture id to number |mFboRenderTextureID|,
                // create a SurfaceTexture with that id, and use it to create a
                // context.
                mRenderSurfaceTexture = new SurfaceTexture(mFboRenderTextureID);
                mRenderSurfaceTexture.setDefaultBufferSize(1024, 1024);
                return egl.eglCreateWindowSurface(display,
                        config,
                        mRenderSurfaceTexture,
                        eglSurfaceAttribList);
            }
            // GLSurfaceView will do a check and an eglMakeCurrent() after this.
        }

        public void destroySurface(EGL10 egl,
                EGLDisplay display,
                EGLSurface surface) {
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            egl.eglDestroySurface(display, surface);
        }
    }

}
