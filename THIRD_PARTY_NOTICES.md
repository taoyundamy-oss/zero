# 第三方制品与许可

本项目原创代码采用Apache-2.0。下面的制品保留其上游许可；本仓库通过下载脚本获取依赖，不将它们重新许可为原创代码。

| 组件 | 版本/来源 | 许可与处理 |
|---|---|---|
| sherpa-onnx Java与Android native | 官方v1.12.28 release | Apache-2.0；LICENSE见third-party-licenses/sherpa-onnx-LICENSE |
| ONNX Runtime Java/JNI | Maven Central onnxruntime-android 1.17.1 | MIT；LICENSE见third-party-licenses/onnxruntime-LICENSE |
| sherpa配套libonnxruntime.so | 官方v1.12.28 Android归档中的原始文件 | 归档与逐文件SHA固定；不将其版本误标成Java/JNI的1.17.1 |
| Gradle Wrapper | Gradle 8.10.2官方脚本与JAR | Apache-2.0；上游https://github.com/gradle/gradle/tree/v8.10.2 |
| BGE中文分词测试词表 | BAAI/bge-small-zh-v1.5官方vocab.txt，SHA-256 45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c | BGE模型卡标注MIT，保留FlagEmbedding许可；词表同时与google-bert/bert-base-chinese逐字节相同，另保留BERT Apache-2.0许可，见third-party-licenses/；仅测试词表，不含权重 |
| 其他Gradle/Python依赖 | 对应构建/requirements文件 | 以各发布物的POM、LICENSE和NOTICE为准 |
| ASR/VAD/KWS/TTS/向量模型 | 可选，未随源码分发 | 软件运行时许可不代表模型许可，部署前独立确认 |

运行时下载地址、版本和SHA-256见gradle/verification/runtime-downloads.json。公开准备时已经逐文件确认现有Sherpa JAR、两种ABI共8个native文件，以及ONNX Java/JNI与上述官方资产一致。历史溯源文档中的待确认状态是当时记录，以本次固定下载清单为当前证据。

BGE词表来源：https://huggingface.co/BAAI/bge-small-zh-v1.5/blob/main/vocab.txt ，许可声明：https://huggingface.co/BAAI/bge-small-zh-v1.5/blob/main/README.md 。

BERT同源词表：https://huggingface.co/google-bert/bert-base-chinese/blob/main/vocab.txt ，上游许可：https://github.com/google-research/bert/blob/master/LICENSE 。
