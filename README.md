# 后台媒体守护（Background Media Guard）

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/sw1313/BackgroundMediaGuard)](https://github.com/sw1313/BackgroundMediaGuard/releases/latest)

面向 **LSPosed / 现代 libxposed API 101–102** 的 Xposed 模块。  
在 Android 12+ 与 HyperOS 等系统上，为你选中的媒体应用提供**后台续播**、**自动切集**相关保护，以及官方 **Plex** 的若干客户端兼容修复；不是全局关闭系统省电策略。

当前版本：**1.4.32**  
下载：[Releases](https://github.com/sw1313/BackgroundMediaGuard/releases/latest)

---

## 它解决什么问题

常见症状：

| 现象 | 典型原因 |
|------|----------|
| 退到后台约一分钟后被系统静音 | Android AudioHardening |
| 后台播放中遇到约一分钟无声段后停止续播 | 澎湃 OS / HyperOS 零数据暂停 |
| 进程还在，但播放卡住、通知消失 | 缓存冻结（Cached App Freezer）、OOM 降权 |
| Emby 能播完当前集，后台却无法自动下一集 | WebView 控制层被暂停；或片尾后无法再次进入前台服务 |
| Plex 关画中画后停播、控件消失 | 退出 PiP 时误发 `Stop` |
| Plex 后台控件显示 0:00 / 无进度条 / 点不回前台 | 媒体通知误开 chronometer，且 `contentIntent` 为空 |
| Plex 切下一集后回前台偶发黑屏（有声无画） | Surface 未可靠重绑 |
| Plex 点控件回前台 / 息屏 / 未加载完就回后台后从头播 | 激进 Intent 重挂载，或重建开播时 startPosition/seek 被写成 0 |
| Plex 画中画/息屏后 Error Occurred | Surface 拆卸过晚，HEVC 解码器进入 ERROR |

本模块按应用开启保护，尽量只在「正在播 / 刚切集」的窗口内介入。  
**Plex / Jellyfin 后台切集通常正常**，一般不必为切集去开「控制层」或「片尾 JS 桥接」。Plex 的画中画 / 控件 / 黑屏 / 从头播 / Error Occurred 等问题，用齿轮里的 **Plex 兼容修复**（五个独立开关）按需开启即可。Jellyfin 后台播放卡住、回前台播不动，或蓝牙耳机断开不停，用齿轮里的 **Jellyfin：后台只放音频，回前台接上画面**（默认开）。

---

## 功能概览

### 系统层（`system`）

- **按应用放行后台音频**：放行与播放、音频焦点等相关的 AppOps，避免合法媒体会话被静音。
- **MediaSession 跟踪**：关注 `PLAYING` / `BUFFERING` / `CONNECTING` 等状态；停止后仍有可配置的**保护宽限期**（默认 120 秒），方便切集、缓冲。
- **媒体前台等效**：在保护窗口内把进程按媒体前台服务对待，并钳制内部进程状态与写给 LMKD 的 OOM adj。
- **阻止缓存冻结**：媒体活动期间拦截 AOSP Cached App Freezer 对目标进程的冻结。
- **后台前台服务（FGS）放行**：Android 12+ 限制后台 `startForeground()`。自动下一集时若需重新拉起 `MediaService` 一类前台服务，可在保护窗口内放行（对 Emby 等连播场景很重要）。
- **共享 UID / 多进程**：尽量识别同 UID 与 Chrome 类隔离子进程。

### HyperOS（`com.miui.powerkeeper`）

- **零数据暂停保护**（可选）：澎湃 OS / HyperOS 在后台播放时，若连续约 60 秒检测不到音频数据（无声段），会暂停音轨导致无法继续播放。本项拦截 PowerKeeper 该逻辑。  
  通用 MediaSession 保护无法单独覆盖该路径，因此需要勾选 PowerKeeper。

### 应用进程内（目标 App 需在 LSPosed 作用域中）

- **控制层后台可见性保持**（可选）：保持 WebView / React Native 控制层可调度。主要用于 **Emby** 等混合客户端；**Plex / Jellyfin 后台切集通常正常，一般不必开**。
- **片尾 JS 桥接**（可选）：面向 Emby 及改包名兼容客户端，将片尾事件可靠送达页面脚本，配合系统侧 FGS 放行完成自动下一集。官方包名 `com.mb.android` **默认开启**，其他应用需在齿轮里手动打开。
- **Plex 兼容修复**（仅官方 `com.plexapp.android` 齿轮可见，**默认关**，**五个独立开关**）：

  | 开关 | 针对现象 | 做法概要 |
  |------|----------|----------|
  | 关闭画中画后继续后台播放 | 关 PiP 停播、控件消失 | 退出 PiP 时跳过误发的 `Stop` |
  | 修复后台播放控件 | 0:00 / 无进度条 / 点不回前台 | 关 chronometer、补 `contentIntent` / 时长、软化拉起 Intent |
  | 回前台恢复画面 | 切集后回前台偶发黑屏（有声无画） | `attachView` 后轻量重绑 Surface |
  | 防止息屏/回前台后从头播放 | 息屏/退 PiP/点控件/未加载完回后台后进度回到开头 | 软 Intent + 记住 startPosition + 拦 seek→0 + 纠正重建开播进度（不拦返回键） |
  | 防止画中画/息屏 Error Occurred | 弹 Error Occurred（HEVC / MediaCodecVideoRenderer） | `surfaceDestroyed` 同步卸面 + 吞拆面超时（不强制重绑画面） |

### 配置方式

- 主界面勾选要保护的应用。
- 每个应用右侧**齿轮**进入单独设置，各项带场景说明。
- 通过 **Remote Preferences** 实时下发到 system / PowerKeeper / 应用进程；改完一般无需重装模块（**应用进程内 Hook 需强停目标应用后再生效**）。
- **重启模块作用域**：可勾选要重启的进程。只改应用内开关时，勾选对应 App 即可；**system / PowerKeeper 在模块或系统侧逻辑未变时通常不必勾**。

---

## 推荐作用域

模块 `scope.list` 推荐：

| 包名 | 用途 |
|------|------|
| `system` | 音频、会话、OOM、冻结、FGS 等系统保护（**必选**） |
| `com.miui.powerkeeper` | 澎湃 OS / HyperOS 零数据暂停（小米机强烈建议） |
| `com.mb.android` | Emby（控制层 / 片尾 JS 桥接） |
| `com.plexapp.android` | Plex（画中画 / 通知控件 / 画面恢复 / 防从头播等兼容修复） |
| `org.jellyfin.mobile` | Jellyfin（后台只放音频 / 耳机暂停需要；切集本身一般不必开控制层） |

模块为 **非静态作用域**（`staticScope=false`）：可在 LSPosed 中手动勾选，也可在应用设置里打开控制层 / 片尾 JS 桥接 / Plex 兼容修复 / Jellyfin 后台只放音频时由模块**动态请求**加入作用域。

---

## 安装与使用

### 1. 环境

- 已 Root，并安装支持 **libxposed API ≥ 101** 的框架（如 LSPosed）。
- 框架需支持：`PROP_CAP_SYSTEM`、`PROP_CAP_REMOTE`（Remote Preferences）。

### 2. 安装模块

1. 从 [Releases](https://github.com/sw1313/BackgroundMediaGuard/releases/latest) 安装 APK。
2. 在 LSPosed 中启用「后台媒体守护」。
3. 作用域至少勾选 **`system`**；HyperOS 再勾选 **PowerKeeper**。
4. 使用 Emby 连播、Plex 兼容修复或 Jellyfin 后台只放音频时，把对应应用也勾进作用域（或在齿轮里打开相关开关并同意弹窗）。
5. 需要时用「重启模块作用域」勾选进程；只改 Plex/Emby 应用内开关时，强停对应 App 通常即可。

### 3. 建议配置（以 Emby 后台连播为例）

1. 主列表启用 Emby。
2. 齿轮中建议打开：
   - 放行后台音频
   - 澎湃 OS / HyperOS 零数据暂停保护（小米）
   - 媒体活动时阻止缓存冻结
   - 控制层后台可见性保持
   - **片尾 JS 桥接**（官方 Emby 默认已开；改包版请手动打开）
   - 保护宽限期：120 秒或更长
3. 同意把目标应用加入作用域后**强停该应用**，再后台播放至自动下一集。

### 4. Plex / Jellyfin

- **后台切集**：通常不依赖本模块的控制层 / JS 桥接。
- **通用续播**：按需打开放行后台音频、零数据暂停、阻止冻结等系统侧开关即可。
- **Plex 额外问题**（官方 `com.plexapp.android`）：
  1. 主列表启用 Plex，打开齿轮。
  2. **只打开你遇到的开关**（五个互相独立，默认全关）：
     - 关画中画会停 →「关闭画中画后继续后台播放」
     - 控件 0:00 / 点不回前台 →「修复后台播放控件」
     - 切集后回前台黑屏 →「回前台恢复画面」
     - 息屏/未加载完回后台后从头播 →「防止息屏/回前台后从头播放」
     - 弹 Error Occurred（HEVC）→「防止画中画/息屏 Error Occurred」
  3. 同意作用域弹窗后**强停 Plex**，再按对应场景验证。
  4. 开关各管各的：防从头播≠Error Occurred≠恢复画面；异常缩放时先关「回前台恢复画面」并确认已装 ≥1.4.26。
- **Jellyfin 后台只放音频 / 耳机暂停**（官方 `org.jellyfin.mobile`）：
  1. 系统侧保护负责让进程和界面留着。这个开关负责后台关视频轨、回前台开视频轨，以及耳机暂停，默认开启。
  2. 打开 Jellyfin 的齿轮，确认「后台只放音频，回前台接上画面」开着，并同意作用域。
  3. **强停 Jellyfin** 后再测：后台音频一直走；从应用或通知回去仍是原来的播放器，画面接着进度播；断开蓝牙耳机应暂停。

---

## 各开关说明（简表）

| 开关 | 建议 | 说明 |
|------|------|------|
| 启用模块 | 开 | 总开关 |
| 启用此应用的媒体保护 | 开 | 加入保护列表 |
| 放行后台音频 | 开 | 后台约一分钟后被系统静音 |
| 澎湃 OS / HyperOS 零数据暂停保护 | 小米开 | 后台无声段约一分钟后停止续播 |
| 媒体活动时阻止缓存冻结 | 开 | 假死、通知消失 |
| 始终保护 | 默认关 | 无 MediaSession 时兜底，更耗电 |
| 控制层后台可见性保持 | Emby 等开 | WebView/RN 切集；Plex/Jellyfin 一般不必 |
| 片尾 JS 桥接 | 官方 Emby 默认开 | Emby/改包兼容客户端的片尾连播 |
| Plex：关闭画中画后继续后台播放 | 遇该问题再开 | 关 PiP 误 Stop；仅官方 Plex |
| Plex：修复后台播放控件 | 遇该问题再开 | 0:00/无进度条/点不回前台；仅官方 Plex |
| Plex：回前台恢复画面 | 遇该问题再开 | 切集后偶发黑屏，轻量重绑画面；仅官方 Plex |
| Plex：防止息屏/回前台后从头播放 | 遇该问题再开 | 软 Intent、记 startPosition、拦 seek→0、纠正重建进度（不拦返回键）；仅官方 Plex |
| Plex：防止画中画/息屏 Error Occurred | 遇该问题再开 | surfaceDestroyed 同步卸面 + 吞拆面超时（不强制重绑）；仅官方 Plex |
| Jellyfin：后台只放音频，回前台接上画面 | 默认开 | 看不见时关同一播放器的视频轨，回前台再打开；耳机/蓝牙断开直接暂停；仅官方 Jellyfin |
| 保护宽限期 | 120s+ | 切集间隙防降权 |

---

## 验证

后台播放或切集时可用：

```shell
adb logcat | grep -E "BackgroundMediaGuard|AudioHardening|freezing|Plex"
adb shell dumpsys media_session
adb shell dumpsys audio
```

期望现象（因机型而异）：

- 选中应用在媒体会话活跃时，不再因 AudioHardening 被真正静音。
- 澎湃 OS / HyperOS 上，后台播放遇到无声段时不再被零数据策略误暂停续播。
- Emby 片尾日志中可见 `sendJavaScript(ended)`，以及系统侧 `放行后台 startForeground`；会话应进入下一集并保持 `PLAYING`。
- 开启 Plex 兼容修复后，日志中可见例如：
  - `Plex 兼容修复`
  - `跳过 Stop`
  - `已修补媒体通知` / `重写 session/通知拉起 Intent`
  - `重绑播放画面`（「回前台恢复画面」）
  - `记下目标进度` / `纠正开播起始` / `拦截 Exo seek` / `纠正被重置的进度`（「防从头播」）
  - `已同步卸掉 Video Surface` / `吞掉 Surface 拆卸错误`（「防 Error Occurred」）
  - `关掉视频轨，只放音频` / `回到前台，打开视频轨` / `耳机断开，已暂停播放`（Jellyfin）
  - `已安装界面保活` / `跳过 low-mem 销毁` / `跳过 assetsPaths 重载`（息屏和回前台都留下原来的界面，需重启后才有）
- `dumpsys media_session` 中 Plex 会话宜为 `PLAYING` / `PAUSED`，而不是长期卡在 `ERROR`。

Android 16 的部分 `would be muted` 日志可能是预警而非真实拦截，以实际听感与 `dumpsys` 为准。

---

## 更新说明

### 1.4.32

相对 1.4.26 的主要变化：

- 澎湃 OS / Android 17 上 OOM 保护改挂 `ProcessRecordInternal`。旧版找不到 `ProcessStateRecord` 就整段跳过，音频能留着，应用本身仍会被杀。
- 正在播放或停止还不到 30 分钟时，跳过息屏后的 `low-mem` 拆界面，以及回前台时的 `assetsPaths` 配置重建。原来的界面和播放器留着。这两处在 system_server，安装后需要重启。
- 官方 Jellyfin 新增开关（默认开）：**后台只放音频，回前台接上画面**。界面完全看不见时关掉同一个播放器的视频轨，只放音频；回到前台再打开，画面接着当前进度。不新建播放器。
- 耳机或蓝牙断开时，按系统「音频即将变吵」暂停。
- 中间迭代曾尝试记参数重开播放页、跳回进度、拦返回和保持前台服务，已去掉。

### 1.4.26

相对 1.4.16 的主要变化：

- 新增独立开关：**防止画中画/息屏 Error Occurred**（HEVC / MediaCodecVideoRenderer）。`surfaceDestroyed` 同步卸面 + 吞拆面超时；**不做** `surfaceCreated` 强制重绑，避免 PiP 小窗叠到全屏异常缩放。
- **防从头播**重做为纯进度路径：软化拉起 Intent；捕获 `setMediaItems`/`Seek` 目标进度；`onPause` 武装保护；拦 seek→0；重建开播近 0 时改写 `startPosition`；回前台多段纠正。**不拦返回键**（手势返回与系统注入无法可靠区分）。
- 五个 Plex 开关继续互相独立；画面黑屏仍用「回前台恢复画面」。
- 同步更新应用内开关说明与 README。

中间迭代（1.4.17–1.4.25）曾尝试拦 BACK / PiP Surface 旁路等，已收敛为上述策略。

### 1.4.6

- 新增 Plex 独立开关：**防止息屏/回前台后从头播放**（后续版本多次迭代，以 1.4.16 精简策略为准）。
- 软化回前台 Intent；忽略系统注入 BACK。

### 1.4.2

- Plex：后台控件 chronometer / `contentIntent` / 进度与拉起 Intent 修复。
- 「重启模块作用域」支持勾选要重启的进程（system / PowerKeeper / 各 App）。

### 1.4.0 – 1.4.1

- 拆分 Plex 三项兼容修复开关（关画中画继续播、修复后台控件、回前台恢复画面）。
- 明确 Plex / Jellyfin 后台切集通常不必开控制层 / JS 桥接。

---

## 构建

需要 **JDK 17**、Android SDK（compileSdk 36）：

```shell
./gradlew test assembleDebug assembleRelease
```

产物：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

Windows 示例：

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17..."
.\gradlew.bat assembleRelease
```

---

## 架构说明（给开发者）

```
┌─────────────────┐     Remote Preferences      ┌──────────────────┐
│  模块 UI / 配置  │ ─────────────────────────► │ system_server    │
└─────────────────┘                             │ 会话 / 音频 / OOM │
                                                │ 冻结 / FGS       │
                                                └──────────────────┘
                                                           │
                                                ┌──────────┴──────────┐
                                                ▼                     ▼
                                     ┌─────────────────┐   ┌──────────────────┐
                                     │ PowerKeeper     │   │ 目标媒体 App      │
                                     │ 零数据暂停拦截  │   │ 控制层 / Emby 桥 │
                                     │                 │   │ / Plex 兼容修复  │
                                     └─────────────────┘   └──────────────────┘
```

主要代码：

- `app/.../xposed/ModuleEntry.kt` — 入口与 Hook 安装
- `app/.../xposed/*Hook.kt` — 各保护逻辑（含 `PlexCompatHook`）
- `app/.../ui/` — 主列表、按应用设置、可勾选的作用域重启
- `META-INF/xposed/` — 模块元数据与推荐作用域

---

## 限制与风险

- 需要框架具备 system Hook 与 Remote Preferences 能力。
- 依赖应用正确发布 **MediaSession**；没有会话的应用只能用「始终保护」，耗电更高。
- HyperOS 保护依赖当前固件上的 PowerKeeper 实现；系统大版本升级后可能需要适配。
- Plex 兼容修复依赖官方包名与当前客户端内部实现；Plex 大版本更新后部分 Hook 可能需再适配。
- OOM / 冻结保护只在媒体活动与宽限期内生效；极端内存压力下仍可能被杀。
- 共享 UID 在系统层难以完全拆开，勾选一个包可能影响同 UID 其他包。
- 放行后台音频会削弱系统「防止意外后台发声」的防护，请只对信任的媒体应用开启。
- 应用自身解码失败、网络中断、或业务上禁止后台连播时，模块无法代替应用完成播放。

---

## 兼容性

| 项目 | 说明 |
|------|------|
| 最低 Android | API 26 |
| 目标 Android | API 36 |
| 框架 | libxposed API 101–102（如 LSPosed 2.1.x） |
| 重点场景 | HyperOS / Android 12+ 后台媒体与自动切集；官方 Plex 兼容修复 |

已重点验证过的应用方向：Emby、Plex、Jellyfin、Chrome 类网页媒体（效果因站点实现而异）。

---

## 许可证

[MIT](LICENSE) © 2026 sw1313

---

## 免责声明

本项目仅供学习与个人使用。修改系统行为可能导致耗电增加、通知异常或其他副作用。使用前请自行评估风险；作者不对因使用本模块造成的任何损失负责。
