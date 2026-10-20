# Zero v2 - Project Development Guidelines for Codex

## Architecture Principles
1. **Source of Truth**: Always refer to `ARCHITECTURE_FINAL.md` for class structures, function definitions, and safety guards before writing code.
2. **Decoupling**: Strictly adhere to Hilt DI and Bus event decoupling. Avoid direct circular references between modules.
3. **Safety First**:
   - Wrap module calls in `ModuleLifecycleManager.safeCall()`.
   - Protect Room DB writes with `Mutex` and `@Transaction`.
   - Enforce 3s timeouts in `MessagePipeline` and 100ms contracts on `IMessageFilter`.

## Autonomous Development Loop Rules
1. **Read Roadmap First**: Check `ROADMAP.md` at the start to locate the first unchecked task (`[ ]`).
2. **Strict Incremental Steps**: Complete ONLY ONE task per step. Read requirements from `ARCHITECTURE_FINAL.md` -> Write/modify code -> Verify compile.
3. **Mandatory Build Verification**: After editing files, run `./gradlew assembleDebug` (or specific subproject build tasks). Do NOT mark `[x]` in `ROADMAP.md` if the compilation fails.
4. **Self-Correction Protocol**: On build errors, read the compiler output, diagnose the issue, and apply a fix independently.
5. **Continuous Progression**: Once a task passes and `ROADMAP.md` is updated, immediately start the next task without waiting for human confirmation, until all tasks are `[x]`.
