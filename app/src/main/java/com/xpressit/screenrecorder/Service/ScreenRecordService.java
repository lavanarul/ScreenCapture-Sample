
package com.xpressit.screenrecorder.Service;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import com.xpressit.screenrecorder.Activities.MainActivity;
import com.xpressit.screenrecorder.R;
import com.xpressit.screenrecorder.Utils.AppConstants;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

/**
 * Created by Lavanya on 26-12-2017.
 */


public class ScreenRecordService extends Service {
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static int WIDTH, HEIGHT, FPS, DENSITY_DPI;
    private static int BITRATE;
    private static boolean mustRecAudio;
    private static String SAVEPATH;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message message) {
            Toast.makeText(ScreenRecordService.this, R.string.screen_recording_stopped_toast, Toast.LENGTH_SHORT).show();
            showEndNotification();
        }
    };
    private boolean isRecording;
    private boolean isBound = false;

    private long startTime, elapsedTime = 0;
    private SharedPreferences prefs;
    private WindowManager window;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private MediaProjectionCallback mMediaProjectionCallback;
    private MediaRecorder mMediaRecorder;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        switch (intent.getAction()) {
            case AppConstants.SCREEN_RECORDING_START:
                if (!isRecording) {
                    getValues();
                    Intent data = intent.getParcelableExtra(AppConstants.RECORDER_INTENT_DATA);
                    int result = intent.getIntExtra(AppConstants.RECORDER_INTENT_RESULT, Activity.RESULT_OK);
                    mMediaRecorder = new MediaRecorder();
                    initRecorder();
                    mMediaProjectionCallback = new MediaProjectionCallback();
                    MediaProjectionManager mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

                    mMediaProjection = mProjectionManager.getMediaProjection(result, data);
                    mMediaProjection.registerCallback(mMediaProjectionCallback, null);
                    mVirtualDisplay = createVirtualDisplay();
                    try {
                        mMediaRecorder.start();
                        if (isBound)
                            isRecording = true;

                        Toast.makeText(this, R.string.screen_recording_started_toast, Toast.LENGTH_SHORT).show();
                    } catch (IllegalStateException e) {
                        Log.d(AppConstants.TAG, "Mediarecorder reached Illegal state exception. Did you start the recording twice?");
                        Toast.makeText(this, R.string.recording_failed_toast, Toast.LENGTH_SHORT).show();
                        isRecording = false;
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        startTime = System.currentTimeMillis();
                        Intent recordPauseIntent = new Intent(this, ScreenRecordService.class);
                        recordPauseIntent.setAction(AppConstants.SCREEN_RECORDING_PAUSE);
                        PendingIntent precordPauseIntent = PendingIntent.getService(this, 0, recordPauseIntent, 0);
                        NotificationCompat.Action action = new NotificationCompat.Action(android.R.drawable.ic_media_pause,
                                getString(R.string.screen_recording_notification_action_pause), precordPauseIntent);

                        startNotificationForeGround(createNotification(action).build(), AppConstants.SCREEN_RECORDER_NOTIFICATION_ID);
                    } else
                        startNotificationForeGround(createNotification(null).build(), AppConstants.SCREEN_RECORDER_NOTIFICATION_ID);
                } else {
                    Toast.makeText(this, R.string.screenrecording_already_active_toast, Toast.LENGTH_SHORT).show();
                }
                break;
            case AppConstants.SCREEN_RECORDING_PAUSE:
                pauseScreenRecording();
                break;
            case AppConstants.SCREEN_RECORDING_RESUME:
                resumeScreenRecording();
                break;
            case AppConstants.SCREEN_RECORDING_STOP:
                    stopScreenSharing();
                stopForeground(true);
                break;
        }
        return START_STICKY;
    }

    @TargetApi(24)
    private void pauseScreenRecording() {
        mMediaRecorder.pause();
        elapsedTime += (System.currentTimeMillis() - startTime);
        Intent recordResumeIntent = new Intent(this, ScreenRecordService.class);
        recordResumeIntent.setAction(AppConstants.SCREEN_RECORDING_RESUME);
        PendingIntent precordResumeIntent = PendingIntent.getService(this, 0, recordResumeIntent, 0);
        NotificationCompat.Action action = new NotificationCompat.Action(android.R.drawable.ic_media_play,
                getString(R.string.screen_recording_notification_action_resume), precordResumeIntent);
        updateNotification(createNotification(action).setUsesChronometer(false).build(), AppConstants.SCREEN_RECORDER_NOTIFICATION_ID);
        Toast.makeText(this, R.string.screen_recording_paused_toast, Toast.LENGTH_SHORT).show();

    }

    @TargetApi(24)
    private void resumeScreenRecording() {
        mMediaRecorder.resume();
        startTime = System.currentTimeMillis();
        Intent recordPauseIntent = new Intent(this, ScreenRecordService.class);
        recordPauseIntent.setAction(AppConstants.SCREEN_RECORDING_PAUSE);
        PendingIntent precordPauseIntent = PendingIntent.getService(this, 0, recordPauseIntent, 0);
        NotificationCompat.Action action = new NotificationCompat.Action(android.R.drawable.ic_media_pause,
                getString(R.string.screen_recording_notification_action_pause), precordPauseIntent);
        updateNotification(createNotification(action).setUsesChronometer(true)
                .setWhen((System.currentTimeMillis() - elapsedTime)).build(), AppConstants.SCREEN_RECORDER_NOTIFICATION_ID);
        Toast.makeText(this, R.string.screen_recording_resumed_toast, Toast.LENGTH_SHORT).show();

    }

    private VirtualDisplay createVirtualDisplay() {
        return mMediaProjection.createVirtualDisplay("MainActivity",
                WIDTH, HEIGHT, DENSITY_DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mMediaRecorder.getSurface(), null , null
                );
    }


    private void initRecorder() {
        try {
            if (mustRecAudio)
                mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mMediaRecorder.setOutputFile(SAVEPATH);
            mMediaRecorder.setVideoSize(WIDTH, HEIGHT);
            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (mustRecAudio)
                mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mMediaRecorder.setVideoEncodingBitRate(BITRATE);

            mMediaRecorder.setVideoFrameRate(FPS);

            int rotation = window.getDefaultDisplay().getRotation();
            int orientation = ORIENTATIONS.get(rotation + 90);
            mMediaRecorder.setOrientationHint(orientation);
            mMediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private NotificationCompat.Builder createNotification(NotificationCompat.Action action) {
        Bitmap icon = BitmapFactory.decodeResource(getResources(),
                R.mipmap.logo);

        Intent recordStopIntent = new Intent(this, ScreenRecordService.class);
        recordStopIntent.setAction(AppConstants.SCREEN_RECORDING_STOP);
        PendingIntent precordStopIntent = PendingIntent.getService(this, 0, recordStopIntent, 0);

        Intent UIIntent = new Intent(this, MainActivity.class);
        PendingIntent notificationContentIntent = PendingIntent.getActivity(this, 0, UIIntent, 0);

        NotificationCompat.Builder notification = new NotificationCompat.Builder(this)
                .setContentTitle(getResources().getString(R.string.screen_recording_notification_title))
                .setTicker(getResources().getString(R.string.screen_recording_notification_title))
                .setSmallIcon(R.drawable.logo1)
                .setLargeIcon(
                        Bitmap.createScaledBitmap(icon, 128, 128, false))
                .setUsesChronometer(true)
                .setOngoing(true)
                .setContentIntent(notificationContentIntent)
                .setPriority(Notification.PRIORITY_MAX)
                .addAction(R.drawable.ic_notification_stop, getResources().getString(R.string.screen_recording_notification_action_stop),
                        precordStopIntent);
        if (action != null)
            notification.addAction(action);
        return notification;
    }

    private void showEndNotification() {
        Bitmap icon = BitmapFactory.decodeResource(getResources(),
                R.mipmap.logo);
        Intent videoListIntent = new Intent();
        PendingIntent sharePendingIntent = PendingIntent.getActivity(this, 0, Intent.createChooser(
                videoListIntent, getString(R.string.share_intent_title)), PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder shareNotification = new NotificationCompat.Builder(this)
                .setContentTitle(getString(R.string.share_intent_notification_title))
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(Bitmap.createScaledBitmap(icon, 128, 128, false))
                .setAutoCancel(true)
                .setContentIntent(sharePendingIntent);
        updateNotification(shareNotification.build(), AppConstants.SCREEN_RECORDER_SHARE_NOTIFICATION_ID);
    }

    private void startNotificationForeGround(Notification notification, int ID) {
        startForeground(ID, notification);
    }

    private void updateNotification(Notification notification, int ID) {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(ID, notification);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void getValues() {
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String res = prefs.getString(getString(R.string.res_key), getResolution());
        setWidthHeight(res);
        FPS = Integer.parseInt(prefs.getString(getString(R.string.fps_key), "30"));
        BITRATE = Integer.parseInt(prefs.getString(getString(R.string.bitrate_key), "7130317"));
        mustRecAudio = prefs.getBoolean(getString(R.string.audiorec_key), false);
        String saveLocation = prefs.getString(getString(R.string.savelocation_key),
                Environment.getExternalStorageDirectory() + File.separator + AppConstants.APPDIR);
        File saveDir = new File(saveLocation);
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED) && !saveDir.isDirectory()) {
            saveDir.mkdirs();
        }
        String saveFileName = getFileSaveName();
        SAVEPATH = saveLocation + File.separator + saveFileName + ".mp4";
    }

    private void setWidthHeight(String res) {
        String[] widthHeight = res.split("x");
        WIDTH = Integer.parseInt(widthHeight[0]);
        HEIGHT = Integer.parseInt(widthHeight[1]);
    }

    private String getResolution() {
        DisplayMetrics metrics = new DisplayMetrics();
        window = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        window.getDefaultDisplay().getMetrics(metrics);
        DENSITY_DPI = metrics.densityDpi;
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        return width + "x" + height;
    }

    private String getFileSaveName() {
        String filename = prefs.getString(getString(R.string.filename_key), "yyyyMMdd_hhmmss");
        String prefix = prefs.getString(getString(R.string.fileprefix_key), "recording");
        Date today = Calendar.getInstance().getTime();
        SimpleDateFormat formatter = new SimpleDateFormat(filename);
        return prefix + "_" + formatter.format(today);


    }

    private void destroyMediaProjection() {
        try {
            mMediaRecorder.stop();
            indexFile();
        } catch (RuntimeException e) {
            if (new File(SAVEPATH).delete())
            Toast.makeText(this, getString(R.string.fatal_exception_message), Toast.LENGTH_SHORT).show();
        } finally {
            mMediaRecorder.reset();
            mVirtualDisplay.release();
            mMediaRecorder.release();
            if (mMediaProjection != null) {
                mMediaProjection.unregisterCallback(mMediaProjectionCallback);
                mMediaProjection.stop();
                mMediaProjection = null;
            }
        }
        isRecording = false;
    }

    private void indexFile() {
        ArrayList<String> toBeScanned = new ArrayList<>();
        toBeScanned.add(SAVEPATH);
        String[] toBeScannedStr = new String[toBeScanned.size()];
        toBeScannedStr = toBeScanned.toArray(toBeScannedStr);
        MediaScannerConnection.scanFile(this, toBeScannedStr, null, new MediaScannerConnection.OnScanCompletedListener() {

            @Override
            public void onScanCompleted(String path, Uri uri) {
                Message message = mHandler.obtainMessage();
                message.sendToTarget();
                stopSelf();
            }
        });
    }

    private void stopScreenSharing() {
        if (mVirtualDisplay == null) {
            return;
        }
        destroyMediaProjection();
    }

    private class MediaProjectionCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            stopScreenSharing();
        }
    }
}
