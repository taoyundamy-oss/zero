# Zero V2 — 模块化轨模拟器自测手册（经验与方法）

> 版本 1.0 ｜ 2026-09-16 ｜ 来源：M1.1–M1.3 模拟器 L3 实测战役（commit `b96b853`，11 项通过 + 1 真 bug 修复）
> **读者**：① 后续 M 轨 Step 的自测执行者（Claude Code/人）；② 任何需要在 AS 模拟器上自动验证 Zero 行为的轨。
> **定位**：可复制的 runbook + 经验教训。测试结果本身以落地计划 §七进度表为权威，本文不重复维护结果，只沉淀**怎么测**。

---

## 一、测试环境与装配链（Runbook）

### 1.1 环境基线

| 项 | 值 | 说明 |
|---|---|---|
| 模拟器 | AS 模拟器 Android 15（API 35）x86_64 **userdebug** | 关键：userdebug 镜像支持 `adb root`（Google Play 镜像不支持） |
| adb | `C:\Users\USER/AppData/Local/Android/Sdk/platform-tools/adb.exe` | Git Bash 中需 `export MSYS_NO_PATHCONV=1` 防 POSIX→Windows 路径劫持 |
| 多设备 | 物理机 + 模拟器并存 | 一律 `-s emulator-5554` 显式指定 |
| 主机 Python | 3.12（sqlite3 标准库） | DB 直读观测通道用 |

### 1.2 观测通道：Room trace 直读（核心基建）

**问题**：`AgentTraceManager` 只落 Room（`agent_trace_events` 表），不写 logcat——外部无法直接看 trace。
**方案**：root 拉 `zero_v2.db`（+`-wal`/`-shm` **三件套齐拉**，WAL 未 checkpoint 时主库缺最新数据）→ 主机 python sqlite3 查询。

工具 `_test_trace.py`（已入库，根目录）：
```bash
export PYTHONIOENCODING=utf-8        # 否则中文 summary 乱码
python _test_trace.py shizuku_action 20   # (traceId 模糊过滤, 条数)
python _test_trace.py "" 10               # 空=全量最近 N 条
```

### 1.3 对话 UI 自动化：`_test_send.py`

LLM FC 回路是真验证面（chat → LLM → FC → 特权执行 → 回执）。Zero 无 CLI 入口，用 uiautomator 驱动对话界面：
```bash
python _test_send.py "run this command with the shell_run tool: echo zero_shizuku_ok"
```
内部流程：dump 定位 EditText → tap 聚焦 → `input text`（**空格必须 `%s` 转义**，否则整句只剩第一个词——实测踩坑）→ dump 验证文本在框 → 按**底边对齐**找输入行右侧最远可点击节点（发送键）→ tap。
- **长文本会把输入框撑高**（bounds 顶边大变），发送键定位必须按底边（y2）对齐——踩坑后修正。
- 发送成功输出 `SENT: input=(x,y) send=(x,y)`；失败码 `NO_EDITTEXT`（**前台不是 Zero**——如 start_app 测试后 Settings 在前台，需先 monkey 拉回）/ `TEXT_NOT_ENTERED` / `NO_SEND_BUTTON`。
- 发送后 `sleep 30~50` 等云端 LLM 回合，再 `_test_trace.py` 读证据。

### 1.4 Shizuku 装配链（模拟器 root 路线）

```bash
# ① 装 release APK（GitHub Releases，universal 包即可）
curl -sL -o shizuku.apk <v13.6.0 release url> && adb install shizuku.apk
# ② root 直起 server（新版无外部 start.sh——starter 是 native so！）
adb shell "<nativeLibraryDir>/libshizuku.so --apk=<pm path 的 base.apk>"
# nativeLibraryDir 取法：dumpsys package moe.shizuku.privileged.api | grep legacyNativeLibraryDir（下有 x86_64/）
# ③ 验证：ps -A | grep shizuku_server（uid 应为 root）
```

**授权（最大坑）**：`pm grant com.zero.v2 moe.shizuku.manager.permission.API_V23` **无效**——平台权限 granted=true 但 Shizuku API 的 `checkSelfPermission()` 问的是 **server 端 per-uid flags**（v11+ 不再依赖平台权限态）。唯一路径 = manager 应用 UI：
1. `monkey -p moe.shizuku.privileged.api ...` 打开 → 点 "Authorized N applications" 条目
2. 点目标应用行 → **行右侧 Switch** 打开（uiautomator dump 找 `checkable="true"` 节点；对话框类时序竞争时用 screencap 截图佐证）
3. 之后 Zero 重启即 `bind_requested → user_service_connected`

**权限名勘误**：是 `moe.shizuku.**manager**.permission.API_V23`（非 `privileged.api.permission.API_V23`）。

### 1.5 Termux 装配链

```bash
adb install termux.apk                       # 必须 F-Droid/GitHub 版，Play 版无 RUN_COMMAND
adb shell pm grant com.zero.v2 com.termux.permission.RUN_COMMAND
adb root && adb shell "mkdir -p /data/data/com.termux/files/home/.termux \
  && echo 'allow-external-apps = true' > .../termux.properties"   # 安全闸（root 可写）
adb shell monkey -p com.termux ...           # 起 Termux（RUN_COMMAND 需要）
```

**契约冒烟**（不起 Zero 侧代码，验证 intent 键名/路由/后台执行）：
```bash
adb shell am startservice -n com.termux/com.termux.app.RunCommandService \
  -a com.termux.RUN_COMMAND \
  --es com.termux.RUN_COMMAND_PATH /data/data/com.termux/files/usr/bin/touch \
  --esa com.termux.RUN_COMMAND_ARGUMENTS "/data/data/com.termux/files/home/zero_smoke.txt" \
  --ez com.termux.RUN_COMMAND_BACKGROUND true
# 验证：ls /data/data/com.termux/files/home/zero_smoke.txt（owner=Termux uid）
```
⚠️ 目标文件放 **Termux 家目录**——`/sdcard` 对 Termux（untrusted_app_27）有 SELinux 拦截，会假失败。

---

## 二、发现的问题与修复闭环（范式）

| 症状 | 定位证据 | 根因 | 修复 | 复验 |
|---|---|---|---|---|
| LLM 回"没有 shell_run 工具" | trace `fc_loop_start: tools=79`（应 88） | `ShizukuBridge.onAwake()` 漏调 `registerFunctions()`（McpClientModule:79 先例是显式自调；M1.2 改 onAwake 时只留了监听器） | onAwake 补 `registerFunctions()` | 重装后 tools=88，shell_run/tap/swipe 全链通过 |

**范式**：症状 → trace/进程/日志三方取证 → 根因对到具体行为差异 → 最小修复 → 装回复测。修复属授权三类之三（真实 bug），入 commit `b96b853`。

---

## 三、经验教训（按坑频排序）

1. **`adb shell input text` 空格 = `%s`**，且只能 ASCII（中文不可输）；**约 110+ 字符静默失败**（TEXT_NOT_ENTERED——长指令拆短句或多次发送）；含 `(` 的文本经未加引号的 adb shell 传参会撞 sh 语法错——整个 text 参数加单引号。→ 对 LLM 用英文指令；漏转义时整句只剩首词，LLM 会困惑回复（易误判为 LLM 问题——先查 `USER_INPUT` trace 原文）。
2. **`am force-stop` 会断无障碍服务**（Android 对 stopped 应用不重绑）——每次 force-stop 后手势兜底全废。恢复：root `settings put secure enabled_accessibility_services com.zero.v2/com.zero.v2.app.background.AgentRuntimeService` + `accessibility_enabled 1`。测试期尽量用 monkey 拉起代替 force-stop 重启。
3. **Shizuku 授权 ≠ 平台 pm grant**（见 1.4）——凡 Shizuku 相关"未授权"先查 manager UI 开关，别浪费时间在 pm grant。
4. **`uiautomator dump` 有时序竞争**（开关状态、对话框未及渲染）——dump 无果先 `screencap` 截图人眼核对，再回 dump 找节点坐标。
5. **UI 自动化坐标随状态漂移**（键盘弹出上移、长文本撑高输入框、前台应用切换）——每次都从**当次 dump** 重算坐标；对齐基准选底边（输入行底边与发送键底边稳定共线）。
6. **root 下 `/sdcard` 写入受 SELinux 约束的对象会假失败**——Termux 产物验证用其家目录。
7. **trace 读不到 ≠ 没发生**：WAL 三件套、`PYTHONIOENCODING`、过滤词拼写（LIKE 模糊匹配不支持 `\|` 或运算——分开多次查）。
8. **LLM 是测试变量**：高危拦截测试中 LLM 读 FC description 直接拒调（描述层防线生效），代码层路径反而不可能被 LLM 触发——设计 FC 测试时先想清楚模型行为对路径覆盖的影响；需要强测代码层时考虑绕过 LLM 的直接单测/冒烟。
9. **模拟器 force-stop/重装不丢 Shizuku 授权与 Termux 前置**（server flags 与文件持久）——一次装配，整轨复用。

### M1.4 战役追加（2026-09-16）

10. **后台 RUN_COMMAND 无 stdin——pkg/apt 交互提示永久挂死**（`do_select` 卡 15min 实证）。一切经 Termux 跑的脚本必须 `</dev/null` 封 stdin + `DEBIAN_FRONTEND=noninteractive`；Termux sources.list 为空时 pkg 走交互式测镜像（同挂死路径）——脚本先自愈镜像源再 install。
11. **`proot-distro` 5.x 是 Python 实现 + 容器布局变更**：rootfs 在 `containers/<distro>/rootfs`（旧版 `install-rootfs/<distro>/rootfs`）；`mkdir -p` 对旧路径会**凭空造幽灵目录造成假成功**——涉容器路径一律先探测存在者。OCI 下载器用 Python ssl，部分网络下 `SSL: RECORD_LAYER_FAILURE` 连续失败而 curl 同域 200（registry API 可达但 blob 传输断）——逃逸路径：host 手动 OCI 拉取（token→manifest→blob 三步 curl）+ `adb push` + `proot-distro install --override-alias debian <tarball>` 本地安装。
12. **`adb push` 到 app 数据目录会错属主**（push 落地为 root/u0_a0，app 是 u0_a2xx）——push 后必 `chown $(stat -c %u /data/data/<pkg>) <file>`；若推回 SQLite 还须先删设备侧 `-wal/-shm`（配对不一致会 "Failed to open database"，trace 层静默降级故表象是"trace 消失"——双重陷阱）。
13. **复杂命令经 `--esa` 传参是引号地狱**（本地 Bash→adb argv→设备 sh 三层重解析 + Termux 背景 PATH 不含 $PREFIX/bin）——凡多语句/含引号命令一律 **root 落脚本文件（shebang 指 Termux bash + export PATH）→ RUN_COMMAND 直指脚本**，输出重定向到家目录文件再 root cat。
14. **E28 截断取舍**：bootstrap 失败 trace 只存 200 字符，深层错误（镜像提示在头部）覆盖了真死点——诊断期改用"手测脚本全量落盘"取证，码内 trace 保持截断（上下文保护是对的）。

### M2.3 战役追加（2026-09-16）

15. **iptables 模拟 loopback 受限必须 owner 限定**：`-o lo --dport 32768:60999 -j REJECT` 会**连回包一起杀**（服务端 SYN-ACK 的 dport=客户端临时端口同落段内→supervisor 7878 也瘫）。正确姿势：`-m owner --uid-owner <app_uid>`——仅 app 出向受限（精确模拟 Android16 per-app loopback 限制，系统/Termux 不受扰）；固定端口服务（7878）单独加规则可验 socket 兜底通道。
16. **SELinux Enforcing 阻断 app→他 app 抽象 socket**：`avc: denied { connectto } path=\0... scontext=c213 tcontext=c209 unix_stream_socket permissive=0`——untrusted_app 间跨 uid 抽象名连接被拒（设计假设"抽象名跨 app 共享"在 Enforcing 不成立；M2.1b socket 帧测试当时经 root shell 客户端故未踩）。app 侧 socket 传输/healthViaSocket 验证须 `setenforce 0`（验完恢复 1）；真机 Enforcing 下 D59 socket 兜底可用性归 B13 矩阵。
17. **`LocalSocket.connect(endpoint, timeout)` 双参变体 API35 直接抛 `UnsupportedOperationException`**（message 还是 null——trace 里只见 "null" 极易误判）——一律用**单参 `connect(endpoint)`**（TermuxBridge.healthViaSocket 同款先例）；抽象名 connect 即时解析无 timeout 语义缺口。
18. **`settings put secure enabled_accessibility_services` 本身会立即拉起 app 进程**（a11y 绑定）——与"推库→monkey 启动"期望序赛跑，restore 读走旧 DB 行（表象：restore 静默未跑/读了旧态）。冷启动 DB 注入正序：force-stop → 推库（进程必死时）→ settings put 即启动（无需 monkey）；测后核对 trace 时间戳确认目标周期真跑。

### M2.4 战役追加（2026-09-16）

19. **UI 自动化编辑文本后必须 `KEYCODE_BACK` 收 IME 再点下方控件**（坑5 的强化版）：键盘弹出把整屏上推，"保存"按钮实际坐标被顶出屏或落键上——表象是 tap 落空且 dump 尺寸不变（同尺寸≠无变化，勿以此判稳）。正序：input text → BACK 收键盘 → 重 dump → 按新坐标操作。
20. **Shizuku root starter 的 so 在 `legacyNativeLibraryDir/<abi>/` 子目录**（x86_64 模拟器非 arm64-v8a）——直指 `$NATIVE/arm64-v8a/libshizuku.so` 静默失败；先 `ls` 取 abi 再拼路径。server 后起时 app 侧 binder 不会自动重绑（ShizukuBridge.isReady 仍 false），需 app 进程重启才恢复——测 wake-lock 探测正路径要么 server 先起再冷启 app。

### M3.0 战役追加（2026-09-17）

21. **模拟器 DocumentsUI 的 ALLOW 确认框对注入输入免疫**（SAF 工作目录配置卡壳）：`USE THIS FOLDER → Allow access` 对话框对 `input tap`（视觉中心多轮）与 `KEYCODE_ENTER` 均无响应（screencap 连帧实证）；且 **uiautomator dump 会含屏外节点**（网格滚动后 USE 按钮 bounds 仍在树中但不可见，按 dump 坐标打必空）——dump 坐标不中时先 `screencap` 人眼核对视口与按钮真实位置。结论：SAF 目录配置留用户真机两击手动步；涉及 SAF 正路径的 FC（linux_upload/download）验降级分支 + 真机补正路径。
22. **模拟器偶发自灭后重启不丢数据但丢运行态**（AVD userdata 持久）：`emulator -avd zero_test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot` 冷起约 1.5min——Termux/Debian/supervisor 全冷（app 自愈链正好全验）但 **装着的 APK 是重启前最后版本**（安装窗口被吃掉的改动会"神秘消失"——先核对 APK 是否真装上再查代码）。

### M3.1 战役追加（2026-09-17）

23. **Debian 12 PEP 668 拦系统级 pip**（`externally-managed-environment`，`--user` 同拦）——manifest deps 安装须 `pip3 install --break-system-packages`（proot 玩具容器无系统完整性顾虑，D67 "pip 自由"契约落法；实测 requests 7s 装完含网络下载）。

### SD 战役追加（2026-09-18）

25. **`enabled_accessibility_services` 可能整体静默失效**（2026-09-18 20:11 起实例复现）：`settings put`（root）、`content insert` 直写 provider、**系统无障碍 UI toggle**（翻转呈蓝但 get 仍 null、服务不绑定）三路全不落——settings provider 对该 key 写路径坏（疑似 provider 状态损；emulator kill+冷启未修复，触发条件未定位）。对策：换 AVD 实例/擦 userdata 重建；依赖 a11y 的 L3 用例遇"put 成功但 dumpsys 0"先查本坑勿疑代码。**真机不受影响**（用户走系统 UI，正常路径）。

### M3.2 战役追加（2026-09-17）

24. **勿以宿主 root 直接跑容器内 python**（proot-distro login 预检等）——rootfs 内产物落宿主 root 属主（如 `modules/__pycache__`），proot 内 u0_a209 映射写不动 → 后续 py_compile/import `Permission denied` 假语法错（M3.2 LLM 连撞两次）。语法门改 `python3 -c "compile(open(f).read(), f, 'exec')"` 纯编译零 pyc 落盘（行号语义同）；容器内操作一律经 supervisor（u0_a209）或 app 通道。

### S2R 战役追加（2026-09-19）

26. **Compose VM 构造期 StateFlow.collect 必 NPE（启动闪退，S2R.4 R13 实证）**：`viewModelScope` 用 `Dispatchers.Main.immediate`——`init` 块里 `launch { stateFlow.collect {} }` 的 lambda **在构造函数内同步执行**（StateFlow 首值即时回放），若该 init 块按 Kotlin 声明序排在被读写属性（`_isGenerating`/`_messages` 等）之前 → 读到未初始化属性 → NPE 闪退。**SharedFlow（replay=0）collect 首值挂起等待，同位置不触发**——历史代码安然无恙是运气不是安全。判据：FATAL 栈 `emit` 帧直接挂在 `ChatViewModel.<init>` 下。修复范式：观察块放**全部属性声明之后**的 init 块。
27. **"未变化"判定拿原始字段对比兜底后值 → 每开 app 新建会话（R14 实证）**：`refreshActiveCharacter` 的 unchanged 判 `activeCharacter?.name == globalActiveCharacterName`——characters 空表时 null vs "Zero"/DEFAULT_PROMPT_TEXT 恒不等 → 每次进 ChatScreen（LaunchedEffect(Unit)）都误判"角色已切换"→ startNewConversation，上下文"丢失"。修复范式：**对比值与 setter 同口径**（`?: "Zero"` / `?: DEFAULT_PROMPT_TEXT`）。诊断金标准：run-as 拉库数 conversations 行数与启动时刻对表。
28. **模拟器 DB 二进制拉取必须 `adb exec-out run-as … cat`**——`adb shell` 管道做 CRLF 转换，拉出的 db/-wal 必现 `DatabaseError: database disk image is malformed`（本地 python sqlite3 打开即炸）；exec-out 二进制安全，三件套（db/-wal/-shm）拉齐后 python 直查。另：a11y 坏（坑25）时 `uiautomator dump` 输出空文件不可用 → 改 `screencap -p` 拉截图人眼定位坐标再盲 tap（注意 Read 工具显示分辨率缩放，坐标×系数映射回原始分辨率；底部导航区盲 tap 会误切页——先确认 y 落在内容区）。

---

## 四、可复用资产清单

| 资产 | 用途 |
|---|---|
| `_test_trace.py` | Room trace 直读（任何轨可复用——按 traceId 过滤） |
| `_test_send.py` | 对话 UI 自动化发送（FC 回路验证的标准入口） |
| 1.4/1.5 装配链命令 | Shizuku/Termux 环境重建（模拟器重置后照抄） |
| `adb logcat -s ZeroShizuku` | B9 延迟观测（特权进程侧打点 TAG） |
| root `settings put secure` | 无障碍恢复 |

---

## 五、真机（物理机）差异提示

模拟器 root 路线 ≠ 真机路线，以下项真机行为可能不同，L3 真机验收时重点看：

| 项 | 模拟器 | 真机（三星外版）预期差异 |
|---|---|---|
| Shizuku 激活 | root starter 直起 | 无线调试配对激活；**每次重启需重激活**（B8 风险项） |
| Shizuku server uid | 0（root） | 2000（ADB shell）——`shizukuUid()` 显示与 hidden API 豁免同源 |
| Termux RUN_COMMAND 回传 | 待 M1.4 验（NOT_EXPORTED 疑点） | 同样待验；One UI 后台杀 Termux 概率高（A1 保活主题） |
| B9 binder 注入 | 15–16ms | 预计同量级；超标按 G 类迭代 |
| 无障碍重开 | root settings 直改 | 必须用户手动关/开（R8） |

---

## 六、后续挂接（写作时点的未验项）

- ~~M1.4 bootstrap 触发时验：Termux PendingIntent 回传~~ **✅ 2026-09-16 已解除**（NOT_EXPORTED 四轮 result trace 全通，API 35）。
- ~~app 侧 socket 兜底通道未触发场景~~ **✅ 2026-09-16 M2.3 已验**（permissive 下 healthViaSocket 保活 + McpSocketTransport 数据面全链；Enforcing 阻断见坑 16，真机矩阵 B13）。
- M2.0 触发时验：supervisor 双通道（HTTP 7878 + `\0zero-supervisor`）探活/重启序列/进程组 kill。
- 次要 FC（input_text/open_url/grant_perm/set_setting）与 D38 Live 会话权限动态：机制同路，可在 M2.x 顺手补验。
- 真机差异：CN 网络下 proot-distro OCI 下载是否同样 TLS 断（真机无模拟器 NAT 层，可能无恙）；断则用 §三.11 逃逸路径。
- 每完成一个 Step 的自测，结果写落地计划 §七，经验教训回本文档追加。

*本文档随测试战役滚动更新；结果类数据以落地计划进度表为权威。*
