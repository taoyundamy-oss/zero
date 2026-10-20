# 公开源码构建与可选资产

本公开仓库不包含维护者的设备数据，也不分发语音/向量模型权重。运行时依赖使用scripts/bootstrap_runtime.py下载，归档和目标文件均验证SHA-256。

## 默认构建

安装JDK21、Android SDK35、Python3.11+，运行bootstrap脚本后执行Gradle质量和编译任务。源码可以在没有模型资产时编译；模型相关能力需要部署资产后单独验收，不能将编译成功等同于语音识别/合成通过。

## 可选模型路径

- 热词：app/src/main/assets/models/kws/，需要与KWS模型匹配的encoder/decoder/joiner.onnx、tokens.txt及自行配置的keywords.txt。
- ASR：services/src/main/assets/models/asr/，需要与语音模块匹配的encoder/decoder/joiner.onnx及tokens.txt。
- VAD：services/src/main/assets/models/vad/silero_vad.onnx。
- 其他语音/向量模型按模块配置部署，不自动上传用户资料或切换到付费API。

上游模型目录：https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html 。每个模型分别核对版本、授权、词表和输入契约，不以sherpa-onnx代码许可证代替模型许可证。本仓库不提供来源尚未核验的模型镜像。

## 示例连接

203.0.113.10和192.0.2.50为文档示例地址，不代表可使用的服务器；全零默认证书指纹只是占位，必须替换为自己服务的真实证书信任配置。本地诊断脚本使用PATH中的adb；设备序列号由操作者指定。

## 验收边界

公开版本的独立构建、测试与脱敏检查在仓库维护中记录。原个人设备验收不代表所有设备、账号或全新公开配置均通过；请用合成资料先验证自己的连接，并在安装前保护现有数据。

## 自托管凭据准备

在zero-cloud目录运行 python3 scripts/prepare_env.py，再使用deploy.sh或Docker Compose。脚本仅在本机创建私有.env，独立生成Gateway Token、SearXNG密钥与停用NapCat profile的WebUI密钥（不启用该profile），不输出密钥，不覆盖已有非空凭据；重复键先报错。SearXNG通过官方SEARXNG_SECRET环境变量注入，Compose在未设置时拒绝启动。此仓库修订不自动部署或轮换任何现有服务；没有Docker容器实测时不将配置检查等同服务验收。

环境变量依据：https://docs.searxng.org/admin/settings/settings_server.html 。prepare_env.py面向Linux部署，600权限不等于Windows ACL；Windows操作者需自行保护本地凭据文件权限。
