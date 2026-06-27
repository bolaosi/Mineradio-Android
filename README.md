# Mineradio Android ??

> 把 Mineradio 桌面版音乐播放器搬到 Android 上！  
> 原项目：[XxHuberrr/Mineradio](https://github.com/XxHuberrr/Mineradio)

Mineradio 是一款沉浸式音乐播放器，融合天气电台、歌词舞台、粒子视觉和 3D 歌单架。  
本仓库将其 **Electron 桌面版** 移植为 Android APK，保留完整的 UI 和动画体验。

---

## ?? 截图 / 效果

| 界面 | 说明 |
|------|------|
| ?? 3D 粒子视觉 | Three.js 粒子特效，随音乐律动 |
| ??? 天气电台 | 根据天气变化自动推荐音乐 |
| ?? 歌词舞台 | 滚动歌词 + 动态背景 |
| ??? 3D 歌单架 | 立体封面墙浏览歌单 |

---

## ?? 下载 APK

### 方法一：直接下载（推荐）

[?? 点此下载最新 APK](../../releases/latest)

**安装要求：**
- Android 8.0+（API 24+）
- 建议 4GB 内存以上
- 需要开启"允许安装未知来源应用"

### 方法二：自行构建

> 如果你有 GitHub 账号，可以在云端免费构建，无需本地安装任何开发工具。

**步骤：**

1. Fork 本仓库到你自己的 GitHub
2. 进入你仓库的 **Actions** 标签
3. 左边栏点击 **Build Mineradio APK**
4. 点击右边 **Run workflow** → 再点 **Run workflow**
5. 等待 5-10 分钟，构建完成
6. 点进运行记录，在 **Artifacts** 下载 `Mineradio-Android-APK`

---

## ? 核心功能

| 功能 | 支持情况 | 说明 |
|------|---------|------|
| ?? 网易云音乐搜索/播放 | ? | 内嵌 NeteaseCloudMusicApi |
| ?? QQ 音乐搜索/播放 | ? | 同上 |
| ?? 3D 粒子视觉特效 | ? | 基于 Three.js |
| ?? 滚动歌词 | ? | 完整歌词舞台 |
| ??? 天气电台 | ? | 根据天气推荐 |
| ??? 均衡器 | ? | 内置 DSP 效果 |
| ?? 本地音乐播放 | ? | 开发中 |
| ?? 通知栏控制 | ? | 计划中 |

---

## ??? 技术架构

```
┌─────────────────────┐
│  WebView (Android)  │  ← 渲染 Web UI
│  ┌───────────────┐  │
│  │  index.html   │  │  ← 1.3MB 单页应用
│  │  Three.js     │  │  ← 3D 粒子/动画
│  │  GSAP         │  │  ← 动画引擎
│  └───────────────┘  │
├─────────────────────┤
│  Node.js 运行时     │  ← nodejs-mobile-android
│  ┌───────────────┐  │
│  │  server.js    │  │  ← 音乐 API 后端
│  │  Netease API  │  │  ← 网易云/QQ音乐
│  └───────────────┘  │
├─────────────────────┤
│  Electron API 存根  │  ← 桌面功能兼容层
└─────────────────────┘
```

---

## ?? 项目结构

```
mineradio-android/
├── .github/workflows/
│   └── build-apk.yml        ← GitHub Actions 自动构建
├── app/
│   ├── build.gradle          ← Android 构建配置
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── index.html    ← Mineradio Web 前端
│       │   ├── vendor/       ← Three.js / GSAP
│       │   └── nodejs-project/ ← Node.js 后端
│       ├── java/com/mineradio/android/
│       │   ├── MainActivity.java  ← WebView 主界面
│       │   └── NodeService.java   ← Node.js 管理
│       └── res/
├── build.gradle
├── settings.gradle
└── README.md
```

---

## ?? 本地开发

需要先在本地安装：
- **Android Studio**（推荐）或 Android SDK + Gradle
- **JDK 17+**（[下载](https://adoptium.net/)）
- **Node.js 18+**

```bash
# 1. 安装后端依赖
cd app/src/main/assets/nodejs-project
npm install

# 2. 用 Android Studio 打开项目根目录
# 3. 等待 Gradle Sync 完成
# 4. Build → Build APK(s)
```

或命令行构建：

```bash
# Windows
gradlew.bat assembleDebug

# macOS/Linux
./gradlew assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/`

---

## ?? 已知问题

- **桌面歌词**：Android 上不可用（已禁用）
- **壁纸模式**：Android 上不可用（已禁用）
- **全局快捷键**：Android 上不可用
- **Google Fonts**：首次加载需联网
- **Node.js 后端首次启动**：第一次启动会解压提取 node_modules，稍慢

---

## ?? 许可

本项目遵循原项目 [XxHuberrr/Mineradio](https://github.com/XxHuberrr/Mineradio) 的许可协议。

---

## ?? 致谢

- [XxHuberrr](https://github.com/XxHuberrr) — Mineradio 原作
- [nodejs-mobile](https://github.com/nodejs-mobile) — Android Node.js 运行时
- [NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi) — 音乐 API
