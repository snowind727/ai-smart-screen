package com.example.aismartscreen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

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
    // 唤醒后继续录制的时长（秒）
    private static final int RECORD_SECONDS_AFTER_WAKE = 10;

    private AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private Thread recordThread;

    private WakeWordEngine wakeWordEngine;
    // 是否已经检测到唤醒词（只触发一次）
    private volatile boolean wakeDetected = false;
    // 是否正在播放响应语音
    private volatile boolean isPlayingResponse = false;
    // 响应语音播放是否完成
    private volatile boolean responsePlaybackFinished = false;
    // 是否已经完成唤醒后的录音
    private volatile boolean hasRecordedAfterWake = false;
    
    private MediaPlayer mediaPlayer;
    private Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // 初始化主线程 Handler
        mainHandler = new Handler(Looper.getMainLooper());

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

        // 释放 MediaPlayer
        releaseMediaPlayer();

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
        // 避免重复触发
        if (wakeDetected) {
            return;
        }
        wakeDetected = true;
        Log.d(TAG, "检测到唤醒词: " + keyword);

        // 在主线程播放响应语音
        mainHandler.post(() -> playRandomResponse());
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
            ByteArrayOutputStream recordBuffer = null;
            int targetBytes = SAMPLE_RATE * RECORD_SECONDS_AFTER_WAKE * 2; // 16-bit PCM, 单声道
            int recordedBytes = 0;
            boolean kwsStopped = false;

            while (isRecording) {
                int readBytes = audioRecord.read(buffer, 0, buffer.length);
                if (readBytes <= 0) {
                    continue;
                }

                if (!wakeDetected) {
                    // 唤醒前：继续进行 KWS
                    if (wakeWordEngine != null) {
                        wakeWordEngine.feedAudio(buffer, readBytes);
                    }
                } else if (isPlayingResponse) {
                    // 正在播放响应语音：丢弃音频数据，等待播放完成
                    // 不做任何处理，继续循环等待
                } else if (responsePlaybackFinished && !hasRecordedAfterWake) {
                    // 已经检测到唤醒词：停止 KWS，并开始录制固定时长的 PCM

                    if (!kwsStopped) {
                        if (wakeWordEngine != null) {
                            wakeWordEngine.release();
                            wakeWordEngine = null;
                        }
                        kwsStopped = true;
                    }

                    if (recordBuffer == null) {
                        recordBuffer = new ByteArrayOutputStream();
                    }

                    int remaining = targetBytes - recordedBytes;
                    int toWrite = Math.min(readBytes, remaining);
                    recordBuffer.write(buffer, 0, toWrite);
                    recordedBytes += toWrite;

                    if (recordedBytes >= targetBytes) {
                        // 保存为 WAV 文件
                        savePcmAsWav(recordBuffer.toByteArray());
                        hasRecordedAfterWake = true;
                        Log.d(TAG, "record finished");
                        try {
                            recordBuffer.close();
                        } catch (IOException e) {
                            // ignore
                        }
                        
                        // 录音完成后，重置状态并重新初始化 KWS，继续监听
                        Log.d(TAG, "重新初始化 KWS，继续监听唤醒词");
                        wakeDetected = false;
                        isPlayingResponse = false;
                        responsePlaybackFinished = false;
                        hasRecordedAfterWake = false;
                        kwsStopped = false;
                        recordBuffer = null;
                        recordedBytes = 0;
                        
                        // 重新初始化唤醒词引擎
                        if (wakeWordEngine == null) {
                            wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
                        }
                    }
                }
                // 录音完成后，状态已重置，下次循环会自动进入 KWS 检测分支
            }

            // 录音循环结束后，确保释放 AudioRecord 资源
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                } catch (IllegalStateException e) {
                    // ignore
                }
                audioRecord.release();
                audioRecord = null;
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

    /**
     * 将内存中的 PCM 数据保存为 WAV 文件，便于后续分析/播放。
     */
    private void savePcmAsWav(byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0) {
            return;
        }

        File dir = new File(getExternalFilesDir(null), "wake_records");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "无法创建录音目录: " + dir.getAbsolutePath());
            return;
        }

        File outFile = new File(dir, "wake_" + System.currentTimeMillis() + ".wav");

        int channels = 1;
        int bitsPerSample = 16;
        long totalAudioLen = pcmData.length;
        long totalDataLen = totalAudioLen + 36;
        long byteRate = SAMPLE_RATE * channels * bitsPerSample / 8;

        byte[] header = new byte[44];

        // RIFF/WAVE 头
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        writeIntLittleEndian(header, 4, (int) totalDataLen);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        // fmt chunk
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        writeIntLittleEndian(header, 16, 16); // Subchunk1Size (16 for PCM)
        header[20] = 1; // AudioFormat = PCM
        header[21] = 0;
        writeShortLittleEndian(header, 22, (short) channels);
        writeIntLittleEndian(header, 24, SAMPLE_RATE);
        writeIntLittleEndian(header, 28, (int) byteRate);
        writeShortLittleEndian(header, 32, (short) (channels * bitsPerSample / 8)); // block align
        writeShortLittleEndian(header, 34, (short) bitsPerSample);
        // data chunk
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        writeIntLittleEndian(header, 40, (int) totalAudioLen);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(outFile);
            fos.write(header, 0, 44);
            fos.write(pcmData);
            fos.flush();
            Log.d(TAG, "WAV 文件已保存: " + outFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "保存 WAV 文件失败", e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    private void writeIntLittleEndian(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
        data[offset + 2] = (byte) ((value >> 16) & 0xff);
        data[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private void writeShortLittleEndian(byte[] data, int offset, short value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

    // ---------- 响应语音播放相关 ----------

    /**
     * 从 assets/response_audio/ 目录随机选择一个 WAV 文件并播放。
     */
    private void playRandomResponse() {
        if (isPlayingResponse) {
            return; // 已经在播放，避免重复
        }

        try {
            AssetManager assetManager = getAssets();
            String[] files = assetManager.list("response_audio");
            
            if (files == null || files.length == 0) {
                Log.w(TAG, "response_audio 目录为空，跳过播放响应");
                // 如果没有响应文件，直接标记为播放完成，开始录音
                responsePlaybackFinished = true;
                return;
            }

            // 过滤出 .wav 文件
            List<String> wavFiles = new ArrayList<>();
            for (String file : files) {
                if (file.toLowerCase().endsWith(".wav")) {
                    wavFiles.add(file);
                }
            }

            if (wavFiles.isEmpty()) {
                Log.w(TAG, "response_audio 目录中没有找到 WAV 文件");
                responsePlaybackFinished = true;
                return;
            }

            // 随机选择一个文件
            Random random = new Random();
            String selectedFile = wavFiles.get(random.nextInt(wavFiles.size()));
            String assetPath = "response_audio/" + selectedFile;

            Log.d(TAG, "播放响应语音: " + selectedFile);

            // 释放之前的 MediaPlayer
            releaseMediaPlayer();

            // 创建新的 MediaPlayer
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(assetManager.openFd(assetPath));
            mediaPlayer.prepare();
            
            isPlayingResponse = true;
            responsePlaybackFinished = false;

            // 设置播放完成监听器
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "响应语音播放完成");
                isPlayingResponse = false;
                responsePlaybackFinished = true;
                releaseMediaPlayer();
            });

            // 设置错误监听器
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "播放响应语音失败: what=" + what + ", extra=" + extra);
                isPlayingResponse = false;
                responsePlaybackFinished = true;
                releaseMediaPlayer();
                return true;
            });

            // 开始播放
            mediaPlayer.start();

        } catch (IOException e) {
            Log.e(TAG, "加载响应语音文件失败", e);
            isPlayingResponse = false;
            responsePlaybackFinished = true;
        } catch (Exception e) {
            Log.e(TAG, "播放响应语音时发生错误", e);
            isPlayingResponse = false;
            responsePlaybackFinished = true;
            releaseMediaPlayer();
        }
    }

    /**
     * 释放 MediaPlayer 资源。
     */
    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "释放 MediaPlayer 失败", e);
            }
            mediaPlayer = null;
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
