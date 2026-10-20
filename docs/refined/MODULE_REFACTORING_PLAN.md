# Zero v2 — 模块重构方案（对标 Alife 源码）

> 基于 Alife v4.2.0 源码分析 + Zero v2 当前状态
> 2026-08-11

---

## 一、Alife 模块架构分析

### 1.1 核心发现

Alife **没有集中式 ModelRouter 或统一 LLMClientModule**。每个模型提供商是一个独立模块，各自管理自己的配置、API Key 和调用逻辑。

```
Alife.Function.Language.OpenAI/
├── OpenAILanguageModel.cs       ← 实现 ILanguageModel + IConfigurable<T>
├── OpenAILanguageModelConfig.cs ← endpoint, modelId, apiKey, extraBody, ...
├── OpenAILanguageModelUI.razor  ← Blazor UI 子页面
└── manifest.json               ← 版本 + NuGet 依赖

Alife.Function.Vision.OpenAI/
├── OpenAIVisionModel.cs         ← 实现 IVisionModel + IConfigurable<T>
├── OpenAIVisionModelConfig.cs   ← BaseUrl, ModelName, ApiKey
├── OpenAIVisionModelUI.razor
└── manifest.json

Alife.Function.Vision.Qwen/      ← 另一个视觉模型实现
Alife.Function.Vision.MiniCPM/   ← 又一个视觉模型实现（本地模型）
Alife.Function.Speech.EdgeTTS/   ← 语音合成实现
Alife.Function.Speech.Genie/     ← 另一个语音实现（本地模型）
```

### 1.2 配置类对比

**Alife OpenAILanguageModelConfig**：
```csharp
public class OpenAILanguageModelConfig {
    public string endpoint = "";       // 端点 URL
    public string modelId = "";        // 模型 ID
    public string apiKey = "";         // API Key（模块独立管理）
    public bool defaultThinking = false;
    public string extraHeaders = "";
    public string reasoningEffort = "low";
    public string extraBody = "{ \"thinking\": {\"type\": \"enabled\"} }";
}
```

**Alife OpenAIVisionModelConfig**：
```csharp
public class OpenAIVisionModelConfig {
    public string BaseUrl = "https://api.moonshot.cn/v1/chat/completions";
    public string ModelName = "moonshot-v1-8k-vision-preview";
    public string ApiKey = "";
}
```

**关键特征**：
1. 每个模块有自己的 `apiKey` / `ApiKey` 字段 — 独立管理
2. 每个模块有自己的 `endpoint` / `BaseUrl` — 独立端点
3. 配置文件由框架自动加载为 JSON（`IConfigurable<T>` 模式）
4. 用户通过角色配置（`index.json` 的 `Modules` 数组）选择激活哪些模块
5. 没有集中式路由 — 用户激活 DeepSeek 模块就只用 DeepSeek，激活 Qwen 就只用 Qwen

### 1.3 模块注册与发现

```csharp
[Module("OpenAI语言模型", "...", 
    defaultCategory: "Alife 官方/模型接入/语言模型",
    editorUI: typeof(OpenAILanguageModelUI))]
public class OpenAILanguageModel : ChatBehaviour, ILanguageModel, IConfigurable<...>
```

- `[Module]` 属性声明模块元数据
- `ChatBehaviour` 基类提供生命周期
- `ILanguageModel` 接口声明能力
- `IConfigurable<T>` 声明配置类型
- `editorUI` 指向自定义 UI 子页面

### 1.4 与 Zero v2 当前设计的对比

| 维度 | Zero v2 当前 | Alife |
|------|------------|-------|
| 模型管理 | LLMClientModule 集中管理 → ModelRouter 路由 | 每提供商独立模块，用户选择激活哪个 |
| API Key | SecureKeyStore 集中存储 | 每模块 Config 中独立 apiKey 字段 |
| 配置存储 | Room module_configs 表 + Encrypted SP | 每模块独立 JSON 文件 |
| 模块切换 | UI 下拉框动态切换 | 用户激活/禁用整个模块 |
| 多模型并存 | 一个 LLMClientModule 包含多个 Adapter | DeepSeek 模块、Qwen 模块、OpenAI 模块分别存在 |

---

## 二、重构方案

### 2.1 核心原则：每提供商独立模块

```
重构前:
  LLMClientModule (单体)
    ├── DeepSeekAdapter
    ├── QwenAdapter
    └── CustomOpenAIAdapter
  WebSearchModule → 复用 LLM 的 Qwen Key
  VisionModule → 复用 LLM 的 Qwen Key

重构后:
  DeepSeekChatModule    ← 独立模块：自管 Key、自管端点、独立配置
  QwenChatModule        ← 独立模块：自管 Key、自管端点、独立配置
  QwenWebSearchModule   ← 独立模块：自带搜索 API Key
  QwenVisionModule      ← 独立模块：自带视觉 API Key
  (未来) OpenAIChatModule
  (未来) ClaudeChatModule
```

每个模块：
- 实现 `IZeroModule`
- 自带 `Config` 类（含 `apiKey`, `endpoint`, `modelId`）
- 自带 ModuleDetailScreen 配置 UI
- 完全独立，不依赖其他模块的 Key

### 2.2 新的 LLM 模块结构

```
services/src/main/java/com/zero/v2/services/llm/
├── ILanguageModel.kt              ← 语言模型接口（对标 Alife ILanguageModel）
├── DeepSeekChatModule.kt          ← DeepSeek 对话模块
├── DeepSeekChatConfig.kt          ← apiKey, modelId, endpoint
├── QwenChatModule.kt              ← Qwen 对话模块
├── QwenChatConfig.kt              ← apiKey, modelId, endpoint
├── OpenAIChatModule.kt            ← OpenAI 对话模块（未来）
└── OpenAIChatConfig.kt

services/src/main/java/com/zero/v2/services/vision/
├── QwenVisionModule.kt            ← Qwen 视觉模块
└── QwenVisionConfig.kt            ← apiKey, modelId, endpoint

services/src/main/java/com/zero/v2/services/search/
├── QwenWebSearchModule.kt         ← Qwen 搜索模块
└── QwenWebSearchConfig.kt         ← apiKey, searchModelId, endpoint
```

### 2.3 配置类设计

```kotlin
// DeepSeekChatConfig.kt
class DeepSeekChatConfig : ModuleConfigBase() {
    @ConfigField("API Key", TEXT, password=true)
    var apiKey: String = ""

    @ConfigField("模型 ID", DROPDOWN, options=["deepseek-chat","deepseek-reasoner"])
    var modelId: String = "deepseek-chat"

    @ConfigField("端点 URL", TEXT)
    var endpoint: String = "https://api.deepseek.com/v1"

    @ConfigField("Temperature", SLIDER, min=0.0, max=2.0)
    var temperature: Float = 0.7f

    @ConfigField("最大 Token", SLIDER, min=256.0, max=8192.0)
    var maxTokens: Int = 4096

    @ConfigField("启用思考模式", TOGGLE)
    var enableThinking: Boolean = true
}

// QwenChatConfig.kt
class QwenChatConfig : ModuleConfigBase() {
    @ConfigField("API Key", TEXT, password=true)
    var apiKey: String = ""

    @ConfigField("模型 ID", DROPDOWN, options=["qwen3.8-max","qwen-plus","qwen-turbo"])
    var modelId: String = "qwen3.8-max"

    @ConfigField("端点 URL", TEXT)
    var endpoint: String = "https://ws-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"

    @ConfigField("Temperature", SLIDER, min=0.0, max=2.0)
    var temperature: Float = 0.7f

    @ConfigField("最大 Token", SLIDER, min=256.0, max=8192.0)
    var maxTokens: Int = 4096

    @ConfigField("启用思考模式", TOGGLE)
    var enableThinking: Boolean = false
}

// QwenWebSearchConfig.kt
class QwenWebSearchConfig : ModuleConfigBase() {
    @ConfigField("API Key", TEXT, password=true)
    var apiKey: String = ""

    @ConfigField("搜索模型", DROPDOWN, options=["qwen3.5-flash","qwen3.5-plus"])
    var searchModelId: String = "qwen3.5-flash"

    @ConfigField("端点 URL", TEXT)
    var endpoint: String = "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1"

    @ConfigField("最大返回数", NUMBER, min=1.0, max=20.0)
    var maxResults: Int = 10
}

// QwenVisionConfig.kt
class QwenVisionConfig : ModuleConfigBase() {
    @ConfigField("API Key", TEXT, password=true)
    var apiKey: String = ""

    @ConfigField("视觉模型", DROPDOWN, options=["qwen3-vl-flash","qwen3-vl-plus"])
    var visionModelId: String = "qwen3-vl-flash"

    @ConfigField("端点 URL", TEXT)
    var endpoint: String = "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1"
}
```

### 2.4 ILanguageModel 接口（对标 Alife）

```kotlin
/**
 * 语言模型接口 — 对标 Alife ILanguageModel。
 * 每个对话模型模块实现此接口。
 */
interface ILanguageModel {
    val modelId: String
    val displayName: String
    val contextWindow: Int

    suspend fun chatSync(
        userMessage: String,
        systemPrompt: String?
    ): String

    fun chatStream(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        functions: List<FunctionDefinition>
    ): Flow<StreamChunk>
}
```

### 2.5 模块选择机制

对标 Alife 的角色 `index.json` 中的 `Modules` 数组：

```json
{
  "Modules": [
    "Alife.Function.Language.OpenAI.OpenAILanguageModel",
    "Alife.Function.Vision.Qwen.QwenVisionModel",
    "Alife.Function.Speech.EdgeTTS.EdgeTTSSpeechModel"
  ]
}
```

Zero v2 对应：用户在 `ModuleCenterScreen` 中启用/禁用模块。
- 启用 `DeepSeekChatModule` → ChatBot 使用 DeepSeek 对话
- 启用 `QwenChatModule` → ChatBot 使用 Qwen 对话
- 同时启用两个 → ChatBot 使用优先级高的那个（或用户配置优先级）

ChatBotModule 通过 `ModuleLifecycleManager` 查找所有已启用的 `ILanguageModel` 实现：

```kotlin
// ChatBotModule 自动发现活跃的语言模型
private val activeLanguageModel: ILanguageModel? by lazy {
    moduleManager.getEnabled()
        .filterIsInstance<ILanguageModel>()
        .minByOrNull { it.metadata.launchOrder }  // launchOrder 小的优先
}
```

### 2.6 模块详情页

每个模块点击进入详情页后，AutoForm 渲染该模块的 Config 字段：

```
┌─────────────────────────────────────────┐
│  ← 返回      🤖 DeepSeek 对话           │
├─────────────────────────────────────────┤
│  🟢 已启用 | DeepSeek Chat V3           │
│                                         │
│  API Key     [sk-397b...]  👁           │
│  模型 ID     [deepseek-chat    ▼]       │
│  端点 URL    [api.deepseek.com/v1]      │
│  Temperature [========●=========] 0.7   │
│  最大 Token  [256             8192]     │
│  ☑ 启用思考模式                         │
│                                         │
│              [保存配置]                  │
└─────────────────────────────────────────┘
```

### 2.7 文件变更清单

```
删除 (6):
  services/llm/LLMClientModule.kt        ← 集中式 LLM 客户端
  services/llm/LLMClientConfig.kt        ← 集中式配置
  services/llm/ModelRouter.kt            ← 集中式路由
  services/llm/ModelConfigModule.kt      ← 集中式配置模块
  services/llm/DeepSeekAdapter.kt        ← 改为 DeepSeekChatModule
  services/llm/QwenAdapter.kt            ← 改为 QwenChatModule
  services/llm/CustomOpenAIAdapter.kt    ← 改为独立模块
  core/common/ModelAdapter.kt            ← 不再需要统一 Adapter 接口

新增 (8):
  core/common/ILanguageModel.kt          ← 语言模型接口
  services/llm/DeepSeekChatModule.kt     ← DeepSeek 独立模块
  services/llm/DeepSeekChatConfig.kt     ← DeepSeek 配置
  services/llm/QwenChatModule.kt         ← Qwen 独立模块
  services/llm/QwenChatConfig.kt         ← Qwen 配置
  services/vision/QwenVisionModule.kt    ← Qwen 视觉模块（已有，需重构）
  services/vision/QwenVisionConfig.kt    ← Qwen 视觉配置
  services/search/QwenWebSearchModule.kt ← Qwen 搜索模块（已有，需重构）
  services/search/QwenWebSearchConfig.kt ← Qwen 搜索配置

保留 (2):
  services/security/SecureKeyStore.kt    ← 保留但每模块独立 key 前缀
  core/data/store/ModuleConfigStore.kt   ← 保留 Room 配置持久化

修改 (2):
  framework/chat/ChatBotModule.kt        ← 注入 ILanguageModel 而非 LLMClientModule
  app/di/AppBindingModule.kt             ← 注册新模块
```

### 2.8 与当前设计的区别总结

| 当前设计 | 重构后 |
|---------|--------|
| LLMClientModule 包含所有模型 | 每个模型独立模块 |
| ModelRouter 动态切换 | 用户启用/禁用模块决定使用哪个 |
| 集中式 SecureKeyStore 管理所有 Key | 每模块 Config 中 apiKey 字段 + SecureKeyStore 加密存储 |
| 一个 LLMClientConfig | DeepSeekChatConfig, QwenChatConfig 各自独立 |
| UI 下拉框切换模型 | UI 启用/禁用整模块 |
| WebSearch/Vision 复用 LLM Qwen Key | WebSearch/Vision 自带独立 apiKey 字段 |
