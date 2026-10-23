# 1.2.3 构建与验证

源码和必要构建文件已整理入库。Android 使用内置 Java 官方 API 客户端，不需部署后端；Node 目录只作协议参考与开发测试，不打入 APK。1.1.0 私有账号存储沿用，旧 Node 后端不需升级。

## Android

需要完整 JDK 17（含 javac/jlink）、Android SDK Platform 34 / Build Tools 34.0.0。工程包含 Gradle 8.5 wrapper 和校验值，不用系统 Java 25 运行它。

```powershell
$env:JAVA_HOME = '你的完整JDK17目录'
$env:ANDROID_HOME = '你的AndroidSDK目录'
./gradlew.bat --no-daemon --console=plain lintDebug testDebugUnitTest assembleDebug
```

Linux/macOS 使用 `chmod +x gradlew` 后运行 `./gradlew` 相同参数。标准输出是 `app/build/outputs/apk/debug/app-debug.apk`。首次构建需要网络下载 Maven/Gradle 依赖。新机器默认 debug 签名与本次交付签名不相同；不要用不同签名 APK 覆盖已安装包。此次调试私钥没有打包进源码。

Windows 中文目录如出现 Gradle 测试 worker 找不到测试类，保留源码位置，增加：

```powershell
./gradlew.bat --no-daemon --console=plain '-PmineradioBuildRoot=D:/mineradio-build' lintDebug testDebugUnitTest assembleDebug
```

这时输出为 `D:/mineradio-build/app/outputs/apk/debug/app-debug.apk`。本地交付目录的专用脚本和证据不入库；新环境按上面的通用命令构建即可。可选输出路径仅更改构建产物位置，不搬动源码。

本次结果：62项JUnit/Android框架影子测试全通过，本次离线lint0错误/28警告，assembleDebug成功。警告包含硬编码QQ页面文案/i18n及依赖相关项，未关闭lint错误规则。包名 `com.mineradio.android`，versionCode8，versionName `1.2.3-debug`，minSdk24/targetSdk34，Media3固定1.3.1。

本轮增加Robolectric 4.12.2作为**仅测试依赖**，用于执行真实Media3数据源/MP3解析/PCM处理及content文件描述符测试，不模拟手机扬声器。首次测试还会下载Android API28与编译API34的框架JAR；图形/音频原生运行时分发包未使用，已从测试依赖排除，不进入APK。

本机遇到Maven下载中断/元数据校验不一致，改从Google的Maven Central镜像取得框架JAR并核对SHA-512；没有跳过校验。框架缓存可放在任意本机目录，例如 `D:/mineradio-robolectric-runtime`。通用在线命令在正常Maven网络下可直接运行；本机已经缓存依赖，成功命令增加：

```powershell
./gradlew.bat --no-daemon --offline --console=plain `
  '-PmineradioBuildRoot=D:/mineradio-build' `
  '-PmineradioRobolectricRuntime=D:/mineradio-robolectric-runtime' `
  lintDebug testDebugUnitTest assembleDebug
```

本地交付脚本不入库，使用上面示例并设置自己的JDK/SDK即可。框架缓存的两个文件名：`android-all-instrumented-9-robolectric-4913185-2-i6.jar`、`android-all-instrumented-14-robolectric-10818077-i6.jar`，原始路径为Maven坐标 `org/robolectric/android-all-instrumented/<version>/<filename>`。可用 `https://repo.maven.apache.org/maven2/` 或 `https://maven-central.storage-download.googleapis.com/maven2/` 拼接该路径下载，使用相应 `.sha512` 校验。不要将不完整下载当作有效缓存。

真实、无账号网络探测是显式选用的测试，不默认在常规构建中联网：

```powershell
./gradlew.bat --no-daemon --offline `
  '-PmineradioBuildRoot=D:/mineradio-build' `
  '-PmineradioRobolectricRuntime=D:/mineradio-robolectric-runtime' `
  '-PmineradioLiveAudioProbe=true' '-PmineradioProbeProxy=7890' `
  testDebugUnitTest --tests '*NativeAudioLiveTest'
```

`mineradioProbeProxy`只在本机需要现有127.0.0.1代理时提供，其他环境省略。`--offline`仅限制Gradle下载依赖，这个显式探测本身仍访问官方API/CDN，账号Store为空、禁止写凭据，错误日志不输出签名地址。覆盖原曲Synergy的官方试听及一首匿名可用QQ曲目；只证明真实取源、读取和MP3样本解析，未执行设备MediaCodec/AudioTrack。

## JavaScript 与浏览器

需要 Node LTS。运行单元测试（49 项）：

```powershell
node --test tests/*.test.cjs app/src/main/assets/nodejs-project/test/*.test.js
```

真实浏览器检查另需 Playwright、Chromium、ffmpeg。`NODE_PATH` 指向已安装 Playwright 的 node_modules，`PLAYWRIGHT_CHROMIUM_EXECUTABLE` 指向 Chromium 可执行文件。无需登录浏览器账号。

```powershell
node tests/android-mobile.browser.cjs
node tests/android-mobile-controls.browser.cjs
node tests/android-native-ui.browser.cjs
node tests/android-mobile-glass.browser.cjs
node tests/android-touch.browser.cjs
```

三个脚本分别有 8 / 6 / 6 组检查：实际 MP3 解码/时间推进、真实触摸滚动、图片背景、预设保存与模拟原生状态同步。API/SAF/原生服务使用模拟响应，不是 Android 播放、息屏或真实登录验收。测试仅访问本地 fixture；预期搜索失败场景会有两条 HTTP 503 资源错误。

开发参考 Node 后端检查：在 `app/src/main/assets/nodejs-project` 运行 `npm ci --ignore-scripts --no-audit --no-fund`、`npm run check`、`npm test`。这些依赖不进入 APK。

## 设备验收仍待完成

本机构建时 `adb devices` 无设备，未安装运行本版 APK。请区分单元/浏览器通过与真实后台运行：

1. 导入三段自行准备的短音频（例如每段20秒），顺序循环，播放后锁屏至少 70 秒；应连续经过三首并循环。测试切到其他 App、通知暂停/继续/前后首；回 App 检查歌曲/队列/进度与无双声。
2. 有联网权限的三首在线曲目执行同样步骤；再测随机、单曲循环、网络中断/恢复、链接过期、其他 App 抢音频焦点与耳机拔出。播放受版权/会员权限限制的曲目应报错或明确试听，不冒充完整可播。
3. 歌单播放中滑动、搜索/加队列、视觉切换；退出重进检查设置。系统文件选择取消应保留队列；重启后仅有持久读授权的本地文件恢复为暂停，文件被删时明确报错。
4. 网易扫码登录由用户此前真机确认可用，本轮未重用真实账号。QQ 在官方窗口完成授权，必须通过账号验证后才显示登录；另行验证歌单可读与歌曲播放权限。自动路径受第三方页面/风控限制时，保留 Cookie 导入。

本轮不声称上述设备项已通过；截图中的运行时错误已用完整服务播放器复现并修复，但真机出声/硬件时钟仍未验收，当前结果与限制见 [docs/VERIFICATION.md](docs/VERIFICATION.md)。


## 1.2.3 触摸复现与边界

原本地诊断结果（输出目录不入库）：相同真实触摸输入下，DOM列表播放前后都能原生滚动，3D卡片和3D歌曲详情索引始终为0。原代码没有触摸拖动监听；不能将此直接认定为手机DOM故障的唯一原因。`tests/android-touch-baseline.browser.cjs` 是诊断脚本，用于原版本时可复现；当前运行的输出使用独立diagnostic文件名，避免覆盖原始证据。

新版 `tests/android-touch.browser.cjs` 包含26段CDP触摸，分别检查DOM和3D（侧边/舞台、一级/二级），覆盖真实本地产生MP3播放、暂停恢复、切歌、面板重开、横竖屏、取消后继续拖动、误点击与背景相机隔离；原生状态使用明确标记的模拟桥，不模拟Android声音或息屏。没有Android设备时仍需执行交付说明中的手机步骤。

准备已安装的Playwright、Chromium和ffmpeg；下面路径为示例，替换为自己机器的实际位置。也可省略环境变量，直接使用项目可解析的playwright和它的默认浏览器：

```powershell
$env:PLAYWRIGHT_MODULE = 'D:\tools\node_modules\playwright'
$env:PLAYWRIGHT_CHROMIUM_EXECUTABLE = 'D:\tools\chromium\chrome.exe'
$env:MINERADIO_TEST_PORT = '18754' # 可改为本机空闲端口；不更改系统网络配置
node tests/android-touch.browser.cjs
node tests/android-mobile-glass.browser.cjs
node tests/android-native-ui.browser.cjs
node tests/android-mobile-controls.browser.cjs
```

所有测试只访问本地模拟服务；没有远程后端更新。本轮未变动Java播放代码，完整服务音频输出回归仍通过（模拟AudioTrack）；APK资产与当前源文件逐字节核对。本地交付APK的SHA256与证书摘要见 [docs/VERIFICATION.md](docs/VERIFICATION.md)；CI重新构建的APK不是同一签名产物。
