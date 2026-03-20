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
    private static final String[] WAKE_WORDS = {"你好西西"};
    
    // 结束关键字（用于退出持续对话模式）
    private static final String END_KEYWORD = "结束";
    // 继续关键字（用于恢复视频播放）
    private static final String CONTINUE_KEYWORD = "继续";
    // 下一首关键字（用于切换下一首哄睡歌曲）
    private static final String NEXT_KEYWORD = "下一首";
    
    // 哄睡相关关键字
    private static final String[] SLEEP_KEYWORDS = {"宝贝睡觉"};
    
    // 监听关键字超时时间（毫秒）
    private static final long KEYWORD_TIMEOUT_MS = 10000; // 10秒
    
    // 当前正在播放的视频路径（用于暂停后恢复）
    private String currentVideoPath = null;
    // 视频暂停时的播放位置（毫秒）
    private int videoPausedPosition = 0;
    
    // 哄睡模式相关变量
    private boolean isSleepMode = false; // 是否在哄睡模式
    private List<String> sleepVideoList = new ArrayList<>(); // 哄睡视频列表
    private int currentSleepVideoIndex = -1; // 当前播放的哄睡视频索引
    
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
        
        // 播放哈喽相关关键字 -> "hello"
        put("播放哈喽", "hello");
        
        // 播放小跳蛙相关关键字 -> "小跳蛙"
        put("播放小跳蛙", "小跳蛙");
        
        // 水果蔬菜相关关键字映射
        put("苹果", "苹果");
        put("香蕉", "香蕉");
        put("西瓜", "西瓜");
        put("草莓", "草莓");
        put("菠萝", "菠萝");
        put("梨", "梨");
        put("番茄", "番茄");
    }};

    private AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private Thread recordThread;

    private WakeWordEngine wakeWordEngine;
    // 当前状态：0=监听唤醒词, 1=播放响应中, 2=监听关键字, 3=播放答案中, 4=视频暂停等待指令
    private volatile int currentState = 0; // 0: 监听唤醒词, 1: 播放响应, 2: 监听关键字, 3: 播放答案, 4: 视频暂停等待指令
    
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
            
            // 如果当前正在播放视频（状态3），暂停视频
            if (currentState == 3) {
                Log.d(TAG, "视频播放中检测到唤醒词，暂停视频");
                mainHandler.post(() -> pauseVideo());
            }
            
            // 注意：不退出哄睡模式，暂停后可以通过"继续"恢复播放
            
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
        } else if (!isWakeWord && (currentState == 2 || currentState == 4 || currentState == 3)) {
            // 检测到普通关键字，且当前在监听关键字状态或播放答案状态（播放答案时也要监听"下一个"）
            Log.d(TAG, "检测到关键字: " + keyword);
            
            // 检查是否是哄睡关键词（在状态2时）
            boolean isSleepKeyword = false;
            for (String sleepKw : SLEEP_KEYWORDS) {
                if (sleepKw.equals(keyword)) {
                    isSleepKeyword = true;
                    break;
                }
            }
            
            if (isSleepKeyword && currentState == 2) {
                Log.d(TAG, "检测到哄睡关键词: " + keyword);
                // 取消超时重置
                cancelTimeoutReset();
                currentState = 3; // 切换到播放答案状态
                // 在主线程启动哄睡模式
                mainHandler.post(() -> startSleepMode());
                return;
            }
            
            // 检查是否是"下一个"关键字（在哄睡模式中）
            if (NEXT_KEYWORD.equals(keyword) && isSleepMode && (currentState == 3 || currentState == 4)) {
                Log.d(TAG, "检测到下一个关键字，切换下一首哄睡歌曲");
                // 取消超时重置
                cancelTimeoutReset();
                // 切换到下一首
                mainHandler.post(() -> playNextSleepVideo());
                return;
            }
            
            // 检查是否是"结束"关键字
            if (END_KEYWORD.equals(keyword)) {
                Log.d(TAG, "检测到结束关键字");
                // 取消超时重置
                cancelTimeoutReset();
                
                // 如果视频处于暂停状态，关闭视频
                if (currentState == 4) {
                    Log.d(TAG, "关闭视频，重置到初始状态");
                    mainHandler.post(() -> stopVideo());
                }
                
                // 退出哄睡模式
                if (isSleepMode) {
                    isSleepMode = false;
                    sleepVideoList.clear();
                    currentSleepVideoIndex = -1;
                }
                
                // 重置状态，重新开始监听唤醒词
                currentState = 0;
                currentVideoPath = null;
                videoPausedPosition = 0;
                if (wakeWordEngine != null) {
                    wakeWordEngine.resetStream();  // 重置 stream 确保能继续检测
                } else {
                    wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
                }
                return;
            }
            
            // 检查是否是"继续"关键字（视频暂停时）
            if (CONTINUE_KEYWORD.equals(keyword) && currentState == 4) {
                Log.d(TAG, "检测到继续关键字，恢复视频播放");
                // 取消超时重置
                cancelTimeoutReset();
                // 恢复视频播放
                mainHandler.post(() -> resumeVideo());
                return;
            }
            
            // 普通关键字处理（只在状态2时处理，状态4时忽略）
            if (currentState == 2) {
                // 取消超时重置
                cancelTimeoutReset();
                currentState = 3; // 切换到播放答案状态
                // 在主线程播放对应的答案音频
                mainHandler.post(() -> playAnswerAudio(keyword));
            }
        }
    }
    
    /**
     * 启动超时重置计时器（10秒后如果没有检测到关键字，重置到初始状态）
     */
    private void startTimeoutReset() {
        // 先取消之前的计时器
        cancelTimeoutReset();
        
        timeoutResetRunnable = () -> {
            if (currentState == 2 || currentState == 4) {
                // 如果还在监听关键字状态或视频暂停等待指令状态，说明超时了
                Log.d(TAG, "10秒内未检测到关键字，重置到初始状态");
                // 取消超时重置
                cancelTimeoutReset();
                
                // 如果视频处于暂停状态，关闭视频
                if (currentState == 4) {
                    mainHandler.post(() -> stopVideo());
                }
                
                // 退出哄睡模式（如果正在哄睡模式）
                if (isSleepMode) {
                    isSleepMode = false;
                    sleepVideoList.clear();
                    currentSleepVideoIndex = -1;
                }
                
                // 重置状态，重新开始监听唤醒词
                currentState = 0;
                currentVideoPath = null;
                videoPausedPosition = 0;
                // 确保 KWS 引擎存在并重置 stream
                if (wakeWordEngine != null) {
                    wakeWordEngine.resetStream();  // 重置 stream 确保能继续检测
                } else {
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
                String action = intent.getAction();
                if ("com.example.aismartscreen.VIDEO_PLAYBACK_COMPLETE".equals(action)) {
                    Log.d(TAG, "收到视频播放完成广播");
                    // 取消视频超时计时器
                    if (videoTimeoutRunnable != null) {
                        mainHandler.removeCallbacks(videoTimeoutRunnable);
                        videoTimeoutRunnable = null;
                    }
                    
                    // 如果是哄睡模式，自动播放下一首
                    if (isSleepMode && !sleepVideoList.isEmpty()) {
                        Log.d(TAG, "哄睡模式：自动播放下一首");
                        playNextSleepVideo();
                    } else {
                        // 清除视频路径和位置
                        currentVideoPath = null;
                        videoPausedPosition = 0;
                        // 不重置到初始状态，而是继续监听关键字（状态2）
                        currentState = 2;
                        // 启动10秒超时计时器
                        startTimeoutReset();
                        // 确保 KWS 引擎存在
                        if (wakeWordEngine == null) {
                            wakeWordEngine = new WakeWordEngine(AudioService.this, SAMPLE_RATE, AudioService.this::onWakeUp);
                        }
                    }
                } else if ("com.example.aismartscreen.VIDEO_PAUSED".equals(action)) {
                    // 收到视频暂停广播，保存暂停位置
                    videoPausedPosition = intent.getIntExtra("position", 0);
                    Log.d(TAG, "视频已暂停，保存位置: " + videoPausedPosition + "ms");
                    // 切换到等待指令状态
                    currentState = 4;
                    // 启动10秒超时计时器
                    startTimeoutReset();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.example.aismartscreen.VIDEO_PLAYBACK_COMPLETE");
        filter.addAction("com.example.aismartscreen.VIDEO_PAUSED");
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

                if (currentState == 0 || currentState == 2 || currentState == 3 || currentState == 4) {
                    // 状态 0: 监听唤醒词，状态 2: 监听关键字，状态 3: 播放答案中，状态 4: 视频暂停等待指令
                    // 继续喂给 KWS 引擎进行检测（视频播放时也要监听）
                    if (wakeWordEngine != null) {
                        wakeWordEngine.feedAudio(buffer, readBytes);
                    }
                } else {
                    // 状态 1: 播放响应中
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
                Log.d(TAG, "响应语音播放完成");
                releaseMediaPlayer();
                
                // 如果之前视频被暂停，进入等待指令状态
                if (currentVideoPath != null && videoPausedPosition > 0) {
                    Log.d(TAG, "视频已暂停，进入等待指令状态");
                    currentState = 4;
                } else {
                    // 否则切换到监听关键字状态
                    currentState = 2;
                }
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
                        int dotIndex = file.lastIndexOf('.');
                        if (dotIndex <= 0) continue; // 跳过无扩展名的文件（如子目录）
                        String fileNameWithoutExt = file.substring(0, dotIndex);
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
            // 保存当前视频路径
            currentVideoPath = videoAssetPath;
            videoPausedPosition = 0;
            
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
    
    /**
     * 暂停视频播放
     */
    private void pauseVideo() {
        Intent intent = new Intent("com.example.aismartscreen.PAUSE_VIDEO");
        sendBroadcast(intent);
        Log.d(TAG, "已发送暂停视频广播");
    }
    
    /**
     * 恢复视频播放
     */
    private void resumeVideo() {
        if (currentVideoPath != null) {
            Intent intent = new Intent("com.example.aismartscreen.RESUME_VIDEO");
            intent.putExtra("video_path", currentVideoPath);
            intent.putExtra("position", videoPausedPosition);
            sendBroadcast(intent);
            Log.d(TAG, "已发送恢复视频广播，位置: " + videoPausedPosition);
            // 恢复到播放状态
            currentState = 3;
            // 取消超时重置
            cancelTimeoutReset();
        }
    }
    
    /**
     * 停止视频播放
     */
    private void stopVideo() {
        Intent intent = new Intent("com.example.aismartscreen.STOP_VIDEO");
        sendBroadcast(intent);
        Log.d(TAG, "已发送停止视频广播");
        currentVideoPath = null;
        videoPausedPosition = 0;
    }
    
    /**
     * 启动哄睡模式：先播放哄睡音频，然后循环播放sleep目录下的视频
     */
    private void startSleepMode() {
        try {
            Log.d(TAG, "启动哄睡模式");
            isSleepMode = true;
            
            // 加载sleep目录下的所有视频文件
            AssetManager assetManager = getAssets();
            String[] allFiles = assetManager.list("answer_video/sleep");
            sleepVideoList.clear();
            if (allFiles != null) {
                for (String file : allFiles) {
                    if (file.toLowerCase().endsWith(".mp4") || 
                        file.toLowerCase().endsWith(".avi") || 
                        file.toLowerCase().endsWith(".mov") || 
                        file.toLowerCase().endsWith(".mkv")) {
                        sleepVideoList.add("answer_video/sleep/" + file);
                    }
                }
            }
            
            // 按文件名排序，确保顺序播放
            sleepVideoList.sort(String::compareTo);
            
            if (sleepVideoList.isEmpty()) {
                Log.w(TAG, "sleep目录下没有找到视频文件");
                isSleepMode = false;
                currentState = 2;
                startTimeoutReset();
                return;
            }
            
            Log.d(TAG, "找到 " + sleepVideoList.size() + " 个哄睡视频");
            currentSleepVideoIndex = -1; // 初始化为-1，播放音频后会设置为0
            
            // 先播放哄睡音频
            String audioAssetPath = "answer_audio/哄睡01.wav";
            Log.d(TAG, "播放哄睡音频: " + audioAssetPath);
            
            // 释放之前的 MediaPlayer
            releaseMediaPlayer();
            
            // 创建新的 MediaPlayer
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(assetManager.openFd(audioAssetPath));
            mediaPlayer.prepare();
            
            // 设置播放完成监听器：音频播放完成后开始播放第一个视频
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "哄睡音频播放完成，开始播放第一个视频");
                releaseMediaPlayer();
                // 开始播放第一个视频
                currentSleepVideoIndex = 0;
                playCurrentSleepVideo();
            });
            
            // 设置错误监听器
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "播放哄睡音频失败: what=" + what + ", extra=" + extra);
                releaseMediaPlayer();
                // 如果音频播放失败，直接开始播放视频
                currentSleepVideoIndex = 0;
                playCurrentSleepVideo();
                return true;
            });
            
            // 开始播放音频
            mediaPlayer.start();
            
        } catch (Exception e) {
            Log.e(TAG, "启动哄睡模式失败", e);
            isSleepMode = false;
            sleepVideoList.clear();
            currentSleepVideoIndex = -1;
            currentState = 2;
            startTimeoutReset();
        }
    }
    
    /**
     * 播放当前索引的哄睡视频
     */
    private void playCurrentSleepVideo() {
        if (!isSleepMode || sleepVideoList.isEmpty() || currentSleepVideoIndex < 0) {
            Log.w(TAG, "哄睡模式状态异常，无法播放视频");
            return;
        }
        
        if (currentSleepVideoIndex >= sleepVideoList.size()) {
            // 如果超出范围，重置到第一个（循环播放）
            currentSleepVideoIndex = 0;
        }
        
        String videoAssetPath = sleepVideoList.get(currentSleepVideoIndex);
        Log.d(TAG, "播放哄睡视频 [" + (currentSleepVideoIndex + 1) + "/" + sleepVideoList.size() + "]: " + videoAssetPath);
        
        // 保存当前视频路径
        currentVideoPath = videoAssetPath;
        videoPausedPosition = 0;
        
        // 通过 Broadcast 通知 MainActivity 播放视频
        Intent intent = new Intent("com.example.aismartscreen.PLAY_VIDEO");
        intent.putExtra("video_path", videoAssetPath);
        sendBroadcast(intent);
        
        // 设置一个超时计时器（作为备用）
        if (videoTimeoutRunnable != null) {
            mainHandler.removeCallbacks(videoTimeoutRunnable);
        }
        videoTimeoutRunnable = () -> {
            if (currentState == 3 && isSleepMode) {
                Log.w(TAG, "哄睡视频播放超时（60秒），继续播放下一首");
                playNextSleepVideo();
            }
        };
        mainHandler.postDelayed(videoTimeoutRunnable, 60000); // 60秒超时
    }
    
    /**
     * 播放下一首哄睡视频
     */
    private void playNextSleepVideo() {
        if (!isSleepMode || sleepVideoList.isEmpty()) {
            Log.w(TAG, "不在哄睡模式或视频列表为空");
            return;
        }
        
        // 停止当前视频
        stopVideo();
        
        // 切换到下一首（循环）
        currentSleepVideoIndex++;
        if (currentSleepVideoIndex >= sleepVideoList.size()) {
            currentSleepVideoIndex = 0; // 循环到第一个
        }
        
        Log.d(TAG, "切换到下一首哄睡视频，索引: " + currentSleepVideoIndex);
        
        // 取消超时重置
        cancelTimeoutReset();
        
        // 播放下一首视频
        currentState = 3;
        playCurrentSleepVideo();
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
