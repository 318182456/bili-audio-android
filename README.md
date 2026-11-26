# 哔哩听单

原生 Android 项目。当前包含一个应用模块和启动页。

## 打开项目

准备 Android SDK 35、JDK 17 和 Gradle 8.9。在 Android Studio 中打开本目录，Gradle 设置选择本机 Gradle 8.9，然后同步并在 Android 8.0（API 26）或以上的设备运行 `app`。

也可在工程目录运行 `gradle assembleDebug`。需要 Gradle Wrapper 时，运行 `gradle wrapper --gradle-version 8.9` 生成。

## 功能方向

- 从 B 站链接获取音轨和可用的 AI 字幕。
- 将单个视频中的长音轨按章节时间点分集，支持手动调整。
- 离线播放并显示同步字幕，支持后台播放和与其他应用同时播放。
