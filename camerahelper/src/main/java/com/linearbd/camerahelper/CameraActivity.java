package com.linearbd.camerahelper;

import android.Manifest;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

public class CameraActivity extends AppCompatActivity implements View.OnClickListener {
    private static final SparseIntArray ORIENTATION = new SparseIntArray();
    static {
        ORIENTATION.append(Surface.ROTATION_0,90);
        ORIENTATION.append(Surface.ROTATION_90,0);
        ORIENTATION.append(Surface.ROTATION_180,270);
        ORIENTATION.append(Surface.ROTATION_270,180);
    }
    private static final int REQUIRED_PERMISSION = 2000;
    private static final int ACTIVITY_START_CAMERA_APP = 0;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAIT_LOCK = 1;
    private int mState;
    private TextureView textureView;
    private Button btnTakePhoto;

    private Size previewSize;
    private String cameraId;

    private TextureView.SurfaceTextureListener surfaceTextureListener
            = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //Setup the Camera when surface Textture is Available
            Log.d("HHH","onSurfaceTextureAvailable Called");
            setupCamera(width,height);
            openCamera();

        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };



    private CameraDevice cameraDevice;
    private CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice=camera;
                    createCameraPreview();

                    //Toast.makeText(MainActivity.this, "I am in State Callback", Toast.LENGTH_SHORT).show();

                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice=null;

                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice=null;

                }
            };

    private CaptureRequest previewCaptureRequest;
    private CaptureRequest.Builder previewCaptureRequestBuilder;

    private CameraCaptureSession cameraCaptureSession;
    private CameraCaptureSession.CaptureCallback cameraCaptureSessionCallback =
            new CameraCaptureSession.CaptureCallback() {
                // Create as method here to Process Capture Result
                private void process(CaptureResult result){

                    switch (mState){
                        case STATE_PREVIEW:
                            break;

                        case STATE_WAIT_LOCK:
                            Integer autoFocusState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if(autoFocusState==CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED){
                                /*unlockFocus();
                                Toast.makeText(getApplicationContext(), "Focus Lock Successfully", Toast.LENGTH_SHORT).show();*/
                                captureStillImage();
                            }
                            break;
                    }

                }
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    // Call Process Method
                    process(result);
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    super.onCaptureFailed(session, request, failure);
                    Toast.makeText(getApplicationContext(), "Focus Lock  unSuccessful", Toast.LENGTH_SHORT).show();
                }
            };

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    // Image File
    private static File imageFile;
    private ImageReader imageReader;
    // A listener is needed
    private final ImageReader.OnImageAvailableListener imageAvailavleListener
            = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            backgroundHandler.post(new ImageSaver(reader.acquireNextImage()));

        }
    };
    private static class ImageSaver implements Runnable{
        private final Image image;

        private ImageSaver(Image image) {
            this.image = image;
        }

        @Override
        public void run() {

            ByteBuffer byteBuffer = image.getPlanes()[0].getBuffer();
            byte[] bytes= new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);

            // Create a File Input Stream
            FileOutputStream fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(imageFile.getPath());
                fileOutputStream.write(bytes);
            }catch (IOException e) {
                e.printStackTrace();
            }finally {
                image.close();
                if(fileOutputStream!=null){
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

            }

        }
    }

    private void createCameraPreview() {
        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            //CaptureRequestBuilder is initialize here
            previewCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewCaptureRequestBuilder.addTarget(previewSurface);
            //Create a Camera Sesson Here
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface,imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if(cameraDevice==null){
                                return;
                            }

                            try{
                                previewCaptureRequest=previewCaptureRequestBuilder.build();
                                cameraCaptureSession=session;
                                cameraCaptureSession.setRepeatingRequest(previewCaptureRequest,
                                        cameraCaptureSessionCallback,backgroundHandler);

                            }catch (CameraAccessException e){
                                e.printStackTrace();
                            }

                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                            Toast.makeText(CameraActivity.this, "create Camera Session Failed", Toast.LENGTH_SHORT).show();

                        }
                    },null);

        }catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    private void setupCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            for(String cameraId:cameraManager.getCameraIdList()){
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);

                if(cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
                        == CameraMetadata.LENS_FACING_FRONT){
                    continue;
                }

                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                //get Largest Image Size
                Size largestImageSize = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new Comparator<Size>() {
                            @Override
                            public int compare(Size o1, Size o2) {
                                return Long.signum(o1.getWidth()*o1.getHeight()-o2.getWidth()*o2.getHeight());
                            }
                        });

                // Initialize Image Reader Here
                imageReader = ImageReader.newInstance(largestImageSize.getWidth(),largestImageSize.getHeight(),
                        ImageFormat.JPEG,1);
                // Set the listener
                imageReader.setOnImageAvailableListener(imageAvailavleListener,backgroundHandler);
                //set Camera Preview
                previewSize = getPreviewSize(map.getOutputSizes(SurfaceTexture.class),width,height);
                //set the Camera Id
                this.cameraId=cameraId;
                Log.d("HHH",cameraId);
                return;

            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private Size getPreviewSize(Size[] mapSizes,int width,int height){
        List<Size> collectorSizes = new ArrayList<>();

        for(Size x: mapSizes){
            if(width>height){
                if(x.getWidth()>width && x.getHeight()>height){
                    collectorSizes.add(x);

                }
            }else {
                if(x.getWidth()>height && x.getHeight()>width){
                    collectorSizes.add(x);
                }
            }
        }

        if(collectorSizes.size()>0){
            return Collections.min(collectorSizes, new Comparator<Size>() {
                @Override
                public int compare(Size o1, Size o2) {
                    return Long.signum(o1.getWidth()*o1.getHeight()-o2.getWidth()*o2.getHeight());
                }
            });
        }

        return mapSizes[0];

    }

    private void openCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            Log.d("HHH","I am in openCamera");
            //noinspection MissingPermission
            cameraManager.openCamera(cameraId,stateCallback,backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if(cameraCaptureSession!=null){
            cameraCaptureSession.close();
            cameraCaptureSession=null;
        }

        if(cameraDevice!=null){
            cameraDevice.close();
            cameraDevice=null;
        }

        if(imageReader!=null){
            imageReader.close();
            imageReader=null;
        }
    }

    private void openBackgroundThread(){
        //init background Thread
        backgroundThread = new HandlerThread("Camera2Api Thread");
        backgroundThread.start();

        //init background Handler
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void closeBackgroundThread(){

        if(backgroundThread!=null){
            backgroundThread.quitSafely();

            try {
                backgroundThread.join();
                backgroundThread=null;
                backgroundHandler=null;

            }catch (InterruptedException e){
                e.printStackTrace();
            }
        }

    }

    private void takePhoto(){
        imageFile = createImageFile();
        lockFocus();
    }

    private File createImageFile() {
        File file= new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)+"/"+getString(R.string.app_name));

        if(!file.exists()){
            file.mkdirs();
        }

        File imageFile = new File(file.getAbsoluteFile(), System.currentTimeMillis()+".jpg");

        Log.d("JJJJJ",file.getAbsolutePath());

        return imageFile;

    }

    private void lockFocus(){
        try {
            mState= STATE_WAIT_LOCK;
            previewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START);

            cameraCaptureSession.capture(previewCaptureRequestBuilder.build(),
                    cameraCaptureSessionCallback,backgroundHandler);
        }catch (CameraAccessException e){
            e.printStackTrace();
        }
        // Change the state

    }

    private void unlockFocus(){
        try {
            mState= STATE_PREVIEW;
            previewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);

            cameraCaptureSession.capture(previewCaptureRequestBuilder.build(),
                    cameraCaptureSessionCallback,backgroundHandler);
        }catch (CameraAccessException e){
            e.printStackTrace();
        }
        // Change the state

    }

    private void captureStillImage(){
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());

            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            builder.set(CaptureRequest.JPEG_ORIENTATION,ORIENTATION.get(rotation));

            CameraCaptureSession.CaptureCallback cameraCaptureCallback =
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                            super.onCaptureCompleted(session, request, result);
                            Toast.makeText(getApplicationContext(), "Capture Completed", Toast.LENGTH_SHORT).show();
                            unlockFocus();

                        }
                    };

            cameraCaptureSession.capture(builder.build(),cameraCaptureCallback,null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_camera);
        initView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        requiredPermission();
    }

    @AfterPermissionGranted(REQUIRED_PERMISSION)
    private void requiredPermission() {
        String[] perms = {Manifest.permission.CAMERA,Manifest.permission.WRITE_EXTERNAL_STORAGE};
        if (EasyPermissions.hasPermissions(this, perms)) {
            // Already have permission, do the thing
            // ...
            openBackgroundThread();
            if(textureView.isAvailable()){
                setupCamera(textureView.getWidth(),textureView.getHeight());
                openCamera();

            }else {
                textureView.setSurfaceTextureListener(surfaceTextureListener);
            }

        } else {
            // Do not have permissions, request them now
            EasyPermissions.requestPermissions(this, "App need to Permission for Location",
                    REQUIRED_PERMISSION, perms);
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(
                requestCode, permissions, grantResults, this);
    }

    @Override
    protected void onPause() {
        closeCamera();
        closeBackgroundThread();

        super.onPause();
    }



    private void initView() {
        textureView = (TextureView) findViewById(R.id.texture_view);
        btnTakePhoto = (Button) findViewById(R.id.take_photo);
        btnTakePhoto.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        takePhoto();

    }
}
