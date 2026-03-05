package com.example.aismartscreen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 前台 Service：使用 AudioRecord 持续录音，通过 WakeWordEngine 检测唤醒词。
 */
public class AudioService extends Service {

    private static final String TAG = "AudioService";
    private static final String CHANNEL_ID = "audio_channel";

    // 录音参数：16kHz, 单声道, 16bit
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private Thread recordThread;

    private WakeWordEngine wakeWordEngine;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // 初始化唤醒词引擎
        wakeWordEngine = new WakeWordEngine(this, SAMPLE_RATE, this::onWakeUp);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 启动前台通知
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, notification);
        }

        // 开始录音
        startRecording();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecording();

        // 释放唤醒词引擎
        if (wakeWordEngine != null) {
            wakeWordEngine.release();
            wakeWordEngine = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 唤醒回调 ----------

    /**
     * 检测到唤醒词时调用。
     * 注意：此方法在录音线程中执行，如需操作 UI 请 post 到主线程。
     */
    private void onWakeUp(String keyword) {
        Log.d(TAG, "检测到唤醒词: " + keyword);

        // TODO: 在这里处理唤醒后的逻辑，比如：
        // - 开始 ASR（语音识别）
        // - 播放提示音
        // - 通知 Activity 更新 UI
    }

    // ---------- 录音逻辑 ----------

    private void startRecording() {
        if (isRecording) return;

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return;
        }

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );
        } catch (SecurityException e) {
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            return;
        }

        isRecording = true;
        audioRecord.startRecording();

        recordThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            while (isRecording) {
                int readBytes = audioRecord.read(buffer, 0, buffer.length);
                if (readBytes > 0) {
                    // 将 PCM 数据喂给唤醒词引擎
                    wakeWordEngine.feedAudio(buffer, readBytes);
                }
            }
        }, "AudioRecordThread");

        recordThread.start();
    }

    private void stopRecording() {
        isRecording = false;

        if (recordThread != null) {
            try {
                recordThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            recordThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException e) {
            }
            audioRecord.release();
            audioRecord = null;
        }
    }

    // ---------- 通知相关 ----------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "音频监听",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("持续监听麦克风输入");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("智能屏语音监听")
                .setContentText("等待唤醒...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
    }
}
