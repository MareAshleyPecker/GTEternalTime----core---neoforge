package rain.fox.gtetcore.api.timeflow

import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier
import com.gregtechceu.gtceu.api.registry.GTRegistries
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.utils.GTMath
import com.tterrag.registrate.util.entry.RegistryEntry
import com.tterrag.registrate.util.nullness.NonNullSupplier
import rain.fox.gtetcore.GTETSCore

/**
 * **时间流（TF）作为 GTM 的一等 [RecipeCapability]**。
 *
 * 注册进 `GTRegistries.RECIPE_CAPABILITIES` 之后，GTM 的配方体系就原样负责 TF 的匹配与扣费：
 * 配方 JSON / NBT 里的能力键由 GTM 自己的 codec 读写，扣费走
 * [com.gregtechceu.gtceu.api.capability.recipe.IRecipeHandler#handleRecipeInner]，每 tick 一次
 * （链路见 [ETTimeFlowHandler] 的类注释）。
 *
 * ## ⚠️ 8.0.0 的三处硬变化（本类相对老工程的全部改写，均已读源码核实）
 * 1. **构造器收 [net.minecraft.resources.ResourceLocation]，不再收 String**
 *    （`RecipeCapability.java:58-65`），所以能力名是 `gtetscore:time_flow`（带命名空间）；
 * 2. **显示名的语言键格式变了**：`getName()` 现在是
 *    `Component.translatable(id.toLanguageKey("recipe_capability"))`
 *    （`RecipeCapability.java:125-127`），即 `recipe_capability.<命名空间>.<路径>`
 *    —— 见 `TimeFlowHatchLang.RECIPE_CAPABILITY_NAME`（老工程的 `recipe.capability.<name>.name`
 *    在 8.0.0 已经不会被任何代码拼出来）；
 * 3. **注册路径换了**：8.0.0 不再有 `IGTAddon#registerRecipeCapabilities()` 回调，GTM 自己在
 *    `GTRecipeCapabilities.register(...)`（`GTRecipeCapabilities.java:27-31`）里走
 *    `REGISTRATE.generic(路径, GTRegistries.Keys.RECIPE_CAPABILITY, 构造器).register()`
 *    —— 即**走 Registrate 的通用注册**（`GTRegistries.Keys.RECIPE_CAPABILITY` 的定义在
 *    `GTRegistries.java:78`），所以 [register] 照抄这条路径，而不是老工程那句
 *    `GTRegistries.RECIPE_CAPABILITIES.register(...)`（那条路在 8.0.0 只剩内部在用）。
 *
 * ## 本类实现/覆写了什么（其余全部继承 `RecipeCapability` 的默认实现）
 * - 构造器 `super(id, color, doRenderSlot, sortIndex, serializer)`：唯一的**必需**项。
 * - [copyInner]：TF 内容是**不可变值对象**，直接返回入参，省掉默认实现那次 FriendlyByteBuf 往返
 *   （`RecipeCapability.java:78-83`；EU 也是这么做的，见 `EURecipeCapability.java:27-30`）。
 * - [copyWithModifier]：**必须覆写**。默认实现直接丢弃 modifier（`RecipeCapability.java:88-90`），
 *   而并行数就是通过 `ContentModifier.multiplier(parallels)` + `Content#copy`
 *   （`Content.java:48-54`）作用到 `tickInputs` 上的 —— 不覆写的话「并行 64 倍」不会让 TF 消耗跟着 ×64。
 * - [getMaxParallelByInput]：按「仓里现有的 TF 存量 ÷ 配方每 tick 的 TF 成本」限制并行，
 *   形状对齐 `EURecipeCapability.java:103-130`；机器上**一个 TF 仓都没有时直接返回 `limit`**
 *   （即不在这里判负），把「TF 不足」的报错留给内容匹配阶段，避免这一层提前把并行清零。
 *
 * ## 刻意**没有**覆写的（默认值就是对的，理由写在这里免得后人以为漏了）
 * - `limitMaxParallelByOutput`（`RecipeCapability.java:163-166`）：默认 `Integer.MAX_VALUE` = 不处理。
 *   TF 不是「会被撑爆的库存」，输出空间合并没有意义。
 * - `skipEmptyContentCheck`（`RecipeCapability.java:216-218`）：默认 `false`。这是 8.0.0 **新增**的
 *   钩子，只有 CWU 那种「存量为 0 也照样能提供算力」的速率型能力才返回 `true`
 *   （`CWURecipeCapability.java:30-33`）；TF 是**有量的缓冲**，空了就是不够、就该匹配失败。
 * - `doMatchInRecipe`（:149-151）默认 `true`：TF 必须进配方匹配，这一条不能动。
 * - `shouldBypassDistinct`（:207-209）默认 `true`：TF 仓在语义上等同能源仓（「每台多方块一个全局仓」），
 *   要能绕过 distinct 检查，才能在 ME 样板仓那种 distinct 场景里照样供电。
 * - `getCapabilityHandlers(MetaMachine)`（:226-240）默认返回空表：8.0.0 新增，**只有 GUI 槽位布局**
 *   在用（`MachineCapabilityLayoutBuilder.java:43,84` 只查 item / fluid），TF 不画槽位
 *   （`doRenderSlot = false`）所以用不着。
 * - `isRecipeSearchFilter` / JEI/EMI 相关：TF 做的是**消耗**不是匹配查找，没有配方搜索槽。
 *
 * @author rain fox
 */
class ETTimeFlowCapability private constructor() : RecipeCapability<ETTimeFlowStack>(
    GTETSCore.id(NAME), COLOR, false, SORT_INDEX, ETTimeFlowSerializer,
) {

    override fun copyInner(content: ETTimeFlowStack): ETTimeFlowStack = content

    override fun copyWithModifier(content: ETTimeFlowStack, modifier: ContentModifier): ETTimeFlowStack =
        ETTimeFlowStack(modifier.apply(content.amount))

    override fun getMaxParallelByInput(
        holder: IRecipeCapabilityHolder,
        recipe: GTRecipe,
        limit: Int,
        tick: Boolean,
    ): Int {
        if (!holder.hasCapabilityProxies()) return limit

        val inputs = if (tick) recipe.getTickInputContents(this) else recipe.getInputContents(this)
        if (inputs.isEmpty()) return limit

        // 每份配方要花的 TF：`chance == 0` 的是「不消耗」内容，不计入。
        // ⚠️ 8.0.0 的 Content 是 record，组件访问器**没有 get 前缀**（`Content.java:16`），
        //    Kotlin 侧按属性读写（`content.chance` / `content.content`）。
        var costPerRecipe = 0L
        for (content in inputs) {
            if (content.chance != 0) costPerRecipe += of(content.content).amount
        }
        if (costPerRecipe <= 0L) return limit

        val handlers = holder.getCapabilitiesFlat(IO.IN, this)
        // 机器上还没有 TF 仓：不在这里判负，交给内容匹配阶段报「insufficient in: Time Flow」。
        if (handlers.isEmpty()) return limit

        var buffered = 0L
        for (handler in handlers) {
            for (content in handler.contents) {
                if (content is ETTimeFlowStack) buffered += content.amount
            }
        }

        if (buffered < costPerRecipe) return 0
        return GTMath.saturatedCast(buffered / costPerRecipe).coerceAtMost(limit)
    }

    companion object {

        /**
         * 能力名（`ResourceLocation` 的**路径**部分，命名空间是本模组 id）。
         *
         * ⚠️ **这是配方 JSON / NBT / 网络里的那个字符串键**，改它等于破坏存档与配方兼容。
         * 8.0.0 起能力是**注册表项**，所以配方里要写全名 `gtetscore:time_flow`
         * （老工程 7.5.3 是裸名 `time_flow`，见类注释第 1 条）。
         */
        const val NAME: String = "time_flow"

        /** 能力主题色，青蓝 `#00E5FF`（ARGB）。用于 [getColoredName]，与 EU 的黄色区分。 */
        const val COLOR: Int = 0xFF00E5FF.toInt()

        /**
         * 排序位。GTM 自带的是 item=0 / fluid=1 / eu=2 / cwu=3 / block_state=5
         * （各自的 `super(...)` 构造器），4 空着，TF 作为「第二种能量」紧跟在 EU 与 CWU 之后。
         */
        const val SORT_INDEX: Int = 4

        /** 全局唯一实例。GTM 自带能力也都是单例（`EURecipeCapability.CAP` 等）。 */
        @JvmField
        val CAP: ETTimeFlowCapability = ETTimeFlowCapability()

        /**
         * 注册表条目；`null` = 还没登记。
         *
         * 非空就说明 [register] 已经跑过（[register] 是幂等的，重复调用直接返回）。
         * 存着它只是为了防止重复注册（Registrate 对同名重复注册会抛异常），不在别处使用，
         * 所以是 `private` —— 外部要确认「注册上了没有」请查
         * `GTRegistries.RECIPE_CAPABILITIES`（`ETGTAddon.verify()` 就是这么做的）。
         */
        private var registryEntry: RegistryEntry<RecipeCapability<*>, ETTimeFlowCapability>? = null

        /**
         * 把本能力登记进 GTM 的配方能力注册表。
         *
         * **必须在 mod 构造期调用一次**（本工程的落点是 `CommonProxy.kotlinInit`）：注册表项要在
         * `RegisterEvent` 派发前排队，而配方 JSON 的解析（用 `Registry#byNameCodec`）发生在更晚的
         * 数据包加载期 —— 晚一步就是「配方里的 `gtetscore:time_flow` 解析不出来」。
         *
         * ⚠️ 幂等：重复调用直接返回，不重复注册。
         */
        @JvmStatic
        fun register(registrate: GTRegistrate) {
            if (registryEntry != null) return
            // 照 GTRecipeCapabilities.java:28-30 的写法：走 Registrate 的通用注册，
            // 注册名 = `gtetscore:time_flow`，与构造器里那个 ResourceLocation 逐字一致。
            registryEntry = registrate
                .generic(NAME, GTRegistries.Keys.RECIPE_CAPABILITY, NonNullSupplier { CAP })
                .register()
        }
    }
}
