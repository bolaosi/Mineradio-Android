# Node 开发参考后端

本目录保留原有 Node / NeteaseCloudMusicApi 服务，仅用于开发、测试和协议对照，不打入 APK。
最终 Android 产品采用纯 Java 原生 API，安装后无需部署 Node 或填写远程后端地址。本地播放完全离线；在线搜索与登录仍需访问第三方官方服务。
以下远程服务步骤只供开发/自测使用，不是最终 APK 的安装要求。
**本次未替用户部署公网服务，也未用真实账号验证网易/QQ。**

## 单用户边界

原服务将网易和 QQ 的凭据分别存入全局 `.cookie`、`.qq-cookie`，因此每个实例只能由本人使用。
本修复增加所有路由的 Bearer 认证、限定 Origin，默认只监听 `127.0.0.1`；它没有多用户会话隔离。
不要将同一实例/令牌分发给他人，否则仍会共享或替换同一账号状态。需要多人服务时，应先实现真正的独立存储与会话隔离。
桌面更新、安装器下载、补丁写文件、beatmap 写缓存 API 在此远程模式下直接禁用。
不要通过端口转发直接暴露未加 TLS 的 HTTP 服务。自行控制的公网服务器应在 HTTPS 反向代理后运行，且不记录 Authorization/Cookie 请求头及请求体。

## 安装与运行（现有个人电脑/服务器，Node 18 或以上）

在 `app/src/main/assets/nodejs-project` 运行；推荐受维护的 Node LTS。依赖固定为 NeteaseCloudMusicApi 4.32.0，并提供 package-lock.json。

PowerShell：

```powershell
npm.cmd ci --ignore-scripts --no-audit --no-fund
npm.cmd test
$env:MINERADIO_API_TOKEN = (& node -e "process.stdout.write(require('crypto').randomBytes(32).toString('hex'))")
$env:HOST = '0.0.0.0' # 仅为了同一局域网手机访问；本机使用则保留默认 127.0.0.1
$env:PORT = '3000'
npm.cmd start
```

Linux/macOS shell：

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm test
export MINERADIO_API_TOKEN="$(node -e "process.stdout.write(require('crypto').randomBytes(32).toString('hex'))")"
export HOST=127.0.0.1
export PORT=3000
npm start
```

`MINERADIO_API_TOKEN` 必须至少 32 个字符。将生成的令牌通过你自己的安全方式填入 App“在线设置”；不应贴到 issue、测试报告或 Git。
重新生成令牌后，手机须同步修改。上述会话环境变量随终端结束消失；长期服务应由本人的服务管理器或受保护的环境配置保存，配置文件不提交 Git。

自建开发客户端连接时使用服务根地址，例如 `http://电脑局域网IP:3000`（同一可信局域网）或你的 `https://域名`，不填 `/api`；最终 APK 使用原生 API，不提供此后端地址设置。
手机的 `127.0.0.1` 指手机本身；电脑服务必须用电脑局域网 IP。Android 模拟器访问宿主可用 `http://10.0.2.2:3000`，前提是宿主绑定/防火墙允许。
不需要购买任何资源；未运行服务时，本地音乐仍可使用。

自建开发客户端须在请求头携带 `Authorization: Bearer 令牌` 调用本版本 `/api/health`，核对 `service=mineradio, apiVersion=1, mode=single-user, authRequired=true`。最终 APK 使用原生 API，服务就绪与第三方账号登录验证仍是两件事。
`MINERADIO_ALLOWED_ORIGINS` 默认 `https://mineradio.local`；需要自己开发浏览器测试页时可用逗号显式添加本人的测试 Origin，不能设通配符。

Cookie 可用 `COOKIE_FILE` / `QQ_COOKIE_FILE` 指定持久化文件的绝对路径，父目录须存在，且仅服务用户可访问。
默认写入本目录，已加入 .gitignore。容器/服务器重启要挂载持久化目录或保留这两个文件；已保存的凭据在每次状态查询时重新向官方验证，不以“文件存在”当作登录有效。

## 网易云登录与同屏扫码

App 点登录 → 网易云 → 刷新二维码。二维码流程为 key → create → check，分别展示等待扫码、已扫待确认、过期、失败、验证和成功。
`803` 仅代表扫码确认；后端取得 Cookie、官方账号查询返回真实正数账号 ID 并保存成功，再经前端状态复查，才显示已登录。
关闭登录窗口、切换平台、刷新二维码或退出页面会停止旧轮询并丢弃迟到结果。

同屏可点“保存二维码 PNG”，在 Android 系统选择保存位置，再到网易云 App 的扫一扫选择相册识别；该入口是否支持文档保存位置/二维码识别取决于网易云 App 和设备。
若相册看不到，可在系统文件应用将 PNG 保存到图片目录，或用另一台设备显示/扫描二维码。
本轮没有手机和真实账号可验证相册识别，所以不能保证每个系统版本都有同名菜单。
二维码有时效，过期需返回刷新。风控、验证码、网络/官方接口变化需要在官方 App 处理，不绕过限制。

## QQ 音乐备用 Cookie 导入

**自动网页或扫码登录的凭据回传仍未完成。** 原上游 Electron 使用 BrowserWindow 独立 session 分区读取 Cookie 并回传，不是可直接调用的 Android API；将系统浏览器打开不等于回传 Cookie。

1. 仅在本人电脑浏览器打开并登录 `https://y.qq.com/`。
2. 浏览器开发者工具的 Network 中选择本人刚触发的 y.qq.com 请求，将 Request Headers 的完整 Cookie 值复制（不是把任何别的网站 Cookie 发来）。HttpOnly Cookie 无法由 `document.cookie` 完整导出，优先查看本人请求头。
3. 最终 APK 中选择登录 → QQ 音乐 → 粘贴 →“验证并保存”，由 Java 原生 API 验证并保存在 App 私有空间。测试本 Node 参考服务时，开发客户端可将完整值 POST 到受令牌保护的 `/api/qq/login/cookie`。

后端沿用原有字段（uin/qqmusic_uin/wxuin；qm_keyst/qqmusic_key/music_key 等）；不要凭记忆手拼格式。
验证调用官方当前登录用户接口 `music.UserInfo.userInfoServer/GetLoginUserInfo`，无查询目标用户参数；公共资料页按 uin 查到昵称不算凭据有效。
只有顶层和子请求成功、官方返回非空昵称且返回账号（如有）相符时才保存。上游拒绝、缺少票据、网络失败都保持未验证，且不替换已有凭据。
字段外观完整不保证有效。`playbackKeyReady` 仅说明已有原服务支持的播放票据字段，不证明任一歌曲有播放/版权/会员权限。

协议实现参考源码：[原上游登录窗口](https://github.com/XxHuberrr/Mineradio-paused/blob/main/desktop/main.js)、[GetLoginUserInfo 请求形状](https://github.com/chongya369/qqmusic-api-py/blob/main/qqmusic/apis/user.py)。本修复没有引入这些项目的完整依赖。

## 验证范围

`npm test` 包含真实本地 Node HTTP 启动/健康检查/鉴权/无效导入，以及注入模拟官方响应的状态机与凭据测试。
模拟响应测试覆盖 801/802/803/800、重试、取消与迟到响应、不完整凭据拒绝、无网络不假成功、验证后保存和退出清理。
这些测试只验证程序处理流程；不代表网易/QQ 真实登录、歌单查询或受版权限制歌曲播放已经通过。真实账号步骤必须由本人完成。
