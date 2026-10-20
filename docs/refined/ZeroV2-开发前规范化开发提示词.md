# Claude Code 开发前规范化轨开发启动提示词

> 将此提示词粘贴到 Claude Code 新会话中，即可继续 Zero V2 **开发前规范化轨**（FC 缺陷修复 / LLM 重构 / 文件合并 / 数值定界 / BudgetGuard，N0–N5）开发。
> 参考 `CLAUDE_CODE_PROMPT.md` 的 5 版本结构；本轨为**模块化轨（M0–M4）的前置**。
> 权威文档：`ZeroV2-开发前规范化设计.md`（决策/根因/数值）> `ZeroV2-开发前规范化落地实施计划.md`（Step 实施细节 + 交叉验证 X1–X8）。

---

## 版本 A：完整启动（首次会话 / 中断后恢复）

```
你正在开发 Zero V2 的开发前规范化轨（N0–N5）。项目位于 D:\ZeroV2。

## 第一步：加载上下文（只读，不修改任何文件）

依次读取：
1. `D:\ZeroV2\docs\refined\ZeroV2-开发前规范化落地实施计划.md` §九 进度追踪表 —— 确认"当前 Step"
2. `D:\ZeroV2\docs\refined\ZeroV2-开发前规范化设计.md` —— 决策/根因（§五 FC 缺陷、§七 数值定界、§八 预算）
3. 标杆模块（按当前 Step 选读）：
   - FC 注册 + SAF → `app/src/main/java/com/zero/v2/app/data/WebFetchModule.kt`
   - FC 循环（N0 必读）→ `framework/chat/src/main/java/com/zero/v2/framework/chat/ChatBotModule.kt`
     （关键行号：212 while 循环 / 224-230 msgsForLLM 构建 / 311-317 finally 完成事件）
   - MCP 动态注册 / 结果处理（N4.4）→ `services/src/main/java/com/zero/v2/services/mcp/McpClientModule.kt`
   - 专用存储 + 专用 UI 先例（N2 profile 必读）→ `services/.../mcp/McpServerStore.kt` + `app/.../ui/settings/McpServersScreen.kt`
   - ConfigField 注解（N4.1）→ `core/module-api/.../ConfigField.kt`（25-26 行默认 min/max）
   - 预算存储（N4.2/N5）→ `app/.../ui/settings/AgentBehaviorViewModel.kt`（38-39 行默认值）+ `AgentBehaviorScreen.kt`（126-132 滑杆）
   - ConfigField 范式 → `services/.../llm/DeepSeekChatConfig.kt`

精读落地实施计划中"当前 Step"的 创建/修改/验证 + 代码骨架。

## 第二步：执行当前 Step Nx.y

严格按落地实施计划该 Step 定义：只创建/修改指定文件；代码骨架已给出按其实现；不改无关代码。

## 行为规则

1. **每次只做一个 Step**。完成后停下，让用户 Build + 实测。
2. **改动 ≤3 文件或 ≤1 逻辑单元**。
3. **先读后写**。修改 ChatBotModule/McpClientModule/ConfigField.kt 等既有文件前，先 Read 确认当前签名与行号。
4. **参考标杆模块**，风格与现有代码一致（安卓 Google 规范）。
5. **埋 AgentTrace**：fc_loop_capped / read_paged / llm_call / config_clamped / mcp_amapuri_rewritten / budget_*。
6. **ConfigField 纪律**：新字段必须显式 min/max（防 §7.1 默认 max=100 脚枪）；类型仅 Int/Float/Boolean（D30 反射写入约束）；SECRET 不进 AutoForm。
7. **N4.1 与 N4.2 必须同一 commit**（注解默认放开=无界字段裸奔，高危约束）。
8. **N3 合并时 FC 名全部保持原样**（read_work_file 等 7 个，LLM 已熟悉；仅模块 id 变）。
9. **N5 BudgetGuard fail-open**：守护自身故障（存储读失败）→ 放行+warn，绝不砖死对话。
10. **N2/N3 渐进双轨**（§6.5）：先新建并行→验证→切换→删旧；每子步 commit 可回退。
11. **不改 build.gradle**，除非 Step 明确要求。
12. 真机验证项（65KB 分页读 / Live 播报 / 预算触顶）标"待用户实测"。

## 第三步：输出标准格式

### Step Nx.y 完成: [Step 名称]

**改动文件**:
- 新增: path/to/NewFile.kt (N 行)
- 修改: path/to/ChangedFile.kt (变化: 简要描述)

**验证要求**:
- [ ] Build 通过（:app:assembleDebug）
- [ ] 实测项（如适用，标注"待用户实测"）

**如果 Build 失败**: 请粘贴完整错误信息，我会立即修复（只改必要文件）。

## 第四步：更新状态

修改 `ZeroV2-开发前规范化落地实施计划.md` §九 进度表：当前 Step ✅、下一步 🔧；阻塞记入表后注。

---

遇到无法确定的实现选择，参考 `ZeroV2-开发前规范化设计.md` 对应章节与交叉验证 X1–X8（落地计划 §八）。若仍无答案，向我提问而不是自作主张。
```

---

## 版本 B：精简启动（连续开发）

```
继续 Zero V2 开发前规范化轨（N0–N6）开发。项目在 D:\ZeroV2。

1. 读 `ZeroV2-开发前规范化落地实施计划.md` §九 确认当前 Step
2. 读该 Step 的 创建/修改/验证 + 骨架，参考 `ZeroV2-开发前规范化设计.md` 对应决策
3. 执行（≤3 文件或 ≤1 逻辑单元，不改无关代码）
4. 输出标准格式（改动文件 + 验证要求 + 实测项标注）
5. 更新 §九 进度表

行为约束：
- 先 Read 核心文件确认签名/行号（ChatBotModule:212/224-230/311-317、ConfigField.kt:25-26、AgentBehaviorViewModel:38-39）
- ConfigField 显式 min/max、仅 Int/Float/Boolean（D30）
- N4.1+N4.2 同一 commit；N3 FC 名不变；N5 fail-open；N2/N3 双轨渐进
- 关键路径埋 AgentTrace；不确定时提问；检验 Build 并输出最新 APK
```

---

## 版本 C：修复模式（Build 失败后）

```
Zero V2 开发前规范化轨 Build 反馈。项目在 D:\ZeroV2。

信息：
[粘贴完整编译错误]

请读取相关文件（含 ChatBotModule/ConfigField.kt 等既有签名），分析根因，修复错误。
只改必要文件；保持 ConfigField 纪律/FC 名不变/fail-open 等约束（提示词版本 A 规则 6-10）。
修复后告诉我改了什么 + 涉及哪条约束。
```

---

## 版本 D：审查模式（阶段完成）

```
Zero V2 开发前规范化轨 阶段 [N0/N1/N2/N3/N4/N5] 完成。审查是否符合设计。

1. 读 `ZeroV2-开发前规范化设计.md` 对应章节（§一-§五/§七/§八）
2. 读 `ZeroV2-开发前规范化落地实施计划.md` 该阶段所有 Step + §八 交叉验证 X1–X8
3. 检查代码库涉及文件（framework/chat、app/data、services/llm、services/realtime、services/mcp、core/module-api、app/ui/settings）
4. 逐项对照：Step 产物到位？接口签名一致？Hilt 绑定注册？决策沿用（附清单）？
   - N0：maxFcRounds 生效 + 最终综合轮 + 末尾 ASSISTANT 触发 ChatFinishedEvent
   - N2：ModelSelector API 不变 + profile 专用存储（非扁平 ConfigField）+ 旧 Adapter 已删
   - N3：7 个 FC 名与权限原样 + ConfigMigration 生效
   - N4：无无界数值字段（N4.1+N4.2 同 commit）+ 预算默认 20M/$10
   - N5：BudgetGuard fail-open + 全 API 计量（chat/TTS/embedding/vision）
5. 输出：✅ 完成 / ⚠️ 偏差 / ❌ 遗漏 + 真机待验项
```

---

## 版本 E：单一文件修改

```
修改 D:\ZeroV2 中的 [文件路径]（开发前规范化轨）。

修改内容：[具体描述]

约束：
- 先 Read 确认当前内容（核心文件注意行号锚）
- 不改无关代码；保持 ConfigField 纪律（显式 min/max、D30 类型）
- 保持与周围代码风格一致
```

---

## 使用方式

```
版本 A: 首次启动本轨 / 中断超 1 天恢复
版本 B: 刚完成上一 Step、Build 通过、立即继续
版本 C: Build 失败
版本 D: 阶段（N0–N5）完成质量对照
版本 E: 临时小修改

推荐节奏:
  A(1 次) → B × N（N0.1→N0.2→N1.x→N2.1–N2.6→N3.x→N4.1+N4.2(同 commit)→N4.3–N4.5→N5）
  → Build 失败 → C → B × N … → 阶段完成 → D → 下一阶段
  本轨全部完成 → 切模块化轨提示词（ZeroV2-模块化开发提示词.md，M0–M4）
```

---

## 附：轨际衔接

- **进度归属**：本轨进度记于 `ZeroV2-开发前规范化落地实施计划.md §九`；主线轨（DEVELOPMENT_STATUS.md）与模块化轨（模块化落地计划 §七）各自独立。
- **顺序**：本轨（N0–N5）→ 模块化轨（M0–M4）。N2 的 `OpenAiCompatibleClient` 是模块化轨 M5(自编译) LLM 调用与 N5 计量的共同地基。
- **决策统一**：本轨全部沿用既有 D 系决策（见落地计划附清单）；新增决策记入设计文档，不另立体系。
