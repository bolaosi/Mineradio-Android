# Mineradio Android

> 把 Mineradio 桌面版音乐播放器搬到 Android 上！
> 原项目：https://github.com/XxHuberrr/Mineradio

Mineradio 是一款沉浸式音乐播放器，融合天气电台、歌词舞台、粒子视觉和 3D 歌单架。
本仓库将其 Electron 桌面版移植为 Android APK，保留完整的 UI 和动画体验。

---

## 下载 APK

### 方法一：直接下载（推荐）

点此下载最新 APK：
https://github.com/bolaosi/Mineradio-Android/releases/latest

安装要求：
- Android 8.0+（API 24+）
- 建议 4GB 内存以上
- 需要开启"允许安装未知来源应用"

### 方法二：自行构建（GitHub Actions 云端构建，免费）

如果你有 GitHub 账号，可以在云端免费构建，无需本地安装任何开发工具。

步骤：
1. Fork 本仓库到你自己的 GitHub
2. 进入你仓库的 Actions 标签
3. 左边栏点击 Build Mineradio APK
4. 点击右边 Run workflow -> 再点 Run workflow
5. 等待 5-10 分钟，构建完成
6. 点进运行记录，在 Artifacts 下载 Mineradio-Android-APK

---

## 核心功能

| 功能 | 支持 | 说明 |
|------|------|------|
| 网易云音乐搜索/播放 | 支持 | 内嵌 NeteaseCloudMusicApi |
| QQ 音乐搜索/播放 | 支持 | 同上 |
| 3D 粒子视觉特效 | 支持 | 基于 Three.js |
| 滚动歌词 | 支持 | 完整歌词舞台 |
| 天气电台 | 支持 | 根据天气推荐 |
| 均衡器 | 支持 | 内置 DSP 效果 |
| 本地音乐播放 | 开发中 | 待完成 |
| 通知栏控制 | 计划中 | 待完成 |

---

## 技术架构

WebView (Android) -> index.html + Three.js + GSAP
Node.js (nodejs-mobile) -> server.js + NeteaseCloudMusicApi
Electron API 存根 -> 桌面功能兼容层

---

## 项目结构

`
mineradio-android/
  .github/workflows/build-apk.yml   自动构建
  app/build.gradle                   构建配置
  app/src/main/AndroidManifest.xml
  app/src/main/assets/index.html     Web 前端
  app/src/main/assets/vendor/        第三方库
  app/src/main/assets/nodejs-project/ 后端
  app/src/main/java/.../MainActivity.java
  app/src/main/java/.../NodeService.java
`

---

## 本地开发

需要先安装 Android Studio、JDK 17+、Node.js 18+

`
cd app/src/main/assets/nodejs-project
npm install
`

然后用 Android Studio 打开项目根目录，Build -> Build APK(s)

---

## 已知问题

- 桌面歌词：Android 上不可用（已禁用）
- 壁纸模式：Android 上不可用（已禁用）
- 全局快捷键：Android 上不可用
- Google Fonts：首次加载需联网
- Node.js 后端首次启动会慢一些

---

## 许可

本项目遵循原项目 XxHuberrr/Mineradio 的许可协议。
