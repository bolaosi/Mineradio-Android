# Mineradio Android

将 Mineradio 桌面版 Electron 音乐播放器移植到 Android 的原生 APK。

## ?? 项目结构

```
mineradio-android/
├── app/
│   ├── build.gradle              # Android 构建配置（含 nodejs-mobile）
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── index.html         # Mineradio Web 前端（主应用）
│       │   ├── desktop-lyrics.html
│       │   ├── wallpaper.html
│       │   ├── vendor/            # Three.js / GSAP / music-tempo
│       │   ├── assets/            # 3D 粒子数据等
│       │   └── nodejs-project/    # Node.js 后端（server.js + NeteaseCloudMusicApi）
│       │       ├── server.js
│       │       ├── dj-analyzer.js
│       │       └── package.json
│       ├── java/com/mineradio/android/
│       │   ├── MainActivity.java  # WebView 主界面
│       │   └── NodeService.java   # Node.js 运行时管理
│       └── res/
├── build.gradle
├── settings.gradle
├── gradle.properties
└── .github/workflows/
    └── build-apk.yml             # GitHub Actions 自动构建
```

## ?? 功能说明

| 功能 | 状态 |
|------|------|
| ? 完整 UI 渲染（3D 粒子、歌词、皮肤） | 已完成 |
| ? GSAP / Three.js 动画 | 已完成 |
| ? Electron API 兼容（desktopWindow 存根） | 已完成 |
| ? Node.js 后端（网易云/QQ音乐 API） | 已集成（需 npm install） |
| ? 本地文件播放 | 待开发 |
| ? 桌面歌词（Android 通知替代） | 待开发 |

## ?? 构建方法

### 方法一：GitHub Actions（推荐，无需本地环境）

1. 将此仓库 Fork/Push 到你的 GitHub
2. 进入 Actions 标签页
3. 手动触发 `Build Mineradio APK` workflow
4. 等待几分钟后下载 APK artifact

### 方法二：本地 Android Studio 构建

**前置条件：**
- Android Studio Hedgehog (2023.1.1) 或更新版本
- JDK 17+
- Android SDK 34

**步骤：**
```bash
# 1. 安装 Node.js 后端依赖
cd app/src/main/assets/nodejs-project
npm install

# 2. 用 Android Studio 打开项目根目录
# 3. 等待 Gradle Sync 完成
# 4. Build → Build APK(s)
```

### 方法三：命令行构建

```bash
# Windows
gradlew.bat assembleDebug

# macOS/Linux
./gradlew assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/`

## ?? 网络音乐 API

应用内置了 **NeteaseCloudMusicApi**，提供：
- 网易云音乐搜索 / 播放 / 歌单管理
- QQ 音乐搜索 / 播放
- 扫码登录（实验性）

## ??? 技术栈

- **前端**: 原版 Mineradio Web App（HTML + CSS + Three.js + GSAP）
- **容器**: Android WebView (WebViewAssetLoader)
- **后端**: Node.js Mobile (nodejs-mobile-android) + NeteaseCloudMusicApi
- **构建**: Gradle + Android SDK 34

## ?? 已知问题与限制

1. Google Fonts 需要网络连接（首次加载）
2. 桌面歌词/壁纸模式在 Android 上不可用
3. 全局快捷键不可用
4. B 站音乐检测不可用

## ?? 许可

Mineradio 项目采用其原有许可协议。
