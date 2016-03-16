package com.seu.magicfilter.video;


import android.graphics.SurfaceTexture;
import android.opengl.EGLContext;
import android.opengl.Matrix;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.seu.magicfilter.camera.CameraEngine;
import com.seu.magicfilter.camera.utils.CameraInfo;
import com.seu.magicfilter.filter.base.MagicCameraInputFilter;
import com.seu.magicfilter.filter.base.gpuimage.GPUImageFilter;
import com.seu.magicfilter.filter.helper.MagicFilterFactory;
import com.seu.magicfilter.filter.helper.MagicFilterType;
import com.seu.magicfilter.utils.Rotation;
import com.seu.magicfilter.utils.TextureRotationUtil;
import com.seu.magicfilter.video.gles.EglCore;
import com.seu.magicfilter.video.gles.WindowSurface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Helper class to draw texture to whole view on private thread
 */
public final class RenderHandler implements Runnable {
	private static final boolean DEBUG = false;	// TODO set false on release
	private static final String TAG = "RenderHandler";

	private final Object mSync = new Object();
    private EGLContext mShard_context;
    private boolean mIsRecordable;
    private Object mSurface;
	private int mTexId = -1;
	private float[] mMatrix = new float[32];

	private boolean mRequestSetEglContext;
	private boolean mRequestRelease;
	private int mRequestDraw;
	private int mWidth;
	private int mHeight;
	private static FloatBuffer gLCubeBuffer;
	private static FloatBuffer gLTextureBuffer;

	public static final RenderHandler createHandler(final String name,final int w,final int h) {
		if (DEBUG) Log.v(TAG, "createHandler:");
		CameraInfo info = CameraEngine.getCameraInfo();
		final RenderHandler handler = new RenderHandler();
		handler.mWidth = w;
		handler.mHeight = h;

		gLCubeBuffer = ByteBuffer.allocateDirect(TextureRotationUtil.CUBE.length * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
		gLCubeBuffer.put(TextureRotationUtil.CUBE).position(0);

		gLTextureBuffer = ByteBuffer.allocateDirect(TextureRotationUtil.TEXTURE_NO_ROTATION.length * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
		gLTextureBuffer.put(TextureRotationUtil.TEXTURE_NO_ROTATION).position(0);


		gLTextureBuffer.clear();
		gLTextureBuffer.put(TextureRotationUtil.getRotation(Rotation.fromInt(info.orientation),
				false, true)).position(0);

		synchronized (handler.mSync) {
			new Thread(handler, !TextUtils.isEmpty(name) ? name : TAG).start();
			try {
				handler.mSync.wait();
			} catch (final InterruptedException e) {
			}
		}

		return handler;
	}

	public final void setEglContext(final EGLContext shared_context, final int tex_id, final Object surface, final boolean isRecordable) {
		if (DEBUG) Log.i(TAG, "setEglContext:");
		if (!(surface instanceof Surface) && !(surface instanceof SurfaceTexture) && !(surface instanceof SurfaceHolder))
			throw new RuntimeException("unsupported window type:" + surface);
		synchronized (mSync) {
			if (mRequestRelease) return;
			mShard_context = shared_context;
			mTexId = tex_id;
			mSurface = surface;
			mIsRecordable = isRecordable;
			mRequestSetEglContext = true;
			Matrix.setIdentityM(mMatrix, 0);
			Matrix.setIdentityM(mMatrix, 16);
			mSync.notifyAll();
			try {
				mSync.wait();
			} catch (final InterruptedException e) {
			}
		}
	}

	public final void draw() {
		draw(mTexId, mMatrix, null);
	}

	SurfaceTexture surfaceTexture = null;
	public final void draw(final int tex_id,SurfaceTexture surfaceTexture ) {
		this.surfaceTexture = surfaceTexture;


		draw(tex_id, mMatrix, null);
	}


	public final void draw(final float[] tex_matrix) {

		draw(mTexId, tex_matrix, null);
	}

	public final void draw(final float[] tex_matrix, final float[] mvp_matrix) {
		draw(mTexId, tex_matrix, mvp_matrix);
	}

	public final void draw(final int tex_id, final float[] tex_matrix) {
		draw(tex_id, tex_matrix, null);
	}

	public final void draw(final int tex_id, final float[] tex_matrix, final float[] mvp_matrix) {
		synchronized (mSync) {
			if (mRequestRelease) return;
			mTexId = tex_id;
			if ((tex_matrix != null) && (tex_matrix.length >= 16)) {
				System.arraycopy(tex_matrix, 0, mMatrix, 0, 16);
			} else {
				Matrix.setIdentityM(mMatrix, 0);
			}
			if ((mvp_matrix != null) && (mvp_matrix.length >= 16)) {
				System.arraycopy(mvp_matrix, 0, mMatrix, 16, 16);
			} else {
				Matrix.setIdentityM(mMatrix, 16);
			}
			mRequestDraw++;
			mSync.notifyAll();
/*			try {
				mSync.wait();
			} catch (final InterruptedException e) {
			} */
		}
	}

	public boolean isValid() {
		synchronized (mSync) {
			return !(mSurface instanceof Surface) || ((Surface)mSurface).isValid();
		}
	}

	public final void release() {
		if (DEBUG) Log.i(TAG, "release:");
		synchronized (mSync) {
			if (mRequestRelease) return;
			mRequestRelease = true;
			mSync.notifyAll();
			try {
				mSync.wait();
			} catch (final InterruptedException e) {
			}
		}
	}



	//
//********************************************************************************
//********************************************************************************
	private EglCore mEgl;
	private WindowSurface mInputSurface;
	private MagicCameraInputFilter mInput;
	private GPUImageFilter filter;
//	private GLDrawer2D mDrawer;

	@Override
	public final void run() {
		if (DEBUG) Log.i(TAG, "RenderHandler thread started:");
		synchronized (mSync) {
			mRequestSetEglContext = mRequestRelease = false;
			mRequestDraw = 0;
			mSync.notifyAll();
		}
        boolean localRequestDraw;
        for (;;) {
        	synchronized (mSync) {
        		if (mRequestRelease) break;
	        	if (mRequestSetEglContext) {
	        		mRequestSetEglContext = false;
	        		internalPrepare();
	        	}
	        	localRequestDraw = mRequestDraw > 0;
	        	if (localRequestDraw) {
	        		mRequestDraw--;
//					mSync.notifyAll();
				}
        	}
        	if (localRequestDraw) {
        		if ((mEgl != null) && mTexId >= 0) {
            		mInputSurface.makeCurrent();
					// clear screen with yellow color so that you can see rendering rectangle
//					GLES20.glClearColor(1.0f, 1.0f, 0.0f, 1.0f);
//					GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
//					mDrawer.setMatrix(mMatrix, 16);
//            		mDrawer.draw(mTexId, mMatrix);
					float[] transform = new float[16];      // TODO - avoid alloc every frame
					surfaceTexture.getTransformMatrix(transform);
					mInput.setTextureTransformMatrix(transform);

					if(filter == null)
						mInput.onDrawFrame(mTexId, gLCubeBuffer, gLTextureBuffer);
					else
						filter.onDrawFrame(mTexId, gLCubeBuffer, gLTextureBuffer);


					mInputSurface.swapBuffers();//.swap();
        		}
        	} else {
        		synchronized(mSync) {
        			try {
						mSync.wait();
					} catch (final InterruptedException e) {
						break;
					}
        		}
        	}
        }
        synchronized (mSync) {
        	mRequestRelease = true;
            internalRelease();
            mSync.notifyAll();
        }
		if (DEBUG) Log.i(TAG, "RenderHandler thread finished:");
	}

	private final void internalPrepare() {
		if (DEBUG) Log.i(TAG, "internalPrepare:");
		internalRelease();
		mEgl = new EglCore(mShard_context, EglCore.FLAG_RECORDABLE);


		mInputSurface = new WindowSurface(mEgl, (Surface)mSurface, true);
//   		mInputSurface.createWindowSurface(mSurface);//.createFromSurface(mSurface);

		mInputSurface.makeCurrent();
//		mDrawer = new GLDrawer2D();

		// Create new programs and such for the new context.
		mInput = new MagicCameraInputFilter();
		mInput.init();
		filter = MagicFilterFactory.initFilters(MagicFilterType.SUNSET);
		if(filter != null){
			filter.init();
			filter.onInputSizeChanged(mWidth, mHeight);//mPreviewWidth, mPreviewHeight);
			filter.onDisplaySizeChanged(mWidth, mHeight);
		}
		mSurface = null;
		mSync.notifyAll();
	}

	private final void internalRelease() {
		if (DEBUG) Log.i(TAG, "internalRelease:");
		if (mInputSurface != null) {
			mInputSurface.release();
			mInputSurface = null;
		}
		if (mInput != null) {
			mInput.destroy();
			mInput = null;
		}
		if (mEgl != null) {
			mEgl.release();
			mEgl = null;
		}
	}

}
