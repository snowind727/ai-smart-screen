# AI 智慧屏

一款面向儿童的智能语音交互 Android 应用，支持语音唤醒、语音问答、视频播放和哄睡模式。

## 功能特性

### 🎙️ 语音唤醒
- 唤醒词：**你好西西**
- 唤醒后播放随机响应语音，然后进入聆听状态

### 🔤 语音指令
| 指令 | 说明 |
|------|------|
| 小狗怎么叫 / 小狗汪汪叫 | 播放小狗叫声视频 |
| 小猫怎么叫 / 小猫喵喵叫 | 播放小猫叫声视频 |
| 小鸭子怎么叫 | 播放小鸭子叫声视频 |
| 彩色球 | 播放彩色球视频 |
| 播放哈喽 | 播放 hello 相关视频 |
| 播放小跳蛙 | 播放小跳蛙视频 |
| 苹果 / 香蕉 / 西瓜 / 草莓 等 | 播放对应水果的问答视频 |
| 宝贝睡觉 | 进入哄睡模式 |
| 继续 | 恢复暂停的视频 |
| 下一首 | 切换到下一首哄睡视频 |
| 结束 | 退出当前模式，回到监听状态 |

### 🌙 哄睡模式
- 说 "宝贝睡觉" 启动
- 自动播放哄睡音频，循环播放 sleep 目录下的视频
- 支持 "下一首" 切换视频，"结束" 退出模式
- 用户可在 app/src/main/assets/answer_video/sleep 文件夹下放置自己的视频文件

### 📺 视频播放
- 支持 MP4、AVI、MOV、MKV 格式
- 视频播放时可继续监听语音指令
- 支持暂停、恢复、停止操作

## 项目结构

```
app/src/main/
├── java/com/example/aismartscreen/
│   ├── MainActivity.java      # 主界面，处理视频播放
│   ├── AudioService.java       # 前台服务，负责录音和语音识别
│   └── WakeWordEngine.java     # 唤醒词引擎
├── res/
│   └── layout/                  # 布局文件
└── assets/
    ├── response_audio/         # 唤醒响应语音（.wav）
    ├── answer_audio/           # 问答音频（.wav）
    └── answer_video/           # 问答视频（.mp4 等）
        └── sleep/              # 哄睡视频
```

## 技术栈

- **语言**：Java
- **最低 SDK**：Android 8.0 (API 26)
- **目标 SDK**：Android 14 (API 34)
- **音频处理**：AudioRecord (16kHz, 16bit, mono)
- **语音识别**：离线唤醒词检测

## 权限

- `RECORD_AUDIO` - 麦克风录音
- `FOREGROUND_SERVICE` - 前台服务（后台持续运行）

## 构建

```bash
./gradlew assembleDebug
```

## TODO

- [ ] 支持更多语音指令
- [ ] 添加在线语音识别
- [ ] 支持语音合成对话
- [ ] 家长控制界面
