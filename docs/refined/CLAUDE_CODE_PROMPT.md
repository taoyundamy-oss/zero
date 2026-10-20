# Claude Code 开发启动提示词

> 将此提示词粘贴到 Claude Code 新会话中，即可继续 Zero V2 开发。
> 分两个版本：完整版（首次或需要完整上下文时）和精简版（连续开发时）。

---

## 版本 A：完整启动（首次会话 / 中断后恢复）

```
你正在开发 Zero V2，一个 Android AI Agent 应用。项目位于 D:\ZeroV2。

## 第一步：加载上下文（只读，不修改任何文件）

请依次读取以下文件，了解项目全貌：

1. `D:\ZeroV2\docs\refined\DEVELOPMENT_STATUS.md` — 当前进度
2. `D:\ZeroV2\docs\refined\CLAUDE_CODE_IMPLEMENTATION_PLAN.md` — 开发计划
3. `D:\ZeroV2\docs\refined\DEVELOPMENT_DESIGN.md` — 架构设计（如首次读取）

根据 DEVELOPMENT_STATUS.md 中的"当前 Step"，精读 CLAUDE_CODE_IMPLEMENTATION_PLAN.md 中对应 Step 的任务描述。

## 第二步：执行当前 Step

执行 DEVELOPMENT_STATUS.md 中记录的"当前 Step"。严格按照 CLAUDE_CODE_IMPLEMENTATION_PLAN.md 中该 Step 的定义：
- 只创建/修改该 Step 指定的文件
- 不改动无关文件
- 不改动 Step 范围外的功能

## 行为规则

1. **每次只做一个 Step**。完成后停下来，让用户验证 Build。
2. **改动 ≤ 3 个文件**。如果 Step 涉及更多文件，拆分为子步骤。
3. **先读后写**。修改任何现有文件前，先用 Read 工具确认其当前内容。
4. **参考标杆模块**。新代码的风格、模式与现有代码一致：
   - FC 函数 → 参考 WebSearchModule
   - Room → 参考 MemoryDao / MemoryEntity
   - Compose → 参考 ModuleDetailScreen
   - DI → 参考 AppBindingModule
5. **埋 AgentTrace**。每个关键集成点加一句 `traceManager.record(...)`。
6. **不改 build.gradle**。除非 Step 明确要求新增依赖。

## 第三步：输出标准格式

完成后输出：

### Step X.Y 完成: [Step 名称]

**改动文件**:
- 新增: path/to/NewFile.kt (N 行)
- 修改: path/to/ChangedFile.kt (变化: 简要描述)

**验证要求**:
- [ ] Build 通过
- [ ] 具体功能验证点

**如果 Build 失败**: 请粘贴完整错误信息，我会立即修复。

## 第四步：更新状态

修改 `D:\ZeroV2\docs\refined\DEVELOPMENT_STATUS.md`：
- 将"当前 Step"更新为下一步
- 将完成的 Step 加入"已完成 Step"列表
- 如有阻塞项，记录到"阻塞项"

---

如果遇到无法确定的实现选择（如：某个参数应该放 Config 还是硬编码），参考 DEVELOPMENT_DESIGN.md 中的设计原则。如果设计文档也没有答案，向我提问而不是自行决定。
```

---

## 版本 B：精简启动（连续开发，已了解项目）

```
继续 Zero V2 开发。项目在 D:\ZeroV2。

1. 读取 `D:\ZeroV2\docs\refined\DEVELOPMENT_STATUS.md` 确认当前进度
2. 读取 `D:\ZeroV2\docs\refined\CLAUDE_CODE_IMPLEMENTATION_PLAN.md`、"D:\ZeroV2\docs\refined\ROADMAP_P5_P8.md"中当前 Step 的任务描述，并参考"D:\ZeroV2\docs\refined\DEVELOPMENT_DESIGN.md"中的设计要求和规范
3. 执行该 Step（每 Step ≤3 个“逻辑责任单元”，文件数量可因迁移而临时超过 3，但必须保持单一目标，不改无关代码）
4. 完成后输出标准格式（改动文件 + 验证要求）
5. 更新 DEVELOPMENT_STATUS.md

行为约束：
- 参考现有代码风格（代码符合安卓Google开发规范）
- 关键路径埋 AgentTrace
- 不确定时提问，不自作主张
- 检验Build成功，并构建输出最新apk文件
```

---

## 版本 C：修复模式（Build 失败后）

```
Zero V2 Build测试反馈。项目在 D:\ZeroV2。

信息：
[粘贴完整编译错误]

请读取相关文件，分析根因，修复错误。只修改必要的文件，不做额外改动。
修复后告诉我改了什么。
```

---

## 版本 D：审查模式（Phase 完成，检查一致性）

```
Zero V2 Phase [N] 完成。审查当前代码是否符合设计。

1. 读取 `D:\ZeroV2\docs\refined\DEVELOPMENT_DESIGN.md` 中该 Phase 对应章节
2. 读取 `D:\ZeroV2\docs\refined\CLAUDE_CODE_IMPLEMENTATION_PLAN.md` 中该 Phase 的所有 Step
3. 检查当前代码库中该 Phase 涉及的所有文件
4. 逐项对照：所有 Step 的产物是否到位？接口签名是否与设计一致？Hilt 绑定是否注册？
5. 输出审查报告，列出：
   - ✅ 已完成的项
   - ⚠️ 部分完成或与设计有偏差的项
   - ❌ 遗漏的项
```

---

## 版本 E：单一文件修改（小修复或微调）

```
修改 D:\ZeroV2 中的 [文件路径]。

修改内容：[具体描述]

约束：
- 先 Read 确认当前内容再修改
- 不改动无关代码
- 保持与周围代码风格一致
```

---

## 使用方式

```
新会话开始时，用户复制版本 A 或 B 的提示词，粘贴到 Claude Code 中。

版本 A: 首次启动 / 中断超过 1 天后恢复 / 需要重新建立上下文
版本 B: 刚完成上一个 Step，Build 通过，立即继续下一个 Step
版本 C: 上一个 Step Build 失败
版本 D: Phase 完成，做质量检查
版本 E: 临时小修改

推荐节奏:
  版本 A（1次）→ 版本 B × N（连续推进 Step）→ Build失败 → 版本 C（修复）→ 版本 B × N ...
                                               → Phase完成 → 版本 D（审查）→ 版本 A（下一Phase）
```
