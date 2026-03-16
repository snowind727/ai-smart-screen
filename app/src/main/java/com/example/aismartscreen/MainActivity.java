package com.example.aismartscreen;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 单 Activity，启动后请求录音权限，然后启动前台 Service 持续监听麦克风。
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_RECORD_AUDIO = 1;
    private TextView tvStatus;
    private ImageView ivBackground;
    private VideoView videoAnswer;
    private BroadcastReceiver videoPlayReceiver;
    // 当前播放的视频临时文件
    private File currentVideoTempFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        ivBackground = findViewById(R.id.iv_background);
        videoAnswer = findViewById(R.id.video_answer);

        // 注册广播接收器，接收播放视频的广播
        registerVideoPlayReceiver();

        // 注意：视频播放完成和错误监听器在 playAnswerVideo 方法中动态设置，
        // 因为需要在回调中删除对应的临时文件

        // 检查录音权限
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startAudioService();
        } else {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    /**
     * 注册播放视频的广播接收器
     */
    private void registerVideoPlayReceiver() {
        videoPlayReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("com.example.aismartscreen.PLAY_VIDEO".equals(action)) {
                    String videoPath = intent.getStringExtra("video_path");
                    if (videoPath != null) {
                        playAnswerVideo(videoPath);
                    }
                } else if ("com.example.aismartscreen.PAUSE_VIDEO".equals(action)) {
                    pauseVideo();
                } else if ("com.example.aismartscreen.RESUME_VIDEO".equals(action)) {
                    String videoPath = intent.getStringExtra("video_path");
                    int position = intent.getIntExtra("position", 0);
                    resumeVideo(videoPath, position);
                } else if ("com.example.aismartscreen.STOP_VIDEO".equals(action)) {
                    stopVideo();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.example.aismartscreen.PLAY_VIDEO");
        filter.addAction("com.example.aismartscreen.PAUSE_VIDEO");
        filter.addAction("com.example.aismartscreen.RESUME_VIDEO");
        filter.addAction("com.example.aismartscreen.STOP_VIDEO");
        registerReceiver(videoPlayReceiver, filter);
    }

    /**
     * 播放答案视频
     */
    private void playAnswerVideo(String videoAssetPath) {
        new Thread(() -> {
            File tempFile = null;
            try {
                Log.d(TAG, "开始播放答案视频: " + videoAssetPath);
                
                // 将 assets 中的视频文件复制到临时文件
                tempFile = new File(getCacheDir(), "temp_video_" + System.currentTimeMillis() + ".mp4");
                InputStream is = getAssets().open(videoAssetPath);
                FileOutputStream fos = new FileOutputStream(tempFile);
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
                fos.flush(); // 确保数据写入磁盘
                fos.close();
                is.close();
                
                // 验证文件是否存在且可读
                if (!tempFile.exists() || !tempFile.canRead()) {
                    throw new Exception("临时文件创建失败或不可读: " + tempFile.getAbsolutePath());
                }
                
                final String tempFilePath = tempFile.getAbsolutePath();
                final File finalTempFile = tempFile; // 用于在回调中删除
                
                Log.d(TAG, "视频文件复制完成: " + tempFilePath + ", 文件大小: " + tempFile.length());
                
                // 在主线程更新 UI 并播放视频
                runOnUiThread(() -> {
                    try {
                        // 先停止之前的播放（如果有）
                        if (videoAnswer.isPlaying()) {
                            videoAnswer.stopPlayback();
                        }
                        
                        // 保存当前视频文件引用
                        currentVideoTempFile = finalTempFile;
                        
                        // 隐藏背景图，显示视频
                        ivBackground.setVisibility(View.GONE);
                        
                        // 设置视频源（在设置可见性之前）
                        videoAnswer.setVideoPath(tempFilePath);
                        
                        // 设置播放完成监听器（每次播放前都设置，确保能删除对应的临时文件）
                        videoAnswer.setOnCompletionListener(mp -> {
                            Log.d(TAG, "答案视频播放完成，删除临时文件: " + finalTempFile.getAbsolutePath());
                            // 删除临时文件
                            if (finalTempFile.exists()) {
                                finalTempFile.delete();
                            }
                            currentVideoTempFile = null;
                            // 隐藏视频，显示背景图
                            videoAnswer.setVisibility(View.GONE);
                            ivBackground.setVisibility(View.VISIBLE);
                            // 通知 Service 视频播放完成
                            Intent intent = new Intent("com.example.aismartscreen.VIDEO_PLAYBACK_COMPLETE");
                            sendBroadcast(intent);
                        });
                        
                        // 设置错误监听器
                        videoAnswer.setOnErrorListener((mp, what, extra) -> {
                            Log.e(TAG, "播放答案视频失败: what=" + what + ", extra=" + extra);
                            // 删除临时文件
                            if (finalTempFile.exists()) {
                                finalTempFile.delete();
                            }
                            currentVideoTempFile = null;
                            // 隐藏视频，显示背景图
                            videoAnswer.setVisibility(View.GONE);
                            ivBackground.setVisibility(View.VISIBLE);
                            // 通知 Service 视频播放完成（即使是错误）
                            Intent intent = new Intent("com.example.aismartscreen.VIDEO_PLAYBACK_COMPLETE");
                            sendBroadcast(intent);
                            return true;
                        });
                        
                        // 最后设置可见性并开始播放
                        videoAnswer.setVisibility(View.VISIBLE);
                        videoAnswer.start();
                        
                    } catch (Exception e) {
                        Log.e(TAG, "播放视频失败", e);
                        // 删除临时文件
                        if (finalTempFile != null && finalTempFile.exists()) {
                            finalTempFile.delete();
                        }
                        currentVideoTempFile = null;
                        // 隐藏视频，显示背景图
                        videoAnswer.setVisibility(View.GONE);
                        ivBackground.setVisibility(View.VISIBLE);
                        // 通知 Service 视频播放完成（即使是错误）
                        Intent intent = new Intent("com.example.aismartscreen.VIDEO_PLAYBACK_COMPLETE");
                        sendBroadcast(intent);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "复制或播放视频失败: " + videoAssetPath, e);
                // 删除临时文件（如果存在）
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
                runOnUiThread(() -> {
                    currentVideoTempFile = null;
                    // 隐藏视频，显示背景图
                    videoAnswer.setVisibility(View.GONE);
                    ivBackground.setVisibility(View.VISIBLE);
                    // 通知 Service 视频播放完成（即使是错误）
                    Intent intent = new Intent("com.example.aismartscreen.VIDEO_PLAYBACK_COMPLETE");
                    sendBroadcast(intent);
                });
            }
        }).start();
    }
    
    /**
     * 暂停视频播放
     */
    private void pauseVideo() {
        if (videoAnswer.isPlaying()) {
            int position = videoAnswer.getCurrentPosition();
            videoAnswer.pause();
            Log.d(TAG, "视频已暂停，当前位置: " + position + "ms");
            // 通知 Service 视频暂停位置
            Intent intent = new Intent("com.example.aismartscreen.VIDEO_PAUSED");
            intent.putExtra("position", position);
            sendBroadcast(intent);
        }
    }
    
    /**
     * 恢复视频播放
     */
    private void resumeVideo(String videoAssetPath, int position) {
        if (currentVideoTempFile != null && currentVideoTempFile.exists() && videoAnswer.getVisibility() == View.VISIBLE) {
            // 如果临时文件还在且视频可见，直接恢复播放
            try {
                videoAnswer.seekTo(position);
                videoAnswer.start();
                Log.d(TAG, "视频已恢复播放，从位置: " + position + "ms");
            } catch (Exception e) {
                Log.e(TAG, "恢复视频播放失败", e);
                // 如果恢复失败，重新播放视频
                playAnswerVideo(videoAssetPath);
                videoAnswer.setOnPreparedListener(mp -> {
                    videoAnswer.seekTo(position);
                    videoAnswer.start();
                });
            }
        } else {
            // 如果临时文件不存在或视频不可见，重新播放视频
            Log.d(TAG, "临时文件不存在或视频不可见，重新播放视频: " + videoAssetPath);
            playAnswerVideo(videoAssetPath);
            // 等待视频准备完成后跳转到指定位置
            videoAnswer.setOnPreparedListener(mp -> {
                videoAnswer.seekTo(position);
                videoAnswer.start();
            });
        }
    }
    
    /**
     * 停止视频播放
     */
    private void stopVideo() {
        if (videoAnswer.isPlaying()) {
            videoAnswer.stopPlayback();
        }
        // 删除临时文件
        if (currentVideoTempFile != null && currentVideoTempFile.exists()) {
            currentVideoTempFile.delete();
            currentVideoTempFile = null;
        }
        // 隐藏视频，显示背景图
        videoAnswer.setVisibility(View.GONE);
        ivBackground.setVisibility(View.VISIBLE);
        Log.d(TAG, "视频已停止");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 注销广播接收器
        if (videoPlayReceiver != null) {
            unregisterReceiver(videoPlayReceiver);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudioService();
            } else {
                tvStatus.setText("录音权限被拒绝，无法启动监听");
            }
        }
    }

    private void startAudioService() {
        // 不再显示"录音监听中..."文字，只显示背景图
        Intent intent = new Intent(this, AudioService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
