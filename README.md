# GTEternalTime Core — 1.21.1 / NeoForge 移植工程

GregTech Eternal Time 在 **Minecraft 1.21.1 + NeoForge 21.1.252** 上的移植工程。
原 1.20.1 / Forge 版在另一个仓库，两者独立演进。

## 环境

- **Java 21**。注意：`gradle.properties` 里已把 Gradle 自己的 JVM 钉在 `C:/Program Files/Zulu/zulu-21`——
  本机 `JAVA_HOME` 指向 Java 25，而 Gradle 8.8 跑在 Java 25 上会直接崩
  （`Unsupported class file major version 69`）。换机器时这一行要按本机路径改。
- Gradle 8.8（随 wrapper 提供，无需另装）。

## 依赖 jar（不入库，需自行准备）

- `libs/gtceu-1.21.1-8.0.0-SNAPSHOT.jar`：GTCEu（GTM）8.0.0 的 1.21.1 构建，自行获取后放进 `libs/`。
- `libs/jarjar/**`：gtceu 内嵌的三个库（`com.tterrag.registrate` / `brachy.modularui` / `dev.toma.configuration`）。
  跑一次 **`gradlew extractGtceuJarJar`** 会从上面那个 jar 的 `META-INF/jarjar/` 里自动抽出来。
  换 gtceu 版本后需要重跑（`--rerun-tasks`）。

## 常用命令

| 目的 | 命令 |
|---|---|
| 编译 | `gradlew classes` |
| 数据生成（机器模型 + 中英语言文件） | `gradlew runData` |
| 进游戏 | `gradlew runClient` |

数据生成必须带 `--existing-mod gtceu`（`build.gradle` 里已配好），否则 addon 机器引用 GTM 的父模型时会报
`Model at gtceu:block/machine/... does not exist`——NeoForge 1.21 的 `ExistingFileHelper` 只认
`--existing` 目录与 `--existing-mod` 点名的模组资源。

## 已知问题

见 **`docs/GTM-1.21.1-问题记录.md`**：GTM 8.0.0 / MUI 的上游问题、复现条件、我们这边的修法或规避，
以及「不是 bug 但会让老代码编不过」的 API 变更速查表。
