# Zero V2 云服架构演进设计方案（C 演进轨 · 裁定版 v2.1 审核修订 · 可执行）

> 目标：云服极限精简——只留微信/企业微信/QQ 24 小时值守；搜索/抓取作为动态模块示例入 Termux；其余舍弃；文件与消息传输不丢；Zero 层 UI 控制登录与参数。
> **状态：v2.1 · 2026-09-19 全文审核修订（openclaw 受阻纪律贯彻 + 衔接顺序 + 命名勘正）· 待开工**。
> 关联：C 轨现状（云服落地实施计划）/ M 轨 Termux 能力（模块化落地实施计划）/ S2R 轨（自驱消息——IM 事件上屏联动）。

---

## 一、用户裁定回填（v1 → 终态）

| v1 决策点 | 裁定 |
|---|---|
| D-E1 openclaw 定位 | **定为 IM 统一网关**（微信/企业微信官方插件 + QQ 通道） |
| D-E2 QQ 载体 | 随 openclaw 统一栈（探针期实测） |
| **新裁定（openclaw 落地纪律）** | **落地遇到问题及时停下向用户反馈（附实测证据与可选路径），不私自更改设计、不自定回退方案进行开发**——方案变更以用户裁决为准 |
| D-E4 承接主次 | **搜索+抓取=动态模块示例开发入 Termux**（兼作 CodeDev 自编译闭环示范用例） |
| D-E5 playwright | 舍弃（不承接）；抓取用轻量抽取（httpx+trafilatura 系） |
| D-E6 云服终态 | **激进精简**：除 IM 值守外全部移除——worker/沙箱/searxng/crawl4ai/gotenberg/weclawbot/wechat-tail 退役；**research/python_exec/OCR/media/转换/下载等其余舍弃**（不双轨） |
| D-E8 文件中枢 | **保留最小文件通道**（IM 媒体中转 + 手机⇄服务器互传，确保不丢） |
| 新裁定 | 云服具备 **Zero 层功能 UI**：登录扫码控制、通道状态、可调参数（类似动态模块的模块详情页形态） |

---

## 二、精简终态架构

### 2.1 容器终态：11 → **4**

| 容器 | 职责 | 说明 |
|---|---|---|
| **nginx** | TLS 终端（8443 自签+指纹 pin 既有）/ 静态文件直出 | 不变 |
| **gateway**（FastAPI 瘦身） | ①MCP 端点（仅 IM/文件/配置工具，15→约 8）②`GET /events` SSE（redis pubsub 转发，30s 心跳既有）③`POST /files` 上传 + `GET /files/{id}` 下载（C3.4 既有端点保留，流式配额既有） | 删除 search/fetch/research/python_exec/file_process/convert/download/media/task 提交面 |
| **redis** | IM 事件 pubsub + 读游标（F18 持久语义平移）+ file_id 注册表 + IM 配置存储 | 单实例不变 |
| **openclaw** | IM 协议引擎：`@tencent-weixin/openclaw-weixin`（微信·腾讯官方）+ `wecom-openclaw-plugin`（企业微信·官方）+ QQ 通道 | npm 版本锁定（G11）；扫码会话卷持久化 |

**退役清单**：weclawbot（E25 灰度栈）、wechat-tail（openclaw 取代 stdout 旁门）、worker、sandbox、socket-proxy、searxng、crawl4ai、gotenberg（7 容器；容器定义与配置留存 git 供历史可考——恢复属方案变更，经用户裁决后执行）。

### 2.2 gateway 工具终态（MCP）

| 工具 | 语义 |
|---|---|
| `im_status` | 三通道登录态/账号摘要（Zero UI 消费同源） |
| `im_login` | 生成登录二维码（file/URL——Zero UI 展示）+ 轮询态 |
| `im_read(since, channel?)` | 增量读（游标持久 redis；D107 不可信语义不变） |
| `im_send(channel, peer, text[, file_id])` | 发文本/文件（file_id→服务器路径→openclaw 发送） |
| `im_config_get` / `im_config_set` | 可调参数（白名单/每日转发上限/文件大小上限/自动转发开关）——Zero UI 设置页数据源 |
| `upload_file` / `download_file` / `cloud_file_url`（既有 FC/端点） | 手机⇄服务器文件互传（IM 收件箱拉取通道——C3.4 既有链路零改动） |

**事件链（消息不丢三层保障）**：openclaw→gateway（插件事件面）→ redis pubsub → SSE 秒推手机 kick（D114 既有）→ 手机 cloudTickCheck 增量读（游标持久，断线重连不重不漏）→ **tick 兜底**覆盖 SSE 断连窗口（Z28/Z29 既有）。**文件不丢**：IM 收到媒体→服务器 `/data/zero/inbox/{file_id}` 登记→SSE 事件→手机 `download_file` 拉取（端点保留）；发送向：手机 `upload_file`→file_id→`im_send`。

### 2.3 Zero 层控制 UI（"云服像动态模块一样有设置页"）

落点：**权限中心「云服」区块**（S8.3 通道状态区扩展）+ 模块详情页形态：
- 通道状态卡 ×3（微信/企业微信/QQ：已登录/未登录/探测中——`im_status` 轮询/刷新）
- 扫码登录按钮（`im_login` → 二维码图内嵌展示 → 自动轮询至成功）
- 参数区（`im_config_get/set`：转发白名单、每日上限、文件大小上限等）
- 手机侧 FC 描述同步（`im_read`/`im_send` 替代 wechat_read/wechat_send 命名泛化）

---

## 三、精简后最低配置（答案）

| 项 | 稳态占用估算 |
|---|---|
| Ubuntu 22.04 + Docker daemon | ~300-400MB |
| nginx | ~15MB |
| gateway（python:3.12-slim + fastapi/httpx/redis） | ~100-150MB |
| redis（小数据集） | ~30-60MB |
| openclaw（node:20 + 三插件进程） | ~200-350MB |
| **合计稳态** | **~650MB-980MB**（无 ffmpeg/浏览器/沙箱后无计算峰值；文件中转为 I/O 型流式，内存平稳） |

> **最低配置：1 核 1G（1C1G）+ 2G swap 可跑**（贴线——内存峰值约 1G 边缘，swap 兜底；IM 消息速率对单核毫无压力）。
> **推荐配置：1C2G**（舒适冗余，轻量应用服务器入门档即覆盖）；盘 20G 足够（镜像 ~2-3G + 收件箱数据），40G 更从容；带宽按流量包（文件中转为主要流量源）。
> 现役 2C4G 可直接降配换购/到期迁移（migrate.sh 搬迁 → 新机 import，S7.1 既有演练）。

---

## 四、Termux 动态模块示例（搜索 + 抓取）

作为 M 轨自编译闭环的示范用例，同时就是日常能力面：

| 模块 | 实现 | 说明 |
|---|---|---|
| `local_search` | supervisor 承载 **searxng 进程服务**（引擎配置平移 E16/E17 教训：yandex 主力+超时上限+0 结果显式失败）+ 模块 thin client（HTTP→localhost，D69 进程服务通道的标准用法） | 手机侧查询走**家庭/移动 IP**——E17/E19 反证的数据中心 IP 风控优势兑现 |
| `local_fetch` | 纯 python：httpx + trafilatura/bs4 正文抽取 → Markdown | 重 JS 站点诚实降级标注（裁定 D-E5：playwright 舍弃） |

开发路径双轨：①我方手写示范模块入 `services/assets/` 供挂载；②`DEVELOP_GUIDE` 引导 Zero 经 `develop_module` 自举迭代（CodeDev 有界修复闭环的实战教材）。搜索/抓取之外的研究编排等能力：由 Zero 在两模块基础上自主组合（自驱回合+next_tick 心跳续跑），不再单独建云能力。

---

## 五、实施 Steps（全部无阻塞）

### 云服侧（E1 轨 · 每步 ≤1 逻辑单元 · 服务器验证）

| Step | 内容 | 验证 | 状态 |
|---|---|---|---|
| E1.1 | **openclaw 上线**：容器（版本锁定+插件三装+扫码会话卷）+ gateway ImProvider 抽象（D97 升级）+ openclaw 事件面探针（官方文档为据：事件→redis publish `type=im`；发送 API 收发各 1 条）。**受阻纪律：任何不通即停下向用户反馈（附实测证据与可选路径），不私自切换设计/不自定回退** | 三通道扫码通；重启会话保留；探针全链 trace | ✅ 完成（2026-09-20 收束）。微信扫码通 ✓ + 探针收发 ✓ + 重启会话保留 ✓ + ImProvider 抽象 ✓；**wecom/QQ 云路经用户裁定弃用**（2026-09-20：QQ 走手机本地动态模块已绿/D6，企微放弃）→ 三插件已禁用+napcat 容器/镜像清除（2cd89ab，微信链零破坏实证）。实测坑全录：npm OOM（1600m）/npmmirror 20×/迁移锁租约/状态目录分裂/登录失效 3 次自停/doctor 停机态范式 | ✅ |
| E1.2 | **工具切换**：im_* 六工具实装（im_read 游标持久/im_send 文件链）+ SSE `type=im` + 手机 cloudTickCheck 泛化（wechat 检查器→im 检查器）。**手机侧改动排在 S2R.3 之后实施**（AutonomousThinkingJob/TriggerHub 同文件避免双改冲突）。**事件源命名对齐 S2R systemSource**：`WECHAT_PRIVATE` / `QQ_PRIVATE` / `WECOM_PRIVATE` | 微信/企业微信/QQ 消息→Zero 自主回复秒级；断线 30s 重连不重不漏 | ✅ 完成（用户实测全绿 2026-09-20）。服务器侧七绿（53b9ec4）+ 手机侧泛化（7534284）+ 回复链修复（f8466f8）+ **抢答静音达成**（mute 哑模型 NO_REPLY 官方静默标记，e346a5a/d23be54）+ **媒体全链**（出站 CDN ✓/入站 file_id+落盘 ✓，d983b5e/d92ae9e）+ **零发版解耦五维闭合**（C-R5/C-R6）。QQ_PRIVATE 命名由本地 qqbot 模块复用（D6 后 QQ 走本地缝，systemSource 语义一致） | ✅ |
| E1.3 | **计算面退役**：migrate.sh 全量备份（含 weclawbot 扫码包/收件箱）→ 7 容器下线 → gateway 删 12 工具注册（容器定义与配置留存 git 供历史可考）。**退役仅在 E1.2 全链绿之后执行**（先立后破，顺序即保障） | 退役后 IM+文件全链绿；migrate.sh 备份包 checksum 全过 | ✅ 完成（2026-09-20）。备份=封存会话 migrate.sh export（checksum 双绿）；容器下线提前完成（用户裁定，8 容器）；**gateway 工具删除完成**：退役 bili_login/cloud_download/cloud_fetch/cloud_file_convert/cloud_file_process/cloud_media/cloud_python_exec/cloud_research/cloud_search/cloud_task_cancel/cloud_task_status/wechat_read/wechat_send/wechat_status 共 14 注册（8 文件删除+file.py 收敛），runtime.py/compose 清退役端点，/health 移除 searxng 检查（顺带治愈 unhealthy）。**终态验证**：tools/list=7（im_* 6+cloud_file_url，§2.2 终态）· im_read 通（cursor 32 含实机测试增量）· health 200 · cloud_search 确证消失。compose 容器定义按设计留存 git | ✅ |
| E1.4 | **Zero UI 控制面**：权限中心云服区（通道状态×3/扫码/参数区——im_status/login/config 消费） | UI 扫码登录全闭环；参数改动生效 | 🔶 代码完成（2026-09-20，PermissionCenterScreen+VM 两文件）。**通道状态**：探测切换 wechat_status(已退役,恒未知回归)→**im_status**（channels_text 解析 running）；**扫码登录**：im_login→qr_url→"用微信打开"按钮（浏览器链接由微信接管确认，本机免扫码图）+5s×24 轮询至登录；**参数区**：白名单/每日上限/自动转发 im_config_get/set 表单（保存数组化）。QQ 通道位注释改指本地 qqbot 模块（D6）。**待真机实测**：三区块 UI 渲染/登录闭环/参数保存生效（模拟器仅启动冒烟 FATAL=0；im_* 消费需真机 MCP 连接） | ⏳ |

### Termux 侧（E2 轨）

| Step | 内容 | 验证 | 状态 |
|---|---|---|---|
| E2.1 | searxng 入 supervisor（进程服务）+ `local_search` 动态模块 | 对话搜索全链；命中率/延迟对比记录（vs 退役前云侧） | ⏳（QQ 本地化插入项完成·P2 探针 PASS 2026-09-20：真机 Termux Debian trixie aarch64 出码 8 次；tuna 403→官方源勘误；proot --kill-on-exit 教训入档=常驻必须 supervisor 锚。**D11-R 通用本地事件缝已落地**（TriggerHub 127.0.0.1:7879，模拟器全链实证 Zero 真实回复 QQ_PRIVATE 消息，中文零乱码——content-length 字节语义 bug 冒烟实证修复）；**D12-a QQ 指令已落地**（QQ_PRIVATE/QQ_GROUP 分支+dyn_qqbot_send 契约）；**qqbot.py 模块已交付**（services/assets/，严格循构建指引：manifest+configSchema 4 项 Zero 侧 UI/FC 五件 qq_status·send·group_history·poke+napcat 子进程状态机/@白名单过滤/环形缓存/缝推送；file_id=f555a11a 72h）；**待真机部署验收**：装 APK(1aef339+9debf53)→Zero 下载模块入 $PREFIX/zero/modules/→动态模块页启用→qq_status 扫码→白名单配置→全链 Case） | ⏳ |
| E2.2 | `local_fetch` 动态模块（trafilatura 抽取） | URL→Markdown；重 JS 站点诚实降级文案 | ⏳ |
| E2.3 | CodeDev 自举验收：引导 Zero 经 develop_module 迭代其一 | LLM 自主编修→挂载→调用通（闭环示范） | ⏳ |

### 手机侧改动（并入 E1.2/E1.4，≤3 文件/步）

`TriggerHub.kt`（im 检查器泛化+通道状态）+ `AutonomousThinkingJob.kt`（Prompt 通道标签泛化）+ `PermissionCenterScreen/VM`（云服区）。

---

## 六、风险与应对

| 风险 | 应对 |
|---|---|
| openclaw 插件事件面与预期不符（E1.1 探针受阻） | **停下向用户反馈**（附实测证据与可选路径）——方案变更以用户裁决为准（新裁定纪律，不自定回退） |
| 1C1G 内存贴线 | 先在现 2C4G 上瘦身实测稳态（free -h 连续 48h），再决定降配目标 1C1G/1C2G |
| 计算能力舍弃后偶发重需求（如 B 站下载高清） | Termux Debian 原生可装 yt-dlp/bilibili-api（用户 bili_downloader 先例）；按需动态模块化 |
| 手机侧搜索依赖手机在线 | 自驱场景本要求手机在线；离线期无检索属预期边界 |
| 退役后发现旧能力偶需 | migrate.sh 全量备份在手（E1.3 前置）；容器定义留存 git——恢复属方案变更，经用户裁决后执行 |

---

## 七、审核记录（v2.1 终审 · 2026-09-19）

| # | 发现 | 处置 |
|---|---|---|
| C-R1 | E1/E2 Steps 表无状态列——中断恢复无锚 | 两表补"状态"列（全 ⏳），与 S2R 轨进度列同构 |
| C-R2 | IM 事件源命名未与 S2R systemSource 对齐 | E1.2 钉死：`WECHAT_PRIVATE`/`QQ_PRIVATE`/`WECOM_PRIVATE` |
| C-R3 | 轨间依赖仅有文字提示 | E1.2 步内明示"手机侧排在 S2R.3 之后"；S2R 方案 Steps 表尾同步标注 |
| C-R4 | 阻塞项复核：E1.1 探针是全链唯一未知面（openclaw 外部应用事件面细节） | 已有受阻纪律兜底（停下反馈）；其余各步均在既有已验证机制上平移（SSE/游标/kick/file_id/upload-download 端点），**无其他阻塞项** |
| C-R5（解耦裁定 · 2026-09-20 用户裁定 a+b） | **原则：云服功能演进零手机发版**。已解耦面：MCP 工具动态发现（tools/list 连接时刷新——E1.3 删 14 加 6 工具手机零改动实证）/ im_read JSON 加性字段（media[] 旧端忽略新端即用）/ 文件端点 URL 不变（download_file 无感）/ systemSource 服务端归一（shim CHANNEL_MAP）。历史手机发版均为一次性迁移改名（wechat_*→im_*、type=wechat→im），非增量演进常态。**残余耦合两处已拆除**：①SSE 事件类型白名单→泛化为任意事件即 kick（检查器自行决定查什么；im_read 游标幂等+E29 纯读不自激）——云服加新事件类型零手机发版；②Prompt 硬编码工具名→能力化解析（mcpFcAny：精确名优先→能力 Regex 模糊匹配注册表→候选裸名兜底，McpClientModule.fcNameByCapability）——云服工具改名零手机发版。连带修复：CLOUD_TASK 指令退役工具引用（wechat_send/cloud_task_status→im_send 能力化）。**媒体功能实证零发版**：入站 media[] 经工具结果 JSON 到达，手机既有 download_file FC 自主消费 | 已实施（TriggerHub/McpClientModule/AutonomousThinkingJob 三文件 + 本记录）；SSE 泛化行为待真机实测 |
| C-R6（解耦完整性审查 · 2026-09-20 用户裁定"彻底零发版"） | C-R5 两措施审查确认完整（白名单零残留/解析路径正常路径逐字等价/模拟器 BACKLOG 自主回合全跑 persist_turn=4 零 FATAL）；**审查发现两处补全**：①mcpFcName 死代码（调用点全迁 mcpFcAny）——删除；②**第三处残余耦合：buildInstruction 按源名路由**——QQ_PRIVATE/WECOM_PRIVATE/未来新 IM 通道落 default 指令不引导回复。**修法**：`*_PRIVATE` 通用分支 buildImInstruction（channel 事件源名透传——im_send 服务端自持枚举映射，云侧演进；peer=净载荷方括号 sender；D107 同源）——**新 IM 通道接入零手机发版达成**。至此零发版矩阵闭合：加工具/加事件类型/工具改名/加性字段/加 IM 通道 五维全部零手机发版；需发版仅剩：改既有字段语义/动手机本地模块 | 已实施（AutonomousThinkingJob 单文件，bfb9b13 后续 commit）；通用分支真机行为待 QQ/WECOM 接入实测 |

## 八、参考（openclaw 通道格局检索实证 2026-09）

- [OpenClaw 官方文档（微信通道）](https://docs.openclaw.ai)——`@tencent-weixin/openclaw-weixin`（腾讯微信团队官方外部插件：私聊+媒体+扫码）
- [WecomTeam/wecom-openclaw-plugin](https://github.com/WecomTeam/wecom-openclaw-plugin)——企业微信官方插件（长连接智能机器人）
- [npm：@tencent-weixin/openclaw-weixin](https://www.npmjs.com)——版本兼容矩阵
- [企业微信帮助中心：OpenClaw 接入智能机器人](https://work.weixin.qq.com)
- [知乎：OpenClaw 接入微信/钉钉/飞书/QQ 教程](https://zhuanlan.zhihu.com)
- [腾讯云：渠道适配架构指引](https://www.tencentcloud.com)
