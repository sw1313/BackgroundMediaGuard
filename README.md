# 后台媒体守护（Background Media Guard）

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

面向 **LSPosed / 现代 libxposed API 101–102** 的 Xposed 模块。  
在 Android 12+ 与 HyperOS 等系统上，为你选中的媒体应用提供**后台续播**与**自动切集**相关保护，而不是全局关闭系统省电策略。

当前版本：**1.4.2**

---

## 它解决什么问题

常见症状：

| 现象 | 典型原因 |
|------|----------|
| 退到后台约一分钟后被系统静音 | Android AudioHardening |
| 后台播放中遇到约一分钟无声段后停止续播 | 澎湃 OS / HyperOS 零数据暂停 |
| 进程还在，但播放卡住、通知消失 | 缓存冻结（Cached App Freezer）、OOM 降权 |
| Emby 能播完当前集，后台却无法自动下一集 | WebView 控制层被暂停；或片尾后无法再次进入前台服务 |
| Plex 关画中画后停播 | 退出 PiP 时误发 Stop（见「Plex 兼容修复」） |
| Plex 后台控件显示 0:00 / 无进度条 / 点不回前台 | 媒体通知误开 chronometer，且 `contentIntent` 为空 |
| Plex 切下一集后回前台偶发黑屏 | Surface 未可靠重绑 |

本模块按应用开启保护，尽量只在「正在播 / 刚切集」的窗口内介入。  
**Plex / Jellyfin 后台切集通常正常**，一般不必为切集去开「控制层」或「片尾 JS 桥接」。

---

## 功能概览

### 系统层（`system`）

- **按应用放行后台音频**：放行与播放、音频焦点等相关的 AppOps，避免合法媒体会话被静音。
- **MediaSession 跟踪**：关注 `PLAYING` / `BUFFERING` / `CONNECTING` 等状态；停止后仍有可配置的**保护宽限期**（默认 120 秒），方便切集、缓冲。
- **媒体前台等效**：在保护窗口内把进程按媒体前台服务对待，并钳制内部进程状态与写给 LMKD 的 OOM adj。
- **阻止缓存冻结**：媒体活动期间拦截 AOSP Cached App Freezer 对目标进程的冻结。
- **后台前台服务（FGS）放行**：Android 12+ 限制后台 `startForeground()`。自动下一集时若需重新拉起 `MediaService` 一类前台服务，可在保护窗口内放行（对 Emby 等连播场景很关键）。
- **共享 UID / 多进程**：尽量识别同 UID 与 Chrome 类隔离子进程。

### HyperOS（`com.miui.powerkeeper`）

- **零数据暂停保护**（可选）：澎湃 OS / HyperOS 在后台播放时，若连续约 60 秒检测不到音频数据（无声段），会暂停音轨导致无法继续播放。本项拦截 PowerKeeper 该逻辑。  
  通用 MediaSession 保护无法单独覆盖该路径，因此需要勾选 PowerKeeper。

### 应用进程内（目标 App 需在 LSPosed 作用域中）

- **控制层后台可见性保持**（可选）：保持 WebView / React Native 控制层可调度。主要用于 **Emby** 等混合客户端；**Plex / Jellyfin 后台切集通常正常，一般不必开**。
- **片尾 JS 桥接**（可选）：面向 Emby 及改包名兼容客户端，将片尾事件可靠送达页面脚本，配合系统侧 FGS 放行完成自动下一集。官方包名 `com.mb.android` **默认开启**，其他应用需在齿轮里手动打开。
- **Plex 兼容修复**（仅官方 `com.plexapp.android` 齿轮可见，**默认关**，三个独立开关）：
  1. **关闭画中画后继续后台播放** — 后台播时开画中画，直接关掉画中画会误发 Stop；开启后跳过该 Stop。
  2. **修复后台播放控件** — 关掉错误的 chronometer（避免一直 0:00）、补通知 `contentIntent` / metadata 时长、修正拉起 `MainActivity` 的 Intent。
  3. **回前台恢复画面** — 后台自动切下一集后回前台偶发黑屏（有声无画）时强制重绑 Surface。

### 配置方式

- 主界面勾选要保护的应用。
- 每个应用右侧**齿轮**进入单独设置，各项带场景说明。
- 通过 **Remote Preferences** 实时下发到 system / PowerKeeper / 应用进程，改完一般无需重装模块（应用进程内 Hook 需强停目标应用后再生效）。

---

## 推荐作用域

模块 `scope.list` 推荐：

| 包名 | 用途 |
|------|------|
| `system` | 音频、会话、OOM、冻结、FGS 等系统保护（**必选**） |
| `com.miui.powerkeeper` | 澎湃 OS / HyperOS 零数据暂停（小米机强烈建议） |
| `com.mb.android` | Emby（控制层 / 片尾 JS 桥接） |
| `com.plexapp.android` | Plex（画中画 / 通知控件 / 画面恢复等兼容修复） |
| `org.jellyfin.mobile` | Jellyfin（可选；一般只需系统侧保护，不必开控制层） |

模块为 **非静态作用域**（`staticScope=false`）：可在 LSPosed 中手动勾选，也可在应用设置里打开控制层 / 片尾 JS 桥接 / Plex 兼容修复时由模块**动态请求**加入作用域。

---

## 安装与使用

### 1. 环境

- 已 Root，并安装支持 **libxposed API ≥ 101** 的框架（如 LSPosed）。
- 框架需支持：`PROP_CAP_SYSTEM`、`PROP_CAP_REMOTE`（Remote Preferences）。

### 2. 安装模块

1. 安装 Release / Debug APK。
2. 在 LSPosed 中启用「后台媒体守护」。
3. 作用域至少勾选 **`system`**；HyperOS 再勾选 **PowerKeeper**。
4. 使用 Emby 连播或 Plex 兼容修复时，把对应应用也勾进作用域（或在齿轮里打开相关开关并同意弹窗）。
5. 点「重启模块作用域」可**勾选**要重启的进程：只改应用内开关时，勾选对应 App 强停即可；**system / PowerKeeper 在模块或系统侧逻辑未变时通常不必勾**。刚启用模块、新加 system/PowerKeeper 作用域，或更新系统侧 Hook 后再勾它们。

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
- **Plex 额外问题**：在 Plex 齿轮里按需打开三项「Plex 兼容修复」（可只开遇到的那几项），同意作用域后强停 Plex 再测。

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
| Plex：回前台恢复画面 | 遇该问题再开 | 切集后回前台偶发黑屏；仅官方 Plex |
| 保护宽限期 | 120s+ | 切集间隙防降权 |

---

## 验证

后台播放或切集时可用：

```shell
adb logcat | grep -E "BackgroundMediaGuard|AudioHardening|freezing"
adb shell dumpsys media_session
adb shell dumpsys audio
```

期望现象（因机型而异）：

- 选中应用在媒体会话活跃时，不再因 AudioHardening 被真正静音。
- 澎湃 OS / HyperOS 上，后台播放遇到无声段时不再被零数据策略误暂停续播。
- Emby 片尾日志中可见 `sendJavaScript(ended)`，以及系统侧 `放行后台 startForeground`；会话应进入下一集并保持 `PLAYING`。
- 开启 Plex 兼容修复后，日志中可见 `Plex 兼容修复` / `跳过 Stop` / `已修补媒体通知` / `重绑播放画面` 等字样。

Android 16 的部分 `would be muted` 日志可能是预警而非真实拦截，以实际听感与 `dumpsys` 为准。

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
- `app/.../ui/` — 主列表与按应用设置
- `META-INF/xposed/` — 模块元数据与推荐作用域

---

## 限制与风险

- 需要框架具备 system Hook 与 Remote Preferences 能力。
- 依赖应用正确发布 **MediaSession**；没有会话的应用只能用「始终保护」，耗电更高。
- HyperOS 保护依赖当前固件上的 PowerKeeper 实现；系统大版本升级后可能需要适配。
- Plex 兼容修复依赖官方包名与当前版本内部实现；Plex 大版本更新后部分 Hook 可能需再适配。
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
| 重点场景 | HyperOS / Android 12+ 后台媒体与自动切集 |

已重点验证过的应用方向：Emby、Plex、Jellyfin、Chrome 类网页媒体（效果因站点实现而异）。

---

## 许可证

[MIT](LICENSE) © 2026 sw1313

---

## 免责声明

本项目仅供学习与个人使用。修改系统行为可能导致耗电增加、通知异常或其他副作用。使用前请自行评估风险；作者不对因使用本模块造成的任何损失负责。
