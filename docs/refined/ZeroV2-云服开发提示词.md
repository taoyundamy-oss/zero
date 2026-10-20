# Claude Code 云服轨开发启动提示词

> 将此提示词粘贴到 Claude Code 新会话中，即可继续 Zero V2 **云服轨**（C0–C4）开发。
> 参考 `CLAUDE_CODE_PROMPT.md` 结构，适配云服+Android 双端开发。
> 权威文档：`ZeroV2-云服模块化架构设计.md`（架构/决策 D81–D100）> `ZeroV2-云服落地实施计划.md`（Step 实施细节）。

---

## 版本 A：完整启动（首次会话 / 中断后恢复）

```
你正在开发 Zero V2 的云服轨（C0–C4）。项目位于 D:\ZeroV2，云服在阿里云 ECS。

## 第一步：加载上下文（只读，不修改任何文件）

依次读取：
1. `D:\ZeroV2\docs\refined\ZeroV2-云服落地实施计划.md` §五 进度追踪表 —— 确认"当前 Step"
2. `D:\ZeroV2\docs\refined\ZeroV2-云服模块化架构设计.md` —— 架构决策（D81–D100）
3. 该 Step 的详细定义 + 验证标准
4. `D:\ZeroV2\docs\refined\ZeroV2-云服落地实施计划.md` §〇 开发约束（E1–E7 实战教训）

## 第二步：执行当前 Step Cx.y

严格按落地实施计划该 Step 的定义：
- 云服端代码修改后通过 Workbench CLI 上传 → docker compose rebuild → 验证
- Android 端代码修改后本地构建 APK（_build_apk.bat）→ 交用户实测
- 每步只做一个 Step，验证通过后再推进

## 行为规则

1. **每次只做一个 Step**。完成后停下，让用户验证。
2. **Android 端改动 ≤3 文件/Step**；云服端改动 ≤1 逻辑单元/Step。文件数量可因迁移而临时超过 3，但必须保持单一目标，不改无关代码。
3. **先读后写**。修改既有文件前，先 Read 确认当前内容。
4. **MCP 协议约束（E3）**：所有 MCP 响应必须返回可解析 JSON，禁止 204/空 body。
5. **Token 认证（E2）**：云服 Token 放在 URL `?token=` 参数（McpServersScreen 不存 Key 的 Bug 已有回退）。
6. **Docker 镜像（E4）**：daemon.json 已配 DaoCloud 镜像，不需额外操作。
7. **Workbench CLI（E5）**：
   - 远程执行：`$WB exec -i i-bp16l4ohdfaopfsou4nw -c "command"`
   - 上传必须用 PowerShell：`powershell.exe -Command "& '$WB' upload '<local>' '/remote/' --instance-id i-bp16l4ohdfaopfsou4nw --force"`
   - 超时 30s——长命令用 nohup 后台 + 轮询日志
8. **Android 构建后自动验证**：Build 成功 + APK 时间戳更新 + 交用户实测。
9. **云服不可达降级（P1-3）**：所有 cloud_*/wechat_* 工具异常时返回"云服不可达"引导文案。
10. **埋点**：每个 Gateway 工具入口/出口 JSON 日志（tool, event, duration_ms）。

## 第三步：输出标准格式

### Step Cx.y 完成

**云服端改动**：
- 修改: path/to/file.py（变化描述）
- 部署: 上传 → rebuild → 重启

**Android 端改动**：
- 修改: path/to/File.kt（变化描述）
- APK: 已构建（时间戳）

**验证要求**：
- [ ] 服务器端验证（curl / docker logs）
- [ ] Android 端实测（待用户确认）

## 第四步：更新状态

修改 `ZeroV2-云服落地实施计划.md` §五 进度表：当前 Step ✅、下一步 🔧。

---

遇到无法确定的实现选择，参考 `ZeroV2-云服模块化架构设计.md` 对应决策编号。
若仍无答案，向我提问而不是自作主张。
```

---

## 版本 B：精简启动（连续开发）

```
继续 Zero V2 云服轨（C0–C4）开发。

1. 读 `ZeroV2-云服落地实施计划.md` §五 确认当前 Step
2. 读该 Step 定义 + 架构设计对应决策
3. 执行（云服端 Workbench CLI 部署 / Android 端本地构建 APK）
4. 输出标准格式 + 更新进度表

行为约束：
- 遵守 §〇 E1–E7 实战教训（MCP JSON 响应/Token URL 参数/DaoCloud 镜像/PowerShell 上传/nohup 后台）
- Android ≤3 文件/Step，构建后交用户实测
- 云服 ≤1 逻辑单元/Step，docker compose rebuild 后 curl 验证
- 不确定时提问
```

---

## 版本 C：修复模式

```
云服轨开发反馈。

信息：
[粘贴错误/Trace 日志]

请分析根因，修复错误。
- 云服端问题：修改代码 → Workbench CLI 上传 → rebuild → curl 验证
- Android 端问题：修改代码 → 构建 APK → 交用户实测
只改必要文件。
```

---

## 版本 D：审查模式（阶段完成）

```
云服轨 阶段 [C0/C1/C2/C3/C4] 完成。审查是否符合设计。

1. 读 `ZeroV2-云服模块化架构设计.md` 该阶段对应章节
2. 读 `ZeroV2-云服落地实施计划.md` 该阶段所有 Step
3. 检查：
   - 服务器：docker ps 全部 running + health 200 + MCP 三步握手通过
   - Android：MCP 工具全部发现 + cloud_search 端到端可用
   - 安全：HTTPS 有效 + Token 认证 + 无 204 空 body
4. 输出审查报告
```

---

## 使用方式

```
版本 A: 首次启动 / 中断恢复
版本 B: 连续开发
版本 C: Build/运行失败
版本 D: 阶段完成审查

推荐节奏:
  A(1次) → B×N → 失败→C → 修复→B → 阶段完成→D → 下一阶段
```
