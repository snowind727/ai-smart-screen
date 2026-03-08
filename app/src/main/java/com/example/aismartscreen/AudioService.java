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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.io.File;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    
    // 唤醒词列表
    private static final String[] WAKE_WORDS = {"你好西西", "小雪飞"};
    
    // 结束关键字（用于退出持续对话模式）
    private static final String END_KEYWORD = "结束";
    
    // 监听关键字超时时间（毫秒）
    private static final long KEYWORD_TIMEOUT_MS = 10000; // 10秒
    
    // 关键字到视频名称前缀的映射关系（多个关键字可以映射到同一个前缀）
    private static final Map<String, String> KEYWORD_TO_VIDEO_PREFIX = new HashMap<String, String>() {{
        // 小狗相关关键字 -> "小狗叫"
        put("小狗怎么叫", "小狗叫");
        put("小狗汪汪叫", "小狗叫");
        
        // 小猫相关关键字 -> "小猫叫"
        put("小猫怎么叫", "小猫叫");
        put("小猫喵喵叫", "小猫叫");
        
        // 小鸭子相关关键字 -> "小鸭子"
        put("小鸭子怎么叫", "小鸭子");
        
        // 彩色球相关关键字 -> "彩色球"
        put("彩色球", "彩色球");
    }};

    private AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private Thread recordThread;

    private WakeWordEngine wakeWordEngine;
    // 当前状态：0=监听唤醒词, 1=播放响应中, 2=监听关键字, 3=播放答案中
    private volatile int currentState = 0; // 0: 监听唤醒词, 1: 播放响应, 2: 监听关键字, 3: 播放答案
    
    private MediaPlayer mediaPlayer;
    private Handler mainHandler;
    // 用于超时重置的 Runnable
    private Runnable timeoutResetRunnable;
    private BroadcastReceiver videoPlaybackCompleteReceiver;
    private Runnable videoTimeoutRunnable; // 视频播放超时 Runnable

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // 初始化主线程 Handler
        mainHandler = new Handler(Looper.getMainLooper());

        // 初始化唤醒词引擎
        wakeWordEngine = new WakeWordEngine(this, SAMPLE_RATE, this::onWakeUp);
        
        // 注册视频播放完成广播接收器
        registerVideoPlaybackCompleteReceiver();
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

        // 取消超时重置
        cancelTimeoutReset();
        
        // 取消视频超时计时器
        if (videoTimeoutRunnable != null) {
            mainHandler.removeCallbacks(videoTimeoutRunnable);
            videoTimeoutRunnable = null;
        }
        
        // 注销广播接收器
        if (videoPlaybackCompleteReceiver != null) {
            unregisterReceiver(videoPlaybackCompleteReceiver);
        }

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
     * 检测到关键字时调用（可能是唤醒词或普通关键字）。
     * 注意：此方法在录音线程中执行，如需操作 UI 请 post 到主线程。
     */
    private void onWakeUp(String keyword) {
        Log.d(TAG, "检测到关键字: " + keyword);
        
        // 判断是否是唤醒词
        boolean isWakeWord = false;
        for (String wakeWord : WAKE_WORDS) {
            if (wakeWord.equals(keyword)) {
                isWakeWord = true;
                break;
            }
        }
        
        if (isWakeWord) {
            // 检测到唤醒词，无论当前在什么状态都切换到播放响应状态
            Log.d(TAG, "检测到唤醒词: " + keyword + "，当前状态: " + currentState);
            // 取消所有正在进行的操作
            cancelTimeoutReset();
            // 取消视频超时计时器
            if (videoTimeoutRunnable != null) {
                mainHandler.removeCallbacks(videoTimeoutRunnable);
                videoTimeoutRunnable = null;
            }
            // 停止正在播放的音频（如果有）
            releaseMediaPlayer();
            // 切换到播放响应状态
            currentState = 1;
            // 在主线程播放响应语音
            mainHandler.post(() -> playRandomResponse());
        } else if (!isWakeWord && currentState == 2) {
            // 检测到普通关键字，且当前在监听关键字状态
            Log.d(TAG, "检测到关键字: " + keyword);
            
            // 检查是否是"结束"关键字
            if (END_KEYWORD.equals(keyword)) {
                Log.d(TAG, "检测到结束关键字，重置到初始状态");
                // 取消超时重置
                cancelTimeoutReset();
                // 重置状态，重新开始监听唤醒词
                currentState = 0;
                if (wakeWordEngine == null) {
                    wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
                }
                return;
            }
            
            // 取消超时重置
            cancelTimeoutReset();
            currentState = 3; // 切换到播放答案状态
            // 在主线程播放对应的答案音频
            mainHandler.post(() -> playAnswerAudio(keyword));
        }
    }
    
    /**
     * 启动超时重置计时器（10秒后如果没有检测到关键字，重置到初始状态）
     */
    private void startTimeoutReset() {
        // 先取消之前的计时器
        cancelTimeoutReset();
        
        timeoutResetRunnable = () -> {
            if (currentState == 2) {
                // 如果还在监听关键字状态，说明超时了
                Log.d(TAG, "10秒内未检测到关键字，重置到初始状态");
                // 取消超时重置
                cancelTimeoutReset();
                // 重置状态，重新开始监听唤醒词
                currentState = 0;
                // 确保 KWS 引擎存在
                if (wakeWordEngine == null) {
                    wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
                }
            }
        };
        
        mainHandler.postDelayed(timeoutResetRunnable, KEYWORD_TIMEOUT_MS);
    }
    
    /**
     * 取消超时重置计时器
     */
    private void cancelTimeoutReset() {
        if (timeoutResetRunnable != null) {
            mainHandler.removeCallbacks(timeoutResetRunnable);
            timeoutResetRunnable = null;
        }
    }
    
    /**
     * 注册视频播放完成广播接收器
     */
    private void registerVideoPlaybackCompleteReceiver() {
        videoPlaybackCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.example.aismartscreen.VIDEO_PLAYBACK_COMPLETE".equals(intent.getAction())) {
                    Log.d(TAG, "收到视频播放完成广播，继续监听关键字");
                    // 取消视频超时计时器
                    if (videoTimeoutRunnable != null) {
                        mainHandler.removeCallbacks(videoTimeoutRunnable);
                        videoTimeoutRunnable = null;
                    }
                    // 不重置到初始状态，而是继续监听关键字（状态2）
                    currentState = 2;
                    // 启动10秒超时计时器
                    startTimeoutReset();
                    // 确保 KWS 引擎存在
                    if (wakeWordEngine == null) {
                        wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter("com.example.aismartscreen.VIDEO_PLAYBACK_COMPLETE");
        registerReceiver(videoPlaybackCompleteReceiver, filter);
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
                if (readBytes <= 0) {
                    continue;
                }

                if (currentState == 0 || currentState == 2) {
                    // 状态 0: 监听唤醒词，状态 2: 监听关键字
                    // 继续喂给 KWS 引擎进行检测
                    if (wakeWordEngine != null) {
                        wakeWordEngine.feedAudio(buffer, readBytes);
                    }
                } else {
                    // 状态 1: 播放响应中，状态 3: 播放答案中
                    // 丢弃音频数据，等待播放完成
                }
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


    // ---------- 响应语音播放相关 ----------

    /**
     * 从 assets/response_audio/ 目录随机选择一个 WAV 文件并播放。
     */
    private void playRandomResponse() {
        if (currentState != 1) {
            return; // 状态不对，避免重复
        }

        try {
            AssetManager assetManager = getAssets();
            String[] files = assetManager.list("response_audio");
            
            if (files == null || files.length == 0) {
                Log.w(TAG, "response_audio 目录为空，跳过播放响应");
                // 如果没有响应文件，直接切换到监听关键字状态
                currentState = 2;
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
                currentState = 2;
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

            // 设置播放完成监听器
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "响应语音播放完成，开始监听关键字");
                releaseMediaPlayer();
                // 切换到监听关键字状态
                currentState = 2;
                // 启动10秒超时计时器
                startTimeoutReset();
            });

            // 设置错误监听器
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "播放响应语音失败: what=" + what + ", extra=" + extra);
                releaseMediaPlayer();
                // 切换到监听关键字状态
                currentState = 2;
                // 启动10秒超时计时器
                startTimeoutReset();
                return true;
            });

            // 开始播放
            mediaPlayer.start();

        } catch (IOException e) {
            Log.e(TAG, "加载响应语音文件失败", e);
            currentState = 2;
            // 启动10秒超时计时器
            startTimeoutReset();
        } catch (Exception e) {
            Log.e(TAG, "播放响应语音时发生错误", e);
            releaseMediaPlayer();
            currentState = 2;
            // 启动10秒超时计时器
            startTimeoutReset();
        }
    }

    /**
     * 播放答案内容（优先查找视频，找不到再查找音频）。
     * @param keyword 检测到的关键字（对应文件名，不含扩展名）
     */
    private void playAnswerAudio(String keyword) {
        if (currentState != 3) {
            return; // 状态不对，避免重复
        }

        try {
            AssetManager assetManager = getAssets();
            
            // 先查找视频文件：通过关键字映射到视频前缀，然后随机选择一个匹配的视频
            String videoPrefix = KEYWORD_TO_VIDEO_PREFIX.get(keyword);
            boolean videoFound = false;
            
            if (videoPrefix != null) {
                String[] videoFiles = assetManager.list("answer_video");
                if (videoFiles != null) {
                    // 收集所有匹配前缀的视频文件
                    List<String> matchedVideos = new ArrayList<>();
                    for (String file : videoFiles) {
                        String fileNameWithoutExt = file.substring(0, file.lastIndexOf('.'));
                        // 检查文件名是否以指定前缀开头
                        if (fileNameWithoutExt.startsWith(videoPrefix) && 
                            (file.toLowerCase().endsWith(".mp4") || file.toLowerCase().endsWith(".avi") || 
                             file.toLowerCase().endsWith(".mov") || file.toLowerCase().endsWith(".mkv"))) {
                            matchedVideos.add(file);
                        }
                    }
                    
                    // 如果找到匹配的视频，随机选择一个
                    if (!matchedVideos.isEmpty()) {
                        Random random = new Random();
                        String selectedVideo = matchedVideos.get(random.nextInt(matchedVideos.size()));
                        String videoAssetPath = "answer_video/" + selectedVideo;
                        Log.d(TAG, "关键字 \"" + keyword + "\" 映射到前缀 \"" + videoPrefix + "\"，随机选择视频: " + selectedVideo);
                        playAnswerVideo(videoAssetPath);
                        videoFound = true;
                    }
                }
            }
            
            // 如果没找到视频，查找音频文件（音频保持全匹配）
            if (!videoFound) {
                String audioAssetPath = "answer_audio/" + keyword + ".wav";
                Log.d(TAG, "未找到视频，播放答案音频: " + keyword);
                playAnswerAudioFile(audioAssetPath);
            }

        } catch (Exception e) {
            Log.e(TAG, "查找答案文件失败: " + keyword, e);
            // 不重置到初始状态，而是继续监听关键字（状态2）
            cancelTimeoutReset();
            currentState = 2;
            // 启动10秒超时计时器
            startTimeoutReset();
            if (wakeWordEngine == null) {
                wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
            }
        }
    }
    
    /**
     * 播放答案视频文件。
     * @param videoAssetPath 视频文件在 assets 中的路径
     */
    private void playAnswerVideo(String videoAssetPath) {
        try {
            // 通过 Broadcast 通知 MainActivity 播放视频
            Intent intent = new Intent("com.example.aismartscreen.PLAY_VIDEO");
            intent.putExtra("video_path", videoAssetPath);
            sendBroadcast(intent);
            Log.d(TAG, "已发送播放视频广播: " + videoAssetPath);
            
            // 设置一个超时计时器（作为备用，如果 MainActivity 没有发送完成广播）
            // 取消之前的超时计时器
            if (videoTimeoutRunnable != null) {
                mainHandler.removeCallbacks(videoTimeoutRunnable);
            }
            videoTimeoutRunnable = () -> {
                if (currentState == 3) {
                    Log.w(TAG, "视频播放超时（60秒），继续监听关键字");
                    cancelTimeoutReset();
                    // 不重置到初始状态，而是继续监听关键字（状态2）
                    currentState = 2;
                    // 启动10秒超时计时器
                    startTimeoutReset();
                    if (wakeWordEngine == null) {
                        wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
                    }
                }
            };
            mainHandler.postDelayed(videoTimeoutRunnable, 60000); // 60秒超时
            
        } catch (Exception e) {
            Log.e(TAG, "播放答案视频失败: " + videoAssetPath, e);
            // 不重置到初始状态，而是继续监听关键字（状态2）
            cancelTimeoutReset();
            currentState = 2;
            // 启动10秒超时计时器
            startTimeoutReset();
            if (wakeWordEngine == null) {
                wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
            }
        }
    }
    
    /**
     * 播放答案音频文件。
     * @param audioAssetPath 音频文件在 assets 中的路径
     */
    private void playAnswerAudioFile(String audioAssetPath) {
        try {
            AssetManager assetManager = getAssets();
            
            // 释放之前的 MediaPlayer
            releaseMediaPlayer();

            // 创建新的 MediaPlayer
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(assetManager.openFd(audioAssetPath));
            mediaPlayer.prepare();

            // 设置播放完成监听器
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "答案音频播放完成，继续监听关键字");
                releaseMediaPlayer();
                // 取消超时重置（如果还在运行）
                cancelTimeoutReset();
                // 不重置到初始状态，而是继续监听关键字（状态2）
                currentState = 2;
                // 启动10秒超时计时器
                startTimeoutReset();
                // 确保 KWS 引擎存在
                if (wakeWordEngine == null) {
                    wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
                }
            });

            // 设置错误监听器
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "播放答案音频失败: what=" + what + ", extra=" + extra);
                releaseMediaPlayer();
                // 取消超时重置（如果还在运行）
                cancelTimeoutReset();
                // 不重置到初始状态，而是继续监听关键字（状态2）
                currentState = 2;
                // 启动10秒超时计时器
                startTimeoutReset();
                if (wakeWordEngine == null) {
                    wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
                }
                return true;
            });

            // 开始播放
            mediaPlayer.start();

        } catch (IOException e) {
            Log.e(TAG, "加载答案音频文件失败: " + audioAssetPath, e);
            // 取消超时重置（如果还在运行）
            cancelTimeoutReset();
            // 不重置到初始状态，而是继续监听关键字（状态2）
            currentState = 2;
            // 启动10秒超时计时器
            startTimeoutReset();
            if (wakeWordEngine == null) {
                wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
            }
        } catch (Exception e) {
            Log.e(TAG, "播放答案音频时发生错误: " + audioAssetPath, e);
            releaseMediaPlayer();
            // 取消超时重置（如果还在运行）
            cancelTimeoutReset();
            // 不重置到初始状态，而是继续监听关键字（状态2）
            currentState = 2;
            // 启动10秒超时计时器
            startTimeoutReset();
            if (wakeWordEngine == null) {
                wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
            }
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
