# Zero 全页面与残留UI验收清单

> 最新验收收尾（2026-10-11 LLM-U7）：代码审核修复4项P1（共享连接契约复验、草稿路由校验、原生状态上下文预算、Responses失败用量记录），新增显式cf-openai-responses-v1及图片输入，共13契约。CF Luna真实聊天、图片SSE、medium思考及两次工具3/3通过，共5次请求、每次1536输出上限；2个加密思考项、3条输出状态恢复回传。LLM页完成分区卡片、参数/能力选项和模型卡片重构；质量530任务、JVM592通过1跳过、实机UI10/10、Agent6/6。原配置/密钥/MCP摘要、实装APK、FGS2003和八模块恢复核对通过。保留Chat Completions，无自动迁移。通用契约不代表所有GPT型号或全部OpenAI API功能已验收。三家直连取消、Ollama/vLLM暂缓不阻塞、Gemini账务未核实、原Qwen400取消及A9停止不变。证据 `artifacts/llm-review-ui-20261011/acceptance.md`。

> 此前验收收尾（2026-10-11 LLM-U6b-12）：GPT-6 Luna经CF REST Responses已实现并实测：聊天/SSE通过，medium思考+两次Agent工具及落盘续接通过，1个真实加密思考项、3条输出状态恢复回传。共8次应用请求（初始low 5次无思考项，补验medium 3次），每次1536输出上限。新增cf-luna-responses-v1，共12契约，保留旧Chat Completions和全部配置，无自动迁移。质量530任务、JVM586通过1跳过、Agent6/6；实装、配置摘要、FGS2003及八模块恢复核对通过。三家直连取消、Ollama/vLLM暂缓不阻塞、Gemini账务未核实、原Qwen400取消及A9停止不变。证据 `artifacts/llm-u6b-luna-responses-20261011/acceptance.md`。

> 此前验收收尾（2026-10-11 LLM-U6b-11）：Anthropic原生手动思考/签名已实现并通过CF真实验收：聊天、SSE、两次Agent工具3/3，5次请求（总输出上限1536、思考预算1024）；1个真实签名块，续接前3条原生内容经metadata落盘恢复回传成功。新增显式cf-anthropic-native-v1契约（共11契约）、整条消息原生状态及存储元数据v3；旧契约/配置不自动迁移。质量530任务、JVM579通过1跳过、Agent6/6，实装APK及八模块恢复通过。用户确认本轮自行修改模型/密钥，其新配置保留，MCP/业务模块配置不变。当前授权功能范围收口：三家直连取消，Ollama/vLLM暂缓部署不阻塞；Gemini账务层级仍未核实，原Qwen400取消、A9停止不变。完整证据 `artifacts/llm-u6b-anthropic-thinking-20261011/acceptance.md`。

> 此前收尾结论（2026-10-11 LLM-U6b-10，覆盖下方历史待办）：CF Haiku真实两次Agent工具回传通过，本片3次请求、每次256输出上限，与U6b-7聊天/SSE合并闭环；Luna三项已于U6b-9通过。用户明确取消OpenAI/Anthropic/Gemini直连验收，不再阻塞收尾。质量530任务、JVM572通过1跳过、实机Agent6/6及原配置/八模块恢复核对通过，无生产代码或VPN修改。当前可用配置已验收收尾；Ollama/vLLM无可用部署仍待验，Anthropic原生思考/签名属于未实现能力（当前明确拒绝），不能写成已支持；Gemini账务层级未独立核实。U6扩展父门仍开放，U4b取消、A9停止不变。总证据 `artifacts/llm-u6b-closeout-20261011/acceptance.md`。

> 此前有效结论（2026-10-11 LLM-U6b-9，覆盖下方历史待验状态）：用户当前手机代理下，Zero生产客户端的GPT-6 Luna聊天/SSE/真实两次工具全部通过，5次应用层请求，上限6、输出上限256、effort=none。默认ProxySelector实际选择HTTP；免费trace先HK、后US/SJC，NO_PROXY对照也为US/SJC，故节点标签不等于固定出口，也不能断言地区封禁。未改VPN、保存配置或生产代码；质量门及实机Agent6/6通过，原配置和八模块恢复核对一致。证据 `artifacts/llm-u6b-phone-luna-20261011/acceptance.md`。Luna手机/SSE/工具条件关闭；其他直连、Haiku真实工具/思考签名、Ollama/vLLM和Gemini账务边界仍保留，U4b取消与A9停止不变。


> U6b-8网络诊断补充：Luna/Nano经PC US出口HTTP通过，原手机CN出口失败，属于网络对照，未改UI或生产代码，不计新增页面/八条件通过。证据 `artifacts/llm-cf-openai-diagnostic-20261011/acceptance.md`；下方U6b-7失败描述为历史原路径结果。

> LLM U6b-7（2026-10-11）：CF Haiku聊天/SSE兼容修复和工程/Agent回归通过；Luna CF503未通过。沿用现有连接/契约编辑页，本片未改UI、不新增视觉/八条件通过声明。代码、验收与进度核对见 `artifacts/llm-u6b-cf-final-20261011/acceptance.md`、`code-doc-audit.md`；原UI父门不随网络验收关闭。

> 更新至2026-10-10；执行顺序与完成门见《验收推进计划》。各页仅按已绑定产物的证据范围验收，未外推全应用通过。

## 页面台账

| 页面/路由 | 主入口/owner | 收敛重点 | 新计划验收 |
|---|---|---|---|
| chat | 对话/ChatScreen、ChatViewModel | 全来源正文、Markdown/代码、附件输入、停止/重试、抽屉、阅读位置 | 待验 |
| live_session | 对话语音入口/LiveSessionScreen | 波形/状态环、控制层级、后台停动画、语音退出 | A9渲染/真实媒体资源/IME与物理折叠草稿限定通过；导航/进程死亡及完整资源门待验 |
| library | 资料/RootHubScreen | 标题/列表密度与轻量容器 | S6c.3当前真实根入口绑定；逐页父项差额开放 |
| work_files | 资料/WorkFilesScreen | SAF逐级目录、Markdown阅读、授权错误、返回 | A8b.1限定范围已验收，见末节 |
| memory_manage | 资料/MemoryManageScreen | 来源/编辑/删除、空与失败、列表/筛选密度 | A8b.2限定范围已验收，历史未核验资料政策保留 |
| memory_graph | 资料/MemoryGraphScreen | 图示语义、选中详情、主题和可访问替代 | 3a–3c限定范围已验收，中文TalkBack支持限制保留 |
| music_library | 资料/MusicScreen | 列表与播放控制、错误反馈、返回 | 4a/4b/4c限定范围已验收，见末节 |
| automation | 工作区/RootHubScreen | 系统状态与任务入口层级；不把凭据当可用性 | S6c.3当前真实根入口绑定；逐页父项差额开放 |
| automation_list | 工作区/AutomationScreen | 任务状态/取消/详情、列表一致性 | 5a读取/5b.1动作状态限定通过；完整列表/详情待验 |
| automation_create | 自动化列表/CreateAutomationScreen | 复用列表owner、输入验证、创建后返回 | 5b.2a窄窗确认草稿页通过；输入/IME/恢复待验 |
| dynamic_modules | 工作区/DynamicModulesScreen | 启停/版本/错误状态，保留现有能力 | A8b.6限定范围已验收 |
| dyn_module/{id} | 动态模块列表/DynModuleDetailScreen | 部署版本卡、配置、回滚结果、返回 | A8b.6b.2/3限定范围已验收；真实发布另归A10 |
| logs | 工作区/RunLogScreen | 时间线、展开正文、长参数、错误码与详情分层 | A8b.7a/7b限定范围已验收 |
| settings | 设置/SettingsScreen | 领域分组、唯一入口、短描述、轻量行 | A8c.1根页/回调/真实末端限定通过 |
| appearance | 设置/AppearanceScreen | 浅深/动态色、持久化、系统栏 | A8c.1八条件与隔离真实存储限定通过 |
| character_manage | 设置/CharacterManageScreen | 角色与表达状态/关闭/重置、卡片统一 | 角色范围限定通过；2a事务＋2b.1八条件页面＋2b.2真实模块 |
| agent_behavior | 设置/AgentBehaviorScreen | 自驱/记忆参数唯一编辑owner；范围不缩水 | 已有局部证据，完整矩阵待验 |
| trigger_settings | 设置/TriggerSettingsScreen | 权限/事件选择、返回、过时说明清理 | S6c.2统一/状态文案/Activity草稿及真实宽窄限定通过 |
| modules及模块详情 | 设置/ModuleCenterScreen、ModuleDetailScreen | 能力/模型配置、上下文跳转、错误反馈 | S3加载/错误/参数确认写入及实际宽/分屏矩阵范围通过 |
| mcp_servers | 设置/McpServersScreen | 服务/状态/删除/重连与返回 | S4真实网络/取消/迟到/保存及宽分屏矩阵限定通过 |
| permission_center | 设置/PermissionCenterScreen | 系统授权与云通道配置归属拆清；保留已用IM通道 | S2b/c权限/云区矩阵限定通过；非干净新装OS首次弹窗 |
| assistant_role | 设置中的系统动作 | 验证调用系统角色选择；不是缺失的NavHost页面 | 待验 |
| data_backup | 设置/DataExportScreen | 导出/恢复进度、取消和错误说明 | S5既定范围通过；真实宽/分屏16组112截图，五域/提供方边界单列 |
| 模型profile编辑 | 模块详情/LlmProfilesScreen | 连接参数/能力声明/错误、唯一凭据入口 | S3验证/失败/返回/重建、宽窄及真实分屏矩阵/CHAT-TTS长字段范围通过 |
| 首次权限入口 | 主起点chat；设置/permission_center | 旧孤立引导淘汰，复用唯一权限owner | S2c隔离拒绝/返回通过；非新装OS弹窗验收 |
| Sheet/Dialog/附件查看 | 各业务页共享入口 | 样式、IME、返回、触控区域与语义；同业务复用一个owner | 待验 |

## 已发现的候选残留

| 编号 | 当前代码依据 | 处理归属 | 关闭标准 |
|---|---|---|---|
| UI-R01 | ChatScreen.systemCardColors按来源写固定色 | A7/A8a | 移入共享语义色定义或明确保留依据；浅/深/动态实际对比验证 |
| UI-R02 | SystemStatusCard预算点使用固定绿/橙/红/灰 | A7 | 统一状态色且有文字，不能只靠颜色识别；实际对比验证 |
| UI-R03 | ZeroNavigationRow所有入口重边框Surface | A7 | 对照截图调整分组/分隔/容器层级，降低全页卡片感；不以简单删边框代表审美完成 |
| UI-R04 | 各二级页独立Scaffold/TopAppBar/表单 | A8b/A8c | 统一可共享部分，保留业务差异；每页主操作和返回可达 |
| UI-R05 | permission_center仍承载云通道配置 | A8c | 单一领域owner；已有IM功能/配置值保留；其他入口只跳转 |
| UI-R06 | LiveSessionScreen独立800ms无限动画 | A9 | 验证生命周期与减少动态条件；活动时平稳，不可见不循环 |
| UI-R07 | 字号切换后曾回到对话页 | A9 | 重建/折叠/字号变化后路由、草稿、展开与阅读位置有实机证据 |
| UI-R08 | 旧阶段编号、未来功能、内部实现说明可能散落在文案 | A8c/Z1 | 逐项确认再修订；注释与用户文案区分，业务状态不误删 |

## 每页共用验收矩阵

- [ ] 浅色、深色、动态色；正文对比≥4.5:1，状态不只靠颜色。
- [ ] 默认字体与200%；窄屏、Fold展开、分屏；无截断关键操作。
- [ ] 打开、返回、系统返回、IME；表单与草稿、滚动/展开状态保留。
- [ ] 空、加载、成功、失败、取消、重试、权限缺失；无静默失败。
- [ ] 点击≥48dp；TalkBack名称/顺序/状态不重复播报；减少动态。
- [ ] 截图/录屏与APK哈希、设备状态、日期绑定；业务正确与视觉通过分栏。

新计划未填写的格子均为待验；先前D250/B5A6包的局部证据可引用，但不能外推整个矩阵通过。

## 2026-10-07 A7.3a 入口与剩余 owner 复核

| 领域 | 主页唯一入口与当前编辑 owner | 上下文入口/审计边界 |
|---|---|---|
| 角色与表达 | settings → character_manage / CharacterManageViewModel | 不增加第二个角色编辑器；完整表单矩阵归A8c |
| 外观 | settings → appearance / AppearanceStore、AppearanceViewModel | zero_appearance key未迁移；实际打开/返回及配置不变已验 |
| 自主行为 | settings → agent_behavior / AgentBehaviorViewModel | ModuleDetailScreen仅跳转同路由，generic字段排除5项agent_runtime及3项memory专属参数；其余模块参数保留 |
| 事件感知 | settings → trigger_settings / TriggerSettingsViewModel、TriggerWhitelistStore | 白名单及事件配置不增加副本；过时描述逐项A8c复核 |
| 能力与模型 | settings → modules / ModuleCenter、ModuleDetail、ModuleProfilesSection | 文件LlmProfilesScreen.kt不等于存在全局旧路由；模块API Key与profile凭据的实际key/回退交叉仍需A8c核对，不以主页唯一代替凭据全审 |
| 外部工具 | settings → mcp_servers / McpServersScreen | 同一路由连接管理；不新增旁路编辑器 |
| 系统权限 | settings → permission_center / PermissionCenterViewModel | 云端IM登录/参数仍在权限页，UI-R05继续A8c拆归属，保留已用功能和配置 |
| 系统语音助手 | settings → assistant_role / Android系统动作 | 非NavHost目的页；组件矩阵检查名称/尺寸，不自动发起系统授权 |
| 备份与恢复 | settings → data_backup / DataExportScreen | 保留原数据恢复owner，视觉矩阵归A8c |
| 资料/工作区 | library、automation / AppDestinations | 4个资料入口和3个工作区入口不重塞设置；实际主导航已验，业务子页归A8b |

- AppDestinations共16个业务入口（设置9、资料4、工作区3），无重复route；15个为NavHost路径，assistant_role为系统动作。配置专属字段/范围和入口唯一性JVM回归继续通过。
- A7.1/2完成共享页头、分组行、线性主导航图标、明确按钮名称/折叠状态、轨迹前景配对。16组固定组件矩阵全部通过；原生主应用的设置→外观→返回、资料、工作区导航通过，appearance prefs全值未变。
- 该证据不关闭每页完整矩阵。A7.3b仍需收口：UI-R01来源卡颜色统一owner/真实对比、共享表单/状态容器及消费例外台账。A7整体保持进行中。
- UI-R02预算色点已使用主题语义色，保留充足/偏低/超限/未知文字；UI-R03主入口重边框已移除，统一分组和轻分隔，16矩阵及真实根页面留证。二级页各自卡片不因此全部标通过。
- 新UI-R09（A8c）：AgentBehaviorViewModel.persistBehavior的异常被静默吞掉，需保存失败/恢复的可见反馈；这是源码候选，不以此推断当前配置已丢失。预算实际由BudgetGuard读取，旧“未来接入”注释需同步校正，不能误删有效预算功能。
- 新UI-R10：轨迹emoji在200%字下挤正文；已换固定18dp线性图标、8dp间距和可收缩正文列，元信息改普通文字；普通工具调用不再用错误红色。最终16矩阵再次通过，旧截图/失败保留。
- Live参考和初始规格见《【A7视觉方案】ChatGPT基座与GeminiLive》；球体/控制层级及后台/减少动态、录屏和帧门仍归A9。本轮没有启动麦克风或Live。

## 全页美学重构补充（2026-10-07，用户要求）
所有页面均纳入美学重构，不仅根入口；页面底部、末行、尾部说明、空态、弹窗和操作栏均为显式验收项。未经修订或验证的尾部继续标待验，不以截图首屏代替整页。

执行顺序：A7.3b共享来源卡/设置行→A8a对话与附件→A8b工作文件/记忆/图谱/音乐/任务/动态模块/日志→A8c角色/自主行为/事件/模块与模型/MCP/权限/备份/首次引导→A9 Live及跨尺寸动效。
每页改造清单：中性背景及容器、统一页头/正文层级/线性图标/间距；去除无必要重描边与堆叠卡片；状态保留文字与真实数据。验证首屏和末屏、主操作与返回、200%字及浅深色，按需补IME、错误/空态及动态色。资料/云通道/模型配置不因清理旧样式而删除。

## A8a.1限定视觉证据（2026-10-07）
- chat：页头/正文/输入/错误末项视觉组件16组通过；首屏/展开/末屏48张截图。六来源assistant统一MessageBubble；实际媒体、IME、草稿、停止/重试等留A8a.2，完整页面仍未验收。
- UI-R01主题来源色与前景对比通过；零透明元信息。ZeroCard/ZeroSettingSwitch现有消费者外观页已迁入，真实导航无配置变化；其余业务卡片与表单留A8b/c。
- 全部24份Screen.kt、首次引导、弹窗、模型profile子页及每页末屏仍在台账范围。只完成主入口和对话视觉首片，不清空未订项、不提前标全UI统一。

### A8a.2b 草稿恢复及 A8a.2a 补验（2026-10-07）
- A8a.2a/2b限定范围通过。生产ChatDraftState由SavedStateHandle保存文本、附件URI/MIME/路线及会话ID，不保存媒体字节/base64；OpenDocument尽可能取得持久读取权限，provider不支持时保留可见读取失败边界。发送失败/取消仅在当前草稿空时恢复，避免覆盖新输入。
- 四项隔离实机通过（13.062秒，a8a2-accepted-device-rerun.log）：浅深×默认/200%字四组IME可见、页面切换/Activity重建/新SavedStateHandle恢复；Room回复归属及原时间/后续消息保留；生产Composer停止真实任务；真实ChatBotModule＋本地确定性模型重新生成无输出取消保留原回复、部分输出取消保留新内容、再次生成成功。测试不写个人对话、不访问云端。真实输入法全键盘及发送键未验，强制停止/OS进程死亡恢复未验，synthetic URI不等于真实媒体读取通过。
- 初次新增引擎测试失败来自测试包Hilt EntryPoint无法被主应用组件实现；入口移入debug源集后复验通过，失败日志a8a2-accepted-device.log保留。真实主应用导航和外观配置保留单项再验通过（10.883秒，a8a2-actual-navigation.log）。
- 完整qualityCheck＋AndroidTest构建成功（49秒，530任务/27执行，a8a2-final-quality.log）。APK已ADB保留数据安装，SHA256 8DEA57ED575778FF7846BDF4A6C84A742D8CDB6345179940074E88EA49C2D575与手机base.apk一致；正常应用已重新启动。证据目录artifacts/stability-20261008-a8a2的名称不代表未来测试日期，实际本轮日期为10月7日。
- A8a.2整体仍开放。A8a.2c依序审计/修订：会话切换/清空/删除/角色切换前等待取消保存完成；重新生成工具调用续轮；错误重试、Markdown、真实媒体读写及输入法发送。现有重新生成只走单次chatStream，未证明工具执行完整，列为实现偏移，不勾选整体。
- A8b资料/工作区、A8c全部设置/末屏末行/过时描述和重复入口、A9 Live持续待验；24份页面盘点不等于全页美学通过。本轮未改变模型prompt/缓存前缀/工具manifest/Room30，不声称新增98%缓存或资源门通过，无新增付费请求。


### A8a.2c.1 会话切换取消与保存顺序（2026-10-08，限定通过）
- 根因：clear/load/delete/角色变化入口各自启动协程，没有等待UI生成collector的NonCancellable保存结束；TurnGate仅保护引擎历史，不保护UI落库及页面投影。现共用ChatGenerationTask.transition：主线程同步预约、Mutex串行、取消并join当前生成及其保存，再执行历史操作。预约/排队期间阻止发送和重新生成，界面按钮与ViewModel双重守卫；初始化任务仍先完成。后台自主回合继续沿用引擎TurnGate，不声称本片覆盖所有后台来源竞争。
- 角色未变化时只检查，不取消生成、不新建会话；删除非当前会话仍串行但不取消当前生成。清空按原语义新建，删除当前会话先完成取消保存再删除。原3秒保存上限和失败可见ERROR保留；没有扩展为断电/存储故障的零丢失保证。原草稿、URI/路线及Room30不变。
- 五项本地ChatGenerationTask回归通过（task-tests.xml，0 failures）：取消清理期间阻止新生成、生命周期取消、历史修改等待保存并顺序执行、排队取消释放预约、无变化/无关删除不打断。新增串行入口是此前没有的测试缝；未声称已用旧APK实机复现本次竞争。
- 实机使用真实ChatViewModel＋ChatBotModule＋隔离inMemory Room和唯一确定性本地模型，补验加载另一会话、新建、删除当前/其他会话及角色变化/不变，核对旧部分回复落库、新页面不串写及删除后不复活；不写个人对话，不调用云端模型。最终连同草稿/停止/Room/重新生成回归共5项通过（11.991秒，final-device.log），正式主应用导航/外观配置保留1项通过（12.55秒，actual-navigation.log）。不外推实际触摸历史菜单或远端取消的完整端到端证据。
- qualityCheck成功500任务/16执行（1m4s，quality.log）；app＋AndroidTest＋定向单测构建成功267任务/20执行（48秒），最终控制器构建240任务/6执行（11秒）。保留原工作树改动，未提交或清理。
- APK artifacts/stability-20261007-a8a2c/ZeroV2-a8a2c-debug.apk已ADB保留数据安装，SHA256 460EC2038E96FF90A19CF0367462212000F1F3FBC9D69AC4E298317801395194与手机base.apk一致；正式主应用已恢复、七日常模块ACTIVE，SDK1.1.0及加载/安装/两侧指纹一致，emergency=false，OS phantom覆盖null。验收跨过午夜，目录名称10月7日不代表最终验收日期，最终UTC/本地时间见final-phone.json。
- A8a.2c.1限定通过，A8a.2c及A8a.2整体仍开放。下一子片A8a.2c.2优先修订重新生成仅一次chatStream的工具续轮偏移：复用发送的FC执行/错误/预算/取消逻辑，保持用户锚点与稳定前缀，并验回写完整工具消息与最终回复；不通过另造一次普通用户发送重复用户消息。之后A8a.2c.3错误重试/Markdown/真实媒体读写/IME发送。
- 全页视觉A8b资料与工作区、A8c所有设置及末屏末行/旧描述/重复入口、A9 Live继续未验。没有改变provider配置/密钥、缓存前缀/工具manifest，也未新增云端成本或声称98%缓存和A4严格资源门通过。

### A8a.2c.2 重新生成完整工具续轮（2026-10-08，限定验收）
- 根因实机复现：确定性本地模型返回discover_tools后旧regenerate仅一次chatStream即结束，应有发现/执行/作答3轮，实际1轮（red-device.log，0.211秒失败）。普通发送与重新生成现共用runFcLoop：有界发现/正常dispatcher与ModuleLifecycleManager.safeCall、逐轮预算、重复失败保护、工具媒体引用、上限综合和取消清理；没有另造用户发送或重复用户消息。测试fixture最终归属真实chatbot，未绕过注册表或生命周期守卫。
- 重新生成以原用户ID为锚，TurnGate取得锁后再核对最新用户ID；回合变动/缺模型/缺用户返回明确错误但不改引擎历史、不落库覆盖原回复。发送和重新生成先join初始化，修复测试中“3轮已完成但初始化回读old覆盖新回复”的真实竞态。重生成清除整条被替代工具链，保留首助手ID/时间及来源source/systemSource/triggerId，取消无输出恢复原链，有部分输出保留新链。
- ConversationRepository Mutex＋MessageDao.replaceReplyTurn Transaction替换该用户完整assistant/tool链，保存工具请求、结果、思考与媒体引用。拒绝其他回合/其他会话ID占用、缺用户和异常角色。用户时间保留，首回复有足够时间槽时保留；链增长需更多Long时间槽时，只将后续消息与其上下文整体顺延必要毫秒数，保持ID/正文及相对间隔。已严格有序且时间槽足够的单条替换不改时间，缩短新链移除旧工具行；不是维持所有未来消息的绝对时间不变。
- 工具逐个完成即入历史；取消后补齐本回合未得结果的tool_call_id为明确“未完成”，不把未知副作用当作成功，不回滚已执行副作用，不重写更早历史。最终综合指令放消息尾部，stable system不增字；关闭工具的最终综合轮按实际空manifest重算prefixVersion并再次检查预算。重新生成不重放ChatSent/ChatFinished，不重新调用自动证据或视觉预分析，避免重提取已处理用户模板为事实。
- UI按引擎每轮投影，避免将前轮正文累加进最终回复；当前及重载后的最终助手聚合该回合工具轨迹。媒体测试仅验证隔离URI引用保存，不代表真实图像/视频/语音读写验收。普通发送在隔离测试中只临时关闭内存evidenceAutoInject后复原，未改个人持久化配置；普通发送/重新生成均3轮，stable system＋prefixVersion完全一致，续轮消息前段逐项不变。不声称实测98%或新增provider热请求。
- 最终实机9项逻辑（ownership-logic-device.log，1.182秒）：真实ViewModel完整工具链/普通发送/Room重载、后一个工具取消保留前一结果、上限综合及哈希、模拟模型续轮异常显式错误、缺模型/锚点拒绝、空输出/部分输出取消及来源保留、SQLite注入写入失败完整回滚/幂等/后续回合和上下文保留、会话切换回归。另2项IME/草稿/停止回归通过（11.567秒），实际主导航与外观配置保留1项通过（12.268秒），共12项。使用本地确定性模型及隔离inMemory Room，未写个人对话或调用付费模型；真实网络取消、真实预算超限阻断及生产工具外部副作用未实跑。
- 保留失败证据：red-device.log的一次调用；final-device.log的新初始化竞争及2项UI未到达节点。窗口实读为Dozing/锁屏，界面分批唤醒复验通过，不降低键盘/节点要求；编译出现辅助函数错放类外的Unresolved _chatHistory，移回类内后构建通过，中间编译和UTF-8脚本读取失败日志/脚本保留。
- 完整qualityCheck成功500任务/34执行（55秒，ownership-quality.log）；最终app＋控制器构建成功259任务/23执行（37秒，ownership-build.log）。最终APK artifacts/stability-20261008-a8a2c2/ZeroV2-a8a2c2-final-debug.apk已ADB保留数据安装，SHA256 6D31D8603A944EB7A5A9E47CD75F7480C88C5C921384687FFB85CBC439C9917E，安装及控制器指纹/主应用恢复/日常模块见final-phone.json与acceptance-index.json。Room30/密钥/模型配置/Termux发布策略未变；A4账本1035198 embedding/299短重排，上限1500000/384不变；A4严格资源门false保持原限定规则。
- A8a.2c.2限定通过；A8a.2c与A8a.2整体仍开放。接续A8a.2c.3错误重试及Markdown、真实媒体URI/权限/原生或工具路线、真实IME发送、重新生成轨迹状态/错误尾项验收。A8b资料/工作区、A8c全设置及尾部/过时描述/重复入口、A9 Live美学与动效仍待验，未把全页盘点或局部截图当作全页面验收。

### A8a.2c.3a Markdown正文完整性（2026-10-08，限定通过）
- 核验A8a.2c.3中的Markdown先行子项。旧解析仅取3个围栏字符，结束使用startsWith，导致4字符围栏包裹的示例被内部3字符围栏截断，带正文的类似结束行也误闭合。新增3项先红后绿回归：嵌套短围栏与后续段落、带正文的伪结束、流式未闭合长围栏；markdown-red.log保留6项中3项失败。现保存完整围栏长度/类型，结束须同字符且长度足够、其余仅空白；未闭合流式代码原样显示与复制，不执行代码。
- 六项MarkdownBlocksTest全绿（markdown-tests.xml，0 failures）；定向单测＋assembleDebug成功236任务/12执行（14秒），app＋AndroidTest构建成功259任务/13执行（22秒）；完整qualityCheck成功500任务/20执行（51秒）。无Room/模型配置/提示前缀/工具manifest变更，原工作树改动保留。
- 实机markdownCopyHorizontalScrollAndTailRemainReachable通过（22.759秒），生产MessageBubble/MessageBody在隔离debug fixture验证浅深×默认/200%字号四组：原生点击复制，剪贴板代码逐字一致，代码/表格横向滚动可用，TABLE_END与MARKDOWN_END可达，容器正文主题色对比≥4.5。12张截图和markdown-report.json在device目录，已查看浅色200%及深色默认截图。测试还原原剪贴板；不是个人会话端到端，也未实测超长正文纵向翻页、完整CommonMark/行内格式/动态色/折叠外屏矩阵。
- 最终APK artifacts/stability-20261008-a8a2c3/ZeroV2-a8a2c3a-debug.apk已ADB保留数据覆盖安装；SHA256 E8A746AF4E4C0CDEFB3489D7857E4F33DFA486F744A24F6221B2B45931E5D1A2，与手机base.apk一致，控制器指纹亦一致。正常MainActivity恢复，七日常模块ACTIVE；SDK1.1.0、加载/安装/APK四资产及Debian SDK指纹一致，emergency=false、max_phantom_processes=null，本轮owned forward17878已移除。final-phone.json及acceptance-index.json绑定证据；电池读数33.4℃不是热性能验收。
- 无新增付费模型请求，A4账本1035198 embedding tokens/299短重排，上限1500000/384不变。A4严格资源门false的限定工程接受保持；不声称新增98%缓存或全页美学通过。
- A8a.2c.3a限定通过；A8a.2c.3及A8a.2整体仍未验收。下一片A8a.2c.3b：错误重试入口、原用户回合归属及工具副作用提示、重新生成轨迹终态；代码审计已发现regenerate成功/拒绝路径未刷新并结束当前UI trace，暂不冒称已修订。其后真实媒体URI/权限与原生或动态工具路线、实际IME发送。A8b资料/工作区、A8c所有设置/末屏末行/过时描述/重复入口、A9 Live仍按计划开放。

### A8a.2c.3b 错误重试归属与轨迹终态（2026-10-08，限定通过）
- 真实ViewModel＋确定性本地模型先红：模型请求失败后ERROR，但UI当前轨迹没有ERROR事件（red-device.log）。重新生成成功/拒绝/异常未刷新当前trace；生成入口也未清旧trace。现发送/重新生成入口清空旧投影，错误chunk记录明确errorCode，finally记录generation_finished终态、刷新UI并调用endTrace。AgentTraceManager.endTrace本身保留内存记录，不能称其删除记录或实施活跃状态管理；取消原3秒落库逻辑保留，终态经同一出口记录。
- 错误尾项新增直接“重新生成本轮”，绑定失败回合USER ID；不复制新草稿、不另造USER，双击/生成/切换中的入口守卫保留。进入引擎前附件读取失败不绑定旧USER，恢复草稿供调整后发送；新会话/加载会话清除错误、重试目标和旧轨迹。工具重复执行说明明确“已完成的工具操作不会撤销”，用户显式选择重生成才调用模型/工具，没有自动网络重试或自动回滚外部副作用。
- 最终action-logic-device.log 10项通过：新增失败→显式重试成功→缺模型拒绝→附件读取前失败→清空会话序列，核对原USER唯一/Room回复/新草稿保留/双击不重复/新trace无旧ERROR；其余9项FC完整链、工具取消、上限、错误、来源、持久化回滚与会话切换回归。生产VM/引擎/注册表＋隔离inMemory Room，不写个人对话，不访问云端。轨迹诊断仍按应用既有异步机制留存。
- 最终action-ui-device.log 1项通过，浅深×默认/200%字四组生产错误状态组件：工具副作用说明可读、48dp点击、原生重试回调、点击后ACTION_CLICK移除且重复动作不增加回调；非真实ChatScreen全链路。ui-device.log和owned-ui-device.log保留失败：先等待文字存在再断言isEnabled，后等待isEnabled仍失败。实读failed-tree-retry-disabled.json显示节点保留clickable标志但已无ACTION_CLICK；改验实际动作及执行次数，不以Android enabled映射代替禁用行为。4截图/retry-report.json在device目录。未外推实际网络故障、外部工具副作用或所有页面。
- action-quality-build.log完整qualityCheck＋app/控制器构建通过；此前owned-quality-build.log亦成功530任务/26执行（56秒）。Room30/模型配置/缓存稳定前缀/工具manifest无变更，工作树保留。APK artifacts/stability-20261008-a8a2c3b/ZeroV2-a8a2c3b-debug.apk已保留数据ADB安装，SHA256 A19A52AF6B00C07054C096BDC3E551E0E53B48DA9D9C4F6943EF62767075F2D9与手机及控制器指纹一致。正常应用恢复、七日常模块ACTIVE；SDK1.1.0及四资产/两侧SDK指纹一致，emergency=false、OS phantom覆盖null。A4账本1035198/299、累计限额1500000/384不变，本轮零付费模型请求；A4严格资源门false保持限定工程接受。
- A8a.2c.3b限定通过；A8a.2c.3及A8整体仍开放。接续A8a.2c.3c真实IME发送动作及禁用守卫，A8a.2c.3d真实媒体URI读取/查看/权限与动态工具路线。A8b资料/工作区逐页首尾与状态、A8c全部设置及过时文案/重复入口继续待验；A9 Live独立待验。不得将本片局部UI或前轮截图视为全页验收。

### A8a.2c.3c 原生IME动作验收（2026-10-08，限定通过）
- 生产ChatComposer原有KeyboardActions(onSend)已检查sendEnabled，本片不改生产发送逻辑，仅debug fixture增加禁用场景及实机测试。IME可见、文字经原生ACTION_SET_TEXT进入后，执行编辑节点ACTION_IME_ENTER；允许发送准确一次，禁用为零，草稿保持。八组允许/禁用×浅深×默认/200%字全部通过（ime-device.log，1项，12.212秒），8截图及ime-send-report.json已保存。
- 证据是原生无障碍IME动作到生产Composer回调，不是直接调用onSend、点击app发送图标或仅adb输入文本；亦不是点击三星键盘物理发送键，未声称键盘全布局/外屏/所有IME兼容验收。本地debug回调计数隔离个人对话与云端，真实ViewModel发送/重试/落库沿用A8a.2c.2/3b各自冻结证据，不把它们冒认为本APK全量重跑。
- app＋AndroidTest构建成功259任务/13执行（13秒）；完整qualityCheck成功500任务/18执行（51秒）。APK artifacts/stability-20261008-a8a2c3c/ZeroV2-a8a2c3c-debug.apk保留数据ADB覆盖安装，SHA256 465D6CF534CEDEDCB8CF63AB8B41411A410FEB2B132D1F3090B365F55535DAE4，手机及控制器指纹一致。正常应用已恢复，七日常模块ACTIVE、SDK1.1.0及四资产/两侧SDK指纹一致，emergency=false，OS phantom覆盖null，owned forward已移除；final-phone.json和acceptance-index.json绑定证据。Room30、provider配置、稳定缓存前缀未改；A4账本1035198/299，上限1500000/384不变。
- A8a.2c.3c限定通过，A8a.2c.3及A8整体仍开放。下一片A8a.2c.3d真实媒体URI读取/权限/查看与原生或动态工具路线；代码检查发现普通发送缺原生图片模型返回的终止chunk未带errorCode，列为待复现，不能当媒体失败验收通过。其后A8b资料/工作区、A8c所有设置/残余尾项美学与功能矩阵，A9 Live仍待验。

### A8a.2c.3d.1 真实图片引用与模型缺失错误（2026-10-08，限定通过）
- 实机真实PNG＋FileProvider content URI→AttachmentStore导入→隔离Room保存→删除测试源文件→重载：引用与路线NATIVE保持，base64不落库，Android BitmapFactory仍可解码16px图，FileProvider重新读取字节逐项一致。随机fixture与导入产物仅在本片新创建时清理，不删用户附件；同UID重新读取不是外部应用授权或SAF撤权证明。未打开个人照片/写个人对话。
- 首红red-device.log明确expected MEDIA_MODEL_UNAVAILABLE but null。普通发送缺原生图片模型和缺任何对话模型分支原先只有提示正文，无errorCode。现分别补MEDIA_MODEL_UNAVAILABLE/CHAT_MODEL_UNAVAILABLE、ERROR状态和诊断事件，沿现有StreamChunk传递到ViewModel错误终态与重试owner；未改变模型选择优先级、前缀或自动改走其他路线。
- 新实机用确定性本地模型补验原生NATIVE缺图片模型不调用文本模型；注册IMAGE_INPUT模型后原生请求带完整data URL；TOOLS路线交给文本回合并保留TOOLS附件引用；缺任何模型明确CHAT_MODEL_UNAVAILABLE。这是能力路由/请求边界，非真实provider网络多模态、动态视觉工具执行或音视频解码。上述MediaUriAcceptanceTest连同前片10项重试/FC/取消/Room回滚/切换回归，本APK logic-device.log共11项通过。
- qualityCheck＋app/AndroidTest构建成功530任务/53执行（1m8s）。最终APK artifacts/stability-20261008-a8a2c3d/ZeroV2-a8a2c3d1-debug.apk已ADB保留数据安装，SHA256 8902EE11A373DD7316F97D515FCBAE5C5E354C798065680B893998A69CB89012，手机及控制器指纹一致。正常应用恢复、七日常模块ACTIVE，SDK1.1.0及四资产/两侧SDK指纹一致，emergency=false，OS phantom覆盖null，owned forward已移除。Room30及密钥/模型配置不变，A4账本1035198/299，上限1500000/384不变；无付费provider请求、不声称98%缓存或A4严格资源门已通过。
- A8a.2c.3d.1限定通过；A8a.2c.3d、A8a.2c.3和A8整体仍开放。后续需实证真实音视频入口/出口、外部查看权限和动态工具往返，图片AUTO视觉回退与provider真实输入亦不以本地模型代替；随后A8b资料/工作区逐页及A8c全设置/末屏/残留清理继续执行。A8a.2c.3b/3c截图和IME分别绑定其冻结APK，未称本APK重跑所有视觉矩阵。

### A8a.2c.3d.2a 真实音视频解码与Debian附件往返（2026-10-08，限定通过）
- 补齐生产MediaAudioDecoder和MediaVideoFrames的Android实机证据：0.4秒AAC/m4a 2855字节、1秒H.264/mp4 10651字节，经真实FileProvider content URI导入，源测试文件删除后使用vault引用继续解码；TOOLS路线保持、音视频不生成图片base64。音频产出非空16kHz/单声道/16位PCM WAV，视频产出开头/中间/结尾三个可由BitmapFactory解码的JPEG，边长≤1024。services debug-only访问缝直接调用生产codec，release不包含此测试访问对象；无借用Live麦克风或录个人音视频。
- 同样两份样本走生产AttachmentStore.export→真实Termux supervisor原始字节上传→Debian文件回读→AttachmentStore.publish回传；逐字节一致，输出引用MediaKind为AUDIO/VIDEO。这是附件桥与真实工作站文件往返，未冒称动态分析模块执行、ASR/provider转写或完整视频原生模型能力通过。独立小样本在/root/zero/attachments/内容SHA路径保留作证，两个路径/散列/字节数详见media-report.json；本地仅清理本片新建的样本及导入文件，保留已有同hash文件，未改模块源码或个人资料。
- AudioVideoTransferAcceptanceTest实机1项通过（device.log，1.113秒）。首次build.log失败来自新测试字段误用error/uri及nullable mediaRefs，按现有FunctionResult.errorMessage/MediaRef.attachmentId/orEmpty修正；fixed-build.log app＋控制器构建成功259任务/9执行（9秒）。完整qualityCheck成功500任务/58执行（1m18s）。本轮生产实现无需修改，不能把新增验收代码称为生产音视频缺陷修复。
- APK artifacts/stability-20261008-a8media/ZeroV2-a8media-debug.apk已ADB保留数据安装，SHA256 C66AD7DBD8919E7D89A727B5DD72A51CF27A630101F301E923E919730CFABC52，手机与控制器指纹一致；正常主应用恢复、七日常模块ACTIVE，SDK1.1.0及四资产/两侧SDK指纹一致，emergency=false，OS phantom覆盖null，owned forward移除。Room30/provider配置/缓存稳定前缀未变；A4账本1035198 embedding/299短重排，上限1500000/384不变；测试未调用付费模型或改个人对话。证据acceptance-index.json/媒体报告均绑定冻结APK。
- A8a.2c.3d.2a限定通过，A8a.2c.3d.2及A8整体继续开放。下一片A8a.2c.3d.2b真实跨UID附件查看授权/撤权及失败反馈，随后动态视觉/音视频工具真实往返、图片AUTO回退及provider输入证据。仍需A8b资料/工作区逐页首尾/空失败/返回矩阵和A8c全设置/过时项/重复入口收口；既有页面盘点不等于已验收。

### A8a.2c.3d.2a.1 M4A供应器MIME偏移修订（2026-10-08，限定通过）
- 前片真实报告暴露Android FileProvider将M4A返回audio/mpeg；AAC容器解码正确不等于外部查看声明正确。精确实机回归先红（mime-red-device.log，expected audio/mp4 but audio/mpeg），根因是导入直接信任供应器MIME。本片MediaAttachmentPolicy.mimeForImport仅对audio/mpeg且首盒ftyp/常见MP4 major brand可识别的附件返回audio/mp4，AttachmentStore在已有5MB有界读取后使用该结果；真实MP3、短/未知头、视频和未知类型保持原声明。不是完整容器/恶意文件校验或新增大小限制。
- MediaAttachmentPolicyTest四项JVM回归通过，新增用例覆盖M4A误标、真实MP3、短头、未知brand、视频/未知类型不变；原格式限额和路由持久化回归仍通过。mime-device.log三个实机用例通过：真实AAC/视频导入/生产codec/工作站导出回传且MIME精确正确，真实图片URI/原生-工具路线及缺模型反馈，重试归属/缺模型/草稿/轨迹/切换回归。本地模型隔离测试，不调用付费provider、不写个人对话。
- mime-quality-build.log定向单测＋app/控制器构建＋完整qualityCheck通过，具体tasks/时长保留原日志；mime-media-report.json记录AAC 14336 PCM字节、视频3帧与2855/10651输入字节，两份Debian样本路径沿前片hash不增副本。原audio/mpeg报告、APK、失败日志与acceptance-index.json保持历史证据；新证据单独mime-acceptance-index.json，不把原检查改写成已正确。
- 最终APK artifacts/stability-20261008-a8media/ZeroV2-a8media-mime-debug.apk已保留数据ADB安装，SHA256 36383B72433806001D135C850CD7C7C16596CB3FD65F14FAADD268933E8482E4，手机与控制器一致。正常主应用恢复，七日常模块ACTIVE，SDK1.1.0及四资产/两侧SDK指纹一致，emergency=false、OS phantom覆盖null、owned forward移除。Room30/provider配置/稳定前缀不变，A4账本1035198/299，上限1500000/384不变；不声称新增98%缓存或A4严格资源门通过。
- A8a.2c.3d.2a.1限定通过，后续仍从A8a.2c.3d.2b跨UID附件查看授权/撤权与失败反馈接续；动态视觉/音视频工具执行、图片AUTO视觉回退、provider真实输入证据仍开放。A8b资料/工作区与A8c全设置/末屏/残留清理未完成，A8整体不勾选。

### A8a.2c.3d.2b.1 跨UID附件读取授权与撤权（2026-10-08，限定通过）
- AttachmentTile原文件路径→vault验证→FileProvider URI→ACTION_VIEW＋明确MIME＋临时READ授权提取为同一attachmentViewIntent窄函数，生产打开入口仍使用该实现，无第二分享路径、无新增持久/写授权。异常仍沿原可见Toast路径；本片未声称Toast/图库UI验收完成。
- 真实手机外部测试Activity运行于测试APK独立进程/UID，读取生产构造Intent里的真实PNG引用后回报sha256、MIME与UID，不回报媒体正文。目标UID=10025、接收UID=10340不同；临时READ授权可读且字节散列一致，撤权后另一次无授权打开明确SecurityException拒绝。test-only接收器使用Android/Java类和5MB有界读取，不依赖目标APK/Kotlin类加载，不纳入生产或release；仅在AndroidTest manifest exported，生产provider仍不导出。测试后撤销该URI授权并只删除本片新建样本。
- AttachmentGrantAcceptanceTest实机1项通过（device.log），验证是跨UID系统授权而非同UID读取或伪造getType。第二次新读拒绝不等于撤销已打开文件描述符；SAF目录/重启持久读取及第三方图库/音视频播放效果尚未由本片证明。测试使用隔离debug组件唤醒窗口，未改系统字号/密度/安全策略或个人对话。
- final-quality-build.log app＋控制器构建及完整qualityCheck成功530任务/17执行（30秒）；先前quality-build.log成功530任务/37执行（1m8s）。新增接收器在冻结前改用仅Android/Java并保持有界读，未留下外部进程依赖主APK类的问题。APK artifacts/stability-20261008-a8grant/ZeroV2-a8grant-debug.apk已保留数据ADB安装，SHA256 C06B7053E584BE0C59875D86EEF3D83A3689FCB5288885E134A089EAF0410ABF，手机/控制器指纹一致；正常应用恢复、七日常模块ACTIVE，SDK1.1.0及四资产/两侧指纹一致，emergency=false、OS phantom覆盖null、owned forward移除。
- Room30/provider配置/缓存前缀未改，原M4A修订保留；A4账本1035198/299，上限1500000/384不变。该APK未重跑前片音视频/IME/全视觉矩阵，前片结果仍分别绑定其冻结APK，不以本片权限证据替代。无付费provider测试。
- A8a.2c.3d.2b.1限定通过；A8a.2c.3d.2b及A8整体继续开放。接续实际附件查看UI/失败反馈，再补动态媒体工具执行、AUTO视觉回退/provider真实输入；A8b资料工作区、A8c全设置/末屏残留和A9 Live仍待验，未因本片局部授权验证勾选整体。

### A8a.2c.3d.2b.2a 附件卡片预览与持续失败反馈（2026-10-08，限定通过）
- 实机复现文件不存在仍显示“点击打开 · image/png”（red-device-unlocked.log）；预览状态只标记加载完成，未区分空解码结果。AttachmentTile现对图片空结果显示“预览不可用 · 点击尝试打开”；打开异常从瞬时Toast改为卡片内持续可见错误，说明再次点击重试/移除，使用主题error色与无障碍Polite liveRegion。错误随URI/MIME变化重置，预览状态随URI/类型变化重建，成功启动后清除错误；音视频及文件原打开路径/只读授权保持。
- 真实Samsung实机AttachmentViewingAcceptanceTest覆盖浅/深色×100%/200%四组：vault真实PNG缩略图、隔离不存在文件预览提示、点击后明确打开失败、48dp可点击移除按钮及一次回调。AttachmentGrantAcceptanceTest同APK复验外部独立UID读取/撤权后拒绝；final-device.log 2项通过。截图device/attachment-*.png与view-report.json记录实际组件，不访问个人媒体或云端，不以隔离测试Activity替代真实对话/第三方图库或音视频播放验收。
- 初始两次实机被锁屏挡住，日志保留，用户手动解锁后才取得真正缺陷复现。移除测试最初模糊查找文本，错误说明也含“移除附件”，导致量到正文/外层卡片，等待回调和触摸探针同样误点。probe-tree.json及a8view-focus.json/截图确认实际目标是外层卡片；改为优先精确contentDescription定位，再测包含图标的最小真实ACTION_CLICK目标。保留全部失败日志，未降低48dp/一次回调断言；不能将本片探针失败误报为生产移除功能或无障碍点击故障。没有改系统锁屏/字号/密度/安装校验。
- final-quality-build.log 完整qualityCheck及应用/测试包构建成功（530任务）；APK artifacts/stability-20261008-a8view/ZeroV2-a8view-debug.apk已保留数据ADB安装，SHA256 2EFD5357CC4ED276892817F04ECE4F99E4079A771B685829A872049E90380261，安装应用/测试包均与冻结文件一致。正常Main恢复；日常模块最终状态{'bili_downloader': 'STOPPED', 'image_gen': 'ACTIVE', 'qqbot': 'ACTIVE', 'qzone_post': 'ACTIVE', 'searx_search': 'ACTIVE', 'vision_pro': 'ACTIVE', 'web_fetch': 'ACTIVE'}，SDK1.1.0、APK四资产/加载安装/两侧SDK一致，emergency=false、phantom覆盖null、owned forward移除。以final-phone.json实际状态为准，不把STOPPED声称为ACTIVE。
- Room30、provider配置、稳定缓存前缀、Termux模块源码未改；A4账本1035198 embedding/299短重排、上限1500000/384不变。未新增真实provider媒体/98%缓存或正式资源通过的证据；此前音视频/IME证据继续绑定各自APK。
- 子片A8a.2c.3d.2b.2a限定通过；A8a.2c.3d.2b.2和A8整体仍开放，下一片实际第三方图库/播放器打开和返回，随后动态工具执行、AUTO视觉回退及provider真实输入。A8b资料工作区、A8c所有设置/末屏残留统一、A9 Live继续待验。

### A8a.2c.3d.2b.2b.1 三星图库/视频播放器真实显示与返回（2026-10-08，限定通过）
- 复用生产attachmentViewIntent（vault校验→FileProvider URI→明确MIME→临时只读授权），仅测试时显式选择实机已安装三星图库GalleryActivity和MoviePlayer，不修改用户默认应用、不安装新应用、不通过QQ发送媒体。真实600×300合成PNG和12秒H.264 MP4进入独立系统应用：截图中每种RGB采样>2000像素，证明显示实际合成图/视频帧，而非仅接收Intent、读到字节或模拟播放器。图片/视频的系统返回均恢复调用Zero窗口焦点；本片不声称音频外部播放、视频音轨/seek/持续播放时序或系统选择器路径已通过。
- ExternalMediaViewingAcceptanceTest 1项/2接收场景实机通过，device.log；host-back-report.json记录两次真实ADB KEYCODE_BACK，测试通过唯一nonce请求返回、有界等待真实窗口恢复，不调用App业务返回函数。显式receiver选择限于测试，没有替换生产打开入口。测试后撤销各URI只读授权；仅删除本次新建样本，已有同hash保持。此前启动中断留下一份合成视频14723字节（fixture-retention.json），未纳入Room个人资料，现已把新建样本cleanup覆盖Activity启动失败分支。
- 初始低层键事件断言失败（device-back-probe-failed.log）；测试内部shell返回探针阻塞后停止隔离测试，后续一次Activity启动45秒超时，失败/中断日志保留。改用主机真实ADB返回＋测试窗口恢复断言后通过；这些探针问题不记作Zero生产播放器故障。quality-build.log完整qualityCheck、应用与测试包构建成功530任务；生产代码本片无需修订，不虚构新修复。
- APK artifacts/stability-20261008-a8external/ZeroV2-a8external-debug.apk与上一片生产APK字节相同，SHA256 2EFD5357CC4ED276892817F04ECE4F99E4079A771B685829A872049E90380261，已安装应用与冻结APK指纹匹配，新测试控制器ADB安装且指纹匹配。正常Main恢复，模块最终状态{'bili_downloader': 'STOPPED', 'image_gen': 'ACTIVE', 'qqbot': 'ACTIVE', 'qzone_post': 'ACTIVE', 'searx_search': 'ACTIVE', 'vision_pro': 'ACTIVE', 'web_fetch': 'ACTIVE'}；SDK1.1.0、四资产/两侧SDK均一致，emergency=false、phantom覆盖null、owned forward移除。Room30、个人对话/记忆、模型配置与缓存前缀未改；A4账本1035198/299，上限1500000/384保持，没有付费模型调用。
- 新审计边界：hash文件FileProvider.getType实际为application/octet-stream，两款三星viewer在Intent明确MIME下可以显示，本片不证明所有第三方应用兼容该元数据；该边界继续列入音频/系统选择器与附件元数据兼容复核。实机audio/mp4只发现QQ/Termux通用入口，没有专用播放器；未自动打开这些入口或把它们冒充音频播放通过。
- 子片A8a.2c.3d.2b.2b.1限定通过，父项2b.2b/2b.2及A8仍开放。接续2b.2b.2：系统选择器真实一次选择/返回、音频查看兼容与MIME元数据；随后动态媒体工具执行、AUTO视觉回退/provider输入，再按顺序A8b资料工作区、A8c全设置/末屏/残留统一、A9 Live，未跳过未验项。

### A8a.2c.3d.2b.2b.2a 查看URI的MIME一致性（2026-10-08，通过）
- 实机red-device.log复现audio/mp4 Intent的URI查询为application/octet-stream。新增非导出AttachmentFileProvider，继承原FileProvider路径/读取/授权行为，先调用super.getType验证原路径，再读取声明型zero_mime元数据；规范MIME及大小写归一，非法/过长/含换行值回退原行为，不以元数据替代字节解码校验。attachmentViewIntent为vault查看URI附带MIME，并保持与Intent type一致。外部原content URI保持，不改vault hash文件名、旧URI、Room或导入路径，不新增持久/写授权。
- AttachmentMimeMetadataAcceptanceTest实机覆盖音频/视频、大小写归一、原始URI仍可读、声明类型及逐字节一致、非法元数据回退、存在于vault外测试文件的穿越读取拒绝。AttachmentGrantAcceptanceTest独立UID真实读取并查询image/png，撤权后新读取拒绝；device.log 2项通过。首轮原缺陷失败日志保留，原图片/音视频代码未被模拟provider替代。
- quality-build.log完整qualityCheck及应用/测试包构建成功530任务；APK artifacts/stability-20261008-a8mime/ZeroV2-a8mime-debug.apk SHA256 6369D82C9DDCD076868B8FDA77C49C2FAF784C4F3C55332E5ECFFCC31204E8B5 已保留数据ADB安装，应用与控制器指纹一致；正常Main恢复，6模块ACTIVE、bili_downloader仍STOPPED，SDK1.1.0与四资产/两侧一致、emergency=false、phantom覆盖null、owned forward移除。A4账本1035198/299，上限1500000/384未变，无付费模型调用；缓存前缀/模型配置/个人对话和记忆未改。
- MIME子片通过，A8整体及2b.2b.2保持开放，马上接续系统选择器及音频查看；图库/视频显示需在最终修订APK继续复核，不能用旧APK证据冒充。A8b/A8c所有页与末屏统一、A9 Live继续按计划，未提前勾选。

### A8a.2c.3d.2b.2b.2b 音频附件原生预览（2026-10-08，限定通过）
- 实机 audio/mp4 仅有 QQ/Termux 通用外部入口，新增 AttachmentAudioDialog，复用 Media3 和生产 vault/查看 URI，在附件卡片添加 48dp「预览音频」。用户手动播放，可暂停/续播/重播；关闭释放播放器，离开页面暂停，使用临时音频焦点；文件不可用、格式不支持及准备超时均明确显示。未写音乐库或调用 MusicModule，保留外部查看入口。
- AttachmentAudioAcceptanceTest 真实 Samsung 浅色100%/深色200%两组：两秒合成 AAC 实际解码到结束，暂停超过片长后仍暂停，再续播到结束，丢失文件明确反馈；device.log 1项通过，device/audio-*.png 已检查，深色大字正文/按钮可见。初始锁屏及无障碍点击探针失败不算产品失败；改为系统触摸事件后通过，未改系统锁屏、音量或默认应用。此证据不证明麦克风录音、云端识音或扬声器可听质量。
- 同一 APK ExternalMediaViewingAcceptanceTest 回归：三星图库/视频真实 RGB 显示和 ADB 系统返回、URI resolver MIME 一致，viewer-regression.log 1项通过。final-quality-build.log 完整 qualityCheck、应用及测试包成功（530任务、10执行）。APK artifacts/stability-20261008-a8audio/ZeroV2-a8audio-debug.apk SHA256 6157C49A303C8B66E75592BA16AD6BC7227AE04CBFDAC755D044C464C6707AF1 已保留数据安装，应用和测试控制器指纹均吻合。
- 正常 Main 已恢复；SDK1.1.0、APK四资产/加载安装/两侧SDK一致，emergency=false，phantom覆盖null，owned forward移除。最终 image_gen 与 bili_downloader 为 STOPPED，其余5模块ACTIVE；image_gen监督器crashes=0，不宣称所有模块恢复ACTIVE，也未擅自启动。Room30、模型/缓存前缀、对话/记忆未改，A4预算账本1035198/299、上限1500000/384保持，无付费模型调用。
- 音频子片限定通过，系统选择器一次选择/返回仍待验；父项2b.2b.2与A8未勾选。随后动态工具媒体/AUTO与provider边界、A8b/A8c全页与末屏统一、A9 Live继续按顺序。证据索引 artifacts/stability-20261008-a8audio/acceptance-index.json。

### A8a.2c.3d.2b.2b.2c 系统一次选择与附件查看兼容收口（2026-10-08，限定通过）
- AttachmentChooserAcceptanceTest 从生产 AttachmentTile 实际触摸进入 Android ResolverActivity，选择三星「相册」再「仅此一次」，检查生成RGB图片真实显示，宿主ADB系统BACK返回Zero附件界面。device.log 1项通过（5.661s），host-back-report.json 记录真实系统键；resolver/gallery截图已留存，default-resolution.txt 复核 image/png 仍解析到ResolverActivity，未设置默认应用、未选择IM分享入口。
- 两次定位探针失败保留：三星应用标签实际「相册」而非「图库」；系统选择器入场/选择动画改变按钮坐标，测试等待动画稳定后成功。未修改生产代码或降低真实显示/返回门槛。此证据仅为真实图片系统一次选择，不冒充所有文件格式/第三方应用兼容。
- quality-build.log 完整qualityCheck及应用/测试包成功（530任务、10执行）；应用与上一音频片完全相同，SHA256 6157C49A303C8B66E75592BA16AD6BC7227AE04CBFDAC755D044C464C6707AF1，已安装包与冻结APK一致，当前控制器指纹一致。正常Main恢复；SDK1.1.0/四资产/两侧一致，emergency=false、phantom覆盖null、owned forward已移除。日常5模块ACTIVE，image_gen/bili_downloader STOPPED，保持实际状态并列为下阶段运行状态核对项；未擅自启动或把停止声称为恢复成功。
- 结合 MIME 子片2a、音频2b（实际AAC暂停续播/结束/丢失提示）及同APK三星图库/视频回归，2b.2b.2所列「一次选择/音频查看/MIME复核」按上述范围收口。A8整体仍开放，动态工具媒体往返、AUTO视觉回退与真实provider输入边界继续；随后A8b资料工作区、A8c全页设置/末屏残留统一、A9 Live。Room30、对话/记忆、模型配置、稳定前缀保持，A4账本1035198/299、上限1500000/384未变，无付费调用。
- 本轮最新安装产物 artifacts/stability-20261008-a8chooser/ZeroV2-a8chooser-debug.apk；索引 artifacts/stability-20261008-a8chooser/acceptance-index.json，前两片证据分别绑定各自APK和控制器，不覆盖历史失败日志。

### A8a.2c.3d.2c 动态媒体工具实际往返（2026-10-08，限定通过）
- 新增 DynamicMediaAcceptanceTest：独立 a8_acceptance 时间戳模块经生产ReleaseManager发布、ModuleLifecycleManager.safeCall启动/挂载、生产McpClientModule发现并注册，真实ToolDispatcher执行。合成AAC和MP4依次经ChatBot discover_tools→attachment_export→动态inspect_media→attachment_publish→最终回复；Debian模块实际读取隔离附件，校验SHA256/字节数，回传附件类型及逐字节一致。确定性本地planner不调用云端，不以此冒充真实LLM自由编排、OCR/识音/视频语义正确。
- 两组各4工具回合，prefix及prefixVersion每组一致，后续请求历史只追加，结果以真实TOOL附件进入历史；device.log 1项通过（34.072s）。完整qualityCheck＋应用/测试构建530任务、20执行成功。首次测试编译的可空正文类型错误已修订，失败日志build-nullability-failure.log保留，没有降低断言。
- 最终 finally 卸载并停止专用模块、删除本片专用dynamic_modules行、取消临时工具注册与本地模型、恢复自动证据配置。发布版本/部署审计及Debian合成附件保留，未删除用户来源/工作文件或更改日常模块。当前5日常模块ACTIVE，image_gen及bili_downloader保持STOPPED，后续仍需核对用户运行状态；SDK1.1.0/四资产/两侧一致，emergency=false、phantom覆盖null，owned forward移除，正常Main恢复。
- APK artifacts/stability-20261008-a8dynamic/ZeroV2-a8dynamic-debug.apk SHA256 C6644783C276B5206A9069D1689C2113759ABCB0EE4B93C665FD906EE0E84384 已保留数据安装，应用/控制器指纹一致。生产代码本片未改，仅debug入口与native验收；旧图库/音频证据仍绑定旧APK，不称已复验本APK。Room30、模型配置/稳定前缀及个人数据保持；A4账本1035198/299，上限1500000/384未变，无付费模型请求。
- 子片2c限定通过；继续AUTO视觉回退及真实provider输入边界，A8a与A8整体未验收。随后A8b资料/工作区、A8c所有设置/末屏残留统一与A9 Live，不能用管线通过代替全页验收。证据索引 artifacts/stability-20261008-a8dynamic/acceptance-index.json。

### A8a.2c.3d.2d AUTO视觉回退字节恢复与统一守卫（2026-10-08，限定通过）
- red-device.log 在真实ChatBot复现：AUTO图片缺少临时base64时被过滤跳过，回合没有媒体不可用说明。修订取消该过滤，通过StoredAttachmentReader从原持久URI恢复图片字节；IO线程执行有界读取，无法读取明确生成image_read_failed关联块，保留动态工具导出指引，不改用户原话。原生图片模型存在及TOOLS强制选择保持原逻辑。
- 原预分析直接调用VisionModule绕过统一工具owner守卫，改用ToolDispatcher.execute(vision_analyze)→ModuleLifecycleManager.safeCall；尊重视觉模块禁用/目录状态并保留错误，允许LLM选择动态补充。回退块仅进入一次turnContext历史，稳定前缀协议不改；不是为缓存填充重复文字或声称98%命中。
- AutoMediaFallbackAcceptanceTest验证缺失图片明确不可用、真实PNG持久URI字节恢复到本地视觉契约handler、明确禁用真实Vision owner后不执行handler并有回退说明、用户正文保持。最初guard用例把UNINITIALIZED当禁用，与现行runtimeState契约不符；改为真实isEnabled=false并finally恢复，失败日志device-owner-fixture-failure.log保留，未改守卫门槛。
- 最终同APK实机AutoMediaFallback/MediaUri/DynamicMedia/RegenerationTools共4项通过；前IO版本同4项通过另存device-before-io-offload.log。当前实机包含真实音视频发布/挂载/MCP读取导出回传、原生/TOOLS图片选择、重生成工具续轮及稳定前缀/只追加历史。视觉模型handler及planner为本地确定性契约，不能外推真实provider识图语义、视频声音或缓存96–98%门通过。
- final-quality-build.log完整qualityCheck＋应用/测试包530任务、50执行通过。APK artifacts/stability-20261008-a8auto/ZeroV2-a8auto-debug.apk SHA256 2C8FE843813236261D239846CDDD26A84B81F7F7F7FB97722D3310014E5C5FFA 已保留数据安装，应用/控制器指纹一致；正常Main恢复，SDK1.1.0/四资产/两侧一致，emergency=false、phantom覆盖null、owned forward移除。日常状态以final-phone.json为准：{'bili_downloader': 'STOPPED', 'image_gen': 'STOPPED', 'qqbot': 'ACTIVE', 'qzone_post': 'STARTING', 'searx_search': 'ACTIVE', 'vision_pro': 'ACTIVE', 'web_fetch': 'ACTIVE'}。未擅自启动STOPPED模块。
- Room30、个人对话/记忆、模型配置/稳定前缀保持；A4账本1035198/299、上限1500000/384不变，无付费模型请求。仅删除本次拥有的本地合成附件及专用dynamic_modules行，独立发布审计/远端合成附件留存；日常模块不注入失败。工作树所有既有改动保留。
- 子片2d限定通过，A8整体未完成。接续2e真实provider媒体请求与可用配置边界，之后A8b资料/工作区各页与文档打开、A8c所有设置/末屏残留与入口统一、A9 Live。系统图库/音频UI旧证据继续绑定旧APK，本片不冒充最终全页UI复测。证据索引 artifacts/stability-20261008-a8auto/acceptance-index.json。

A8 AUTO最终模块恢复补核：首采样qzone_post=STARTING，稍后只读复核已ACTIVE；最终5日常模块ACTIVE，image_gen/bili_downloader仍STOPPED。补核报告 artifacts/stability-20261008-a8auto/module-restoration-recheck.json；未启动停止模块或更改配置。

### A8a.2c.3d.2e.1 真实provider合成媒体请求（2026-10-08，限定通过）
- 手机现有配置：deepseek-v4-flash启用且声明IMAGE_INPUT，MiMo mimo-v2.5-asr启用且凭据可取用；qwen聊天profile已停用。只记录能力/启用/密钥是否配置，不导出密钥、不更改模型设置。生产MediaAnalysisService→OpenAiCompatibleClient实际请求：DeepSeek图片返回73142，三个真实视频采样画面返回红/绿/蓝，MiMo将本机SAPI生成语音转写为“测试代码：七三一四二。”。独立数字、颜色及语音答案来自合成媒体，不是模型自评。
- RealMediaProviderAcceptanceTest device.log 1项通过（3.989s），共3次真实请求；图片/视频用现有profile的临时256输出、关闭思考副本，不写回配置。测试自身累计attempts六次上限，发送前持久预留、失败不退次；本轮provider-journal.json=3，未清零。生产BudgetGuard预检查和client响应usage计量保持；该counter不是token消耗报告。A4 embedding/rerank账本1035198/299、上限1500000/384未变，此处不能把“零付费请求”沿用到本片。
- 范围：真实provider请求、持久图片字节读取、视频三个采样画面与音频转写；不证明视频音轨/全连续画面理解、所有模型、用户实际聊天UI/工具自由编排或缓存96–98%通过。图片/视频请求由有界profile facade走生产client，生产原始参数全部可用性的验收仍需最终回归。
- quality-build.log 完整qualityCheck及应用/测试包530任务、20执行通过。APK artifacts/stability-20261008-a8provider/ZeroV2-a8provider-debug.apk SHA256 8B39311D72711786BFEB81F3E7410129F5C79EF2F3E57E05E665F8ACD144BAE7 已保留数据安装；采集时应用/对应provider控制器指纹一致，正常Main恢复；SDK1.1.0/四资产/两侧一致，emergency=false、phantom覆盖null、owned forward移除，5日常模块ACTIVE、image_gen/bili_downloader STOPPED。
- 接续资源审计：独立本地模型的MediaTemporaryFramesAcceptanceTest已实机复现一次分析新增3个持久抽帧文件（frame-red-device.log），不将此问题因真实请求通过而忽略。继续2e.2临时帧生命周期修订与同APK回归，再决定A8a媒体闭环；A8b/A8c全页与末屏、A9 Live保持未验。Room30/个人对话记忆/主缓存前缀未改，既有工作树保留。证据索引 artifacts/stability-20261008-a8provider/acceptance-index.json。


### A8a.2c.3d.2e.2 临时抽帧生命周期与媒体/对话汇总（2026-10-08，限定通过）
- 根因：MediaAnalysisService.image对每个仅用于分析的采样帧执行vault.save，一次分析遗留3个无持久消息引用的文件；原实机红证据保留a8provider/frame-red-device.log。改为内存base64＋内容SHA256的短URN，普通图片沿原附件URI，不重复持久化，不清理历史vault或修改用户媒体。真实codec/StoredAttachmentReader使用已有payload，成功、失败和取消自然释放请求内存。
- MediaTemporaryFramesAcceptanceTest实际解码3帧，成功/IOException可见回退/取消传播后vaultGrowth=0；同APK AUTO回退、MediaUri、实际Debian动态模块导出回传及重新生成工具续轮合计5项实机通过（device.log，36.303秒）。完整qualityCheck＋应用/测试包530任务通过（初次1m57s/85执行；最终测试控制器11s/16执行）。MaxLineLength构建失败留存quality-line-failure.log，通过拆行修订，没有新增抑制。
- 真实DeepSeek视频复验1请求通过（real-video-device.log，1.415秒）；图片73142与MiMo合成语音“测试代码：七三一四二。”补验2请求通过（real-image-audio-device.log，2.074秒）。本轮累计6真实请求到达自设六次上限，未清零；A4账本1035198 embedding/299短重排及1500000/384上限不变。媒体三种最终JSON均可解析且passed=true。上一片采集脚本误写a8real-media-report.json，冻结文件实际为cat错误，不能作为语义JSON证据；原失败文件/索引不覆盖，本片改成真实文件名并强制解析/检查通过，补齐报告。旧片device.log实际通过仍保留，但其JSON留存声明由本更正限定。
- 同APK对话复验6项首轮5通过/1探针失败（final-chat-ui-device.log）；重试按钮实际禁用树无ACTION_CLICK，旧父节点缓存使等待超时。仅测试遍历刷新父节点后，浅深×默认/200%四组重试复验通过（retry-ui-recheck.log，4.714秒），保持不可重复调用/48dp断言，未改生产行为。其余16组全来源对话/轨迹首尾、4组Markdown复制/横向滚动、停止collector、8组IME允许/禁用与草稿Activity重建均在本APK通过；device下保存报告/截图。各日志绑定对应冻结控制器，主APK字节相同，不冒充整个控制器组合一次全绿。
- APK artifacts/stability-20261008-a8frames/ZeroV2-a8frames-debug.apk SHA256 C7480E31B6D03764A5613F6A69311621B57F440DB7B5C06FBB2D36CE216E7C85，已保留数据ADB安装并与手机指纹一致；最新控制器一致，正常Main恢复、SDK1.1.0/四资产/加载安装/两侧SDK一致、emergency=false、OS phantom覆盖null、owned forward移除。末次只读恢复复核日常状态{'bili_downloader': 'STOPPED', 'image_gen': 'STOPPED', 'qqbot': 'ACTIVE', 'qzone_post': 'ACTIVE', 'searx_search': 'ACTIVE', 'vision_pro': 'ACTIVE', 'web_fetch': 'ACTIVE'}；STOPPED未擅自启动。Room30、个人对话/记忆、模型配置/稳定前缀不改。
- 媒体父项2e及2d/2c/2b/2a聚合按所列合成媒体、URI、外部授权/查看、原生AUTO/TOOLS、实际动态模块和当前provider范围通过；不证明全视频音轨/连续帧、所有模型、真实LLM自由编排或96–98%缓存门。本片UI汇总只覆盖对话组件及已有业务契约，图库/音频/系统选择器先前证据仍绑定各自APK。
- A8a限定计划范围汇总通过，A8整体继续开放。接续A8b.1工作文件页：统一页头/分组/线性图标与长文阅读，保留SAF逐级目录及唯一配置owner，覆盖首末行、空/错误/忙态、Markdown关闭/返回和200%字号；然后记忆/图谱/音乐/任务/模块/日志逐页推进。A8c所有设置/末屏残留和A9 Live仍未验收。证据索引 artifacts/stability-20261008-a8frames/acceptance-index.json。


### A8b.1a 工作文件页视觉与阅读交互（2026-10-08，限定通过）
- WorkFilesScreen采用共享ZeroPageHeader/ZeroCard、线性图标、主题前景配对与统一留白；去除标题emoji和过时版本注释，目录配置仍只有本页SAF owner。列表整行可打开，打开/删除无障碍标签绑定具体文件；目录不再显示假文件大小，页头返回与系统返回均进入父目录。配置/刷新/上级/阅读关闭操作最小48dp；原生Markdown阅读保留选择复制/长文滚动并避让系统栏，删除仍确认，外部ACTION_VIEW及临时读授权保持。
- 提取实际生产WorkFilesContent为纯状态投影，ViewModel仍管理唯一WorkDirRepository/SAF数据路径；没有第二文件管理实现或mock生产文件读取。debug fixture仅提供隔离合成目录/文本及计数回调。实机WorkFilesVisualAcceptanceTest 1项49.862秒通过，360/720dp×浅深×动态/固定×默认/200%字号16组，覆盖目录进入/返回、Markdown末段/关闭、列表末行、删除取消不触发删除、操作48dp与主题文本对比≥4.5；另验未配置/授权错误可重新选择/忙态无回调。51张首屏/末屏/阅读尾图与结构报告保存在device，已目视核对浅色首屏。
- quality-build.log 530任务41秒通过；final-quality-build.log 530任务31执行35秒通过，包含最终测试对比断言与自动镜像打开图标。APK artifacts/stability-20261008-a8workfiles/ZeroV2-a8workfiles-debug.apk SHA256 15881EC5D3F4610A144372A871365BEC7F6965AED482E36877341A670665F953，保留数据ADB安装，应用/控制器指纹一致；正常Main恢复，SDK1.1.0/四资产/加载安装/两侧SDK一致、emergency=false、phantom覆盖null、owned forward移除，5日常模块ACTIVE、image_gen/bili_downloader保持STOPPED。
- 当前页与A8a媒体资源修订同处最新APK，但本片没有重跑真实provider、第三方图库或全部对话矩阵，旧证据按其冻结APK绑定。此片零新增云端调用，provider累计6不重置，A4账本1035198/299与上限1500000/384保持；Room30/个人资料/配置/稳定前缀不改。
- 1a UI限定通过，不等于真实SAF持久授权/生产目录读取通过；A8b.1与A8整体仍开放。下一片1b先核对现有SAF授权，走真实Repository/ViewModel目录和Markdown只读读取，之后补隔离失败/返回与授权恢复，再进入记忆/图谱/音乐/任务/动态模块/日志逐页。A8c所有设置/尾页残留、A9 Live仍待验。索引 artifacts/stability-20261008-a8workfiles/acceptance-index.json。


### A8b.1b 真实SAF目录与Markdown跨进程读取（2026-10-08，限定通过）
- 直接使用手机已有工作目录及其持久read/write授权，不更改tree配置/权限。独立UUID子目录只写一份合成readme.md，经生产WorkDirRepository.writeText实际落点和真实WorkFilesViewModel进入目录、打开文件、正文UTF-8/逐字节一致、关闭、返回根目录及不存在文件可见错误；没有读取/导出个人资料正文或使用云端。
- 两次独立instrumentation进程完成prepare/verify，PID 21590→21689，真实持久授权与文件内容跨进程保持。final-prepare-device.log 1项1.655秒、final-verify-device.log 1项1.757秒通过；verify-report.json/最终报告记录fixtureRemoved=true，侧车元数据不存在复核exitCode=1。清理先检查专用目录只有本片文件且内容一致，再删已验证文件、核对目录为空、走deleteTree清理专用目录，不递归清理其他目录，不撤用户授权。
- 初轮读取/跨进程检查已通过，但测试清理误用deleteFile删除文件夹，被生产目录守卫拒绝；verify-device.log失败与verify-report.json检查结果均保留，不能因报告passed=true忽略finally失败。修正测试清理为目录专用API，对旧遗留空目录专门核对owner/树指纹/无子项后清理（cleanup-device.log 1项0.207秒），再用新独立样本完整重跑两阶段通过。没有修改生产目录删除守卫来掩盖测试错误。
- quality-build.log完整qualityCheck＋应用/测试包530任务34执行1m11s通过，final-quality-build.log最终控制器530任务16执行20秒通过。APK artifacts/stability-20261008-a8saf/ZeroV2-a8saf-debug.apk SHA256 35D46C429D88E82FABD24BA56D5377FB9FB265EE1E5FF4CC04006EFE65FC2779，已保留数据ADB安装，应用/控制器指纹一致，正常Main恢复；SDK1.1.0/四资产/加载安装/两侧SDK一致、emergency=false、phantom覆盖null、owned forward移除，5日常模块ACTIVE，image_gen/bili_downloader仍STOPPED。
- 本片只增加debug验收入口和native测试，工作文件生产UI沿1a实现、数据仓库未改。1a的16组/51截图仍绑定a8workfiles APK，媒体/对话前片各自绑定冻结APK，不冒充全部在新APK重新运行。零新增云端请求，provider累计6不清零；A4账本1035198/299、上限1500000/384、Room30、个人数据和模型/缓存前缀保持。
- A8b.1b限定通过；A8b.1父项仍开放，接续1c真实系统目录选择器取消/重新授权与UI绑定复核，不为负面测试撤销个人工作目录现有授权。附加审计待复现：deleteTree的dirs计数包含目标自身，force=false空目录可能也被“非空”拒绝，需独立小片验证，不将本片force=true的专用空目录清理外推为该契约通过。随后A8b.2记忆管理及图谱/音乐/任务/模块/日志、A8c设置尾页残留、A9 Live继续。A8整体未完成；索引 artifacts/stability-20261008-a8saf/acceptance-index.json。


### A8b.1c.1 空目录删除契约与根路径保护（2026-10-08，通过）
- 独立UUID合成SAF目录实机复现空目录被当作非空，red-device.log/红JSON明确fileCount=0、dirCount=1。根因是collectDeleteList将目标自身放入dirs，判断误用dirs.isNotEmpty；现仅文件存在或目录数量>1时要求force，不改既有递归确认、Mutex、审计结果和普通文件删除保护。空目录/含文件/含空子目录三种真实SAF场景已验证，非空拒绝后原内容仍逐字节一致，最终专用样本清理成功。
- 同时补齐deleteTree边界：缺失/空白/根路径及./..非法路径必须在provider访问前拒绝，force=true也不能绕过。FilesModule缺失folder_name会传空字符串，旧resolvePath允许空路径指向工作根目录，不能把旧偶然非空拒绝当保护。负面测试使用独立SharedPreferences＋无效隔离provider URI，对12种force/路径组合断言具体拒绝，不向个人工作根目录发送删除请求，不改用户配置或授权。
- EmptyDirectoryDeletionAcceptanceTest实机2项1.057秒通过；最终完整qualityCheck/应用与测试构建530任务35执行1m26s通过（此前构建日志另存）。APK artifacts/stability-20261008-a8empty/ZeroV2-a8empty-debug.apk SHA256 8188D88D35BFD5301677A4B1485C53C3EAEF1301C3C26093FAC537F579C596B2 已保留数据ADB安装，手机/控制器指纹一致，正常Main恢复，SDK1.1.0/四资产/两侧一致、emergency=false、phantom覆盖null，owned forward已移除。日常状态末次只读复核{'bili_downloader': 'STOPPED', 'image_gen': 'STOPPED', 'qqbot': 'ACTIVE', 'qzone_post': 'ACTIVE', 'searx_search': 'ACTIVE', 'vision_pro': 'ACTIVE', 'web_fetch': 'STOPPED'}，不把STOPPED报告成ACTIVE或擅自启动。Room30、模型配置、稳定前缀和个人数据不改；无云端请求，provider累计6与A4账本1035198/299、1500000/384上限保持。
- 小片1c.1通过，A8b.1c/1及A8整体保持开放。下一小片1c.2使用真实WorkFilesScreen/Hilt ViewModel与Android目录选择器，验证取消不改配置、重新选择现有目录恢复、实际UI目录/Markdown绑定；不为失败用例撤销个人授权。索引 artifacts/stability-20261008-a8empty/acceptance-index.json，前片UI/provider证据仍各自绑定冻结APK。


### A8b.1c.2 真实目录选择器与工作文件页面（2026-10-08，限定通过）
- WorkFilesScreen使用实际Hilt ViewModel与Android OPEN_DOCUMENT_TREE，当前持久tree授权转换成对应document URI作为初始位置。tree URI直接传入未定位到当前工作目录的实机失败日志及原APK/控制器保留；修订后真实选择器定位验证通过。取消返回不改zero_work_dir配置与授权，重新选择同一目录真实执行saveDirectory回调并增加成功trace，持久读写权限保留。
- 原生测试实际进入UUID合成目录、打开并逐字节绑定Markdown、关闭阅读页与返回根目录，最终专用样本清理成功、原配置完全一致。未撤销个人授权，未读取或导出个人文件正文，未将同目录重选外推为个人授权撤销恢复。失败诊断还修复主机不支持mCurrentFocus探针（改topResumedActivity）、嵌套目录BACK先退上级的系统行为（最多8次且限定系统选择器前台）及取消信号复用误伤第二次选择，所有失败日志保留，生产断言未放宽。
- WorkFilesPickerAcceptanceTest实机1项6.258秒通过；完整qualityCheck与应用/测试构建530任务35执行1m44s通过，最后测试信号修订后530任务16执行26s再次通过。APK artifacts/stability-20261008-a8picker/ZeroV2-a8picker-debug.apk SHA256 9894B3D823B83F77EBAA6553F0C56913147AB8E33C7CF9F3775E90B7E5E341CD 已保留数据安装，手机/控制器指纹均一致。正常Main恢复，SDK1.1.0、四资产与两侧一致，emergency=false、phantom覆盖null；日常模块{'bili_downloader': 'STOPPED', 'image_gen': 'STOPPED', 'qqbot': 'ACTIVE', 'qzone_post': 'ACTIVE', 'searx_search': 'ACTIVE', 'vision_pro': 'ACTIVE', 'web_fetch': 'STOPPED'}，STOPPED如实记录未擅自启动。无新增云端请求，provider累计6与A4账本1035198/299、上限1500000/384保持。
- 工作文件页A8b.1按1a视觉矩阵、1b跨进程SAF、1c删除契约和真实选择器/阅读范围限定收口；各旧UI矩阵与媒体请求继续绑定各自冻结APK，并非本片重复全量矩阵。A8b其他页/A8c设置尾页和A9 Live继续开放。下一片A8b.2记忆管理：统一页头/卡片/图标、修复长文详情滚动与小字对比度，保留来源核验标注和既有事务删除链路。证据索引 artifacts/stability-20261008-a8picker/acceptance-index.json。

- 1c.2截图补验：原生无障碍节点先于系统Dialog淡入完成，初次截图保存为initial-animation-document.png。测试等待空闲与500ms系统淡入后再次实机通过（7.103s），完整阅读页背景已人工视觉复核无底层串显；应用APK未变，测试控制器新指纹和源码/证据哈希已更新索引，旧通过控制器与日志保留。该补验构建成功，不改系统动画设置。


### A8b.2a 记忆管理视觉与长文阅读（2026-10-08，限定通过）
- MemoryManageScreen保留Hilt/observeAll与MemoryDeletionRepository事务删除owner，提取生产展示函数供独立合成UI验收，不用测试ViewModel替换生产数据路径。统一ZeroPageHeader/ZeroCard、线性搜索和删除图标、主题配色与48dp筛选/关闭/删除目标，移除脑/归档emoji及英文生命周期枚举展示，卡片信息允许换行，正文/来源/标签均使用实际容器的配对颜色；来源核验政策不改。
- 独立合成长文在360视口、200%字号实机复现详情缺滚动视口（red-device.log，2.593s）。详情增加滚动与正文选择，关闭/删除仍独立可达。后续16组矩阵通过后筛选复验发现“偏好→全部”仍保留中段列表锚点（filter-anchor-red-device.log/合成截图），现仅搜索或类型改变时回首行，DAO新资料变化不触发强制回首。
- 浅/深、动态/固定色、字号100%/200%、主页面360/720受控视口共16组实机最终通过；检查列表末行、长文末尾、来源未核验标注、至少4.5对比度、关闭返回≥48dp、筛选和无结果搜索、空态及删除失败保留提示。保存50张合成截图，末尾正文和元数据人工视觉复核。测试Activity在attachBaseContext创建独立配置上下文并明确断言fontScale=1/2；首次onCreate覆盖因资源已读取而失败的日志/APK保留，改为资源读取前配置后再验。修复仅LocalDensity控制父页而独立Dialog未继承的问题；未修改手机全局字号。Dialog按Fold3真实独立窗口复核，不把父页360视口当作窄屏Dialog/物理折叠姿态证据；旧仅投影字号的通过日志/APK/控制器另存，最终原生耗时60.819s。
- 最后完整qualityCheck/应用与测试构建530任务33执行1m08s通过（attached-font-quality-build.log），此前源码确认构建259任务1执行5s通过（此前FlowRow缺ExperimentalLayoutApi opt-in编译失败及修复记录保留）。APK artifacts/stability-20261008-a8memory/ZeroV2-a8memory-debug.apk SHA256 941FE69F4A8A2F370A230BE5EFB9647F029DCACEE9F00BCA8A0EBDDAD2A58A76 已保留数据ADB安装，手机与控制器指纹一致；恢复正常Main，SDK1.1.0/四资产与两侧一致、emergency=false、phantom覆盖null。日常模块{'bili_downloader': 'STOPPED', 'image_gen': 'STOPPED', 'qqbot': 'ACTIVE', 'qzone_post': 'ACTIVE', 'searx_search': 'ACTIVE', 'vision_pro': 'ACTIVE', 'web_fetch': 'STOPPED'}，STOPPED如实记录。无云端请求，无个人记忆写入；provider累计6与A4账本1035198/299、上限1500000/384保持。证据索引 artifacts/stability-20261008-a8memory/acceptance-index.json。
- 2a限定通过，A8b.2父项和A8整体继续开放。接续2b核验真实记忆读取/来源/删除失败；新审计项：MemoryManageViewModel读取catch空吞可能将失败误报暂无记忆，需要loading/error/重试和取消传播独立修订，不能拿本片合成空态/删除失败提示外推生产DAO故障通过。窄屏独立详情窗口/折叠姿态列为待验。随后图谱/音乐/任务/模块/运行日志、A8c所有设置/尾页残留入口和A9 Live继续按序。


### A8b.2b.1 记忆读取失败、重试和取消传播（2026-10-08，通过）
- 实机最小失败用例已确认observeAll抛错后ViewModel状态与成功空列表完全相同（red-device.log）；候选吞异常/DAO未触发/UI丢错误中，明确DAO已抛错且直接检查VM，定位init空catch。新增isLoading/readError与retryRead单订阅Job，普通异常显示可重试状态、保留已加载资料和搜索/类型，重复重试不增加活动订阅，CancellationException重抛。UI加载/失败不再显示暂无记忆，已有记录可继续阅读，错误卡与48dp重新读取入口消费生产VM方法，Room读流/Hilt及Mutex+事务删除owner不改。
- 实机7测试66.672s通过：5个真实VM状态/重试/取消/同步订阅失败/实际Room读取，既有GraphLifecycle删除触发器拒绝用例（独立in-memory DB保留原资料），以及16组浅深/动态固定/100%-200%/360-720有效父视口加载失败旧资料与重试UI、两类来源标签；保存49张合成截图。真实已安装Room只读1376条逐项元数据与向量字节比对一致，来源eligible 6/未核验1370，仅输出计数不导出正文。此分类不证明旧资料错误，也未自动升格或删除历史数据。
- 最终只读比较改用脱敏布尔断言，避免测试失败日志输出个人记录正文；控制器构建259任务6执行9s后，VM与删除用例6项0.538s再次通过。UI测试源码未变，UI证据绑定matrix-controller.apk，最终状态证据绑定state-controller.apk；应用APK不变。完整qualityCheck/应用/测试530任务35执行1m41s通过。
- APK artifacts/stability-20261008-a8memory-state/ZeroV2-a8memory-state-debug.apk SHA256 37124C34707E8BADF940E02D6B582425D413CF17B6A83D3F8C018AFD013CB06C 已保留数据ADB安装，应用/最终控制器指纹一致，正常Main恢复；SDK1.1.0/四资产/两侧一致、emergency=false、phantom覆盖null，日常模块{'bili_downloader': 'STOPPED', 'image_gen': 'STOPPED', 'qqbot': 'ACTIVE', 'qzone_post': 'ACTIVE', 'searx_search': 'ACTIVE', 'vision_pro': 'ACTIVE', 'web_fetch': 'STOPPED'}，STOPPED如实记录未擅自启动。零新增云端请求，provider累计6、A4账本1035198/299与上限1500000/384保持，Room30/个人资料和模型前缀未改。
- 1通过，2b/2父项暂不勾选，接续2b.2实际Fold3折叠外屏的窄屏详情/末尾/关闭返回与错误重试；用户已回复外屏解锁。设备已确认state=0，外屏physical832x2268、已有effective840x2289/density360覆盖，保留现有显示配置，不把父页360视口外推为独立窄屏弹窗通过。其后图谱等A8b及A8c/A9继续开放。索引 artifacts/stability-20261008-a8memory-state/acceptance-index.json。


### A8b.2b.2 Fold3实际折叠外屏与记忆页收口（2026-10-08，通过）
- 用户已实际合上手机并解锁外屏，前后device_state=0；physical832x2268，现有effective840x2289/density360，实际约373dp。未改显示覆盖、系统字号、锁屏或超时。测试Activity资源fontScale明确为1/2，仅供隔离合成界面；此次是独立Dialog处于真实外屏窗口，补齐前片父视口矩阵不能外推的窄屏证据。
- 浅深/动态固定/100%-200%共8组实机通过（MemoryFoldAcceptanceTest 1项44.589s，32截图）：列表首末行、详情滚动至长文末尾和元数据、关闭/返回、删除按钮≥48dp且完整在实际屏幕内（不执行删除）、读取错误与重新读取回调。200%窄屏长文/操作和错误页人工截图复核。显示尺寸、密度和物理姿态前后完全一致，无个人资料写入和provider请求。
- 本片只新增控制器用例，应用APK与2b.1完全相同SHA256 37124C34707E8BADF940E02D6B582425D413CF17B6A83D3F8C018AFD013CB06C，保留数据安装已核对，最终fold-controller指纹一致；本片应用/控制器构建259任务6执行8s通过，2b.1完整qualityCheck530任务仍按其产物绑定。正常Main恢复，SDK1.1.0/四资产/两侧一致、emergency=false、phantom覆盖null，模块{'bili_downloader': 'STOPPED', 'image_gen': 'STOPPED', 'qqbot': 'ACTIVE', 'qzone_post': 'ACTIVE', 'searx_search': 'ACTIVE', 'vision_pro': 'ACTIVE', 'web_fetch': 'STOPPED'}。provider累计6、A4账本1035198/299和上限1500000/384完全不变。
- 结合2a受控视觉/长文/筛选、2b.1真实Room/来源分类/删除失败与错误恢复、2b.2实际外屏，A8b.2/2b按上述计划范围收口；不声称旧1370条未核验记录已核实或已重跑全部媒体/provider。A8整体仍开放。下一片A8b.3图谱：读取异常不能置空冒充暂无、去重复加载、统一页头/卡片/大字号/来源与关系可达；其后音乐/任务/模块/日志及A8c全设置/尾页、A9 Live继续。索引 artifacts/stability-20261008-a8memory-fold/acceptance-index.json。


### A8b.3a 图谱读取状态与重试（2026-10-08，通过）
- 实机red确认DAO抛错后GraphUiState被重置为成功空列表；直接VM证据排除DAO正常为空及UI丢错误。新增readError、保留已加载实体/属性/关系、显式重试单活动Job、CancellationException重抛；移除页面LaunchedEffect与VM init双加载。生产页使用统一ZeroPageHeader和错误卡、≥48dp重新读取入口，加载/失败不冒充暂无。移除空态过时设置路径描述，不新增重复设置入口。
- 独立内存Room/生产VM四项与生产UI投影一项实机通过：首次失败、失败保留图谱后重试恢复、并发调用只发一次DAO查询、取消传播；折叠外屏360dp父视口的浅深/动态固定/100%-200%八组加载/失败/旧数据/重试回调，24张合成截图。UI使用合成图谱，不写个人资料，不等同完整图谱长属性/关系验收。
- qualityCheck/assembleDebug/assembleDebugAndroidTest BUILD SUCCESSFUL，530任务35执行1m24s；APK保留数据ADB安装与控制器指纹核对通过。SDK1.1.0/四资产/两侧一致、emergency=false，正常Main已恢复；四个日常模块ACTIVE，其余三个STOPPED照实保留。零云端新增，provider累计6、A4累计1035198/299与上限1500000/384未变；Room30和模型稳定前缀未修改。工作树保留不提交。
- 证据 artifacts/stability-20261008-a8graph-state/acceptance-index.json；应用SHA256 2ead38a95117238c661c8b224776a6bd6dc5659a493927eee0c1ca8573d56eae。3a限定通过，A8b.3父项/A8整体仍开放。接续3b长属性/关系/来源及展开、首尾、返回和颜色配对的统一美学验收，其后音乐/任务/模块/日志及A8c全设置、A9 Live按序。


### A8b.3b 图谱内容美学与真实读取（2026-10-08，外屏限定通过）
- 生产图谱统一ZeroCard/页头/中性色；实体标题与类型/展开纵向排版，≥48dp点击仅作用标题区域，长属性纵向阅读并可选中文字；正文/来源分别配onSurface/onSurfaceVariant，关系优先显示当前实体名称，目标缺失保留原ID，保留类型和完整生效日期。来源可追溯/来源未核验与记忆提取/历史实体规则沿用，不以美学改动升级旧事实。关系分组和名称映射按状态remember一次。
- Fold3实际折叠外屏的360dp父视口，浅深/动态固定/100%-200%八组40张合成截图：长属性末尾、来源两类、正常关系名称/缺失目标回退、展开收起、末行实体和返回、真实空态旧说明清理。正文与次级文字配卡片底色对比≥4.5。旧3a八组错误/加载/保留旧数据/重试同步复验，五个生产VM测试含真实已安装Room只读，最终7项实机通过。首次测试在末行仅部分露出时停止滚动，partial-tail-device.log保留失败；控制器改为先滚动到完整可见、距底部系统区留24dp再点击，宽/高仍须≥48dp，不放宽门槛；259任务6执行7s控制器构建后整组复验。真实图谱218实体/273属性/161关系逐项实体/当前属性/关系与DAO一致；脱敏断言及报告只计数，trace写隔离内存Room，不导出个人正文。
- 完整qualityCheck/应用/控制器构建530任务35执行1m26s通过，APK SHA256 799603cb6c671760d9ce9cc3a0d93aa94a06c894e3481d1acbc78b3e8fb4ea1c已ADB保留数据安装、两包指纹一致、正常Main恢复。SDK1.1.0四资产/两侧一致，emergency=false，四日常模块ACTIVE/其余三STOPPED保留；provider累计6与A4累计1035198/299、上限1500000/384未变，零新增云端请求，Room30/缓存前缀无改动。证据 artifacts/stability-20261008-a8graph-ui/acceptance-index.json。
- 3b仅外屏/读取限定通过，A8b.3父项与A8整体继续开放。共用矩阵的实际展开屏/分屏、系统返回、重建后展开/滚动状态及完整TalkBack仍需3c补验，不以本片360dp截图代替。其后音乐/任务/动态模块/日志及A8c全设置和A9 Live继续按序。


### A8b.3c.1 图谱重建恢复与可访问语义（2026-10-08，通过）
- 实机最小失败red-device.log复现收起实体在Activity重建后重新展开；相同页面/实体键的夹具排除页面重置与键变化，定位普通remember未保存展开值。生产MemoryGraphContent用rememberSaveable维护展开实体集合，位于Lazy列表之外；GraphEntityCard仅消费expanded/onToggle。独立GraphViewport保存列表索引/偏移，配置重建后等两个布局帧一次性重放合法位置，新滚动优先、取消跟随LaunchedEffect生命周期。owner为页面保存态，卡片只消费状态和回调；UI仅保存展开实体ID集合和列表位置，不存个人正文或向量，不改Room/来源规则/模型稳定前缀。
- 可点击实体标题声明Button角色、实体类型/已展开或已收起状态、展开或收起动作标签；视觉副标题清空冗余语义，避免同一实体名/状态重复。UiAutomation核验名称仅一项、按钮角色/类型状态/动作、可访问焦点入口及点击；不启用或改系统TalkBack，未测试实际语音播报。调试夹具由Intent显式恢复同一合成页面，资源fontScale1/2在重建前后核对，不改系统字号。
- 7项实机通过：既有5个生产VM读取/保留/重试/并发/取消/真实Room用例，新增收起和末行展开重建两项，各浅深/动态固定/100%-200%8组，共32张合成截图；末行位置重建前后偏差≤2dp。普通保存状态后首轮测量会重新夹紧偏移（save-probe-device.log保存[5,196]、恢复后[5,87]）；等待边界稳定、关闭动画、提升展开状态的失败对照均保留，最终布局后一次位置恢复解决，保留展开动画。原生角色父View带子节点导致Button未暴露，修订单一clearAndSetSemantics按钮节点后通过；UiAutomation未开启触摸探索，焦点动作仅核验入口，实际读屏焦点操作/语音留后续。不保留生产诊断，SavedState全量探针已从最终测试移除，仅留历史合成证据。实际Room218实体/273属性/161关系只读逐项一致，失败日志用脱敏布尔断言。用户已展开并解锁，实测state=3/physical1768x2208、现有density360覆盖保持；本片父视口360，不将其外推为720或真实分屏通过。
- qualityCheck/assembleDebug/assembleDebugAndroidTest BUILD SUCCESSFUL，BUILD SUCCESSFUL in 1m 18s / 530 actionable tasks: 33 executed, 497 up-to-date（此前阶段质量日志单列）；67c3271c7cb292e6c1b107f546cf401ce767f6b9f9099889fa601cc661f1fdaf已保留数据ADB安装并核对应用/控制器指纹，正常Main恢复；SDK1.1.0四资产及Android/Debian一致，emergency=false；四日常模块ACTIVE/其余三STOPPED未擅自启动，provider累计6/A4累计1035198与299/上限1500000与384不变，验收代码不调用provider。工作树保留不提交。
- 证据 artifacts/stability-20261008-a8graph-restore/acceptance-index.json。3c.1通过；3c父项/A8b.3/A8整体仍开放。下一片3c.2真实内屏720/360矩阵、实际Main系统返回/页头返回；真实分屏及TalkBack语音体验单独取证，Activity重建不冒称强杀/进程死亡已验收。随后音乐/任务/动态模块/日志、A8c全设置残留与A9 Live继续按序。


### A8b.3c.2a 真实内屏宽窄矩阵与Main返回（2026-10-08，通过）
- 用户展开并解锁后实测state=3，physical1768x2208、现有density360覆盖不改。真实窗口约786dp，控制器先要求实际宽度≥720dp，再覆盖360/720父视口×浅深×动态固定×100%-200%各16组：长属性末尾、关系名称/缺失目标回退、两类来源、展开收起、末行/返回/空态；加载/失败不置空、旧资料保留与重试原回调复验。两项实机143.534s通过，128张合成截图，不等同真实分屏。
- 单独启动真实ZeroApplication/Main/Hilt/NavHost，实际记忆图谱路由三次往返：系统返回、页头返回、Main重建保留图谱路由再系统返回资料主页；一项实机9.812s通过，外观偏好一致，未编辑资料/配置，不导出个人图谱正文或截图。这里只证明真实路由返回，个人图谱滚动重建不外推自合成夹具。
- 本片仅补控制器/验收用例；应用APK与3c.1完全相同SHA256 67c3271c7cb292e6c1b107f546cf401ce767f6b9f9099889fa601cc661f1fdaf、已安装指纹一致。assembleDebug/assembleDebugAndroidTest 259任务6执行13s通过；完整qualityCheck 530任务33执行1m18s仍绑定3c.1相同应用产物。正常Main恢复、SDK1.1.0四资产/两侧一致、emergency=false；四日常模块ACTIVE/三STOPPED保留，provider累计6/A4累计1035198与299及上限1500000与384账本不变，UI验收动作不直接调用收费provider。工作树保留未提交。
- 证据 artifacts/stability-20261008-a8graph-window/acceptance-index.json。3c.2a通过；A8b.3c.2/3c/3父项及A8仍开放。接续3c.2b真实分屏/窗口变化与资料路由、3c.2c实际读屏焦点与语音体验；当前已验证原生Button名称/类型/状态/动作和焦点入口，未开启系统读屏、不冒称语音实测。进程死亡与跨姿态动效另按A9归属。本片不新增设置入口，不放宽缓存/资源门。


### A8b.3c.2b.1–2 真实分屏与分隔条窗口变化（2026-10-08，通过）

- 用户已将Zero与计算器实际分屏；原生任务mode=multi-window、main/right，Fold3实际展开state=3，原窗口[891,88][1768,2174]约389.78dp。当前系统资源字号实测1.3，density360已有覆盖保持，不修改系统字号、锁屏和超时，不用720dp夹具代替真实分屏。ADB直接操作当前Main，不重启应用或instrumentation，不导出个人图谱正文/截图。
- 2b.1：实际资料主页→记忆图谱→页头返回，再进图谱→系统返回，≥48dp入口完整位于窗口内。2b.2：真实拖动两窗分隔条使Zero缩窄到[1082,88][1768,2174]约304.89dp，图谱路由保留，窄窗48dp页头返回、资料入口再进图谱可达；反向拖动恢复原窗口精确边界，图谱路由仍保留，系统返回资料主页。全程PID一致、前后multi-window，保留原分屏比例，未编辑个人资料。
- 脚本先发现焦点位于计算器而无Zero节点，点击已核对的Zero页头后切回；保留first-focus-failure.log。后续一次uiautomator返回null root可能复用临时旧树，stale-tree-probe记录仅作诊断、不得作为验收；修订每次先删除自有临时文件，要求本次明确成功生成树后再解析，有界重试，最终两份报告均重新实跑通过。已选资料标签没有点击动作时先检查真实资料根路由，点击匹配仅选存在可点击owner的节点；selected-tab-probe.log保留。没有放宽48dp或窄窗门槛。
- assembleDebug BUILD SUCCESSFUL in3s，227任务均UP-TO-DATE；本片只增加外部验收脚本，应用未改。已安装应用SHA256 67c3271c7cb292e6c1b107f546cf401ce767f6b9f9099889fa601cc661f1fdaf与3c.1/2a留存APK一致，未重装以免破坏当前分屏。完整qualityCheck仍引用3c.1同一产物530任务33执行1m18s，不将本片3s构建说成新质量全量。SDK1.1.0四资产已安装/已加载/APK与Debian一致、emergency=false；qqbot/qzone_post/searx_search/vision_pro ACTIVE，其余三STOPPED如实保留。
- 费用账本逐对象比较不变：provider累计6、A4 embedding1035198/短重排299，上限1500000/384；脚本动作不发送provider请求，不新增模型前缀或Room改动，工作树全部保留未提交。脱敏证据 artifacts/stability-20261008-a8graph-split/acceptance-index.json、split-report.json、resize-report.json、final-phone.json；证据索引绑定脚本与日志哈希。
- 2b.1及2b.2限定通过。2b父项保留开放：真实分屏200%资源字号还未实测；3c.2c实际读屏焦点与语音仍待验，既有Button语义/焦点动作入口不能代替实际播报；进程死亡/跨姿态动效归A9。A8b.3、A8b/A8父项不勾选，后续音乐/任务/动态模块/日志及A8c全设置残留、A9 Live按序继续。


### A8b.3c.2c TalkBack用户实机验收（2026-10-08，基本功能通过）

- 用户直接反馈已进行TalkBack验收，设备中文支持不佳、基本功能通过。按用户反馈关闭本片基本读屏操作门，结合此前名称/类型/展开收起/点击/焦点入口语义证据；不声称中文发音完整通过，不将此结论外推全应用其他页面，也没有代理观察的播报录音。设备中文语音支持限制保留记录，不修改系统TTS、读屏或锁屏设置。
- 证据 artifacts/stability-20261008-a8graph-split-font/talkback-user-report.json。图谱父项仍待真实分屏200%资源字号专项；A8整体继续开放，后续音乐/任务/模块/日志、设置残留按序推进。


### A8b.4a 音乐失败反馈与命令后刷新（2026-10-08，状态契约通过）

- 最小实机red两项明确复现：music_resume返回FunctionResult.failure但UI state无错误；playlist_create被门闩暂停尚未完成，loadPlaylists已重读到2次。最初Hilt入口放测试包不能映射到应用组件的harness-entrypoint-failure.log单列，不算业务red；改用非导出的debug RegenerationAcceptanceEntryPoint音乐出口后再跑才得到上述业务失败。测试使用隔离内存Room、受控DAO/dispatcher和真实MusicViewModel，不实际播放、创建个人歌单或调用provider。
- MusicViewModel为唯一UI owner：加载/读取错误/操作错误显式投影，读取失败保留当前数据，重试单活动Job+读Mutex；命令Mutex串行，FunctionResult.success才在命令完成后刷新（扫描/建删歌单/设取消定时），失败不假刷新。CancellationException重抛，finally清busy，普通异常给可见错误、Trace仅错误类别。页面共享ZeroPageHeader，显示读取/错误与48dp重新读取入口，读取失败不冒充成功空库。typed MusicCommandDispatcher/FC名称参数、Hilt组装、实际MusicModule播放owner未迁移，稳定前缀/Room schema不改。
- 最终实机4测试0.53s通过：拒绝进入UI状态、等待成功再刷新、读取错误重试恢复、拒绝不刷新及取消不误报。完整qualityCheck/assembleDebug/assembleDebugAndroidTest BUILD SUCCESSFUL in1m3s，530任务26执行。APK SHA256 efe3c99f45424c1e6a28f7eedad6deb396261c274f7a75fef288a588546642d0已ADB保留数据安装，应用/控制器指纹核对一致。此片为状态契约验收，不代替音乐全页/弹窗美学与真实播放。
- SDK1.1.0四资产已加载/已安装/APK与Debian一致、emergency=false；四日常模块ACTIVE，另外三STOPPED保留。provider累计6、A4 embedding1035198/短重排299与上限1500000/384账本逐对象不变。正常Main最终恢复留待当前图谱分屏测试结束，避免中途重启/覆盖正在等待的人机测试窗口。工作树全部保留未提交。
- 证据 artifacts/stability-20261008-a8music-state/acceptance-index.json。4a通过，4父项/A8仍开放。接续4b音乐全页、长标题/播放控制/歌单定时弹窗/尾部及读取与命令错误矩阵；终态定时清理的直接DAO失败反馈、删除写互斥待独立修订，不能把本片FC命令结论外推该旁路。图谱真实分屏200%正在补证，TalkBack用户基本操作通过、中文播报支持不佳已单列。


### A8b.3c.2b.3 图谱真实分屏资源字号与 A8b.4b 音乐视觉/终态清理（2026-10-08）

- 图谱真实分屏专项通过：Fold3展开姿态、真实约389.78dp窄窗，100%/200%资源字号×明暗×动态色8组；每组独立启动且核验isInMultiWindowMode、实际资源宽度、屏幕窗口范围。图谱长属性/关系/来源/展开收起/首尾与返回、加载/失败/保留旧行/重试共16个实机测试，均通过。结合前轮真实Main路由、分隔条390→305→390、重建后展开/阅读位置和用户TalkBack基本通过证据，A8b.3及3c相关父项按已有边界关闭。中文TalkBack支持限制继续保留；进程死亡和跨姿态Live动效仍归A9，未外推验收。
- 分屏测试控制器失败留痕：native/window metrics和无障碍窗口一度出现负坐标。最终聚焦触点由顶部中心改为右侧空白边距（避开Samsung分屏手柄），相同200%条件三项通过，再按8组实跑。字号覆盖改为debug Activity.applyOverrideConfiguration，仅覆盖fontScale、不改手机1.3系统字号/密度/锁屏/超时；夹具非导出，screenSize/smallestScreenSize/screenLayout仅debug配置。采用每条件独立启动保留真实系统分屏门，不把全屏360/720夹具等同分屏。所有失败日志保留，不作为验收通过证据。
- 音乐生产UI统一ZeroPageHeader/ZeroCard和配对错误色，清理emoji及过时空态描述，长标题允许换行，播放/扫描/条目等入口保持48dp；歌单和定时对话框草稿rememberSaveable，定时长内容可滚动、模式和歌单选项纵向满宽。全屏父窗口真实展开，360/720内容宽度×字号×明暗×动态色16组；真实分屏8组，长标题/控制回调/创建删除歌单与定时弹窗取消/末行/读取及操作错误/空态均通过。生产MusicLibraryContent＋真实ViewModel使用隔离合成资料与dispatcher；不读取个人音乐正文、不实际起播或调用provider。
- 视觉复核发现200%弹窗系统过渡仍可能在无障碍idle后叠影，因此截图新增有界连续两帧一致检查；大字号分屏4组另跑通过，稳定截图已人工复核浅色大字号定时弹窗和深色首尾。原动画中的截图保留作诊断，不用作最终美学通过证据。最终全屏16组亦按稳定截图重跑；图谱重建2项、读取状态5项复验，实际Room读取仍只读。
- 终态清理的业务red：旧MusicDao.deleteSchedule能删除active任务。修订为Room @Transaction内SQL仅允许fired/missed/skipped_cap/cancelled，返回删除数；UI隐藏其他状态清除入口。MusicViewModel命令Mutex保护本地清理，0行提示状态已变化并重读，异常保留旧数据和可见错误，取消重抛，finally清busy。隔离SQLite2项验证active/firing/unknown保留、终态删除；音乐状态5项验证读错/重试/拒绝/完成后刷新/取消和清理异常。曾有测试在operationError发布后、finally前立即检查busy的时序竞争，改为等待明确终态(error非空且busy=false)，保留原失败日志、不延长2s门槛。
- 验证汇总：真实分屏8组×3组件=24测试；稳定大字号音乐4次单项；最终完整全屏音乐16组＋音乐状态/SQLite7项＋图谱重建/读取7项=15测试通过。qualityCheck/assembleDebug/assembleDebugAndroidTest BUILD SUCCESSFUL in54s，530任务18执行；后续仅控制器截图等待与分组参数修订，AndroidTest编译通过。应用SHA256 f4170460135b722b85c0abdfcd80a81b89a5012c7f5e736fd68cec006c208f54已ADB保留数据覆盖安装且本轮指纹核对一致，正常Main已恢复；控制器与matrix/final两套源快照绑定，证据 artifacts/stability-20261008-a8music-ui/acceptance-index.json、split-matrix-report.json、stable-final-device.log及stable-split/stable-full。
- SDK1.1.0四资产已安装/已加载/APK及Debian一致、emergency=false；日常模块按final-phone.json实测保存。A4账本embedding1035198/短重排299、限额1500000/384逐对象不变；provider累计6不变，本轮测试零新增云端请求。Room schema30、provider/模型配置与缓存稳定前缀不改，现有工作树全部保留未提交。
- A8b.4b限定通过，音乐父项及A8整体继续开放：实际音频起播/暂停恢复/队列、真实歌单写入及定时触发未由本片合成UI代替；新增A8b.4c作为下一片，须使用隔离自有音频/资料验证并清理测试产物。随后继续任务、动态模块、运行日志及A8c全设置/尾页/残留描述与重复入口，A9独立推进。设置统一、全应用美学及全部功能无破坏尚未整体验收。


### A8b.4c.1 定时固定播放失败与取消终态（2026-10-08，限定通过）

- 隔离Room＋生产MusicModule执行器实机red4：空/已删除歌单被误记fired；已接受取消被markFired覆盖、被advanceRecurring复活。debug工厂注入生产依赖但使用隔离Room、关闭启动扫描、不启动周期订阅；没有可播放文件，不调用provider，不执行个人调度。
- 一次性FIXED明确消费FunctionResult，拒绝进入failed并写错误Trace；界面以错误色显示“播放失败 · 查看运行记录”，允许终态清理。周期仍按原相位推进，失败理由进入运行记录，本片不宣称周期界面持久化最后错误已完成。完成/失败/触顶跳过/周期推进仅更新firing，取消只接受active/firing，防止覆盖取消或改写旧终态。Room schema30不变（现有status字符串扩展），dbMutex与条件SQL保持写入约束。
- 同机green11项0.788s全部通过，终态清理包含failed且保留active/firing/未知状态；完整qualityCheck＋应用/控制器构建1m45s、530任务112执行通过，产物保留数据ADB安装且应用/控制器SHA核对一致。证据 artifacts/stability-20261008-a8music-execution/stage1-index.json、red-device.log、green-device.log、quality-build.log。
- 4c.1限定通过，A8b.4c/音乐父项/A8仍开放；继续4c.2真实WAV解码、生产UI owner/工具分发/前台服务、歌单读写及OS固定调度投递与自有资料清理。不将本片隔离调度拒绝测试冒充真实播放。模型配置、稳定前缀与现有工作树保留；运行费用和日常模块状态在本轮最终恢复时复核。


### A8b.4c.2 真实播放、系统定时与音乐页收口（2026-10-08，通过）

- 生产 MusicViewModel → ToolDispatcher → MusicModule → MusicService/真实 WAV 解码链路通过：静音起播、进度、暂停恢复、上一首/下一首、seek、队列及歌单创建/播放/删除。独立 UUID 音频和数据库记录在 finally 中按所有权清理；原音乐资料逐项比较一致，临时音量/配置恢复。静音自动测试不证明人工听感，长期周期稳定性未做浸泡测试。
- 系统一次性 FIXED 定时实际收到 MUSIC_DUE 并完成，重复 tick 不重复播放；周期首轮实际投递、相位准确推进 60 秒后取消并清理。取消后残留 AlarmManager 唤醒有实机 red（cancel-extended-device.log），修复仅在无后继音乐任务时取消音乐 PendingIntent，不影响提醒/自动化。复验确认取消后无音乐唤醒。
- 条件终态更新的调用点审计发现启动恢复也使用 advanceRecurring；隔离实机 restore-red-device.log 复现恢复时尝试过期周期。拆出仅 active/recurring/原 trigger 匹配的 restoreRecurring，恢复跳过旧周期且不播放；执行推进仍限定 firing，取消不复活。Room schema30、写 Mutex/事务与稳定前缀不改。
- 最终五个测试类合计 14 项实机通过（40.244 秒）；失败定时在真实分屏约390dp、200%资源字号下明暗均可读，清理失败提示节点可见且记录保留。两张最终截图人工复核失败标签/长文/清理入口，截图未完整显示清理错误提示，后者依据原生节点断言，证据不混用。
- final-quality-build.log：qualityCheck/assembleDebug/assembleDebugAndroidTest BUILD SUCCESSFUL，1m58s、530任务121执行。最新 APK 已保留数据 ADB 安装且指纹一致：artifacts/stability-20261008-a8music-execution/ZeroV2-a8music-final-debug.apk，SHA256 9d1b4bb389652986445f1ff091547238d99c587e4fae93fae1f708764c433df9。最终14项源码、应用/控制器及旧 red/green 产物分别冻结保留，证据 acceptance-index.json。
- 正常 Main 已恢复；SDK1.1.0、四资产 APK/已安装/已加载及 Debian 一致，emergency=false；qqbot/qzone_post/searx_search/vision_pro ACTIVE，其余三 STOPPED 保留。手机全局字号1.3不变；provider累计6与A4累计1035198 embedding tokens/299短重排、限额1500000/384逐对象不变。工作树保留未提交。
- A8b.4c.2/4c及音乐 A8b.4 按4a状态、4b视觉、4c真实执行证据收口；A8整体未完成。下一项 A8b.5 自动化任务：先修订读取失败/取消/重试与重复加载，再验任务列表/详情/创建全页美学及动作失败，之后动态模块/日志、A8c设置尾页和A9。


### A8b.5a 自动化任务读取状态与重试（2026-10-08，限定通过）

- 生产 AutomationViewModel + 隔离 Room/受控 DAO 实机 red 四项：首次读取错误被冒充成功空库、刷新失败清空旧任务、重叠刷新六次查询、取消同样清空。直接 VM 断言排除页面渲染误报；旧异常→emptyList及无活动Job保护为根因。保留 red应用/控制器/源码/日志。
- 新增 readError、单活动查询 Job；普通异常保留旧任务、页面显示统一错误卡和≥48dp重新读取；取消重抛、finally 清 loading。init负责首次读取，页面首次进入不重复刷新，返回保留的页面仍刷新（创建页继续共享父destination ViewModel）。统一 ZeroPageHeader，加载/失败不显示暂无，移除空态emoji。此片只收敛读取状态，详情/创建和动作反馈待下一片。
- 最终实机五个状态用例＋一个八条件 UI 用例，共6项38.17s通过；真实约390dp分屏、明暗×动态/固定×100%/200%资源字号八组，错误正文、重新读取及隔离旧任务可见，点击目标≥48dp完整位于窗口。八张合成截图，200%明暗人工复核；仅 production AutomationReadStatus组件，不冒充全任务列表/详情/创建页美学验收，不执行个人任务。
- 完整qualityCheck/assembleDebug/assembleDebugAndroidTest 1m28s、530任务35执行通过；仅补UI控制器后的构建19s、259任务6执行通过。最新 APK artifacts/stability-20261008-a8automation-read/ZeroV2-a8automation-read-debug.apk SHA256 d15edf7d403cd86e5b5c13b1fd06d5710086c6365b9de35d5e7018b606a057b6 已保留数据ADB安装，应用/控制器指纹一致，正常Main恢复。音乐4c实机证据继续绑定其冻结APK，不将它说成本片重复执行。
- SDK1.1.0四资产 APK/已安装/已加载及Debian一致、emergency=false；四日常模块ACTIVE，另三STOPPED保留，系统字号1.3不变。无个人数据库写入、零新增provider请求，provider累计6、A4账本1035198/299和上限1500000/384逐对象不变。工作树保留未提交。证据 artifacts/stability-20261008-a8automation-read/acceptance-index.json。
- 5a限定通过，A8b.5及A8整体仍开放。下一片5b：草稿生成/确认创建、运行/调度/管理的失败反馈与busy清理、失败不得清草稿或假返回、详情与创建表单现代化/大字号，随后动态模块、日志、A8c设置尾页与A9。


### A8b.5b.1 自动化动作失败与草稿保护（2026-10-08，限定通过）

- 最小实机 red 四项确认：管理/修改调度忽略拒绝结果；创建拒绝后清草稿且调用返回；取消生成或执行后 busy 不清理。受控 typed dispatcher 与生产 AutomationViewModel/隔离Room，不执行个人任务或provider。red控制器、应用和源码保留，不以构建成功替代这些业务失败。
- 统一消费工具结果；普通异常变成可见错误，取消重抛，finally 清生成/创建/执行状态。创建失败/取消保留用户草稿，成功才清草稿、刷新并返回；确认创建入口同步占位，重复点击只创建一次。管理/调度仅成功后刷新及关闭，失败留在原弹窗，详情展示当前任务状态。typed FC/Hilt/工具权限owner不改。
- 列表、详情、调度编辑与创建页接入共享配对错误色卡，错误持久到明确关闭/下一操作，不再5秒静默消失；成功提示仍短暂显示。创建输入rememberSaveable；调度编辑内容可滚动。草稿生成成功不向用户展示原始JSON。此片不声称所有表单/详情布局现代化已完成，余下横排选项/动作溢出、标题和全文阅读矩阵归5b.2。
- 最终七项动作状态＋五项读取状态＋两项八条件生产组件视觉，共14项实机 72.531 秒通过。包括普通异常、创建取消、成功完成后刷新/仅返回一次及拒绝保留；明暗×动态/固定×100%/200%真实约390dp分屏，读取/动作错误组件各八组，≥48dp重试/关闭可达，16张合成截图与两份报告留存。共享错误组件通过不等同真实详情/编辑/创建页全路径验收。
- 最终final-quality-build.log qualityCheck/assembleDebug/assembleDebugAndroidTest：1m42s、530任务35执行通过（初轮1m37s证据另存，不与最后源码混用）。最新 APK artifacts/stability-20261008-a8automation-command/ZeroV2-a8automation-command-debug.apk SHA256 5ec11ed9dcdec2637199d32eaf615df74804b6b4783718a02635e30eea902570 已保留数据ADB安装，应用/控制器指纹一致，正常Main恢复。证据 acceptance-index.json；音乐14项与上一片读取6项继续绑定各自冻结产物，不冒充本片重跑音乐。
- SDK1.1.0四资产各侧一致、emergency=false，四日常模块ACTIVE、其余三STOPPED保留；系统字号1.3不变。provider累计6、A4 embedding1035198/短重排299、上限1500000/384逐对象不变。本轮无个人资料写入、无新增云请求，现有工作树保留未提交。
- 5b.1限定通过；5b/自动化/A8父项继续开放。下一片5b.2列表/详情/创建整页美学、200%窄窗完整动作、失败状态实际页面和草稿/展开恢复；其后动态模块、运行日志、A8c设置/尾页与A9 Live。不提前关闭A8。


### A8b.5b.2a 任务确认页美学与真实窄窗（2026-10-08，限定通过）

- 真实390dp分屏、200%资源字号最小 red 明确“静态步骤”不可见；旧截图显示第三执行选项横排挤到屏外、确认按钮换行变形。原生资源字号/窗口检查排除假窄窗，原APK、控制器、源码与red日志/截图保留。
- CreateAutomationScreen统一ZeroPageHeader、纵向满宽执行/调度选项与≥48dp目标、统一ZeroCard计划块、纵向满宽确认/重新描述按钮。标题输入满宽，描述草稿rememberSaveable沿用；清理emoji、内部模式编号及Claude Code类比文案，保留三类实际执行/调度能力、开关前置说明，不添加重复设置owner或修改工具schema/稳定前缀。
- 实际生产确认草稿页＋真实AutomationViewModel/隔离DAO/dispatcher，明暗×动态/固定×100%/200%资源字号八组全部通过：六类选择实际回调并核对草稿字段、选项和末尾两操作完整可见且≥48dp，16张自有合成截图，200%明暗首尾人工复核。生成/选择使用受控结果，不调用provider、不写个人任务。此片不声称真实云端创建或需求输入/IME/重建恢复、全部任务详情已验收。
- 最后实机七项动作＋五项读取＋一项八条件确认页，共13项56.583秒通过。qualityCheck/assembleDebug/assembleDebugAndroidTest 1m20s、530任务30执行通过；仅矩阵控制器追加后的构建28s、259任务6执行通过。测试滚动定位要求控件完整可见后才验收，48dp/窗口条件未放宽；不把部分露出的末行当完整操作通过。
- 最新APK artifacts/stability-20261008-a8automation-ui/ZeroV2-a8automation-ui-debug.apk SHA256 b547cee707c112fb74a39e817ccd19b0ef1fbf0305a4329f55f8499dc788d129 已保留数据ADB安装，应用/控制器指纹一致，正常Main已恢复。SDK1.1.0四资产各侧一致、emergency=false；qqbot/qzone_post/searx_search/vision_pro ACTIVE，另外三STOPPED如实保留。系统字号1.3未改，测试结束电池温度29.9℃/电量95%，本片为功能/视觉验收，不是资源性能基准。
- provider累计6、A4账本1035198/299、上限1500000/384逐对象不变；Room30/个人资料、模型配置及全部现有工作树保留，未提交。证据 artifacts/stability-20261008-a8automation-ui/acceptance-index.json，截图范围为真实确认草稿页，旧共享错误卡矩阵仍绑定5a/5b.1冻结产物。
- 2a限定通过，5b.2/自动化及A8整体保持开放。下一片2b：任务列表/长标题/状态、详情执行过程与最终结果、大字号操作区、编辑失败实际页面及需求输入/IME/草稿/展开恢复；随后动态模块、运行日志、A8c设置尾页/残留入口与A9。音乐A8b.4已按4a–4c证据收口。


### A8b.5b.2b.1 任务详情全文与重建恢复（2026-10-08，限定通过）

- 对实际AutomationScreen/隔离任务作红绿验证：旧版Activity重建后详情消失，Missing UI:立即执行；原生执行过程点击区域已达到48dp，未将其误报为已确认缺陷。旧APK/源码/控制器及red-device.log保留。
- 详情/编辑选择改用rememberSaveable任务ID并从当前状态解析；列表保存滚动位置、统一ZeroCard纵向标题/状态、末尾留FAB空间。详情固定短页头，全文在可滚动正文阅读，步骤/最终结果可选中文本、语义背景与文字配对；动作区FlowRow，过程与步骤展开保存。SELF_DRIVE明确显示自驱回合，未安排的非手动任务不再误标手动。编辑草稿保存及纵向选项实现已编译，实际编辑/IME验收继续下一片。
- 真实Fold3约390dp分屏，明暗×动态/固定×100%/200%资源字号八组：隔离长步骤/最终结果均滚动到末尾，五项动作完整位于弹窗且≥48dp，正文配色对比≥4.5，16张仅含本应用合成弹窗截图。200%明暗末尾人工复核。首次矩阵未等待展开重组即查找滚动容器，失败日志与控制器单独留存；修正等待展开状态并使用24行隔离结果后通过，不放宽原窗口/触控门。
- 实际页面两项恢复/点击＋一项八条件详情矩阵＋五项读取＋七项动作，共15项66.12秒实机通过。qualityCheck/assembleDebug/assembleDebugAndroidTest最后全量1m19s、530任务33执行通过；测试脚本修正后控制器构建9s、240任务6执行通过。无个人任务执行或资料写入，无provider请求；Activity重建不冒称强杀/进程死亡验收。
- 最新APK artifacts/stability-20261008-a8automation-detail/ZeroV2-a8automation-detail-debug.apk SHA256 fb1c20ef5848670549f201fdb663e84b5d10b7c513f2b9387bdf67e3689a8a8b已保留数据ADB安装并核对指纹；正常Main恢复。SDK1.1.0四资产各侧一致、emergency=false，qqbot/qzone_post/searx_search/vision_pro ACTIVE，另三STOPPED保留。系统字号1.3未改；provider累计6及A4账本1035198/299（上限1500000/384）逐对象不变。
- 证据 artifacts/stability-20261008-a8automation-detail/acceptance-index.json。2b.1限定通过，2b/5b/任务/A8父项仍开放；接续2b.2实际编辑失败与草稿、列表末尾/返回状态，随后需求输入/IME，动态模块/日志、A8c设置尾页与A9。


### A8b.5b.2b.2a 实际编辑失败与详情返回（2026-10-08，限定通过）

- 实际AutomationScreen隔离任务复现：展开详情→编辑→取消，原局部rememberSaveable因详情退出组合而丢失，red-device.log Missing isolated editor UI:收起。用rememberSaveableStateHolder按任务ID保留详情子树状态，关闭/成功删除释放，编辑取消返回原详情。既有Hilt/工具owner/Room/稳定前缀未改。
- 实际编辑标题→Activity重建→保存被拒，标题草稿保留、错误在当前编辑弹窗可见、无假关闭；保存失败不新增任务查询。重建重新进入页面本有读取，测试以保存前读取次数为基线，未据此删掉正常刷新。首次控制器将含编辑二字的合成标题误作按钮，locator-failure-device.log与控制器保留，后按完整名称优先定位；不把脚本问题误记为生产缺陷。
- 最终同产物17项实机通过：编辑两项＋详情恢复两项＋八条件详情矩阵一项＋读取五项＋动作七项。真实Fold3分屏、明暗/动态/100%-200%详情16张合成截图复验，完整结果末尾与≥48dp动作保持。未执行个人任务、未写个人资料、未调用provider；本片编辑为100%资源字号，不冒称200%编辑/输入法矩阵通过。
- qualityCheck/assembleDebug/assembleDebugAndroidTest最终2m18s、560任务26执行通过（首轮1m41s、560任务37执行；格式归整后重建冻结最终产物）。APK artifacts/stability-20261008-a8automation-edit/ZeroV2-a8automation-edit-debug.apk SHA256 5437377c25cdf47886f5373e7c453d8388e1255cc6bbd5952f0302ca582ae7f5已保留数据ADB安装、指纹一致，正常Main恢复；SDK1.1.0资产/APK/Debian一致，emergency=false，四日常模块ACTIVE，另三STOPPED保留。系统字号1.3未改，全部工作树改动保留未提交。
- provider累计6及A4账本1035198/299（上限1500000/384）逐对象不变。证据 artifacts/stability-20261008-a8automation-edit/acceptance-index.json。2b.2a限定通过；A8b.5b.2b/任务与A8继续开放。下一片2b.2b：长列表末行/FAB与滚动恢复、编辑200%窄窗及实际输入法/需求草稿恢复；之后动态模块/日志、A8c设置尾页与A9按序继续。


### A8b.5b.2b.2b 长列表、编辑与需求输入全页验收（2026-10-08，限定通过）

- 生产页面/真实ViewModel，隔离24个长标题任务，200%资源字号真实分屏：末行完整≥48dp且不与创建FAB相交，详情关闭后Activity重建保留末行滚动位置。实际输入法窗口已显示，生成入口在键盘上方可用；生成被拒保留需求文字，重建后文字仍在，无假草稿或残留busy。
- 200%实际编辑页三类调度选项均≥48dp可用，隔离修改标题及45分钟间隔后保存被拒，错误在ViewModel可见，重建保留标题/间隔输入，保存入口≥48dp，取消回详情。此片不执行个人调度，也不以隔离拒绝结果声称真实云生成或任务执行通过。
- 列表、编辑、需求输入实际生产页：明暗×动态/固定×100%/200%资源字号八组真实约390dp分屏，三类调度选择实际点击、生成入口可用，24张仅含本应用合成资料截图；200%列表/编辑浅色与需求深色人工复核。确认页八组绑定2a、详情全文八组绑定2b.1/2a复验，错误/加载组件矩阵保持各冻结产物边界。
- 本片4项（含一项八组全页矩阵）80.909秒实机通过；首轮功能3项20.808秒及独立长列表/IME2项13.806秒留存，不累加冒称独立更多用例。最终控制器/应用构建9s、378任务6执行通过，生产APK未改；完整质量门继续绑定上一片final-quality-build.log（2m18s、560任务26执行），同应用此前17项实机回归与本片4项分两次控制器运行，共21项不同用例，不冒称本片重跑完整质量门。
- APK artifacts/stability-20261008-a8automation-input/ZeroV2-a8automation-input-debug.apk SHA256 5437377c25cdf47886f5373e7c453d8388e1255cc6bbd5952f0302ca582ae7f5与2b.2a相同，已安装指纹核对；正常Main恢复。SDK1.1.0四资产/APK/Debian一致，emergency=false，四日常模块ACTIVE，另三STOPPED保留；系统字号1.3未改。provider累计6及A4账本1035198/299（上限1500000/384）逐对象不变，工作树366项既有改动全部保留未提交。
- 证据 artifacts/stability-20261008-a8automation-input/acceptance-index.json。2b.2b及2b/5b.2/5b/自动化A8b.5按读取状态、命令反馈与草稿保护、全页美学/真实窄窗/重建证据限定收口；真实云生成、真实任务端到端与长周期自驱仍归A10，进程死亡/跨姿态连续动效归A9。A8整体未验收，下一项A8b.6动态模块列表/详情/状态/部署版本卡与错误反馈，随后A8b.7运行日志、A8c设置尾页/残留入口；不以本片关掉剩余门。


#### 下一片 A8b.6a 开工源码核对（待修订、未验收）

- DynamicModulesViewModel.kt:71 的模块Flow无加载/失败投影；84–99 busy在协程内占位，需验证同帧双击及启停失败反馈；105–111详情缺失直接返回且配置读取吞普通Exception（含取消），153–155保存后重启未消费启停布尔结果。先以隔离依赖回归确认，再修订单owner状态/取消/失败，不用页面美化掩盖运行错误。
- DynamicModulesScreen.kt:160–164把所有非ACTIVE/CRASHED状态显示为DISABLED；STOPPED与未知值需明确投影。列表/详情仍有旧TopAppBar、横排状态和内部编号文案，源码弹窗与字段草稿用remember，重建恢复未验。上述为源码核对发现，未声称完成实机复现或修复；下一片按状态/命令反馈→全页美学与部署版本卡→实机恢复推进。


### A8b.6a.1 动态模块读取、配置与失败反馈（2026-10-08，限定通过）

- 隔离生产ViewModel/内存Room/真实ModuleConfigStore与受控生命周期回归red5中4失败：停止拒绝无反馈、保存后停止拒绝仍启动、损坏存储配置被当默认值、缺失详情无终态；同帧启停用例原已通过，不误记成已确认双击缺陷。测试接缝只隔离外部操作，MCP保留运行/工具注册owner；Hilt生产组装经ModuleLifecycleManager.safeCall调用，源码/原APK/控制器/red证据留存。
- 单owner显式loading/readError/detailLoading/detailError/actionError/成功提示；列表观察失败保留旧数据可重试，取消重抛、busy清理，详情代次隔离迟到结果，缺失模块结束加载。普通异常/拒绝持久可见，不再假空态或默认配置；Trace只记录阶段/异常类别，不导出配置内容。启停与保存同模块占位互斥；无新增Room表或调用前缀。
- 保存严格验证有限数值/已有边界/布尔输入，写入前解析原配置，保留当前表单外字段。仅确认停止后继续启动，分别提示已存储但未生效、启动失败、取消生效；不把持久化成功冒称运行已生效。保存失败保留当前编辑态，详情未读成功时保存不可用；全页样式和字段重建恢复待6b。
- 最终12项状态/取消/恢复/配置保护＋一项八条件错误组件，共13项42.326秒实机通过；真实Fold3约390dp分屏、明暗×动态/固定×100%-200%资源字号，16张自有合成截图，错误正文对比≥4.5，重新读取/关闭提示≥48dp且实际回调，200%明暗人工复核。此片为共享错误组件，不冒称完整模块列表/详情、真实日常模块启停或配置修改验收。
- qualityCheck/assembleDebug/assembleDebugAndroidTest 1m36s、560任务35执行通过。APK artifacts/stability-20261008-a8dynamic-state/ZeroV2-a8dynamic-state-debug.apk SHA256 4443275968e10a4dd22caa3a8bd687336e387cd28cf9ca5591b95906e85fcc3d已保留数据ADB安装并核对指纹，正常Main恢复；SDK1.1.0四资产/APK/Debian一致、emergency=false，qqbot/qzone_post/searx_search/vision_pro ACTIVE，另三STOPPED保留。系统字号1.3未改，工作树366项保留未提交；provider累计6、A4账本1035198/299及上限1500000/384逐对象不变，无个人资料/任务写入，无云请求。
- 证据 artifacts/stability-20261008-a8dynamic-state/acceptance-index.json。继续核对发现McpClientModule.unmountDynamicModule忽略supervisor响应并无条件注销工具/写DISABLED；本片受控false回归不能替代底层真实确认，故6a父项仍开放。下一片6a.2先补停止确认门，之后6b列表/详情/源码/配置/版本卡全页与恢复，6/模块和A8整体均未验收，随后日志与设置残留按序推进。


### A8b.6a.2 supervisor 停止确认与本地状态保护（2026-10-08，限定通过）

- 根因：McpClientModule.unmountDynamicModule忽略supPost的返回值，无论HTTP失败/null、明确ok=false或缺失确认字段，均注销FC并写DISABLED，导致界面与仍在运行的模块不一致。核对本地zero_release/zero_supervisor：成功停止返回布尔ok=true；未修改SDK、模块代码或日常配置。
- 提取实际停止提交边界后，行为保持的red四项中三失败，明确成功路径通过；修订为仅布尔ok=true允许原有注销/状态写入/成功Trace，缺失、字符串true、拒绝或断连均false并记录停止未确认，保留原工具注册和运行状态。取消确认前重抛，禁止本地提交；不冒称远程与本地跨进程原子事务，后续心跳仍负责实际状态收敛。
- 五项协议回归全绿；当前APK保留数据安装后12项真实ViewModel/内存Room/ModuleConfigStore状态、取消、配置保护实机通过1.210秒。真实supervisor负响应未现场注入，避免中断日常模块；本片以实际生产提交边界回归证明负分支，不声称实机断网验收。
- qualityCheck＋assembleDebug＋assembleDebugAndroidTest全量通过1m53s，560任务83执行；首次质量门为新增测试通配导入规范失败，已改显式导入并重跑全门，不改baseline或抑制规则。共享错误组件/列表/详情源指纹与6a.1一致，沿用此前八条件16张视觉证据；手机再次锁屏，当前APK未重跑视觉，不重复计数。
- APK artifacts/stability-20261008-a8dynamic-stop/ZeroV2-a8dynamic-stop-debug.apk SHA256 d7d2a2265899400c8ce4fe194b6c3f102d13fc7993e1025d9671cf1a13207eed 已ADB安装并逐字核对已安装指纹；正常Main恢复，SDK1.1.0四资产/APK/Debian一致、emergency=false，qqbot/qzone_post/searx_search/vision_pro ACTIVE，bili_downloader/image_gen/web_fetch STOPPED保留。字号1.3未改；provider累计6与A4累计1035198/299、上限1500000/384账本逐对象不变，无新增云请求或个人数据更改。
- 证据 artifacts/stability-20261008-a8dynamic-stop/acceptance-index.json，red/green XML、构建与实机日志、源码快照均留存。6a.1/6a.2在读取与失败反馈限定范围收敛；A8b.6b仍需模块全页现代化、长ID/200%布局、源码与未保存配置恢复及部署卡验收，模块父项与A8整体未通过；日志和设置残留继续按序。


### A8b.6b.1 动态模块列表与源码阅读统一（2026-10-08，限定通过）

- 生产列表统一ZeroPageHeader/ZeroCard与主题颜色，完整长模块名独立排版，状态与开关留出空间，详情/源码操作纵排且触控至少48dp。删除列表内部阶段编号、工具前缀与旧端点指导；端点仍留在详情诊断，后台运行及权限owner不变。源码标题统一为“模块源码”，模块标识移到阅读正文，正文明确onSurface配色。
- StateBadge明确投影ACTIVE/CRASHED/STOPPED/DISABLED/STARTING/STOPPING；未知值显示原值与未识别提示，不再伪装DISABLED。徽标使用对应container/content语义色；当前窄屏200%未知长状态可以换行。此共享徽标同时影响详情，但完整详情布局不借此验收。
- 列表显式rememberLazyListState；源码选中ID用rememberSaveable并从最新列表定位，不保留过时实体快照；每模块源码滚动使用保存状态容器，正常关闭清除该阅读状态。测试只替换列表数据/命令回调，真实页面投影与恢复代码共用，不另写测试专用界面。
- 真实Fold3约390dp分屏、明暗×动态/固定×100%-200%真实资源字号八条件全通过：16条长ID列表末项详情、启停、源码入口均可点击且在窗口内≥48dp，回调准确指向末项；32行长源码滚动到末尾，Activity重建保留当前模块与末尾位置，关闭后列表末项仍可见且无需再次滚动。状态文字语义色对比≥4.5；32张仅自有合成窗口截图保留，200%明暗人工复核通过。八条件作为一项原生测试，连同12项状态/取消/配置保护，本APK13项78.357秒实机全绿，不计作八项独立测试。
- 首轮测试按通用按钮文字误选同屏另一卡，已补模块名无障碍描述并精确定位；次轮误要求恢复到末尾后仍看见已滚出视口的模块标识，改核对选中模块专属源码和实际滚动终点。两次测试定位失败留痕，不冒称生产崩溃根因或忽略失败。最终qualityCheck/assembleDebug/assembleDebugAndroidTest全量通过32s、560任务16执行（生产改动前一轮全门1m4s通过，末轮仅修测试断言，生产APK一致）；停止确认/状态owner源指纹与6a.2相同，沿用五项协议证据。
- APK artifacts/stability-20261008-a8dynamic-list/ZeroV2-a8dynamic-list-debug.apk SHA256 7178d23d42da75d97cca3b6dfaa9920d92085c8577145ba765a951f3b31c4c62 已保留数据ADB安装并逐字核对安装指纹，控制器同时核对；正常Main恢复，SDK1.1.0四资产/APK/Debian一致、emergency=false，四日常模块ACTIVE、另三STOPPED保留。字号1.3未改，provider累计6与A4累计1035198/299及上限1500000/384账本逐对象不变，无云请求、日常模块启停、个人资料或配置写入；工作树原有改动保留未提交。
- 证据 artifacts/stability-20261008-a8dynamic-list/acceptance-index.json，原屏幕源码、最终源码快照、构建/两次定位失败/最终实机日志、32张正式截图与报告已留存。6b.1仅列表/源码阅读限定通过；6b.2详情与未保存配置/实际输入法/重建恢复、6b.3部署版本卡仍开放，A8b.6模块父项及A8整体未通过。随后继续6b.2→6b.3→A8b.7日志→A8c设置尾页/重复入口/残留描述；进程死亡与跨姿态动效归A9。


### A8b.6b.2 模块详情、受控配置与草稿恢复（2026-10-08，限定通过）

- 在原生产渲染逻辑提取只读部署观察插槽后，两项真实ViewModel/内存Room/ModuleConfigStore实机red均失败：Activity重建后未保存文本回到默认值；重新读取新存储值后覆盖未保存草稿。原APK、原屏幕、行为保持接缝、red控制器和日志留存；不是仅根据remember关键字推测。
- 单一表单编辑态按moduleId rememberSaveable，文本/开关/数值不再各自保存临时值；界面显示和提交取同一份值。重新读取保留与存储不同的编辑值；存储回报相同值时消除对应覆盖项，避免再次读取仍被已保存旧值遮蔽。保存/读取/错误未确认阶段禁用提交，原有VM/生命周期/事务/前缀不变，无新Room表或实际模块发布动作。
- 模块详情统一ZeroPageHeader/ZeroCard/语义颜色；长ID与徽标纵排；运行端点、依赖和时间折叠为诊断记录，不显示成实时事实。源码与运行信息折叠状态及外层阅读位置可重建恢复；源码由外层单滚动阅读，明确onSurface颜色。配置文案去除内部manifest/env/阶段号，准确说明重启或下次启用生效。有界数值保留滑块并提供精确输入，原存储值不因显示预览自动改写，最终有限数值/边界检查仍由VM负责。
- 原两项red→green2项9.250s；最终本APK17项108.162s实机通过：三项草稿/重新读取/恢复后的真实存储与停止拒绝反馈＋两项详情视觉/实际键盘＋12项状态、取消和配置保护。真实Fold3约390dp分屏、明暗×动态/固定×100%-200%资源字号八条件作为一项测试：文本、开关、无界负小数、滑块/精确数值7.25准确提交并重建恢复，诊断/源码折叠与源码末尾位置保留，保存入口≥48dp且在窗口内，正文/错误语义对比≥4.5。32张矩阵截图＋1张真实键盘视口共33张，仅自有合成窗口；200%明暗、数值控件、源码末尾及IME视口人工复核。
- 实际软键盘出现后，描述节点变为不可编辑View，原无障碍文字注入及递归定位失败留痕；未据此认定产品输入失效。最终先断言Zero测试窗口有焦点，再以实际按键选择/输入，并核验提交值“isolated keyboard draft”和保存矩形不与实时键盘重叠；单项7.479s通过、最终17项再次通过。滑块一次读到上一帧3，改为有界等待重绘再断言7.25。首次新增测试常量编译失败已改Android AccessibilityAction正确调用，诊断探针已从源码移除，未改baseline/抑制或放宽断言。
- qualityCheck/assembleDebug/assembleDebugAndroidTest最终41s、560任务10执行通过。APK artifacts/stability-20261008-a8dynamic-detail/ZeroV2-a8dynamic-detail-debug.apk SHA256 84bb8d38e62ce23175f0b7b2c016b87a015ffae1ed0e74f25500248d77c9bc83 已保留数据ADB安装并逐字核对安装指纹；控制器同核对，正常Main恢复。SDK1.1.0四资产/APK/Debian一致、emergency=false，qqbot/qzone_post/searx_search/vision_pro ACTIVE，另三STOPPED保留；系统字号1.3未改，工作树既有改动保留未提交。provider累计6、A4累计1035198/299及上限1500000/384账本逐对象不变，本轮验收无新增云端测试、日常模块启停或个人配置写入。
- 证据 artifacts/stability-20261008-a8dynamic-detail/acceptance-index.json；6b.2仅详情/受控表单/Activity重建/实际IME限定通过。部署版本观察插槽在详情矩阵中隔离，不能据此关闭6b.3；模块父项/A8整体未通过。下一项6b.3部署卡真状态/陈旧未知/历史/窄屏，再A8b.7运行日志、A8c设置尾页与重复入口；进程死亡及跨姿态动效仍归A9，真实日常配置变更和远程停止负响应未现场注入。


### A8b.6b.3 部署版本卡、历史可读性与真实接线（2026-10-08，限定通过）

- 实机 red 确认：真实窄分屏、200%资源字号下，长未知历史状态横向挤掉版本摘要；保留未知原文的断言仍通过，摘要读取失败。改为纵向版本身份、状态、时间布局，统一 ZeroCard 与成对语义颜色，未知状态明确显示原值。
- 陈旧/未知观测明确显示“上次观测版本/状态”，不冒充当前运行状态；空数据、加载、读取错误与重试均有独立反馈。读取仓库、ViewModel、发布与部署后端行为保持原有实现。
- 全量 qualityCheck + assembleDebug/assembleDebugAndroidTest：BUILD SUCCESSFUL，37s，560 tasks。3项正式实机测试通过（84.32s）：原缺陷回归、八种真实分屏资源条件×六类状态、实际 SystemStateRepository→生产 ViewModel→公开版本卡接线；56张矩阵截图与1张真实接线截图，48次重试回调、48dp点击目标及配色对比度检查通过。
- 测试控制器安装尚未完成时曾误启动测试，两个新类未加载；保留 controller-install-race.log，等待安装完成后正式三项全绿，此初始化失败不认定为产品回归。原 red/green、构建、最终日志与源码快照均保留。
- APK artifacts/stability-20261008-a8deployment-ui/ZeroV2-a8deployment-ui-debug.apk（SHA256 2396c18b4999474b532302dedbb3ee4ad1db41a7c7dc9ca0018988d777ca51ef）已通过ADB覆盖安装并校验手机包摘要，恢复正常 MainActivity。SDK1.1.0、APK/Termux/Debian资产一致；qqbot/qzone_post/searx_search/vision_pro ACTIVE，另三模块STOPPED；emergency=false，系统字号1.3未改。
- provider账本与A4账本完整对象保持不变；未新增云请求、发布操作或日常配置写入。证据 artifacts/stability-20261008-a8deployment-ui/acceptance-index.json。6b.3按卡片读取/状态/历史/显示接线限定通过，A8整体仍开放；下一项 A8b.7运行日志读取异常、并发刷新、筛选/全文及美学，再A8c设置尾页/残留入口。真实远程负响应、日常配置变更、发布端到端及长周期自驱归A10，进程死亡/跨姿态动效归A9。

- A8b.6收口范围核对：6a.1/6a.2读取、配置、停止确认与错误反馈，6b.1列表/源码，6b.2详情/草稿/IME，6b.3部署卡均已有对应证据；模块UI父项6b及A8b.6在这些限定范围收口。A9/A10另列的进程死亡、跨姿态、真实日常变更/发布与长周期自驱未由本父项代验；A8整体仍开放。


### A8b.7a 运行日志历史保留、取消与刷新次序（2026-10-08，限定通过）

- 生产 RunLogViewModel + 隔离内存Room三项实机red全部失败：读取异常清空已知历史、取消被吞成成功空历史、旧请求晚返回覆盖新请求。原源码、red控制器和失败日志保留，未触碰个人日志数据库。
- reload只保留当前请求所有权：取消旧Job并记录刷新代次，提交前ensureActive和代次检查；CancellationException继续传播。普通历史读取失败保留上次已知历史并标记historyError，当前内存同ID优先逻辑保持；加载状态独立，结束/取消不留下永久加载。页面补充读取失败、重新读取和首次加载反馈，不把错误呈现为“暂无运行记录”。
- 最终 qualityCheck + assembleDebug/assembleDebugAndroidTest BUILD SUCCESSFUL（55s，530 tasks）；3项正式实机回归全绿（0.788s）。此片验证的是生产状态所有权/Room读取边界，尚未验证日志全页美学、搜索/全文/复制、页面重建和错误UI矩阵，不以状态测试代替界面验收。
- 最终APK artifacts/stability-20261008-a8log-state/ZeroV2-a8log-state-debug.apk SHA256 40aaf28276d02a7b1a3ff9c35a417807785878366fd47b68b875432e28b9f9fa 已ADB覆盖安装并核验手机包摘要，正常Main恢复。四个日常模块ACTIVE，另三STOPPED；SDK1.1.0和资产匹配，emergency=false，系统字号1.3不变；云调用账本及A4预算完整对象未变化。
- 证据 artifacts/stability-20261008-a8log-state/acceptance-index.json；本片限定通过，A8b.7父项/A8整体仍开放。下一片A8b.7b：运行日志统一Zero页头/卡片，长ID与200%真实窄窗阅读、完整detail展示/筛选/复制、查询与展开状态恢复、错误/空/加载UI矩阵；随后A8c设置尾页、重复入口和过时描述。进程死亡/跨姿态动效归A9，长周期自驱归A10。


### A8c 设置与尾页验收顺序（2026-10-08，执行计划，未验收）

按既有A8c范围拆分，每片实现、质量/编译、实机证据、记录后进入下一片：

1. A8c.1 设置根入口/尾部说明、外观：九个入口唯一归属、系统动作、首尾与200%字号、主题控制及配置保留。
2. A8c.2 角色与表达：列表/详情/编辑、状态和关闭/重置、草稿/展开与错误反馈。
3. A8c.3 自主行为：在场退避、离场波动、记忆和预算完整范围、保存反馈；不改自驱门槛来通过UI验收。
4. A8c.4 事件/系统权限/首次引导：权限动作与缺失状态、云通道配置唯一owner、旧说明逐条清理。
5. A8c.5 能力中心/模块详情/模型profile：读取与编辑边界、模型能力和专属参数、错误/返回、单一配置owner。
6. A8c.6 外部工具连接：列表/增删/重连/失败反馈及表单草稿，隔离服务与配置测试。
7. A8c.7 备份恢复：实际进度/取消/错误与数据保护，已有仓库/事务证据仅按范围沿用。
8. A8c.8 全局残留清理核对：重复入口/过时项、首尾/对话框/Sheet和路由，逐页证据表与未关闭项，不以共享组件通过替代每页验收。

沿用八种真实分屏资源条件及正常安装指纹/模块恢复/预算检查；仅在真实链路验证必要时使用实机，日常凭据与个人资料不因视觉验收改写。Live动效与进程死亡/跨姿态连续体验仍归A9，真实长周期自驱/发布归A10。当前A8c及A8均未通过。


### A8b.7b 运行日志全文与统一页面体验（2026-10-08，限定通过）

- 行为保持提取RunLogContent后，实机red按detail内容检索，仍无法阅读匹配全文；原APK/页面、red控制器与日志留存。根因是TraceLine仅渲染summary，且旧展开额外依赖summary聚合存在。
- 统一ZeroPageHeader/ZeroCard和正文语义色；移除11sp固定正文与表情图标。长记录ID、统计、展开/复制纵向排版；每个展开事件成为独立Lazy项，完整工具名/调用ID/summary/detail可阅读选择。可选统计缺失不阻止详情。现有搜索范围与全部/单记录复制文本保持，未新增或改写日志数据库。
- 搜索、选中展开记录rememberSaveable，显式LazyListState恢复阅读位置；加载、真正空、无匹配、首次错误及保留旧记录的错误均区分显示。重试与复制入口至少48dp，完整内容不用截断预览替代。
- 全量qualityCheck + assembleDebug/assembleDebugAndroidTest通过（1m34s，530 tasks）；最终5项实机144.221s全绿：全文原缺陷、八条件真实分屏/100%-200%资源字号/明暗/动态固定矩阵、三项生产VM+隔离Room状态回归。八条件均验证无可选summary时详情/完整复制、16条长ID末项操作、展开/阅读位置/查询Activity重建恢复和错误/空/加载；72张仅自有合成窗口截图，200%浅深人工复核，正文/错误对比≥4.5。
- APK artifacts/stability-20261008-a8log-ui/ZeroV2-a8log-ui-debug.apk SHA256 19beb48004029add4db85ec30e34dc54c3d1de11ecb7aa4064758cd0be1856dd 已ADB覆盖安装并核验指纹，恢复正常Main。四日常模块ACTIVE、另三STOPPED，SDK1.1.0四资产/APK/Debian匹配，emergency=false；系统字号1.3未改。provider与A4预算账本完整对象不变，个人资料和配置未修改。复制目的地受控，不宣称本片已验证真实剪贴板/系统分享。
- 证据 artifacts/stability-20261008-a8log-ui/acceptance-index.json。结合7a读取/取消/刷新证据，A8b.7按日志读取与页面范围收口；A8整体仍开放，进入A8c设置/尾页/残留。进程死亡/跨姿态连续动效归A9，真实日常任务与长周期自驱归A10。


### A8c.1 设置根页、尾部与外观（2026-10-08，限定通过）

- 根页沿用已落地的共享ZeroPageHeader/分组容器/导航行，提取生产SettingsContent以隔离系统动作回调；显式列表状态便于恢复验证，九入口及系统助手现有动作保持。外观提取同一生产投影，实际AppearanceStore/ViewModel/原配置key未改，删除未使用旧图标导入；不把行为保持接缝称为新的功能修复。
- 源码核对八个内部入口均在ZeroNavHost有目标，系统语音助手为系统动作。正式实机一项含八条件真实分屏/100%-200%资源字号/明暗/动态固定矩阵（97.416s）：72次入口点击分别正确指向八路由与一系统动作回调；≥48dp、根页实际末端与重建后终点、外观三种模式和动态色、实际隔离Store持久化、新Store回读、页面重建和返回均通过。48张正式自有窗口截图，200%明暗与完整尾说明人工复核。系统设置真正启动未在本矩阵重复执行，不能将回调验收当成系统角色授权完成。
- 首轮八条件99.35s通过，但截图只证明尾说明进入视口；因此未收口，保留initial-device/initial-index/initial-screens，将断言收紧为真实列表滚动终点及重建后仍在终点，再正式八条件通过。无忽略失败或修改手机字号；原生产布局无需通过减少内容来满足测试。
- 全量qualityCheck/assembleDebug/assembleDebugAndroidTest首轮1m41s、最终43s，530 tasks均通过；最终仅测试收紧，生产APK摘要一致。APK artifacts/stability-20261008-a8settings-root/ZeroV2-a8settings-root-debug.apk SHA256 2042aa65685e8a1a9769c59fc16314a5112ca2c2c67050dd6d5278c822fc578a 已ADB覆盖安装并核验手机包摘要、控制器同核对，正常Main恢复。
- 手机原zero_appearance.xml完整文件SHA256前后相同（d9011c3bbf09abd65a60c3022c188d3764574ac49e65ad6fd566a8691120407b）；隔离偏好已清理。系统字号1.3不变，四日常模块ACTIVE、另三STOPPED；SDK1.1.0/四资产/APK/Debian匹配，emergency=false。provider与A4账本完整对象不变，无新增云测试或个人配置/资料改写。
- 证据 artifacts/stability-20261008-a8settings-root/acceptance-index.json。A8c.1限定通过，A8c/A8父项继续开放。下一项A8c.2a角色写入与动作owner：已定位CharacterManageViewModel两次非事务激活写入、动作失败反馈缺失，以及ChatViewModel.ensureActiveCharacter同类生产调用；为源码候选，尚未实机复现，见next-character-audit.md。之后2b角色全页/编辑草稿/动态状态/首尾矩阵，再按A8c.3–8既定顺序推进，不以根页通过关闭子页。


### A8c.2a 角色选择事务与动作状态（2026-10-08，限定通过）

- 行为保持提取角色状态模块访问接缝后，生产ViewModel+隔离Room四项实机red全部失败：非法目标清空当前角色；SQLite触发器拒绝激活后当前角色为null；同帧五次创建进入五次写入；编辑失败缺少可见错误。预期注入异常仅由测试捕获，原页面/DAO/Chat源码、red APK/控制器和失败日志保留，个人角色未用于复现。
- 新增Hilt单例CharacterRepository，以Mutex串行角色写入；CharacterDao新增事务方法，校验目标后原角色停用/新角色激活同事务提交，默认补选在事务内再次核对现有活跃角色。设置VM与ChatViewModel.ensureActiveCharacter共用生产写入owner；保留无活跃角色时取首个角色的兼容规则。新增方法不改变表结构，Room仍30。
- 保存/删除/预设初始化走同一仓库，编辑对象已不存在不伪报成功，删除按存储身份保护预设，批量预设初始化同事务/空库二次核对。UI动作在启动协程前预留busy，失败保留编辑状态/原值，取消继续传播且清忙、不报成功；读取错误保留列表、可重读，迟到快照按角色与取消边界校验。模块调用经过ModuleLifecycleManager.safeCall，表达状态仍归原模块；未声称实际模块持久化失败/重置端到端已验收。
- 页面接上Snackbar反馈，当前仅编译/生产状态验证；完整错误UI、编辑草稿与八条件显示仍归2b，不把StateFlow测试当成界面显示完成。核心配置和提示词内容未重新设计或改写。
- 最终qualityCheck/assembleDebug/assembleDebugAndroidTest BUILD SUCCESSFUL（46s，530 tasks；生产修订首轮2m16s全门通过）。11项正式实机3.635s全绿：10项角色owner/真实SQLite/取消/预设保护/删除后编辑/读取重试/共享仓库竞争＋1项现有生产ChatViewModel对话切换与局部回复回归。编辑失败green改用真实SQLite触发器检验事务方法，不以委托DAO旧update覆盖漏拦新事务方法。
- APK artifacts/stability-20261008-a8character-state/ZeroV2-a8character-state-debug.apk SHA256 76c2f75e5f4cae541ddb9afc3e5d591516e0c7321416c71cb716b618c6216fab 已ADB覆盖安装并校验手机包摘要，控制器同核对、正常Main恢复。四日常模块ACTIVE/另三STOPPED，SDK1.1.0/资产/APK/Debian匹配，emergency=false，字号1.3不变；provider和A4预算账本完整对象不变，无新增真实provider请求。
- 证据 artifacts/stability-20261008-a8character-state/acceptance-index.json。2a按角色写入/动作状态限定通过，角色父项/A8c/A8均未关闭。下一片2b.1：生产角色页统一视觉/长角色名与200%操作/编辑草稿重建，随后2b.2实际模块状态读写/重置/失败绑定，再A8c.3自主行为；真实个人角色变更/进程死亡/跨姿态连续动效不以本片代验。


### A8c.2b.1a 角色编辑草稿与提交保护（2026-10-08，限定通过）

- 生产页面行为保持提取 CharacterManageContent 与动作回调接缝后，真实分屏/200%资源字号复现未保存名称在 Activity 重建后丢失（red 1项失败，9.778s）。根因：三个编辑字段仅 remember，重建重新读取初始值。原页面、red APK/控制器及日志均保留。
- 三字段改为按角色ID rememberSaveable；编辑区可滚动，描述允许多行；保存失败在编辑框内持续显示，busy禁用字段/保存/取消并阻止外部关闭，确认/取消至少48dp。保留角色内容、生产写入owner、Room30和预设保护；未对个人角色或提示词内容作改写。
- 原缺陷初次green 1项7.371s通过；新增矩阵两次因无障碍定位失败未通过，记录 initial-matrix-device/second-matrix-device/fixture-node-diagnostic：草稿标识是 EditText 的子节点，测试应沿父节点找到编辑动作owner，不应要求标识节点本身 isEditable。保留失败后按实际节点层级修正，未删断言或放宽产品门槛。
- 最终 qualityCheck/:app:assembleDebug/:app:assembleDebugAndroidTest BUILD SUCCESSFUL（47s，530 tasks）；13项正式实机73.221s全绿：2项编辑草稿UI（其中一项含八条件真实分屏、100%/200%资源字号、明暗、动态/固定色）＋10项生产角色VM/真实隔离Room＋1项生产ChatVM对话切换。矩阵逐字段验证名称、多行描述与长角色说明提交原文、失败保存后的三个草稿与实际Activity重建恢复、busy禁用/等待文案和取消回调。失败为受控UI投影回调，不能代替实际模块持久化失败验收；本片也不证明列表美学、全部触控目标或进程死亡恢复。
- 最终APK artifacts/stability-20261008-a8character-ui/ZeroV2-a8character-draft-debug.apk SHA256 99e2b56cee8073eeffb79db4c225fe645a35e7083740fa06d4f9e479890fb625 已ADB安装并核验手机包摘要，控制器同核对，正常Main恢复。四日常模块ACTIVE、另三STOPPED，SDK1.1.0/四资产/APK/Debian匹配、emergency=false；系统字号仍1.3。provider与A4预算账本完整对象与2a基线相同，无新增云测试请求。
- 证据 artifacts/stability-20261008-a8character-ui/acceptance-index.json、draft-matrix-report.json。仅2b.1a编辑草稿子项通过；2b.1角色全页、2b.2实际模块、角色父项/A8c/A8仍开放。后续先统一角色列表/动态状态的页头、卡片、长文纵向操作、空/读错/busy状态、折叠保存及八条件首尾视觉矩阵，再补实际模块状态持久化/重置；详见 remaining-audit.md。A8c.3-8原顺序保持，A9/A10边界不变。


### A8c.2b.1 角色列表、动态表达与编辑统一体验（2026-10-09，限定通过）

- 沿已通过的2b.1a生产编辑草稿接缝，角色页统一 ZeroPageHeader/ZeroCard、中性色正文与间距。移除横向头像/多徽标/操作拥挤及描述两行截断，长名称、完整描述、角色类型/当前标记纵向显示，使用/编辑/删除独立≥48dp按钮，预设不提供删除。新建进入列表首部，取消覆盖末项的悬浮入口。
- 加载、真正空、读失败及保留数据时错误分开显示，错误有明确读取重试；busy阻止新建/角色操作/表达开关/重置。状态未加载、失败或无角色不开放重置。近期变化依据默认折叠，按角色保存展开状态，事件类型/完整原因/时间纵向显示。动态表达说明改为“让表达随互动自然变化，关闭后保持固定风格。”，描述开关效果，不在产品UI重复内部安全术语；后台事实/权限/安全约束未改写。
- 首轮14项实机187.901s通过，但末项进入视口不能证明真实终点；因此收紧到实际滚动终点并在Activity重建后无需滚动仍停在终点，独立八条件145.376s复验通过。200%浅深色关键截图人工核对后清理上述旧说明，再完成最终构建和全部14项实机复验（221.135s）。这些测试包括1项八条件列表矩阵、2项编辑草稿、10项真实生产VM/隔离Room及1项ChatVM对话切换；不能将每个矩阵条件另计JUnit测试。
- 最终 qualityCheck/:app:assembleDebug/:app:assembleDebugAndroidTest BUILD SUCCESSFUL（34s，530 tasks）。八条件全部是真实分屏、100%/200%资源字号、明暗、动态/固定色；验证返回/新建/开关/重置/末角色使用/编辑/删除回调、完整描述末尾、控件≥48dp且完全位于自有窗口、依据展开恢复、实际尾部与阅读位置恢复、busy禁用、状态失败阻止重置、空/加载/读错区分及重试。80张仅自有合成页面截图归档，200%关键浅深色人工复核，正文/次正文/错误所用配色对比≥4.5。实际模块写入/重置不由受控回调代验，仍归2b.2。
- 首轮构建因测试使用 ACTION_SHOW_ON_SCREEN 的错误常量位置失败，已按 AccessibilityAction 定义修正；initial-build及全部首轮/收紧/正式报告保留，无忽略失败。角色仓库/事务DAO/生产VM沿2a实现未改，Room仍30；个人角色、提示词内容、对话和模块配置未用于测试或改写。
- APK artifacts/stability-20261008-a8character-list/ZeroV2-a8character-list-debug.apk SHA256 daeae8add2d30adcb21b5fefd8dd29b4aae39830ce74a5623226be6b3ec19d76 已ADB安装并核对手机包摘要，控制器同核对，正常Main恢复；四日常模块ACTIVE/另三STOPPED、SDK1.1.0四资产/APK/Debian匹配、emergency=false、系统字号仍1.3。provider与A4账本完整对象与2b.1a基线相同，无新增真实云测试请求。
- 证据 artifacts/stability-20261008-a8character-list/acceptance-index.json、list-matrix-report.json、draft-matrix-report.json及screens。2b.1按角色列表/动态状态投影/编辑表单范围收口；2b/角色父项、A8c/A8仍开放。下一片2b.2：真实CharacterStateModule读写/重置/持久化失败与取消；snapshot捕获取消及配置先发布、saveNow失败保留/重试须结合实际owner隔离复验，不单独回滚内存导致延迟写与内存分叉。随后按A8c.3–8原顺序；进程死亡/跨姿态连续动效归A9、真实日常长周期自驱与发布归A10。


### A8c.2b.2 实际角色状态模块与持久化一致性（2026-10-09，限定通过）

- 接真实 CharacterStateModule、ModuleLifecycleManager.safeCall、隔离Room与真实配置重试worker，3项实机red（1.226s）均失败：取消快照被吞成null；SQLite拒写时模块开关已改变而存储未变；解除拒写后已报失败的false候选被后台重试悄悄写入。原模块/存储源码、red APK/控制器与日志保留。测试不使用个人角色和配置，非受控动作投影替代真实模块。
- 根因是快照Exception分支包含取消，以及开关先发布内存、再调用“失败保留/重试”的saveNow。ModuleConfigStore新增saveConfirmed专用于要求明确成功后生效的手动写入：共享writeMutex，以版本预约避开旧待写值；失败不排队自己的候选、恢复仍有效的旧版本，保留既有已接受队列及并发新请求，成功仅清理不晚于自己的旧待写值。saveNow/updateConfigDebounced原重试语义保持，不通过关闭整个配置重试来修复角色开关。
- 角色开关使用模块writeMutex串行，先检查取消，复制候选，确认本地落库后同步config和StateFlow；开始本地提交后以NonCancellable完成“提交＋发布”，保证晚到的取消不会令存储/运行分叉，外层VM仍传播取消且不伪报成功。提交开始前/等锁期间取消不应用候选。snapshot/currentStyleDirective/reducer订阅均继续传播CancellationException，普通风格读取异常仍按原规则回退固定人设。缓存稳定前缀及风格注入位置未改。
- 最终qualityCheck/:app:assembleDebug/:app:assembleDebugAndroidTest BUILD SUCCESSFUL（1m6s，530 tasks）。配置存储7项JVM（0失败/错误/跳过，3.065s），证明拒绝候选不延期生效、旧接受值/其他模块保留、并发新请求在成功/失败时均保留、成功覆盖旧队列、取消恢复旧队列和原saveNow继续重试；XML原件归档。
- 正式25项实机7.036s全绿：14项真实模块＋10项角色VM/事务＋1项ChatVM回归。实际生命周期重载验证开关持久化；关闭表达不删关系/情绪/历史。快照保留最近5条；亲密度只重置指定角色、情绪只清其会话，其他角色/无归属会话保留，历史事件保留。SQLite触发器拒绝多会话删除时整条DELETE回滚，生产VM给错误而不报成功，可解除触发器后重试；读取失败/重载、失败开关/重试、序列化切换与等锁取消、提交中取消及VM清理后的无虚假成功/DB运行一致均验证。
- 页面源码与2b.1接受版本摘要完全相同，沿用其80张八条件真实分屏/草稿/首尾证据；本片验证真实动作到生产VM状态，未把StateFlow断言当成新页面截图，也未改个人配置以验证日常角色。debug acceptanceConfigStore只给测试提供有生命周期的隔离存储worker，未暴露到release运行界面。Room仍30、个人资料和原工作树改动保留。
- APK artifacts/stability-20261009-a8character-module/ZeroV2-a8character-module-debug.apk SHA256 aab66d710d2ba0c9b68174741c7c0d559989c1eb7b9562d99fc968c897c1745c 已ADB覆盖安装并核验手机包摘要，控制器同核验，正常Main恢复。四日常模块ACTIVE/另三STOPPED，SDK1.1.0四资产/APK/Debian匹配、emergency=false，系统字号1.3不变；provider/A4账本完整对象与2b.1基线相同，无新增真实云请求。
- 证据 artifacts/stability-20261009-a8character-module/acceptance-index.json。结合2a数据写入/事务、2b.1完整页面与本片实际模块绑定，A8c.2/2b按角色范围收口；A8c/A8仍开放。下一片A8c.3自主行为设置：已读到直接发布配置后saveNow、读取异常吞掉、缺持久化反馈等源码候选，先复现再修订，保持在场退避/离场波动和既有预算门槛；随后4–8按既定顺序。进程死亡/跨姿态连续动效仍归A9，真实长周期自驱/发布归A10。


### A8c.3a 自主行为模式保存与运行时一致性（2026-10-09，限定通过）

- 真实 AgentRuntime＋ChatBusImpl＋隔离Room/ModuleConfigStore＋生产AgentBehaviorViewModel实机red 1项失败（1.648s）：OFF→SMART先启动运行时再保存，运行时读到旧OFF退出，界面与存储后来显示SMART但循环并未启动。原VM、red APK和日志归档；构造接缝仅分离配置/用量/模式调用依赖以覆盖生产VM，不改自驱节奏。未使用个人配置作为夹具。
- 改为确认写入agent_behavior后再发布模式并切换运行时，生产调用经过ModuleLifecycleManager.safeCall。沿用2b.2 saveConfirmed，不将被拒绝的手动候选送入后台重试队列；错误候选保留在当前VM供用户明确重试。读取失败单独反馈并阻止模式、自驱开关、预算覆盖；取消继续传播。提交开始前检查取消，本地提交＋发布＋切换完成后再传播晚到取消，避免退出页面造成落库/运行分叉。
- mode/selfDriveOnTick/预算共享一个保存忙状态，防重复操作和旧快照覆盖；模式值限定OFF/SMART/ALWAYS_ON，日预算保持8M–100M、月预算5–50，非有限数字拒绝。保存失败保持原模式；运行切换失败保留已存意图并明确提示，可再次尝试同一模式。页面增加读取/保存状态、错误与显式重试，模式和自驱开关在加载/保存期间禁用。完整视觉重构、报点/记忆参数保存反馈和页面八条件仍属3b，不能用本片StateFlow断言代替UI截图。
- 新增8项实机验收2.642s通过：原真实循环启动red转绿；拒写保持旧值且解除故障后700ms不自动重放、显式重试生效；运行切换失败后重试；读取失败保护与重载；并发模式/开关/预算不覆盖；提交中VM清理仍完成持久化/运行意图；实际SMART→OFF→ALWAYS_ON停止/重启；预算边界与无效值。失败由隔离DAO/回调注入，非个人数据故障，运行成功由真实ChatBus订阅核验。生产Hilt safeCall适配已编译；隔离模式fixture直接接实际runtime，未把注入的失败回调称为实际模块崩溃隔离测试。
- 最终33项实机10.843s全绿（8模式＋14实际角色模块＋10角色VM/事务＋1ChatVM）；qualityCheck/:app:assembleDebug/:app:assembleDebugAndroidTest BUILD SUCCESSFUL（1m1s，530 tasks）。7项配置存储XML以及4项运行时生命周期XML原件归档；生命周期JVM覆盖OFF启动/任务恢复门、持续用户在场超6小时保持安静与离场波次重置、计划心跳不绕过自主间隔、停止/恢复订阅。运行时代码与稳定前缀未改，长周期真实自驱仍归A10。settings ownership XML同归档，无失败/错误/跳过；不把构建缓存中的JVM结果称为本轮新实机执行。
- APK artifacts/stability-20261009-a8behavior-mode/ZeroV2-a8behavior-mode-debug.apk SHA256 185223ada7ebfefc42810bc14636ad5a0d6a385069297cc84ed6f73087f88c8f 已ADB安装并与手机包完全匹配，控制器摘要同核对。恢复正常Main；SDK1.1.0四资产/APK/Debian匹配，四日常模块ACTIVE/三STOPPED，emergency=false，系统字号1.3不变。provider/A4账本完整对象与既有基线相同，隔离夹具不调用真实provider。Room仍30，所有原工作树改动保留。
- 证据 artifacts/stability-20261009-a8behavior-mode/acceptance-index.json、red-native.log、final-device.log、quality-build.log与source-final。3a按模式/共享行为配置范围通过；A8c.3/A8c/A8仍开放。下一步3b按共享视觉规范收敛自主行为页，核验报点/记忆真实配置owner、持久化反馈、数值草稿、完整正文、末页与重建、八条件分屏/字号；再按A8c.4–8顺序推进。此前2a＋2b.1＋2b.2的角色父范围已限定收口。


### A8c.3a.2 自动化与报点自驱共用心跳启动修订（2026-10-09，限定通过）

- 优先响应实机“自动化/报点自驱均未运行”。只读检查red：个人agent_behavior为SMART/selfDriveOnTick=true，trigger_hub.selfDriveEnabled=true、日顶100；无障碍未启用，最近loop_stopped晚于loop_started，4项ACTIVE DAILY已到期。Scheduler有启动记录但没有新执行，未见预算/全局日顶拒绝证据。只输出开关、数量、时间戳及配置摘要，不输出个人任务、密钥或对话。
- 排查假设为无障碍绑定启动、初始化/订阅时序、预算/在场退避。实际应用仅initializeAll，AgentRuntime.onStart不启动，唯一启动/停止在AccessibilityService连接/销毁；设置页切模式虽已修复，重启应用仍不能恢复。这是共同停摆根因。旧应用启动接缝4项实机red中2失败（SMART/ALWAYS_ON无订阅）；OFF及手动start幂等用例通过，red APK/控制器/日志保留。
- ZeroApplication经initializeAutonomousRuntime完成所有模块初始化后，以ModuleLifecycleManager.safeCall启动运行时；尊重runtime.isEnabled及已存OFF。无障碍连接/断开只挂载/卸载屏幕、手势和工作文件能力，不再启动/停止自主循环。Scheduler以UNDISPATCHED建立订阅再返回onStart，防应用首tick在订阅就绪前丢失。唯一心跳源、间隔公式、在场退避、离场波次、稳定前缀、预算及每日触发上限不变，未修改手机无障碍/锁屏/超时设置。
- 新增6项实机：SMART/ALWAYS_ON无无障碍到达真实Scheduler与自驱入口；已存OFF不发tick/不执行；重复start/kick不增加订阅或额外自驱请求；实际AutomationModule的隔离PLAN任务调用本地工具，完整核验markRun/runCount/lastRunAt/nextRunAt与done，OFF时不运行。后两项接真实AgentScheduleGateway/BudgetGuard/Room，预算偏好隔离、闹钟Context拒绝系统Alarm服务以防替换个人PendingIntent；PLAN不调用TriggerHub，运行时自驱在Lazy处截断，无真实provider/个人任务。debug工厂不进入release流。
- 最终39项实机15.862s全部通过（6启动/实际自动化＋8模式＋14角色模块＋10角色VM/事务＋1ChatVM）。qualityCheck/:app:assembleDebug/:app:assembleDebugAndroidTest BUILD SUCCESSFUL（2m9s，530 tasks）。4项运行时生命周期JVM本轮重执行，XML零失败/错误/跳过（1.023s）；配置存储7项XML归档。角色页面证据沿用，不把backend断言称为新UI八条件验收。
- APK artifacts/stability-20261009-a8scheduler/ZeroV2-a8scheduler-debug.apk SHA256 c0d9ce95eb74ed8516577c8bea49216889b0339c2f08ecc9010be1ddacaaf388 已ADB安装并核对手机包/控制器摘要。恢复正常Main后，无障碍仍关闭，新loop_started、job_executed、automation_run_done及SELF_DRIVE tick触发记录出现；ACTIVE DAILY到期数4→0，INTERVAL仍0，用户配置全表逐项SHA256不变。证明实际调度/触发/重排恢复，不据此宣称每项模型结果正确或长期睡眠验收完成。
- 正常运行时跨时刻复制DB/WAL形成不一致诊断副本，未据此判断手机DB损坏。采集器改为短暂停本应用进程、同一冻结窗口复制两文件、finally恢复，再下载检查；此次窗口含ADB恢复约0.406s，quick_check=ok，只保存脱敏JSON。原失败临时副本 C:\Users\USER/AppData/Local/Temp/zero-scheduler-fvgkzdhv/zero_v2.db 因自动审批拒绝删除，尚未清理；拒绝未给出具体理由，已停止尝试，不能声称所有临时副本已清除。后续成功采集的临时副本正常清理。shell嵌套引号失败修订保留，最终采集脚本使用shlex.quote。
- SDK1.1.0四资产/APK/Debian一致，四日常模块ACTIVE/三STOPPED，emergency=false，系统字号1.3不变；provider/A4完整账本对象前后一致，无新增隔离云测试。恢复后个人应用按既有设置自主运行，其日常模型请求不等于隔离测试。Room仍30，所有工作树改动保留。
- 3a.2按应用进程内启动与调度范围通过，A8c.3/A8仍开放。发现AutomationWorker.doWork是返回成功的未接入存根，enqueue当前生产无调用；BootReceiver只恢复提醒，旧“恢复WorkManager”注释不符实现。该残留不能冒充后台兜底，Doze/系统回收/重启仍列A8c.3a.3独立复现收敛，保持唯一调度owner。A8c.3b的参数保存反馈/现代UI/草稿/八条件继续，之后4–8；A9进程死亡动效、A10长期运行发布仍开放。
- 证据 artifacts/stability-20261009-a8scheduler/acceptance-index.json、red-native.log、final-device.log、device-red.log/device-green.log及source-final。


### A8c.3b.1 报点与记忆参数确认保存及实机错误重试（2026-10-09，限定通过）

- 承接3a.2，继续自主行为页参数验收。两项实机red均失败（0.533s）：隔离DAO阻塞保存时，报点配置/页面已90→1234，记忆整理小时已2→9。旧代码直接修改共享config与StateFlow，然后saveNow；不只缺提示，还让未保存候选提前运行、普通异常未被页面捕获。red源码/APK/控制器/报告归档。
- VM复制报点/记忆配置候选，参数依据SettingsOwnership实际注解范围钳位、拒绝非有限数/错误类型/非专属字段；共享parameterSaving保护重复修改，saveConfirmed确认落库后才发布到注入的config并同步页面。拒写不排队自己的候选，原值保持，保留明确用户重试入口；旧saveNow后台重试契约不改。提交前取消不应用，本地提交开始后完成提交/发布；VM清理后传播晚取消，不伪报成功。未增加模型提示词限制，未更改调度节奏或Schema。
- 页面新增参数保存中/失败/已保存状态及重试；保存中禁用对应滑杆、数值编辑和记忆开关，预算输入在读取/保存行为配置时禁用。滑杆移动只更新预览，手势结束才提交，避免每个拖动采样形成写入；确认状态变化后预览回到已存值。仍保留注解范围与整数转换契约。完整现代视觉、数值草稿重建、末页/字号/分屏矩阵尚未收口，不以此片宣布页面全部通过。
- 6项参数实机回归覆盖报点/记忆确认后发布、各自SQLite触发器拒写的错误/无假成功、解除拒绝后700ms不后台重放而显式重试成功、提交中VM清理的落库/内存一致且无成功提示；未修改参数（randomOffset=29、graphThreshold=77）保留。fixture是生产VM＋真实配置类＋隔离Room/worker；本片未直接调用真实ReflectionJob，不称为长期记忆整理验收。运行时启动与真实自动化模块由3a.2及本轮联合回归验证。
- 1项实际设置页实机用例打开90秒数值编辑、录入1234、触发SQLite拒写，核对原运行值/错误提示，通过页面显式重试，核对已保存消息与1234显示；3张仅自有合成页面截图归档并人工查看失败/新值图。是100%资源字号、浅色固定配色、实际当前展开窗口的一条件验证，未伪装为八条件窄分屏/200%或草稿恢复。
- 正式46项实机21.554s全部通过（1参数UI＋6参数＋6启动/实际自动化＋8模式＋14角色模块＋10角色VM/事务＋1ChatVM）。最终qualityCheck/:app:assembleDebug/:app:assembleDebugAndroidTest BUILD SUCCESSFUL（29s，530 tasks）；前轮1m26s构建及red失败保留。参数持久化反馈从触发到UI验证闭环，但未据此关闭A8c.3或A8。
- APK artifacts/stability-20261009-a8behavior-parameters/ZeroV2-a8behavior-parameters-debug.apk SHA256 da75193d84f2e558012dcfa8af49a940033774d074355665ba253bd9ae5d651e 已ADB安装并核对手机包/控制器。正常Main恢复，SDK1.1.0四资产/APK/Debian一致，四日常模块ACTIVE/三STOPPED、emergency=false、系统字号1.3；诊断quick_check=ok，用户配置全表摘要与3a.2修订前一致，个人自动化/对话/记忆不用于修改测试。provider/A4完整账本对象无变化；隔离测试未调用云服务。正常自主运行仍按个人原设置。
- 证据 artifacts/stability-20261009-a8behavior-parameters/acceptance-index.json、red-native.log、final-device.log、quality-build.log、screens及source-final。3b.1按参数保存与错误重试范围通过；下一片3b.2依共享ZeroPageHeader/ZeroCard收敛自主行为页，清理旧emoji标题/技术化说明和横向拥挤，保存数值草稿并做真实八条件UI/末页重建验收。3a.3后台/Boot/未接入Worker、A8c.4–8、A9/A10及此前受审批阻断的临时副本清理仍按各自边界开放。


### A8c.3b.2 自主行为页统一视觉与真实八条件窄分屏验收（2026-10-09，限定通过）

- 承接3b.1确认保存，自主行为页采用共享ZeroPageHeader/ZeroCard/ZeroSettingSwitch，统一标题、圆角分组、正文/辅助文字颜色及留白；清理emoji标题、横向拥挤模式选择与技术化说明。模式改纵向单一RadioButton行，开关整行单一语义owner；共享开关新增显式enabled重载，原四参数调用保留默认启用契约。参数标签和值分行、数值入口至少48dp；用量分行显示。大字号数值弹窗正文可滚动，无效输入明确提示，禁用保存。编辑状态/数值草稿使用rememberSaveable，页面重建不覆盖草稿。
- 订正OFF描述：停止报点心跳/报点自驱并跨重启保持，既有系统闹钟仍按自身规则执行。删除旧“提醒/自动化/反思全暂停”的不实暗示；未偷偷修改闹钟/自动化执行政策。智能与始终在线目前相同调度/电源策略明确显示。用户在场退避、波次唤醒及预算规则本片未变；背景执行政策仍由3a.3单独收敛。
- 1项生产页面＋实际VM/配置类/隔离Room矩阵实机用例，100%/200%资源字号×浅/深色×固定/动态色八条件，均为Fold3真实约389.78dp窄分屏，无伪造窗口/Density，无系统字号/锁屏修改。每条件验证三模式保存、自主行动开关、九个数值入口可触达且完整48dp点击区域、取消弹窗、无效输入禁止保存、实际Activity.recreate后bad草稿保持、1234确认写入SQLite/内存、末页滚动及重建恢复。模式UI使用无副作用applyMode回调，实际Runtime模式由下述独立回归验证；未把UI夹具当成真实长期反思任务。
- 矩阵耗时163.444s全部通过，32张仅自有合成页面截图归档。人工核查100%浅色末页/深色首页、200%浅色首页/末页与深色末页，文字可读，无横向溢出，末页可到达。数值弹窗动作、禁用及草稿用AX验证；页面window截图不包含独立Dialog，不宣称已做完整Dialog视觉截图审查。六次前置脚本失败保留：状态提交后Compose虚拟节点父引用过期、底部部分露出按钮的剪裁边界、禁用按钮不提供CLICK动作；改用新树祖先定位及自有窗口滚动后核验，未降低48dp/真实窗口门槛。
- 另一次联合回归46项22.433s通过（参数UI1＋参数确认/拒写/晚取消6＋启动/真实自动化6＋模式8＋角色模块14＋角色事务10＋ChatVM1）；总47项，明确分两次调用，矩阵八条件不是八项JUnit。qualityCheck/assembleDebug/assembleDebugAndroidTest通过（530 tasks），最终控制器源码重新编译且匹配实机。3a.2已修复共享运行时被无障碍服务连接控制、首次tick订阅竞争，实机四项逾期DAILY已恢复，自动化完成与SELF_DRIVE tick可追溯；后台杀进程/Doze/Boot/未接入Worker不因此认定通过。
- APK artifacts/stability-20261009-a8behavior-ui/ZeroV2-a8behavior-ui-debug.apk SHA256 81287c32dc9aac15ae1cbfec5f48a9073b13dbf897af0b03a26a792ca58b8dc0 已ADB安装，包与控制器摘要核对一致；正常Main恢复。SDK1.1.0四资产/APK/Debian一致，四日常动态模块ACTIVE、三STOPPED，紧急停止未开启，系统字号仍1.3。诊断quick_check=ok，用户配置全表摘要保持修订前一致；A4/provider完整账本对象未变。隔离测试没有云端调用/个人任务修改。此前被自动审批拒绝的临时副本清理仍保持原审计记录，不声称已清理。
- 证据：artifacts/stability-20261009-a8behavior-ui/acceptance-index.json、matrix-report.json、matrix-device.log、final-device.log、quality-build.log、screens、source-final。3b.2按本片范围通过；A8c.3/A8仍开放。下一片优先3a.3后台/Boot/未接入Worker统一运行所有权和OFF策略，随后按计划推进A8c.4–8及A9/A10，不增加并行心跳源。


### A8c.3a.3a 双模式前台保活宿主（2026-10-09，限定通过）

- 用户明确决定：智能／始终在线均保活，必要时保留无障碍等手段。两模式继续沿用同一在场退避、离场波次与预算，不改变既有节奏，不新增平行心跳或无限唤醒锁。无障碍仍提供屏幕/手势能力，断连不再控制运行时生命周期。
- 新增AutonomousKeepAliveService薄前台宿主，常驻低优先级通知“Zero自主运行”，明确可在自主行为页关闭。Android14+声明specialUse用途/对应权限，Android26–33用已有FGS接口；通知点击打开Zero，未宣称新增通知内一键暂停按钮。服务START_STICKY，等待Application初始化确认后，订阅Room已保存模式及Runtime生命周期，经safeCall启动/停止唯一运行时；模式OFF或模块禁用停止宿主。重复预算写入/两开启模式互切不重复启动自驱。配置流或初始化错误停止运行时/服务并记录错误，不吞异常伪称保活成功。
- Application增加一次初始化完成信号；MainActivity在实际STARTED且模块初始化完成、模式未关闭时请求前台宿主；设置页模式确认保存后同步启停宿主。宿主不再次initializeAll，不新增网络/周期循环，不借用Live麦克风/投影权限。OFF页面说明仍维持提醒/自动化系统闹钟自身规则，未暗改闹钟政策。
- 新增2项实机测试（生产宿主配置绑定函数＋实际Runtime/隔离Room）：SMART、ALWAYS_ON各验证启动、自驱入口1次、三次预算变更和开启模式互切不增加请求/订阅、确认OFF后退出、重新订阅仍OFF不启动、恢复开启模式只有单一订阅并保留原报点截止时间（不立即多发自驱）、模块禁用触发停止。所有模型入口被拦截；不是假配置回调。前次宿主断言错误要求恢复立即多发，按实际退避契约订正；UI失败另由锁屏阻断，保留日志，不作为生产故障掩盖。联合48项实机24.512s通过，含此前46项参数/真实自动化/模式/角色/ChatVM回归。qualityCheck/assembleDebug/assembleDebugAndroidTest通过（46s，530 tasks）。A8c.3b.2页面布局保持，完整八条件UI证据仍关联该片；本片未冒称对新宿主做八条件视觉或Hilt真实个人OFF/ALWAYS_ON转换。
- 实机正常Main恢复后，Android15(API35)系统ServiceRecord确认isForeground=true、notificationId2003、specialUse类型0x40000000、START_STICKY。APK artifacts/stability-20261009-a8autonomous-host/ZeroV2-a8autonomous-host-debug.apk SHA256 874abdb6b53f7cc0b754090493e8c98246e9e0e896fd290819a425212fa1bc5a 已ADB安装并与手机包/控制器摘要核对。SDK1.1.0资产/APK/Debian一致，四日常模块ACTIVE，紧急停止false；系统字号1.3、用户配置全表摘要与修订前一致，诊断quick_check=ok，A4/provider完整账本不变。正常个人自主运行仍按原配置；隔离夹具不调用云服务。
- 证据 artifacts/stability-20261009-a8autonomous-host/acceptance-index.json、policy.md、final-device.log、foreground-host.txt、quality-build.log、source-final。已有前台宿主不等于Doze下CPU/网络按90秒必达，不把通知存在当作后台全验收；Boot/进程回收/Doze、旧Worker接线和实际执行仍开放。当前Worker没有生产enqueue调用且是假成功存根，Application尚未配置HiltWorkerFactory；合并Manifest仍含默认WorkManagerInitializer。下一片应统一初始化/持久任务所有权与真实结果，修复闹钟直接模块调用、并发到期认领和取消语义，先隔离复现后实施，避免第二心跳。
- 实际按Home退到桌面，30秒后Main不处于Resumed而宿主仍isForeground=true；自有ServiceRecord/聚合JSON归档，然后恢复正常Main。只证明正常后台驻留，不假称CPU休眠、断网、Doze或进程死亡通过。
- 官方核对：Android [specialUse用途与权限](https://developer.android.com/about/versions/14/changes/fgs-types-required#special-use)；[Hilt Worker配置](https://developer.android.com/training/dependency-injection/hilt-jetpack#workmanager)；[WorkManager周期最低15分钟且受系统约束](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)。据此不能用周期Worker替代现有90秒节奏，也不能用错误FGS类型宣称可靠保活。A8c.3a.3按拆分仅此子片通过，A8c.3/A8继续开放。


### A8c.3a.3a.1 前台宿主对齐模块中心统一用户开关（2026-10-09，通过）

- 3a.3a验收后的真实入口核对发现遗漏：模块中心toggleModule经ModuleLifecycleManager.setUserEnabled(false)改变统一运行态，并不调用Runtime.markDisabled；原宿主订阅Runtime.lifecycle，虽覆盖生命周期禁用，却不能覆盖用户手动禁用。这是原“模块禁用”结论的边界补证与修订，原报告不改成更强的证据。
- 用同一新控制器在已核对SHA874abdb6b53f7cc0b754090493e8c98246e9e0e896fd290819a425212fa1bc5a的旧宿主APK上实跑，SMART/ALWAYS_ON两项均失败（原宿主不响应setUserEnabled）；保留red-native.log、red-installed.json、原宿主源码。随后改为组合Room确认模式与ModuleLifecycleManager.runtimeStates，以对应模块operational为门禁；不增加自持启用位，不改模块中心，不靠轮询。相同用例改为实际setUserEnabled入口，确认退出/无额外自驱，原OFF/恢复截止时间/预算不重复等断言保留。
- 正式48项实机23.64s通过，qualityCheck/assembleDebug/assembleDebugAndroidTest通过（43s，530 tasks）。这修复的是宿主配置绑定函数对统一手动开关的响应；实际Android服务模式转换仍未写入个人配置进行测试，保留原证据边界。正常Main恢复后Android15宿主isForeground=true、id2003、specialUse、START_STICKY确认；APK artifacts/stability-20261009-a8autonomous-usergate/ZeroV2-a8autonomous-usergate-debug.apk SHA256 8833dc4ea63e01e843938c04bf3d5cafc99de487a033f78bd7d6756fde8826d8 已ADB安装并核对控制器/手机包。
- 用户配置全表摘要与本轮修订前一致，诊断quick_check=ok，A4/provider完整账本未变；正常主界面、SDK/APK/Debian资产、四日常动态模块ACTIVE及紧急停止false均核对。原八条件页面矩阵及退到Home30秒前台驻留证据仍分栏关联原子片，没有将旧APK的背景驻留证据冒充新包Doze结果。临时解锁仅用于本轮测试，文档/证据不保存解锁凭据。
- 证据 artifacts/stability-20261009-a8autonomous-usergate/acceptance-index.json、red-native.log、final-device.log、quality-build.log、foreground-host.txt、source-final。此补片通过，A8仍开放；下一项3a.3b聚焦Boot/旧Worker初始化与真实结果、闹钟safeCall/并发认领/取消、回收及Doze证据，保持已有单一报点owner。此前被自动审批阻断的临时副本清理仍未完成。


### A8c.3a.3b.1 自动化并发到期扫描与取消语义（2026-10-09，限定通过）

- 继续3a.3b按单片推进，先用生产AutomationModule、隔离Room和本地工具复现，不写个人任务/配置、不替换系统闹钟、不调用云端。已核对旧APK SHA8833dc4ea63e01e843938c04bf3d5cafc99de487a033f78bd7d6756fde8826d8；2项实机用例均失败：两个并发execute入口对同一到期任务调用工具2次，工具CancellationException被步骤及外层扫描捕获，未向上传播。红态日志和原生产源码留存。两个入口是模拟闹钟/报点的实际模块调用，不冒充真实AlarmManager广播。
- 根因：getDue在markRun之前并发读取同一行；并发入口没有共享执行所有权。新增模块内dispatchMutex覆盖扫描与整个到期执行，等待锁后重新查询due，避免对旧快照再执行。PLAN步骤及调度层独立捕获CancellationException并重抛，取消不更新runCount/lastRunAt/nextRunAt、不发布done成功；普通异常原处理保持。无数据库版本变化、无新增轮询或心跳。该锁只保证同进程定时入口串行，不能证明进程死亡前外部副作用与Room记账具有事务性，也未覆盖手动run_now与定时入口竞争。
- 新2项实机通过：重复调度仅一次工具调用/一次记账；取消可传播且保留到期状态、不显示完成。红态后额外增强同一取消用例，确认下一次正常执行可取得锁并完成，证明取消不会永久堵住调度。相关旧48项联合回归保留，包括真实启动自动化、SMART/ALWAYS_ON宿主、模式/参数、角色和会话切换；合计50项，24.486s通过。qualityCheck、assembleDebug、assembleDebugAndroidTest通过（1m32s，530 tasks）；detekt初次拒绝广义捕获后类型检查，改为独立catch，未屏蔽规则，失败日志保留。
- 修复APK artifacts/stability-20261009-a8automation-dispatch/ZeroV2-a8automation-dispatch-debug.apk SHA256 cf20b663b3fbd64c290ddcce3b5781c90edaa114c6d4bd5b6828fdd8bf2d85c9 已ADB安装，手机主包/最终控制器与归档摘要一致。恢复正常Main后前台宿主isForeground=true、id2003、specialUse、START_STICKY；SDK1.1.0四资产/APK/Debian一致、四日常动态模块ACTIVE、紧急停止false、系统字号仍1.3。诊断quick_check=ok，用户配置全表摘要与既有基线一致；A4/provider完整账本对象未变。保留全部工作树改动。
- 本片无页面视觉修改，原自主行为页八条件真实窄分屏矩阵仍关联A8c.3b.2，不将旧截图当成本片新UI证据。50项联合用例含单独的参数UI回归，不声称重跑完整页面矩阵。A8继续开放，不能以本片通过宣称后台全验收。
- 后续有序拆分：3a.3b.2核验Scheduler/闹钟的safeCall、初始化门禁及取消传播；3a.3b.3清除Worker假成功并明确生产接线/持久调度所有权；3a.3b.4补Boot、进程回收、Doze实机证据。随后A8c.4–8与A9/A10继续按原计划。Worker存根、Receiver直接模块调用、Scheduler取消吞并等风险仍未验收；不引入第二报点owner，不自行放宽门槛。此前被自动审批拒绝清理的临时副本仍按原记录开放，不声称已删除。
- 证据：artifacts/stability-20261009-a8automation-dispatch/acceptance-index.json、red-native.log、red-installed.txt、red-AutomationModule.kt、final-device.log、quality-build.log、final-device-check.json、foreground-host.txt、source-final。


### A8c.3a.3b.2a Scheduler取消所有权（2026-10-09，限定通过）

- 3b.1完成实现、50项实机/构建/六文档后继续下一子片。用生产Scheduler、真实SystemBus、隔离Room/Trace和本地合成AgentJob建立红绿回路。旧APK SHAcf20b663b3fbd64c290ddcce3b5781c90edaa114c6d4bd5b6828fdd8bf2d85c9上实跑3项，2项失败、1项普通异常隔离对照通过：销毁调度器后第二任务仍调用1次；局部任务取消被记为job_failed。红控制器APK/源码、原Scheduler及日志均归档，无个人任务或云调用。
- 原因是广义catch吞掉宿主取消后继续for循环。修订每项分发及完成记账前检查当前协程有效性；CancellationException单独处理，宿主协程已取消则立即传播，后续任务不再调用/不记成功；宿主仍有效的局部任务取消记录job_cancelled及限长原因，不错误报崩溃，也不终止长期tick订阅。普通异常依然记录job_failed并隔离，后续任务和未来tick继续。没有第二心跳、循环重启器或新增定时源。
- 新3项实机均通过：销毁后后续任务0次且不误记失败；首轮局部取消/下一轮正常执行、订阅保留；普通失败两轮仍隔离且有两次真实失败记录。联合原50项共53项，25.145s通过；qualityCheck、assembleDebug、assembleDebugAndroidTest通过（1m 22s，530 tasks）。初次detekt指出局部取消原因未保留，已记录限长原因，失败日志保留，未加抑制规则。
- APK artifacts/stability-20261009-a8scheduler-cancellation/ZeroV2-a8scheduler-cancellation-debug.apk SHA256 8cfc11f7481c9b6c26f4c0e9d02f58bf49c6231a741245ab89727363a9c33f9b 已ADB安装；手机主包和最终测试控制器与归档摘要一致。正常Main恢复，前台宿主isForeground=true、id2003、specialUse、START_STICKY；SDK1.1.0四资产/APK/Debian一致、四日常模块ACTIVE、紧急停止false、系统字号1.3。诊断quick_check=ok，用户配置全表摘要与既有基线相同，A4/provider完整账本对象不变；全部工作树改动保留。
- 本片验证的是实际Scheduler生命周期/取消分发，任务体为隔离合成AgentJob；3b.1实际AutomationModule执行与取消用例也在53项中重跑。没有声称真实闹钟广播/Doze/开机恢复验收。ModuleLifecycleManager.safeCall原实现已传播CancellationException，本片核对确认其非吞取消根因，未改动该公共安全包装。
- A8仍开放。3a.3b.2剩余拆为2b：调度模块safeCall/运行门禁、Receiver初始化与生命周期；3b.3：Worker真实结果/生产接线/持久所有权；3b.4：Boot/进程回收/Doze证据，然后A8c.4–8及A9/A10。手动与定时竞争、跨进程副作用幂等仍开放。OFF策略/个人配置/UI不变；原八条件UI矩阵仅关联3b.2，未冒充本片重跑。此前被自动审批拒绝的临时副本清理仍保留原开放记录。
- 证据 artifacts/stability-20261009-a8scheduler-cancellation/acceptance-index.json、red-native.log、red-installed.txt、red-controller.apk、red-Scheduler.kt、final-device.log、quality-build.log、final-device-check.json、foreground-host.txt、source-final。


### A8c.3a.3b.2b.1 定时模块统一门禁与崩溃隔离（2026-10-09，限定通过）

- 按2b先完成Scheduler实际模块入口。用生产Scheduler、真实SystemBus、生产ModuleLifecycleManager/执行守卫和隔离Room建立4项实机反馈环；旧APK SHA8cfc11f7481c9b6c26f4c0e9d02f58bf49c6231a741245ab89727363a9c33f9b上3项失败、取消重试对照通过：模块中心setUserEnabled(false)后仍调用1次，DESTROYED后仍调用1次，非核心模块异常未进入safeCall禁用隔离。红控制器APK、原Scheduler和原测试源码留存。无个人任务/配置/云调用，不将合成模块说成真实用户任务。
- 根因是Scheduler直接job.execute绕过现有统一门禁。为IZeroModule任务接入Provider<ToolExecutionGuard>，执行时用metadata.id查源模块（不是独立AgentJob.id），由现有executeForModule读runtimeState并转safeCall；不新增状态副本，不让services依赖manager实现，也不新建Hilt直接构造环。禁止调用或隔离异常返回失败时记录job_not_run，不记job_executed；局部/宿主取消沿用2a语义。守卫Provider只在业务分发解析，不在构造或启动订阅时取出。
- 原构造3参数改为4参数强制守卫，实际启动夹具也绑定真实Manager；纯合成非模块Job取消夹具以会抛错的Provider验证不误用模块守卫。4项绿态通过：实际模块中心关闭/恢复，销毁门禁，崩溃自动禁用且下一tick不重复调用，局部取消不禁用且下一tick可重试。全联合57项26.741s通过；qualityCheck、assembleDebug、assembleDebugAndroidTest通过（1m 42s，530 tasks）。detekt初次指出新增100字理由截断的MagicNumber，改为既有60字约定，保留失败日志，不抑制规则。
- 第一次联合57项中参数UI用例受锁屏阻断（其余56项通过），保留locked-device-failure.log；按既有用户授权临时解锁后重跑相同57项断言通过，未改系统锁屏/超时/字号，记录不保存解锁凭据。
- 首次普通Main恢复后宿主检查未通过，归档host-failure-device-check.json：运行时已启动但服务未出现；相同包实际Home→Main重新进入的对照通过（resume-host-control.json），未直接用am startservice绕过生产入口。按真实重新进入后核对正常Main/宿主。本片仅验收定时模块门禁，冷启动初始化前退到桌面的前台启动窗口另立后续子片，不以此次对照声称该窗口已解决；最初red-resume-host.json文件内容实际上为通过的对照，勿解释成失败证据。
- APK artifacts/stability-20261009-a8scheduled-module-guard/ZeroV2-a8scheduled-module-guard-debug.apk SHA256 0a3ff93db5d7aba52f95b060d10801ab470ad09541e9afa444c5b4ca87bf3bda 已ADB安装，手机主包/控制器与归档摘要一致。正常Hilt Main启动和前台宿主isForeground=true/id2003/specialUse/START_STICKY核对；SDK1.1.0四资产/APK/Debian一致、四日常模块ACTIVE、紧急停止false、字号1.3。quick_check=ok、用户配置全表摘要与既有基线一致；A4/provider完整账本未变，保留所有工作树改动。已与本轮源快照对照，只收敛Scheduler及所需测试接线，不归属此前工作树内容。
- 本片覆盖实现IZeroModule的任务源，例如Automation/SystemEvent/Music/Knowledge/Memory模块；不是IZeroModule的独立Job保留自身所属模块与生命周期契约，未宣称全体后台任务已完成门禁迁移。pendingJobCount统计未在本片改动。关闭只能阻止下一次分发，不声称中断已产生的外部副作用。无UI视觉修改，原八条件矩阵只关联3b.2；联合单项参数UI回归不冒充全页面重新验收。
- A8保持开放。下一项2b.2审计Receiver初始化/生命周期和统一守卫；之后3b.3 Worker真实结果/生产接线/持久所有权、3b.4 Boot/进程回收/Doze，以及A8c.4–8与A9/A10。手动/定时竞争、跨进程副作用幂等及独立Job源门禁仍单列待审。此前被自动审批拒绝的临时副本清理保持原开放记录，不另途径绕过。
- 证据 artifacts/stability-20261009-a8scheduled-module-guard/acceptance-index.json、red-native.log、red-installed.txt、red-controller.apk、red-Scheduler.kt、red-ScheduledModuleGuardAcceptanceTest.kt、final-device.log、quality-build.log、final-device-check.json、foreground-host.txt、source-final。


### A8宿主冷启动退到桌面现场补验（2026-10-09，单一场景通过）

- 2b.1普通恢复首次未检出前台宿主后，继续用真实生产入口补验：只force-stop Zero，启动Main后立即按Home，连续12秒读取自有ServiceRecord，最后恢复Main。手机未改系统设置/用户配置/Termux模块。主界面确实不在Resumed，宿主约第2秒出现并持续到观察结束；同进程Home→Main对照也通过，未用直接am startservice替代生产入口。
- 本次为正向实机观察，不是已复现的缺陷；red-cold-home.json/log虽使用最初预期失败的文件名，实际内容是通过，不得当作红态证据。没有修改Main启动代码，没有凭首次collector异常推断生命周期时序根因。首次异常仍保留追踪，12秒观察不证明Doze/进程死亡/所有冷启动顺序可靠。
- 产物与2b.1同一APK，SHA256 0a3ff93db5d7aba52f95b060d10801ab470ad09541e9afa444c5b4ca87bf3bda，复用该包57项实机及530 tasks构建证据，不虚增JUnit数。证据 artifacts/stability-20261009-a8cold-host/cold-home-check.py、red-cold-home.json、red-cold-home.log；A8仍开放，继续闹钟分发入口及持久执行链。


### A8c.3a.3b.2b.2a 闹钟分发模块统一门禁（2026-10-09，分发层限定通过）

- 2b.1与冷启动现场补验记录后继续2b.2。原Receiver把action分发写在goAsync协程内，没有可安全隔离的测试入口。先抽取生产AlarmDueDispatcher，Receiver真实调用该组件；这一阶段保持原直接execute及音乐Bus语义，编译并安装中间包，再用该包建立红态，非先修好后自评。红中间APK SHAccff808f00d925d1b181ad783a2692657e71e1d06f4ed3459e3e5fd8bd830730与手机核对，4项实机2项失败：关闭模块、销毁模块仍各执行1次；实际PLAN正常执行/重排和取消传播对照通过。中间APK/控制器、未守卫组件源码及日志均保留。
- 组件为提醒/自动化接入Provider<ToolExecutionGuard>，用真实metadata.id实时读源运行态，经现有executeForModule→safeCall隔离；未知action不分发，音乐仍只发SystemBus.onMusicDue，不注入app MusicModule。失败记录alarm_job_not_run和有界原因，不把到达广播当作任务完成。Receiver单独重抛CancellationException，保留finally PendingResult.finish；没有新增心跳、实际闹钟、初始模块重初始化或个人配置写入。
- 新4项实机均通过：生产AlarmDueDispatcher＋真实AutomationModule/真实Manager/隔离Room验证模块中心实际关闭阻止执行、销毁阻止执行、两次唤醒仅一次本地PLAN/一次记账并重排、取消不推进任务且不禁用模块。提醒Provider在本地AUTOMATION夹具中会抛错，确认没有误走提醒源；不是向手机发送真实系统闹钟。Receiver框架onReceive/goAsync/finish以及真实提醒/音乐交付尚未实测，本片不可借组件测试宣称已验证这些时序。
- 原57项联合保留，共61项26.406s通过；qualityCheck、assembleDebug、assembleDebugAndroidTest通过（2m 13s，530 tasks）。两次detekt失败分别为新组件MagicNumber和行长，已命名TRACE_REASON_LIMIT并分开有界原因表达，失败日志留存，无规则抑制或门槛放宽。UI用例先确认可显示，不改锁屏/超时/字号，不存解锁凭据。
- 最终APK artifacts/stability-20261009-a8alarm-module-guard/ZeroV2-a8alarm-module-guard-debug.apk SHA256 b70d955df8e12ca1a96b311f845c46775844aadde3bf69297653bbe4863e710c 已ADB安装，主包/控制器与归档摘要一致。恢复生产Main并核验前台宿主isForeground=true、id2003、specialUse、START_STICKY；SDK1.1.0四资产/APK/Debian一致，四日常动态模块ACTIVE、紧急停止false、字号1.3；quick_check=ok、用户配置全表摘要和A4/provider完整账本与原基线一致。保留现有工作树/暂存区，未提交交错的既有改动。本片无页面视觉更改，联合单项UI回归不代替八条件全页矩阵。
- 初始化/持久生命周期明确继续开放：Android官方goAsync文档说明广播执行时限仍包含异步处理到finish的时间，长任务应交给系统后台设施。故不能仅加awaitModuleInitialization或safeCall就认定冷启动/长任务可靠；旧AutomationWorker仍无生产enqueue调用且doWork固定成功，ZeroApplication仍未配置HiltWorkerFactory。下一完整纵切片须一起完成：单一初始化完成信号、Worker真实扫描结果/取消/隔离、Hilt工厂与初始化配置、Receiver已知action的一次性持久入队；不新增周期心跳，不恢复已退役确认流程，不删个人任务。2b.2b与3b.3依赖联动，先做Worker实执行/初始化契约的隔离反馈环，再接Receiver生产producer，不能把任一空接线独立标全通过。随后3b.4 Boot/回收/Doze、A8c.4–8及A9/A10继续。独立非IZeroModule Job源门禁、手动/定时竞争、跨进程副作用幂等及首次宿主恢复异常仍另列，A8保持开放。
- 官方依据：[BroadcastReceiver.goAsync生命周期约束](https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync())。证据 artifacts/stability-20261009-a8alarm-module-guard/acceptance-index.json、red-native.log、red-installed.txt、red-seam-main.apk、red-controller.apk、red-AlarmDueDispatcher.kt、final-device.log、quality-build.log、final-device-check.json、foreground-host.txt、source-final；前序cold-home-check的12秒场景仅对应前序APK，不冒充本片新包冷启动/Doze证据。此前被自动审批拒绝的临时副本清理仍按原记录开放。


### A8c.3a.3b.2b.2b＋3b.3 闹钟持久交接与真实Worker（2026-10-09，限定通过）

- 接续L1和2b.2a。生产Receiver旧goAsync承载整个模块扫描，AutomationWorker固定成功，Application未提供Hilt工厂。先在旧实装包上跑2项实机，2项失败：不存在的旧automation_id被报成功、Application没有Configuration.Provider。红APK/源码/日志保留；完成修订后真实业务及配置验证均通过。
- Receiver只接受三种已知action，提交一次性持久任务并等待提交确认后finish；业务在Worker执行。core AlarmWorkQueue接口隔离services与app/WorkManager；Hilt队列按action串接APPEND_OR_REPLACE，后续唤醒不取消正在执行的扫描，无网络前提、无新周期心跳。提交future最多等待5秒；不将其描述为所有冷启动/系统调度情况下的时限保证。
- Application与前台宿主/Worker共用Singleton ModuleInitializationGate。Worker仅初始化等待有60秒期限、最多2次重试；业务经生产AlarmDueDispatcher与现有模块守卫/safeCall。取消及工具局部超时传播，已进入业务的普通异常失败且不自动重放。成功仅表示到期扫描/Bus分发返回，不能等同每个任务交付或音乐播放成功。保留Worker类名，移除无调用方的旧enqueue存根；只有旧automation_id且缺少alarm_action的遗留请求明确失败，不删除个人任务。
- Application提供HiltWorkerFactory配置，只移除WorkManagerInitializer元数据，保留其他AndroidX Startup初始化器。实机直接验证该配置能创建实际AutomationWorker；Hilt工厂无单例约定，初次assertSame误判已改为真实创建/执行验证，没有改变生产作用域迎合测试。
- 联合23项实机10.221s通过：2项工厂/旧输入、6项Worker/初始化/取消/真实广播交接、4项模块守卫、6项启动、2项SMART/ALWAYS_ON宿主及3项Scheduler取消。真实同UID有序广播验证Receiver.finish发生在提交后，两个唤醒均保留且业务仅执行一次；业务Room和WorkManager用隔离夹具，无个人任务或云请求。另两进程阶段用物理WorkManager数据库提交无action的隔离任务，显式force-stop后正常Main重启，验证阶段读取原ID为FAILED、执行次数1且没有重试；只证明持久恢复/真实工厂，不冒充LMK、有效个人任务、Boot或Doze交付。
- qualityCheck＋assembleDebugAndroidTest通过（最终26秒，530 tasks，含assembleDebug；quality-build-final-3.log）。新增Gate取消等待单测通过。保留编译修正日志（Hilt EntryPoint导入、WorkInfo可空契约）和WorkManager执行次数断言纠正日志及安装未完成提前启动旧包的无效结果premature-install-native.log，后者不计绿态。未抑制质量规则。
- 最终APK artifacts/stability-20261009-a8durable-alarm/ZeroV2-a8durable-alarm-debug.apk SHA256 bef486dfc11d0f08b9ddc0b38bd2e02df15dfe5c060a3b27b878da57c6c5a482 已保留数据ADB安装、手机摘要一致；正常Main恢复及前台宿主另留foreground-host.txt。诊断quick_check=ok，用户module_configs全表摘要与本轮基线完全一致。本轮未改LLM/上下文/缓存请求逻辑，保留local-qwen与ADB reverse，不发DeepSeek验收请求、不重置既有预算。全部已有工作树改动保留。
- A8保持开放。下一片3b.4须审计BootReceiver初始化前直接恢复并补真实回收/Doze证据；提醒一次性过期跳过、周期下期重排的现有规则保持。独立Job源门禁、手动/定时竞争、跨进程副作用幂等、首次宿主恢复异常、A8c.4–8及A9/A10仍开放。本片无UI视觉变化，不复用截图宣称全页新验收。旧临时数据库副本被自动审批拒绝清理仍开放，不绕过。
- 官方依据：[自定义WorkManager配置](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration)、[Hilt Worker](https://developer.android.com/training/dependency-injection/hilt-jetpack#workmanager)、[唯一任务串接策略](https://developer.android.com/reference/androidx/work/ExistingWorkPolicy)。证据见本片acceptance-index.json、red-native.log、final-device.log、persistence-enqueue.log、persistence-verify.log、quality-build-final-3.log、installed-sha.txt及source-final。



### A8c.3a.3b.4a 开机恢复契约与Doze持久任务探针（2026-10-09，限定通过）

- 接续3b.3，保留本轮开始的391项工作树改动。旧生产BootReceiver直接调用模块异步恢复，绕过共享初始化和源门禁；恢复入口没有启用态检查，广义捕获隐藏取消。先安装测试接缝包，在真实SystemEventModule、隔离Room及拒绝调度真实闹钟的Context上实跑1项，1项失败：关闭模块后过期合成提醒仍从active改为missed。红源码/APK/控制器及red-native.log保留，不读取个人提醒，不发送模型请求。
- BootReceiver只在BOOT_COMPLETED提交已知BOOT_RESTORE action，等待现有一次性持久队列提交后finish；复用真实AutomationWorker/共享初始化Gate、模块守卫和safeCall，不新增周期唤醒。恢复业务采用suspend restoreAfterBoot，等待落库和队列更新后返回，取消/错误交给Worker所有者处理；旧权限变化回调保留模块作用域异步rescheduleAllOnBoot适配，取消单独传播，不破坏非suspend调用方。
- 恢复入口检查关闭/销毁状态；restoreMutex串行化初始化、开机和权限回调的提醒恢复，未把该锁声称为所有提醒增删/执行的全局互斥。恢复后的StateFlow改用更新后的Room数据，修正旧快照仍显示active或旧周期时间的问题。一次性过期仍missed且不补发，周期保持原相位推进到未来，不修改历史规则或数据库版本。未改变全局onAwake初始化策略；关闭态拒绝结论限定为额外恢复入口及Worker守卫。
- 新4项实机通过：关闭模块不改记录；恢复完成时Room和只读流一致、周期相位/重复恢复幂等；已取消调用不改记录；真实AlarmWorkExecution等待Gate后经真实Dispatcher/ModuleLifecycleManager/SystemEventModule恢复，关闭源返回Failure。原23项联合保留，共27项11.418s通过。Context仅在最后一项允许空模块启动时一次只读精确闹钟权限查询，后续闹钟服务访问拒绝；没有替换个人AlarmManager排程或通知。控制器不运行正常Application.onCreate，真实系统开机广播未伪造。
- qualityCheck＋assembleDebugAndroidTest通过（1m 17s，530 tasks，含assembleDebug）。初次detekt拒绝新泛型捕获，改为让Worker拥有错误处理；随后编译指出旧权限回调是非suspend，保留明确适配入口修复。失败日志留存，没有屏蔽规则。本片普通业务错误不自动重放可能产生的副作用。
- 实机Doze探针：手机约31℃，既有用户白名单包含Zero，未改白名单/保活设置。先向物理WorkManager数据库提交缺少action的隔离任务，再临时模拟拔电/熄屏和force-idle；5次间隔10秒采样均IDLE，退出后原任务终态FAILED、执行次数1且没有重试。探针无有效业务action、无个人任务/模型调用；不能由此宣称有效提醒准点送达、无白名单Doze行为、真实系统闹钟或LMK恢复已通过。临时force状态、电池模拟已解除，最终状态见doze-observation.json和restored-system-state.txt。该探针按[Android官方Doze测试流程](https://developer.android.com/training/monitoring-device-state/doze-standby)执行，验收边界保持。
- 修复APK artifacts/stability-20261009-a8boot-recovery/ZeroV2-a8boot-recovery-debug.apk SHA256 093492168842403b1b92e45bb3474a0697d6adbca7b88eb156b3fbcbdfc62ab9 已保留数据ADB安装，主包/控制器摘要核对。正常Main恢复，前台宿主另留foreground-host.txt；quick_check=ok，用户module_configs全表摘要与本轮基线一致。未改LLM预算/上下文/缓存源码，local-qwen及USB转发保留，无DeepSeek测试请求、不重置既有额度账本。本片无页面布局变化，不替代全页UI矩阵。
- A8及3b.4整体仍开放；下一项3b.4b需真实设备重启后BOOT_COMPLETED、有效提醒/自动化在Doze中的交付及进程回收证据。本片仅收口恢复方法/Worker接线和无业务持久探针。独立Job门禁、手动/定时竞争、跨进程副作用幂等、A8c.4–8、A9/A10仍按原序推进。此前被自动审批拒绝清理的旧临时数据库副本仍开放，不重试绕过。
- 证据 artifacts/stability-20261009-a8boot-recovery/{acceptance-index.json,red-native.log,green-native.log,quality-build-final.log,doze-observation.json,doze-worker.log,doze-verify.log,final-device-check.json,installed-sha.txt,source-final}。


### A8c.3a.3b.4b.1 真实开机恢复／前台宿主缺口（2026-10-09，限定通过）

- 接续4a，从本轮开始392项工作树改动继续，未reset/clean/提交。真实重启手机，首次解锁前ADB不可见，由用户完成首次解锁恢复连接；未伪造BOOT_COMPLETED或修改锁屏设置。首次新boot_id下真实BOOT_RESTORE完成且SMART循环启动，但主用户AutonomousKeepAliveService不存在。verify-boot.py after-device.json red实跑断言失败并保存red-real-boot.log、red-boot-check.json和原BootReceiver；不是用组件测试代替真实开机。
- 三项诊断按实际证据收敛：MainActivity是唯一宿主start生产调用方；模式OFF/配置变动由SMART和摘要一致排除；系统拒绝不是首轮根因，因为旧BootReceiver根本未请求宿主。新增Hilt AutonomousBootHost，在开机接收器的持久交接协程中按确认配置请求现有specialUse前台服务，OFF/当前关闭的Runtime不请求，服务本身继续等待共享初始化及观察运行态。宿主请求被平台拒绝时独立留boot_host_start_failed，不阻断提醒恢复入队。无第二心跳、周期重试或后台恢复器，不改缓存/模型路由。
- 1项新增复合实机用例用隔离Room测试SMART/ALWAYS_ON/OFF、关闭Runtime和配置不被改写；不是6个独立测试，也不冒充三模式真实重启。原27项保留，共28项12.551s全通过。qualityCheck＋assembleDebugAndroidTest通过（1m 32s，530 tasks，含assembleDebug）。
- 安装修复包后第二次真实重启，用户首次解锁并未打开Zero主页面。新boot_id与首轮不同；主用户已出现foregroundId2003、isForeground=true、specialUse服务，BOOT_RESTORE成功记录位于本次估算boot epoch之后。初期读取曾见旧记录，等待实际完成后用严格epoch校验收口，未把前轮成功冒充本轮。第二次verify-boot.py green-device-final.json green通过，证明当前Android15/SDK35、SMART及既有电池豁免条件下真实开机接线成立。
- 手机同时存在用户0和用户95的同名进程，旧pidof“唯一进程”诊断主动拒绝读取。仅修订本片诊断脚本，以run-as主用户UID10025匹配ps的准确主进程，并以dumpsys --user 0核验宿主；没有冻结、读取或修改用户95私有数据。原诊断模板及历史证据未覆盖写，副用户不计本片验收。异常输出留痕；不把两用户服务混算成同一Runtime重复owner。
- 主用户quick_check=ok、module_configs全表摘要与重启前一致，五条ACTIVE自动化数量与分组保持。两次重启后dynamic_modules全记录state/crashCount一致：qzone_post/searx_search/vision_pro为ACTIVE，其他保持原DISABLED；未擅自启用关闭模块，DB状态不等同每项远端功能均已调用验收。Termux恢复走原生产引导与mount链，原dyn_restore_failed记录早于本轮，未报告为新失败。
- APK artifacts/stability-20261009-a8real-boot/ZeroV2-a8real-boot-debug.apk SHA256 09149cb405373efe5cef982e04ff29361eb9f13be0ad217cf1edbbc868ac1e1f 已保留数据ADB安装，手机主包/控制器摘要核对。两个重启后ADB reverse8080已恢复，本地节点health正常；本轮不新增DeepSeek付费测试、不操作旧预算额度账本。真实常规运行的专用能力网络行为不宣称已全部本地化。
- 3b.4b仅真实开机子项收口，A8仍开放。下一3b.4b.2补有效提醒/自动化在真实Doze中的AlarmManager→Receiver→Worker→业务交付及进程回收；4a无action探针不代替有效任务。真实OFF/ALWAYS_ON开机、无电池豁免和其他用户空间另需证据，不修改个人模式来冒充已验收。A8c.4–8、独立Job源门禁/手动竞争和A9/A10按原计划继续。本片无UI布局变化；旧被自动审批拒绝清理的临时副本仍开放。
- 官方边界：[后台启动前台服务的例外与限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)、[Android15开机服务类型限制](https://developer.android.com/about/versions/15/changes/foreground-service-types)。本机处于既有电池豁免，不能由结果外推所有后台限制条件。证据本片acceptance-index.json、red/green-boot-check.json、red/green-real-boot.log、green-host.txt、green-device-final.json、green-native.log、quality-build.log及source-final。


### A8c.3a.3b.4b.2a 有效PLAN真实Doze广播交付（2026-10-09，限定通过）

- 按3b.4b.1后续推进有效任务，未更改生产业务源码。新增显式validDozeProbe=true才运行的验收控制器；主用户0、独立requestCode900820，目标为生产ExactAlarmReceiver，action为真实AUTOMATION_DUE。该身份与生产4002不同，不替换个人闹钟；本次用ELAPSED_REALTIME_WAKEUP＋setExactAndAllowWhileIdle安排45秒后唤醒，生产RTC身份/时钟及权限降级仍另验。无新增debug导出组件、生产周期心跳或模型调用。
- 实机走真实AlarmManager广播→Hilt生产Receiver→生产DurableAlarmWorkQueue接口→实际AutomationWorker/AlarmWorkExecution→模块守卫/ModuleLifecycleManager→真实AutomationModule PLAN。业务工具为本地夹具回调，只读当时系统idle状态；隔离Room保存合成计划，WorkManagerTestInitHelper提供内存数据库及组装实际Worker的工厂，不扫描个人自动化、不发送通知或模型请求。此接缝验证交付和业务执行，不能冒充生产WorkManager物理落盘、真实Hilt工厂冷恢复或进程死亡后的执行。
- 1项复合实机用例45.544s通过，不拆成多个测试计数：9次5秒间隔采样均IDLE，工具真正执行时idleAtTool=IDLE；工具calls=1、Room runCount=1、workCount=1、Worker成功，下次执行时间已推进到未来。计划与实际elapsed相差131ms，仅为单次观察，不是长期准点/性能门，不放宽原验收门槛。该用例不是4a“缺少action→Failure”的无业务探针，已进入实际PLAN和记账路径。
- 测试结束finally取消900820对应的独立PendingIntent并取消隔离WorkManager的本测试队列；未cancelAll/prune生产任务。驱动脚本解除force-idle和电池unplug模拟，deep恢复ACTIVE、mForceIdle=false、实际供电状态恢复（AC=true、USB=false）；既有Zero电池白名单保留，未改锁屏、超时、字号或其他Android用户配置。源门禁、初始化、取消及两种宿主模式的28项上一片证据仍对应同一主APK，本轮不冒充重跑28项。
- qualityCheck＋assembleDebugAndroidTest通过（38s，530 tasks，含assembleDebug）。主APK SHA256 09149cb405373efe5cef982e04ff29361eb9f13be0ad217cf1edbbc868ac1e1f与上一片实装一致，仅ADB更新测试控制器；没有发现需通过改业务逻辑修复的新失败。安装主包事实仍以本轮installed-sha.txt核对；不将新增验收控制器包装成新修复主包。
- 正常Main/前台宿主恢复：主用户foregroundId2003、specialUse、isForeground=true，quick_check=ok；module_configs全表摘要、个人自动化分组及dynamic_modules state/crashCount与本轮基线一致。原关闭模块未启用，现有工作树改动全部保留，未提交交错改动。local-qwen与USB转发保留，未操作旧费用额度账本；“无模型请求”限定本测试夹具，不外推为常规后台所有专用能力均无网络。
- 3b.4b.2及A8保持开放，下一2b验收有效业务在物理持久队列和进程回收后的恢复，必须避免活instrumentation/内存夹具替代冷恢复；同时补生产RTC排程、真实提醒通知、权限降级和必要竞态证据。A8c.4–8、独立Job门禁/手动竞争及A9/A10继续原顺序。本片无UI视觉修改，不能替代全页UI矩阵；此前被自动审批拒绝清理的旧临时副本仍开放，不重试绕过。
- 证据 artifacts/stability-20261009-a8valid-doze/{acceptance-index.json,native-doze.log,driver.log,business-receipt.json,doze-observation.json,quality-build.log,installed-sha.txt,before-device.json,after-device.json,foreground-host.txt,restored-system-state.txt}；控制器源码ValidDozeAlarmAcceptanceTest.kt及本片final源码快照。


### A8c.3a.3b.4b.2b.1 持久业务夹具跨进程恢复（2026-10-09，限定通过）

- 接续2a，仅扩展androidTest：原守卫夹具默认仍内存Room；显式参数只允许独立a8_disk_recovery_fixture.db，恢复阶段不重新插入任务。新增diskRecoveryPhase显式双阶段控制器，隔离配置/运行时/工具，无个人任务扫描、模型请求或生产闹钟变更。
- seed阶段实际Room提交合成PLAN并保存就绪标记。驱动核对主用户UID10025、进程名及PID后，仅对该测试进程执行SIGKILL，确认原PID退出；不是force-stop、真实系统LMK或后台自恢复。随后独立instrumentation新进程读取同一物理数据库，未重建任务，经实际AutomationWorker→AlarmWorkExecution→模块守卫→AutomationModule执行本地工具。
- seed PID12140、恢复PID12209；恢复前runCount=0，工具calls=1、记账runCount=1，下次时间推进未来；同一恢复进程再次Worker扫描没有重执行。1项恢复用例通过；原夹具/Worker接线10项相关实机回归通过（2.445s）。强制终止的seed不是通过测试，未把崩溃日志算成功。此项不证明副作用后记账前崩溃的恰好一次语义。
- qualityCheck＋assembleDebugAndroidTest通过（41s、530 tasks，含assembleDebug）；生产源码/主APK未变，主APK SHA256 09149cb405373efe5cef982e04ff29361eb9f13be0ad217cf1edbbc868ac1e1f与手机安装摘要一致，仅更新控制器。原28项不冒充本轮重跑。
- 验收后关闭并删除本测试独立数据库及就绪标记，驱动确认不存在；保留私有脱敏回执和仓库证据。正常Main及user0前台宿主恢复，foregroundId2003/isForeground=true；个人module_configs摘要、自动化分组、动态模块state/crashCount与基线一致，quick_check=ok。本地8080转发保留，不重置费用账本、不触碰副用户95或既有工作树改动。
- 2b及A8仍开放：下一2b.2必须补生产WorkManager物理队列及真实Hilt自动冷恢复。本片Worker由测试工厂构建，进程由instrumentation重新启动，不能替代队列/系统自动唤醒验收。RTC生产排程、提醒通知及权限降级、必要竞态、A8c.4–8和A9/A10继续原序。无UI布局变化，不代替全页UI验收。先前被自动审批拒绝的旧临时副本保持原状态，不重试绕过。
- 证据 artifacts/stability-20261009-a8disk-recovery/{acceptance-index.json,termination.json,seed-native.log,recovery-native.log,regression-native.log,business-receipt.json,quality-build.log,before-device.json,after-device.json,installed-sha.txt,host.txt,reverse.txt}及控制器源码快照。


### A8c.3a.3b.4b.2b.2a 真实持久WorkManager／Hilt新进程执行（2026-10-09，限定通过）

- 从393项工作树改动接续，保留全部既有改动，未reset/clean/提交。新增仅debug编译的ColdPlanAcceptanceWorker及显式coldWorkPhase控制器；无生产源码/Manifest/缓存前缀/模型路由变更。隔离Worker固定自己的物理Room、标记文件及Work UUID，缺标记/身份不符拒绝执行，不接个人工具、提醒或数据。
- seed使用正常Application的Configuration.Provider与真实HiltWorkerFactory、真实WorkManager数据库（no_backup/androidx.work.workdb），提交60秒延迟独立Work并确认ENQUEUED后保存就绪标记。不使用WorkManagerTestInitHelper、内存队列、TestListenableWorkerBuilder或手工Worker工厂；生产DurableAlarmWorkQueue的请求类仍为AutomationWorker，本片独立请求类为debug桥接Worker，不能混称原生产入队端到端冷验收。
- 主用户UID10025准备PID30026，核对身份后SIGKILL并确认消失；等待期间仅run-as读取回执和ps观察，没有instrumentation重启、Main打开、广播、强制Job或force-stop。新PID30125中由正常Hilt创建debug桥接Worker，内部以独立Gate/模块/Room调用实际AutomationWorker→AlarmWorkExecution→守卫→AutomationModule PLAN。相同UUID 418225fb-f5fd-4526-a24a-425b77d1cb1d自动完成，本地工具calls=1、runCount=1、nextRunAt推进未来。
- 重要归因边界：采样先观察到新进程，约50秒后才收到任务回执，不能声称该进程仅由到期JobScheduler首次拉起；现有宿主/后台恢复可能先启动它。验证的是准备进程消失后持久任务在自动恢复进程中由Hilt自动执行，不是假装从未运行的冷启动性能测试。未模拟系统LMK，不证明副作用后记账前崩溃的恰好一次语义。
- 回执到达后才启动verify控制器，读取同一UUID物理WorkSpec为SUCCEEDED且独立Work只一条；1项验证通过（0.178s）。另12项原Hilt配置、模块守卫、Worker/广播接线实机回归通过（2.540s）；终止的seed日志不是成功用例。关闭并删除本片独立业务数据库/就绪文件，保留完成WorkSpec及脱敏回执，不cancelAll/prune个人队列。
- 首轮控制器两处可空WorkInfo处理编译失败已修正并保留失败日志；最终qualityCheck＋assembleDebugAndroidTest通过（51s、530 tasks，含assembleDebug）。主APK因新增debug验收桥而变化，SHA256 ad9d723555a410461a9190d5b92fd3c493a361aad08bad0a83af23c169786f43，已保留数据ADB安装并核对手机摘要，不称生产调度修复。归档 artifacts/stability-20261009-a8cold-work/ZeroV2-a8cold-work-debug.apk；benchmark/release不包含此debug Worker。
- Main和主用户specialUse宿主恢复，foregroundId2003/isForeground=true；quick_check=ok，module_configs全表摘要、个人自动化分组、动态模块state/crashCount与基线一致。本地8080转发保留；夹具拒绝模型，但正常Application后台行为仍走原配置，不能外推所有专用能力无网络。未操作旧费用账本，不读取/终止副用户私有进程、不触碰先前被自动审批拒绝清理的临时副本。
- 持久WorkManager和正常Hilt新进程创建接缝取得真实证据，2b/A8仍开放：原生产AutomationWorker请求类＋共享初始化Gate＋Receiver在同一冷恢复链的独立业务隔离证据仍需补齐；生产RTC排程/权限降级、提醒通知、必要竞态及A8c.4–8/A9/A10继续原序。无页面视觉变化，不借用旧截图替代UI验收。
- 证据本片acceptance-index.json、termination.json、observations.json、business-receipt.json、seed/verify/regression-native.log、quality-build[-final].log、installed-sha.txt、before/after-device.json、主用户host.txt及限定Job摘要、源码快照。


### A8c.3a.3b.4b.2b.2b 原生产RTC／Receiver／共享Gate联合提醒交付（2026-10-09，限定通过）

- 接续2b.2a，仅新增显式productionReminderPhase控制器；主APK不变。本片不再使用debug桥、测试WorkManager、替换Hilt工厂/Gate或手工构造Worker。使用真实生产库中唯一标注的临时提醒a8-production-cold-reminder，非独立Room库；执行前拒绝任何180秒内个人active提醒及未完成提醒Work。为纯本机系统通知测试，不安装模块、不使用外部发信工具。
- 控制器经实际DAO持久保存60秒后的测试提醒，再调用原ExactAlarmScheduler.scheduleReminder：RTC_WAKEUP、标准REMINDER_DUE/原4001身份，真实alarm dump确认触发时间。暂时替代最近一次唤醒是本片自身到期任务的正常生产排程，未cancelAll或修改个人提醒。准备PID11934核对user0 UID10025后SIGKILL并确认退出；不force-stop、不冒充系统LMK。
- 驱动进入真实Doze，在到期前后持续IDLE，仅观察进程和系统状态；无Main打开、instrumentation重启、广播注入或强制Job触发。自动恢复进程运行正常Application初始化，原Receiver→DurableAlarmWorkQueue→物理WorkManager→原Hilt AutomationWorker→生产AlarmWorkExecution/共享Gate/模块守卫完成。本轮新增Work UUID f69bf411-3836-4946-9681-a7fc9200d92e，物理worker_class_name为原AutomationWorker，state=2/SUCCEEDED；不是上一片debug桥请求。
- 交付归因如实收敛：Runtime的job_execute先开始处理到期提醒，系统通知在计划时刻后42ms登记；78ms后出现alarm_fired: REMINDER_DUE，再6ms出现Worker scan_finished。当前实例只出现一条通知、一条对应notified轨迹，原一次性测试行删除。证明现有双入口联合链交付和Worker后续正常扫描，不能声称此次副作用由Worker独立完成或已经证明任意并发恰好一次；生产代码原本明确Worker成功只代表扫描返回。
- 到期及后台观察完成后才启动verify，1项复合实机验证0.220s通过；被SIGKILL的seed不是通过用例。校验其余全部提醒完整实体摘要保持不变，随后仅取消本测试通知、删除本测试行/就绪文件，保留原个人提醒和完成WorkSpec。门禁/工厂12项上一片回归仍对应同一主APK，不冒充本轮重跑。
- qualityCheck＋assembleDebugAndroidTest通过（41s、530 tasks，含assembleDebug）；仅ADB更新控制器。主APK ad9d723555a410461a9190d5b92fd3c493a361aad08bad0a83af23c169786f43与当前手机实装摘要一致，没有新生产修复包。finally解除force-idle及电池模拟，deep恢复ACTIVE；未修改白名单、系统锁屏/字号/超时。正常Main和主用户specialUse宿主恢复，foregroundId2003/isForeground=true；module_configs摘要、个人自动化分组、dynamic_modules state/crashCount一致，quick_check=ok，本地8080转发保留。未重置旧费用账本；正常后台按现有路由运行，不声称所有专用能力无网络。
- 2b.2b按生产联合交付范围收口；A8及3b.4b整体仍开放。下一项补提醒Runtime/Worker/恢复方法并发队列和周期写入保护、独立Worker交付及精确权限降级必要证据，随后按原序推进剩余Job门禁/手动竞争、A8c.4–8/A9/A10。onAwake对过期一次性仍执行既有missed兼容规则，本次自动进程在到期前已恢复，不能外推到到期后首次恢复。无UI布局修改，不借旧截图代替UI验收；旧被自动审批拒绝的临时副本保持原状态。
- 证据本片acceptance-index.json、termination.json、rtc-before.txt、observations.json、business-receipt.json、seed/verify-native.log、quality-build.log、before/after-device.json、installed-sha.txt及主用户host.txt、控制器源码快照。


### A8c.3a.3b.4b.2c.1 周期提醒保留元数据／删除不复活（2026-10-09，修复限定通过）

- 按2c先处理可复现的周期写入缺口，使用diagnosing-bugs流程。旧实现由moduleScope异步saveReminder(REPLACE)，重建对象只带id/remark/trigger/isRecurring/recurringMs；真实模块/隔离Room两项实机红测均失败：title空、note=null、createdBy从llm变user、repeatRule丢失；通知权限检查接缝中删除行后，旧异步保存又插入该行。只用本地合成数据，强制拒绝系统通知/个人闹钟/模型，不把源码推测写成实机失败。
- 新DAO接口advanceRecurringIfActive(id,expectedTriggerAt,nextTriggerAt):Int，只对status=active且trigger_at仍匹配的现有行更新trigger_at。Room @Transaction＋周期推进Mutex保护，执行路径等待提交成功再发布下一期队列/唤醒；不重建整条ReminderEntity，不把错误吞进后台协程。保留全部其他字段，删除/非active/已改触发时间更新返回0，不重建或覆盖该行；数据库结构未改，无新增迁移。
- 2项原失败用例转绿，另1项复合DAO用例验证旧触发时间不能覆盖新排程、非active行不能被推进。相关门禁/Hilt/Worker/广播/开机恢复16项同轮回归，共19项实机通过（6.498s）。不是19项新增，也不是真实个人通知/LMK测试；真正系统通知出口沿用上片证据。本片仅保留已验证写入范围，未声称所有PriorityQueue读写已串行。
- 最终qualityCheck＋assembleDebugAndroidTest通过（1m29s、530 tasks，含assembleDebug）；前一轮2m15s构建也通过，补全Mutex/事务后以最终产物重新测试。修复APK f35d3592e7054f61c9a8353a04fd795ef88068067be557d57ff0d31cf7e17e1b已保留数据ADB安装，手机实装摘要一致；归档artifacts/stability-20261009-a8reminder-write/ZeroV2-a8reminder-write-debug.apk。
- 正常Main及主用户specialUse宿主已恢复，foregroundId2003/isForeground=true；quick_check=ok，module_configs摘要、个人自动化分组、dynamic_modules state/crashCount与开工基线一致，本地8080转发保留。不改LLM/缓存前缀/模型配置/额度账本；测试夹具不请求模型，不外推常规后台所有能力无网络。全部既有工作树改动保留，生产差异相对本片开工源码独立存档，未reset/clean/提交交错改动。
- 架构收敛：周期排程推进使用具备状态和触发时间条件的深DAO接口，将“可否发布下一期”与Room提交结果关联；调用方不再拼装易遗漏字段的全实体。仍需下一2c.2统一提醒Runtime/Worker/恢复/FC/UI队列访问，并审计一次性删除与创建异步写入；同触发时刻下并发修改重复规则仍未由本次条件独立覆盖。Worker独立交付、精确权限降级、必要竞态及A8c.4–8/A9/A10保持原序，A8不整体验收。无UI视觉修改；先前被自动审批拒绝清理的旧临时副本保持原状态。
- 证据本片acceptance-index.json、red/green-native.log、red-build.log、quality-build[-final].log、installed-sha.txt、before/after-device.json、host.txt、源码before/final及slice.diff。红测命令：am instrument --user 0 -w -r -e class com.zero.v2.app.acceptance.RecurringReminderWriteAcceptanceTest com.zero.v2.test/com.zero.v2.app.acceptance.ProviderAcceptanceRunner；红结果Tests run:2, Failures:2，绿同类3项及相关回归共19项通过。


### A8c.3a.3b.4b.2c.2 提醒统一队列边界／一次性及创建取消提交（2026-10-09，限定通过）

- 接续2c.1，按diagnosing-bugs实机红绿流程：新增3项隔离实际模块/Room红测均失败。一次性提醒在通知接缝中被改到未来，旧异步delete(id)仍删除新排程；恢复和cancel_reminder在正在处理的到期任务未释放时已完成，证明原restore锁和后台写入没有统一入口。原失败日志保存，不把审查推测当成设备复现。
- 统一taskMutex：Runtime/Worker的execute、恢复、set/cancel/modify_reminder、next_tick及UI重排的队列/数据库变更共享同一锁，周期推进复用此边界，取消传播保留。公共UI入口仍异步，由模块生命周期scope持有，但内部等待真实写入，错误留痕；FC创建/取消不再把“后台写入已启动”当作成功。创建先提交Room再发布内存队列和唤醒，取消先提交删除再移除队列并刷新。
- 一次性送达改为等待deleteDeliveredIfActive(id,expectedTriggerAt):Int，Room @Transaction条件删除只匹配active原排程，不误删同ID已重排记录。save/delete交易标注补齐，数据库结构不变。读nextRunTime使用PriorityBlockingQueue弱一致、线程安全遍历，所有模块内队列变更受共同Mutex约束；不承诺跨线程只读查询是线性一致快照或跨进程副作用恰好一次。
- 3项原红测转绿，另1项真实FC创建/取消回执立即对应已提交Room数据的用例通过；同轮周期保护/Hilt/Worker/广播/开机恢复19项回归，共23项实机通过（9.366s），其中4项新增。拒绝个人闹钟/通知与模型，使用隔离Room，未以这些用例替代真实生产Worker独立通知交付。此前生产联合通知证据另存，不冒充本轮复测。
- 初轮质量门发现行长和魔法数2项，修正后通过；随后保留UI异步入口错误处理及取消传播，以最终产物为准。最终qualityCheck＋assembleDebugAndroidTest通过（1m22s，530 tasks；含assembleDebug），日志quality-build-release.log名称仅表示最终门，不是release构建。修复debug APK SHA256 4b01e9980871c9abc35e984399e7f70d6a48f63b112f0def6618d7bdbf0ab3fa已保留数据ADB安装并核对手机摘要，归档artifacts/stability-20261009-a8reminder-queue/ZeroV2-a8reminder-queue-debug.apk。
- Main及主用户specialUse前台宿主恢复，foregroundId2003/isForeground=true，quick_check=ok；module_configs全表摘要、个人自动化分组及dynamic_modules state/crashCount与本片基线一致，本地8080转发保留。全部既有工作树改动保留，按开工源码另存本片差异，未reset/clean/提交交错内容；无缓存前缀、LLM路由、额度账本或锁屏设置变更。夹具无模型请求，不外推常规后台全部专用能力无网络。
- 防复发收敛：提醒生命周期操作共享同一串行入口，成功回执以已提交数据为准；删除与周期推进采用原排程条件，避免迟到写入破坏较新的状态。2c.2按上述接口/同进程范围收口，A8仍开放。下一2c.3继续独立生产Worker交付、精确闹钟权限降级及必要取消/进程恢复边界；A8c.4–8/A9/A10继续原序。通知副作用后的进程死亡、未知外部DAO写入和取消瞬间的事务/队列一致性尚不能由本轮保证。无UI视觉修改，不替代截图矩阵，旧被自动审批拒绝清理的临时副本保持原状态。
- 证据本片acceptance-index.json、red/green-native.log、quality-build[-final/-release].log、installed-sha.txt、before/after-device.json、host.txt、源码before/final及slice.diff。红命令为ProviderAcceptanceRunner中ReminderQueueAcceptanceTest，Tests run:3/Failures:3；最终该类4项加19项回归全部通过。


### A8c.3a.3b.4b.2c.3a 原Worker独立通知及取消恢复（2026-10-09，限定通过）

- 接续2c.2，新增WorkerReminderDeliveryAcceptanceTest。实际AutomationWorker.doWork→AlarmWorkExecution→AlarmDueDispatcher→实际ModuleLifecycleManager.safeCall→SystemEventModule；使用独立内存Room和测试WorkerFactory，ModuleInitializationGate由夹具完成，Runtime从未启动、无模型调用，个人系统闹钟服务被夹具拒绝。本片为验收补证，未发现新生产失败，未修改生产代码，因此不伪造红绿修复。
- 三项新实机用例通过：Worker独立发出一条真实系统通知并提交一次性删除，第二次扫描未增加通知；FC取消后恢复再扫描无复活/无通知；模块禁用后Worker返回failure且保留待处理行、无通知。按随机测试内容识别并清理本测试通知，未cancelAll或删除个人提醒。23项队列/周期/守卫/Worker/持久交接/Boot相关回归同轮通过，合计26项（12.03s）。
- 归因范围：证明原Worker的独立业务与通知出口，不证明生产WorkSpec物理队列在Runtime被抑制时的冷恢复。测试Worker由TestListenableWorkerBuilder及测试工厂创建，不声称正常Hilt/系统调度本轮端到端通过；先前生产联合冷恢复报告仍保留其Runtime提前送达78ms边界。
- qualityCheck＋assembleDebugAndroidTest通过（19s，530 tasks，包含assembleDebug）。仅更新测试控制器并ADB安装成功；主APK未变，手机摘要核对为4b01e9980871c9abc35e984399e7f70d6a48f63b112f0def6618d7bdbf0ab3fa，继续使用上片修复APK，不把测试控制器更新包装成新修复版本。
- 测试后正常Main恢复，user0宿主foregroundId2003/isForeground=true；quick_check=ok，module_configs摘要、个人自动化分组及dynamic_modules state/crashCount与本片基线一致。发现ADB本地8080反向转发缺失，已恢复，节点/health HTTP200；未发送模型测试请求、未改LLM/缓存前缀/额度账本。未修改精确闹钟或锁屏权限、不操作副用户95，全部既有工作树改动保留。
- 2c.3及A8仍开放：继续生产物理队列独立通知、精确权限降级及取消瞬间/副作用后进程死亡边界；A8c.4–8/A9/A10原序不变。源码现有canScheduleExactAlarms→setExactAndAllowWhileIdle间的权限竞态及外层静默catch属于待验证风险，不以源码推测称已复现。未进行全页UI视觉验收，旧被自动审批拒绝清理的临时副本保持原状态。
- 证据artifacts/stability-20261009-a8worker-reminder/{acceptance-index.json,native.log,quality-build.log,before-device.json,after-device.json,installed-sha.txt,host.txt,reverse.txt,WorkerReminderDeliveryAcceptanceTest.kt}。实机命令为ProviderAcceptanceRunner中该类＋ReminderQueue/RecurringReminderWrite/AlarmWorker/AlarmModuleGuard/DurableAlarmExecution/BootRecovery七类，OK(26 tests)。


### A8c.3a.3b.4b.2c.3b.1 精确闹钟权限竞态降级（2026-10-09，限定通过）

- 接续2c.3a，按diagnosing-bugs提取生产调度决策为AlarmSchedulingPolicy，第一版保留原canExact/精确或窗口提交/失败吞掉语义，再运行确定性故障测试。7项中4项失败：精确提交时SecurityException未降级、权限查询SecurityException未降级、降级次数为0、取消异常被吞。红XML及日志独立保存；属于生产共同策略的故障注入复现，不冒充手机实际撤销权限复现。
- 修订为权限查询或精确提交SecurityException时仅尝试一次窗口提交，窗口自身拒绝不二次提交；其他运行异常返回FAILED，取消及非运行异常继续传播。ExactAlarmScheduler实际调用该策略，沿用原RTC_WAKEUP、同一PendingIntent、4001/4002/4003身份和60秒请求窗口；不加重试循环/心跳、不把窗口降级承诺为准点。调度降级或失败使用ZeroAlarm系统日志输出action/mode/异常类，不包含提醒内容或凭据；准备PendingIntent/系统服务阶段失败同样留痕。未新增Room字段、Hilt循环依赖或模型请求。
- 修复后8项单测全绿，新增窗口自身拒绝只能提交一次的边界。debug-only访问桥只暴露共同策略给控制器；3项实机使用独立900823身份、独立action及一小时后触发时间，真正调用AlarmManager并读取系统注册window：精确分支window=0，拒绝/注入提交时撤销分支window非0；每次finally仅取消本测试闹钟。既有26项提醒/Worker/守卫/恢复同轮回归，共29项实机通过（12.331s）。手机实际精确权限及个人4001–4003身份未撤销/替换；未验证窗口闹钟实际到期送达或Doze时延。
- 中间质量门发现泛异常捕获及测试写法问题，修正后另遇一次Lint KaModule内部异常，保留失败日志；最终完整qualityCheck＋assembleDebugAndroidTest成功（2m55s，530 tasks，含assembleDebug）。不修改detekt基线/关闭Lint。最终修复APK SHA256 1491ddc178c32cc85f7ae6fe5db3663d013f35acfe0aed6d9bd7fdaa5d641c8d已保留数据ADB安装并核对手机摘要，归档artifacts/stability-20261009-a8alarm-fallback/ZeroV2-a8alarm-fallback-debug.apk，测试控制器安装成功。
- 正常Main/user0前台宿主恢复，foregroundId2003/isForeground=true，quick_check=ok；module_configs摘要、自动化status/scheduleType分组数量、动态模块state/crashCount与基线一致。DAILY到期数由0变1（诊断按观察时间计算），不能把该动态字段称为完全相等或清除个人到期任务。本地8080反向转发恢复；不改缓存前缀/模型路由/额度账本/锁屏或副用户95，全部既有工作树改动保留。旧被自动审批拒绝清理的临时副本保持原状态。
- 2c.3及A8仍开放：下一项仍需正常生产物理WorkManager队列的独立冷通知、手机真实精确权限撤销/恢复闭环和必要取消/进程死亡边界。共享策略＋实际系统注册只按上述范围收口，不替代这些端到端门。A8c.4–8/A9/A10继续原序；本片没有UI视觉修改。
- 证据本片acceptance-index.json、red-test.log/red-tests.xml、green-tests.xml、native.log、quality-build-final-device.log、安装及摘要、before/after-device.json、host/reverse、源码快照与相对开工源码slice.diff。红命令: :services:testDebugUnitTest --tests '*AlarmSchedulingPolicyTest'；红7/4、绿8/0，实机ProviderAcceptanceRunner八类OK(29 tests)。


### A8c.3a.3b.4b.2c.3c.1 冷进程原Worker独立交付／OFF残留报点修复（2026-10-09，限定通过）

- 从395项既有工作树改动接续，未reset/clean/提交。新增显式opt-in独立冷恢复控制器：仅user0，保留原agent_behavior完整JSON及updated_at在手机私有标记内，事务＋Mutex临时切OFF，75秒测试提醒，独立900824未知action闹钟20秒自动拉起正常Application。原提醒仍用RTC4001→原Receiver→DurableAlarmWorkQueue→正常Hilt创建原AutomationWorker→共享初始化Gate→实际safeCall/SystemEventModule→真实系统通知。没有测试WorkerFactory、内存WorkManager或debug桥接Worker；未改系统锁屏/精确权限/副用户95。
- 首轮明确SIGKILL后自动恢复，Runtime有loop_blocked_by_mode且无loop_started；通知1条、测试行已删除，但物理队列新增2条成功Work，原“必须1条”断言失败。第二条在约5秒后扫描旧AUTO_TICK，无第二条提醒通知。按diagnosing-bugs补隔离实际模块/Room红测：OFF且自动报点deadline已过时，提醒送达后nextRunTime仍出现新自动任务，1项失败；不把测试工厂与真实生产冷链证据混称。
- 根因是SystemEventModule的旧自动报点兜底未读已确认自主模式。修订通过AgentScheduleGateway读取配置owner；execute统一taskMutex内，OFF先清AUTO_TICK并将nextAutoTick置MAX，再正常处理显式提醒。SMART/ALWAYS_ON及既有保活策略不变，next_tick/提醒工具入口不删、不扩大为全局任务禁用。首轮新增构造依赖触发detekt参数数量门，收回到调度网关，未修改质量基线。旧SMART自动报点分支仍存在，其完整单owner/唤醒频率按后续A10另验，不能由本片宣称全部收敛。
- 最终qualityCheck＋assembleDebugAndroidTest通过（1m43s，530 tasks，含assembleDebug）。原红测转绿并与既有提醒/Worker/守卫/权限策略/Boot同轮30项实机回归通过（12.538s）。随后同一冷恢复流程及原断言重新验证1项通过：物理新Work仅1条、class=AutomationWorker/state=SUCCEEDED、通知1条、测试行不在，个人提醒完整实体摘要与当次seed基线一致，通知在Worker alarm_fired之后23ms，较计划时刻延迟173ms。
- 一次复验准备保护检查发现个人提醒临近，测试在改模式/写测试行前停止；保留guard失败日志，恢复正常Main后再执行，不调整个人提醒。首轮红链/终止/回执完整保留red-cold目录，seed被明确终止的日志不计成功测试。verify/finally和驱动独立cleanup恢复原JSON/updated_at，仅删除自己的测试行/通知/预唤醒PendingIntent；若有并发用户配置修改则拒绝覆盖。私有就绪/配置备份标记已清除，保留脱敏回执。
- 新修复APK SHA256 6fa01def1f185bc9be38063deb951088f095692ce3c54a65ab7f3a48354b6945已保留数据ADB安装并核对实装摘要；归档artifacts/stability-20261009-a8independent-cold/ZeroV2-a8independent-cold-debug.apk。Main和user0宿主foregroundId2003/isForeground=true恢复；SMART、module_configs摘要、自动化status/scheduleType分组数量、dynamic_modules state/crashCount与开工基线一致，quick_check=ok，本地8080转发恢复。动态due按时间变化不称逐值不变。无缓存前缀/模型路由/额度账本改动，夹具无模型请求；正常Application后台仍按原配置运行，不外推全部网络关闭。旧被自动审批拒绝清理的临时副本保持原状态。
- 归因边界：验证明确终止后的自动冷进程恢复及原生产闹钟/持久交接/Hilt Worker独立通知。冷进程由独立预唤醒在提醒前启动，提醒WorkSpec在恢复后由原Receiver入队；不证明WorkSpec已在终止前入队、JobScheduler唯一拉起进程、系统LMK或副作用后崩溃的恰好一次。2c.3及A8仍开放，真实权限撤销/恢复及这些剩余边界继续原序；A8c.4–8/A9/A10不跳过。本片无UI视觉修改。
- 证据本片acceptance-index.json、red-cold/*、red-queue-native.log、green-native.log、business-receipt.json、termination.json、observations.json、verify/cleanup-native.log、quality-build-gate.log、before/after-device.json、installed-sha/host/reverse、源码快照及slice.diff。命令run-independent.py驱动independentReminderPhase seed/verify/cleanup；冷verify1项、回归30项，cleanup1项为清理确认另列。


### A8c.3a.3b.4b.2c.3b.2 真实精确权限撤销／系统授权重排（2026-10-09，限定通过）

- 接续3c.1，新增显式opt-in ExactPermissionAcceptanceTest。实机原有user电池豁免与user0 SCHEDULE_EXACT_ALARM AppOp=default；电池豁免按应用ID共享，单改AppOp不能可靠制造不可用。驱动保存原状态，暂时移除共享豁免并将user0 AppOp设deny；未读写副用户私有资料、未操作其AppOp。临时OFF完整JSON/updated_at只保存在手机私有备份，Room事务＋Mutex，保护检查拒绝临近个人提醒。退出finally先恢复共享豁免，再恢复AppOp=default，并独立运行清理控制器。
- 第一次实际canScheduleExactAlarms=false、原ExactAlarmScheduler登记窗口成功；授权40秒等待失败，原状态已恢复。按diagnosing-bugs检查三候选：系统广播未到、监听未注册、重排未完成。实际记录有alarm_permission_changed: exact=true及重排，最近个人提醒nextRun=1791554400000被正确选中；失败是控制器错误要求共用4001身份继续指向更晚测试行。保留first-attempt证据，不称生产缺陷或修改生产代码。将本测试设为2分钟后，且仍要求个人活跃提醒均在3分钟保护范围之外，保证测试行确为最近项；质量门重跑通过。
- 修正后真实闭环1项通过：canExact由false变true，系统登记window由+1m28s663ms变0；grant通过主用户AppOps allow改变真实系统授权，控制器没有手工发送授权广播，生产已注册监听收到系统广播并自动重排。原4001/RTC身份沿用，未使用模拟AlarmManager/测试工厂或注入权限结果。prepare及cleanup各1项另列，不凑为业务验收数量；30项既有提醒/Worker/模式门/注册/Boot实机回归同轮通过。
- 一次重跑准备因个人提醒临近而提前拒绝，权限及配置未改变，恢复正常Main后等待正常处理再运行。每次清理只删本测试行，个人提醒完整实体摘要与当次prepare基线一致，私有配置备份/就绪标记已清除。共享电池豁免与主用户AppOp恢复原值，SMART模式及配置updated_at恢复。真实窗口到期时延/Doze仍未由本片测量，不以窗口注册冒充准点送达。
- 最终qualityCheck＋assembleDebugAndroidTest通过（36s，530 tasks，含assembleDebug）；前一轮43s也通过。只新增/修正测试控制器并ADB安装；生产源码及主APK未变，实装SHA256 6fa01def1f185bc9be38063deb951088f095692ce3c54a65ab7f3a48354b6945与上片修复一致。Main及user0宿主foregroundId2003/isForeground=true恢复，quick_check=ok，module_configs摘要、自动化status/scheduleType分组数量、动态模块state/crashCount与本片基线一致，本地8080转发恢复。全部既有工作树改动保留；无模型请求由测试发起、缓存前缀/路由/额度账本不变，不外推正常后台全网络关闭。旧被自动审批拒绝清理的临时副本保持原状态。
- 3b的真实撤销/授权重排闭环按上述设备条件收口；2c.3及A8仍开放。下一项继续已入队原WorkSpec跨终止与必要取消/副作用后恢复边界，随后回A8c.4–8/A9/A10；不复用本片为全页面UI验收。同步纠正计划首页A8c“待开始”及文档顶端过期摘要，保留历史证据。
- 官方规则依据 https://developer.android.com/about/versions/14/changes/schedule-exact-alarms ：电池豁免可影响有效精确闹钟能力。证据本片acceptance-index.json、business-receipt.json、platform-before/after.json、prepare/observe/cleanup-native.log、regression-native.log、first-attempt/*、personal-guard-native.log、quality-build-final.log、before/after-device.json、installed-sha/host/reverse及最终控制器源码。驱动run-permission.py，闭环1项＋回归30项，准备/清理2项另列。


### A8c.3a.3b.4b.2c.3c.2 已入队原WorkSpec跨明确终止恢复（2026-10-09，限定通过）

- 保留开工395项工作树改动，新增显式opt-in QueuedAlarmColdAcceptanceTest及run-queued.py，仅user0。个人活跃提醒若在3分钟保护窗口内或有未完成提醒Work则拒绝准备；原agent_behavior完整JSON/updated_at保存在手机私有标记，事务＋Mutex临时切OFF，不创建/改排个人提醒、不修改系统权限、锁屏或副用户95。
- 控制器使用原DurableAlarmWorkQueue实现及实际WorkManager实例入队原REMINDER_DUE（构造Provider提供真实manager，并非替换WorkerFactory）。原Application Configuration.Provider/Hilt创建原AutomationWorker，ProviderAcceptanceRunner跳过Application.onCreate，使共享初始化Gate待就绪。终止前必须确认RUNNING，并从真实androidx.work.workdb读出worker_class_name及state=1才发布就绪；没有内存队列、TestListenableWorkerBuilder、伪Worker或测试版业务扫描。
- UUID=6044e6c0-47ab-4bd6-9291-c38d3da1d9a2；驱动核对uid10025及准确PID后SIGKILL并确认原PID消失。另登记独立900825未知action预唤醒作为恢复兜底；实际原Worker扫描早于预唤醒计划时间约9.6秒，不能归因由该预唤醒启动，也不据此宣称JobScheduler唯一拉起进程。55秒观察期间未启动Main/instrumentation、未手工广播/强制Job或重新入队。正常Application在OFF下初始化原模块并完成共享Gate，物理队列恢复相同UUID，原class/state最终SUCCEEDED(2)。终止前attempt=1，最终attempt=2，保留真实次数，不声称只有一次Worker启动。
- verify1项通过：终止前后PID不同、原UUID不变、新增提醒Work仅此1条、真实模块守卫扫描成功，Runtime无loop_started且有loop_blocked_by_mode，个人提醒完整实体摘要不变。本项没有到期提醒，测试通知数0；证明已提交且等待初始化任务的持久恢复/扫描，不把空扫描算为通知交付，也不拼接上片75秒提醒为同一实验。seed故意终止的Process crashed不计成功测试；cleanup1项独立列出，只清理本标记/预唤醒、取消仍未完成的本UUID并恢复原配置，不prune其他WorkSpec。
- 最终qualityCheck＋:app:assembleDebugAndroidTest通过（43s，530 tasks，含assembleDebug），前轮全项目AndroidTest构建1m3s/751 tasks另列；最终清理逻辑已重编。30项既有权限策略/提醒/周期实体/Worker/守卫/物理交接/Boot实机回归通过。只新增测试代码并安装控制器，生产源码与主APK未变，实装SHA256=6fa01def1f185bc9be38063deb951088f095692ce3c54a65ab7f3a48354b6945沿用修复版。Main及user0宿主foregroundId2003恢复，SMART/配置摘要、自动化分组数量、动态模块state/crashCount与基线一致，quick_check=ok，本地8080转发恢复。测试未发模型请求，不改路由、稳定前缀、额度账本，不外推后台原应用完全无网络。
- 2c.3及A8仍开放。下一项验证取消及通知副作用后故障窗口：先独立复现当前notify→条件删除间恢复语义，再决定必要收敛，不凭WorkSpec成功推导任意副作用恰好一次。系统LMK、纯JobScheduler冷启动、真实Doze窗口送达未由本片测量；后续A8c.4–8/A9/A10按原序，UI矩阵未复用。旧被自动审批拒绝清理的临时副本保持原状态。
- 证据artifacts/stability-20261009-a8queued-cold：acceptance-index.json、business-receipt.json、termination.json、observations.json、seed/verify/cleanup-native.log、regression-native.log、quality-build-final.log、before/after-device.json、installed-sha/host/reverse、run-queued.py及最终源码快照。1业务验收＋30回归，清理1项单列。


### A8收敛S1a 同轮通知重放（2026-10-09，限定通过）

- 按《【A8收敛计划】剩余验收与交付.md》执行。新增真实Android通知出口重放测试，生产SystemEventModule＋隔离Room；反射只调用原私有通知出口，未修改个人权限、提醒或渠道，不称为真实SIGKILL/原Worker端到端中断。红3项中1项失败：同轮两次提交出现2条，另两项不同ID/周期保持独立。候选为时间ID、渠道或采样延迟，间隔100ms提交/真实列表确认时间ID是根因。
- 原出口改用zero_reminder:提醒ID:原触发时刻作为tag及固定id0，并setOnlyAlertOnce(true)；调用者传入任务原轮次。不同提醒/周期不合并，同轮在通知栏中更新。无Room结构/模型/缓存变更、不新增重试或心跳。旧三控制器清理同步用tag＋id。Android官方身份规则：https://developer.android.com/reference/android/app/NotificationManager 。用户清除后再提交仍可能新建通知，不宣称任意副作用恰好一次；声音/振动未实测，仅检查onlyAlertOnce标志。
- 最终qualityCheck＋:app:assembleDebugAndroidTest通过（4s/530 tasks；前轮2m42s另列，含assembleDebug）。实机33项通过：3通知重放＋30既有提醒/权限策略/Worker/守卫/Boot回归（15.937s）。修复APK已保留数据ADB安装，SHA256=29d3e513aaef2cd1f1e2203ef41dbe890cc3108eaebfa4d6a8afc3e794e1dcec，归档本片ZeroV2-a8notification-replay-debug.apk。user0正常Main/foregroundId2003、SMART和本地8080转发恢复；配置摘要、自动化分组数量、动态模块state/crashCount与基线一致，quick_check=ok。
- 测试未发送模型请求，不改路由/缓存稳定前缀/预算账本；不外推日常后台全部网络关闭，保留全部既有工作树与历史证据。S1a通过，S1b通知失败/条件提交失败、S1c真实故障窗口及S2–S6继续；A8未整体验收。实施进度、审计报告、验收计划和UI清单同步引用本片acceptance-index.json、红绿日志、构建/安装/摘要、before/after-device.json及slice.diff。


### A8收敛S1b.1 通知失败不伪送达（2026-10-09，限定通过）

- S1a收口后继续。真实原Worker＋模块守卫＋隔离Room，仅在隔离模块Context注入POST_NOTIFICATIONS拒绝：红2项全失败，原Worker仍返回success，原通知出口Unit吞失败后继续一次删除/周期推进。不是手机真实撤销权限测试，不改变个人权限/通知渠道。
- 通知出口现在返回提交结果；权限拒绝、系统通知关闭/渠道禁用、系统提交异常返回false，CancellationException继续传播。execute共同taskMutex内，失败条件提交markUndeliveredIfActive(id,原触发时刻)，标既有missed状态并同步刷新只读列表，然后error传播到原Worker failure。提醒实体保留、周期原规则/触发时刻保留，复用现有已错过/重新排程入口，不自动紧密重试；数据库写拒绝则原记录仍在且Worker失败。无Room字段/版本变更，无新增Hilt依赖或心跳。成功路径仍用S1a稳定轮次身份和原条件删除/推进。
- 首轮联合38项失败7项，原始日志保留first-regression-native.log：1项暴露通知拒绝异常被当作非核心模块崩溃禁用，另6项旧队列/周期夹具把PERMISSION_DENIED当成功交付。新增module-api的ModuleOperationRejectedException，执行守卫在safeCall内转明确FunctionResult.failure并记录，不增加崩溃计数/禁用；取消和未知异常仍遵循原守卫。只有可预期通知拒绝使用该类型，数据库拒绝仍真实失败。强化新测试：拒绝后模块仍operational且恢复空扫描可正常完成。旧6项夹具改为真实授权提交并按精确tag/id清理，原并发/实体断言不变；拒绝语义由新增测试单列。
- 新DAO事务仅匹配active原轮次，已重新排程或取消不覆写/复活。绿5项：一次拒绝与恢复不重复、周期拒绝原字段保持、系统服务提交异常、SQLite触发器拒绝missed提交、迟到失败不覆盖重排/删除。同轮S1a三通知与原30回归，共38项实机通过。渠道禁用分支实现但本片未改手机实际渠道；没有测notify后真实SIGKILL、提交中取消或恰好一次声音输出，不把隔离失败称为完整跨进程事务。
- 最终qualityCheck＋:app:assembleDebugAndroidTest通过，任务数/耗时以quality-build-gate.log为准；中间1m50s/530 tasks质量门不算最终守卫修订通过。初轮detekt ReturnCount/UseCheckOrError失败留存，修正实现、不改基线。APK已保留数据ADB安装并核对摘要26ac172591edd649824f0c9e6159bbf0f03f2fdb9cdab74ecc58811519b94d40，归档本片ZeroV2-a8notification-failure-debug.apk。正常Main/user0宿主foregroundId2003、SMART及本地8080转发恢复；配置摘要、自动化分组数量、动态模块state/crashCount与S1b开工基线一致，quick_check=ok。
- S1b开工前searx_search已DISABLED，不能把本片基线一致称为全部动态模块正常；具体恢复失败列入S3，证据before-device.json。全部工作树改动保留，模型路由/缓存前缀/预算账本未变，夹具不发模型请求。下一S1b.2/S1c补通知后条件提交失败、取消/真实故障恢复及必要Doze差额，再进入S2–S6；A8仍未整体验收。
- 证据artifacts/stability-20261009-a8notification-failure：acceptance-index.json、red/green-native.log、红构建及最终质量门、安装及实装摘要、before/after-device.json、host/reverse、slice.diff。5失败处理业务验收＋33回归分列。


### A8收敛S1b.2 通知后提交失败与新owner恢复（2026-10-09，限定通过）

- 按收敛索引接续，保留397项既有工作树改动。原Worker/实际模块守卫＋隔离内存Room，用SQLite触发器拒绝一次性DELETE或周期trigger_at更新；Android通知真实提交。失败后原记录完整保留、可见通知1条、Worker failure。移除本隔离触发器后新建生产SystemEventModule实例恢复：一次性原轮次按既有兼容规则转missed不补发，active周期按原相位推进到未来、全部其他字段保持。新owner空到期扫描不增加通知。首次Worker由TestListenableWorkerBuilder/夹具工厂创建，不称真实生产物理队列或Hilt冷Worker；新owner不是实际进程重启，未在个人库创建触发器。
- 红3项中1项失败：未授权通知导致的missed周期记录被新owner改回active，推进触发时刻。按diagnosing-bugs核对恢复忽略状态/失败未落库/旧实例三个候选，持久化missed已确认且新建owner，根因为restore只跳过done。改为仅恢复active；已有missed/done不重新入队，须用户显式重排。active周期相位及过期一次性转missed原规则不变；同步修正文内“周期永不missed”的过时注释。无Room结构/版本、工具协议、稳定前缀、模型路由、心跳或自主门槛变更。
- 三项新恢复实验＋此前38项回归，设备运行41项全部通过；完整qualityCheck＋:app:assembleDebugAndroidTest最终通过（含assembleDebug，耗时/530任务详见quality-build-final.log），前轮质量门另保留。未知SQLite故障仍走既有模块隔离，不将其改作预期通知拒绝；失败保留与新owner恢复的范围分列，不宣称任意通知/声音副作用恰好一次。
- 修复APK已保留数据ADB安装并核对手机SHA256 68d27c1f5fc6b0910b2878c0659cab2ba42ad5c2df9c09b619a71aa540ea4a29，归档本片ZeroV2-a8commit-recovery-debug.apk。正常Main/user0宿主foregroundId2003、SMART和本地8080转发恢复；配置摘要、自动化分组数量、动态模块state/crashCount与本片基线一致，quick_check=ok。searx_search在两端均DISABLED，S3开工前恢复差额仍开放，不称全部模块正常。测试不发模型请求，不改额度账本，保留个人数据/权限及副用户空间。
- S1b.2按隔离通知后事务故障/新owner范围收口。S1c仍须必要真实中断/Doze差额及父项证据归并，随后S2事件权限首次引导→S3能力模型/搜索恢复→S4外部连接→S5备份恢复→S6全页残留。A8未整体验收，不借用此片证明UI矩阵或实际SIGKILL。证据artifacts/stability-20261009-a8commit-recovery/acceptance-index.json、红绿设备日志、构建/安装/摘要、before/after-device.json、host/reverse、最终源码及slice.diff。


### A8收敛S2a 权限状态不误报与Termux:Boot入口（2026-10-09，限定通过）

- S1c通知后真实恢复1业务＋41回归收口；Doze窗口因DeviceIdleController屏幕前置条件未满足保留原门，按收敛索引推进独立S2。用真实PermissionCenterViewModel、Context权限查询、隔离内存Room trace/动态模块DAO；ProviderAcceptanceRunner不初始化MCP，真实MCP无activeClients，读取立即失败、不连接服务或保存配置。未修改个人授权/凭据。4项红中3项失败：SecurityException误报GRANTED、CancellationException吞掉、实机仅Termux安装却显示Boot安装。实际平台权限刷新项正常。按diagnosing-bugs核对异常分支、状态覆盖、包名混用候选，根因为catch返回true及Boot安装/入口两处查com.termux。
- 精确闹钟查询普通失败返回DENIED，取消继续传播；未增加重试或后台源。Termux工作站电池/OEM仍查com.termux，Boot单独查com.termux.boot，已装跳Boot详情、未装跳原安装指引；Manifest仅新增Boot包可见性声明。实机ADB确认Boot包未安装，新测试验证状态false与指引URL；已安装分支本片无实机证据。清理无障碍描述，把自主运行后台宿主与屏幕/手势权限区分；精确闹钟说明明确未授权窗口及休眠延期，不再承诺准点保障。未改Room结构、模型配置、稳定前缀、预算或两个保活模式。
- 原4项回归绿＋41项提醒/调度回归，实机45项通过；qualityCheck＋:app:assembleDebugAndroidTest最终通过（含assembleDebug，任务/耗时见日志）。修复APK保留数据ADB安装并核对摘要a3504c2bae0f59d6aa44e971481e2bf9aa62e32825da2ee09046a5a778c9e167，产物artifacts/stability-20261009-a8permissions-state/ZeroV2-a8permissions-state-debug.apk。Main、主用户宿主2003、SMART及8080连接恢复，配置/自动化分组/动态模块state与基线一致，quick_check=ok；searx_search既有DISABLED差额仍由S3处理。无测试模型请求，不动先前审批拒绝临时副本。
- S2a仅权限状态与入口Intent范围通过，不把ViewModel测试当生产页面授权往返或浅深色/200%矩阵。S2b接续权限中心共用ZeroPageHeader/ZeroCard、纵向大字号与错误可见；S2c云通道读取/保存/取消/草稿、实际首次入口核对。当前PermissionOnboardingScreen无导航调用，不以孤立组件声称首次引导已落地；沿实际系统权限唯一入口审计，最终S6再清理确认为过时的残留。随后S3模型能力/搜索恢复→S4外部连接→S5备份→S6父项对账。Doze窗口原门、S2其余及A8仍开放。
- 证据本片acceptance-index.json、red/green-native、quality-build-final、源码/Manifest快照、slice.diff、安装摘要、before/after-device及host/reverse。原红日志保留，不改测试断言或质量基线。

### A8收敛S2b 权限页统一与可见跳转失败（2026-10-10，限定通过）

- 系统权限页复用ZeroPageHeader/ZeroCard、线框图标、独立长描述/状态/操作、48dp最小操作与配对主题色；登录操作换行，清理旧重开无障碍描述；目标无法打开时有Snackbar和最多一个已有兜底。
- 实际展开786dp及真实分屏390dp；正常/200%资源字号×浅深色×动态色开关，共16条件。标题、Termux、Boot操作与最后注册声明可达，head/tail/end截图留档并复核；系统字号不修改。生产页真实OS往返/隔离探针刷新、原拒绝跳转及契约回归11项＋1分屏矩阵通过。
- 最终530任务质量门、APK安装及恢复通过，摘要b6a789d0c6b4c2be38cb6a4194c2aa53c5aba8bcf91f64de5ddab9ccbc8ee581；证据artifacts/stability-20261010-a8permissions-ui/acceptance-index.json及matrix-report.json。S2c云连接/加载/保存/草稿/取消与实际首次入口仍待验；本片不替代全授权组合、已装Boot或A8父项验收。

### A8收敛S2c 云通道回执、取消与草稿（2026-10-10，云区限定通过）

- Hilt受控云调用＋原zero-cloud owner；未知/错误可见，读写迟到结果保护草稿，严格匹配保存回执，取消不能撤销远端已提交。返回确认与SavedStateHandle重建通过；无个人云配置写入或模型测试请求。
- 19业务/契约＋2生产页＋1真实分屏通过；786dp/390dp、正常/200%资源字号、浅深色/动态色共16条件，最终26秒/530任务质量门通过。APK已保留数据安装并核对摘要d36c88f92f6817b877226d5c6382ead9f2b740473c6e27f9c6d9b483b921b09f。个人配置/自动化分组/模块与基线一致，宿主及8080恢复，searx_search既有DISABLED差额由S3接续。
- 实际首次路由chat和唯一permission_center已审计；未调用PermissionOnboardingScreen的归并/清理及干净首次拒绝/返回仍缺证，S2c父门保持开放。SavedStateHandle重建不等同实际进程恢复，隔离云响应不等同个人远端登录。真实Doze、S3–S6/A9/A10仍未验收，A8未整体通过。
- 证据：artifacts/stability-20261010-a8cloud-config/acceptance-index.json、matrix-report.json、first-entry-audit.json；实施进度/审计报告记录完整红绿和限制。下一步按下面固定顺序推进，不再将已通过云区重复拆分。

### A8收敛S2c 首次权限入口归并（2026-10-10，按隔离状态范围通过）

- 旧无调用PermissionOnboardingScreen已归档删除；起点chat不变，设置→permission_center统一权限owner。新真实页面/隔离新ViewModel的拒绝显示、返回设置与再次进入1项＋原19回归通过；不是独立新安装或真实OS首次拒绝弹窗。依据收敛计划允许的隔离状态范围，合并此前S2a/S2b和云区矩阵后收口S2c。
- 最终36秒/530任务质量门通过，APK已安装、摘要cc5a4ff36fe1fedf55d786feabf6d52972398eeb50c7b71b5582e30b5c0a2efb；个人数据/配置摘要、模块、自动化分组与保活恢复核对一致。原锁屏/语义查找失败留档；零测试模型请求，无缓存或个人权限改动。证据artifacts/stability-20261010-a8permission-entry/acceptance-index.json。
- 接续S3搜索restore失败与模型/媒体能力→S4外部连接→S5备份→S6逐页证据与残留对账，每片实施、测试、编译、记录后推进。真实Doze只在有效IDLE前置下补验；A8未整体通过，A9连续Live与A10长期运行独立。


### A8收敛S3 模块加载终态与窗口证据（2026-10-10，限定范围通过）

- 真实ModuleDetailViewModel、只请求唯一不存在的a8_missing_身份，原红1项0.931秒：moduleId仍空、production页面module=null只显示永久进度。根因getById=null直接return，没有状态终态。新增isLoading/loadError，开始记录请求身份，不存在结束加载并提供错误/重试；新加载取消旧Job，配置读取/迁移保存的CancellationException继续传播，销毁取消加载和runtime订阅。没有改个人模块配置写入策略或数据库结构。
- ModuleCenter/Detail接入ZeroPageHeader/ZeroCard，描述用onSurfaceVariant、去状态emoji和旧API Keys标题；模型编辑返回仍经过草稿守卫。2项最终实机4.605秒通过：不存在身份两次请求均结束，并以生产错误组件在浅/深200%资源字号触发真实重试回调。此前7项联合中6项通过，错误组件字号失败为夹具只设置第一轮、第二轮实测1.3；改为每轮设置/断言2.0，不改系统字号、不把该夹具问题称生产bug。保留module-detail-font-fixture-native.log。
- 模型编辑器补正确Surface，原孤立夹具外层未铺深色背景使标题继承错误默认前景；实际生产Scaffold不据此归因。修正后窄屏373dp八组16截图保存在profiles-narrow-final，联合回归5编辑器项均通过（含预置虚拟密钥+非法数字不触发密钥/模型回调）。用户展开设备后真实786dp八组25.743秒通过，补16截图，字号1/2×浅深×动态/固定主题不模拟宽度。只读RealMediaProviderAcceptanceTest不传runRealMedia，1项0.594秒，仅模型/能力/密钥存在布尔值盘点，未发provider请求；不是实际媒体服务质量验收。
- 媒体对账：ChatScreen的AUTO/NATIVE/TOOLS→Attachment.route；ChatBot图片显式IMAGE_INPUT+必要工具能力，无候选NATIVE明确失败、AUTO回退和不可用标注；MediaAnalysisService视频是三个采样帧交图片能力，不称原生完整视频或含声音；音频经已配置ASR转WAV；用户/LLM均可用media_analyze或attachment_export→发现动态工具，结果attachment_publish呈现，四连续工具前缀/追加历史见本轮已绿动态媒体报告。本地Qwen仍只声明CHAT/FUNCTION_CALLING，当前未加载视觉投影，云CHAT停用保持，不偷偷提升能力或开云回退。专用ASR/视觉等凭据存在不证明有效性。
- qualityCheck＋assembleDebugAndroidTest530任务/1分21秒通过；最后夹具controller另编译见module-detail-font-controller-build.log。APK保留数据已实装并核对摘要518def8c920afb59c3c038a8b762ae2b3dcfc8a962eb8fd9d0c0c3fb9a5128b4，artifacts/stability-20261010-a8s3-capabilities/ZeroV2-a8s3-capabilities-debug.apk；正常Main/SMART/触发门/配置/动态模块与profile切片后基线一致，quick_check=ok，foregroundId2003及本地8080恢复，Qwen health=ok，searx_search ACTIVE。源码工作树保留，预算账本/稳定前缀/个人权限不改。
- S3继续按有限差额推进，不标父门通过：实际分屏、模型草稿重建/返回完整路由、模块已加载页完整矩阵与凭据交叉入口，通用参数写失败/迟到保护仍需隔离证明。静态复核发现ModuleDetail flush失败可能清pending、运行配置先于持久确认变更、onConfigChanged未走safeCall，作为下一项候选，用隔离SQL拒绝复现后修订，不称已验证故障。随后固定S4外部连接→S5备份→S6全页证据；真实Doze仅有效IDLE前置补验。A8/A9/A10未整体通过，不把当前组件证据当所有页面验收。
- 证据module-detail-red/final-native、module-detail-quality、module-acceptance-index.json、窄/宽matrix与截图、media-inventory-native和a8-real-media-report.json、module-after-device/host及实装摘要。

### A8收敛S3 参数确认写入与跨模块草稿保护（2026-10-10，限定范围通过）

- 隔离内存Room＋真实ModuleDetailViewModel/模块管理器复现：SQL拒绝后运行count由3变7；编辑第一模块后切换，第二模块被写成7。修正夹具等待实际加载终态后两项仍红（2.789秒）。最早固定500ms准备断言失败不算SQL证据，随后两次误用runner的启动失败也单列保留；有效红为config-awaited-red-native-final.log。
- 草稿与防抖按moduleId持有，提交Mutex串行；后来编辑只取消待启动防抖、不取消已经提交的兄弟Job。从独立defaultConfig克隆运行配置，有限数字/整数/布尔验证后用saveConfirmed确认落库，再发布运行对象并safeCall onConfigChanged；拒绝不排入后台重试，失败保留草稿并显示重试保存。只移除仍与本批一致的草稿，旧完成不清新输入。公开Store原有带Scope构造仅供隔离夹具管理其生命周期，生产注入/后台写契约不变。
- 最终本片2项实机通过（SQL拒绝/解除后不自动写/手动重试、跨模块草稿隔离），完整qualityCheck及assembleDebugAndroidTest通过，日志分别config-green-native.log与config-green-quality.log。联合4项中本片2项及加载终态通过，错误页重试在锁屏焦点未找到按钮而失败，保留config-combined-locked-native.log，不冒充界面复验通过；上一片已有错误页通过证据仍独立。失败候选未应用/未回调；手动重试后运行/数据库7且回调一次；第二模块始终3。异常输入不输出原值或密钥。数据库已确认但模块应用失败明确标为部分成功，不能声称数据库与任意模块副作用全局原子，也不宣称覆盖其他配置owner或进程死亡草稿恢复。
- APK保留数据实装并核对SHA256=1ecf02e01c5731776f75e264f3aad81fc6356536cc3371ad7b81ecb872daaba0，ZeroV2-a8s3-config-confirmed-debug.apk；正常Main、SMART/触发门/配置摘要/动态模块与上一片一致，quick_check=ok，前台宿主2003/8080反向代理恢复，Qwen health=ok。未改稳定前缀、工具schema、模型选择、Room版本或预算账本，不发云端模型请求；共享APK代码作用于其他用户，业务验证仅user0。
- 下一步固定补S3模型草稿重建和真实分屏、已加载模块页/凭据入口对账；随后S4外部连接、S5备份和S6全页证据归并。真实Doze仍缺有效IDLE门。S3/A8父项仍开放，局部绿不代替剩余验收。


### A8收敛S3 模型草稿真实重建（2026-10-10，限定范围通过）

- debug非导出VisualAcceptanceActivity加入生产ProfileEditorPage，仅隔离回调，不连接个人模型/密钥存储。实际填回复上限1024和未保存虚拟密钥后Activity.recreate，等待新实例：1024恢复、未保存密钥为空，提交保存回调收到1024且不触发密钥写入。证明真实Activity重建/Compose SavedState范围，未假称OS杀进程、全NavHost路由恢复或完整媒体provider质量。
- 本片重建1项＋解锁后生产错误/重试组件200%资源字号1项通过，7.153秒，profile-restoration-native.log；上一片锁屏导致按钮不可达报告保留，不覆盖。首次debug夹具遗漏rememberScrollState import编译失败已修正；最终qualityCheck含assembleDebug及测试包530任务/51秒通过，profile-restoration-quality.log，未改检查门。没有改生产模型选择、缓存前缀或计数，也不发云端请求。
- APK保留数据已实装并核对SHA256=a8cd1f5c17eaea04702650b33c56cf5377d1f7ea4e1e52fe0186865cd895c879，ZeroV2-a8s3-draft-restored-debug.apk；SMART、触发门、配置摘要、动态模块与参数修复基线一致，quick_check=ok，正常Main、前台宿主2003、本地8080代理和Qwen health恢复。参数两项绿仍见config-green-native.log；完整质量门已经包含该生产修复。
- S3有限剩余固定为真实分屏模型页、已加载模块中心/详情完整视觉与长字段/凭据入口对账。已提出分屏窗口协作请求，未收到真实窗口证据前不标通过；后续S4外部连接、S5备份、S6全页归并和真实Doze依序，A8/A9/A10父项未通过。只读S4预审看到refresh缺finally与取消状态、增改弹窗保存后立即退出等候选，尚未复现/修订，不称为已确认运行故障。


### S3凭据入口静态对账（2026-10-10）

| 消费模块 | 唯一模型编辑入口 | 主凭据/兼容回退 |
|---|---|---|
| openai_chat | 模块详情 → CHAT profile | llm_client:deepseek、llm_client:qwen；本地llm_client:local |
| memory | 模块详情 → EMBEDDING profile | embedding:qwen → llm_client:qwen |
| vision_analyze | 模块详情 → VISION profile | vision_analyze:qwen → llm_client:qwen |
| web_search | 模块详情 → SEARCH profile | web_search:qwen → llm_client:qwen |
| speech_tts | 模块详情 → TTS profile | tts:mimo |
| speech_asr | 模块详情 → ASR profile | asr:mimo → tts:mimo |

- 对账来源ModuleDetailScreen、ModuleProfilesSection/ViewModel、LlmProfile.defaults、OpenAiCompatibleClient.resolveApiKey；旧全局LlmProfilesScreen没有可调用路由，实际CHAT模块身份openai_chat。ModuleDetailViewModel保留的llm_client分支没有查到现行模块metadata身份，作为确认后可清理的代码残留，不冒充实际重复编辑入口；凭据字符串llm_client:*仍是有效存储引用，不能随旧分支删除。
- 继承同一回退凭据不等于新增重复设置owner；已有独立embedding/vision/search/asr主ref保留。web_fetch的Jina密钥是网页抓取消费方，未并入LLM profile；MCP连接的服务凭据归S4独立owner。本轮只读源码与能力盘点，不读取/迁移/覆写个人密钥，不用密钥存在替代服务有效性。
- S3剩余明确为真实分屏、已加载模块中心/详情全页矩阵与长字段交互；全NavHost/进程死亡/Live连续恢复归其原父门，真实Doze仍须有效IDLE。已通过记录按产物引用，不删历史缺证说明，不标S3/A8全绿。


### A8收敛S3 父项收口与最终实装（2026-10-10，按既定范围通过）

- 依据S3原范围归并已有证据：搜索原源码/SDK socket契约恢复与实际ACTIVE；HTTP/socket交接并发及动态工具媒体四轮见原报告；模型非法输入、失败保留、父/系统返回保护、真实Activity重建；参数SQL拒绝/手动重试/跨模块草稿保护；能力声明和图片/视频采样帧/音频ASR/动态工具输入出口对账。凭据owner与兼容回退静态对账见前节，不因无调用的旧llm_client分支删除仍有效的存储ref。
- 已加载生产ModuleCenterScreen/ModuleDetailScreen以唯一隔离模块/内存Room验证，不点击个人开关。最终786dp展开八条件24截图＋390dp真实分屏八条件24截图；模型页真实分屏八条件16截图，资源字号1/2×浅深×固定/动态主题。整页长名称/长描述可读、参数区可达。超长模型显示名/模型ID/端点，另含TTS长音色，在200%浅/深实际控件保存后完整保留且凭据引用不变；都是隔离回调，不把自由音色字符串称为provider支持。
- 新发现超长标题在固定TopAppBar高度下裁切，共享ZeroPageHeader改为单行省略，完整标题仍在正文/语义中；参数控件按moduleId+field.key绑定，防跨owner复用remember草稿。这两项生产改动之后重跑最终宽/分屏整页矩阵，不把旧截图作为新APK证据。首轮真实分屏模型保存通过、随后定位取消失败；截图显示控件可见，保存后重新确认Zero窗口焦点，原八条件全部通过，未改生产页尾布局。原失败及模块同轮通过报告module-profile-split-native.log保留，不掩盖。
- 最终宽屏3项29.338秒（整页矩阵、CHAT/TTS长字段、只读CHAT/ASR盘点），最终分屏2项118.458秒（模型/模块各八条件）均无skip通过。CHAT/ASR盘点与基线一致，专用provider未实请求；完整qualityCheck＋assembleDebugAndroidTest530任务/54秒通过，包含顶栏/参数生产修复、单测与assembleDebug，s3-matrix-final-quality.log；顶栏修订质量门41秒独立保留。
- 最终APKZeroV2-a8s3-final-debug.apk保留数据已安装，SHA256=1d8f4988886bf6750beb89022a7414215d497dc3aa5b48a0803085b2580008ef与手机base.apk一致；正常Main恢复全屏，SMART、触发门、配置摘要/动态模块与基线一致，quick_check=ok，前台宿主2003/8080反向代理及Qwen health正常。最终UI运行前后zero_llm_profiles摘要一致，本地CHAT主力和禁用云CHAT不变，不新增云端测试调用，不改稳定前缀、工具schema、Room版本或预算账本。共享APK影响其他用户代码，功能核验仍仅user0；不宣称整个数据库字节完全不变或云缓存命中比例已重测。
- S3按照能力/模型与页面范围收口；全NavHost/进程死亡连续恢复、Live动效及长周期自驱沿原A9/A10，不外推本片通过。异步偏好存储的断电耐久性、任意模块副作用全局原子与真实provider语义/成本质量不由UI夹具证明。下一片固定S4外部连接，随后S5备份、S6入口/全页归并；真实Doze仍须有效IDLE单列，不因S3完成关闭。A8父项仍进行中。
- 总证据索引s3-acceptance-index.json，最终截图loaded-wide-final/loaded-split-final/profile-split-final，以及原红/绿、质量门、恢复/实装摘要。资料和现有工作树保留。


### S3真实进程恢复补验与动态恢复差额（2026-10-10，父门仍开放）

- 补齐前序明确要求的配置/草稿进程恢复门，纠正前节提前收口。首轮instrumentation到达onStop与磁盘检查点，但SIGKILL同时导致系统清理instrumentation任务；profile-process-instrumentation-first-host.log、profile-process-seed-native.log保留，属于夹具边界，不计应用恢复失败/通过。
- 普通Activity首轮已有任务保留，但最初显式启动参数不同于launcher，在旧任务上增加主页面；改为相同MAIN/CATEGORY_LAUNCHER参数从干净任务复验。ADB首次编辑还发生输入焦点偏移，准备断言及时阻止进入SIGKILL；宿主增加逐字段焦点确认和全选替换，不调整生产表单。原日志profile-process-launcher-first-host.log、profile-process-input-first-host.log保留，不把输入准备失败计为业务恢复失败。
- 改用仅debug存在、android.permission.DUMP保护的独立恢复Activity，实际生产ProfileEditorPage及LlmProfileStore；nonce限定，偏好文件独立，不写个人模型或密钥。宿主核对UID10025/PID后SIGKILL普通应用进程，再通过已有launcher任务恢复页面。回复草稿1024、模型isolated实际恢复；终止前虚拟未保存密钥已非空，恢复后为空。另一新进程原生测试逐字段核对配置等值并删除本轮独立偏好文件，OK(1 test)；报告profile-process-recovery-report.json。不是Activity.recreate替代，不声称未落盘apply、断电或全NavHost连续恢复通过。
- 最终完整qualityCheck＋assembleDebugAndroidTest通过（profile-process-shell-quality.log）；本片新增是debug/androidTest夹具与宿主证据，不再改生产UI或缓存前缀。此前最终宽屏/真实分屏UI矩阵的生产实现不变；测试入口不会打包到release，debug入口不能由普通第三方应用调用。
- 最终恢复对账曾失败：qzone_post/vision_pro在supervisor ACTIVE但Room DISABLED，profile-process-record.log原失败保留。直接分支分别是start返回空、health_check_failed，根因尚未确认，不擅自归因模型/SDK/资源。经原safeCall路径明确恢复两模块，原生1项11.171秒通过；随后正常应用单次SIGKILL冷恢复PID20215→20665，动态状态和配置摘要与基线相同，baseline-cold-recovery-report.json。追加完整质量门530任务2m7秒通过。单次通过不能推翻此前间歇失败，高频中断/启动健康检查与控制面失联仍是S3下一差额，不将父门改绿。
- 测试包/force-stop生命周期后，原已选的Zero无障碍服务不再选中；按原基线恢复该可选服务并保持其他服务选择，使用合并manifest实际完整组件名。仍有独立前台宿主运行证据；不改锁屏、显示、系统字号或超时。accessibility-baseline-restored.json及最终状态单列。
- APK ZeroV2-a8s3-recovery-debug.apk保留数据安装，手机base.apk核对SHA256=484e90e3e6f04319daa43b4d4578480288305f5e25778b2637cf8835e1c81335。正常Main全屏、本地Qwen health/8080反向代理与前台宿主2003恢复；SMART、触发门、配置摘要和动态模块与上轮基线一致，quick_check=ok，个人zero_llm_profiles摘要不变。
- S3页面/配置与真实进程恢复范围通过，间歇动态恢复差额仍待收敛；总索引s3-acceptance-index.json更新，S3父门保持开放。接续固定先复现并收敛启动健康检查/控制面返回空，再S4外部连接→S5备份恢复→S6全页证据归并，真实Doze仍须有效IDLE，A8父项、A9与A10保持开放。不以本次恢复替代云缓存命中比例、当前全部provider网络语义、全局模块副作用原子或长期自驱验收。


### S3动态模块间歇启动收敛（2026-10-10，既定范围通过）

- 根因机制已复现：ReleaseStore.start持全局RLock执行健康探测，后到请求排队超过Android普通20秒read timeout后仍可能执行，导致调用方失败、运行端后来ACTIVE的状态错位。有效红start-queue-red-final.log为“Queued start outlives client timeout and mutates runtime later”；初次假后端deployment_id准备错误单列，不作业务证据。历史qzone_post空响应/vision_pro健康失败分支与此机制相容，但没有原始请求全链证据，不称两者仅有该原因。
- start改为非阻塞取得控制面锁，忙碌立即control_plane_busy且不入后续变更队列，紧停优先。Android有界等待45秒/500ms，null或health_check_failed仅重试一次/1秒，永久契约错误不重试；start独立read130秒/call140秒覆盖服务端最高120秒探测契约，普通控制仍20秒。OkHttp异步请求绑定协程取消，取消传播，不声称能回滚服务端已接受的启动或保证任意模块健康成功。Busy耗尽仍明确失败，不无限唤醒。
- Python发布协议17项全绿；Android重试/紧停/永久错误/取消3项全绿。最终qualityCheck＋assembleDebugAndroidTest530任务1m53秒通过；此前13项及2项detekt规范失败修正，失败日志保留，不调整门槛/抑制。生产修改限定zero_release.start及MCP启动请求/重试，发布、灰度、紧停、Room版本、稳定前缀、工具schema与模型选择保持。
- user0真实原生4项无skip通过，432.032秒：并发直接start明确busy，safeCall mount等待后Room ACTIVE且echo返回7；HTTP/socket初始化重叠均完成；媒体发现/导出/动态读取/发布与连续四轮工具历史通过。实际加载与已安装SDK四文件摘要以及Debian SDK身份均与APK一致；前段耗时含SDK安全部署/既有进程恢复，不把总时长当单次交互时延。测试仅独立a8_*模块，结束停止并删除本夹具Room条目，发布审计历史保留。
- 三轮普通应用UID10025核对后SIGKILL冷恢复全部通过，其中第二轮启动2秒后再中断一次；原四个ACTIVE模块无需手工重新启用，逐轮四个dyn_mounted成功时间均晚于该轮终止开始，原DISABLED与crashCount保持，SMART/触发门/配置摘要及个人模型偏好摘要一致，quick_check=ok，观察窗口无新增dyn_restore_failed。首次三轮仅对账持久状态，不能证明新进程挂载完成，cold-state-only-first-report.json单列；补强挂载成功时点后完整三轮复验，cold-recovery-report.json才是最终恢复门。三轮不外推长周期/所有进程故障。辅助诊断曾误用SQLite列及默认编码，日志保留后修正，不计应用故障，也未递归清理旧临时目录。
- APK ZeroV2-a8s3-start-reliability-debug.apk保留数据已实装，手机base.apk摘要一致SHA256=90c0b2c7096fc311798fb15b61ab8a4c573c78169159f17a4cc61f3b63431f32；正常Main、前台宿主2003、8080反向代理和本地Qwen health正常。未发云端模型验收请求，不声称已重新实测DeepSeek缓存命中比例；共享APK作用于其他用户代码，业务验收仅user0。工作树改动保留。
- S3前序页面/模型能力/参数与真实进程恢复证据沿原索引，间歇启动差额按本轮有界机制和实机回归收敛；S3既定范围通过。接续S4按凭据owner→配置确认/刷新取消→统一页面及真实窗口顺序，详见本目录next-s4-plan.md；再S5备份、S6全页归并。有效IDLE的Doze、A8/A9/A10父门继续开放，不删除历史失败或提前收口说明。证据artifacts/stability-20261010-a8s3-start-reliability/acceptance-index.json及原生/冷恢复/质量日志。


### S4外部连接凭据owner（2026-10-10，限定切片通过，S4父门进行中）

- UI添加/编辑与LLM mcp_add_server原先均按mcp_server:显示名建引用，两个同名连接会覆写同一凭据。先提取原规则并接入实际两个入口，credential-red.log两项一红，具体同名引用相等，非编译准备失败。
- 共用McpCredentialReference：新连接采用随机ID、密钥引用按不变ID；改名/留空不动既有引用；明确填新密钥时，独占旧ref沿用，共享旧ref仅为本连接分叉，不删除/覆写其他连接的旧凭据。还检查候选ref已被其他owner引用的情况。仅本次写入决策，不自动迁移全部历史密钥；旧共享记录的原始独立密钥若此前已被覆盖，无法据此恢复。
- 4项单测全绿：同名隔离、改名/留空/独占更新、共享旧ref的新旧值独立、其他owner占用候选引用。实际UI与LLM入口调用同一规则；没有向个人MCP服务发送测试请求或修改个人密钥。连接增改trace只记ID/指纹存在状态，移除URL与密钥ref，避免旧原始token引用或查询参数进入这些日志；不宣称完成全部网络日志脱敏审计。
- 首次完整质量门1m27秒/530任务通过；为最终日志与说明改动再次运行完整qualityCheck＋assembleDebugAndroidTest，credential-final-source-quality.log通过。此前一处MaxLineLength修正，失败日志保留，不调整门槛。S3启动生产修复与SDK资源保持，稳定前缀、工具schema、Room版本、CHAT主力及云CHAT禁用不改。
- 最终APK ZeroV2-a8s4-credentials-debug.apk保留数据实装，手机base.apk摘要一致SHA256=2e16b140a61f87a859608ae3861ff6f74a14a4fd043b6bc096fc2fc068547b33。安装后四原ACTIVE模块均有晚于安装开始的新dyn_mounted，SMART/触发门/配置/原动态状态及crashCount一致，quick_check=ok；个人连接偏好、加密密钥文件、模型偏好三份SHA256前后一致。首轮安装后手机锁屏休眠，Main onStart等待初始化后已非STARTED，前台宿主2003不存在，record-s4-host-first.log与host-first-observed.log保留。核对真实PIN输入页后使用此前用户授权解锁，正常Main重新进入STARTED后2003恢复；不称安装锁屏下自动宿主恢复已通过，package替换广播恢复作为新增A8保活边界跟踪。最终8080反向代理、本地Qwen health与原无障碍选择保持。未改锁屏/系统超时/显示设置，业务验收仍仅user0，现有工作树保留。
- S4仅凭据owner纵切片通过。下一片按既定next-s4-plan.md处理配置确认/保存失败保留草稿、连接发现失败/重试/取消及迟到保护，再统一生产页面并补真实窗口矩阵；McpClientModule.refreshAllTools当前捕获Exception、UI刷新缺finally/确认保存及表单立即关闭仍是未收敛候选，不称S4完成。S5备份、S6全页归并、有效IDLE的Doze及A8/A9/A10父门继续开放。
- 证据artifacts/stability-20261010-a8s4-credentials/acceptance-index.json、credential-red.log、credential-tests.xml、最终质量/安装/新挂载记录与个人配置摘要对账。S3已收口证据独立见stability-20261010-a8s3-start-reliability/acceptance-index.json，不用当前单测替代真实连接或UI验收。


### 锁屏升级自动保活修复（2026-10-10，实际包替换范围通过）

- 使用前一已交付APK、实际user0锁屏和保留数据安装复现；安装后12次/5秒采样均无foregroundId2003，全程不打开Main、不解锁，red-real-update.log明确失败。不是模拟广播或Activity启动代替包替换。旧BootReceiver只接BOOT_COMPLETED；Main的onStart在初始化完成时要求STARTED，因此锁屏更新后没有独立宿主请求。
- Manifest及原BootReceiver加入MY_PACKAGE_REPLACED，复用AutonomousBootHost确认模式/启用态守卫、已有specialUse前台宿主和原BOOT_RESTORE持久队列；新增host_restore_requested记录真实来源和是否请求，宿主失败仍不阻断提醒恢复。未新增心跳、定时重试、无障碍要求或第二调度器；未添加LOCKED_BOOT_COMPLETED绕过首次解锁。规则依据[Android前台服务后台启动限制官方文档](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)，实际平台结果单独验证。
- 隔离Room模式守卫实机1项0.369秒通过：SMART与ALWAYS_ON允许、OFF及关闭runtime不请求，原配置不改。完整qualityCheck＋assembleDebugAndroidTest530任务49秒通过。修复APK实际锁屏替换后宿主自动出现，全程没有Main启动/解锁；随后7轮约100秒以上锁屏观察宿主持续在前台、MY_PACKAGE_REPLACED请求时间与原持久恢复完成均晚于安装开始，原四个ACTIVE模块均有本轮新dyn_mounted，配置/触发门/动态状态/crashCount不变、quick_check=ok。不是仅看旧Room ACTIVE；green-report.json、stable-report.json与stable-0..6.json留证。
- 个人连接、加密密钥与模型偏好三份SHA256前后一致；原无障碍选择保持，不靠改变用户设置造通过。APK ZeroV2-locked-upgrade-debug.apk已保留数据安装、手机摘要一致SHA256=24050bca4e1b2fc1d98629712ad33530b3ec8171cdc566369bf60568cfa2def7。本轮未打开主页面以证明广播独立恢复，开发本地模型与缓存前缀未改，也未发云端模型验收请求。当前源码和407项已有工作树改动保留。
- 本片关闭前轮锁屏安装后的自动宿主恢复缺口；实际SMART平台门通过，ALWAYS_ON/OFF为隔离生产决策门，不外推首次解锁前、用户force-stop、任意OEM/长期驻留或真实Doze准点交付。接续S4配置确认、刷新失败/取消/迟到保护及现代化生产页面/窗口验收；S4/S5/S6及A8/A9/A10父门仍开放。证据artifacts/stability-20261010-a8locked-upgrade/acceptance-index.json。

### S4配置确认持久化（2026-10-10，存储切片通过，父门开放）

- 实机隔离生产McpServerStore复现拒绝写入仍发布新配置：store-red-native.log为1项1失败，明确Rejected preference write must report failure。使用独立内存偏好后端模拟commit先改内存、磁盘拒绝，不修改个人连接或密钥。
- upsert/remove/toggle/togglePermission串行计算候选，commit确认后才发布StateFlow；失败恢复偏好内存旧值并明确报错，不静默后台重试。恢复commit同样可能失败，不据此声称跨文件/断电原子性或磁盘故障修复。
- 同一实机原生测试修复后1项0.096秒通过：拒绝新增/更新、删除和开关保持旧状态，重建Store不读到未确认候选，允许写入后手动重试成功。qualityCheck＋assembleDebugAndroidTest530任务1m32秒通过；首轮detekt要求error()替代直接抛异常，已修正且原日志保留。实际修复APK保留数据安装成功。
- UI/LLM入口尚需承接写入失败和凭据补偿，刷新取消/迟到、草稿及生产页面窗口仍待验收；本片只确认存储边界，不关闭S4，也不将孤立存储测试算作完整页面通过。证据artifacts/stability-20261010-a8s4-confirmed/store-red-native.log、store-green-native.log、quality-store.log。接续先接入统一确认保存及错误状态，再刷新与页面验收。


### S4确认保存与失败反馈（2026-10-10，保存入口切片通过，S4父门开放）

- 接续存储红/绿实机证据，将配置候选与凭据更新共用McpServerStore.saveWithCredential；沿用已验收的连接ID owner和共享旧ref分叉规则，写入串行，配置拒绝时补偿本连接旧密钥或移除本轮新增密钥，不改其他owner。补偿失败明确报错，未实现或宣称多文件/断电全局原子事务。
- 审计实际SecureKeyStore发现put只记录commit布尔值、delete用apply：现改为同一writePreferenceConfirmed，拒绝落盘先恢复进程缓存并抛失败，revision在finally结束，删除同样确认；去掉put引用/长度日志。原加密方式、兼容回退与已有凭据引用保留。JVM固定后端模拟commit先改内存再磁盘失败，拒绝put/remove/新key与手动重试均通过；不把该后端称为真实Keystore故障实测。
- 生产McpServersViewModel拆为独立owner，批量校验名称/HTTP(S)/可选64hex指纹后在IO确认保存；失败表单不关闭，明确保留草稿和重试，确认成功才回调关闭。保存和发现成功分别处理，发现失败不撤销已保存配置。刷新进入safeCall、单Job及finally释放忙态；McpConnectionActions仅隔离发现边界，生产仍进入原模块。API Key遮罩且该字段仅remember，不存SavedState；非秘密字段rememberSaveable及可滚动长表单。当前不是完整现代化页面或真实窗口验收。
- LLM添加接入相同确认owner，在IO写入且存储/凭据拒绝返回FunctionResult.failure；删除确认保存后才注销该连接工具，拒绝删除不会提前清空。工具名称、参数schema、稳定前缀、模型主力、Room版本和调度预算未改。未宣称实际个人MCP网络服务或provider语义通过。
- 原生实机3项0.282秒通过：生产Store拒绝写入不发布/重载候选、旧/新增凭据补偿/其他owner不变、生产ViewModel非法输入/拒绝保存无成功回调/手动重试确认，以及隔离发现失败后已保存配置保持。发现边界使用固定隔离actions，不代替真实HTTP失败/取消/迟到门。完整qualityCheck＋assembleDebugAndroidTest最终530任务1m48秒通过；之前风格门失败留日志并修正，未改基线或抑制新增问题。证据final-native.log、confirmed-preference-tests.xml、quality-delivery.log。
- 最终APK ZeroV2-a8s4-confirmed-debug.apk保留数据安装，user0手机base.apk摘要一致SHA256=901924fee299550c987c5409d9fdb29fab22f4277610c2ad9c3a0486cf3b3d36。最后实装保持锁屏，无Main启动/解锁，MY_PACKAGE_REPLACED宿主请求和四个新dyn_mounted均晚于安装开始，前台宿主2003自动恢复；SMART/配置/触发门/动态状态及crashCount与前轮基线一致，个人连接偏好、加密密钥和模型偏好三份SHA256一致，原无障碍选择不变。本地8080反向代理与Qwen health=ok。证据acceptance-index.json与delivery-state-*；既有工作树改动全部保留。
- S4保存入口切片通过；剩余真实网络失败/无工具/部分成功/取消/迟到保护、高德既有查询参数、未保存返回/删除确认、统一生产页面和真实窄宽/200%字号/浅深窗口矩阵继续开放。S4、S5、S6、有效IDLE的Doze及A8/A9/A10父门不提前关闭。下一步固定先真实网络可复现门，再模块刷新报告与取消保护，再页面矩阵；顺序见next-s4-plan.md。

### S4真实网络基础红绿回归（2026-10-10，基础切片通过，扩展门接续）

- 手机原生TCP/HTTP隔离夹具直接调用生产McpClientModule与其mcp_refresh_tools声明handler：原代码3项3失败，分别HTTP500仍报告成功、删除连接后迟到清单重新注册、取消先清除已确认工具；red-native.log保留具体断言，非编译准备失败。
- 网络发现暂存候选，refreshMutex串行，确认连接Store generation后在同一写入锁中发布；每连接version包含同值重写/凭据变更，旧handler执行前也校验owner。取消传播至原OkHttp call，不清未变更连接的已确认工具；finally剔除已删除/变更owner。动态工具挂载独立保留，不刷新时清空dynamic activeClients。报告区分失败与无工具，FC失败可见，UI共用报告、取消按钮owner及refresh generation避免旧finally覆写新忙态。
- 完整qualityCheck＋assembleDebugAndroidTest530任务1m50秒通过；生产网络3项加原保存3项实机6项0.845秒通过。结构/风格门失败已修正，原日志留存；删除无调用togglePermission旧方法（全Kotlin检索仅自身声明），权限字段仍由upsert确认持久化。凭据owner及工具名称/schema未改。
- 此处只关闭三个原始网络缺口的基础回归；当前正在补验部分成功/空清单/tools-list失败、同值凭据版本变更、排队刷新与生产UI网络取消状态，随后统一页面与实际窗口。个人MCP服务未发测试请求，未用固定actions代替本片真实HTTP。父S4仍开放，不声称任意注册器副作用全局原子或取消可撤回已发布操作。证据artifacts/stability-20261010-a8s4-network/red-native.log、green-native.log、quality.log。

### S4真实网络失败、取消与迟到保护（2026-10-10，网络切片通过）

- 同一实机原生TCP/HTTP夹具扩展7项1.417秒全绿：initialize HTTP500返回失败；tools/list HTTP500与有效空清单分开；部分失败保留成功连接工具并报告失败；删除后迟到清单不注册；URL/名称/权限变更的旧结果丢弃，排队新刷新仅发布新owner；同配置/ref更新凭据也改变version、旧handler不向删除owner发tools/call；取消保留未变更已确认工具；生产ManagedMcpConnectionActions＋safeCall＋ViewModel真实网络失败可见，旧取消finally不能清除新刷新忙态。
- 初始red-native.log三项三红→green-native.log原网络3项和保存3项六绿→expanded-native.log扩展七绿；不删旧失败。生产源码完整qualityCheck530任务1m50秒通过，扩展控制器assembleDebugAndroidTest240任务16秒通过。fixture不onAwake、不启动自驱或个人动态模块操作，服务/连接/凭据/注册表独立；独立TrustConfig只存在隔离instrumentation进程，测试完关闭。不使用个人MCP服务、云LLM或改变用户密钥。
- 版本检查与发布同Store写入锁，网络不占用该锁；所有刷新共用原模块Mutex。取消可阻止未发布候选，不承诺撤回已发布注册或服务端已执行操作；未知注册器异常/模块副作用不称全局原子。FC名称/schema、缓存稳定前缀不改，动态挂载计数仍合计，外部刷新不清dyn客户端。新增声明只为内部报告，不新增LLM工具。
- S4网络切片既定范围通过，S4父门仍开放，接续统一生产页面、未保存返回/删除确认、安全端点参数与实际窄宽/分屏/资源字号/主题矩阵。最终个人摘要与保留数据交付对账在页面后归并。证据artifacts/stability-20261010-a8s4-network/expanded-native.log及quality.log。


### S4外部连接完整范围收口（2026-10-10，真实网络＋统一页面＋实机窗口）

- S4既定范围通过：承接锁屏升级保活、凭据owner与确认保存证据，本轮从实机原生HTTP三项三红修复发现失败误报、取消清空已确认工具、删除后迟到清单复活。最终生产网络8＋Store3＋ViewModel1共12项2.067秒通过；覆盖initialize/list HTTP500、部分失败、有效空清单、取消/新刷新竞争、排队刷新、URL/名称/权限及同值凭据版本变更、失效handler、管理探测/保存拒绝、删除后的旧编辑草稿不能重新插入或写密钥。网络服务/注册表/配置/凭据为隔离测试数据，不请求个人MCP服务或云模型。
- 同一Store确认generation/version并在写入锁中校验发布，刷新Mutex串行、候选发布前可取消，失败保留未变更已确认工具；已删除/变更owner清理。生产操作沿ManagedMcpConnectionActions与safeCall，不新增第二注册/配置owner。管理添加/删除确认保存后才报告成功/注销；保存成功与发现失败分开，探测不把tools/list失败称为有效空清单。工具名/schema、缓存稳定前缀和本地开发模型保持原约束。
- 生产McpServersScreen现由McpConnectionScreen.kt实现，导航入口不变；统一ZeroPageHeader/ZeroCard、成对主题色、可换行长名称/地址、刷新报告/错误与取消、保存忙态、删除确认。编辑页单一标题，滚动表单与固定保存/返回按钮；系统返回和页面返回共用未保存确认。API Key仅内存remember且遮罩，非秘密草稿可恢复，含查询/userinfo/fragment的URL草稿不进SavedState。四项生产组件实机测试通过：拒绝保存留草稿并手动重试、两种返回确认、Activity重建保留普通草稿但清未保存Key、敏感URL草稿不恢复。Activity重建不外推为进程死亡恢复。
- 端点处理集中于McpEndpoint：新高德密钥只在传输时编码添加，不覆盖其他查询参数，明确更新Key时从配置URL分离该参数；旧配置留空Key保持兼容，任意同名服务不按高德改写。列表不显示userinfo/query/fragment。Endpoint JVM3项、凭据引用4项与确认偏好写入1项通过；未全面迁移旧URL中的秘密或宣称个人高德服务连通。高德端点规则依据官方 https://lbs.amap.com/api/mcp-server/gettingstarted 。
- 最终真实Fold3展开窗口[786]dp与真实Samsung分屏[390]dp，分别完成100%/200%资源字号×浅深色×固定/动态主题8组，合计16组64张原生截图（列表、编辑首段、实际滚到末端、删除确认）。所有组保存与取消删除可达，末段说明在固定保存按钮上方，成对正文/辅助文字与Surface最小对比度5.54:1。第一轮截图发现重复标题已清理；初版仅部分说明可见的断言已强化并重跑。wide-native.log四项、split-native.log一项各自通过；旧截图/中断日志保留，不作为当前最终证据。不把分屏390dp声称外屏373dp，不把主题配色断言称全像素测量或全页完成。
- 最终qualityCheck＋assembleDebugAndroidTest530任务47s通过；未新增风格抑制/降低门槛。ZeroV2-a8s4-final-debug.apk保留数据安装，user0安装base.apk摘要一致：bb818f58745bd8e6675480d7f0073fecb9b8e3131d19e283df8051fe4fe2d6b3。最终安装保持锁屏，无Main启动或解锁，MY_PACKAGE_REPLACED前台宿主2003自动恢复，当前八个ACTIVE模块均有晚于本轮安装的新挂载；behavior/triggerGates/configHashes与历史基线一致，动态状态/crashCount与恢复后的当前基线一致、数据库quick_check=ok，个人连接/加密密钥/模型偏好三份SHA256一致，原无障碍选择不变。8080反向连接与本地Qwen health=ok；测试不切个人模型或付费测缓存命中。
- 证据统一：artifacts/stability-20261010-a8s4-ui/acceptance-index.json、final-native.log、wide-native.log、split-native.log、quality.log、wide/split矩阵与截图、delivery-state-*、delivery-install.log、delivery-personal-prefs.log、三份JVM XML；原红绿网络证据保留于artifacts/stability-20261010-a8s4-network。既有工作树改动全部保留，不混合提交。取消不保证撤回服务端已执行请求/已发布注册，跨文件断电与任意注册副作用不称全局原子。
- 历史模块基线不能直接复用：15:24生产ui_toggle明确启用bili_downloader/image_gen/mimo_tts/web_fetch，四项选择保留；16:06 qzone_post自动恢复收到resp=null并落DISABLED，supervisor却仍ACTIVE。第一次最终交付核验因此失败，first-delivery-*及delivery-audit/toggle-audit留证。qzone_post沿生产动态页重新启用，ui_toggle stopping=false ok=true、Room ACTIVE及新dyn_mounted确认；再冻结before-final-delivery并实装核对八模块，不覆盖用户新设置。这个新空回包/运行与挂载状态不一致缺口只完成恢复，根因未确认，不归因本轮刷新修订，也不声称S3启动可靠性已覆盖全部新条件。
- S4按既定外部连接范围验收收口。优先接续S3新增启动空回包差额：隔离真实异常响应及取消复现、沿原启动/注册owner有界对账，保护明确停用状态，再验证当前八模块冷恢复。随后S5备份恢复：审计唯一备份owner与真实进度/取消/错误，使用隔离库和输出文件验证数据保护，再统一页面与实际窗口；沿用A3已有事务证据，缺证部分补验。随后S6全入口/残留/父项证据对账；有效IDLE的Doze、A8/A9/A10/A11仍开放，不以S4关闭替代全应用验收。


### S3新增启动空回包差额收口；S5接续计划（2026-10-10）

- 实机隔离真实supervisor接受启动后，代理丢失成功正文：旧代码回归红（运行ACTIVE但挂载失败），不是由健康页推测根因。空正文/无效JSON/HTTP失败原先压为null，盲目重启并将Android状态置DISABLED，形成运行与挂载差异。历史qzone_post那次null的具体网络原因无法追溯，本轮确认并修订的是这个确定的处理缺口。
- Android启动沿原safeCall/模块锁增加请求ID，遇不确定回包查询GET start-status只读回执。supervisor仅接受本次请求且仍为同一pgid/endpoint、ACTIVE、enabled、活进程的回执，拒绝旧请求/明确停用/急停；回执仅内存保留，不保证supervisor重启后恢复。无法确认时返回可诊断的不确定失败，保留此前意图，不盲目重复启动、不采用health旧端点、不把未确认挂载声称成功。成功后仍须initialize与工具清单核验才注册。
- JVM回执4＋既有重试3共7通过；SDK33项中Windows原生进程7项跳过，另有真实Debian/Android成功空回包实机1项58.569s通过，工具echo可调用且仅一次start。第一次绿色实机尝试因supervisor不可用在发布前置失败，保留green-native.log；补齐ensureSupervisor前置后green-native-final.log通过，未放宽故障断言。
- qualityCheck＋assembleDebugAndroidTest 751任务49s通过（含各子项目测试APK构建，不等于751项测试）；补充JVM测试最初6项风格问题已修复，无新增抑制。锁屏保留数据实装APK e034e29af2794a168d03a6e68ad249a37c95f1ffa0fa07b89559af21afd33015，user0安装摘要相同；八个当前ACTIVE模块均新挂载且supervisor同为ACTIVE，配置/行为/触发门及个人连接/密钥/模型偏好摘要一致，SDK运行/安装资产四项摘要一致，Debian工具SDK一致，本地模型health=ok。
- 证据artifacts/stability-20261010-start-reconcile/acceptance-index.json、red-native/green-native-final、quality-final、receipt-unit、sdk-tests、delivery-*；工作树原改动保留。S3新增空回包差额按上述请求回执范围关闭，不外推任意网络隔离/全局副作用原子或云缓存比例。
- 下一片S5计划已落artifacts/stability-20261010-a8s5-backup/next-s5-plan.md：唯一备份owner、阶段/取消/错误与恢复确认→隔离库/文件保护回归→统一页面及真实宽/分屏矩阵→编译实装与记录。现有备份五域，附件实体/知识库/密钥/模块配置/图谱不包含；SAF提供方原子覆盖不保证。S5/S6、有效IDLE的Doze及A8/A9/A10/A11继续开放。


### S5备份恢复既定范围收口及S3补充修订（2026-10-10）

- 用户确认规则：合并恢复保留本机当前角色；本机无当前角色时采用备份中的当前角色，其他角色完整导入。同ID沿原合并规则更新内容，唯一当前角色的选择与五域恢复在同一Room事务中。早期冻结控制器缺新方法导致零项执行（role-red-no-case），未计入通过证据；换用含用例控制器在旧APK运行，role-red-native复现两个活动角色；最终回归验证本机优先、空库采用备份及后续写失败整体回滚，未恢复个人库。
- 唯一data_backup路由和DataExportViewModel操作owner沿原Hilt注入。真实阶段进度、重复操作保护、读取/准备取消、写入/事务阶段禁止UI取消、finally收尾和operationId迟到保护已实现。选文件后确认恢复，忙碌返回保护、错误重试及统计沿统一Zero页面/卡片主题。
- 修复已有数据遗漏：会话summary/summaryUpto、自动化execMode导出恢复，合并消息后的messageCount按实际行数重算；fields-red-native/count-red-native保留原生红证据。非法记录跳过统计红证据red-native保留。独立真实Room五域往返验证摘要位置、消息来源/附件引用元数据、记忆来源/状态/删除保护、角色及暂停任务REACT模式。取消/未来版本/写前取消保护原文件、同ID合并/本机独有保留均通过。
- S3补充：ok=true缺有效endpoint或ok类型错误也必须按不确定回包走请求ID回执，不当确定成功；回执4＋重试3项JVM全绿。动态模块ACTIVE徽章改“已启用”，明确Room意图不是当前运行/挂载证明。真实成功正文丢失仍只start一次并注册可调用工具；历史qzone_post那次空回包确切传输原因无法追溯，不声称任意控制面故障均修复。
- 最终原生门final-data-start-native-role.log共12项通过：备份数据库8、操作3、真实启动丢回包1，61.162s，采用最终角色修订APK。quality-role-final.log质量检查及测试APK构建530任务通过；加强角色事务回滚夹具后role-rollback-controller-build.log再次构建240任务通过，任务数不是测试数。临时QQ启用态恢复另有显式参数保护的生产owner实机1项通过（qq-resume-native，2.513s），恢复辅助测试不是默认回归；追加测试控制器构建recovery-controller-build通过。
- 真实页面门：wide-native-final.log 2项（真实SAF两种选择器取消＋展开屏矩阵）；split-native-final.log为8次分组实机调用，每次1项。实际786dp/390dp窗口各8组浅深色×固定/动态色×100%/200%资源字号；每组空闲/确认/读取/提交/错误/完成/尾页7张，共112截图。确认、取消、提交保护、忙碌返回和尾部可达均通过，配对正文/卡片对比度≥4.5。字号由测试宿主覆盖，未改系统字号/锁屏；分屏是Samsung真实窗口，不是外屏或模拟窄宽。
- 页面验收APK7c02ef2813556996e0c19f8dac9e0fecdd838df8435efe7ed3fc84a04ff6596e另存ZeroV2-a8s5-ui-tested-debug.apk；之后角色导入/模块状态标签修订未改变DataExportScreen源，数据门在最终APK另验，不混淆产物。热状态4暂停UI，后续短组等待机身≤40.5℃且热状态≤2；既有授权下临时停止Termux并暂停user0测试宿主，随后恢复；未动user95宿主，最终前台1337及八模块已核对。本轮无性能测量，不改变资源门，另有四个QQ残留原生进程临时挂起并按PID/启动时间恢复；恢复后确认旧代仍占用CPU，依既有临时停止授权按同一身份结束旧代，仅保留已恢复的当前模块进程。模块停止后残留高CPU作为S6候选，保留降温及失败日志。
- 最终ZeroV2-a8s5-final-debug.apk SHA256 `5db26ca500bb8f897d577f2a999d9f76cd683804b83e880652fc8d15ef56f11a`，锁屏adb install -r成功，user0实际base.apk摘要一致。八个原ACTIVE模块运行ACTIVE且本次安装后均新挂载、宿主请求新鲜；个人三偏好、配置/行为/触发门/模块意图摘要保持一致；运行/安装SDK四资产及Debian工具摘要相同，本地Qwen health=ok且ADB8080反向连接保留。证据artifacts/stability-20261010-a8s5-backup/acceptance-index.json及delivery-install.log；首次锁屏比对发现临时QQ暂停期间Android意图被健康检查置DISABLED，不能通过；保留delivery-first-*，沿生产mount owner恢复ACTIVE并重做安装核验，未直接改个人数据库、未清数据或重置工作树。
- 边界：五域不含附件实体/知识库/密钥/模块配置/图谱/完整提取作业链。32MiB导出检查发生在完整快照分配后，不是全流程内存上限；SAF失败可能留下部分文件，不保证任意提供方原子覆盖。旧备份缺失字段不能重建；向量不在备份内，导入后立即自动重建仍缺证。真实SAF取消和生产确认分别验收，不声称完成选文件到个人库恢复；未测云缓存比例，未改稳定提示前缀或用户模型配置。
- S5在上述既定范围关闭。S6只读预审列出22入口（18显式路由＋4底部根页），备份路由/owner唯一；接续冻结S0–S5索引→父门/残留对账→仅补缺证。前次联测发布测试模块无响应发生于故障注入前，final-data-start-first.log保留；同产物重测通过，确切原因未知，单列S6控制面差额，不归因温度。最终安装已通过挂载/配置/摘要后一次health查询连接提前关闭（delivery-health-first），保留失败；以本次安装的host_restore_requested时间作更严格新鲜度界重新只读核验，不重复安装，SDK身份及八模块运行最终通过。有效IDLE Doze、S6及A8/A9/A10/A11开放，见next-s6-plan.md和s6-route-owner-preaudit.json。


### S6a QQ进程归属与高CPU诱发修订（2026-10-10，限定通过）

- 实机闲置QQ约3% CPU；真实stop返回ok后，两项事先证明归属qqbot的旧子进程仍活、各约99% CPU（qq-stop-red）。PRoot内外会话不同，原Runtime._kill直接KILL外层组，遗漏内层tracee；不是据进程名猜测归属。独立会话最小原生夹具4项先红3项（停用残留、死根恢复、崩溃先回调），身份变化/无关进程保护项通过。
- ProcessRuntime统一按内核PID启动身份和UID保存后代归属：启动确认/停止重扫＋单一共享0.5s跟踪，退出进程记录被裁剪，仅归属变化才写journal。先TERM/KILL工作进程，确认退出再停PRoot与外层；崩溃先清旧代再回调，恢复可清理已登记的死根后代，逐次验证身份，拒绝无关或PID复用。Supervisor退出沿同一runtime owner，保留enabled意图；超时/服务受控结束也采用归属树清理。尚未观察/登记且已脱离根的进程不声称可识别；同UID不是完整沙箱。
- ownership-native-green四项Linux原生通过，qq-stop-green当前真实QQ停止后旧代存活数0，原SDK路径恢复成功。quality-qq 530任务/1m9s通过，27项SDK脚本Windows4项原生跳过，另有四项设备原生补验；跳过不算通过。修订APK b0711f4d6c7ced3626466d7a822510aed91f1259e077351d0d3787caf0efea0f实装，SDK运行资产与源码一致，行为/触发门/个人模块配置/模块意图与开工快照相同；未改提示前缀或云模型配置。
- 证据 artifacts/stability-20261010-a8s6-qq-control/，未整体关闭S6/A8；继续S6b控制面异常空断差额。真实HTTP处理器夹具3项红：两项RemoteDisconnected、一项HTTP/Socket错误契约不一致；历史瞬断的具体触发未确定。QQ消息端到端、长期资源/Doze仍非本片证据。


### S6b 控制面异常回包及S6证据冻结（2026-10-10，限定通过）

- 生产HTTP Handler提取夹具先红：内部RuntimeError使GET/POST直接RemoteDisconnected；Socket返回原始异常文本且契约不同。新增control_route/control_error，未开始响应时返回脱敏JSON 500及error_type/operation；POST结果统一uncertain=true，不伪装成已确定未执行。已开始响应/对端断开时关闭连接，不追加第二组响应；不自动重试副作用。HTTP与Socket一致，日志不含原异常正文/请求正文/查询值。此处证明异常处理机制，不证明历史每次瞬断均由同一异常触发。
- 最新Linux原生7项通过（进程归属4＋控制面3，8.360s），Windows共30项、4项仅Linux跳过，实际26项通过。qualityCheck＋assembleDebugAndroidTest 530任务/41s通过，包含assembleDebug；最终APK上真实Room/操作取消/生产启动成功正文丢失共12项通过（36.422s），测试隔离库，不恢复个人资料。
- 最终QQ停止后旧代存活0；新模块沿生产SDK启动恢复。原红证据qq-stop-red.log保留2项归属明确的残留各99.47/98.97% CPU；最初同名qq-stop-red.json被绿色复验覆盖，不再用该JSON作红证据，后续脚本以label分别输出。最终活动QQ短时采样38.5/43.9/32.6% CPU，不能称长期低CPU或消息端到端验收；本轮修复残留诱发的多核高占用，活动资源继续跟踪。
- 实装后首次health仍提前关闭，保留control-live-first-after-install.log及control-first-state；发生在恢复窗口，不能凭时序断言全部根因。之后50/50健康请求监督PID不变，2/2真实非法读取参数/非法proc PID请求返回预期脱敏500，随后health正常；非法PID未执行kill。有限连续请求不是长期网络稳定性或无升级空窗证明。
- ZeroV2-s6-final-debug.apk SHA256 `de52201001f12c53f72bc65945d1af0bd74cc8f92f062ae6fd13973667594a1d` 已adb install -r成功，user0 base.apk一致；运行/安装SDK四资产与当前源码及Debian工具一致。八个原ACTIVE模块运行ACTIVE且安装恢复后新挂载，配置/行为/触发门/模块意图、三项个人偏好文件摘要一致，quick_check=ok，本地Qwen health=ok与ADB8080连接保留。未改稳定提示前缀、未测云缓存比例、未调用付费模型、未清个人库/工作树或改变副用户配置。
- S6证据冻结：s6-route-parent-audit.json列18显式路由＋4根页，无重复路由；备份入口/owner唯一，自动化创建沿列表父owner，无旧PermissionOnboardingScreen文件。90份既有A8验收索引按原范围和摘要冻结，逐项记录源码仍匹配或已变化；历史索引不是本轮重新运行。更新UI台账已取得的权限页/云区与MCP页状态，未把所有页改绿。
- 有限后续：S6c按22入口逐页映射正文/弹窗/首尾/恢复证据，仅补当前确切缺证或源码变化；S1有效IDLE Doze送达独立保留；A9连续Live/A10长周期/A11最终全回归保持原门。S6a/b限定通过，S6/A8父门仍开放。证据 artifacts/stability-20261010-a8s6-qq-control/acceptance-index.json、s6-route-parent-audit.json、红绿日志及delivery-result.json。


### S6c.1 活动QQ重复冷启动、升级恢复就绪与逐页证据对账（2026-10-10）

- 根因及范围：真实QQ启动曲线在15秒出现232.8%多核占用，45–75秒约37–45%；热点为QQ utility进程MSFNG线程，Python父进程占用低。更久闲置可自然下降，不能据此认定永久忙循环。另确认ReleaseStore恢复已启动模块，Android自动挂载又无条件stop/start，使QQ重复冷启动；未改手机QQ shim/core或个人配置。
- 自动恢复修订：仅自动挂载显式reuse_active=true。复用必须同时通过完整release配置、实际PID启动身份、入口源码摘要、release检查、RPC工具健康及同一活动generation校验；显式重启仍重启，不匹配则正常启动。三次真实自动挂载QQ均保持PID8301及一致请求回执，所有活动模块PID不变；最终12项instrumentation前后活动PID亦保持。入口摘要不覆盖所有外部import依赖，不能外推成全依赖热更新校验。
- 升级恢复修订：控制面先监听再恢复，health明确starting/recovering/ready/failed；未就绪非health调用返回503 supervisor_not_ready、uncertain=false，不执行操作。Android区分alive/ready；恢复期部分状态不作为权威CRASHED，不误停用。旧owner以真实UID、完整脚本路径、PID启动身份等待实际退出，不能以health失联代替退出；使用已校验staging helper兼容旧SDK升级。最终升级有23个恢复中采样，拒绝调用保护实测503，仍捕获1次旧新监听停启边界EOF，零空窗及全部历史瞬断原因不作通过结论。
- 最终验证：Windows发现38项、9项Linux条件跳过；Debian隔离SDK15项通过；就绪/状态JVM6项通过；最终APK真实Room备份/取消/迟到及启动丢回包12项通过（41.149s）。qualityCheck、assembleDebug及测试APK构建530任务通过（2m，任务数不是测试数）。红证据startup-red、runtime-readiness-red、reattach-red，绿证据native-final、sdk-windows、quality-final、final-data-start-native均独立归档。
- 交付保护：最终APK SHA256 421e2bc75d44ab33cfb68e44992baa0c32ccfa38d211da3b6ed9e5d143a98c13，已通过ADB覆盖安装并核对user0实际base.apk摘要；测试后再次核对SDK四文件、原八模块ACTIVE及新挂载、个人配置和偏好摘要一致、前台保活2003、本地模型健康。最终QQ在线无错误，闲置三采样2.8/2.4/4.2%，未发QQ消息；短采样不是长期资源门。未调用付费模型，未改稳定提示前缀或缓存机制。
- S6c逐页父项：22入口已对账，17页仅匹配当前Screen源码与限定历史证据，不能据此宣称共享主题、弹窗、首尾、窗口及恢复全通过。live_session、library、automation、modules、trigger_settings五入口仍需当前源绑定/差额验收；modules已有S3真实窗口证据，缺绑定不等于从未测试。报点设置页确认独立旧页头/卡片、搜索草稿未保存、配置状态误写“运行中”和横向大字号拥挤，本片只登记，尚未修订。
- 后续固定顺序：S6c.2先修报点页统一组件、真实状态语义、草稿与大字号布局，完成编译/回归/所需真实窗口；S6c.3归并根页、模块、Live的当前源及产物证据，仅补确切缺证，不全量重复17页旧矩阵；之后逐页核对正文/弹窗/首尾/恢复父门。有效IDLE Doze及A9连续Live、A10长周期自主、A11最终全回归独立保留。S6/A8父门未验收。
- 本片证据与最终APK：artifacts/stability-20261010-a8s6c-active-recovery/acceptance-index.json、s6c-page-coverage.json、upgrade-summary.json、reattach-live.json、delivery-result.json、ZeroV2-s6c-final-debug.apk。


### S6c.2 报点设置页修订验收与S6c.3五页证据差额归并（2026-10-10）

- 本片对象为既有trigger_settings「事件感知」路由，仍使用TriggerSettingsViewModel、TriggerWhitelistStore及SystemEventModule提醒owner；未增加设置入口或新写入链。页头采用ZeroPageHeader，配置卡和提醒卡采用ZeroCard及主题配对色；配置状态纵向分行，提醒操作独立一行，应用行单一Checkbox语义和至少56dp高度，长标签允许换行，移除重复推荐点击控件。旧说明中的“模块管理”改为现有“能力与模型”入口。
- 文案修复：配置、白名单不能证明服务健康，原“运行中”改“已配置 · N个应用”；另明确“待配置 · 尚未选择应用”和“未启用 · 通知感知已关闭”。说明实际接入取决于系统通知权限及后台状态，不伪造运行结论；通知源开关进入/恢复页面时从原配置owner刷新，并订阅StateFlow重组。旧逻辑两项JVM先红，修订后两项通过。
- 草稿与操作：搜索改rememberSaveable；抽取实际生产TriggerSettingsContent用于隔离验收，真实Activity重建后搜索内容恢复，筛选目标可见，整行单次选择只回调一次，提醒删除/重新安排走既有owner。首轮脚本误匹配搜索框的相同文字导致toggle断言失败，修正为唯一包名目标；trigger-native-first.log是脚本定位失败，不是生产功能红证据。
- 实机证据：trigger-native-final.log在最终应用APK上一个测试方法内完成786dp展开屏八组（浅/深×固定/动态×100%/200%资源字号），每组配置态、草稿恢复、停用态三图，共24图；23.477s通过。trigger-native-split.log补真实390dp multiWindow、200%固定浅色一组，操作及Activity重建均通过，48.213s含等待用户恢复分屏，共3图。截图确认字色和背景可读、配置说明换行及提醒操作可达；分屏首图右侧仍是系统配对应用选择器，不宣称全程计算器已就绪。未改系统字号/锁屏设置；此处不替代进程死亡或全部提醒生命周期/权限丢失矩阵。
- S6c.3五页对账：trigger_settings已绑定本片源和产物；library/automation由root-navigation-native.log补当前真实MainActivity启动、资料与工作区（包含系统状态）、外观返回和偏好不变，1项7.877s通过、4张截图；modules由module-loaded-native.log补当前已加载中心/详情八组786dp主题/字号，1项13.271s通过、24图，隔离长字段且未动个人开关。历史A7矩阵与S3真实分屏/错误/配置证据单独引用，不以新宽屏结果替代。live_session仍缺当前运行绑定，保留A9连续动效/媒体/退后台/恢复门。本轮由17/22当前页面源码绑定推进到21/22，绑定不等于21页父门全通过。
- 质量及交付：qualityCheck、assembleDebug及测试APK构建通过，530任务、59s；增加窄屏测试参数后的控制器编译240任务、5s通过，应用APK未变。最终APK SHA256 83c305b86759e19223039164bdff0e68bd3c24df036fdd3a460915ca4c8d89c8，ADB覆盖安装成功，user0实际base.apk摘要一致。测试后SDK四文件与源一致，原八模块ACTIVE且挂载刷新；个人配置/密钥/profile偏好摘要与上片冻结基线一致，前台保活2003及本地模型health正常。隔离页面不调用provider，未改提示前缀/缓存链；真实主页面后台流量未做成本计量，不声称绝对零后台token。
- 有限后续：S6c.2按本片UI/配置文案/Activity草稿及必要宽窄窗口范围通过；S6c.3继续逐页核对共享依赖、弹窗/首尾及恢复的确切差额，Live归A9独立门，不能靠源码绑定计数关闭S6/A8。有效IDLE Doze、QQ长期资源/停启空窗、A10长周期自主及A11最终全回归保持既有边界。
- 证据目录：artifacts/stability-20261010-a8s6c2-trigger-ui/acceptance-index.json、five-page-parent-audit.json、device-fixture、device-followup、delivery-result.json及ZeroV2-s6c2-trigger-debug.apk；红/失败尝试完整保留，未清理既有工作树。


## 2026-10-10 S6c.3父项差额对账与A9 Live首轮验收

- S6c.3逐页冻结22入口正文、弹窗/Sheet、首尾、窗口、恢复及已记录共享依赖差额，见 `artifacts/stability-20261010-s6c3-a9-live/s6c3-parent-gap-ledger.md` / JSON。初始绑定21/22；本轮新增Live限定证据后当前Screen源码绑定22/22。源码绑定只证明索引对应当前页面，不能代替每页完整父项验收。
- A9.1：Live使用统一页头、轻量转写卡、主题渐变状态球和自适应控制栏；状态球按可见活动/减少动效/弹窗覆盖决定循环，补THINKING。白名单搜索/勾选/弹窗开关可随Activity重建恢复，确认后重开使用已确认集合。保留原ViewModel、语音服务、权限、投影及白名单owner；返回和挂断语义仍分离。状态颜色仍即时切换，平滑过渡后续补验。
- A9.2：动效策略3项JVM红绿验证；最终生产修改qualityCheck成功（530 tasks），app/test APK编译成功。真实786dp展开屏八组100%/200%×浅深×固定/动态色，六状态、六控制回调、草稿重建及确认重开通过；两个原生方法59.628s。真实窄分屏 390dp、200%固定浅色，六状态/控制回调/白名单Activity草稿恢复及确认重开通过。
- 实际活动窗口237个FrameMetrics样本，总时长P95=14.714326ms；生产参数减少动效覆盖注入后状态球像素静止；实际moveTaskToBack后清空尾帧再观察700ms，新增帧0。这是隔离生产渲染器诊断，不是正式性能门、全局系统减少动画设置或真实语音服务停止验收。12秒录屏及截图已归档，不把录屏当无掉帧结论。
- 窄窗口首轮在Samsung对侧应用选择器尚未退出时提前开始，未找到屏幕阅读状态；第二轮取焦点点击关闭恢复中的弹窗，未找到搜索框。测试脚本增加交互窗口可见性、等待计算器就绪、状态取焦点和恢复后焦点判定；全部失败尝试保留，不用多次重测抹除失败。中间缺交互窗口flag的控制器尝试已主动终止，不计为通过。
- 主APK `artifacts/stability-20261010-s6c3-a9-live/ZeroV2-a9-live-debug.apk` 已安装，SHA256 `2465276dfe7dddeb548f0d0ba868fb15c74c5b89b10339e39ffa2e5eb12e9b95`。最后SDK/八原活动模块/新鲜挂载/宿主foregroundId2003/本地Qwen健康及配置摘要核验见delivery-result.json；配置与模块基线来自上片S6c2冻结after-device，密钥/profile/MCP摘要来自本轮真实安装前采样。不是全库内容一致性证明。没有付费provider请求，个人资料及现有工作树改动保留。
- 隔离控制器结束后正常宿主尚未恢复，delivery-after-narrow.log的foregroundId断言失败；主动拉起正常MainActivity后delivery-restored.log核验通过。测试应用初始化刻意跳过正常宿主，不能将此隔离夹具状态当生产自动恢复验收，也不删除该失败记录。
- A9/S6/A8父门继续开放：真实麦克风和投影权限拒绝/撤销、实际语音/服务媒体生命周期及挂断释放，折叠/IME/长转写与进程死亡连续性、状态颜色平滑过渡及正式资源门；其余逐页父项差额按台账补证。有效IDLE Doze、QQ长期资源/停启空窗、A10/A11仍独立。下一步按next-a9-plan.md先完成A9.2窗口/连续恢复差额，再A9.3真实服务资源，禁止用UI回调替代实际媒体验收。
- 完整范围、源码/产物摘要和失败路径见 `artifacts/stability-20261010-s6c3-a9-live/acceptance-index.json`。本轮S6c.3台账已交付，父项缺证补验持续进行。


## 2026-10-10 A9真实媒体权限/资源与IME、物理折叠连续恢复

- 真实拒权红证据：Android 15在RECORD_AUDIO拒绝时，LiveSessionService.onCreate仍启动microphone FGS，触发SecurityException。第一次仅服务内部拒绝又复现ForegroundServiceDidNotStartInTimeException，两个失败均保留。最终在Live启动入口前置检查授权，服务内部检查授权/捕获前台权限SecurityException，并以startupRejected在onStartCommand停止正确startId。拒权不挂起前台服务，给出重授权提示。
- 生命周期修订：销毁时先取消初始化/收集，再同步停止provider、投影和overlay；初始化结束后ensureActive与serviceDestroyed守卫阻止已挂断会话晚启动，provider未活动或启动异常则结束服务。投影FGS升级进入异常清理范围；系统授权Activity重建不重复拉起授权窗口。快速启动/挂断竞态压力与前台资格竞态尚待独立验证。
- A9.3a实机：真实权限拒绝后跨12秒前台启动超时观察，无服务/AudioRecord/投影残留，media-denied-final.log 13.654s通过。实际Hilt LocalVoiceProvider录音开启，真正系统投影取消保留会话、授权并选择Zero后VirtualDisplay/ImageReader创建，实际MediaProjection.stop触发系统停止回调清理，二次授权创建成功；退后台会话保持，挂断服务和AudioRecord/投影资源释放，500ms后无晚重开。media-release-ready.log 8.389s通过。
- 录音中撤权：真实AudioRecord录音后记录user0 UID/PID/session，外部pm revoke撤销RECORD_AUDIO；系统终止原录音进程，Live服务不再存在，AudioService当前活动录音配置（排除历史日志）user0 UID10025从1变0，音频服务诊断归档。此为预期OS进程终止，instrumentation的Process crashed不是常规JUnit通过，也不与此前服务自身崩溃混淆。原麦克风已授权状态在finally恢复；真实运行时麦克风授权弹窗和生产长会话语音质量仍待验。
- A9.2b：真实IME显示并输入白名单搜索/勾选，收起IME后同一Activity携带草稿经历物理内屏786dp→外屏373dp→内屏786dp，设备状态OPENED(3)→CLOSED(0)→OPENED(3)，搜索/勾选保持并最后一次确认。截图及continuity-report.json归档。此为生产renderer在真实configuration变化下的连续性，不等于真实NavHost/进程死亡恢复。50条合成转写仅作为背景，未声称长转写阅读锚点已验收。
- Samsung折叠后回到桌面，外屏解锁后通过am task focus将既有测试任务带回前台，未重建入口或修改折叠延续设置；因此证明任务恢复后的草稿保持，不声称折叠后自动留在前台。展开回程在同一测试继续完成，continuity-first.log 201.296s通过，含人工姿态等待。
- 最终生产及测试APK编译通过；qualityCheck成功（500 tasks）。修复包已ADB安装，SHA256 `92272eaf8107980abca28fcc8e6c102ef9558819c477cf62c681ddc8fcd6ebe3`。最终SDK/八原活动模块/挂载新鲜度/宿主2003/本地Qwen及配置、密钥/profile/MCP摘要核验见delivery-result.json，本轮真实安装前基线before-device.json与before-personal-prefs.log。正常MainActivity在隔离控制器后恢复；未改提示前缀、缓存链或模型路由，不将本轮资源测试当缓存命中率证明。
- 全部失败和控制器定位修订保留：旧APK缺debug entrypoint不计生产红；Android 15单应用共享选择器未完成不计实现失败；投影对象指针先于VirtualDisplay就绪，测试改等待既有sharing StateFlow的完成状态。隔离runner跳过正常应用启动，不替代实际生产语音profile/识别播报质量验收。
- A9/S6/A8整体继续开放。下一片固定：启动/挂断初始化及前台资格竞态→实际NavHost/进程死亡与长转写/IME阅读位置→平滑状态颜色及正式资源门；S6逐页父项、有效IDLE Doze、QQ长期资源/停启空窗、A10/A11仍独立。证据 `artifacts/stability-20261010-a9-media-continuity/acceptance-index.json`。


## 2026-10-10 A9.2c 真实IME确认按钮遮挡修订与最终交付

- 草稿连续性通过后，截图复核另发现按钮遮挡，未以先前通过覆盖该缺陷。新原生窗口坐标断言先红：保存按钮bottom=1379px，真实IME top=1344px，覆盖35px；ime-button-red.log 3.202s保留。
- Live白名单AlertDialog增加imePadding，保留原选择、搜索、保存和取消owner。复验保存bottom=1290px，键盘top=1344px，完整留出54px，实际目标高度49.77778dp；ime-button-green.log 3.222s通过。只声明本组真实786dp窗口IME几何通过，200%/全部IME类型等继续沿原矩阵补验。
- 最终编译及qualityCheck通过（500 tasks）。最新修复APK `artifacts/stability-20261010-a9-ime-controls/ZeroV2-a9-ime-debug.apk` 已ADB安装，SHA256 `5a198794aa20cf7af82512fae5fa09b704772f8dd6b4b67b66980211b7d3a482`；delivery-final.log核对实际base.apk、SDK四文件、八原活动模块、新鲜挂载、宿主2003、本地Qwen和安装前配置/密钥/profile/MCP摘要通过。麦克风授权及flags恢复、user95未变另见前片permission-restore-result.json。
- 真实媒体权限/释放、录音中撤权、IME/物理折叠草稿证据仍冻结在a9-media-continuity目录的原APK（SHA92272eaf…），本片仅增加弹窗IME留白；两个索引与原失败日志均保留，未将旧实机方法改写成最新APK上的重跑结果。
- A9.3a拒权/真实AudioRecord/投影取消授权/系统停止/二次授权/退后台与挂断，以及录音中撤权的OS终止证据限定通过。A9.2b物理内外屏同任务草稿及A9.2c本组按钮几何限定通过。A9/S6/A8整体保持进行中：真实运行时麦克风弹窗、快速初始化挂断与前台资格竞态、实际NavHost/进程死亡/长转写阅读锚点、平滑状态颜色及正式资源门仍待验。
- 当前统一后续顺序：启动/挂断和权限资格竞态→真实导航与进程死亡/长转写及IME阅读位置→其余动效/资源门。实施进度、审计报告、总架构/当前设计、路线图及验收计划/清单同步；没有改动提示前缀、缓存机制或模型路由，未进行缓存命中率实测。


## 2026-10-10 A9.3b/2d 启停竞态、真实导航与阅读锚点

- 快速重启原生红证据：实机实际Hilt provider stop/start复现AudioRecord.read的releaseBuffer断言与SIGABRT（race-red.log/race-crash.log），Kotlin crash shield不能拦截。根因是旧监听协程未结束且共享active/audioRecord，新旧循环可同时读取同一原生缓冲。生命周期Mutex串行start/stop，独立会话SupervisorJob覆盖录音/回复/播报，结束取消并等待旧会话退出；短锁保护非阻塞AudioRecord读取/释放，累计为原100ms帧，不改变VAD/ASR帧节奏。停止完成后发布IDLE，避免旧帧晚改状态。
- 实机8次实际录音快速重启后activeProviderJobs=1，最终结束无资源/旧循环及700ms迟到重开；5次真实服务创建后立即结束，初始化不会迟到录音，后续新服务可正常录音。本片限定启动/结束竞态通过，不冒称所有前台资格时序和长会话语音质量通过。
- 长转写红：生产renderer保留50条、向前阅读后裁头3条，同一项top从1892px跳到954px（native-first.log）。转写增加会话内稳定UUID，列表以ID为key，末项ID变化触发跟随，不再依赖固定50条的size。复验裁头保留同一内容与位置、同任务Activity重建保留阅读偏移，以及满50条末尾新增可跟随通过。合成条目属于隔离生产renderer；不是实际语音文本持久化或OS死亡后的转写恢复。转写继续瞬态50条上限，未新增个人数据库记录。
- 真实MainActivity/NavHost红：Live返回对话保持会话、再次进入后白名单草稿正常；后台保存任务后run-as SIGKILL原应用PID，实际OS进程终止恢复了路由/草稿，却自动重启Live服务（real-navigation-red-report.json/navigation-red.log）。入口用rememberSaveable区分首次显式进入和路由恢复，恢复不隐式启动麦克风。绿验同一路径，搜索草稿恢复、跨12秒观察无Live服务，用户点击开始后实际LISTENING、结束返回对话且服务停止。此是真实主应用，不以renderer或Activity.recreate替代OS进程死亡；测试前force-stop仅隔离入口，正式死亡段为SIGKILL。
- qualityCheck及主/测试APK编译通过，quality-final.log；最初detekt发现两处异常类型判断，已改为独立CancellationException catch并保留失败日志，没有压制规则。最新APK `artifacts/stability-20261010-a9-start-navigation/ZeroV2-a9-start-debug.apk` 已ADB安装，SHA256 `b886bcff42099f12841dfa42f1387905197a8d24006f97151f3797a9b931cbb6`。本轮真实安装前before-device/config与密钥/profile/MCP摘要对比一致；原八活动模块、新鲜挂载、SDK四文件、正常宿主2003及本地Qwen健康见delivery-result.json。未改提示前缀、缓存链或模型路由；不以未改动替代缓存命中率实测。
- 本片原生四项最终回归和真实导航报告独立记录，native-first.log两项录音通过、一项阅读失败不改写为整组通过；原APK与失败保留。导航首次绿尝试出现空root，旧脚本可能读取残留XML，故仅保留为中间尝试；最终脚本每次先删除临时快照、要求新dump成功，再完整复验，不以旧窗口内容评分。正常宿主已恢复，临时测试未修改个人白名单、系统锁屏/显示或麦克风授权。主产物/源码摘要见acceptance-index.json。
- A9/S6/A8整体仍开放：前台资格失效/运行时授权弹窗、真实长会话迟到回复/播报压力、被裁掉锚点降级及真实折叠/IME长转写阅读连续性、平滑状态颜色和正式资源门待补。下一步先补资格与长会话边界，再收敛阅读连续矩阵，随后动效/资源；其他页父项、有效IDLE Doze、QQ长期资源、A10/A11独立。


## 2026-10-10 A9长会话/阅读/动效/资源续验（中间检查点，尚未整体验收）

- 测前规则冻结于 `artifacts/stability-20261010-a9-long-motion/plan.md`。保留现有工作树、个人配置和本地模型路由，不修改提示前缀或缓存机制；本轮没有云端缓存命中率证明。
- 真实Hilt录音/Live服务接不配合取消的2200ms响应夹具，红验结束耗时2212ms、迟到文本事件2次（slow-red.log）。取消等待整棵会话导致阻塞，旧响应回调又缺少会话身份约束。修订为取消整棵会话但仅等待原生录音owner退出，回复/分句/最终结果与播报启动校验捕获会话身份；旧会话不得清理新会话状态。build-slow-green-color-red.log编译成功；slow-color-reading-first.log本项实机通过，夹具原bridge在finally恢复。不是实际LLM回复质量或24小时稳定证明。
- 同日志200%应用资源字号、实际IME弹窗开关及Activity重建保留阅读条目通过；系统字号不改，不冒称相同字符像素位置或物理折叠阅读已验。
- 当前阅读条目被裁掉的实机红验：保留50条、删除前20条后首屏实际跳到30而非20；固定旧索引跳过保留内容。增加捕获旧layout key的裁头降级，当前被删除才回到最早保留条目；未删除锚点保持与跟随末尾需独立绿验。该修订暂不标通过。
- 颜色测试首轮夹具无效：品牌主题primary与tertiary相同，不能据此判定颜色过渡；改测LISTENING→SPEAKING两个不同端点，再取得有效红验后修订过渡。
- 正式资源新增仅benchmark变体profileable manifest，以及真实Main/NavHost/录音服务Macrobenchmark控制器。冻结基线APK，要求同APK三组A/A校准后再比较候选。初次环境语音改变空转写场景及控制器idle等待导致无效；并行shell uiautomator产生的是测试进程注册冲突，不计应用崩溃。第二次缺少打断节点，仍为失败；已补交互窗口检索/失败时刻树证据，不能以测试结束后的桌面推断生产导航故障。正式JSON/Perfetto及完整A/A尚未齐全，资源门继续开放。
- 下一序列：裁头绿验及有效颜色红绿→5分钟真实录音打断/后台返回→正式资源校准和候选对照→最终安装配置/八模块/SDK/宿主/本地Qwen核验。A9/S6/A8整体保持进行中，其他独立门不代替验收。


## 2026-10-10 A9.3c/2e 长会话隔离、裁剪阅读、颜色及媒体收尾（限定验收）

- 最终生产APK SHA256 `a77ad70781a3225228daf52984c552eb7cc60b0c0b00a520e508922ed47d1337`，真实base.apk摘要匹配；native-release-final.log九项42.968s通过。真实慢响应结束耗时86ms（原红2212ms），迟到文本事件0、新会话LISTENING。会话身份隔离覆盖状态、分句、最终结果及播报启动；播报尾音等待后再次校验，避免旧会话越权恢复状态。质量门拆出会话校验/播报收尾/错误恢复，未压制detekt规则。
- 实机裁剪红为阅读条目10被删后跳到30；旧layout key若被删才回到最早保留20，未删条目的同内容/同位置、Activity重建及满50条末项跟随同组绿验。转写仍瞬态50条、不持久化到个人数据库。200%应用资源字号和实际IME弹窗往返保留可见条目通过；不冒称相同字符像素位置、物理折叠阅读或OS死亡后的转写恢复。
- 颜色有效红（reading-color-red.log）：LISTENING→SPEAKING端点不同，但采样全为终态；统一180ms状态过渡后实际中间像素及减少动效即时切换通过。浅色品牌primary=tertiary的首个夹具失败保留，不以夹具失败冒称产品红。color-frames为真实截图裁取状态球，已二进制exec-out重新传输并视觉复核，避免shell换行污染PNG；减少动效静止及实际退后台额外帧数0通过。motion-report.json仅隔离生产renderer诊断，不是正式性能门；系统字号/动画设置未改。
- 五分钟真实Hilt录音/服务保持：301411ms、30次打断、后台任务往返、同一AudioRecord session、结束释放和迟到观察通过（soak.log 303.152s）。仅响应边界使用离线夹具，并在finally恢复；不调用收费模型，不证明真实识别/回答质量或24小时稳定。该报告冻结在ZeroV2-a9-soak-debug.apk（SHA `591fb78db1a2b15872aefa21e737d2ca9ff92f04f24a105940d4833dfe4f08fc`）；后续最终APK仅调整投影释放先后，录音provider相同。最终APK单独九项回归，不把前一产物的长测改写为最新产物重跑。
- 首轮最终九项有一项真实媒体收尾红（native-final.log）：麦克风/服务已停止时投影尚未清空。销毁路径先释放投影，再停止provider并发布IDLE；真实系统取消、授权、系统stop回调、二次授权、退后台与挂断全资源清理九项复验通过。没有把首轮8/9标成整组通过。
- quality-delivery.log 945 tasks通过；投影次序修订后Lint内部FIR崩溃保留quality-release-order.log，采用独立Gradle进程/2 workers，不停用检查，quality-release-order-retry.log 895 tasks通过；帧归档控制器build-color-frames.log编译通过。全量检查另外暴露知识库fake worker取消后仍计数的夹具竞态，给fake build补ensureActive，与真实suspend worker取消语义一致；生产知识库未改，KnowledgeIndexTest 10项全绿。
- 正式资源基线仍无完整合格结果：旧控制器idle等待/环境转写改变场景、节点查找和实际锁屏阻断分别留痕。新增测前实际解锁检查、交互窗口检索、入口/控制缺失时刻窗口树，不能用测试结束后的桌面推断应用故障。热状态最高3时停止性能评分；按既有授权暂时停止Termux并熄屏冷却，正式AA/候选对照尚未通过，测试后必须恢复正常宿主和八模块并比对配置。
- [x] 本轮慢响应跨会话隔离及5分钟录音限定验收。
- [x] 被裁掉锚点有界降级、保留锚点/末尾跟随、200%资源字号/实际IME阅读条目限定验收。
- [x] 状态颜色中间像素/减少动态/后台停止绘帧及最终真实媒体释放限定验收。
- [ ] 正式非debuggable资源AA/候选对照；前台资格/真实运行时授权、实际语音长会话质量、物理折叠长转写阅读等父项差额保持独立。

A9/S6/A8整体继续开放；实施与审计证据 `artifacts/stability-20261010-a9-long-motion/native-index.json`。未修改提示前缀、缓存链或模型路由，不以此代替缓存命中率测试。


## 2026-10-11 A9正式资源测量边界与最终恢复交付

- 本轮长会话/连续阅读/动效功能依据前节九项实机与独立五分钟录音限定验收，不将不同APK或fixture范围混写。生产修订已经qualityCheck（895 tasks）及assembleDebug通过；之后仅正式资源控制器改动，build-resource-headroom.log子项目编译58 tasks通过。
- [x] 通过暖态限定资源门。有完整资源报告的组：baseline1, baseline2, baseline3, candidate1, candidate2, candidate3。frameDurationCpuMs: 基线13.290、候选13.183、差额-0.106、冻结容差1.166、通过=True；frameOverrunMs: 基线12.243、候选15.569、差额3.327、冻结容差4.367、通过=True；pssKiB: 基线357371.000、候选377531.000、差额20160.000、冻结容差23893.000、通过=True
- 测前冻结plan.md、formal-scope-v2.md和formal-thermal-window.md：非debuggable/profileable基线与候选，相同真实Main/NavHost/录音/可访问性控制，WARM/Ignore，物理展开无override，QQ按已有授权临时暂停，空转写固定场景。每组3次正式测量；框架预热iteration=null单列保留，正式0/1/2与三份trace对齐。setup两次热状态0且SKIN≤34.0℃，每次测量begin、三次controls、end共五点必须0；任何异常整组不评分。仅资源控制器允许localhost/127.0.0.1明文，不改目标网络策略。帧P95容差max(1ms,三组AA极差)，PSS容差max(基线中位数2%,三组AA极差)，AA完成冻结后才测候选，不能把容差叫提速。
- 失败证据保留：旧冷启动等待/环境语音/锁屏/坐标注入与loopback策略问题；首份完整暖态功能报告无窗口温度，不计分；首份窗口采样无明确预热轮次，存formal-unscored/baseline1-without-iteration；baseline1-measured-thermal-end-failure.log在end热状态1中止，不追认。后台QQ暂停后不得据此声称全模块负载或冷启动资源通过，外部报告处理温度单列诊断。
- 候选第三组首次在setup的240秒内未达到两次冷却条件，candidate3-warm-cooling-failure.log保留、不评分；失败后快照热状态3、SKIN42.8℃。runtime中QQ无活动句柄，系统多进程短时繁忙，不能凭单个top快照归因为QQ或Zero；间隔CPU采样后已显著回落（candidate3-hot-cpu-series.log）。熄屏充分冷却后仅补测第三组，同一冻结目标/控制器/误差，未停止其他个人应用、未修改正式门槛。
- 最终通过ADB恢复并安装ZeroV2-a9-long-debug.apk，SHA256 `a77ad70781a3225228daf52984c552eb7cc60b0c0b00a520e508922ed47d1337`，真实base.apk相同。正常宿主FGS2003、原八活动模块新鲜挂载、SDK资产/安装/Debian摘要、本地Qwen健康与8080连接全部复核；行为设置/触发开关/模块配置/密钥/profile/MCP个人偏好摘要与开工一致。暂停QQ已恢复，未清个人资料，不改系统字号/动画/锁屏设置。
- 恢复核验首次捕获QQ的Room状态因临时后端stop而同步为DISABLED；升级恢复正确保留停用意图，只自动挂载另外七项，不能把这当成升级丢模块。通过既有生产“工作区→动态模块→启停qqbot”恢复原配置，module-restore-ui.log 3.31s通过，最终dynamicModules与开工完全一致。恢复控制器单独编译/归档，不替换正式资源使用的冻结controller；首次窗口尚未就绪及选中页标题不可点击的控制器失败均留痕，最终按真实可点击组合contentDescription定位。未写SQL修状态、未用空配置启动QQ。
- 最终verify_delivery.py还要求MY_PACKAGE_REPLACED宿主请求以及全部八项挂载时间不早于手机侧本次安装起点，避免旧恢复记录误计新交付。build-module-restore-controller-final.log 58 tasks通过；这是交付控制器编译，不把它混写为再次执行全量qualityCheck。
- 证据索引artifacts/stability-20261010-a9-long-motion/acceptance-delivery-index.json及delivery-result.json。缓存前缀/模型路由未修改；本轮不推断缓存命中率。
- 下一步仍独立核对真实运行时麦克风授权/前台资格、真实LLM语音长会话质量、物理折叠及真实窄分屏长转写阅读、冷启动与全后台模块资源，接续A9后续父项与A10/A11；A9/S6/A8整体验收保持开放。


## 2026-10-11 A9 真实折叠长转写阅读与后续资源门

- [x] 物理折叠长转写阅读限定验收：LiveFoldReadingAcceptanceTest 实机 191.036s、1/1通过；真实786dp内屏→373dp外屏→786dp内屏，同一Activity未销毁，第7条长段落在外屏裁头3条并追加3条后及展开返回仍可见。50条合成转写仅用于生产renderer阅读验收，不调用模型，不写个人资料，不声称相同字符/像素位置或真实语音质量。before/cover/cover-trim/unfolded PNG已二进制传输并视觉复核。
- 首轮65.176s在外屏“同阅读条目可见”断言失败，归档fold-first-failure；当时Samsung折叠后回桌面，缺失败时刻条目/截图，不能据此确定生产阅读缺陷。补采probe后，before=[7,8,9]、cover=[7,8]、activityDestroyed=false，完成往返及裁头绿验。生产滚动代码没有改动；首轮失败不删除、不追认为通过，不把恢复前台与生产滚动修复混写。
- 用户明确安排真实人声多轮语音稍后补验。本轮不使用离线回复夹具代替真实ASR→Qwen→TTS质量；实际人声长会话仍为[ ]。本地Qwen健康、ADB reverse8080；原八模块实际后端均ACTIVE。
- 新增独立冷态/全后台控制器LiveColdFullLoadBenchmark：冷态检查user0目标主PID不存在并记录新PID，使用StartupTimingMetric；全后台场景不停止QQ，始终检查八模块ACTIVE、实际录音控制与释放。协议先固定温度、三组AA与三组候选规则、正式轮次、指标与边界。完整后台指原八模块启用运行，不冒称八模块同时执行重任务；Android冷进程不是Termux/设备冷启动。
- assembleDebug/assembleDebugAndroidTest初版259 tasks通过；诊断及资源控制器317 tasks通过；assembleBenchmark384 tasks通过。生产debug APK仍为a77ad70781a3225228daf52984c552eb7cc60b0c0b00a520e508922ed47d1337，本轮仅验收控制器改动，未改提示前缀/缓存或模型路由。新资源协议及APK摘要已冻结，尚不把编译或上一暖态门当作本门通过。
- 证据目录artifacts/stability-20261011-a9-speech-fold：plan.md、fold.log、report.json、probe.json、fold-inputs.json、build-*.log、resource-protocol.md、resource-frozen-inputs.json。后续先完成冷态及八模块资源驱动/AA/候选，真实语音待用户准备；A9/S6/A8整体继续开放。


## 2026-10-11 A9 资源调通、用户停止及恢复交接

- 用户要求今天停止测试；已停止仅user0的资源测试控制器，保留中止日志及trace。没有继续正式AA或候选评分。A9/S6/A8整体仍开放；真实人声长会话按用户安排稍后补验。
- 物理长转写阅读已完成191.036s、1/1实机限定验收：786dp内屏→373dp外屏→786dp内屏，同一Activity、第7条阅读条目在外屏裁头3条追加3条及展开返回仍可见。生产滚动代码未修改；合成文字不代表真实ASR/LLM质量，不宣称相同字符/像素锚点。首轮65.176s失败及probe均保留。
- 正常监听下冷态驱动12.386s在Macrobenchmark测前COLD检查失败。系统日志显示强停后约87ms因NotificationListener重启user0主进程，之后宿主保活继续启动；这是标准冷态条件不成立，不能计作冷启动成绩，也不通过关闭生产保活来掩盖。已核对本地benchmark 1.3.4源码字节码：shell UID2000的system_action辅助进程并非该包存活判定的根因。
- 原八模块（含QQ）均保持ACTIVE。独立40点闲置环境采样全部热状态0，未采用自适应温度上界。v2全后台暖态调通325.927s未通过第二次setup：48点34.1–36.8℃，最后34.4℃，未满足两次≤34℃；本组不评分。旧v1冷态243.478s环境失败一并保留。
- 用户明确授权短时暂停仅user0 Zero通知监听并测后恢复、单列隔离冷态。run_isolated_cold.py已真实执行，其他监听器完整保留，finally核对原监听授权集合完全恢复。隔离冷态生成第0次正式Perfetto后，因用户要求停止而中止；仅一份trace、无完整三次聚合与资源报告，整组不评分。系统JUnit显示Process crashed是测试控制器被主动停止的结果，不据此新增生产崩溃结论。期间采样曾热状态1、SKIN39.2℃，不能算通过正式资源门。
- 在正式AA/候选之前冻结v3方案：仅将控制器每次setup有界冷却等待240秒延至600秒；两次间隔5秒热状态0且≤34℃、测量5点热状态0及原性能公式不变。:benchmark:assembleBenchmark与:benchmark:assembleDebug分别29 tasks通过。v3 APK及输入摘要已存档，但尚未安装，也没有v3正式成绩；下次必须先安装resource-controller-v3.apk并确认actual speed与新输入摘要。
- 最终已ADB恢复正常ZeroV2-a9-long-debug.apk，实际base.apk SHA256为a77ad70781a3225228daf52984c552eb7cc60b0c0b00a520e508922ed47d1337。verify_delivery.py通过：原八模块在本次升级宿主请求后新鲜挂载、FGS2003、SDK资产/安装/Debian摘要匹配、本地Qwen健康和8080连接正常；行为/触发设置、模块配置以及MCP/密钥/profile偏好摘要与开工一致。恢复原通知监听，未清数据；本轮不修改稳定前缀或模型路由，不推断缓存命中率。
- 交接文件artifacts/stability-20261011-a9-speech-fold/handoff.md。原始证据：fold.log/report.json/probe.json、resource/*/macro.log与setup序列、cold-process-system.log、idle-calibration.json、notification-isolation.json、resource-frozen-inputs.json/resource-frozen-inputs-v2.json/resource-frozen-inputs-v3.json、build-resource-cooling-v3*.log、restore-debug-install.log、delivery-result.json。下一步依次调通v3正常全后台暖态/隔离冷态，完整三组AA后冻结误差、再三组候选；真实人声稍后补验，缺项时不关闭父项。


## 2026-10-11 LLM-U 接入配置UI后续验收

当前优先轨见验收推进计划 LLM-U。本片U1a只修改注册身份，不改页面；上一轮CF BYOK局部UI不等于完整自由配置。

- [ ] U3沿现有模块详情页模型管理入口添加/复制用户模型；同wire modelId不同连接分别显示和操作。
- [ ] U1b/U3连接鉴权NONE/Bearer/Header、网关与Key引用正确保存，Key不进入普通配置/诊断。
- [ ] U2/U3思考/采样/输出控件按契约生成；unset与false/0区分，非法组合保存前可解释拒绝。
- [ ] U3保存失败保留草稿，主力/启停/删除引用与唯一Store一致；宽窄/大字号/返回与进程恢复分别记录。
- [ ] U6聊天/SSE/工具闭环验证状态分别展示，不用API连通性冒充Agent可用。


### 2026-10-11 LLM-U1b/U2 编辑页契约
- [x] 连接认证NONE/Bearer/Header、显式契约、契约字段、参数留空/错误反馈、上下文/输出限制已实现并通过草稿测试与编译。
- [x] 切换契约不清草稿；不支持字段显式清除；不发送温度的契约有说明。
- [ ] U3新增/复制/删除模型与连接选择的真实UI验收；本片隔离实机验证存储与JSON，没有替代编辑页实机交互验收。


### 2026-10-11 LLM-U3：模型与连接管理已验证

- 在现有模型页增加添加、复制、停用后删除、连接选择/独立连接、共享连接显式更新、能力及优先级；新建/复制默认停用，不自动增加工具能力或改变主力。连接仍由单一 LlmProfileStore 原子快照管理，删除不清除凭据。
- 共享连接差异必须显式勾选更新；失败不发布新快照、保留草稿。未保存密钥不进入 Activity 恢复状态。
- `artifacts/llm-u3-20261011/acceptance-index.json`：quality 500 tasks；JVM 557（失败 0，跳过 1）；Fold3 独立包生产编辑器 2/2，覆盖 420/1200 宽度 fixture、共享选择及 Activity 重建。宽度 fixture 不等于真实分屏；非完整 Agent 实机验收。
- 日常 Zero APK 未覆盖；模型/密钥/MCP 三份配置前后 SHA-256 一致。首次独立构建配置时机错误及安装完成前 instrumentation 失败均保留日志，重试通过。
- 下一片 LLM-U4：提供方与 CF 模板、路径和认证；原 Qwen 错误仍须独立采证。U5/U6 未完成。


### 2026-10-11 LLM-U4a：提供方/CF 适配工程与隔离验收通过，U4b 原错误复现开放

- 现有模型编辑页新增 19 个连接模板：OpenAI、DeepSeek、百炼、硅基流动、智谱、Kimi、MiniMax、火山方舟、Gemini、OpenRouter、CF 三种新模式/旧 compat，以及 Ollama/vLLM/llama.cpp/LM Studio 和自定义。模板仅创建独立连接与新凭据引用，清除旧 fallback/未保存密钥，不自动改模型、能力或参数契约；地址占位符须补全，地域/Coding Plan 不能混用。
- `GatewayMode` 明确 DIRECT / CF_WORKERS / CF_BYOK / CF_GATEWAY；Workers REST 使用完整 @cf/...，可选 Gateway header；第三方 REST 使用 CF Token + Gateway header；旧 compat 使用 cf-aig-authorization。旧布尔 BYOK 配置（包括曾用于 Workers 网关的配置）保持请求语义，不自动重命名模型。新模式的错误路径/鉴权/模型格式在保存和发送前拒绝。
- REST 默认 BYOK 密钥缺失可能转统一计费，客户端不能强制免费或保证 BYOK；界面与指南已说明服务端策略边界。连接模板不是所有模型能力、免费额度或真实工具调用已通过的证明。
- 新 `cf-qwen38-v1` 契约按官方模型页限制 effort=low/medium/xhigh，发送 max_completion_tokens；温度保留。bounded 在无法关闭思考时用 low，避免省略后触发服务端最高默认强度。工具 ID、参数、结果与消息顺序回归保持一致。
- snapshot schemaVersion=3：gatewayMode 归连接，localLlamaPreflight 归模型；兼容数组/v1/v2并保留旧 profiles_json，提交失败不发布、未知版本不覆盖。llama.cpp 预检改为显式标志，复制模型保留，并传递所选认证；CF 与本地预检冲突先拒绝。generic 本地预检保留旧思考字段语义，需模型专属设置时显式选择契约。
- U3 收尾：已启用模型也可设为唯一主力；自定义模型隐藏无效恢复默认。主力选择仍由现有单次快照提交。Agent owner、工具执行、safeCall、Bus、Room schema 未改。
- 最终 qualityCheck（含 assembleDebug）500 tasks 成功；JVM 564（563 通过、1 既有跳过、0 失败/错误）。SM-F926N：独立包生产编辑器 3/3；services.test 的 Android JSON/迁移/路由 3/3。UI 仅验证 420/1200 宽度 fixture、连接/模板/草稿重建，不等同真实分屏或全 Agent。列表两项收尾由最终工程门验证，未宣称单独实机验收。
- 个人模型/密钥/MCP 三份配置前后 SHA-256 一致；日常 APK 未替换，未导出密钥或发送个人历史；临时 UI 两包已卸载。首次静态检查、旧测试前提、测试可空值编译、锁屏阻挡的失败日志保留；锁屏那次中止不计通过。仅 debug 的 profile fixture 允许锁屏显示合成页面，不改变系统锁屏或权限设置。
- 原 Qwen 只读历史采证得到两条 HTTP400 通用消息，原始上游响应/参数缺失，当前 qwen 槽位已改 GLM；**U4b 唯一根因与真实复现未关闭**，不把参数契约差异冒充已复现根因。后续代码片 U5；U4b 采样与 U6 真实提供方/Agent 矩阵继续开放，A9 仍停止。
- 证据：`artifacts/llm-u4-20261011/acceptance-index.json`、`quality-closeout.log`、`jvm/`、`device-ui-awake.log`、`device-migration.log`、`qwen-errors-redacted.json`、`provider-sources.md`、`slice.diff`、`final/`、`next-u5.md`。正式 APK SHA256：b3c32bff7f0eb815631323c32ab25ad7fd740b447d7204ec0dff9bd9d417b9f9。


### 2026-10-11 LLM-U5：回合快照与工具状态限定验收通过

- ILanguageModel.snapshotForTurn 在普通发送/重生成选定模型后冻结配置；ProfileLanguageModel 深复制参数/能力并捕获凭据，后续工具请求、输出预算、短补全均使用同一份内存快照。缺凭据不在续轮重新解析，下一用户回合才读取新配置；密钥不进入持久化或诊断。
- ToolProviderState 保留 OpenAI 工具扩展 extra_content.google.thought_signature，按连接/模型/契约/凭据引用绑定作用域；跨作用域不转发。签名不参与工具参数或执行，toString 脱敏。仅覆盖此明确扩展，不宣称原生 Gemini/Interactions/任意厂商扩展均支持。
- ToolCallAssembly 按 SSE index 合并乱序并行与晚到签名；冲突 ID/签名、越界索引失败，缺 ID/名称及同批重复 ID 不执行。既有工具 dispatcher、safeCall、TurnGate 和 Room 事务 owner 保持。
- 现有 tool_calls 文本列升级 metadata version=2，保留 provider_state version=1 和 stream_index，兼容旧数组/v1，不改 Room schema。重载完整工具消息及签名；进程恢复读取历史，不自动重放未完成工具。JVM 多步回传与配置轮换为确定性证据；实机通过数据库重载，不等于跨进程杀死恢复已测。
- quality-final.log：qualityCheck+测试APK，530 tasks，含 assembleDebug；JVM 567（566通过、1原有跳过、0失败）。SM-F926N 在真实宿主 UID、跳过正常 Application 启动的 Runner 下 6/6 Agent 回归通过：实际引擎/dispatcher、隔离Room、两次工具续轮、重载、取消与重生成/失败保留；模型为确定性 fixture，不计真实提供方通过。
- 已备份旧主APK并安装本片新正式 applicationId=com.zero.v2 APK；不同于前片“未安装”。测试不修改个人模型或密钥；U6结束后统一核对配置迁移和正常宿主恢复。首轮编译误改embedding局部变量、静态格式检查失败均已修正，原日志保留，未降规则。
- 证据 artifacts/llm-u5-20261011/acceptance-index.json；后续 U6 真实提供方/Agent矩阵与 U4b 原Qwen400复现继续开放，A9资源测量仍停止。


### 2026-10-11 LLM-U6a：CF真实模型与Zero Agent限定验收通过；U4b按用户裁定停止历史追溯

- Fold3 SM-F926N 使用真实个人UID内的SecureKeyStore凭据，未导出密钥、未发送个人对话、未修改主力模型。新增 opt-in LlmProviderAcceptanceTest，仅发送合成输入、执行无副作用测试工具；请求计数硬上限18，实际17次，未扩大额度重试。
- 当前CF REST账户入口：GLM @cf/zai-org/glm-4.7-flash 与 Qwen @cf/qwen/qwen3.8-27b，普通同步/SSE/两次顺序工具执行与结果综合共6/6通过。Qwen另通过生产 discover_tools → invoke_tool → invoke_tool → 最终综合路径，3条工具结果、两次实际probe执行，无重复执行。使用真实ChatBot/selector/dispatcher/客户端，正常Application启动被验收Runner跳过、只注册无副作用工具；不是全模块所有工具及聊天UI交互已认证。
- Qwen三种请求对照（旧槽位extra_body、仅移除extra_body、新cf-qwen38-v1）全部HTTP200并返回OK，保留请求形状/cf-ray。不能据此将extra_body或旧max_tokens认定为历史400根因。CF日志接口/网关列表均HTTP200，原2026-10-11 03:25–03:40 UTC窗口未取到对应失败日志。
- **用户最新裁定：“无需追求复现，当前功能通过即可”。U4b历史复现不再是阻塞门，停止继续追溯；结论为当前Qwen功能通过，历史唯一根因未确认，绝不写成已定位或已复现修复。**
- U5补验：独立持久Room测试库在PID8171写入、PID8449重开，四条消息、完成/待完成调用及签名完全保留，历史加载不自动重执行。seed/restore两次instrumentation均通过；个人聊天库未用于测试消息持久化。
- 最终 quality-closeout.log：qualityCheck + app:assembleDebugAndroidTest，530 tasks通过，含assembleDebug；JVM 567（566通过、1原有跳过、0失败），未修改基线。新主APK e843875bd91acb884e70b6cd73f44601885819e4576befb5bc4504cf087d7267 已实装，设备base.apk与构建哈希一致。
- 正常MainActivity/宿主FGS已恢复，原8个活动动态模块全部新鲜挂载；SDK资产/安装/DEBIAN哈希一致，行为设置/触发开关/模块配置/启停状态不变，密钥和MCP文件哈希不变。个人profile首次升级到schema3：旧profiles_json原文保留，原有全部字段逐一比对无变化；没有把qwen槽位从GLM切到Qwen。旧APK保存在U5证据目录。
- 本地Qwen仍按原配置停用，PC 8080健康端点当前不可达、adb reverse未配置，本轮未启动/更改本地推理服务；不计本地部署实测通过。首次按手机端口forward检查得到RemoteDisconnected，后核对原架构为PC reverse连接，纠正结果保留于delivery-result.json，不冒充本地模型恢复通过。
- U6b扩展矩阵继续明确未验证：Gemini免费层BYOK/真实签名、其他国内外直连、本地Ollama/vLLM/llama.cpp/LM Studio。连接模板/协议单测不等于这些账户或模型已经真实通过；未向未确认BYOK配置的第三方模型发起可能回落统一计费的探测。
- 证据 artifacts/llm-u6-20261011/acceptance-index.json、matrix.json、discovery.json、diagnostic.json、cf-logs-search.json、restart.json、delivery-result.json。错误的MainActivity包名启动失败日志保留，随后按实际namespace纠正并验证正常宿主。A9仍停止，不扩展本片资源/语音/全UI父门。


### 2026-10-11 LLM-U6b-1：Gemini BYOK 实机入口与错误数组修复

- Fold3 同 UID/Keystore 只读清单通过：default 网关已有 google-ai-studio、alias=default；保留 default_config=false，不改远端配置、不导出密钥。
- 临时 google-ai-studio/gemini-3.8-flash 经 compat/BYOK 的首个真实请求收到上游 503 UNAVAILABLE（high demand）。发现同步聊天先强制解析 JSONObject 导致错误数组遮蔽原错误；现先检查 HTTP 状态、支持根数组错误、响应关闭与密钥脱敏。
- qualityCheck + assembleDebugAndroidTest 530 tasks 成功；JVM 568 项：567 通过、1 原有跳过、0 失败。新增 503 数组与脱敏回归通过。未更改 Agent 工具调度/快照/配置 owner。
- 修复 APK 实装后，Gemini 3.8 Flash 经 CF compat + 已存 BYOK 的真实聊天、SSE（final=true）、Zero Agent 两次顺序工具及最终答复全部通过；2 次调用均取得真实 Google 工具签名，生产客户端继续回传后完成回合。累计 6 次请求（首次 503 + 成功矩阵 5 次），未改个人主力 GLM，也未回退其他模型/统一计费入口。
- 实机 Agent 重生成/取消/工具安全 6/6 通过。签名不是 fixture；不导出原始签名或凭据。其他直连/本地矩阵仍未验。官方模型有 API 免费层，用户现有 BYOK 用于本轮；Google 项目的免费/付费账务状态未独立读取，不能把 CF 估算成本当实际账单。
- 证据：artifacts/llm-u6b-gemini-20261011/acceptance.md、gemini-retry1.json、agent-regression.log；首次 503 原证据 gemini.json 保留。

- U6b-1交付核对：修复APK已安装且哈希一致；正常新进程前台宿主2003与原八模块新挂载；模型/密钥/MCP/行为/模块设置不变，原dirty路径无清除。见 delivery-result.json；当前Gemini仅临时验收配置。

### 2026-10-11 U6b-2至U6b-6：现有可用连接续验收口

- DeepSeek/deepseek-v4-flash、DashScope/qwen3.8-flash、MiMo/mimo-v2.5、llama.cpp/Qwen3.5-9B、LM Studio/Qwen3-8B，五条连接各聊天/SSE/真实Agent两次顺序工具3/3通过，共26次推理。MiMo首次固定标记提示拒答单独保留，普通问题与只读记录查询复验通过，未放宽工具执行/结果断言。
- 本地真实30020输入+2048输出超过24576上下文，生成前阻断1/1通过。LM Studio NONE认证通过，未误用llama.cpp预检。已有云端凭据和本机模型复用；无下载、无原模型/连接启停改写。
- 新增显式mimo-v1（现共10契约）：thinking.type、同步/流式同义、reasoning_content回传、使用服务端温度；不支持未确认effort。仅生产契约目录增量，旧档不迁移，工具执行/快照/Room owner不变。沿用既有动态UI控件；本轮未新增UI视觉验收声明。
- quality-closeout.log 530 tasks成功，JVM568通过/1原有跳过；Agent重生成/取消/工具安全实机6/6。新APK 97eccfd94e3b8700c1967da70b54b307a0b1c62e185a4100e2945b595f741a2a已实装并校验；前台宿主和八模块新挂载，模型/密钥/MCP/行为/模块配置保持一致，原dirty路径全部保留。测试本地服务/模型/转发均撤销，LM Studio配置恢复。
- 剩余OpenAI/Anthropic等直连缺对应CHAT配置、Ollama/vLLM未发现可用部署，不能用模板或其他网关模型替代实测。Gemini项目账务状态仍未独立核对。A9停止、历史Qwen400复现取消的裁定不变。
- 证据及后续条件：artifacts/llm-u6b-remaining-20261011/acceptance.md、acceptance-index.json、delivery-result.json、next.md。
