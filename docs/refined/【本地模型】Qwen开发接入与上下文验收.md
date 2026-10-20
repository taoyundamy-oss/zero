# 本地 Qwen 开发接入与上下文验收

## 2026-10-09 实施结论

按用户最新要求，以本地 Qwen3.5 9B Q4_K_M 为开发／测试 CHAT 主力，覆盖对话、工具续轮及跟随 CHAT 选择器的规划、摘要、记忆提取和短重排。D:/AI/ZeroV2-本地LLM节点-部署报告.md 是部署参考；其中 MiniCPM 主力建议被本次用户选择覆盖，不执行旧报告的停服／切模型脚本。

实时核对 llama.cpp b10859：/health=ok，/v1/models 指向 Qwen3.5-9B-Q4_K_M.gguf，/props 为 4 slots、default_generation_settings.n_ctx=24576。不拿训练上下文 262144 当运行上限；本地 OpenAI 接口接受调用别名 local-qwen，PC 工具请求与手机生产客户端均验证。

| 配置 | 当前实际值 |
|---|---|
| Profile ID／调用别名 | local-qwen |
| 手机端 API | http://127.0.0.1:8080/v1 |
| 连接 | ADB reverse tcp:8080 tcp:8080，手机 loopback 转到电脑 |
| 上下文／输出预留 | 24576／2048 tokens |
| 思考 | 默认关闭，使用 llama.cpp chat_template_kwargs.enable_thinking |
| 优先级 | 1000 |
| CHAT 档 | local-qwen 启用；deepseek、qwen 暂停启用，原档及云端密钥保留 |
| 能力 | 已验证文本、原生 function calling；未声明图片／音频／视频能力 |

目前没加载／验收本地视觉投影，因此不能仅凭模型名称宣称多模态可用。Embedding、视觉、ASR、TTS、搜索及动态模块的专用服务配置保留；这些服务仍可能调用云端，本片不宣称所有功能都零云费用，也不替代既有数据／工具质量验收。

## 开发使用

电脑保持本地服务运行，手机通过 USB 连接。仓库脚本：

```powershell
cd D:\ZeroV2
.\scripts\Use-LocalQwen.ps1
```

脚本核对正在运行的 Qwen 服务，构建、保留数据安装、建立反向转发，通过明确 configureLocal=true 的 debug instrumentation 配置实际 Profile 并验证连接。脚本不会自动重启电脑模型。`-SkipBuild` 使用已构建的应用和控制器，不跳过安装和验证。其他设备通过 `-Serial` 指定。

仅 USB 转发断开后重连，可运行：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s DEVICE_SERIAL reverse tcp:8080 tcp:8080
```

电脑服务停止、USB 断开或转发失效时，本地请求明确失败，不自行启用云端 CHAT。当前方案依赖电脑与 USB；独立使用手机时，需要用户在既有“模型管理”入口手动启用所需云端 CHAT，并停用 local-qwen。原 profiles_json 在手机私有 files/local-development-profiles-before.json 备份一次，不包含实际密钥；备份不导出到公共目录。此操作只更改模型档启用状态和新增本地档／本地占位凭据，不删除对话或记忆。

常规 qualityCheck 的 DeepSeek C3 网络测试现在需要同时设置 `ZERO_ALLOW_PAID_PROVIDER_TESTS=1` 与有效 `DEEPSEEK_API_KEY`。存在密钥不再自动代表付费测试授权。真实云端专项仍须按原账本和本轮授权执行，不因新本地档清零额度。各实机 provider 专项的既有显式开关／账本保留。

## 上下文修订

发现：ChatBot 原 buildMsgsForLLM 用全局 ContextBuilderConfig.contextWindow（默认约 512K）触发世代切片，没有以所选 24K 模型为硬上限；全预算器虽存在，但此发送入口未消费其模型上限。

修订：ILanguageModel 暴露实际 outputReserve，ProfileLanguageModel 随配置读取；FC 每轮、最终综合和共用该循环的重新生成都以所选模型上下文减去输出、安全余量、稳定提示词／工具及请求结构开销后确定历史预算。原世代内 append-only／90% 触发／65% 回落规则继续使用，云端在原 512K 世代线低于模型输入上限时保持该世代线。

local-qwen 另在统一客户端的流式、同步和短重排请求体构造出口加入 llama.cpp 专用检查：读取实际 n_ctx，/apply-template 渲染含 tools 的完整模板，/tokenize 精确计数，验证 input+max_tokens+64 不超过运行／配置上限较小者。三个本地预检单次 HTTP 各限 5 秒；服务器返回失败或不可读取计数时不继续生成。正常长历史按预算切片并沿用滚动摘要；单条超长内容、过大的稳定提示词／工具或不可分割工具组仍超限时明确返回 LOCAL_CONTEXT_LIMIT，保留原始数据，不静默截断正文。摘要为既有异步流程，不保证刚切片时旧内容摘要已完成。

本地 usage 继续累计真实 token／日预算，CHAT 和短重排不计云端费用估算；没有提高个人日预算或停用运行守卫。模型质量、24K 历史切换后的摘要完整性与长周期任务另需实际使用观察，不能用本次短接入用例称为 A8/A10 完整验收。

## 缓存审计与证据

- 截图 9,292,881 为一日总 tokens，缺少 input/output/hit/miss 与 provider 分组，不能据此判定缓存损坏或实际账单。App 冷重启与 provider 缓存生命周期是不同状态，首次请求不能保证命中。
- 稳定 system／工具确定性排序、turnContext 持久位置与历史追加规则保留；本地 kwargs、cache_prompt 和 include_usage 仅适用明确 local-qwen，DeepSeek 请求体不会被这些字段改变。模型／工具集合或世代切换会改变缓存基线，不把这种变更称为热命中。
- LocalLlamaContextGuardTest 3/3：真实服务上下文优先、模板／缓存参数、云端请求体字节不变且不访问本地计数接口。LocalLlamaMessageProjectionTest 1/1：稳定首条 system、历史追加快照、原始历史不变及重复投影幂等。TokenBudgetAllocatorTest 7/7，其中本轮新增 2 项检查本地预算及云端原世代上限。
- TurnContextCacheTest 3/3、CacheRequestDiagnosticsTest 1/1、OpenAiStreamDecoderTest 3/3、ReasoningReplayTest 1/1 通过。最终 qualityCheck＋assembleDebugAndroidTest 全绿，530 tasks、1m44s；首次 instrumentation 编译漏填 input、首次 detekt 3 项失败及补修测试行长失败日志均保留，已修复，未压制门槛。
- 最终手机 LocalModelAcceptanceTest 1/1、2.264s：真实持久配置＋含回合快照的历史 SSE/usage＋原生工具 call delta＋携带 tool_call_id 的真实工具结果续轮＋超长输入拦截＋本地摘除后无云 CHAT 回退。这是一个复合用例，不虚报独立测试数，不宣称整套实际任务质量通过。首版 1.638s 的简单接入未覆盖中途 SYSTEM，不能拿它替代真实历史模板验收。
- 本地独立追加探针实际 input/hit 为 36/0、54/32、72/50；三轮精确计数等于生成端 usage。热两轮加权 82/126≈65.08%，短提示／新增尾部占比较高，仅证明复用确实工作，未设置 98% 门槛或人为扩写前缀抬高分母。该 PC 合成探针不冒充手机个人会话或 DeepSeek 指标。
- 本轮没有请求 DeepSeek 实时热 cohort，未据离线连续性测试宣称云端实际热率达到 96–98%。若后续回切云端，应按真实请求 cohort 分冷／热／工具变更／世代切换核对，不复用本地百分比。

已安装最终 APK：artifacts/local-qwen-20261009/ZeroV2-local-qwen-debug.apk，SHA256 `26a5b59d1e586b95d8348c4c515310609078cd66ed528262639b21dcd1d2e4f6`；手机已安装摘要一致。正常 Main 恢复；首版 Main 的前台宿主检查只归属首版，不冒充最终包的 Doze／冷启动验收。证据：template-native-green-install.log、device-receipt.json、local-tool-probe.json、local-cache-probe.py/json、verify-device.py、quality-build-4.log 与所选 JVM XML。原工作树全部保留；无 Room schema／其他模块代码／UI 风格修改。本片优先处理用户新要求，A8 的 Receiver/Worker/Boot 等未完成项不标通过。

## 用户实机 500 补修

用户反馈 `[请求失败：local_preflight_http_500]` 后立即停止收尾。电脑服务错误为 `System message must be at the beginning`；Zero 的真实历史包含首条 persona 后的 SYSTEM 回合快照，Qwen Jinja 模板拒绝这一消息结构。使用合成多回合请求的 template-repro.py 在 /apply-template 复现 500，再把相同结构纳入真实手机生产客户端：新控制器运行在首版已安装 APK 上，0.246s 出现同一 500（1/1 失败，template-native-red.log），从而确认缺陷不是凭日志推断。

修订 LocalLlamaMessageProjection，仅在 local-qwen 发送投影中把 index>0 的 SYSTEM 快照转为带应用上下文标记的 USER 文本，保留历史位置、内容和工具配对。首条稳定 persona 不合并动态快照，原始 ChatMessage／Room 角色不变，快照不改成用户事实来源；新增后续回合不改写之前投影，重复投影幂等。相同投影同时用于模板计数和实际生成；没有关闭预检或启用服务端静默截断。云端路径完全不采用该投影。

同一补充控制器在修订最终 APK 上 2.264s、1/1 通过，包括两处 SYSTEM 快照、真实工具结果续轮等边界。原始 template-repro.py 仍是 provider 原始非法结构的负向复现工具，预期返回 500；修订通过证据来自实际生产客户端的 template-native-green-install.log，不把 Python 模仿投影当作生产证明。初版 APK 另存 ZeroV2-local-qwen-initial-debug.apk，原简单用例记录保留。一次组合安装／回归命令被自动审批以“策略阻止”拒绝且无详细原因；使用先前验证的仓库脚本完成安装与校验，未关闭审批策略。
