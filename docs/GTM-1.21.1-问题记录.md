# GTM 1.21.1 / NeoForge 问题记录

记录移植 GTET 到 **1.21.1 + NeoForge** 过程中，实际踩到的 **GTM（GTCEu 8.0.0-SNAPSHOT）及其依赖（MUI 等）自身的问题**。
每条固定格式：**一句话** → **来源**（原始报错原文，可直接在日志里搜）→ **触发** → **处理/状态**。

环境基线（换版本时整份文档都要重新复核）：

| 项 | 版本 |
|---|---|
| Minecraft / NeoForge | 1.21.1 / 21.1.252 |
| GTCEu（GTM） | 8.0.0-SNAPSHOT（`libs/gtceu-1.21.1-8.0.0-SNAPSHOT.jar`） |
| MUI | `brachy.modularui:modularui-mc1.21.1:3.3.1-SNAPSHOT`（gtceu jarJar 携带） |
| Registrate | `com.tterrag.registrate:Registrate:MC1.21-1.3.0+67`（gtceu jarJar 携带） |
| Kotlin for Forge | 5.7.0 |

添加新条目：照最后面的模板追加，编号递增；**能定位到上游代码的写清类名与行号**，能给出原始报错原文的一律原文贴上。

---

## 1. MUI 调试覆盖层：点按钮直接崩客户端（严重度：高 · 状态：已修，上游未修）

**一句话**：MUI 调试覆盖层的「Print Theme json」勾上后，点任意按钮都会因主题 JSON 编码器不判空而崩客户端。

**来源**：

```
Caused by: java.lang.NullPointerException: Cannot invoke "brachy.modularui.api.ITheme.getId()" because the return value of "brachy.modularui.api.ITheme.getParentTheme()" is null
	at brachy.modularui.api.ITheme$1.encode(ITheme.java:32)
	at brachy.modularui.overlay.DebugOverlay.logTheme(DebugOverlay.java:154)
	at brachy.modularui.widgets.ButtonWidget.onMousePressed(ButtonWidget.java:72)
```

**触发**：调试覆盖层里勾选「Print Theme json」（它在 MUI 的 dev 配置项里），然后点面板上任何按钮。

**成因**：`ITheme$1.encode` 无条件执行 `getParentTheme().getId()`；**根主题没有父主题**，于是 NPE，异常从 NeoForge 事件总线抛出 → 客户端崩溃（`run/crash-reports/crash-2026-10-03_15.57.39-client.txt`）。字节码里就是 offset 36/41 两句连着：

```
36: invokeinterface ITheme.getParentTheme()
41: invokeinterface ITheme.getId()
```

**处理**：本工程 `src/main/java/rain/fox/gtetcore/mixin/modularui/debug/IThemeEncoderMixin.java` 用 `@Redirect` 把第二个 `ITheme#getId()`（`ordinal = 1`）改成判空，父主题为空写 `"null"`。临时规避＝关掉那个调试选项。

---

## 2. 数据生成报「GTM 的父模型不存在」其实是 NeoForge 的参数问题（严重度：高 · 状态：已修）

**一句话**：`runData` 报 gtceu 的父模型不存在，看着像 GTM 缺资源，真因是 NeoForge 1.21 的数据生成只认识 `--existing` 目录和 `--existing-mod` 点名的模组资源。

**来源**：

```
Caused by: java.lang.IllegalStateException: Model at gtceu:block/machine/template/part/hatch_machine_color_ring does not exist
	at com.gregtechceu.gtceu.data.model.builder.MachineModelBuilder.forAllStatesExcept(MachineModelBuilder.java:325)
Caused by: java.lang.RuntimeException: Unexpected error while running data generator of type null for entry test_sync_part [minecraft:block]
```

**触发**：addon 的机器模型拿 GTM 的模板模型当父模型时必现（我们这个文件确实在 jar 里，只是数据生成看不到）。

**成因**：`net.neoforged.neoforge.data.loading.DatagenModLoader#begin` 构造 `new ExistingFileHelper(existingPacks, existingMods, ...)`，两个入参分别来自命令行 `--existing <目录>` 与 `--existing-mod <modid>`（由 NeoForge 打在 `net.minecraft.data.Main` 上）。1.20.1 的 Forge 会自动带上模组资源，所以老工程不需要这个参数。

**处理**：`build.gradle` 的 data run 加 `programArguments.addAll '--existing-mod', 'gtceu'`。

---

## 3. GTM 的 EMI / JEI mixin 在没装那两个 mod 时刷一批警告（严重度：低 · 状态：忽略）

**一句话**：GTM 的 mixin 配置没有做「目标 mod 存在才加载」的判定，没装 EMI/JEI 时会刷 10 行类找不到的警告。

**来源**：

```
@Mixin target dev.emi.emi.api.EmiApi was not found gtceu.mixins.json:emi.EmiApiAccessor from mod gtceu
@Mixin target mezz.jei.gui.recipes.RecipesGui was not found gtceu.mixins.json:jei.RecipesGuiAccessor from mod gtceu
Error loading class: dev/emi/emi/api/EmiApi (java.lang.ClassNotFoundException: dev.emi.emi.api.EmiApi)
Error loading class: mezz/jei/neoforge/platform/FluidHelper (java.lang.ClassNotFoundException: mezz.jei.neoforge.platform.FluidHelper)
```

**触发**：开发环境没把 EMI / JEI 放进运行时依赖。

**处理**：忽略（装了就消失）。**排查其它问题时注意别被这批警告带偏**。

---

## 4. GTM 音效缺字幕翻译，客户端刷一批 ERROR（严重度：低 · 状态：忽略，上游问题）

**一句话**：GTM 自己漏了音效字幕的语言键，开字幕时每条音效打一条 ERROR。

**来源**：

```
Missing subtitle translation{key='subtitle.gtceu.boiler', args=[]} for sound event: gtceu:boiler
Missing subtitle translation{key='subtitle.gtceu.assembler', args=[]} for sound event: gtceu:assembler
```

（`boiler` / `assembler` / `arc` / `bath` / `centrifuge` / `chainsaw` / `chemical` / `combustion` / `compressor` / `computation` / `cooling` / `cut` … 一大批）

**处理**：忽略。游戏内只有开启字幕后才会看到。

---

## 5. MUI 自带测试物品缺模型（严重度：低 · 状态：忽略）

**一句话**：MUI 自己的测试物品没有模型文件，客户端加载模型时刷一条警告。

**来源**：

```
Unable to load model: 'modularui:item/test_item' referenced from: modularui:item/
```

**处理**：忽略（上游问题，纯日志噪音）。

---

## 6. JarJar 报「选到两个相同标识的嵌套 mod」（严重度：低 · 状态：待观察）

**一句话**：gtceu 以文件依赖同时进了编译期和运行期，而它自身又是 jarJar 载体，选择器会抱怨同一嵌套 mod 被选中两次。

**来源**：

```
[net.neoforged.jarjar.selection.JarSelector/]: Attempted to select two dependency jars from JarJar which have the same identification: Nested Mod File  in Mod File: ...
```

**处理**：目前无害（运行正常）。若以后 gtceu 能走 maven 依赖（`implementation`），这条会自动消失。

---

## 7. 开发环境的 refmap 警告（严重度：低 · 状态：忽略，但发布前必须复核）

**一句话**：GTM / MUI / configuration（以及我们自己的 mixin 配置）在开发环境读不到 refmap，属预期，Release 构建才是真问题。

**来源**：

```
Reference map 'gtceu.refmap.json' for gtceu.mixins.json could not be read. If this is a development environment you can ignore this message
Reference map 'modularui.refmap.json' for modularui.mixins.json could not be read. If this is a development environment you can ignore this message
```

**处理**：忽略；打正式包前确认生产环境的 mixin 映射正常。

---

## 8. GTM 流体存储键重复登记警告（严重度：低 · 状态：忽略，上游问题）

**一句话**：GTM 注册流体时对同一 material 重复挂 FluidStorageKey，刷三条警告。

**来源**：

```
[GTCEu/]: FluidStorageKey{...} already has an associated fluid for material gtceu:lava
[GTCEu/]: FluidStorageKey{...} already has an associated fluid for material gtceu:milk
[GTCEu/]: FluidStorageKey{...} already has an associated fluid for material gtceu:water
```

**处理**：忽略。1.21.1 的 GTM 是 SNAPSHOT，这类自检警告会陆续出现，注意区分「上游自检噪音」与「真的加载失败」。

---

## 9. MUI 调试覆盖层会自动挂在每个界面上，且没有显眼的关闭方式（严重度：中 · 状态：已定位，有热键规避）

**一句话**：`[dev] debugUI = true` 时，MUI 每开一个界面都会自动叠一层调试面板（Debug Options + 左下角调试信息），界面因此显得「收不回去」。

**来源**（字节码，`OverlayStack.onOpenScreen`）：

```
67: invokestatic  // ModularUIConfig$Dev.debugUI:()Z
70: ifeq 103
73: aload_0
74: instanceof    // brachy/modularui/api/IMuiScreen
85: new           // class brachy/modularui/overlay/DebugOverlay
90: invokespecial // DebugOverlay."<init>":(Lbrachy/modularui/api/IMuiScreen;)V
```

配置项（`run/config/modularui.toml`，**默认 false**，我们这份被打开了）：

```toml
[dev]
	#Debug UI? (Will draw widget outlines and widget information)
	#Default: false
	debugUI = true
```

**触发**：开着 `dev.debugUI` 时打开任何 MUI 界面。

**处理**：
- **热键直接翻开关：`Ctrl + Shift + Alt + C`**（开着界面时按）。字节码里就是 `keyTyped` 判 `keyCode == 67('C') && ctrl && shift && alt` → `DEBUG_UI.set(!debugUI())`。
- 或者直接把 `run/config/modularui.toml` 的 `[dev] debugUI` 改回 `false`。
- 关掉之后界面就能正常用 ESC 关闭了。**若仍关不掉**，先在面板空白处点一下（把焦点从数字输入框移开）再按 ESC——MUI 的文本框会吃掉第一次 ESC（见配置项 `[ui] escRestoresLastText` 的说明）。

**顺带**：同一份配置里 `[ui] enableTestGuis = true`（默认就是 true）会给 MUI 注册它的测试方块/测试物品，第 5 条那条缺模型警告就是它带来的。

---

## 10. 带 `@EventBusSubscriber` 的 Kotlin object 会让 mod 构造期直接崩（严重度：高 · 状态：已绕开；KFF 与 NeoForge 版本不兼容）

**一句话**：Kotlin for Forge 5.7.0 与 NeoForge 21.1.252（FML loader 4.0.44）不兼容——**只要 classpath 上存在带 `@EventBusSubscriber` 的 Kotlin object，mod 构造期必崩**，`runData` 与游戏都起不来；改成手工 `addListener` 即可。

**来源**：

```
java.lang.NoClassDefFoundError: net.neoforged.fml.Bindings
	at thedarkcolour.kotlinforforge.neoforge.AutoKotlinEventBusSubscriber.registerTo(...)
```

**触发**：任何 Kotlin `object` 上加 `@EventBusSubscriber`（老项目 1.20.1 的写法）。
⚠️ 顺带一个陷阱：`EventBusSubscriber.Bus` 在 21.1.252 已 `@Deprecated(forRemoval = true)`，而且 NeoForge 的 `AutomaticEventSubscriber.inject` **根本不读 `bus` 成员**（它按每个 `@SubscribeEvent` 方法的参数是不是 `IModBusEvent` 自动判总线）——所以「把 `bus` 参数去掉」并不能解决这个崩溃。

**成因**：FML loader 4.0.44 删掉了 `net.neoforged.fml.Bindings`，而 KFF 5.7.0 的 `AutoKotlinEventBusSubscriber` 仍在调用它。属上游版本不匹配，不是本工程引入的问题。

**处理**：本工程**一律手工注册**，不用 `@EventBusSubscriber`：

```kotlin
NeoForge.EVENT_BUS.addListener(TerminalGroupSeeder::onPlayerTick)   // GAME 总线
MOD_BUS.addListener(::onGatherData)                                 // mod 总线
```

长期建议：换成与 FML 4.0.44 匹配的 KFF 版本；在那之前，任何人加回 `@EventBusSubscriber` 都会复现这个崩溃。

---

## 附录：不是 bug，但会让老代码编不过（API 变更，详见项目笔记）

| 1.20.1 写法 | 8.0.0 现状 |
|---|---|
| `implements IParallelHatch` | 接口已删；控制器用 `part instanceof ParallelHatchPartMachine` 识别，必须继承 GTM 那个类 |
| `canShared()` | `canShared(MultiblockControllerMachine, String substructureName)` |
| `api.machine.trait.RecipeLogic` | `api.machine.trait.recipe.RecipeLogic` |
| `IFancyUIMachine#createUIWidget()`（LDLib 控件） | `IMuiMachine#buildMainUI(...)`（MUI） |
| `@Persisted` / `@DescSynced` / `MANAGED_FIELD_HOLDER` | `@field:SaveField` / `@field:SyncToClient` / 持有者全删 |
| `saveCustomPersistedData` / `loadCustomPersistedData` | `saveAdditional` 是 final；改 `@SaveField` 字段；读档覆写 `loadAdditional`（在 super 之前） |
| `IMachineBlockEntity` / `IInteractedMachine` | 都删了；机器构造收 `BlockEntityCreationInfo`，右键手势覆写成 `MetaMachine#onUseWithItem(ExtendedUseOnContext)`（拿东西的那一路）或 `#onUse(...)`（空手那一路），两者都排在 `tryOpenUI(...)` 之前 |
| `RecipeCapability(String name, …)`、`getName()` = `recipe.capability.<name>.name` | 构造器收 `ResourceLocation`；`getName()` = `id.toLanguageKey("recipe_capability")` ⇒ 键是 `recipe_capability.<命名空间>.<路径>`，配方里写的键也变成带命名空间的全名 |
| `IGTAddon#registerRecipeCapabilities()` 里 `GTRegistries.RECIPE_CAPABILITIES.register(...)` | 回调已删；照 `GTRecipeCapabilities.java:27-31` 走 `REGISTRATE.generic(路径, GTRegistries.Keys.RECIPE_CAPABILITY, 构造器).register()` |
| `handleRecipeInner` 返回 `null` 表示「已结清」 | 返回标了 `@NotNull`，**不许返回 `null`**；结清要返回**空表** |
| 处理器可以不是 `MachineTrait`（自己拼 `RecipeHandlerList`） | `MultiblockPartMachine#getHandlerList()` 只从 `getTraitsByInterface(IRecipeHandlerTrait.class)` 收；且 `@SaveField` 只对 `ISyncManaged` 生效 ⇒ 处理器必须继承 `NotifiableRecipeHandlerTrait`，否则既不进多方块、也存不了档 |
| `Content` 的字段式访问（`content.chance`） | 8.0.0 的 `Content` 是 record，组件访问器无 `get` 前缀；Kotlin 侧仍按属性写（`content.chance` / `content.content`）即可 |
| 只实现 `IMuiMachine` 的部件覆写 `onLoad()` 写 `super.onLoad()` | 接口链上有 NeoForge `IBlockEntityExtension#onLoad()` default ⇒ Kotlin 报 `Multiple supertypes available`，要写 `super<TieredPartMachine>.onLoad()` |
| `IMultiController` / `IMultiPart` / `IDisplayUIMachine#addMultiText` | 全删。部件不能再往控制器面板追加文本（自己开 MUI 面板）；要控制器身份就 `this as? MultiblockControllerMachine`（`parts` 在 `MultiblockControllerMachine.java:163`） |
| `IParallelHatch` / `IParallelHatch#parallelHatch()` | 接口删了；`MultiblockControllerMachine#parallelHatch` 返回 `Optional<ParallelHatchPartMachine>`，控制器认并行仓靠 `instanceof`（`MultiblockControllerMachine.java:183,313`） |
| `class MyLogic(machine: IRecipeLogicMachine) : RecipeLogic(machine)` | `WorkableMultiblockMachine#recipeLogic` 是 **`public final` 字段、由机器构造器注入**（`AssemblyLineMachine.java:48`）⇒ 自写逻辑必须**无参构造**，机器从 `getRLMachine()` 取（`super` 之前用不了 `this`） |
| 概率产出用 `GTRecipeType#chanceFunction` / `getBoostedChance` | 都删了；期望值口径改 `数量 × recipe.getTotalRuns() × chance / maxChance`（`RecipeOutputProvider.java:95`） |
| `MetaMachineBlockEntity` | 没了，`MetaMachine` 自己 extends `ManagedSyncBlockEntity` ⇒ Jade 里 `accessor.getBlockEntity() as? MetaMachine` 即可 |
| 自写配方循环 | 8.0.0 新增 `RecipeHelper#doPrerolls` / `#doTickPrerolls`（`RecipeHelper.java:449,500`），**必须补**，否则 `IntProviderIngredient` 的区间内容会按未展开的区间去用 |
| `stack.save(new CompoundTag())` 往返 NBT | 1.21 必须带 registryAccess：`ItemStack.CODEC` + `NbtOps` + `level.registryAccess().createSerializationContext(...)` |
| `recipe.id` | 8.0.0 是 public 字段 + `getId()` 并存（Kotlin 解析会歧义）⇒ 统一显式写 `recipe.getId()` |
| `typealias` 放类里 | Kotlin 语法不允许，必须放文件顶层 |
| `.or(Predicates.autoAbilities(true, false, true))` 指望它给能源仓/物品 IO | **`autoAbilities(boolean, boolean, boolean)` 的三个布尔是（维护仓, 消音仓, 并行仓）**，一个 IO 槽都不给（javap 逐条确认 `MAINTENANCE` / `MUFFLER` / `PARALLEL_HATCH`）；能源仓与物品输入输出要靠 `autoAbilities(*definition.recipeTypes)`。另外 8.0.0 的机壳谓词要用 `.and(...)` 串（`.or(...)` 是另一套语义） |
| `Predicates` / 谓词类型 | 包从 `api.pattern` 搬到 `api.multiblock`；`TraceabilityPredicate` → `MultiPredicate`（下限方法名不变：`setMinGlobalLimited` / `setMaxGlobalLimited` / `setPreviewCount`；另有 `setMinCount` / `setMaxCount` / `setExactLimit` / `setPriority`） |
| `FactoryBlockPattern.start().aisle(...)` | 换 `MultiblockPatternBuilder.start().slice(...)`（无参 `start()` = 默认三轴） |
| `IDisplayUIMachine#addDisplayText` | 已删；多方块面板改 MUI，扩展点是 `WorkableElectricMultiblockMachine#getWidgetsForDisplay(PanelSyncManager)`；服务端才有的数据用 `GenericListSyncHandler` + `DynamicWidget`（`DynamicWidget<W extends DynamicWidget<W>>` 的自引用泛型在 Kotlin 里要写 `DynamicWidget<Nothing>()`） |
| 覆写 `createRecipeLogic(...)` 换配方逻辑 | 工厂已删；`WorkableMultiblockMachine(BlockEntityCreationInfo, RecipeLogic)` 两参构造内部 `attachTrait(recipeLogic)`，改成在 super 调用里注入 |
| `MetaMachineBlockEntity` | 整个类已删（`MetaMachine` 自己就是 BlockEntity）；引用它的老 mixin 全部作废 |

---

## 11. 开一次背包就崩：创造页重建时同一件物品被放两次（严重度：高 · 状态：已修；根因在 Registrate 的「默认页」）

**一句话**：`AbstractRegistrate.defaultCreativeModeTab` 字段初值是**原版搜索页**，每件物品（含机器的物品）注册时都会被它自动挂一条「把自己塞进搜索页」的 modifier；按 E 开背包会触发创造页重建，此时搜索页聚合结果里已经有这件物品 → NeoForge 断言失败、整个 mod 加载失败。

**来源**：

```
net.neoforged.fml.ModLoadingException: Loading errors encountered:
	- GTEternalTime-Space -- core -- neoforge (gtetscore) encountered an error while dispatching the net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event
	  java.lang.IllegalArgumentException: Itemstack 1 gtetscore:advanced_terminal already exists in the tab's list
	at net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent.assertNewEntryDoesNotAlreadyExists(BuildCreativeModeTabContentsEvent.java:196)
	at net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent.accept(BuildCreativeModeTabContentsEvent.java:94)
	at com.tterrag.registrate.util.CreativeModeTabModifier.accept(CreativeModeTabModifier.java:42)
	at com.tterrag.registrate.builders.ItemBuilder.lambda$tab$4(ItemBuilder.java:184)
	at com.tterrag.registrate.AbstractRegistrate.lambda$onBuildCreativeModeTabContents$2(AbstractRegistrate.java:309)
	at com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate.lambda$registerEventListeners$3(GTRegistrate.java:153)
```

外层是 `ModLoadingException`，看着像内存不足 / OOM，其实与内存无关（崩溃报告里 `Memory: 1186 MiB / 2520 MiB up to 4056 MiB`，堆还剩一大半）。

**触发**：进世界后**按 E 打开创造模式背包**（`CreativeModeTab.tryRebuildTabContents`）。首次构建不炸、重建才炸 —— 搜索页的内容是从**其它页聚合**来的（`CreativeModeTabs.java:1225-1235` 遍历各页取 `getSearchTabDisplayItems()`），第一次构建时别的页还没建、聚合为空，所以看不出问题。

**成因**：

1. `AbstractRegistrate.<init>` 把 `defaultCreativeModeTab` 初始化成 `CreativeModeTabs.SEARCH`；
2. `AbstractRegistrate.item(...)` 建 builder 时若该字段非 null 就自动 `.tab(字段)`（`AbstractRegistrate.lambda$item$13`）；机器的物品也走这条路（`MachineBuilder.java:228` → `getOwner().item(parent, name, factory)`），所以**光给物品自己调 `removeTab` 救不了机器**；
3. GTET 的归页走的是 GTM 那套 `TAB_LOOKUP` + `RegistrateDisplayItemsGenerator`，与这条「影子归页」指向同一个页 → 同一件物品被 `accept` 两次 → `assertNewEntryDoesNotAlreadyExists` 抛异常。
   （GTM 本体有同样的影子归页，但它的页不是 SEARCH、也不与影子归页重合，所以撞不上；GTET 的页是新登记的才暴露。）

**处理**：

- 总闸：`ETRegistrate.clearDefaultTab()`（等价 `defaultCreativeTab(null)`）——**每建完一个创造页就清一次**，之后注册的物品（含机器）都不会再挂影子归页。
- 兜底：`ItemBuilder.noDefaultTab()`——单件物品把 `SEARCH` 与 GTET 七个页从 builder 的页映射里移除，必须在 `.register()` **之前**调（modifier 是 register 时按这份映射注册的）。
- 验证：`javap -c` 确认 `clearDefaultTab` 发的是 `aconst_null → GTRegistrate.defaultCreativeTab(ResourceKey)`；`gradlew classes` 通过。

---

## 12. 3D 预览里「给某个面画标记」看不见（严重度：中 · 状态：已修；MUI 的 `BlockHighlight` 重载陷阱）

**一句话**：`BlockHighlight(int color, float thickness)` 这个二参重载内部把 `allSides` **写死 true**，于是"标记某一个面"变成"六个面各画一圈细边"——在满贴图的机器模型上几乎不可见。

**来源**：无报错（纯视觉问题，日志里看不出任何异常）。

**触发**：在 MUI 的 3D schema 预览里给某个面画高亮（我们的 3D 输入输出配置页就是这么干的）。

**成因**：`BlockHighlight` 四个构造器里只有 `(int, boolean, float)` 真正给 `allSides` 赋值，`(int, float)` 是 `this(color, true, thickness)`；而 `doRender` 开头 `if (allSides) direction = null;`，`renderFrame` 拿到 `null` 就遍历 `Direction.values()` 六个面全画。

**处理**：改用三参构造器 `BlockHighlight(color, false, thickness)`（只框指定面），厚度 1/32 → 1/8 格、alpha 拉满。
**附带**：本 MUI 快照（3.3.1）里 `renderSolid` 是**死路径** —— 建完顶点既没有 `Tesselator.end()` 也没有 `BufferUploader`，所以 `thickness < 0` 的"整面涂色"画不出东西，别指望这条路。

---

## 13. 机器的物品输出面「怎么设都卡在同一个面」+ 侧面覆盖贴图不更新（严重度：高 · 状态：已修；GTM 自身的同步字段名笔误）

**一句话**：`AutoOutputTrait.setItemOutputDirection` 把脏标记打在了**不存在的字段名** `"outputFacingItems"` 上，而真正的字段叫 `itemOutputDirection` —— 于是物品输出面的改动**永远不会同步给客户端**，客户端一直显示初始值。

**来源**：无报错、无异常（完全静默的行为错误）。

**触发**：任何有 `AutoOutputTrait` 的机器（单方块电机器）设物品输出面 —— 无论用我们的配置页还是用扳手（不潜行点侧面），客户端看到的输出面都不变；侧面那张 `OUTPUT_OVERLAY` 覆盖贴图也不出现。

**成因**（逐条证据）：

- `SyncDataHolder.markClientSyncFieldDirty(String)`（`SyncDataHolder.java:49-52`）只是把**字符串**丢进 `dirtySyncFields` 集合；
- 真正决定"哪些字段发给客户端"的是 `shouldSyncFieldToClient(field)`（`:198-202`）：`dirtySyncFields.contains(field.fieldName)` —— **按 Java 字段名匹配**；
- `AutoOutputTrait.setItemOutputDirection`（`AutoOutputTrait.java:225`）标记的是 `"outputFacingItems"`，而字段真名是 `itemOutputDirection`（`:64`）→ **永远匹配不上**；
- 客户端于是保留 `onMachineLoad`（`:127-130`）设的 `getFrontFacing().getOpposite()`（机器朝南时正好是**北面**）→ 现象就是"怎么点都卡在北面"；而 `MachineModel.java:277-301` 渲染用的也是这个陈旧的客户端字段，**所以覆盖贴图也永远不更新**；
- 流体那一半是对的（`:214` 标记的 `"fluidOutputDirection"` 与字段名一致）⇒ **只有物品输出面这一半坏**。

**处理**：新增 `src\main\java\rain\fox\gtetcore\mixin\gtm\AutoOutputTraitMixin.java`，用 `@Redirect` 把 `setItemOutputDirection` 里的 `markClientSyncFieldDirty` 调用改成传 `"itemOutputDirection"`（注册在 `gtetscore.mixins.json` 的通用段）。一条补丁同时修好"面设不上"和"侧面贴图不更新"。

---

## 14. MUI × JEI：`jei.RecipeSlotAccessor` 注入失败（严重度：**高**（2026-10-04 上调，原判"低"） · 状态：**整合包级阻断**，见文末更新）

**一句话**：GTCEu 捆绑的 MUI 3.3.1 里有个 accessor 要读 JEI `RecipeSlot` 的字段，而那字段从 JEI 19.50 起就被重构成了另一个类 —— 而 **LDLib2 的硬性下限又把 JEI 顶到 ≥19.51**，于是两边必然对撞：**只要 `RecipeSlot` 这个类被加载（任何有 JEI 配方分类的 mod 都会触发），MUI 的 accessor APPLY 就失败、类加载不出来、JEI 渲染整体崩**。

**来源**：

```
[Mixin apply for mod modularui failed] modularui.mixins.json:jei.RecipeSlotAccessor
  -> mezz.jei.library.gui.ingredients.RecipeSlot
org.spongepowered.asm.mixin.gen.throwables.InvalidAccessorException:
  No candidates were found matching allIngredients:Ljava/util/List;
```

**成因**（读 JEI 官方 sources jar 逐版核对）：

- MUI 3.3.1 的 accessor 需要 `RecipeSlot` 的 5 个成员：`role` / `cycler` / `tooltipCallbacks` / **`allIngredients`** / **`displayIngredients`**；
- **JEI 19.25.1.328**（GTM 版本目录钉的那版）里它们全在：`RecipeSlot.java:61`（`allIngredients`）、`:69`（`displayIngredients`）⇒ MUI 是对着 **≤19.44** 这一代写的；
- **JEI 19.51.0.417** 同文件：新增协作者 `:47 private final RecipeSlotIngredients ingredients;`（`:70-74` 构造），`allIngredients` 只剩构造器参数名（`:63`），`displayIngredients` 字段消失；读取改走 `:87-88 getAllIngredients()` / `:93-94 getAllIngredientsList()`；
- **分界点落在 19.44.0.413（有）与 19.50.0.414（无）之间**；
- 逐个下载 **≥19.51.0.417 的全部 27 个版本**（19.51.0.417 … 19.57.0.450）的 sources jar 核对：**带旧字段的版本数 = 0/27** ⇒ "往上对齐版本"这条路不存在；
- "往下退版本"也被堵死：**LDLib2 2.2.41 的 `neoforge.mods.toml` 声明 `jei [19.51.0.417,)`**（硬下限），降 JEI 就等于放弃 LDLib2。

**为什么不做补丁**：

- **自己 mixin 给 JEI `RecipeSlot` 补同名字段**：应用顺序上可行（Mixin 按 priority 升序应用，把自己设成低于 MUI 默认 1000 就能先并入字段），但**补了也不修行为** —— 19.51 的 `RecipeSlot` 只读写自己的 `ingredients`，写进补出来的字段等于写进死变量，JEI 永不读；而且缺的是**两个**字段，MUI 在第一个缺失处就抛错。收益只是日志少一行 FATAL，代价是一条钻进第三方私有字段布局的跨 mod mixin。**判定：不做。**
- **Access Transformer**：语义上做不到 —— AT 只能给**已存在**的成员改访问标志（`Modifier` 只有访问级别），没有新增成员的语法；而这里是 `No candidates were found`（字段不存在），不是权限问题。
- **重新分发打过补丁的 JEI jar**：许可证 + 分发成本远大于收益，不做。

**实际影响（可忽略）**：MUI 的 `RecipeViewerHandler.getCurrent()` 选择顺序是 **EMI → REI → JEI → dummy**，装了 EMI 的包里 MUI 的槽位工厂拿到的是 **EMI** 实现；被削掉的只是 MUI 的 **JEI 槽位粘合层**（配料替换/轮换这类附加行为），GT 自己的 JEI 配方页（用 JEI 原生 API 建槽）不受影响，实测那页仍渲染正常。

**结论**：等 MUI（或 GTM 捆绑的 MUI）自己支持 JEI 19.51+，不是我们能 patch 的层。

---

### 更新（2026-10-04）：严重度上调为「高」，整合包级阻断

**触发**：用户在整合包里装了其它 AE2 附属后，JEI 渲染直接崩，报错链就是本条 —— `MixinApplyError: Mixin [modularui.mixins.json:jei.RecipeSlotAccessor] FAILED during APPLY` → `InvalidAccessorException: No candidates were found matching allIngredients:Ljava/util/List;`。

**为什么在整合包里性质变了**：平时我们这里装了 EMI，MUI 的槽位工厂走 EMI 那条路，所以「只是日志里一行 FATAL、那页仍能渲染」。但 **accessor 的 APPLY 失败发生在 `RecipeSlot` 这个类被加载时** —— 任何有 JEI 配方分类的 mod 都会触发它，于是**整个整合包的 JEI 渲染一起崩**，与那些附属本身无关。

**补齐的两条证据（本次 javap 核实）**：

- MUI 的 `brachy.modularui.core.mixins.jei.RecipeSlotAccessor` 需要 `RecipeSlot` 上 **5 个成员**：`setRole` / `setCycler` / `getTooltipCallbacks` / `setAllIngredients` / `setDisplayIngredients`；
- JEI **19.51** 的 `mezz.jei.library.gui.ingredients.RecipeSlot` 字段只剩 `role` / `ingredients`（新协作者）/ `cycler` / `tooltipCallbacks` / `rendererOverrides` / …，**`allIngredients` 与 `displayIngredients` 已不存在**（搬进 `RecipeSlotIngredients`），而且 `role`/`cycler`/`tooltipCallbacks` 都成了 **`private final`**（MUI 生成的 setter 即使能 APPLY，被调用时也会踩 final 语义）。
- 回退目标版本（GTM 1.21.1 自己钉的）是 **`19.25.1.328`**，那一版字段齐全（见上文成因节）。

**三条出路（代价从低到高）**：

1. **撤回 LDLib2 依赖 ⇒ JEI 回退到 `19.25.1.328`**：当前**零实际代价** —— 我们的代码引用 `mezz.jei` 是 0 处，LDLib2 主代码引用也是 0（冒烟探针已删、S0 产物还在可整块删的边界里）。整合包 JEI 立刻恢复。
2. **留着 LDLib2，改 MUI 的 JEI 桥**（GTM 补丁层）：唯一"正确"的修法，但要动 GTM jarJar 里的第三方库。
3. **留着 LDLib2，给 JEI 补字段**（我们侧 mixin shim）：要解决「我们的 mixin 必须早于 MUI 的 accessor 应用」的顺序问题，且躲不开 `role`/`cycler`/`tooltipCallbacks` 已 final 的坑；属于往第三方类注水。

**当前状态**：等用户拍板（本仓库默认倾向 1）。**只要采用 LDLib2，就必须同时做 2**，否则这个崩会重演。

---

## 15. 自写 `RecipeLogic` 时「只覆写 getter」改不动 GTM 的单进度显示（严重度：中 · 状态：已修；百分比读的是**裸字段**）

**一句话**：GTM 的单进度 UI 里「进度 / 时长」两个数走 `getProgress()` / `getMaxProgress()`（可覆写），但**百分比走 `getProgressPercent()`，它直接 `getfield` 读 `progress` / `duration` 两个受保护字段、完全不经过 getter** —— 只覆写 getter 的话进度条会显示「1234/5678 t (0%)」这种自相矛盾的组合。

**来源**：无报错（纯显示问题）。

**成因**（`javap -p -c` 逐条核对）：

- `RecipeLogic.getProgressPercent()`（`RecipeLogic.java:229-236`）= `duration != 0 ? (double) progress / duration : 0`，字节码里是 `getfield progress` / `getfield duration`；
- 用到它的地方：`GTMultiblockTextUtil#addProgressLine` / `#addProgressLinePercentOnly`（多方块面板那条进度行）、`GTSingleblockMachinePanels`、`CokeOvenMachine` / `PrimitiveBlastFurnaceMachine` / 蒸汽锅炉等自带面板；
- 这些是 **MUI 的 `DoubleSyncValue`**：值在**服务端**求出来再推给客户端 ⇒ 服务端读到 0，客户端就是 0；
- 而 `progress` / `duration` 的注解只有 `@SaveField`（**不是 `@SyncToClient`**，`RL_v.txt` 常量池 `#642=SaveField`）⇒ 客户端自己那份永远是 0，指望「客户端字段会同步过来」也没戏。

**处理**：把镜像写回**字段**而不是只覆写 getter ——

```kotlin
private fun mirrorToBaseFields() {
    val primary = primaryThread()
    lastRecipe = primary?.recipe
    lastUnrolledRecipe = primary?.unrolled
    progress = primary?.progress ?: 0
    duration = primary?.duration ?: 0
    isActive = primary != null
}
```

- 这几个字段在 8.0.0 是 `protected` 且**非 final**，Kotlin 子类**可以直接赋值**（`lastRecipe` / `duration` / `isActive` 没 setter ⇒ 走字段；`progress` 会被解析成 `setProgress(int)`，而它内部就是 `putfield progress`，等价）；
- `getProgressPercent()` / `getProgress()` / `getMaxProgress()` / `getLastRecipe()` 全都直接读字段 ⇒ 写字段一次性覆盖所有读法，不用再逐个覆写 getter；
- 多线程机器上这套单进度字段只能反映一条线程，逐线程的真实进度另走我们自己的显示层。

**结论**：任何自写 `RecipeLogic` 子类，**镜像必须落字段**；「有没有 setter」不是判断依据（`javap` 只列方法，容易误判成"只读"）。

---

## 16. 构建还在跑就启动客户端 → `NoClassDefFoundError`（严重度：中 · 状态：已定性；不是代码问题）

**一句话**：强制重编会重写（乃至短暂删空）`build\classes\java\main` 与 `build\classes\kotlin\main`，此时**正在运行的客户端**一旦加载某个类，就是 `NoClassDefFoundError` / `ClassNotFoundException` —— 看着像"代码丢了"，其实类好端端在磁盘上。

**来源**（原文）：

```
java.lang.NoClassDefFoundError: rain/fox/gtetcore/integration/jade/provider/JadeStoredBar
	at ...ETTimeFlowStorageProvider.appendTooltip(ETTimeFlowStorageProvider.java:66)
	at ...snownee.jade.impl.BlockAccessorClientHandler.gatherComponents(BlockAccessorClientHandler.java:93)
Caused by: java.lang.ClassNotFoundException: rain.fox.gtetcore.integration.jade.provider.JadeStoredBar
	at cpw.mods.securejarhandler/cpw.mods.cl.ModuleClassLoader.loadClass(ModuleClassLoader.java:220)
```

**触发**：客户端已经开着，同时在另一个终端跑 `gradlew compileKotlin compileJava --rerun-tasks --no-build-cache`（或任何写 `build/classes` 的任务）。

**成因**：`--rerun-tasks` 会重新执行任务，Gradle 先把该任务的输出目录清掉再写；Java 与 Kotlin 是两个独立输出目录，谁被清到、什么时候被清，取决于两个任务谁先跑。客户端这时若恰好首次加载某个类（Jade 的 tooltip provider 是**按需**加载的，不是启动期加载，所以崩在游戏中途而不是启动时），就会撞上"文件此刻不存在"。

**处理**：

- 正确顺序：**等构建彻底结束**（`build\classes` 里文件时间戳稳定）→ 再 `runClient`。
- 已经崩了的那次**不用改代码**：关掉客户端重开即可。
- 判断依据：报错类在磁盘上确实存在（`Get-Item build\classes\...\X.class` 有时间戳），且错误只在"构建与游戏并发"的那个时间窗出现。

**历史实例**：`...client.mui.MultiblockPreviewFullscreen`（更早一次）、`...jade.provider.JadeStoredBar`（本次）。

---

## 17. MUI 的 `AbstractParentWidget.remove(IWidget)` 永远失败（严重度：高 · 状态：已绕开；上游 `Widget.equals` 写坏）

**一句话**：MUI 3.3.1-SNAPSHOT 里 `parent.remove(someWidget)` **是个静默空操作** —— 控件既没被摘掉、也没被 dispose、更不报错，于是「换内容」的地方会出现新旧叠加（本项目实机现象：全屏多方块预览切换时两个结构叠在一起渲染）。

**来源**：无报错（纯静默失效）。

**成因**（`javap` 逐条核对）：

1. `AbstractParentWidget.remove(I)` 偏移 0-5 调的是 `java/util/List.remove:(Ljava/lang/Object;)`（`children` 是 `java.util.ArrayList`，见 `<init>` 偏移 4-12）；
2. `ArrayList.remove(Object)` 用 `o.equals(e)` 比较，`o` 就是我们传进去的那个控件；
3. 而 `brachy.modularui.widget.Widget.equals` 偏移 0-14 是
   `if (o == null || o.getClass() != Widget.class) return false;`
   —— **任何 Widget 子类都不等于它自己**，所以 `remove(任何子类控件)` 一律返回 false。

**旁证**：MUI 自己的 `DynamicWidget.updateChild` 也不用 `remove(IWidget)`，而是 `child.get().dispose()` + `MutableSingletonList.remove()`（无参）自己换。

**处理**：改成**索引式**删除 ——

```kotlin
val index = parent.children.indexOfFirst { it === widget }
if (index >= 0) parent.remove(index)
```

- `remove(int)` 是正常 List 语义；父级 `isValid()` 时它还会顺带 dispose 被摘的子树（想自己控制 dispose 时注意这一点）。
- **纯新增不受影响**：`child(...)` 只是 `addChild` 内部的 `contains` 判断失效，不报错。
- 本仓库已知受影响并已修的两处：`MultiblockPreviewFullscreen`（换多方块时摘旧预览 ⇒ 就是「叠加渲染」的成因）、`PreviewControls`（内嵌页摘 SchemaWidget 换成提示文字）。全仓 `\.remove\(` 已复查，其余调用都是集合/字符串语义，与本缺陷无关。

**结论**：**在本项目里，凡是「运行时把某个控件摘掉」的写法一律走索引式 `remove(int)`**；引入 MUI 控件删除逻辑时先按这条检查。

---

## 18. `PagedWidget` 不是布局父级，页容器不给尺寸会被解成 18×18（严重度：中 · 状态：已修；MUI 的隐藏默认值）

**一句话**：MUI 的 `PagedWidget` **不是 `ParentWidget`**、不参与布局；页里的子件是按**页容器自己的 area** 摆的，而没有任何尺寸单位的控件会被解成**主题默认 18×18** —— 于是「18 列 × 18px = 324 宽」的网格从一个 18 宽、`leftRel(0.5f)`（= 按父宽居中）的盒子里画出来，x 直接算成 `0.5×18 − 324×0.5 = −153`，**左右各探出 153px**，并且只被 flow 留了 18px 高、把下面那排翻页条和上面那行状态文字一起盖住。

**来源**：无报错（纯布局错位）。

**成因**（`javap` + MUI 反编译逐条核对）：

- `PagedWidget extends Widget`（不是 `ParentWidget`、不实现 `ILayoutWidget`），只 `getChildren()` 返回 pages、`setPage` 用 `setEnabled(false/true)` 切页；
- `IWidget.getParentArea()` 默认实现就是 `getParent().getArea()`；
- 没设尺寸的控件在 `DimensionSizer.apply` 走 `start==null && end==null && size==null` 分支 → `Widget.getDefaultWidth()`：
  `return this.isValid() ? getWidgetTheme(...).theme().getDefaultWidth() : 18;`
  而 MUI 内置 FALLBACK 主题就是 `WidgetTheme.darkTextNoShadow(18, 18, null)`（`IThemeApi.java:48`）⇒ **18×18**；
- `leftRel(0.5f)` 是「按父宽居中」：`Unit.getAnchor()` 在 `autoAnchor && isRelative() && value<1f` 时返回 `value`；`DimensionSizer.calcPoint` 里 `val = anchor*parentSize; if (anchor != 0) val -= width*anchor;`。

**处理**：给页容器**显式尺寸** —— 网格多宽就给它多宽（`PatternPages().size(gridWidth, gridHeight)`），页内的 Grid 同尺寸并**去掉 `leftRel`**（不再需要居中），翻页排再单独 `leftRel(0.5f)`（此时父宽确定，不构成循环依赖）。

**佐证**：GTM 自己那份样板总成**根本不用 `PagedWidget`**，Grid 是 `flow.col().coverChildren()` 的直接子件；GTM 全仓仅有的两处 `PagedWidget` **都显式给了尺寸**（`ItemMagnetBehavior.java:105-108` 的 `.size(150, 55)`）。

**结论**：在 MUI 里用 `PagedWidget`（以及任何非 `ParentWidget` 的内容宿主）**必须显式给尺寸**，否则它就是一个 18×18 的隐形盒子，内容会以它为参照错位；`leftRel/rightRel` 这类百分比在「父级尺寸不确定」时也会变成漂移来源。

---

## 模板（新增条目时复制）

```markdown
## N. 标题（严重度：高/中/低 · 状态：已修/规避中/忽略/待观察）

**一句话**：

**来源**：

```
（原始报错原文，含 Caused by 那几行）
```

**触发**：

**成因**：

**处理**：
```
