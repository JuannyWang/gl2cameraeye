package com.android.gl2cameraeye;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.hardware.Camera;
import android.os.Bundle;
import android.widget.EditText;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import org.chromium.media.VideoCapture;

public class GL2CameraEyeActivity extends Activity {
    private Camera.CameraInfo mCameraInfo = null;
    private VideoCapture mVideoCapture = null;
    private int chosenCameraId = -1;
    private static final String TAG = "GL2CameraEyeActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate ");

        if (chosenCameraId == -1){
            List<String> cameraList = new ArrayList<String>();
            mCameraInfo = new Camera.CameraInfo();
            for (int i=0; i < Camera.getNumberOfCameras(); ++i) {
                Camera.getCameraInfo(i, mCameraInfo);
                cameraList.add( "Cam " + i + ":" + (mCameraInfo.facing ==
                        Camera.CameraInfo.CAMERA_FACING_FRONT ? "front"
                        : "back"));
            }

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
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart ");
        if (mVideoCapture != null) {
            mVideoCapture.startCapture();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause ");
        if (mVideoCapture != null) {
            mVideoCapture.stopCapture();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume ");
        if (mVideoCapture != null) {
            mVideoCapture.startCapture();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "onStop ");
        if (mVideoCapture != null) {
            mVideoCapture.deallocate();
        }
    }

    protected void createContextAndStartCamera(int cameraId){
        Log.d(TAG, "createContextAndStartCamera: " + cameraId);

        mVideoCapture = VideoCapture.createVideoCapture( this, chosenCameraId, 0);
        mVideoCapture.allocate(1280, 720, 30);

        // We need to plug the GLSurfaceView as our content view. This will make
        // our Activity view exercise the GLSurfaceView's SurfaceHolder.Callback
        // and activate it. This is KEY for GL2CameraEye.
        setContentView(mVideoCapture.getVideoCaptureGlThread());

        onStart();
    }
}

