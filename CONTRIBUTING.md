# 参与开发

使用JDK 21、Android SDK 35和Python 3.11或更新版本。先运行
python scripts/bootstrap_runtime.py，再执行Gradle的qualityCheck assembleDebug。

- 实现事实见PROJECT_DESIGN_CURRENT.md；增量计划与验收边界见对应收敛设计。
- 改动应完成相关测试、编译，并更新同一设计文档中的进度。
- 不把真实聊天、数据库、观测原文、密钥、设备序列号或私人服务器配置加入Git。
- 提交前执行python scripts/check_public_tree.py，并人工检查git diff --cached。
- 功能分支使用codex/前缀，通过Pull Request审查和CI后合并。
- 实机安装或真实模型调用需要操作者明确确认范围，不能自动使用真实账号额度。
- 第三方制品遵守各自许可证，不因本项目采用Apache-2.0而改变授权。

新克隆后执行 git config core.hooksPath .githooks 可启用本地提交/推送检查。检查读取暂存区实际内容；仍需人工审查和CI。

推送前再执行 python scripts/check_public_tree.py --history HEAD，要求完整历史（浅克隆需先补齐）。CI拉取完整历史并检查每个可达提交，旧提交删除过的内容仍会检查。不要通过仅修改工作区或删除当前文件绕过历史脱敏。
