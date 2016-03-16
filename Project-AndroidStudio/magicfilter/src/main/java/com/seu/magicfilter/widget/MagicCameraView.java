package com.seu.magicfilter.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.os.Environment;
import android.util.AttributeSet;

import com.seu.magicfilter.camera.CameraEngine;
import com.seu.magicfilter.camera.utils.CameraInfo;
import com.seu.magicfilter.filter.base.MagicCameraInputFilter;
import com.seu.magicfilter.filter.helper.MagicFilterType;
import com.seu.magicfilter.helper.SavePictureTask;
import com.seu.magicfilter.utils.MagicParams;
import com.seu.magicfilter.utils.OpenGlUtils;
import com.seu.magicfilter.utils.Rotation;
import com.seu.magicfilter.utils.TextureRotationUtil;
import com.seu.magicfilter.video.MediaAudioEncoder;
import com.seu.magicfilter.video.MediaEncoder;
import com.seu.magicfilter.video.MediaMuxerWrapper;
import com.seu.magicfilter.video.MediaVideoEncoder;
import com.seu.magicfilter.widget.base.MagicBaseView;

import java.io.File;
import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Created by why8222 on 2016/2/25.
 */
public class MagicCameraView extends MagicBaseView {

    private final MagicCameraInputFilter cameraInputFilter;

    private SurfaceTexture surfaceTexture;

    public MagicCameraView(Context context) {
        this(context, null);
    }

    private boolean recordingEnabled;
    private int recordingStatus;

    private static final int RECORDING_OFF = 0;
    private static final int RECORDING_ON = 1;
    private static final int RECORDING_RESUMED = 2;
    private MediaMuxerWrapper mMuxer;
    private MediaVideoEncoder mVideoEncoder;

    private File outputFile;

    public MagicCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        outputFile = new File(Environment.getExternalStorageDirectory().getPath(),"test.mp4");
        cameraInputFilter = new MagicCameraInputFilter();
        recordingStatus = -1;
        recordingEnabled = false;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        super.onSurfaceCreated(gl, config);
//        recordingEnabled = videoEncoder.isRecording();
        recordingEnabled = mMuxer != null ? mMuxer.isStarted() : true;
        if (recordingEnabled) {
            recordingStatus = RECORDING_RESUMED;
        } else {
            recordingStatus = RECORDING_OFF;
        }
        cameraInputFilter.init();
        if (textureId == OpenGlUtils.NO_TEXTURE) {
            textureId = OpenGlUtils.getExternalOESTextureID();
            if (textureId != OpenGlUtils.NO_TEXTURE) {
                surfaceTexture = new SurfaceTexture(textureId);
                surfaceTexture.setOnFrameAvailableListener(onFrameAvailableListener);
                CameraEngine.startPreview(surfaceTexture);
            }
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        super.onSurfaceChanged(gl, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        super.onDrawFrame(gl);
        if(surfaceTexture == null)
            return;
        surfaceTexture.updateTexImage();
        if (recordingEnabled) {
            switch (recordingStatus) {
                case RECORDING_OFF:
                    CameraInfo info = CameraEngine.getCameraInfo();
//                    videoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
//                            outputFile, MagicParams.videoWidth, MagicParams.videoHeight,
//                            1000000, EGL14.eglGetCurrentContext(),
//                            info));
                    startRecording();
                    mVideoEncoder.setEglContext(EGL14.eglGetCurrentContext(), textureId);
                    recordingStatus = RECORDING_ON;
                    break;
                case RECORDING_RESUMED:
//                    videoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                    recordingStatus = RECORDING_ON;
                    break;
                case RECORDING_ON:
                    break;
                default:
                    throw new RuntimeException("unknown status " + recordingStatus);
            }
        } else {
            switch (recordingStatus) {
                case RECORDING_ON:
                case RECORDING_RESUMED:
//                    videoEncoder.stopRecording();
                    stopRecording();
                    recordingStatus = RECORDING_OFF;
                    break;
                case RECORDING_OFF:
                    break;
                default:
                    throw new RuntimeException("unknown status " + recordingStatus);
            }
        }
        float[] mtx = new float[16];
        surfaceTexture.getTransformMatrix(mtx);
        cameraInputFilter.setTextureTransformMatrix(mtx);
        int id = textureId;
        if(filter == null){
            cameraInputFilter.onDrawFrame(textureId, gLCubeBuffer, gLTextureBuffer);
        }else{
            id = cameraInputFilter.onDrawToTexture(textureId);
            filter.onDrawFrame(id, gLCubeBuffer, gLTextureBuffer);
        }
//        videoEncoder.setTextureId(id);
//        videoEncoder.frameAvailable(surfaceTexture);
        if(mVideoEncoder != null) {
            mVideoEncoder.frameAvailableSoon(id,surfaceTexture);
        }

    }

    private SurfaceTexture.OnFrameAvailableListener onFrameAvailableListener = new SurfaceTexture.OnFrameAvailableListener() {

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            requestRender();
        }
    };

    @Override
    public void setFilter(MagicFilterType type) {
        super.setFilter(type);
//        videoEncoder.setFilter(type);
    }

    private void openCamera(){
        if(CameraEngine.getCamera() == null)
            CameraEngine.openCamera();
        CameraInfo info = CameraEngine.getCameraInfo();
        if(info.orientation == 90 || info.orientation == 270){
            imageWidth = info.previewHeight;
            imageHeight = info.previewWidth;
        }else{
            imageWidth = info.previewWidth;
            imageHeight = info.previewHeight;
        }
        cameraInputFilter.onInputSizeChanged(imageWidth, imageHeight);
        float[] textureCords = TextureRotationUtil.getRotation(Rotation.fromInt(info.orientation),
                info.isFront, true);
        gLTextureBuffer.clear();
        gLTextureBuffer.put(textureCords).position(0);
//        videoEncoder.setPreviewSize(imageWidth, imageHeight);   by shichao.chen

        if(surfaceTexture != null)
            CameraEngine.startPreview(surfaceTexture);
    }

    public void changeRecordingState(boolean isRecording) {
        recordingEnabled = isRecording;
    }

    protected void onFilterChanged(){
        super.onFilterChanged();
        cameraInputFilter.onDisplaySizeChanged(surfaceWidth, surfaceHeight);
        if(filter != null)
            cameraInputFilter.initCameraFrameBuffer(imageWidth, imageHeight);
        else
            cameraInputFilter.destroyFramebuffers();
    }

    public void onResume(){
        super.onResume();
        openCamera();
    }

    public void onPause(){
        super.onPause();
        CameraEngine.releaseCamera();
    }

    @Override
    public void savePicture(final SavePictureTask savePictureTask) {
        CameraEngine.takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                final Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (filter != null) {
                    queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            final Bitmap photo = OpenGlUtils.drawToBitmapByFilter(bitmap, filter,
                                    imageWidth, imageHeight);
                            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
                            filter.onInputSizeChanged(imageWidth, imageHeight);
                            if (photo != null)
                                savePictureTask.execute(photo);
                        }
                    });
                } else {
                    savePictureTask.execute(bitmap);
                }
            }
        });
    }


    /**
     * start resorcing
     * This is a sample project and call this on UI thread to avoid being complicated
     * but basically this should be called on private thread because prepareing
     * of encoder is heavy work
     */
    private void startRecording() {
//        if (DEBUG) Log.v(TAG, "startRecording:");
        try {
//            mRecordButton.setColorFilter(0xffff0000);	// turn red
            mMuxer = new MediaMuxerWrapper(".mp4");	// if you record audio only, ".m4a" is also OK.
            if (true) {
                // for video capturing
                new MediaVideoEncoder(mMuxer, mMediaEncoderListener,MagicParams.videoWidth, MagicParams.videoHeight);
            }
            if (true) {
                // for audio capturing
                new MediaAudioEncoder(mMuxer, mMediaEncoderListener);
            }
            mMuxer.prepare();
            mMuxer.startRecording();
        } catch (final IOException e) {
//            mRecordButton.setColorFilter(0);
//            Log.e(TAG, "startCapture:", e);
        }
    }

    /**
     * request stop recording
     */
    private void stopRecording() {
//        if (DEBUG) Log.v(TAG, "stopRecording:mMuxer=" + mMuxer);
//        mRecordButton.setColorFilter(0);	// return to default color
        if (mMuxer != null) {
            mMuxer.stopRecording();
            mMuxer = null;
            // you should not wait here
        }
    }

    /**
     * callback methods from encoder
     */
    private final MediaEncoder.MediaEncoderListener mMediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
        @Override
        public void onPrepared(final MediaEncoder encoder) {
//            if (DEBUG) Log.v(TAG, "onPrepared:encoder=" + encoder);
            if (encoder instanceof MediaVideoEncoder)
//                mCameraView.setVideoEncoder((MediaVideoEncoder)encoder);
                mVideoEncoder = (MediaVideoEncoder)encoder;
        }

        @Override
        public void onStopped(final MediaEncoder encoder) {
//            if (DEBUG) Log.v(TAG, "onStopped:encoder=" + encoder);
            if (encoder instanceof MediaVideoEncoder)
//                mCameraView.setVideoEncoder(null);
                mVideoEncoder = (MediaVideoEncoder)encoder;
        }
    };
}
