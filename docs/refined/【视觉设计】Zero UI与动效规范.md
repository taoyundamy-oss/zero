# Zero UI v2：对话、系统感知与工作区

> 2026-09-30 设计交付。当前素材未接入APK。总目标见《【迭代总纲】稳定基建·系统感知·受控自迭代·UI重构.md》。预览与样例数据不代表真实系统运行状态。

## 1. 视觉方向与素材入口

采用安静、清晰的对话界面：浅色暖白，深色石墨；正文直接排在画布上，用户消息使用弱对比气泡；输入器为大圆角，主要操作清楚且容易触及。绿色只表示可用/成功，错误和等待同时提供文字，不依赖颜色。参考ChatGPT的对话信息层次与输入体验；图标参考Grok的单色几何识别方向，Zero标识采用开口零环与独立方点。不会把其他产品商标放进Zero界面。

- `assets/zero-ui-v2/visual-direction.png`：imagegen生成的浅/深色折叠屏设计参考。仅作风格参考，图中英文文案/阴影/设备外框不作为生产规范。
- `assets/zero-ui-v2/preview.html`：无外部依赖的中文交互预览，支持浅/深色、窄屏/展开、导航、状态面板、模块版本和消息输入。所有状态标为样例，不请求真实API。
- `assets/zero-ui-v2/zero-icon.svg`：图标展示稿；`zero-mark.svg`：透明矢量标识；`ic_zero_foreground.xml`：Android VectorDrawable前景候选。
- `assets/zero-ui-v2/zero-activity.svg`：处理状态循环动画，含系统减少动态适配。正式应用由Compose Canvas/animation实现同一几何，避免引入WebView/SVG动画运行时。
- `assets/zero-ui-v2/tokens.json`：颜色、间距、字体、圆角、动效参数；`GENERATION.md`：生成方式、提示词与资源用途。

## 2. 信息架构与旧入口映射

| 新入口 | 内容 | 现有入口迁移 |
|---|---|---|
| 对话（默认） | 消息、附件、引用、语音、历史、当前角色 | ChatScreen、LiveSession、历史会话；全保留 |
| 资料 | 知识文档、索引进度、长期记忆/图谱、来源与纠错 | MemoryManage/MemoryGraph、Knowledge相关页、工作目录管理 |
| 工作区 | 自动任务、Termux动态模块、部署版本、活动与运行记录 | Automation、DynamicModules、ModuleCenter、RunLogs；详细技术参数二级页 |
| 设置 | 角色、模型、权限、外观、数据导入导出与高级配置 | 原Settings中全部链接建立映射，禁止漏入口 |
| 能力目录（二级） | 音乐、文件、日历等现有能力 | 保留可直接操作的原UI，通过工作区能力目录进入 |

手机compact：顶栏抽屉收纳历史和四个主入口，对话页不放持续占高的全宽底部导航；当前页有明确标题，新增对话放顶栏。medium/expanded：根据可用宽度使用rail/侧栏与list-detail/supporting pane，不按“某型号手机”硬编码。Z Fold3展开也不保证一定进入expanded，依WindowSizeClass及实际窗口选择；折叠/旋转/多窗口保留草稿、滚动、选择项、任务详情。折痕/铰链不放关键按钮。

主要路径：提问→答案引用→来源页；系统“需要处理”→原因→对应设置/重试；自迭代→验证/灰度卡→工作区版本→一键撤回；高风险提案→变更与数据/权限影响→用户决定。开发过程的hash、端口、pip等留在技术详情，不占普通对话卡。

## 3. 设计 token 与组件

颜色以tokens.json为基准；保留现有Theme.kt动态色能力，设置提供“Zero中性色/系统动态色/跟随明暗”。动态色也须保持语义对比度。正文16sp、行高25sp、可随系统字体缩放；标题22/30sp；提示12sp，不承载不可替代的关键操作。间距4/8/12/16/24/32/48dp；触控区域至少48×48dp；图标24dp，触控区独立。文本对比目标常规4.5:1，大文本/图形3:1，实际动态色需检测。

统一组件：ZeroAppScaffold、ChatComposer、MessageBody、AttachmentTile、CitationChip、RuntimeStatusChip、TaskProgressCard、ModuleReleaseCard、EvidenceList、EmptyState、InlineError、SettingsRow。不要复制页面私有颜色/状态映射。ViewModel输出不可变UiState，操作回到原Command接口，Composable不直接调用模块/DAO。

聊天：助手无大气泡，代码块支持横向滚动/复制，Markdown不执行嵌入脚本；用户消息右对齐且最大宽度约85%；流式输出不按每token重新做入场动画；用户上滚后不强制回底；新内容到达用“回到最新”按钮。composer使用IME和system bar insets，语音/发送/停止互斥状态清楚，附件失败可单独重试。

每屏具备 loading/empty/content/stale/error/offline 状态。系统状态由SystemStateRepository投影；unknown显示“状态待更新”，stale显示观察时间；不能用本地布尔猜测Termux存活。角色状态页明确“表达风格”，亲密度/会话情绪分别重置；资料页能看来源、更正、删除并展示索引尚未同步。

## 4. Android规范与版本策略

现有targetSdk35、Compose BOM 2024.12.01和Material3需先盘点兼容矩阵；更新到实施时稳定版本，在独立切片验证Kotlin/AGP/JDK/Compose，不能为最新视觉API直接引入alpha依赖。当前设计用稳定Material3组件即可落实，不把整个导航栈迁移作为视觉重构前提。

需落实edge-to-edge、正确insets/IME、predictive back、折叠与可调整窗口、字体200%、TalkBack焦点/语义、减少动态、深浅色以及图标主题化。官方参考：[edge-to-edge](https://developer.android.com/develop/ui/compose/system/setup-e2e)、[自适应布局](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps)、[预测返回](https://developer.android.com/develop/ui/compose/system/predictive-back-setup)、[48dp触控建议](https://developer.android.com/guide/topics/ui/accessibility/views/apps-views)。这些是设计依据，不表示当前所有页面已经通过验收。

自适应图标：108×108dp前景/背景分层，主体置于中央66dp安全区；背景为不透明#111214，前景为浅色零环；Android13+另提供monochrome资源，由系统着色；旧设备需要legacy位图密度资源。图标SVG内圆角只作展示，不能直接当adaptive背景裁切。通知小图标单色透明，不能用整个黑底launcher图。U1测试circle/squircle等mask、小尺寸、主题图标、深浅壁纸。启动画面走系统SplashScreen，不额外播放长品牌片头。

## 5. 动效状态与执行规范

| 事件 | 动效 | 参数 | 完成/取消规则 |
|---|---|---|---|
| 按下/反馈 | tonal反馈或小幅透明变化 | 120ms | 系统触控反馈；不缩放正文 |
| 状态切换 | crossfade | 180ms | 状态更新立即可访问，不等动画结束 |
| sheet展开 | Material标准组件 | 280ms参考 | 使用组件默认/系统back进度；避免叠加第二套位移 |
| pane调整 | 布局尺寸/淡入 | 320ms参考 | 手势连续时可中断；不重置阅读位置 |
| LLM/索引处理 | 低对比环与文字 | 1800ms循环 | 只在可见且活动时播放；后台/暂停停止 |
| 任务成功 | 静态对勾淡入 | 180ms一次 | 不用循环庆祝/闪烁 |
| 失败/需用户处理 | 文本+图标 | 无抖动 | 持续显示直到处理，不假装进度完成 |

Compose优先AnimatedVisibility/animateContentSize/Transition，输入器与列表严格控制重组；不引入大Lottie/video素材。读取系统动画时长设置，减少动态时去位移/缩放/循环并立即呈现最终状态；HTML预览“减少动态”也隐藏循环样例。TalkBack应读出“正在处理/已完成”，不能每帧/每token播报；haptic只由明确用户操作或关键终态触发，遵守系统设置。

## 6. 素材与UI验收

设计稿、矢量/XML、tokens和预览本轮交付；Android接线、adaptive-icon配置、截图测试及实际动画流畅度由U1–U3执行。设计预览用本地静态样例；应用必须绑定真实状态。覆盖compact窄屏、Fold展开、分屏、深浅色、200%字体、IME、长中文/代码/附件、TalkBack、断网/超时/状态过期。UI重构不能更改已验证的业务权限与数据语义。
