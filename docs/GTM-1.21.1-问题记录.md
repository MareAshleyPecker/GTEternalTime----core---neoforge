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
