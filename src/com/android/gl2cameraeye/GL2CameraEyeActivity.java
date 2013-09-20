package com.android.gl2cameraeye;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

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

import android.hardware.Camera;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import android.hardware.Camera;


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
                        Camera.CameraInfo.CAMERA_FACING_FRONT ? "front" : "back"));
            }        
        
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Choose camera");        
            builder.setItems(cameraList.toArray(new CharSequence[cameraList.size()]), new DialogInterface.OnClickListener() {
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
        if (mGLView != null)
            mGLView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume ");
        if (mGLView != null)
            mGLView.onResume();
    }
    
    protected void createContextAndStartCamera(int cameraId){
        Log.d(TAG, "createContextAndStartCamera: " + cameraId);
        
        mGLView = new CamGLSurfaceView(this, cameraId);
        setContentView(mGLView);                    
    }

    // GLSurfaceView cannot be initialised before getting the camera to use.
    private GLSurfaceView mGLView = null;
}

class CamGLSurfaceView extends GLSurfaceView {
    private static final String TAG = "CamGLSurfaceView";
    CamRenderer mRenderer;
    Camera mCamera;
    private int mCameraId;

    static class CaptureCapability {
        public int mWidth = 0;
        public int mHeight = 0;
        public int mDesiredFps = 0;
    }
    CaptureCapability mCurrentCapability = null;
    
    public CamGLSurfaceView(Context context, int cameraId) {
        super(context);
        Log.d(TAG, "constructor");
        setEGLContextClientVersion(2);

        openAndConfigureCamera(cameraId);        

        mRenderer = new CamRenderer(context);
        mRenderer.setCamera(mCamera, cameraId);
        setRenderer(mRenderer);
    }

    public boolean onTouchEvent(final MotionEvent event) {
        queueEvent(new Runnable(){
                public void run() {
                mRenderer.setPosition(event.getX() / getWidth(),
                                      event.getY() / getHeight());
            }});
        return true;
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "onPause ");
        mCamera.stopPreview();
        mCamera.release();
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume ");
        openAndConfigureCamera(mCameraId);
        // Next time we are scheduled, hit the setCamera.
        queueEvent(new Runnable(){
                public void run() {
                    mRenderer.setCamera(mCamera, mCameraId);
                }});

        super.onResume();
    }

    private void openAndConfigureCamera(int cameraId){
        mCameraId = cameraId;
        Log.d(TAG, "openAndConfigureCamera ");
        Log.d(TAG, "Opening camera " + mCameraId);
        try {
            mCamera = Camera.open(mCameraId);
        } catch (RuntimeException e) {
            Log.e(TAG, "Error opening camera: " + e);
        }
        Camera.Parameters parameters = mCamera.getParameters();


        int frameRate = 60;
        int height = 480;
        int width = 640;

        //////////////////////////////////////////////////////////////////
        // Calculate fps.
        List<int[]> listFpsRange = parameters.getSupportedPreviewFpsRange();
        if (listFpsRange == null || listFpsRange.size() == 0) {
            Log.e(TAG, "allocate: no fps range found");
            return;
        }
        int frameRateInMs = frameRate * 1000;
        Iterator itFpsRange = listFpsRange.iterator();
        int[] fpsRange = (int[])itFpsRange.next();
        // Use the first range as default.
        int fpsMin = fpsRange[0];
        int fpsMax = fpsRange[1];
        int newFrameRate = (fpsMin + 999) / 1000;
        while (itFpsRange.hasNext()) {
            fpsRange = (int[])itFpsRange.next();
            Log.d(TAG, "fps range available from " + fpsRange[0] + " to " + fpsRange[1]);
            if (fpsRange[0] <= frameRateInMs &&
                    frameRateInMs <= fpsRange[1]) {
                fpsMin = fpsRange[0];
                fpsMax = fpsRange[1];
                newFrameRate = frameRate;
                //break;
            }
        }
        //fpsMin = fpsMax = 24000;
        frameRate = newFrameRate;
        Log.d(TAG, "allocate: fps set to " + fpsMin + " - " + fpsMax);

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
            Camera.Size size = (Camera.Size)itCameraSize.next();
            int diff = Math.abs(size.width - width) +
                    Math.abs(size.height - height);
            Log.d(TAG, "allocate: support resolution (" +
                    size.width + ", " + size.height + "), diff=" + diff);
            if (diff < minDiff ){
                minDiff = diff;
                matchedWidth = size.width;
                matchedHeight = size.height;
            }
        }
        if (minDiff == Integer.MAX_VALUE) {
            Log.e(TAG, "allocate: can not find a resolution whose width " +
                    "is multiple of 32");
            return;
        }
        mCurrentCapability.mWidth = matchedWidth;
        mCurrentCapability.mHeight = matchedHeight;
        Log.d(TAG, "allocate: matched width=" + matchedWidth + ", height=" + matchedHeight);

        //calculateImageFormat(matchedWidth, matchedHeight);
        int mImageFormat = ImageFormat.YV12;

        parameters.setPreviewSize(matchedWidth, matchedHeight);
        parameters.setPreviewFormat(mImageFormat);
        parameters.setPreviewFpsRange(fpsMin, fpsMax);
        mCamera.setParameters(parameters);
        //////////////////////////////////////////////////////////////////

        mCamera.setParameters(parameters);
    }
}

class CamRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {     
    public CamRenderer(Context context) {
        Log.d(TAG, "constructor ");
        mContext = context;

        mTriangleVertices = ByteBuffer.allocateDirect(mTriangleVerticesData.length
                * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTriangleVertices.put(mTriangleVerticesData).position(0);

        Matrix.setIdentityM(mSTMatrix, 0);

        mPos[0] = 0.f;
        mPos[1] = 0.f;
        mPos[2] = 0.f;        
    }

    /* The following set methods are not synchronized, so should only
     * be called within the rendering thread context. Use GLSurfaceView.queueEvent for safe access.
     */
    public void setPosition(float x, float y) {
        /* Map from screen (0,0)-(1,1) to scene coordinates */
        mPos[0] = (x*2-1)*mRatio;
        mPos[1] = (-y)*2+1;
        mPos[2] = 0.f;
    }

    public void setCamera(Camera camera, int cameraId) {
        Log.d(TAG, "setCamera ");
        mCamera = camera;
        mCameraId = cameraId;
        Camera.Size previewSize = mCamera.getParameters().getPreviewSize();
        mCameraRatio = (float)previewSize.width/previewSize.height;
    }

    public void onDrawFrame(GL10 glUnused) {
        synchronized(this) {
            if (updateSurface) {
                mSurface.updateTexImage();

                mSurface.getTransformMatrix(mSTMatrix);
                long timestamp = mSurface.getTimestamp();

                updateSurface = false;
            }
        }

        // Ignore the passed-in GL10 interface, and use the GLES20
        // class's static methods instead.
        GLES20.glClear( GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(mProgram);
        checkGlError("glUseProgram");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        checkGlError("glVertexAttribPointer maPosition");
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        checkGlError("glEnableVertexAttribArray maPositionHandle");

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
        GLES20.glVertexAttribPointer(maTextureHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
        checkGlError("glVertexAttribPointer maTextureHandle");
        GLES20.glEnableVertexAttribArray(maTextureHandle);
        checkGlError("glEnableVertexAttribArray maTextureHandle");

        // Create a rotation for the geometry.        
        float vflip = -1.0f;
        int orientation = 0;
        if (mContext != null) {
            WindowManager wm = (WindowManager)mContext.getSystemService(
                    Context.WINDOW_SERVICE);

            orientation =
                (((int)(wm.getDefaultDisplay().getRotation()) + 1) * 90) % 360;
            if (orientation==180 || orientation==0)
                orientation = (orientation + 180) % 360;
            
            mCameraInfo = new Camera.CameraInfo();
            Camera.getCameraInfo(mCameraId, mCameraInfo); 
            if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
                vflip = -1.0f;
        }       
        Matrix.setRotateM(mRotationMatrix, 0, orientation, 0, 0, vflip);
        
        Matrix.multiplyMM(mMVPMatrix, 0, mProjMatrix, 0, mRotationMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, mMVPMatrix, 0, mVMatrix, 0);        

        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);
        GLES20.glUniform1f(muCRatioHandle, mCameraRatio);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");


        //long currentTimeGlReadPixels1 = SystemClock.elapsedRealtimeNanos();
        // GLES20.glReadPixels(0, 0, 640, 480, GLES20.GL_RGBA,
        //        GLES20.GL_UNSIGNED_BYTE, mBuffer);
        // checkGlError("glReadPixels");

        //long currentTimeGlReadPixels2 = SystemClock.elapsedRealtimeNanos();
        //long elapsed_time =
        //    (currentTimeGlReadPixels2 - currentTimeGlReadPixels1) / 1000000;
        //Log.d(TAG, "ellapsed time :" + elapsed_time + "ms");
    }

    public void onSurfaceChanged(GL10 glUnused, int width, int height) {
        Log.d(TAG, "onSurfaceChanged ");
        // Ignore the passed-in GL10 interface, and use the GLES20
        // class's static methods instead.
        GLES20.glViewport(0, 0, width, height);
        mRatio = (float) width / height;
        Matrix.frustumM(mProjMatrix, 0, -mRatio, mRatio, -1, 1, 3, 7);

        // Hardcoded 4bytes per pixel for RGBA.
        //mBuffer = ByteBuffer.allocate(width * height * 4);
    }

    public void onSurfaceCreated(GL10 glUnused, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated ");
        // Ignore the passed-in GL10 interface, and use the GLES20
        // class's static methods instead.

        /* Set up alpha blending and an Android background color */
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glClearColor(0.643f, 0.776f, 0.223f, 1.0f);

        /* Set up shaders and handles to their variables */
        mProgram = createProgram(mVertexShader, mFragmentShader);
        if (mProgram == 0) {
            return;
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        checkGlError("glGetAttribLocation aPosition");
        if (maPositionHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition");
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        checkGlError("glGetAttribLocation aTextureCoord");
        if (maTextureHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord");
        }

        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        checkGlError("glGetUniformLocation uMVPMatrix");
        if (muMVPMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uMVPMatrix");
        }

        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        checkGlError("glGetUniformLocation uSTMatrix");
        if (muSTMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uSTMatrix");
        }

        muCRatioHandle = GLES20.glGetUniformLocation(mProgram, "uCRatio");
        checkGlError("glGetUniformLocation uCRatio");
        if (muCRatioHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uCRatio");
        }

        /*
         * Create our texture. This has to be done each time the
         * surface is created.
         */

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);

        mTextureID = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);
        checkGlError("glBindTexture mTextureID");

        // Can't do mipmapping with camera source
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        // Clamp to edge is the only option
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("glTexParameteri mTextureID");

        /*
         * Create the SurfaceTexture that will feed this textureID, and pass it to the camera
         */

        mSurface = new SurfaceTexture(mTextureID);
        mSurface.setOnFrameAvailableListener(this);
        try {
            mCamera.setPreviewTexture(mSurface);
            /* Start the camera */
            mCamera.startPreview();
        } catch (Exception t) {
            Log.e(TAG, "Error setting camera preview surface or starting it: " + t);
        }


        Matrix.setLookAtM(mVMatrix, 0, 0, 0, 5f, 0f, 0f, 0f, 0f, 1.0f, 0.0f);

        mLastTime = 0;

        synchronized(this) {
            updateSurface = false;
        }
    }

    static long previousTime = 0;
    synchronized public void onFrameAvailable(SurfaceTexture surface) {
        /* For simplicity, SurfaceTexture calls here when it has new
         * data available.  Call may come in from some random thread,
         * so let's be safe and use synchronize. No OpenGL calls can be done here.
         */
        long currentTime = SystemClock.elapsedRealtimeNanos();
        long elapsedTimeInMs = (currentTime- previousTime)/1000000;
        Log.d(TAG, "ellapsed time :" + elapsedTimeInMs  + "ms -> " 
              + (float)(1000/elapsedTimeInMs) + "fps");
        previousTime = currentTime;
        updateSurface = true;
    }
    
    private int loadShader(int shaderType, String source) {
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
            }
        }
        return shader;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }

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
            }
        }
        return program;
    }

    private void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, op + ": glError " + error);
            throw new RuntimeException(op + ": glError " + error);
        }
    }

    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
    private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
    private final float[] mTriangleVerticesData = {
        //  X ---- Y- Z -- U -- V
        -1.0f, -1.0f, 0, 0.f, 0.f,
         1.0f, -1.0f, 0, 1.f, 0.f,
        -1.0f,  1.0f, 0, 0.f, 1.f,
         1.0f,  1.0f, 0, 1.f, 1.f,
    };

    private FloatBuffer mTriangleVertices;

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

    private float[] mMVPMatrix = new float[16];
    private float[] mProjMatrix = new float[16];
    private float[] mVMatrix = new float[16];
    private float[] mSTMatrix = new float[16];
    private float[] mRotationMatrix = new float[16];
    
    private int mProgram;
    private int mTextureID;
    private int muMVPMatrixHandle;
    private int muSTMatrixHandle;
    private int muCRatioHandle;
    private int maPositionHandle;
    private int maTextureHandle;

    private float mRatio = 1.0f;
    private float mCameraRatio = 1.0f;
    private float[] mPos = new float[3];

    private long mLastTime;

    private SurfaceTexture mSurface;
    private Camera mCamera;
    private Camera.CameraInfo mCameraInfo = null;
    private int mCameraId;
    private boolean updateSurface = false;
    
    //private ByteBuffer mBuffer = null;

    private Context mContext;
    private static String TAG = "CamRenderer";

    // Magic key
    private static int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
}