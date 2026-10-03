# GTM 源码级补丁（modpatch）分析与 1.21.1 / GTM 8.0.0 重写方案

> 只做分析与设计，未改任何代码。核对方法：把官方 7.5.3 的 `-sources.jar` / `.jar` 下到 `%TEMP%`，
> 与归档、与外部检出、与仓内 vendored jar 做「行尾归一化后的逐字节比对」和「逐 ZIP 条目 sha1 比对」；
> 8.0.0 侧读本地源码。未跑 Gradle、未执行 git。

## 0. 证据来源（本节数字都可复现）

| 对照物 | 位置 / 来源 | 关键数字 |
|---|---|---|
| 补丁归档 | `modpatch/gtceu-7.5.3/logic/**` | **8 个文件**（见 §2.1） |
| GTM 7.5.3 官方源码 | `https://maven.gtceu.com/com/gregtechceu/gtceu/gtceu-1.20.1/7.5.3/gtceu-1.20.1-7.5.3-sources.jar` | 10 813 739 B，sha256 `586cac7f…` |
| GTM 7.5.3 官方二进制 | 同上目录 `.jar` | 18 209 988 B，sha256 `d6560d8b6958…` |
| 仓内补丁产物 | `patchJar/maven/com/gregtechceu/gtceu/gtceu-1.20.1/7.5.3/gtceu-1.20.1-7.5.3.jar` | 18 216 233 B，sha256 `d29d038f5f7f…`（与 `patchJar/README.md` 自检表一致） |
| 外部补丁检出 | `D:/java/GregTech-Modern-7.5.3-1.20.1`（`mod_version = 7.5.3`；**存在 `.git`**，隐藏属性，`Get-ChildItem -Force` 才看得到） | 1604 个 `.java` |
| GTM 8.0.0 源码 | `D:\gtm1211-src\GregTech-Modern-latest-1.21`（`mod_version = 8.0.0`，`toolchain.languageVersion = 21`，用 ModDevGradle） | — |
| 1.21.1 现状 | `D:\java\GTEternalTime -- core --neoforge`（**没有任何 modpatch / patchJar 机制**） | — |

---

## 1. modpatch 机制说明

归档里的东西**不是 diff，是按原包路径完整保存的整文件**（`modpatch/<modid>-<version>/{ui,lang,logic}/com/gregtechceu/...`）；用时把这些文件覆盖到一份**外部 GTM 源码检出**，由检出自己的 `gradlew build publishToMavenLocal` 产出打过补丁的 jar，再由 `scripts/patches.gradle` 的 `buildPatchedGtm` 校验后把 **jar + pom + module 三件套**拷进仓内 `patchJar/maven/com/gregtechceu/gtceu/gtceu-1.20.1/7.5.3/`（`scripts/patches.gradle:47-56, 183-188`），`build.gradle` 的 `collectJarjarDeps`（`Sync`，`build.gradle:83-103`）把它改名收进 `build/jarjar/`，最后由 `jar` 任务塞进 `META-INF/jarjar/` 并在 manifest 写 `ContainedDeps: gtceu-1.20.1-7.5.3.jar`（`build.gradle:105-127`）——内嵌即无条件加载，**整合包里不能再放一份 GTM**（`build.gradle:67-68`）。

**自检判据只有一个**（`scripts/patches.gradle:96-108`）：`patchJar` 那份 jar 里
`com/gregtechceu/gtceu/api/machine/multiblock/MultiblockControllerMachine.class` 含有字节串 `MultiblockConfigHook`；判据是**产物里的字符串，不是时间戳、不是体积**（官方 18 209 988 B 与补丁 18 216 233 B 只差 6 KB）。`gradlew verifyPatchedJarjar`（`196-208`）只校验不构建。

**触发条件表**（`scripts/patches.gradle:132-152`）：

| 情形 | 行为 |
|---|---|
| `patchJar` 里已有产物 | `buildPatchedGtm` **SKIPPED**（快路径，不跑外部构建） |
| 产物缺失 + 检出不存 | warn 后跳过，本次不内嵌 GTM |
| 产物缺失 + 检出在 | 自动执行外部构建 |
| `-PgtetPatchBuild=true` | 忽略快路径，**强制重建** |
| `-PgtetSkipPatchBuild=true` | **强制跳过**外部构建 |

**外部构建的固定参数**（`scripts/patches.gradle:63-68` 与 `119-124`）：

```
<检出>\gradlew.bat build publishToMavenLocal --console=plain --no-daemon
  "-Dorg.gradle.java.installations.paths=<JDK 17 根>"
  "-Dorg.gradle.java.home=<JDK 17 根>"
  "-Dorg.gradle.jvmargs=-Xmx512m"  "-Dorg.gradle.workers.max=1"
```

同时把 `JAVA_HOME` 也指到同一个 JDK 17。三条硬理由：
① 检出 toolchain 写死 17；
② 检出自己的 `gradle.properties` 里有一行**失效的** `org.gradle.java.home=D:/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.19+10`（已核对确实在文件里），必须命令行 `-Dorg.gradle.java.home=` 覆盖，否则启动即 `Java home supplied in org.gradle.java.home is invalid`；
③ NeoForm 反编译峰值约 600 MB，堆给大了会 `os::commit_memory(...) failed ... DOS error 1455`，所以 `--no-daemon` + 单 worker + 小堆是必须的。

**版本要同时改三处**：`scripts/patches.gradle:37 gtmVersion`、`gradle.properties: gtm_version`、`build.gradle` 的 JarJar 段（它只读前者的 ext 属性，数值不用重复改）。

**「8 个还是 9 个」的出入不成立**：`modpatch/gtceu-7.5.3/logic/` 下**实际就是 8 个文件**（递归列目录实测，无 `ui/`、无 `lang/`、无 `NOTES.md`），任务书给的那份清单本身也只有 8 条，README 写的也是 8。真正的出入在下一节——**这 8 个文件里没有任何改动**。

---

## 2. 逐补丁

### 2.0 关键发现：归档的 8 个文件与官方 7.5.3 **逐字节相同**（归档里其实没有补丁）

把 8 个归档文件与官方 `-sources.jar` 解出的同名文件做 unified diff（行尾 CRLF/LF 归一化，`n=4`）：

**8 个文件全部 0 hunks**（行数也一一相同：GTCEuAPI 85、IMaterialRegistryManager 116、Material 1987、GTRegistries 136、GTRegistry 290、GTItems 2670、MaterialRegistryImpl 80、MaterialRecipeHandler 691）。

二进制级二次确认——官方 `.jar` 与仓内补丁 `.jar` 逐 ZIP 条目 sha1 比对：

- 只有 **19 个条目不同**：17 个 class 条目 + `gtceu.refmap.json` + `META-INF/jarjar/metadata.json`；
- 差异条目**没有一个**落在归档那 8 个类上——`Material.class`、`GTItems.class`、`GTCEuAPI.class`、`GTRegistries.class`、`GTRegistry.class`、`MaterialRegistryImpl.class`、`MaterialRecipeHandler.class`、`IMaterialRegistryManager.class` 在两份 jar 里**完全相同**；
- 另一个差异：补丁 jar 内嵌 `mixinextras-forge-0.5.5.jar`，官方是 `0.5.3`；补丁 jar 多一个 `com/gregtechceu/gtceu/another/allinit.class`。

**真正的补丁在外部检出里**：官方 7.5.3 的 1603 个 `.java` 中，**12 个内容不同 + 1 个文件是新增**（见 §2.2）。这些改动里 6 个文件带中文注释（如 `MultiblockControllerMachine.java:102`「配置桥接（通过反射调用 GTETCore 的 MultiblockConfigHook）」、`MultiblockWorldSavedData.java:36`「全局共享方块追踪」、`BlockPattern.java:158`「共享方块检测」、`MultiblockState.java:59/61`「方块状态缓存 / 多方块缓存」，全部为 UTF-8），归属 GTET 无歧义；归档更像是一次「改成直接改源码」的旧尝试的残留（README 说检出里那 8 个文件「本来就是补丁版」，实测它们与官方一致）。

> 所以 `gradlew buildPatchedGtm` 的补丁**只存在于那个外部检出**，仓内归档既不能重建它、也解释不了它——这是当前最大的可复现性风险。

### 2.1 归档 8 文件（逐行：全部「无改动」）

| 补丁文件（归档路径） | 改了什么的证据 | 旧工程里谁在用 | 为什么需要 | GTM 8.0.0 现状 | 1.21.1 建议 |
|---|---|---|---|---|---|
| `api/GTCEuAPI.java` | 与官方 7.5.3 逐字节一致，0 hunks（官方 85 行） | `mixin/GTM/material/MixinGTMaterialBlocks.java:52`、`MixinGTMaterialItems.java:51`、`client/terminal/AdvancedTerminalUI.kt:283/290` 用 `GTCEuAPI.materialManager` / `HEATING_COILS` | 用的是官方成员，不需要补丁 | 类同名仍在，API 有变（highTier/coil 注册等） | **删归档**；不需要任何补丁 |
| `api/data/chemical/material/IMaterialRegistryManager.java` | 同上，0 hunks（116 行） | 旧工程无直接引用（经 `GTCEuAPI.materialManager` 间接用） | 同上 | 仍在（`api/data/chemical/material/`） | 删归档 |
| `api/data/chemical/material/Material.java` | 同上，0 hunks（1987 行） | `mixin/GTM/material/MixinGTMaterialItems.java:6`、`ETREGISTRATE.kt:3` 等大量引用 | 同上 | 仍在；1.21.1 多了 data component/registry 语义 | 删归档 |
| `api/registry/GTRegistries.java` | 同上，0 hunks（136 行） | `api/timeflow/ETTimeFlowCapability.kt:120`（`RECIPE_CAPABILITIES.register`）、`RecipeCodeWriter.kt:380/473` | 同上 | 仍在（`api/registry/`，KJS 相关新增 `KJSRecipeKeys` 等） | 删归档 |
| `api/registry/GTRegistry.java` | 同上，0 hunks（290 行） | 旧工程源码无直接引用（泛型基类） | 同上 | 仍在 | 删归档 |
| `common/data/GTItems.java` | 同上，0 hunks（2670 行） | `MixinGTMaterialItems.java:71`、`ETREGISTRATE.kt:121/136`（`unificationItem`） | 同上 | 仍在；物品注册大量改到 `GTMaterialItems`/新 registrate | 删归档 |
| `common/unification/material/MaterialRegistryImpl.java` | 同上，0 hunks（80 行） | 旧工程无直接引用 | 同上 | 仍在 | 删归档 |
| `data/recipe/generated/MaterialRecipeHandler.java` | 同上，0 hunks（691 行） | 旧工程无直接引用 | 同上 | 仍在 | 删归档 |

对照：这 8 个文件所属的「材料/注册表/配方」需求，在旧工程里实际是靠**自己的 mixin**完成的（`src/main/java/rain/gtetcore/gtet/mixin/GTM/material/` 下 4 个：`MixinGTMaterialItems` / `MixinGTMaterialBlocks` / `MixinGTMaterialOreBlocks` / `MixinGTFluids`，例如 `MixinGTMaterialItems.java:31-60` 直接 `@Overwrite generateMaterialItems()` 把材料物品导向 GTET 自己的创造页）——也就是说「改源码」这条路当时被放弃了，走的是 mixin。

### 2.2 真正的补丁（13 个文件，全部在外部检出，路径相对 `D:/java/GregTech-Modern-7.5.3-1.20.1/src/main/java/`）

先按「8.0.0 该怎么办」归类（四档，下面的表给证据）：

| 归类 | 文件 | 一句话理由 |
|---|---|---|
| **已原生支持 / 可用公开 API 替代** | `IMultiController`、`MultiblockWorldSavedData`（部件共享部分） | 重检 → `checkAndFormStructure()`；共享 → `canShared(controller, name)` |
| **架构替换，语义消失（放弃）** | `MultiblockControllerMachine`、`MultiblockWorldSavedData`（异步调度部分）、`MultiblockState`、`CentralMonitorMachine`、`RelativeDirection` | 8.0.0 没有异步检测器了；缓存/计数等优化由 `PatternState` + `checkPatternFastAt` 原生覆盖 |
| **可改用（我们自己的）mixin 或重新打补丁** | `RenderUtil`、`GuiGraphicsAccessor`、`GuiGraphicsMixin` | 8.0.0 里这三处代码 1:1 仍在，是唯一「可能仍需要」的能力；先用 mixin 验证，行不通才真打 |
| **纯残留（放弃）** | `BlockPattern`（无法逐行搬）、`PatternError`、`GTRegistration`、`another/allinit` | 格式化 / 试验残留 / 目标代码已重写 |

#### A 族：异步/延迟结构检测（补丁主体的 7 个文件）

| 补丁文件 | 改了什么的证据（成员 + 行号） | 旧工程里谁在用 | 为什么需要 | GTM 8.0.0 现状 | 1.21.1 建议 |
|---|---|---|---|---|---|
| `api/machine/feature/multiblock/IMultiController.java` | 新增 6 个 default 方法：`requestCheck()` L43、`checkPriority()` L55、`hasCheckButton()` L62、`checking()` L69、`setWaitingTime(int)` L76、`getWaitingTime()` L81（官方 219 行 → 补丁 264 行） | `TerminalBehavior.kt:37`、`ETModularMachine.kt:82`、`ETTagFilterStockBusPartMachine.kt:225`、`ETTagFilterStockHatchPartMachine.kt:130` | 给 GTET 一个「立即重检 + 优先级 + 等待期」的控制器接口 | **接口已删**（`api/machine/feature/multiblock/` 下只剩 `IDistinctPart` 与 `part/`）；职责由 `api/machine/trait/multiblock/MultiblockMachineTrait.java` 承担 | **放弃**；重检改用 `MultiblockControllerMachine#checkAndFormStructure()`（见下） |
| `api/machine/multiblock/MultiblockControllerMachine.java` | ① 新字段 `volatile boolean checking` L73、`volatile int waitingTime` L75；② 反射桥 `tryGetConfigInt()` L103-108 + `getPlacementDelayConfig/getFailedWaitingConfig/getUnloadWaitingConfig` L110-112；③ `onLoad()` 改调 `addAsyncLogicDelayed(this, getPlacementDelayConfig())` L98；④ 覆写 `checkPattern()` 做「失败等待 N tick 再试」L176-194；⑤ 重写 `asyncCheckPattern()`：`checking` 防重入 + `try/finally` 解锁 L201-222；⑥ 新增 `requestCheck()` L229-247；⑦ `setUpwardsFacing`/`setFrontFacing` 内改调 `requestCheck()` L340/L370；⑧ `checking()/setWaitingTime()/getWaitingTime()` L375-387 | `MultiblockConfigHook.kt:20-41`（反射目标）；`TerminalBehavior.kt:36-37`（`machine.waitingTime = 0` + `requestCheck()`）；`ETModularMachine.kt:81-82`、`ETModuleMachine.kt:166`（`waitingTime = 0`） | 7.5.3 的异步检测线程每 250 ms 无条件扫全部控制器，放机器瞬间/卸载瞬间会误判并刷错误；需要「延迟首检 + 失败退避 + 可被手动触发」 | **机制被整体替换**：8.0.0 没有异步检测线程（全源码 0 处 `asyncCheckPattern/addAsyncLogic/searchingTask/ScheduledExecutor`），改为事件驱动——`onLoad()` L90 用 `TickTask(2, this::checkAndFormStructure)`、`onRotated()` L456、`setFrontFacing()` L496、`setUpwardsFacing()` L485 各自触发同步重检；方块变化走 `PatternState.onBlockStateChanged(...)`（`PatternState.java:102-134`，由 `core/mixins/LevelMixin.java:80` 驱动） | **放弃**（语义已被原生替换，见 §3(c)）；要「手动强制重检」用 8.0.0 原生 `checkAndFormStructure()`，要「清缓存后重检」再配 `getDefaultPatternState().getCache().clear()`——GTM 自己的 `common/item/behavior/TerminalBehavior.java:80-81` 就是这么干的 |
| `api/pattern/MultiblockWorldSavedData.java` | ① `sharedBlocks` + `addShared/removeShared/hasShared` L38-59；② `controllers` 由 `CopyOnWriteArrayList` 换 `ConcurrentHashMap.newKeySet()` L87；③ 新增 `delayedUntil` 延迟表 + `PRIORITY_COMPARATOR`（按 `checkPriority()` 降序）L89-91；④ `firstLoad` 首轮跳过 L99 / L154-158；⑤ `getAsyncIntervalConfig()` 反射读配置 L101-108，调度间隔由固定 250 改为配置值 L112；⑥ `addAsyncLogic` 清延迟、`addAsyncLogicDelayed` L119-131；⑦ 扫描时按优先级排序、跳过未到期的控制器 L161-167；⑧ 关闭时 `delayedUntil.clear()` L191 | `MultiblockConfigHook.kt:36`（`getAsyncCheckInterval`）；其余由 GTM 补丁内部互相调用 | 控制异步检测的开销与顺序（玩家附近的机器优先），并让「刚放下/刚卸载」的机器不要立刻被判错 | **无对应物**：8.0.0 的 `MultiblockWorldSavedData.java` 只有 68 行，只有 `mapping` / `chunkPosMapping` / `getPatternsInChunk` / `addMapping` / `removeMapping`，**没有线程、没有调度、没有共享位置表**；部件共享改由 `MultiblockPartMachine#canShared(controller, substructureName)` 在 `formStructure()` 时判定（`MultiblockControllerMachine.java:300/336`） | **放弃**；部件共享已原生（新工程已在用：`ETParallelHatchPartMachine.kt:76`、`ETOverclockHatchPartMachine.kt:52` 覆写 `canShared`） |
| `api/pattern/MultiblockState.java` | ① 计数表 `Object2IntOpenHashMap` → `Reference2IntOpenHashMap`（引用比较，省 hashCode）L44/L46/L73-74；② 新增 `blockStateCache`(Long2ObjectMap\<BlockState\>) L60 与 `sharedCache` L62；③ 新增 `clearCache()` L80-85；④ `update()` 由 `boolean` 改 **`void`**、错误改由 `hasError()/error` 判定 L90-99；⑤ `getBlockState()` 走缓存 `computeIfAbsent` L127；⑥ 结构失效分支改调 `controller.requestCheck()` L207 | 由 GTM 补丁内部使用；`update()` 的语义变化被 `CentralMonitorMachine` 适配 | 检测过程里的重复 `world.getBlockState()` 与装箱开销是主线程卡顿来源 | **类被 `api/multiblock/pattern/PatternState.java` 取代**；8.0.0 原生就有按位置缓存（`PatternState.cache` 为 `Long2ObjectMap<BlockInfo>`，`BlockPattern.checkPatternFastAt()` L81-136 先比对缓存命中直接返回 `VALID_CACHED`），等于把「缓存方块状态」这件事原生化并更进一步 | **放弃**（缓存已原生，且粒度更好）；GTET 侧若要「清缓存」用 `PatternState#shouldUpdate` / `getDefaultPatternState().getCache().clear()` |
| `api/pattern/BlockPattern.java` | ① 计数表换 `Reference2IntOpenHashMap` L40/L391/L402；② 遍历改 `reference2IntEntrySet().fastIterator()` L196/L218；③ `blockMatches[c]`/`[b]`/`[a]` 加三层 null 守卫 L139-143；④ 用 `ordinal` 版偏移 `setActualRelativeOffset(..., int ordinal, ...)`（新增 L560-643，旧枚举版保留给 `autoBuild` L644）；⑤ `worldState.update()` 改判 `hasError() && error == UNLOAD_ERROR` L148；⑥ 部件共享判定加注释说明 L158；⑦ `frontFacing.ordinal()` 提前取出 L127 | 由 GTM 补丁内部使用 | 结构检测是热路径，`Direction.values()`/枚举比较/装箱在大型多方块上开销明显 | **已重写**：8.0.0 的 `BlockPattern` 走 slice/predicate 体系（`checkPatternFastAt` L81、`checkPatternAt` L139、`checkSlice` L191；`IBlockPattern.java` 定义签名），偏移改由 `RelativeDirection#getRelativeFacing` + `OriginOffset#apply` 完成（L194-199、L253-258），**没有** `blockMatches` 三维表、没有 `Object2Int` 计数 | **放弃**（无法逐行搬，且 8.0.0 的检测路径本身已换）；若要 1.21.1 的性能优化，应按新结构单独评估，不是「搬补丁」 |
| `api/pattern/util/RelativeDirection.java` | 新增 `getActualOrdinal(int facingOrdinal)` L81-83（按 ordinal 算方向，避免造中间 `Direction`） | 由 `BlockPattern` 的 ordinal 版偏移调用 | 配合上面的热路径优化 | 8.0.0 该文件有 `oppositeOrdinal()` L43、静态 `getActualDirection(Direction,Direction,Direction)` L152，**没有** `getActualOrdinal` | 放弃（无调用方） |
| `common/machine/multiblock/electric/CentralMonitorMachine.java` | `state.update(...)` 的返回值判定改为 `state.hasError() && state.error == UNLOAD_ERROR` L181-182 | 无（纯适配） | 适配 `update()` 由 boolean 改 void | 8.0.0 该类已不使用 `MultiblockState`（0 处 `PatternState`/`checkStructure`），无对应点 | 放弃 |

> 反射桥的 5 个方法里，GTM 侧只读了 4 个（`getFailedWaitingTicks` / `getPlacementDelayTicks` / `getUnloadWaitingTicks` / `getAsyncCheckInterval`）；`MultiblockConfigHook.kt:40 shouldSendErrorMessage()` 在补丁检出里**没有任何调用方**（全检出 grep 0 命中）——是第 5 个死钩子。

#### B 族：研究物品渲染的递归修复（3 个文件 + 依赖升级）

| 补丁文件 | 改了什么的证据 | 旧工程里谁在用 | 为什么需要 | GTM 8.0.0 现状 | 1.21.1 建议 |
|---|---|---|---|---|---|
| `client/util/RenderUtil.java` | ① 新增 `renderingResearchOutput` 重入闸 `L64-65`；② `renderResearchItemContent(...)` **去掉 `Operation<Void> originalMethod` 参数** L287-289；③ 递归渲染输出改走 `((GuiGraphicsAccessor) graphics).callRenderItem(...)` 并前后置位重入闸 L306-308 | 无（GTET 侧不引用；纯 GTM 内部行为修复） | 官方用 MixinExtras `@WrapMethod` + `Operation.call` 在「研究物品 → 产物」这条路径上存在重入/栈溢出的隐患 | **原样保留官方形态**：`RenderUtil.java:362` 仍是 `(GuiGraphics, Operation<Void>, …)` 且 L381 仍是 `originalMethod.call(...)`；`core/mixins/client/GuiGraphicsMixin.java:40-48` 仍是 `@WrapMethod` + `original.call(...)` | **先实测再决定**（见 §4 第 1 步）；确实复现时才考虑重打 |
| `core/mixins/client/GuiGraphicsAccessor.java` | 新增 `@Invoker("renderItem") callRenderItem(entity, level, stack, x, y, seed, z)` L18-21（官方只有 `callFlushIfUnmanaged`） | 同上 | 给上面的实现提供「绕过 wrap 直接调原方法」的入口 | 8.0.0 的 `GuiGraphicsAccessor.java` 只有 `callFlushIfUnmanaged`（501 B） | 同上 |
| `core/mixins/client/GuiGraphicsMixin.java` | `@WrapMethod` → `@Inject(method="renderItem(...)", at=HEAD, cancellable=true)` L19-27，并在入口判重入闸、命中则 `ci.cancel()` | 同上 | 同上 | 8.0.0 仍是 `@WrapMethod` 形态（L40-48） | 同上 |
| （伴随改动）`gradle/libs.versions.toml` → jarJar 内嵌 | 补丁 jar 内嵌 `mixinextras-forge-0.5.5.jar`，官方内嵌 `0.5.3`（ZIP 条目比对） | — | 配合 B 族的注入方式 | — | 若走「真打补丁」路线，必须一并记录这类**非源码**改动，否则重建出来的 jar 不等价 |

#### C 族：无功能价值的残留

| 补丁文件 | 证据 | 结论 |
|---|---|---|
| `api/pattern/error/PatternError.java` | L48 `getDisplayName()` → `getDisplayName().getString()` | 1.20.1 的 `Component` 转字符串适配；8.0.0 错误类已重写（`api/multiblock/error/*`），无对应点。放弃 |
| `common/registry/GTRegistration.java` | L12 加空行、L16 `/**/` → `/* */`（纯格式；class 字节差异仅来自行号表） | 无功能改动。放弃 |
| `another/allinit.java`（**新增文件**） | `public class allinit { public static void init() {} }`（空类） | 试验残留，却已经打进了分发的 jar（`com/gregtechceu/gtceu/another/allinit.class`）。放弃，且**不要**在 1.21.1 重建 |

---

## 3. 方案对比

### (a) 照旧真打补丁：在 1.21.1 重建这条流水线

**具体步骤**（假定仍然只补 GTM）：

1. 拿到与 `libs/gtceu-1.21.1-8.0.0-SNAPSHOT.jar` **完全同源**的 GTM 源码检出。注意 8.0.0 是 **SNAPSHOT**（`maven.gtceu.com` 的 `gtceu-1.21.1` 版本表里 `latest = 8.0.0-SNAPSHOT`，`lastUpdated = 20261003062337`），不是 tag——检出一旦和 jar 不同源，「编译用的 GTM」与「实际运行的 GTM」就会错位。旧工程那套「归档 = 补丁源码」的账本必须在这里重建（把 §2.2 的 13 个文件按原包路径归档，否则又是不可复现）。
2. 在检出里改 `gradle/libs.versions.toml` 之外的补丁源码 → 跑检出自己的 Gradle 构建。
3. **构建参数必须换**（因为 7.5.3 的经验不能照抄）：
   - JDK **21**（不是 17）：`build.gradle:21 toolchain.languageVersion = JavaLanguageVersion.of(21)`；本机可用 `C:/Program Files/Zulu/zulu-21`。
   - 检出的 `gradle.properties` 里**没有** 7.5.3 那行失效的 `org.gradle.java.home`（8.0.0 只有 `org.gradle.java.installations.auto-download=false`）→ 那条「必须覆盖 java.home」的坑消失，但**变成新坑**：`auto-download=false` 意味着本机没有 JDK 21 时不会自动下载，会直接失败。
   - 内存参数照旧保守（`--no-daemon`、`-Dorg.gradle.workers.max=1`、`-Dorg.gradle.jvmargs=-Xmx512m`），原因不变（NeoForm/ModDevGradle 反编译峰值）。
   - 8.0.0 用 **ModDevGradle**（不是 ForgeGradle/NeoForm 那套），反编译产物与缓存目录都不同，旧的「外部构建产物落在 `~/.m2`、由 `publishToMavenLocal` 产出」假设要重新验证：`publishToMavenLocal` 未必仍是可用的收口方式，可能要直接取 `build/libs/*.jar`。
4. 产物落地方式**在 1.21.1 完全不同**：新工程根本不用 JarJar 内嵌 GTM，而是 `compileOnly`+`runtimeOnly files("libs/gtceu-1.21.1-8.0.0-SNAPSHOT.jar")`（`build.gradle:146-148`）。所以「vendored maven 三件套 + ContainedDeps」这套机制在 1.21.1 属于**多余的复杂度**——直接把补丁版 jar 覆盖 `libs/gtceu-1.21.1-8.0.0-SNAPSHOT.jar` 就够（但代价见下）。
5. 自检判据要换：`MultiblockConfigHook` 这个字符串在 8.0.0 的补丁里根本不会出现（异步子系统没了）。新判据必须新造（例如用 `javap -p` 比对补丁前后某个类的成员集合，或对内嵌 jar 做 sha256 基线）。

**坑（按严重度）**：

| 坑 | 说明 |
|---|---|
| SNAPSHOT 漂移 | 8.0.0 没有正式 tag；上游一更新，补丁 checkout 与 `libs/` 那份就对不上，编译期 API 与运行期行为可能两个版本 |
| 内嵌/替换另一个 mod 的 jar | 本工程现在是把 gtceu 当**外部依赖**（整合包里由玩家/包自己提供）。一旦换成自建补丁版，分发就变成「GTET 自带一份 GTM」：① 版本必须钉死、且与玩家装的 GTM 只能有一份；② LGPL-3.0 的 GTM 被改造后分发要带源码/许可与修改说明（旧工程靠 modpatch 归档勉强算账本，现在账本是空的）；③ 上游一升级就得重打，否则 GTET 把整合包钉死在旧 GTM 上 |
| 补丁成本暴涨 | 7.5.3 的补丁是「在既有异步检测器上加钉子」（~500 行）。8.0.0 要复刻同一效果，得**先在 8.0.0 里把异步检测器整体加回去**（`MultiblockControllerMachine` / `PatternState` / `MultiblockWorldSavedData` 三处重构 + 与原生事件驱动路径共存），量级完全不是一回事 |
| 可复现性 | 旧工程栽在这上面（归档 ≠ 补丁）。重建时若不做「补丁源码归档 + 产物 sha256 基线 + 自检断言」，同样会漂 |

### (b) 改用 mixin / GTM 公开 API 替代

**能替的（逐条）**：

| 原补丁能力 | 8.0.0 替代物 | 替代类型 |
|---|---|---|
| 手动强制重检（`requestCheck()`） | `MultiblockControllerMachine#checkAndFormStructure()`（public，`MultiblockControllerMachine.java:191`）；要「清缓存再检」配 `getDefaultPatternState().getCache().clear()` | **原生 API**（GTM 自己的 `TerminalBehavior.java:80-81` 就这么用） |
| 放置后延迟首检（`placementCheckDelay=20`） | `onLoad()` 里 `TickTask(2, this::checkAndFormStructure)`（L90）；事件驱动，不再是轮询 | **原生行为**（延迟值不可配，2 tick） |
| 卸载/失败退避与重试间隔（`unloadWaitingTime` / `checkFailedWaitingTime` / `asyncCheckInterval`） | **没有替代**：8.0.0 不轮询，失败后的重试只由「邻近方块变化」或玩家/终端触发 | **能力消失** |
| 检测优先级（`checkPriority()`） | 无（没有队列可排序） | **能力消失** |
| 结构检测开销控制（`checking` 防重入、缓存方块状态、引用计数表） | `checkPatternFastAt()` 的缓存命中快路径（L81-136）+ `PatternState.cache` | **原生已覆盖**（粒度更好） |
| 部件共享（`sharedBlocks/hasShared`） | `MultiblockPartMachine#canShared(controller, substructureName)`（`MultiblockControllerMachine.java:300/336`） | **原生 API**（新工程已在用） |
| 材料/注册表/配方那 8 个归档文件 | 新工程用自建 `ETRegistrate.inTab(...)`（`registry/ETRegistrate.kt:49-67`）+ 自己的创造页（`registry/ETCreativeModeTabs.kt`），**不需要动 GTM** | **完全不必要** |

**替不了的（逐条说清是哪一类原因）**：

1. **加字段**：`MultiblockControllerMachine` 的 `checking` / `waitingTime`（7.5.3 补丁 L73/L75）。mixin 可以 `@Unique` 加字段，但**加在别人的类上、还要被别人（GTM 自己的 `setFrontFacing`）读到**，只能靠 `@Overwrite`/`@Inject` 到处改——等于把补丁搬到 mixin 里，收益为零。
2. **改签名 / 改返回值语义**：`MultiblockState.update()` 由 `boolean` 改 `void`（L90），以及 `BlockPattern` 遍历表类型由 `Object2IntMap` 改 `Reference2IntOpenHashMap`（L40/L391/L402）。前者会连带所有调用点（GTM 自己的 `CentralMonitorMachine` 也调），mixin 改不动返回值类型；后者依赖调用方静态类型，**混入方与被混入方必须同时改**。
3. **跨类新增公有 API 并被第三方调用**：`IMultiController` 那 6 个 default 方法（L43-83）。mixin 给接口加 default 方法属于高危操作（`@Mixin` 对接口 default 的注入受限，且 8.0.0 里这个接口**已不存在**——要加就得加到 `MultiblockMachineTrait` 或直接加到 `MultiblockControllerMachine`，但后者已被 GTM 原生方法占位）。
4. **依赖版本变更**：补丁 jar 内嵌 `mixinextras-forge-0.5.5`（官方 0.5.3）。这不是 mixin 能表达的，属于「必须真打包」的部分。
5. **B 族（研究物品渲染重入）**：这一族**理论上可以用我们自己的 mixin 替**——新工程已有 mixin 基建（`src/main/java/rain/fox/gtetcore/mixin/` + `src/main/resources/gtetscore.mixins.json`，`compatibilityLevel: JAVA_21`）。但要注意：GTM 自己的 `GuiGraphicsMixin` 已经 `@WrapMethod` 了同一个 `renderItem(...)`，我们的注入必须与它序位兼容（vanish/priority 调优），且要能证明递归真的发生——否则是拿一个不受控的 mixin 换一个确定的补丁。

### (c) 放弃部分补丁

可放弃且**没有功能损失**的：

- A 族 7 个文件全部：① 8.0.0 的检测机制已换成事件驱动 + 缓存快路径，异步/退避/优先级这些概念在新架构里没有落点；② 这些能力服务的旧功能（终端强制重检、AE 库存总线/仓的 `requestCheck`、模块机换结构重检）**在 1.21.1 上要么已由 GTM 原生覆盖（终端重检），要么还没移植**（AE 切片：见 `AdvancedTerminalBehavior.kt:50-58` 里的 `TODO(AE 切片)`）。
- C 族 3 个文件全部：纯残留。
- 归档 8 个文件全部：本来就没改动，且新工程用自建 registrate/创造页解决了同一需求。
- 新工程配置里那 4 个多方块配置项：`GtetConfig.kt:64-73`（`checkFailedWaitingTime` / `placementCheckDelay` / `unloadWaitingTime` / `asyncCheckInterval`）与取值访问器 `L145-151`——**它们现在是无消费者的死配置**（旧工程靠 `MultiblockConfigHook` + 补丁 GTM 才生效）。建议在文档/配置里明确标注为「1.21.1 无效」，或直接删掉；否则玩家改了它还以为是生效的。

---

## 4. 推荐路线

**结论：不要重建 modpatch/patchJar 流水线。走 (c) 为主 + (b) 单点验证**——即：

1. **归档 8 文件 + A/C 族补丁：放弃**（8.0.0 已原生覆盖或被架构替换；对应 GTET 功能在 1.21.1 上要么已由 GTM 原生提供，要么尚未移植）。
2. **清理死配置**：`GtetConfig.kt` 的 4 个多方块项要么删、要么标注 1.21.1 无效。
3. **只保留一个待验证项**：旧工程那个研究物品渲染重入修复（B 族）。它 1:1 对应 8.0.0 仍然存在的代码，属于「可能仍需要」的唯一一项——先实测，再决定是否用我们自己的 mixin 实现。

**执行顺序**（每步都可独立收工）：

1. **第一步（只读实测，不改代码）**：在 dev 环境造一个带 `RESEARCH_ITEM` data component 的 GTM 研究物品（`gtceu:machine/...` 研究数据棒，或直接找 GTM 已有研究产物），按住 Shift 悬停，观察是否复现无限递归/StackOverflow。判据就是 8.0.0 那两处仍存在的代码路径：`core/mixins/client/GuiGraphicsMixin.java:40-48`（`@WrapMethod` + `original.call`）+ `client/util/RenderUtil.java:362/381`（`originalMethod.call(entity, level, output, …)` 递归渲染产物）。
   - 结论 A：**不复现** → 全案结束，只做第 2、3 步的清理。
   - 结论 B：复现 → 进第 4 步。
2. **第二步**：把 `docs/modpatch-1.21.1-方案.md`（本文件）的结论落到 `docs/GTM-1.21.1-问题记录.md` 的附录表里——那条「1.20.1 的 `IMultiController#requestCheck()` 在 8.0.0 对应 `MultiblockControllerMachine#checkAndFormStructure()`」值得补进去（该附录已有 `canShared` 等同类条目）。
3. **第三步**：处理 `src/main/kotlin/rain/fox/gtetcore/config/GtetConfig.kt:64-73` 与 `L145-151` 这 4 个死配置（删或标注）；同时删掉/不再移植旧工程 `MultiblockConfigHook.kt` 的反射桥（新工程不需要，因为 GTM 侧不再有反射读取点）。
4. **第四步（仅在结论 B 时执行）**：在 `src/main/java/rain/fox/gtetcore/mixin/` 下加一个 `GuiGraphicsMixin`（与 GTM 同名类，不同 package），用 `@Inject(method = "renderItem(...)", at = @At("HEAD"), cancellable = true)` + 一个 `ThreadLocal` 重入闸复刻旧补丁的语义；`gtetscore.mixins.json` 的 `client` 数组加一项。**注意**：这需要与 GTM 自己的 `@WrapMethod` 共存（如冲突，改用 `priority`/`@At` 定位），并且在 1.21.1 上重新验证，不能假设旧补丁的行为等价。
5. **第五步（仅在第四步失败、且该 bug 严重影响体验时才考虑）**：才动「真打补丁」——而且要用 1.21.1 的轻量形态：在**与 `libs/gtceu-1.21.1-8.0.0-SNAPSHOT.jar` 同源**的检出上改这一个文件 → JDK 21 / `zulu-21` 构建 → 用产物覆盖 `libs/gtceu-1.21.1-8.0.0-SNAPSHOT.jar`，并在 `docs/` 里登记「补丁源码副本 + 基线 sha256 + 重建命令」。**不要**恢复老的 vendored-maven + JarJar/ContainedDeps 那一套（新工程不用 JarJar 内嵌 GTM，`build.gradle:146-148`）。

---

## 5. 未解决 / 需用户决定的问题

1. **旧工程那 8 个归档文件的来历**：它们与官方 7.5.3 逐字节相同，但被 README 描述为「补丁版」。是「一次已回退的尝试的残留」，还是「本应生效却漏覆盖」？判定只需一条只读命令（`D:/java/GregTech-Modern-7.5.3-1.20.1` 是 git 工作树，`git status --short` / `git diff` 会直接把补丁清单列出来）——**因为纪律要求不碰 git，我没有执行**。需要你授权或自行跑一次以钉死这件事。
2. **8.0.0 的补丁基线**：`libs/gtceu-1.21.1-8.0.0-SNAPSHOT.jar` 是从哪来的——`D:\gtm1211-src\GregTech-Modern-latest-1.21` 构建的，还是从 `maven.gtceu.com` 下的？这决定「万一要打补丁」时是否真能对齐同源源码（8.0.0 是 SNAPSHOT，随时漂移）。
3. **B 族（研究物品渲染递归）在 1.21.1 是否仍复现**：这是唯一一项可能仍需处理的能力，但必须实测才知道（见 §4 第 1 步）。若你已知当年为什么加这个修复（现场报错/复现步骤），直接告诉我可以省掉一轮试错。
4. **旧工程的 4 个多方块配置项在新工程里怎么处置**：直接删（玩家 toml 里的键会被丢弃）、还是保留但标注无效？涉及 `GtetConfig.kt` 与语言文件（`assets/*/lang/*.json` 里已有对应文案）。
5. **AE 切片未移植带来的连带问题**：旧工程里 `requestCheck()` 的三个 AE 侧调用点（`ETTagFilterStockBusPartMachine.kt:225`、`ETTagFilterStockHatchPartMachine.kt:130`、`IMEStockingHost.kt:87`）在 1.21.1 上还没对应物。移植 AE 时它们的等价物应是 `checkAndFormStructure()`——这条约定要不要现在就写进项目笔记，避免移植时又去找 `requestCheck`。

---

## 附：真补丁的权威清单（本会话只读 `git` 复核，非推断）

在外部检出 `D:\java\GregTech-Modern-7.5.3-1.20.1` 上执行**只读**命令得到的结论：

```
$ git status --short
 M README.md
 M gradle.properties
 M gradle/scripts/repositories.gradle
 M settings.gradle
 M src/main/java/com/gregtechceu/gtceu/api/machine/multiblock/MultiblockControllerMachine.java
 M src/main/java/com/gregtechceu/gtceu/api/pattern/MultiblockWorldSavedData.java
 M src/main/java/com/gregtechceu/gtceu/api/pattern/error/PatternError.java
?? ogmr/            （旧构建用的补丁覆盖目录，未跟踪）

$ git diff --stat
 MultiblockControllerMachine.java  | 18 +++++--
 MultiblockWorldSavedData.java     | 35 +++++++++++--
 PatternError.java                 |  2 +-
 （其余 4 个 M 是检出自带的构建配置，不是对 GTM 的补丁）

HEAD = 5f04f73  feat: GTET-optimized multiblock per-tick detection
```

**结论**：真正对 GTM 的功能性补丁只有 **3 个文件、约 55 行**，主题就是 commit 标题写的「多方块结构每 tick 检测」。
旧工程那整套 `modpatch` → 外部检出构建 → `patchJar/` vendored → JarJar 内嵌的机制，实际只服务于这一处优化。
而 GTM 8.0.0 的结构检测已整体重写为**事件驱动 + 缓存**（`checkAndFormStructure` /
`PatternState#onBlockStateChanged` / `checkPatternFastAt`），该优化在 1.21.1 上已无必要 ——
**新工程不需要重建 modpatch / patchJar 流水线。**
