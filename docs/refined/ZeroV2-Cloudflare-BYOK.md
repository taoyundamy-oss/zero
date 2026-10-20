# Cloudflare AI Gateway BYOK 聊天接入

> 最新验收收尾（2026-10-11 LLM-U7）：代码审核修复4项P1（共享连接契约复验、草稿路由校验、原生状态上下文预算、Responses失败用量记录），新增显式cf-openai-responses-v1及图片输入，共13契约。CF Luna真实聊天、图片SSE、medium思考及两次工具3/3通过，共5次请求、每次1536输出上限；2个加密思考项、3条输出状态恢复回传。LLM页完成分区卡片、参数/能力选项和模型卡片重构；质量530任务、JVM592通过1跳过、实机UI10/10、Agent6/6。原配置/密钥/MCP摘要、实装APK、FGS2003和八模块恢复核对通过。保留Chat Completions，无自动迁移。通用契约不代表所有GPT型号或全部OpenAI API功能已验收。三家直连取消、Ollama/vLLM暂缓不阻塞、Gemini账务未核实、原Qwen400取消及A9停止不变。证据 `artifacts/llm-review-ui-20261011/acceptance.md`。

> 此前验收收尾（2026-10-11 LLM-U6b-12）：GPT-6 Luna经CF REST Responses已实现并实测：聊天/SSE通过，medium思考+两次Agent工具及落盘续接通过，1个真实加密思考项、3条输出状态恢复回传。共8次应用请求（初始low 5次无思考项，补验medium 3次），每次1536输出上限。新增cf-luna-responses-v1，共12契约，保留旧Chat Completions和全部配置，无自动迁移。质量530任务、JVM586通过1跳过、Agent6/6；实装、配置摘要、FGS2003及八模块恢复核对通过。三家直连取消、Ollama/vLLM暂缓不阻塞、Gemini账务未核实、原Qwen400取消及A9停止不变。证据 `artifacts/llm-u6b-luna-responses-20261011/acceptance.md`。

> 此前验收收尾（2026-10-11 LLM-U6b-11）：Anthropic原生手动思考/签名已实现并通过CF真实验收：聊天、SSE、两次Agent工具3/3，5次请求（总输出上限1536、思考预算1024）；1个真实签名块，续接前3条原生内容经metadata落盘恢复回传成功。新增显式cf-anthropic-native-v1契约（共11契约）、整条消息原生状态及存储元数据v3；旧契约/配置不自动迁移。质量530任务、JVM579通过1跳过、Agent6/6，实装APK及八模块恢复通过。用户确认本轮自行修改模型/密钥，其新配置保留，MCP/业务模块配置不变。当前授权功能范围收口：三家直连取消，Ollama/vLLM暂缓部署不阻塞；Gemini账务层级仍未核实，原Qwen400取消、A9停止不变。完整证据 `artifacts/llm-u6b-anthropic-thinking-20261011/acceptance.md`。

> 此前收尾结论（2026-10-11 LLM-U6b-10，覆盖下方历史待办）：CF Haiku真实两次Agent工具回传通过，本片3次请求、每次256输出上限，与U6b-7聊天/SSE合并闭环；Luna三项已于U6b-9通过。用户明确取消OpenAI/Anthropic/Gemini直连验收，不再阻塞收尾。质量530任务、JVM572通过1跳过、实机Agent6/6及原配置/八模块恢复核对通过，无生产代码或VPN修改。当前可用配置已验收收尾；Ollama/vLLM无可用部署仍待验，Anthropic原生思考/签名属于未实现能力（当前明确拒绝），不能写成已支持；Gemini账务层级未独立核实。U6扩展父门仍开放，U4b取消、A9停止不变。总证据 `artifacts/llm-u6b-closeout-20261011/acceptance.md`。

> 此前有效结论（2026-10-11 LLM-U6b-9，覆盖下方历史待验状态）：用户当前手机代理下，Zero生产客户端的GPT-6 Luna聊天/SSE/真实两次工具全部通过，5次应用层请求，上限6、输出上限256、effort=none。默认ProxySelector实际选择HTTP；免费trace先HK、后US/SJC，NO_PROXY对照也为US/SJC，故节点标签不等于固定出口，也不能断言地区封禁。未改VPN、保存配置或生产代码；质量门及实机Agent6/6通过，原配置和八模块恢复核对一致。证据 `artifacts/llm-u6b-phone-luna-20261011/acceptance.md`。Luna手机/SSE/工具条件关闭；其他直连、Haiku真实工具/思考签名、Ollama/vLLM和Gemini账务边界仍保留，U4b取消与A9停止不变。


> 2026-10-11 U6b-8补充：同账户CF OpenAI在手机CN/LAX出口503、PC US/SJC出口200，Luna Chat/Responses及Nano Chat均通过PC对照，交替复核一致。此前503不代表全局计费停服；修复方向优先核对API域名实际出口/地区路由，BYOK能否消除该网络差异未验证。完整证据 `artifacts/llm-cf-openai-diagnostic-20261011/acceptance.md`。

当前配置使用连接模式（DIRECT / CF_WORKERS / CF_BYOK / CF_GATEWAY）与独立模型契约。后文初始“本片”记录为历史快照；最新LLM核对见 `artifacts/llm-u6b-cf-final-20261011/code-doc-audit.md`。BYOK模式仍须核对网关实际密钥与计费策略，不能仅凭Zero选项保证免费。

## 配置

在连接编辑器选择 Cloudflare Gateway 默认 BYOK，API Key 填 Cloudflare Token。Google AI Studio API Key 保存到目标 Gateway 的 Provider Keys，使用 default 别名。

- REST Base URL：`https://api.cloudflare.com/client/v4/accounts/<account>/ai/v1`；Gateway ID 填实际存放 BYOK 的网关。请求使用 Authorization Bearer 和 cf-aig-gateway-id。
- 兼容 Base URL：`https://gateway.ai.cloudflare.com/v1/<account>/<gateway>/compat`；请求只使用 cf-aig-authorization，不将 Cloudflare Token 放入上游 Authorization。此旧入口官方仍支持，但已建议新接入使用 REST。
- 模型 ID 必须包含提供方。Google AI Studio 文档使用 `google-ai-studio/<model>`；按实际入口支持的完整 ID 填写。用户收到上游错误要求 `gemini-3.8-flash`，本实现不改写或降级该版本。REST 模型目录的 `google/<model>` 与 provider 路由命名不能假定可互换。
- 本实现不保证 Gateway 目录已经收录某个 Google 新模型。No such model/404 的具体上游错误会保留，需按返回内容检查 Cloudflare 模型目录。
- BYOK 必须在所选网关正确配置；Cloudflare 在缺失 default key 时可能回退 Unified Billing。开启 Zero 开关本身不能保证 Google 免费层计费；本片未操作云端设置。

## Agent 行为边界

### GPT-6 Luna 与 Anthropic 经 CF REST 的配置边界（2026-10-11）

- 新建独立连接可选择“Cloudflare Gateway · 服务端计费策略”，沿用账户REST根地址及Gateway ID。当前default清单仅有Google BYOK，因此本轮OpenAI/Anthropic按统一计费路径验证；未改个人模型配置。
- `openai/gpt-6-luna`：`effort-v1`、effort=`none`；不要把通用契约枚举当全部能力。U6b-9使用当前手机代理、CF_GATEWAY/default和256输出上限，Zero生产客户端聊天/SSE/真实两次工具全部通过。原保存模型未切换；同账户OpenAI路径使用CF统一计费，不能称作已验证OpenAI BYOK。默认HTTP代理实际出口先HK后US/SJC；排障以同UID同客户端trace为准，不能仅看VPN节点名。
- `anthropic/claude-haiku-4.5`：`generic-v1`，不发送原生thinking参数。CF REST同步可返回OpenAI结构，SSE实测却返回原生Anthropic事件；生产客户端已增加CF Anthropic专用解析分支，最终复验结果见本轮acceptance.md。
- 原生思考需显式选择 `cf-anthropic-native-v1`，模型填写原生ID，例如 `anthropic/claude-haiku-4-5`（与REST目录的 `claude-haiku-4.5` 不混用）；CF_GATEWAY连接地址/Gateway ID沿用，客户端生成CF原生Messages请求。开启thinking、预算1024、总回复上限1536已通过真实聊天/SSE/两次工具与签名回传；默认generic-v1路径仍不接收原生思考块。
- 原生签名和redacted内容按消息完整保存并限原路由回传，存储元数据v3兼容旧记录。真实验收捕获thinking签名；redacted分支只有本地测试覆盖。当前不支持原生附件、adaptive模式或未知扩展，不把Haiku手动思考验收外推所有模型。
- 本轮证据：`artifacts/llm-u6b-cf-final-20261011/`；既有失败和请求计数保留，禁止反复重跑以凑“全通过”。

继续使用原 messages/tools/SSE/tool_call_id 流程。自U2起，参数和reasoning_content回传由显式ModelContract决定，与profileId和BYOK认证模式独立。通用契约不发送思考扩展；选择专用契约前须确认当前模型支持，不再按qwen/deepseek槽位猜测。

聊天流式、同步和 bounded completion 共用鉴权构造。Gemini实际多轮工具与两次签名回传已由U6b-1实机验证，见 `artifacts/llm-u6b-gemini-20261011/acceptance.md`；该证据不证明Google项目实际账务层级。

## 官方依据

- https://developers.cloudflare.com/ai-gateway/usage/rest-api/
- https://developers.cloudflare.com/ai-gateway/usage/chat-completion/
- https://developers.cloudflare.com/ai-gateway/usage/providers/google-ai-studio/
- https://developers.cloudflare.com/ai-gateway/configuration/bring-your-own-keys/

## 本片验证（2026-10-11）

`app:assembleDebug` 成功；定向 JVM 11 项、0 失败/错误：Gateway 4、配置草稿 3、SSE 3、ReasoningReplay 1。最终串行构建 48 秒，244 tasks；KSP 增量关闭仅作用于本次命令，不改项目构建配置。前轮重叠构建造成 KSP 缓存错误，最终串行验证通过。证据与 APK 位于 `artifacts/cloudflare-byok-20261011/`。未安装 APK、未修改手机 profile 或 CF 云端配置、未验证真实 Gemini 多轮工具调用。


## 2026-10-11 U2更新
此前“BYOK一律不发送专用思考参数”的临时策略已被显式模型契约替代。连接只决定认证/网关路由，contractId决定HTTP字段。通用契约不发送思考扩展；仅在上游模型明确支持时选择对应方言。模型ID、免费额度、BYOK默认密钥命中及工具调用能力仍需真实provider证据，不能由本地编译证明。见LLM-U2验收章节与artifacts/llm-u2-20261011/sources.md。


## 2026-10-11 U4 使用方式与边界

在 LLM 模块模型管理中添加/复制模型，编辑页选择连接模板。补全账户与 Gateway ID，输入精确模型 ID，独立配置凭据；选择模型参数契约与能力，保存后启用或设为主力。模板切换创建新的凭据引用，不会把旧密钥发送给新提供方。

- Workers AI：选对应模式，REST 根地址 `/client/v4/accounts/{account}/ai/v1`，CF Token；模型原样 `@cf/...`，填写 Gateway ID 才经过网关。Qwen3.8 27B 的 `cf-qwen38-v1` 限制强度 low/medium/xhigh；这项本地适配尚不证明用户原 HTTP400 已修复。[官方模型契约](https://developers.cloudflare.com/workers-ai/models/qwen3.8-27b/)。
- Gateway BYOK / 服务端计费策略：REST 需要 Gateway ID；模型前缀按此入口目录填写，不自动互换 google/google-ai-studio。REST 只查默认 BYOK 别名，缺失可能转统一计费；是否免费/是否禁用回落必须在 CF 账户侧核对，Zero 不作保证。[官方 BYOK 说明](https://developers.cloudflare.com/ai-gateway/configuration/bring-your-own-keys/)。
- 旧 compat 配置保留，使用 cf-aig-authorization；不自动重写地址或版本。自定义 Worker 或其他兼容代理可用直连模式并显式选择认证。
- 本地模板中的 127.0.0.1 指手机自身；电脑服务器请填写手机可达地址。仅 llama.cpp 可启用实际上下文预检，CF/Ollama/vLLM 不要启用该专有预检。能力、工具、视觉与思考应依部署模型声明。

最终本地与隔离实机证据见 `artifacts/llm-u4-20261011/acceptance-index.json`；真实提供方工具闭环、Gemini签名和回合冻结仍归 U5/U6。个人 Zero APK 尚未覆盖升级。


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

## GPT-6 Luna：Responses思考与工具（U6b-12）

模型编辑页保持已有CF账户Base URL和Token，使用以下配置；不要在Base URL末尾手工追加responses，客户端自动选择。

| 字段 | 设置 |
| --- | --- |
| 连接模式 | Cloudflare Gateway－服务端计费策略（CF_GATEWAY） |
| Base URL | https://api.cloudflare.com/client/v4/accounts/<account>/ai/v1 |
| 认证 | BEARER，现有Cloudflare Token |
| Gateway ID | 已有网关，本轮default |
| 模型ID | openai/gpt-6-luna |
| 参数契约 | CF GPT-6 Luna Responses（思考与工具），cf-luna-responses-v1 |
| 思考强度 | medium已验证实际思考+工具；low聊天/SSE/工具通过，简单任务可能无思考项；none关闭 |
| 回复上限 | 本轮1536（含思考）；可按任务调整，必须小于上下文且不超过模型输出限制 |
| 模型能力 | 文本Agent勾选CHAT、REASONING、FUNCTION_CALLING；LONG_CONTEXT按真实容量声明；本契约暂不支持附件，勿用IMAGE_INPUT/VISION等发图 |

能力勾选用于路由，实际思考由契约中的effort控制。支持none/low/medium/high/xhigh/max，后四档中本轮仅medium做了真实验收，其余不宣称实测。加密思考用于续接，不展示原始思考链。旧effort-v1仍走Chat Completions，Luna在该路径带工具时必须显式none。Gemini BYOK保持原有独立连接和契约，不套用Luna契约。


## LLM-U7 当前 Responses 配置与边界（覆盖此前图片未支持结论）

- CF REST Base URL：`https://api.cloudflare.com/client/v4/accounts/<account_id>/ai/v1`；连接模式 `CF_GATEWAY`，Gateway ID 按现有网关填写，认证沿用 Cloudflare Token。不要在文档或截图暴露凭据。
- Luna 模型 ID：`openai/gpt-6-luna`；可继续使用 `cf-luna-responses-v1`，或显式选择新增 `cf-openai-responses-v1`。无需迁移旧配置。思考参数为 `effort`，本轮真实验收为 `medium`；请求使用 `max_output_tokens`。
- 其他 CF 目录中的 `openai/` 模型可以显式使用通用 Responses 契约；思考枚举是多模型选项集合，不保证每个模型全部支持。不确定时选“服务默认”，按具体模型文档选择；其他型号本轮未逐一真实测试。
- 能力按实际服务勾选：文本对话、工具调用、图片输入；思考由“生成与思考”参数控制，能力勾选不等于开启思考。图片经 `input_image` 输入，Luna 合成图片 SSE 已验证；媒体走工具路由时保留引用。
- 不支持的原生扩展：音视频、Realtime、图片生成、JSON Schema 输出约束、提供方内建搜索/代码执行。文件及音视频仍使用既有工具引用路径。通用 Responses 未扩展强制 CF BYOK 策略；Gemini BYOK 保持原路径。
- 页面保留共享连接提示、API Key 独立保存和脏草稿取消保护；参数 chip 的“服务默认”会移除该参数。切换契约保留草稿，不支持的参数需要显式清除。
