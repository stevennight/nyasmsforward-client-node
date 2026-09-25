# NyaSmsForward Client-Node

NyaSmsForward 的**接收端**（Android，Kotlin + Jetpack Compose）：装在放 SIM 卡收短信的那台手机上，监听短信并上报到服务端；可选接收平台下发的发送任务（回复 / 新发），由手机代发；可选同步手机自带短信 App 里发出的短信。

> 你平时用来看短信、回复短信的主力手机装的是 `nyasmsforward-client`，不是这个 App。

## 两个版本

同一套代码打出两个安装包（Gradle flavor `lite` / `full`），包名不同，可以同时安装，按需选一个：

| | 普通版 `NyaSmsForward-Node_<版本>.apk` | 完整版 `NyaSmsForward-Node-Full_<版本>.apk` |
|---|---|---|
| 包名 | `app.nya.smsforward.node`（原有安装原地升级） | `app.nya.smsforward.node.full` |
| 转发、下发、同步 | ✓ | ✓（「转发」标签页，功能相同） |
| 手机上收发短信、会话列表 | — | ✓（「短信」标签页） |
| 验证码识别、通知里一键复制 | — | ✓（规则与服务端 `sms/code.go` 相同） |
| 平台“同时删除手机短信” | ✗ 系统不允许 | ✓ 设为默认短信应用后生效，先进手机回收站 |
| 手机回收站（30 天内可恢复） | — | ✓ 网页删除和手机上删除的都在这里 |
| 银行收支、快递取件码识别 | — | ✓ 会话里显示卡片，通知里可一键复制取件码 |
| 骚扰拦截 | — | ✓ 内置规则（诈骗、赌博、色情、发票办证默认开；贷款、推广营销可选），号码黑名单（支持 `1069*` 前缀）、关键词、信任号码；验证码、银行收支、取件码、联系人不会被内置规则拦 |
| 搜索、分类筛选、全部已读 | — | ✓ 全部 / 未读 / 验证码 / 银行 / 快递 |
| 多选批量操作 | — | ✓ 长按进入多选：会话批量已读 / 拦截 / 删除，会话内批量复制 / 删除，拦截记录和回收站批量放回 / 删除 |

Android 4.4 起只有**默认短信应用**能写短信库，普通 App 的删除会被系统静默忽略（服务端审计日志会记成 `denied:not_default_sms_app`）。完整版实现了成为默认短信应用所需的组件（`SMS_DELIVER` / `WAP_PUSH_DELIVER` 接收器、来电“短信回复”服务、`SENDTO` 入口），设为默认后由它负责把收到和发出的短信写入短信库。彩信暂不支持显示，只提示“收到一条彩信”。

被拦截的短信不写入系统短信库、不弹通知，保存在本 App 的数据库里 30 天，可以“放回收件箱”。内置规则全部在手机上判断、不联网：每类有“强特征词”（命中一个就拦，如“安全账户”“百家乐”“代开发票”）和“弱特征词”（要两个一起出现，或者和链接 / “加微信”同时出现才拦），匹配前会先去掉“贷 款”“代✦开”这类插进去的空格和符号。误拦时可以把号码加入“信任号码”，规则页里的“试一试”可以粘贴一条短信看会不会被拦。拦截只影响手机：上报走的是 `SMS_RECEIVED`，和普通版一样照常上报到平台。

## 仓库关系

NyaSmsForward 由三个独立仓库组成，互不依赖代码，只通过服务端仓库的 `docs/协议.md` 对接：

- `nyasmsforward-server`：服务端 + Web 管理台
- `nyasmsforward-client-node`（本仓库）：接收端
- `nyasmsforward-client`：客户端（Windows + Android）

## 当前状态

**M1 收信闭环已完成**：能配对、接收短信、本地排队并上报到服务端，令牌失效时不丢短信。

- **配对与连接**：配对页（服务器地址 + 配对码，兼容 `483 920` 和全角数字；也可以点「扫描配对二维码」直接扫 Web 里生成的二维码，用 ZXing，不依赖 Google Play 服务，相机权限只在点扫描时才申请）、连接设置（改地址并验证、测试连接、断开）。令牌用 Android Keystore 里的 AES-GCM 密钥加密保存，长期有效。
- **接收**：`SmsReceiver` 收 `SMS_RECEIVED`（受 `BROADCAST_SMS` 保护，别的 App 无法伪造），长短信拼成一条，识别 SIM 卡槽，写入本地队列后立即返回，不碰网络。
- **上报**：WorkManager 批量上传（≤100 条 / 批，联网时触发，失败指数退避，另有 15 分钟兜底），服务端返回 `accepted` / `duplicate` 才出队，`rejected` 单独标记；**令牌被吊销时短信继续接收并保留，重新配对后自动补传**。
- **严格的失效判定**：只有 `401` + `token_revoked / token_invalid / token_expired` 才算令牌失效，断网、5xx、反代返回的裸 401 都保留令牌重试。
- **状态页**：连接状态、待上报数量、权限（接收短信 / SIM 信息 / 通知）、最近收到的短信；侧载安装时的“受限制的设置”提示。
- **应用更新**：状态页可检查本仓库 GitHub Release，下载前校验 SHA-256，随后调起 Android 系统安装确认；不支持静默安装。

## 下发：让平台经这台手机发短信（M3）

**默认关闭**，只能在这台手机的设置里开启，服务器绕不过。三档策略：

| 策略 | 行为 |
|---|---|
| 关闭 | 拒绝所有下发任务；仍保持删除同步通道 |
| 仅回复 | 只回复最近 7 天内给这台手机发过短信的号码（手机自己的记录，服务器改不了），用收信的那张卡发出 |
| 允许新发 | 除回复外，可向任意号码发短信 |

- **通道**：配对后运行前台服务（`specialUse`，常驻通知），保持到服务器的 WebSocket，承载下发任务和平台删除同步；指数退避无限重连（1 秒到 5 分钟）。只有 `401` + `token_revoked` 等（或关闭码 `4401`）才判定令牌失效，网络故障 / 5xx / 反代裸 401 都保留令牌。开机和升级后自动恢复。

删除同步需要系统允许本 App 写入短信库；部分 Android/ROM 只允许默认短信 App 删除，因此手机拒绝时平台删除仍会完成，并在审计中记录 `denied`。
- **手机端强制执行**（`SendGate`）：策略、任务过期、最近来信号码、可选收件人白名单、**每小时上限（默认 10，手机本地计数）**、仅限真实号码（字母 ID 发件人永远不可发）。不满足则回 `failed` + 原因（`policy_denied` / `recipient_not_recent` / `recipient_not_allowed` / `rate_limited` / `expired` / `no_permission` / `sim_unavailable`）。
- **选卡**：按任务里的卡槽号换算成当前的订阅 ID；该槽位没有卡就回 `sim_unavailable`，**不会**改用另一张卡（否则对方会看到陌生号码）。
- **不重发**：每个 taskId 只处理一次（本地台账 `send_tasks`），服务器重连后重复下发同一任务，只会得到上次的回执；发送中进程被杀的任务按失败上报，宁可误报失败也不重复发送。
- **回执**：`sent`（每一段都交给基站）→ `delivered`（送达报告，运营商不发就停在 sent）/ `failed`。每次连上后，最近 24 小时的回执会重发一遍，服务器对重复回执无副作用，所以断线时丢的回执最终会补上。
- 权限（开启时才申请）：`SEND_SMS`、`READ_PHONE_STATE`（把卡槽号换算成订阅）、通知；建议加入电池优化白名单。

## 同步手机上发出的短信与历史（M4，可选，默认关闭）

在手机自带短信 App 里手动发出的短信，默认不会出现在平台上。在「设置」里开启“同步发出的短信”后：

- **只读、只在开启时读**：`READ_SMS` 在打开开关的那一刻才申请；关闭后不再读取短信数据库（已上报的数据留在服务端）。
- **发件箱同步**：`ContentObserver` 监听短信库，开着本 App 时几秒内就会补上；进程被系统结束后由每 15 分钟一次的 WorkManager 兜底。用游标（发件箱 `_id`）记录读到哪，**开启时只从当前位置往后同步**，不会把旧短信一股脑倒出去。
- **历史回补**（可选 7 / 30 天，只做一次）：收件箱 + 发件箱，带 `backfill=true`，服务端直接标为已读、不弹通知。回补的号码只按短信**自己的时间**记进“最近来信号码”，不会放宽“仅回复”的范围。
- **不重复**：经平台任务发出的短信手机自己会跳过（本地台账里存了正文的 SHA-256，不存正文），服务端还有一层同号码 + 同正文 ±5 分钟的兜底；回补的收到的短信，与已实时上报的同一条（时间戳可能差一两秒）由服务端按 ±2 分钟模糊去重。
- 同步范围经 WebSocket `hello` 上报，Web 设备页仅作展示。

用真实服务端的端到端测试（`SentSyncE2ETest`）覆盖：已读 / 不弹通知、回补不重复实时上报的那条、任务自己的短信不重复。**读取真实短信数据库、ContentObserver、WorkManager 未在真机上验证。**

设计上尽量让逻辑能在 JVM 上测试：短信拼接、收信处理、上传器、配对管理器是纯 Kotlin；本地队列的 SQL 在测试里用真 SQLite（sqlite-jdbc）跑同一批语句；网络层用 MockWebServer 测。**没有在真机上跑过**，短信广播、Keystore、WorkManager 这些必须靠 Android 的部分请在真机上验证。

另有一个对着**真实服务端**跑完整流水线的端到端测试，默认跳过：

```powershell
# 在 nyasmsforward-server 仓库，用一个全新的空数据目录启动服务
$env:NYASMS_LISTEN = "127.0.0.1:18081"; $env:NYASMS_DATA = "$env:TEMP\nsf-e2e"; go run ./cmd/server
# 在本仓库
$env:NYASMS_E2E_URL = "http://127.0.0.1:18081"; ./gradlew testLiteDebugUnitTest --tests "*RealServerE2ETest"
```

它会自己完成管理员设置、生成配对码，然后覆盖：配对、收信、拼接、上报、去重、吊销、重新配对、补传、改地址。另一个 `SendE2ETest`（同样受 `NYASMS_E2E_URL` 控制，可对同一台服务器连着跑）用一个“回环收发器”代替 SmsManager，覆盖真实 WebSocket 上的：回复走原来的卡、策略取较严者、手机本地策略拒绝服务器以为允许的新发、吊销后通道以 `4401` 关闭并停止重试。

**M3 下发（回复 / 新发）和 M4 同步发出的短信 / 历史回补已完成**，见上面两节。

## 开发

需要 JDK 17 和 Android SDK（`ANDROID_HOME`，含 platform 36）。

```powershell
./gradlew testLiteDebugUnitTest testFullDebugUnitTest   # 单元测试（两个版本）
./gradlew lintLiteDebug lintFullDebug                    # Android Lint
./gradlew assembleDebug                                  # app/build/outputs/apk/{lite,full}/debug/*.apk
```

- 版本号只在 `VERSION`（`MAJOR.MINOR.PATCH`）：`versionName` 取它，`versionCode = major*1000000 + minor*1000 + patch`。
- 包名 `app.nya.smsforward.node`（完整版加后缀 `.full`），`minSdk 26`，`compileSdk` / `targetSdk` 36。

## 发布

推送与 `VERSION` 匹配的标签（如 `v0.1.0`）后，`release` 工作流会校验版本、跑 lint 和单元测试、用仓库 secrets 里的 keystore 签名构建 `assembleRelease`，核验签名后把普通版 `NyaSmsForward-Node_<版本>.apk`、完整版 `NyaSmsForward-Node-Full_<版本>.apk` 及各自的 `.sha256` 上传到 GitHub Release。需要配置的仓库 secrets：

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
