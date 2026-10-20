# Zero

Android原生个人Agent，支持多模型对话、工具调用、持久记忆、知识库、自动化、系统能力和可观测用量。

这是脱敏后的公开源码快照，采用独立Git历史。实际聊天、记忆、配置、凭据、登录二维码、设备日志和私有开发历史不在仓库中。

<!-- ZERO_FACTS_BEGIN -->
### 当前构建事实（自动生成）

- 应用版本：`1.0.0`（versionCode `2`）
- Room 数据库版本：`38`
- Room 最新导出 schema：`38`

<!-- ZERO_FACTS_END -->

## 能力与当前边界

- 多模型协议适配及工具调用；实际可用性取决于连接配置、服务商和模型。
- 稳定上下文前缀、按轮追加记忆更正，换代时整理；缓存命中率由服务商实际报告，不能保证固定98%。
- 来源、派生记忆、当前视图分离；六类记忆工具、人工历史复核、版本归档和备份恢复。
- 向量与词法混合召回；来源有效不等于语义一定支持答案，个人盲测、全库规模及长期质量仍需继续验收。
- 文件与知识库、技能、音乐、日历、提醒、MCP及可选自托管zero-cloud。
- 成本与用量本地观测、角色与自主行为设置、可选本地语音和向量模型。

具体实现和未完成项见[当前设计](PROJECT_DESIGN_CURRENT.md)、[架构](ARCHITECTURE_FINAL.md)、[路线图](ROADMAP.md)。历史文档中的本机证据路径只记录验收出处，原始运行资料不会随仓库公开。

## 构建

需要JDK 21、Android SDK 35和Python 3.11或更新版本。Android Studio可用于配置SDK；命令行环境设置ANDROID_HOME或ANDROID_SDK_ROOT。

先获取官方运行时依赖（不下载模型、不调用模型API）：

```sh
python scripts/bootstrap_runtime.py
```

Windows：

```powershell
.\gradlew.bat qualityCheck assembleDebug --no-daemon --max-workers=1
```

Linux/macOS：

```sh
./gradlew qualityCheck assembleDebug --no-daemon --max-workers=1
```

运行时依赖按固定版本下载，校验归档及提取文件的SHA-256。已有文件不符时脚本拒绝覆盖。源码仓库不直接分发模型运行时JNI/JAR或模型权重；官方Gradle wrapper JAR随构建入口保留。

语音模型为可选独立资产，未包含在源码中；未部署模型时不能声称本地语音功能可用。请阅读[模型与运行时说明](docs/PUBLIC_BUILD.md)，按模型各自许可证安装资产。第三方说明见[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 自托管与配置

zero-cloud为可选服务，按其Docker配置部署。在zero-cloud目录运行python3 scripts/prepare_env.py，独立生成本地Gateway和SearXNG凭据；已有非空凭据保留，部署日志不输出密钥。公开源码中的服务器地址、LAN地址和默认证书指纹均为示例；请在设置中配置自己的服务、模型与信任信息。

项目不包含维护者的模型API密钥、账号授权、云服务器或个人数据。不要将自己的.env、数据库、备份或真实测试材料加入Git。

## 开发和GitHub管理

功能分支使用codex/前缀，提交前执行：

```sh
python scripts/check_public_tree.py
```

通过Pull Request和GitHub Actions质量检查合并，详见[CONTRIBUTING.md](CONTRIBUTING.md)。安全问题见[SECURITY.md](SECURITY.md)。初始开源仅发布源码，不自动发布APK，不安装设备，也不运行真实模型测试。

## 许可证

原创代码采用[Apache License 2.0](LICENSE)。第三方运行时、库和模型保留各自授权，不因本仓库许可证而改变。
