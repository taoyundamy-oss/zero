import io.gitlab.arturbosch.detekt.Detekt
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.test") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    id("com.google.dagger.hilt.android") version "2.53.1" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        apply(plugin = "org.jlleitschuh.gradle.ktlint")

        tasks.withType<Detekt>().configureEach {
            if (project.path == ":services") {
                baseline = rootProject.file("services/detekt-baseline.xml")
            }
        }

        if (project.path == ":services") {
            extensions.configure<KtlintExtension> {
                filter {
                    exclude("**/*.kt")
                    include("**/TermuxExecutionPolicy.kt")
                }
            }
        }
    }
}

/** P2-2a：统一质量门禁中的 Room schema 结构检查。 */
tasks.register("verifyRoomSchemas") {
    doLast {
        val schemaRoot = layout.projectDirectory.dir("core/data/schemas").asFile
        val databaseDir = schemaRoot.resolve("com.zero.v2.core.data.db.ZeroDatabase")
        check(databaseDir.isDirectory) { "Room schema 目录不存在：${databaseDir.path}" }

        val requiredVersions = listOf(5, 18, 19, 20, 21, 22)
        requiredVersions.forEach { version ->
            val schema = databaseDir.resolve("$version.json")
            check(schema.isFile && schema.length() > 0) {
                "缺少 Room schema v$version：${schema.path}"
            }
            val content = schema.readText()
            check(content.contains("\"database\"")) {
                "Room schema v$version 不是有效导出结构：${schema.path}"
            }
        }
    }
}

/** P2-3：校验不可由依赖仓库自动验证的 Sherpa 本地二进制，防止静默漂移。 */
tasks.register("verifySherpaArtifacts") {
    doLast {
        val checksumFile = layout.projectDirectory.file("gradle/verification/sherpa-sha256.txt").asFile
        check(checksumFile.isFile) { "缺少 Sherpa 校验清单：${checksumFile.path}" }
        checksumFile.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                check(parts.size == 2) { "非法 Sherpa 校验行：$line" }
                val artifact = layout.projectDirectory.file(parts[1].replace('\\', '/')).asFile
                check(artifact.isFile) { "缺少 Sherpa 制品：${artifact.path}" }
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(artifact.readBytes())
                    .joinToString("") { "%02x".format(it) }
                check(digest.equals(parts[0], ignoreCase = true)) {
                    "Sherpa 制品校验失败：${artifact.path}，expected=${parts[0]}，actual=$digest"
                }
            }
    }
}

/** P2-4a：从当前源码读取版本事实，只校验，不改写 README。 */
tasks.register("verifyProjectFacts") {
    doLast {
        val appBuild = layout.projectDirectory.file("app/build.gradle.kts").asFile.readText()
        val versionCode = Regex("versionCode\\s*=\\s*(\\d+)").find(appBuild)?.groupValues?.get(1)?.toIntOrNull()
        val versionName = Regex("versionName\\s*=\\s*\\\"([^\\\"]+)\\\"").find(appBuild)?.groupValues?.get(1)
        check(versionCode != null && versionName != null) { "无法从 app/build.gradle.kts 读取 versionCode/versionName" }
        check(versionCode > 0) { "versionCode 必须为正数：$versionCode" }
        check(versionName.isNotBlank()) { "versionName 不能为空" }

        val databaseSource = layout.projectDirectory
            .file("core/data/src/main/java/com/zero/v2/core/data/db/ZeroDatabase.kt")
            .asFile.readText()
        val databaseVersion = Regex("version\\s*=\\s*(\\d+)").find(databaseSource)?.groupValues?.get(1)?.toIntOrNull()
        check(databaseVersion != null) { "无法从 ZeroDatabase.kt 读取 Room version" }

        val schemaDir = layout.projectDirectory.dir("core/data/schemas/com.zero.v2.core.data.db.ZeroDatabase").asFile
        val schemaVersions = schemaDir.listFiles()
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.sorted()
            ?: emptyList()
        check(schemaVersions.isNotEmpty()) { "Room schema 目录为空：${schemaDir.path}" }
        check(databaseVersion == schemaVersions.last()) {
            "Room version 与最新 schema 不一致：database=$databaseVersion schema=${schemaVersions.last()}"
        }
        logger.lifecycle("Project facts verified: versionName=$versionName versionCode=$versionCode roomVersion=$databaseVersion schemaVersion=${schemaVersions.last()}")
    }
}

/** P2-4b：只替换 README 受控区块，保留其他人工内容。 */
tasks.register("generateProjectFacts") {
    doLast {
        val appBuild = layout.projectDirectory.file("app/build.gradle.kts").asFile.readText()
        val versionCode = Regex("versionCode\\s*=\\s*(\\d+)").find(appBuild)?.groupValues?.get(1)
        val versionName = Regex("versionName\\s*=\\s*\\\"([^\\\"]+)\\\"").find(appBuild)?.groupValues?.get(1)
        val databaseSource = layout.projectDirectory
            .file("core/data/src/main/java/com/zero/v2/core/data/db/ZeroDatabase.kt")
            .asFile.readText()
        val databaseVersion = Regex("version\\s*=\\s*(\\d+)").find(databaseSource)?.groupValues?.get(1)
        val schemaDir = layout.projectDirectory.dir("core/data/schemas/com.zero.v2.core.data.db.ZeroDatabase").asFile
        val schemaVersion = schemaDir.listFiles()
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.maxOrNull()
        check(versionCode != null && versionName != null && databaseVersion != null && schemaVersion != null) {
            "无法生成 README 版本事实"
        }
        val readme = layout.projectDirectory.file("README.md").asFile
        val source = readme.readText()
        val block = """<!-- ZERO_FACTS_BEGIN -->
### 当前构建事实（自动生成）

- 应用版本：`$versionName`（versionCode `$versionCode`）
- Room 数据库版本：`$databaseVersion`
- Room 最新导出 schema：`$schemaVersion`

<!-- ZERO_FACTS_END -->"""
        val pattern = Regex("(?s)<!-- ZERO_FACTS_BEGIN -->.*?<!-- ZERO_FACTS_END -->")
        check(pattern.containsMatchIn(source)) { "README 缺少 ZERO_FACTS 生成区块" }
        readme.writeText(pattern.replace(source, block))
    }
}

/** P2-4b：验证 README 受控区块与当前事实一致，不自动修改文件。 */
tasks.register("verifyGeneratedProjectFacts") {
    dependsOn("verifyProjectFacts")
    doLast {
        val readme = layout.projectDirectory.file("README.md").asFile.readText()
        val appBuild = layout.projectDirectory.file("app/build.gradle.kts").asFile.readText()
        val versionCode = Regex("versionCode\\s*=\\s*(\\d+)").find(appBuild)?.groupValues?.get(1)
        val versionName = Regex("versionName\\s*=\\s*\\\"([^\\\"]+)\\\"").find(appBuild)?.groupValues?.get(1)
        val databaseSource = layout.projectDirectory.file("core/data/src/main/java/com/zero/v2/core/data/db/ZeroDatabase.kt").asFile.readText()
        val databaseVersion = Regex("version\\s*=\\s*(\\d+)").find(databaseSource)?.groupValues?.get(1)
        val schemaVersion = layout.projectDirectory.dir("core/data/schemas/com.zero.v2.core.data.db.ZeroDatabase").asFile
            .listFiles()?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }?.maxOrNull()
        check(versionCode != null && versionName != null && databaseVersion != null && schemaVersion != null) {
            "无法读取当前版本事实"
        }
        check(readme.contains("<!-- ZERO_FACTS_BEGIN -->") && readme.contains("<!-- ZERO_FACTS_END -->")) {
            "README 缺少 ZERO_FACTS 生成区块"
        }
        check(readme.contains("应用版本：`$versionName`（versionCode `$versionCode`）")) {
            "README 应用版本事实与当前构建不一致，请先运行 generateProjectFacts"
        }
        check(readme.contains("Room 数据库版本：`$databaseVersion`") && readme.contains("Room 最新导出 schema：`$schemaVersion`")) {
            "README Room 事实与当前 schema 不一致，请先运行 generateProjectFacts"
        }
    }
}

/** P2-4c：若当前 README 不是生成结果则失败，防止手工漂移。 */
tasks.register("verifyProjectFactsIdempotent") {
    dependsOn("verifyGeneratedProjectFacts")
    doLast {
        val readme = layout.projectDirectory.file("README.md").asFile.readText()
        val appBuild = layout.projectDirectory.file("app/build.gradle.kts").asFile.readText()
        val versionCode = Regex("versionCode\\s*=\\s*(\\d+)").find(appBuild)?.groupValues?.get(1)
        val versionName = Regex("versionName\\s*=\\s*\\\"([^\\\"]+)\\\"").find(appBuild)?.groupValues?.get(1)
        val databaseSource = layout.projectDirectory
            .file("core/data/src/main/java/com/zero/v2/core/data/db/ZeroDatabase.kt")
            .asFile.readText()
        val databaseVersion = Regex("version\\s*=\\s*(\\d+)").find(databaseSource)?.groupValues?.get(1)
        val schemaVersion = layout.projectDirectory.dir("core/data/schemas/com.zero.v2.core.data.db.ZeroDatabase").asFile
            .listFiles()?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }?.maxOrNull()
        check(versionCode != null && versionName != null && databaseVersion != null && schemaVersion != null) {
            "无法读取当前版本事实"
        }
        val expected = """<!-- ZERO_FACTS_BEGIN -->
### 当前构建事实（自动生成）

- 应用版本：`$versionName`（versionCode `$versionCode`）
- Room 数据库版本：`$databaseVersion`
- Room 最新导出 schema：`$schemaVersion`

<!-- ZERO_FACTS_END -->"""
        val block = Regex("(?s)<!-- ZERO_FACTS_BEGIN -->.*?<!-- ZERO_FACTS_END -->").find(readme)?.value
        check(block == expected) { "README ZERO_FACTS 区块不是当前生成结果，请先运行 generateProjectFacts" }
        logger.lifecycle("README project facts are idempotent")
    }
}

/**
 * P2-2a：本地与 CI 共用的最小质量入口。
 * Detekt/Ktlint 在 P2-2b 接入后追加到此任务，不在这里静默跳过。
 */
tasks.register("verifyModuleBoundaries") {
    val consumers = listOf("services", "framework/chat", "framework/memory")
    inputs.files(consumers.map { fileTree("$it/src") { include("**/*.kt") } })
    inputs.files(consumers.map { file("$it/build.gradle.kts") })
    doLast {
        val violations = consumers.flatMap { consumer ->
            val sourceFiles = fileTree("$consumer/src") { include("**/*.kt") }.files
            sourceFiles.filter { it.readText().contains("com.zero.v2.core.modulemanager.") }
                .map { it.relativeTo(rootDir).path } +
                listOf(file("$consumer/build.gradle.kts"))
                    .filter { it.readText().contains(":core:module-manager") }
                    .map { it.relativeTo(rootDir).path }
        }
        check(violations.isEmpty()) { "Module implementation dependency: ${violations.joinToString()}" }
        logger.lifecycle("Services and framework depend on module-api contracts only")
    }
}

tasks.register("qualityCheck") {
    dependsOn(
        "verifyRoomSchemas",
        "verifyModuleBoundaries",
        "verifySherpaArtifacts",
        "verifyProjectFacts",
        "verifyGeneratedProjectFacts",
        "verifyProjectFactsIdempotent",
        ":core:bus:testDebugUnitTest",
        ":core:common:testDebugUnitTest",
        ":core:module-api:testDebugUnitTest",
        ":framework:chat:testDebugUnitTest",
        ":framework:memory:testDebugUnitTest",
        ":core:data:testDebugUnitTest",
        ":core:module-manager:testDebugUnitTest",
        ":services:testDebugUnitTest",
        ":services:detekt",
        ":services:ktlintCheck",
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":services:lintDebug",
        ":app:assembleDebug",
        ":benchmark:compileDebugKotlin"
    )
}
