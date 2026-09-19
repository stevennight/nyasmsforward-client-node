# NyaSmsForward Client-Node

NyaSmsForward 的**接收端**（Android，Kotlin + Jetpack Compose）：装在放 SIM 卡收短信的那台手机上，监听短信并上报到服务端；可选接收平台下发的发送任务（回复 / 新发），由手机代发；可选同步手机自带短信 App 里发出的短信。

> 你平时用来看短信、回复短信的主力手机装的是 `nyasmsforward-client`，不是这个 App。

## 仓库关系

NyaSmsForward 由三个独立仓库组成，互不依赖代码，只通过服务端仓库的 `docs/协议.md` 对接：

- `nyasmsforward-server`：服务端 + Web 管理台
- `nyasmsforward-client-node`（本仓库）：接收端
- `nyasmsforward-client`：客户端（Windows + Android）

## 当前状态

**M0 脚手架已完成**：工程能构建、测试、签名发布；界面只是外壳。已经落地并有测试的协议相关逻辑：

- `policy/SendPolicy`：三档下发策略（关闭 / 仅回复 / 允许新发）及“取较严者”。
- `sms/PeerKey`、`sms/DedupeKey`：号码归一化和去重键，与服务端共用 [协议 §5.2](../server/docs/协议.md) 里的测试向量。
- `net/ServerUrl`：服务器地址校验（必须 https，仅 localhost / 局域网可用 http）。
- `net/Connection`、`net/Backoff`：“什么情况才算令牌失效”的严格判定和重连退避（协议 §2.2）。

配对、接收和上报从 M1 开始，见 [开发计划](docs/开发计划.md)。

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
