# Sherpa 本地制品依赖溯源

> 建立日期：2026-09-29  
> 适用范围：Zero V2 本地 Android/Termux 维护线；不包含已舍弃的 zero-cloud。

## 当前可验证事实

- Java API 制品位于 `services/libs/sherpa-onnx.jar`，运行时原生库位于 `services/src/main/jniLibs/{arm64-v8a,x86_64}`。
- `app` 是 JAR 与 native `.so` 的最终 APK 打包边界；`services` 只以 `compileOnly` 使用 Java API，避免生成超大 lint AAR。
- 全部本地二进制的 SHA-256 固化在 `gradle/verification/sherpa-sha256.txt`，根任务 `verifySherpaArtifacts` 校验该清单，并已纳入 `qualityCheck`。
- JAR 内包含 `com.k2fsa.sherpa.onnx` Java API，但 Manifest 不含可用于确认版本或来源的字段。
- Git 历史显示这些二进制在 2026-08-25 的 `2d9ed0a` 提交中首次整体加入；历史设计文档将 API 标注为 `v1.12.28`。这可作为候选版本线索，不能证明当前 JAR/native 文件就是该 release 的原始资产。

## 来源与许可证状态

- 上游项目：`k2-fsa/sherpa-onnx`；官方 Android 构建流程会生成 AAR，并将 Java API 与各 ABI 原生库组合发布。
- 上游 `v1.12.28` release 确实存在，且 release notes 提到新增 Kotlin/Java API；官方文档也指向 release 页面获取 Android 预构建库。但当前仓库只保存拆分后的 JAR/`.so`，没有对应下载 URL 或 release asset 名称，无法做 hash 对应。
- 上游仓库声明 Apache-2.0 软件许可证；但当前仓库中的这组本地二进制没有随附原始下载 URL、release tag、构建参数、LICENSE 或 NOTICE。
- 因而当前只能确认“API/文件布局与 sherpa-onnx 一致”，不能从现有二进制可靠反推确切版本，也不能把历史文档中的 `1.12.28` 当作已验证版本。
- 在原始下载记录补齐前，状态标记为：**完整性可验证，来源版本待确认，分发许可证材料不完整**。

## 维护规则

1. 任意替换 JAR 或 `.so` 时，必须同时记录上游 release/tag、直接下载 URL、获取日期、目标 ABI、构建方式，并更新校验清单。
2. 必须把对应版本的 LICENSE/NOTICE 随制品归档；模型权重许可证与 sherpa-onnx 软件许可证分开记录，不能互相推定。
3. `verifySherpaArtifacts` 失败时不得更新校验值来绕过门禁；先确认替换来源与许可证，再评审并更新。
4. 后续若改用可解析的 Maven/AAR 坐标，应移除重复本地制品，并以 Gradle dependency verification 接管完整性校验。

## 待补证据

- 当前 JAR 和两组 ABI `.so` 的原始下载 URL 或构建提交。
- 将当前 SHA-256 与候选 `v1.12.28` release asset 逐项对应的证据。
- 确切 sherpa-onnx 版本及配套 ONNX Runtime 版本。
- 对应版本的 LICENSE/NOTICE 归档，以及 APK 分发所需的第三方声明。
