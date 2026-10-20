# 反馈：`save_to_file` 追加/覆盖两方向对调（append=true 原文丢失）· 给 CC

> 发现时间：2026-09-18 23:xx（真机）
> 严重度：**高**（知识库写入路径的数据丢失——静默、不可恢复）
> 复现成本：低（一次 append 保存 + 读回即可判定）
>
> **✅ 第一轮已修复（2026-09-18，真机 4 项实测：追加保留原文 ✅ / 字符串 "true" ✅ / append=false 新建 ✅）**
> 根因（两个方向各自独立，表象呈"对调"）：
> ① append=true → 原文丢失：`WorkDirRepository.appendSmart` 的 `openOutputStream(uri, "wa")` 直写——追加语义由各 DocumentsProvider **自行实现**，真机所选目录的 provider 对 "wa" 不抛异常却按截断打开 → 只写入新段；旧降级链还把"读旧失败"吞成空串再 "w" 全量重写，同样只剩新段（全程零失败痕迹，违 E28）。
> ② append=false → 变追加（F5）：LLM 把 `append` 发成**字符串** `"false"` 时 `as? Boolean` 落空 → 静默取默认 `true`（追加）——参数类型不健壮，非标志位判反。
> 修复：① 删 "wa" 直写，追加恒定走"读旧（带上限）→内存合并→'w' 全量重写"（SAF 唯一语义可靠路径）；读旧失败**拒写**留痕（宁可不写，绝不与空串合并覆盖原内容）。
> ② FilesModule 新增 `boolArg`/`intArg` 三态解析（Boolean/String/Number 全收），应用于 save_to_file.append / create_document.append / delete_folder.force / 两处 offset；append 参数描述改双向明示。
>
> **✅ 第二轮已修复（2026-09-18 深夜，构建通过；真机验收待装新 APK）——新缺陷：覆盖不截断**
> 真机实测第 4 项：`append=false` 覆盖 23 字文件写入 `X` → 文件仍 23 字，`X` 后残留旧文尾（`MMA-第三次覆盖\nBETA-第二次追加`）——**"w" 的截断语义同样由 provider 自行实现**，该 provider 按 "rw" 原位覆写打开（不 truncate）。与 "wa" 缺陷同源：SAF 模式字符串全靠 provider 自觉。危害：文件长度不缩、回执正常、零痕迹——短内容修文档实留半篇旧文，知识库检索命中早该消失的旧段。
> 修复：新增 `writeFullBytes(target, bytes)` 统一收口三个**既有文档**覆写点（writeText 覆盖分支 / appendSmart 合并重写 / writeBinary——短 docx 覆盖长文件会留损坏 ZIP 尾）：写前 `(stream as? FileOutputStream)?.channel?.truncate(0)` 确定性清空——文件型 provider 的流必是 `ParcelFileDescriptor.AutoCloseOutputStream`（FileOutputStream 子类）；管道型 provider（云端等）整段替换无残留语义，cast 不中自然跳过。其余四处 `"w"` 全为 `createDocument` 新建（长度 0 无残留）不动；DataExporter 为 `"wt"` 新建导出不动。

---

## 一、现象（用户实测）

| 调用 | 回执 | 实际 |
|---|---|---|
| `append=false`（想覆盖） | "已保存" | ❌ 变成追加（越写越多）——F5 |
| `append=true`（想追加） | "已追加到工作目录" | ❌ **变成覆盖**——原内容静默丢失，文件只剩新追加段 |

第二例证据：`save_to_file("knowledge/工程/自驱动回合_功能落地.md", …, append 默认 true)` 后，读回全文 1651 字、开头即"## 终态修订（2026-09-18 22:15）"——早先记的《自驱回合功能落地》整篇不见；`kb_status` 该文件只剩 7 段、标题即新段标题。

## 二、影响

- **比 F5 更重**：F5（想覆盖却追加）文件变长、可发现；本次（想追加却覆盖）**原内容静默丢失、不可恢复**。
- 这是**知识库的写入路径**——每一条沉淀笔记都可能吃掉上一篇。
- "已追加"回执由入参 `append` 拼出，与磁盘行为脱钩——LLM 看到成功回执不会复查（静默失败最危险）。

## 三、根因分析

### 方向①：append=true → 覆盖（数据丢失）

`appendSmart` 原实现（WorkDirRepository）：

```kotlin
val direct = context.contentResolver.openOutputStream(existing, "wa")  // ← 陷阱
if (direct != null) { direct.use { it.write(bytes) }; return null }    // "成功"，无异常
```

- `openOutputStream` 的 mode 字符串最终交各 DocumentsProvider 解释——**provider 不支持 "wa" 时未必抛异常**（有的按 "w" 截断语义打开），追加流变截断流，写完即只剩新段；
- 降级链的"读旧"两处吞错（`?: ""` 与 `catch → ""`）：旧内容读不出来时与空串合并 → "w" 全量重写 → 原文尽失，且无任何 trace（E28 违例）。

### 方向②：append=false → 追加（F5）

FC 参数经 `parseToolArguments`（`JSONObject.get` 原样保留类型）到达模块；`"append": "false"`（字符串）经 `(args["append"] as? Boolean) ?: true` 落空 → **静默取默认 true**。同型风险点：create_document.append（默认 false）、delete_folder.force、read_work_file/read_office_file 的 offset（字符串数字 → 恒回 0 重读首页）。

## 四、修复内容（commit 同步）

| 文件 | 改动 |
|---|---|
| `WorkDirRepository.kt` | `appendSmart` 重写：删 "wa" 直写；读旧带 2MB 上限（超限引导分卷）；读旧失败 `appendReadFailed` **拒写** + E28 trace；合并后 "w" 重写并 trace oldBytes/totalBytes；`writeText` 追加分支注释同步 |
| `FilesModule.kt` | 新增 `boolArg`/`intArg` 三态解析；save_to_file.append / create_document.append / delete_folder.force / 两处 offset 应用；append 参数描述双向明示（true=追加/不存在则新建；false=覆盖重写） |

审计确认（无同类问题）：`writeText` 覆盖分支用 "w"（截断语义各 provider 一致可靠）；新建分支失败即删空壳；`linux_write_file` 纯覆盖写、描述如实；KnowledgeModule 无自有写路径（仅引导文案）。

## 五、验收用例（真机装新 APK）

| 步骤 | 预期 |
|---|---|
| `save_to_file(file_name="t.md", content="A")` | 已追加（新建），读回="A" |
| `save_to_file(file_name="t.md", content="B")`（默认 append） | 已追加，读回="AB" |
| `save_to_file(file_name="t.md", content="C", append=true)` | 已追加，读回="ABC" |
| `save_to_file(file_name="t.md", content="X", append=false)` | 已保存，读回=**"X" 且长度=1**（二轮核心：无旧文残骸） |
| `create_document(file_name="s.docx", …)` 后 `create_document` 更短内容 `append=false` | 读回仅新内容（ZIP 尾无残骸，文件可正常打开） |
| 运行日志（traceId `work_dir`） | 追加呈 `append=true(merge rewrite) oldBytes=N`；覆盖呈 `append=false(overwrite,trunc) bytes=N` |

**核心断言：append=true 时读回 = 原文 + 新段；append=false 时读回 = 仅新段且字节数 = 新内容字节数。**
