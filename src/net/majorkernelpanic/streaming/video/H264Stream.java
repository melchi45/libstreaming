/*
 * Copyright (C) 2011-2013 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of Spydroid (http://code.google.com/p/spydroid-ipcamera/)
 * 
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package net.majorkernelpanic.streaming.video;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import net.majorkernelpanic.streaming.mp4.MP4Config;
import net.majorkernelpanic.streaming.rtp.H264Packetizer;
import net.majorkernelpanic.streaming.rtp.MediaCodecInputStream;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;

/**
 * A class for streaming H.264 from the camera of an android device using RTP. 
 * Call {@link #setDestinationAddress(java.net.InetAddress)}, {@link #setDestinationPorts(int)}, 
 * {@link #setVideoSize(int, int)}, {@link #setVideoFramerate(int)} and {@link #setVideoEncodingBitrate(int)} and you're good to go.
 * You can then call {@link #start()}.
 * Call {@link #stop()} to stop the stream.
 */
public class H264Stream extends VideoStream {

	public final static String TAG = "H264Stream";

	private SharedPreferences mSettings = null;
	private Semaphore mLock = new Semaphore(0);

	/**
	 * Constructs the H.264 stream.
	 * Uses CAMERA_FACING_BACK by default.
	 * @throws IOException
	 */
	public H264Stream() throws IOException {
		this(CameraInfo.CAMERA_FACING_BACK);
	}	

	/**
	 * Constructs the H.264 stream.
	 * @param cameraId Can be either CameraInfo.CAMERA_FACING_BACK or CameraInfo.CAMERA_FACING_FRONT
	 * @throws IOException
	 */
	public H264Stream(int cameraId) throws IOException {
		super(cameraId);
		setVideoEncoder(MediaRecorder.VideoEncoder.H264);
		mPacketizer = new H264Packetizer();
	}

	/**
	 * Some data (SPS and PPS params) needs to be stored when {@link #generateSessionDescription()} is called 
	 * @param prefs The SharedPreferences that will be used to save SPS and PPS parameters
	 */
	public void setPreferences(SharedPreferences prefs) {
		mSettings = prefs;
	}

	/**
	 * Returns a description of the stream using SDP. It can then be included in an SDP file.
	 * Will fail if called when streaming.
	 */
	public synchronized  String generateSessionDescription() throws IllegalStateException, IOException {
		MP4Config config = testH264();

		return "m=video "+String.valueOf(getDestinationPorts()[0])+" RTP/AVP 96\r\n" +
		"a=rtpmap:96 H264/90000\r\n" +
		"a=fmtp:96 packetization-mode=1;profile-level-id="+config.getProfileLevel()+";sprop-parameter-sets="+config.getB64SPS()+","+config.getB64PPS()+";\r\n";
	}	

	/**
	 * Starts the stream.
	 * This will also open the camera and dispay the preview if {@link #startPreview()} has not aready been called.
	 */
	public synchronized void start() throws IllegalStateException, IOException {
		MP4Config config = testH264();
		byte[] pps = Base64.decode(config.getB64PPS(), Base64.NO_WRAP);
		byte[] sps = Base64.decode(config.getB64SPS(), Base64.NO_WRAP);
		((H264Packetizer)mPacketizer).setStreamParameters(pps, sps);
		super.start();
	}

	// Should not be called by the UI thread
	private MP4Config testH264() throws IllegalStateException, IOException {
		if ((mMode&MODE_MEDIACODEC_API)!=0) return testMediaCodecAPI();
		else return testMediaRecorderAPI();
	}

	// Should not be called by the UI thread
	@SuppressLint("NewApi")
	private MP4Config testMediaCodecAPI() throws RuntimeException, IOException {
		final Semaphore lock = new Semaphore(0);
		byte[] sps = null, pps = null;
		String prefix = "h264-mc-"+mEncoderName+"-";

		if (mSettings != null) {
			if (mSettings.contains(prefix+mQuality.framerate+","+mQuality.resX+","+mQuality.resY)) {
				String[] s = mSettings.getString(prefix+mQuality.framerate+","+mQuality.resX+","+mQuality.resY, "").split(",");
				//mActualFramerate = mSettings.getInt(prefix+"act-"+mQuality.framerate, mQuality.framerate);
				//mCorrectedFramerate = mSettings.getInt(prefix+"cor-"+mQuality.framerate, mQuality.framerate);
				return new MP4Config(s[0],s[1],s[2]);
			}
		}

		// Save flash state & set it to false so that led remains off while testing h264
		boolean savedFlashState = mFlashState;
		mFlashState = false;

		boolean cameraOpen = mCamera!=null;
		createCamera();

		// Starts the preview if needed
		if (!mPreviewStarted) {
			try {
				mCamera.startPreview();
				mPreviewStarted = true;
			} catch (RuntimeException e) {
				destroyCamera();
				throw e;
			}
		}

		try {
			mMediaCodec = MediaCodec.createByCodecName(mEncoderName);

			final ColorFormatTranslator convertor = new ColorFormatTranslator(ImageFormat.YV12,mEncoderColorFormat,mQuality.resX,mQuality.resY);
			final byte[][] buffers = new byte[10][];
			for (int i=0;i<10;i++) {
				buffers[i] = new byte[convertor.getBufferSize()];
				mCamera.addCallbackBuffer(buffers[i]);
			}
			Log.e(TAG, "BFS: "+convertor.getBufferSize());

			MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", mQuality.resX, mQuality.resY);
			mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, mQuality.bitrate);
			mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,mEncoderColorFormat);
			mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4);						
			mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, mQuality.framerate);
			mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			mMediaCodec.start();
			final ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();

			mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
				// FIXME: compute the actual framerate and pass it to the MediaCodec
				private long now = 0, oldnow = 0, fps = 0, average = 0, n = 0;
				private int bufferIndex, i = 0, averageFps = 0;
				@Override
				public void onPreviewFrame(byte[] data, Camera camera) {
					oldnow = now;
					now = System.nanoTime()/1000;
					try {
						bufferIndex = mMediaCodec.dequeueInputBuffer(0);
						if (bufferIndex>=0) {
							inputBuffers[bufferIndex].clear();
							Log.e(TAG, "LENGTH: "+inputBuffers[bufferIndex].capacity());
							inputBuffers[bufferIndex].put(data, 0, data.length);
							Log.d(TAG, "Queue buffer: "+data.length);
							mMediaCodec.queueInputBuffer(bufferIndex, 0, data.length, System.nanoTime()/1000, 0);
						} else {
							Log.e(TAG,"No buffer available !");
						}
					} catch (IllegalStateException ignore) {}
					mCamera.addCallbackBuffer(data);
				}
			});			

			InputStream is = new MediaCodecInputStream(mMediaCodec);
			byte[] buffer = new byte[2048];
			int len = 0, p = 4, q = 4, c = 0;

			while ((pps == null || sps == null) && c++<300) {
				len = is.read(buffer,0,buffer.length);
				//StringBuilder e = new StringBuilder();
				//for (int i=0;i<len;i++) e.append(Integer.toHexString(buffer[i]&0xFF)+",");
				//Log.e(TAG,e.toString());
				if (len>0 && buffer[0]==0 && buffer[1]==0 && buffer[2]==0 && buffer[3]==1) {
					Log.d(TAG,"SPS or/and PPS found !");
					// Parses the SPS and PPS, they could be in different packets and in a different order depending on the phone
					// so we don't make any assumption about that
					while (p<len) {
						while (!(buffer[p+0]==0 && buffer[p+1]==0 && buffer[p+2]==0 && buffer[p+3]==1) && p+3<len) p++;
						if (p+3>=len) p=len;
						if ((buffer[q]&0x1F)==7) {
							sps = new byte[p-q];
							System.arraycopy(buffer, q, sps, 0, p-q);
						} else {
							pps = new byte[p-q];
							System.arraycopy(buffer, q, pps, 0, p-q);
						}
						p += 4;
						q = p;
					}
				}
			}

			if (pps == null || sps == null) throw new RuntimeException("Could not determine the SPS & PPS.");

			is.close();

		} finally {
			if (mMediaCodec != null) {
				mMediaCodec.stop();
				mMediaCodec.release();
				mMediaCodec = null;
			}
			if (mCamera != null) mCamera.setPreviewCallbackWithBuffer(null);
			if (!cameraOpen) destroyCamera();
			mFlashState = savedFlashState;
		}

		MP4Config config = new MP4Config(sps, pps);

		// Save test result
		if (mSettings != null) {
			Editor editor = mSettings.edit();
			editor.putString(prefix+mQuality.framerate+","+mQuality.resX+","+mQuality.resY, config.getProfileLevel()+","+config.getB64SPS()+","+config.getB64PPS());
			editor.putInt(prefix+"act-"+mQuality.framerate, mActualFramerate);
			editor.putInt(prefix+"cor-"+mQuality.framerate, mCorrectedFramerate);
			editor.commit();
		}

		return config;
	}


	// Should not be called by the UI thread
	private MP4Config testMediaRecorderAPI() throws RuntimeException, IOException {

		if (mSettings != null) {
			if (mSettings.contains("h264-mr"+mQuality.framerate+","+mQuality.resX+","+mQuality.resY)) {
				String[] s = mSettings.getString("h264-mr"+mQuality.framerate+","+mQuality.resX+","+mQuality.resY, "").split(",");
				return new MP4Config(s[0],s[1],s[2]);
			}
		}

		if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
			throw new IllegalStateException("No external storage or external storage not ready !");
		}

		final String TESTFILE = Environment.getExternalStorageDirectory().getPath()+"/spydroid-test.mp4";

		Log.i(TAG,"Testing H264 support... Test file saved at: "+TESTFILE);

		// Save flash state & set it to false so that led remains off while testing h264
		boolean savedFlashState = mFlashState;
		mFlashState = false;

		boolean cameraOpen = mCamera!=null;
		createCamera();

		// Stops the preview if needed
		if (mPreviewStarted) {
			lockCamera();
			try {
				mCamera.stopPreview();
			} catch (Exception e) {}
			mPreviewStarted = false;
		}

		try {
			Thread.sleep(100);
		} catch (InterruptedException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		unlockCamera();

		try {

			mMediaRecorder = new MediaRecorder();
			mMediaRecorder.setCamera(mCamera);
			mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
			mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
			mMediaRecorder.setMaxDuration(1000);
			//mMediaRecorder.setMaxFileSize(Integer.MAX_VALUE);
			mMediaRecorder.setVideoEncoder(mVideoEncoder);
			mMediaRecorder.setPreviewDisplay(mSurfaceHolder.getSurface());
			mMediaRecorder.setVideoSize(mQuality.resX,mQuality.resY);
			mMediaRecorder.setVideoFrameRate(mQuality.framerate);
			mMediaRecorder.setVideoEncodingBitRate(mQuality.bitrate);
			mMediaRecorder.setOutputFile(TESTFILE);

			// We wait a little and stop recording
			mMediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
				public void onInfo(MediaRecorder mr, int what, int extra) {
					Log.d(TAG,"MediaRecorder callback called !");
					if (what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
						Log.d(TAG,"MediaRecorder: MAX_DURATION_REACHED");
					} else if (what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
						Log.d(TAG,"MediaRecorder: MAX_FILESIZE_REACHED");
					} else if (what==MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN) {
						Log.d(TAG,"MediaRecorder: INFO_UNKNOWN");
					} else {
						Log.d(TAG,"WTF ?");
					}
					mLock.release();
				}
			});

			// Start recording
			mMediaRecorder.prepare();
			mMediaRecorder.start();

			if (mLock.tryAcquire(6,TimeUnit.SECONDS)) {
				Log.d(TAG,"MediaRecorder callback was called :)");
				Thread.sleep(400);
			} else {
				Log.d(TAG,"MediaRecorder callback was not called after 6 seconds... :(");
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			try {
				mMediaRecorder.stop();
			} catch (Exception e) {}
			mMediaRecorder.release();
			mMediaRecorder = null;
			lockCamera();
			if (!cameraOpen) destroyCamera();
			// Restore flash state
			mFlashState = savedFlashState;
		}

		// Retrieve SPS & PPS & ProfileId with MP4Config
		MP4Config config = new MP4Config(TESTFILE);

		// Delete dummy video
		File file = new File(TESTFILE);
		if (!file.delete()) Log.e(TAG,"Temp file could not be erased");

		Log.i(TAG,"H264 Test succeded...");

		// Save test result
		if (mSettings != null) {
			Editor editor = mSettings.edit();
			editor.putString("h264-mr"+mQuality.framerate+","+mQuality.resX+","+mQuality.resY, config.getProfileLevel()+","+config.getB64SPS()+","+config.getB64PPS());
			editor.commit();
		}

		return config;

	}

}
