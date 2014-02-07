package com.android.gl2cameraeye;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.DialogInterface;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class GL2CameraEyeActivity extends Activity {
    private static final String TAG = "GL2CameraEyeActivity";
    private Camera.CameraInfo mCameraInfo = null;
    private int chosenCameraId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate " + chosenCameraId);

        if (mVideoCapture == null){
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
        if (mVideoCapture != null) {
            mVideoCapture.stopCapture();
            mVideoCapture.deallocate();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume ");
        if (mVideoCapture != null)
            mVideoCapture.startCapture();
    }

    protected void createContextAndStartCamera(int cameraId){
        Log.d(TAG, "createContextAndStartCamera: " + cameraId);
        mVideoCapture = VideoCapture.createVideoCapture(this, cameraId, 0);

        mVideoCapture.allocate(640,480,30);
        mVideoCapture.startCapture();
    }

    private VideoCapture mVideoCapture = null;
}
