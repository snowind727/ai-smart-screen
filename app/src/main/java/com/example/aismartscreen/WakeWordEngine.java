package com.example.aismartscreen;

import android.content.Context;
import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;

/**
 * 唤醒词检测引擎，封装 sherpa-onnx 的 KeywordSpotter。
 */
public class WakeWordEngine {

    private static final String TAG = "WakeWordEngine";

    // 模型文件在 assets 中的路径前缀
    private static final String MODEL_DIR = "kws/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01";

    private KeywordSpotter spotter;
    private OnlineStream stream;
    private final int sampleRate;

    public interface WakeUpCallback {
        void onWakeUp(String keyword);
    }

    private final WakeUpCallback callback;

    /**
     * @param context    用于访问 assets
     * @param sampleRate 和 AudioRecord 一致的采样率（通常 16000）
     * @param callback   检测到唤醒词时的回调
     */
    public WakeWordEngine(Context context, int sampleRate, WakeUpCallback callback) {
        this.sampleRate = sampleRate;
        this.callback = callback;
        init(context.getAssets());
    }

    private void init(AssetManager assetManager) {
        // 1. 配置 Transducer 模型路径（相对于 assets 目录）
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig(
            MODEL_DIR + "/encoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx",
            MODEL_DIR + "/decoder-epoch-99-avg-1-chunk-16-left-64.int8.onnx",
            MODEL_DIR + "/joiner-epoch-99-avg-1-chunk-16-left-64.int8.onnx"
        );

        // 2. 模型总配置 (OnlineModelConfig) - 使用默认构造函数和 setter
        OnlineModelConfig modelConfig = new OnlineModelConfig();
        modelConfig.setTransducer(transducer);
        modelConfig.setTokens(MODEL_DIR + "/tokens.txt");
        modelConfig.setNumThreads(2);
        modelConfig.setDebug(false);

        // 3. 特征配置（和采样率匹配）
        FeatureConfig featConfig = new FeatureConfig(sampleRate, 80, 0.0f);

        // 4. KWS 总配置 - 使用默认构造函数和 setter
        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.setFeatConfig(featConfig);
        config.setModelConfig(modelConfig);
        config.setKeywordsFile(MODEL_DIR + "/keywords.txt");
        config.setMaxActivePaths(4);
        config.setKeywordsScore(1.0f);
        config.setKeywordsThreshold(0.5f);
        config.setNumTrailingBlanks(2);

        // 5. 创建 spotter（传入 assetManager 以便从 assets 读取模型）
        spotter = new KeywordSpotter(assetManager, config);
        stream = spotter.createStream("");
    }

    /**
     * 喂入一帧 PCM 数据（16bit 小端序 byte[]），
     * 在录音线程中调用即可。
     */
    public void feedAudio(byte[] pcmBytes, int readBytes) {
        if (stream == null || spotter == null) return;
        // byte[] → float[] (归一化到 -1.0 ~ 1.0)
        int sampleCount = readBytes / 2;
        float[] samples = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int lo = pcmBytes[i * 2] & 0xFF;
            int hi = pcmBytes[i * 2 + 1];
            short sample = (short) (lo | (hi << 8));
            samples[i] = sample / 32768.0f;
        }

        // 喂入 stream
        stream.acceptWaveform(samples, sampleRate);

        // 尝试解码
        while (spotter.isReady(stream)) {
            spotter.decode(stream);
        }

        // 检查结果
        String keyword = spotter.getResult(stream).getKeyword();
        if (keyword != null && !keyword.isEmpty() && callback != null) {
            callback.onWakeUp(keyword);
            // 关键：检测到关键词后必须重置 stream，否则无法继续检测下一个关键词
            // 通过重新创建 stream 实现重置（兼容所有 sherpa-onnx 版本）
            if (stream != null && spotter != null) {
                stream.release();
                stream = spotter.createStream("");
            }
        }
    }

    /**
     * 重置 stream 状态，用于恢复检测能力（如超时重置到状态0后）。
     * 可在需要时由 AudioService 调用。
     */
    public void resetStream() {
        if (stream != null && spotter != null) {
            stream.release();
            stream = spotter.createStream("");
        }
    }

    /**
     * 释放引擎资源，在 Service onDestroy 时调用。
     */
    public void release() {
        if (stream != null) {
            stream.release();
            stream = null;
        }
        if (spotter != null) {
            spotter.release();
            spotter = null;
        }
    }
}
