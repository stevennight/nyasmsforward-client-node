# NyaSmsForward Client-Node

NyaSmsForward 的**接收端**（Android，Kotlin + Jetpack Compose）：装在放 SIM 卡收短信的那台手机上，监听短信并上报到服务端；可选接收平台下发的发送任务（回复 / 新发），由手机代发；可选同步手机自带短信 App 里发出的短信。

> 你平时用来看短信、回复短信的主力手机装的是 `nyasmsforward-client`，不是这个 App。

## 仓库关系

NyaSmsForward 由三个独立仓库组成，互不依赖代码，只通过服务端仓库的 `docs/协议.md` 对接：

- `nyasmsforward-server`：服务端 + Web 管理台
- `nyasmsforward-client-node`（本仓库）：接收端
- `nyasmsforward-client`：客户端（Windows + Android）

## 当前状态

**M1 收信闭环已完成**：能配对、接收短信、本地排队并上报到服务端，令牌失效时不丢短信。

- **配对与连接**：配对页（服务器地址 + 配对码，兼容 `483 920` 和全角数字）、连接设置（改地址并验证、测试连接、断开）。令牌用 Android Keystore 里的 AES-GCM 密钥加密保存，长期有效。
- **接收**：`SmsReceiver` 收 `SMS_RECEIVED`（受 `BROADCAST_SMS` 保护，别的 App 无法伪造），长短信拼成一条，识别 SIM 卡槽，写入本地队列后立即返回，不碰网络。
- **上报**：WorkManager 批量上传（≤100 条 / 批，联网时触发，失败指数退避，另有 15 分钟兜底），服务端返回 `accepted` / `duplicate` 才出队，`rejected` 单独标记；**令牌被吊销时短信继续接收并保留，重新配对后自动补传**。
- **严格的失效判定**：只有 `401` + `token_revoked / token_invalid / token_expired` 才算令牌失效，断网、5xx、反代返回的裸 401 都保留令牌重试。
- **状态页**：连接状态、待上报数量、权限（接收短信 / SIM 信息 / 通知）、最近收到的短信；侧载安装时的“受限制的设置”提示。

设计上尽量让逻辑能在 JVM 上测试：短信拼接、收信处理、上传器、配对管理器是纯 Kotlin；本地队列的 SQL 在测试里用真 SQLite（sqlite-jdbc）跑同一批语句；网络层用 MockWebServer 测。**没有在真机上跑过**，短信广播、Keystore、WorkManager 这些必须靠 Android 的部分请在真机上验证。

另有一个对着**真实服务端**跑完整流水线的端到端测试，默认跳过：

```powershell
# 在 nyasmsforward-server 仓库，用一个全新的空数据目录启动服务
$env:NYASMS_LISTEN = "127.0.0.1:18081"; $env:NYASMS_DATA = "$env:TEMP
sf-e2e"; go run ./cmd/server
# 在本仓库
$env:NYASMS_E2E_URL = "http://127.0.0.1:18081"; ./gradlew testDebugUnitTest --tests "*RealServerE2ETest"
```

它会自己完成管理员设置、生成配对码，然后覆盖：配对、收信、拼接、上报、去重、吊销、重新配对、补传、改地址。

下发（回复 / 新发）、同步发出的短信等见 [开发计划](docs/开发计划.md)。

## 开发

需要 JDK 17 和 Android SDK（`ANDROID_HOME`，含 platform 36）。

```powershell
./gradlew testDebugUnitTest          # 单元测试
./gradlew lint                       # Android Lint
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
```

- 版本号只在 `VERSION`（`MAJOR.MINOR.PATCH`）：`versionName` 取它，`versionCode = major*1000000 + minor*1000 + patch`。
- 包名 `app.nya.smsforward.node`，`minSdk 26`，`compileSdk` / `targetSdk` 36。

## 发布

推送与 `VERSION` 匹配的标签（如 `v0.1.0`）后，`release` 工作流会校验版本、跑 lint 和单元测试、用仓库 secrets 里的 keystore 签名构建 `assembleRelease`，核验签名后把 `NyaSmsForward-Node_<版本>.apk` 和 `.sha256` 上传到 GitHub Release。需要配置的仓库 secrets：

```text
ANDROID_KEYSTORE_BASE64    keystore 文件的 base64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

生成 keystore（**妥善备份**，丢失后无法用同一签名升级已安装的 App）：

```powershell
keytool -genkeypair -v -keystore nyasmsforward-node.keystore -alias nyasmsforward -keyalg RSA -keysize 4096 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes("nyasmsforward-node.keystore")) | Set-Clipboard
```

本地也可以签名：设置环境变量 `ANDROID_KEYSTORE_FILE`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 后运行 `./gradlew assembleRelease`；不设置则产出未签名的 APK。

## 为什么不上 Google Play

Play 对短信权限限制很严（只允许默认短信 App），所以只通过 GitHub Release 分发签名 APK。Android 13+ 侧载安装后，短信权限可能需要在「应用信息 → ⋮ → 允许受限制的设置」里放开，首次使用引导会提示。
