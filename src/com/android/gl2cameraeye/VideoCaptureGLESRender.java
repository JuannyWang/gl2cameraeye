package com.android.gl2cameraeye;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

/**
 * Class for rendering a videocapture texture using OpenGL ES 2.0. This class
 * assumes that is being called inside an appropriate EGL rendering context. The
 * GLES GLSL shaders produce the necessary rotations and flips for the given
 * camera orientation and facing; for that the VideoCapture and Context are
 * needed on creation.
 * Usage: create an object of the class and call its init() method. Both must
 * happen in the rendering thread containing the rendering EGL context. Retrieve
 * its camera SurfaceTexture and plug it into the Camera capture code. Catch the
 * OnFrameAvailable callbacks and signal the rendering thread, then invoke the
 * render() method. When done, stop the class using shutdown().
 */
class VideoCaptureGLESRender{
    private int mWidth;
    private int mHeight;
    private VideoCapture mVideoCapture;
    private Context mContext;

    private int[] mGlTextures = null;
    private int mCaptureTextureID;
    private int mRenderTextureID;
    private SurfaceTexture mCaptureSurfaceTexture;
    private int[] mFramebuffer = null;

    private long mPreviousTimestamp;
    private ByteBuffer mPixelBuf;

    private int mProgram;
    private float[] mMVPMatrix = new float[16];
    private float[] mProjMatrix = new float[16];
    private float[] mVMatrix = new float[16];
    private float[] mSTMatrix = new float[16];
    private float[] mRotationMatrix = new float[16];

    private int muMVPMatrixHandle;
    private int muSTMatrixHandle;
    private int muCRatioHandle;
    private int maPositionHandle;
    private int maTextureHandle;
    private float[] mPos = new float[3];

    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES =
            5 * FLOAT_SIZE_BYTES;
    private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
    private FloatBuffer mTriangleVertices;
    private final float[] mTriangleVerticesData = {
            // X ---- Y- Z -- U -- V
            -1.0f, -1.0f, 0, 0.f, 0.f,
             1.0f, -1.0f, 0, 1.f, 0.f,
            -1.0f,  1.0f, 0, 0.f, 1.f,
             1.0f,  1.0f, 0, 1.f, 1.f,
    };

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

    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final String TAG = "VideoCaptureGLESRender";

    public VideoCaptureGLESRender(Context context,
                                  VideoCapture videoCapture,
                                  int width,
                                  int height) {
        mContext = context;
        mVideoCapture = videoCapture;
        mWidth = width;
        mHeight = height;
        mRenderTextureID = -1;
    }

    // Synchronous method that creates all necessary Open GL ES 2.0 variables
    // for rendering into the current context. The special Video Capture texture
    // and associated SurfaceTexture are also created.
    // If a renderTextureID is passed, an FBO is created and linked to it.
    public boolean init(int renderTextureID){
        mTriangleVertices = ByteBuffer
                .allocateDirect(
                        mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mTriangleVertices.put(mTriangleVerticesData).position(0);

        Matrix.setIdentityM(mSTMatrix, 0);

        mPos[0] = 0.f;
        mPos[1] = 0.f;
        mPos[2] = 0.f;

        // Set up alpha blending and an Android background color.
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.643f, 0.776f, 0.223f, 1.0f);

        // Set up shaders and handles to their variables.
        mProgram = createProgram(mVertexShader, mFragmentShader);
        if (mProgram == 0) {
            return false;
        }

        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        dumpGLErrorIfAny("glGetAttribLocation aPosition");
        if (maPositionHandle == -1)
            return false;
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        dumpGLErrorIfAny("glGetAttribLocation aTextureCoord");
        if (maTextureHandle == -1)
          return false;

        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        dumpGLErrorIfAny("glGetUniformLocation uMVPMatrix");
        if (muMVPMatrixHandle == -1)
            return false;

        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        dumpGLErrorIfAny("glGetUniformLocation uSTMatrix");
        if (muSTMatrixHandle == -1)
            return false;

        muCRatioHandle = GLES20.glGetUniformLocation(mProgram, "uCRatio");
        dumpGLErrorIfAny("glGetUniformLocation uCRatio");
        if (muCRatioHandle == -1)
            return false;

        Matrix.setLookAtM(mVMatrix, 0, 0, 0, 5f, 0f, 0f, 0f, 0f, 1.0f, 0.0f);

        mPixelBuf = ByteBuffer.allocateDirect(mWidth * mHeight * 4);

        if (renderTextureID != -1) {
            mRenderTextureID = renderTextureID;
            createFramebufferObjectTexture(renderTextureID);
            createFramebufferObject(renderTextureID);
        }

        return createVideoCaptureSurfaceTexture();
    }

    public boolean shutdown() {
        deleteFrameBufferObject();
        GLES20.glDeleteTextures(1, mGlTextures, 0);
        // TODO delete fbo texture
        return true;
    }

    public SurfaceTexture getCaptureSurfaceTexture() {
        return mCaptureSurfaceTexture;
    }

    public void render() {
        mCaptureSurfaceTexture.updateTexImage();
        mCaptureSurfaceTexture.getTransformMatrix(mSTMatrix);

        long timestamp = mCaptureSurfaceTexture.getTimestamp();
        Log.d(TAG, "frame received, updating texture, fps~=" +
                1000000000L / (timestamp - mPreviousTimestamp));
        mPreviousTimestamp = timestamp;

        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        dumpGLErrorIfAny("glUseProgram");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mCaptureTextureID);

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT,
                false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        dumpGLErrorIfAny("glVertexAttribPointer maPosition");
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        dumpGLErrorIfAny("glEnableVertexAttribArray maPositionHandle");

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
        GLES20.glVertexAttribPointer(maTextureHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        dumpGLErrorIfAny("glVertexAttribPointer maTextureHandle");
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        dumpGLErrorIfAny("glEnableVertexAttribArray maTextureHandle");

        // Create a rotation for the geometry.
        float vflip = -1.0f;
        int orientation = mVideoCapture.getDeviceOrientation();

        Matrix.setRotateM(mRotationMatrix, 0, orientation, 0, 0, vflip);

        Matrix.multiplyMM(mMVPMatrix, 0, mProjMatrix, 0, mRotationMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, mMVPMatrix, 0, mVMatrix, 0);

        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);
        GLES20.glUniform1f(muCRatioHandle, (float) mWidth / mHeight);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        dumpGLErrorIfAny("glDrawArrays");


        // Retrieve the pixels and dump the approximate elapsed time.
        long currentTimeGlReadPixels1 = SystemClock.elapsedRealtimeNanos();
        GLES20.glReadPixels(0, 0, mWidth, mHeight, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, mPixelBuf);
        mPixelBuf.rewind();
        long currentTimeGlReadPixels2 = SystemClock.elapsedRealtimeNanos();
        long elapsed_time =
                (currentTimeGlReadPixels2 - currentTimeGlReadPixels1) / 1000000;
        Log.d(TAG, "glReadPixels elapsed time :" + elapsed_time + "ms");
        //Log.d(TAG, "dump " + new String(mPixelBuf.array()));
    }

    // Create and allocate the special texture id and associated SurfaceTexture
    // for video capture. Special means type EXTERNAL_OES.
    private boolean createVideoCaptureSurfaceTexture() {
        mGlTextures = new int[1];
        GLES20.glGenTextures(1, mGlTextures, 0);
        mCaptureTextureID = mGlTextures[0];

        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mGlTextures[0]);
        dumpGLErrorIfAny("glBindTextures");
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        mCaptureSurfaceTexture = new SurfaceTexture(mCaptureTextureID);
        return true;
    }

    private boolean createFramebufferObjectTexture(int renderTextureID) {
        // Create and allocate a normal texture, that will be used to render the
        // capture texture id onto. This is a hack but there's an explanation in
        // createEGLContext().
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, renderTextureID);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        // The width and height here are in texel size, not in pixels.
        int size_texels = 64;
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            size_texels, size_texels, 0, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE, null);

        return true;
    }

    private boolean createFramebufferObject(int renderTextureID) {
        if (renderTextureID == -1)
            return true;
        Log.d(TAG, "createFramebufferObject");

        mFramebuffer = new int[1];
        GLES20.glGenFramebuffers(1, mFramebuffer, 0);
        dumpGLErrorIfAny("glGenFramebuffers");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer[0]);
        dumpGLErrorIfAny("glBindFramebuffer");
        // Qualcomm recommends clear after glBindFrameBuffer().
        GLES20.glClearColor(0.643f, 0.776f, 0.223f, 1.0f);

        ////////////////////////////////////////////////////////////////////////
        // Bind the texture to the generated Framebuffer Object.
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                                      GLES20.GL_COLOR_ATTACHMENT0,
                                      GLES20.GL_TEXTURE_2D,
                                      renderTextureID,
                                      0);
        dumpGLErrorIfAny("glFramebufferTexture2D");
        if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) !=
                GLES20.GL_FRAMEBUFFER_COMPLETE){
            Log.e(TAG, " Created Framebuffer and attached to texture");
        } else {
            Log.d(TAG, " Framebuffer created and attached to texture.");
        }

        return true;
    }

    private void deleteFrameBufferObject() {
        GLES20.glDeleteFramebuffers(1, mFramebuffer, 0);
    }


    private int createProgram(String vertexSource, String fragmentSource) {
        Log.d(TAG, "createProgram");
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0)
            return 0;
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0)
            return 0;

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            dumpGLErrorIfAny("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            dumpGLErrorIfAny("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            } else {
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

    private void dumpGLErrorIfAny(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR)
            Log.e(TAG, "** " + op + ": glError " + error);
    }

}
