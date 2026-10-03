package rain.fox.gtetcore.common.machine.multiblock.modular

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
import com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableItemStackHandler
import com.gregtechceu.gtceu.api.machine.trait.recipe.RecipeLogic
import com.gregtechceu.gtceu.api.multiblock.pattern.IBlockPattern
import com.gregtechceu.gtceu.api.recipe.ActionResult
import com.gregtechceu.gtceu.api.recipe.GTRecipe
import com.gregtechceu.gtceu.api.recipe.RecipeHelper
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.data.lang.ModuleLang

/**
 * 「模块物品决定等级」的模块化多方块基类（写法照 GTO 的 PCB 工厂）。
 *
 * 机制：模块槽里的物品 → [moduleTier] → ① 换结构 ② 卡配方电压等级（[maxRecipeTier]）。
 * 子类只需要回答两件事：**什么物品算哪个等级**（登记进 [ETModuleTiers]，或覆写 [tierOfModule] 自己算）、
 * **每个等级长什么样**（[patternOfTier]）。
 *
 * ⚠️ 等级 0（没模块 / 模块不合法）时本机不成型 —— 「等级不合法」自然表现为「结构不成型」。
 *
 * ## 8.0.0 上「按等级换结构」为什么改成这样
 * 老工程（7.5.3）覆写的是 `getPattern()`，运行时返回第 N 套 `BlockPattern`。8.0.0 **没有这个方法**：
 * 图案挂在 definition 上、按 substructure 名字索引
 * （`MultiblockMachineDefinition#getStructurePatterns()` 是 `Map<String, Supplier<IBlockPattern>>`），
 * 检测时走 `MultiblockControllerMachine#getSubstructurePattern(name)`，
 * 而成型判定/部件挂载全挂在「名字为 `main` 的那一份 `PatternState`」上
 * （`formStructure` 只在 `name.equals("main")` 时置 `isFormed`，`MultiblockControllerMachine.java`）。
 * 那张静态表又是**按 definition 全局**的（`protected static Table<definition, name, IBlockPattern>`），
 * 同一台机器的不同实例没法各存一套图案。
 *
 * 所以本基类的做法是：**每档图案注册成一个具名 substructure，运行时改写 `main` 的取法** ——
 * 覆写 [getSubstructurePattern]，把 `main` 映射到当前档位那份图案。这样：
 * - `isFormed` / 部件挂载 / 面板 / 渲染全部走 GTM 原本那条 `main` 通路，不用碰内部字段；
 * - 非当前档位的具名 substructure 只当「图案仓库」，不再有 `PatternState`，因此不会被单独成型。
 *
 * @author rain fox
 */
abstract class ETModularMachine(info: BlockEntityCreationInfo) :
    WorkableElectricMultiblockMachine(info, ETModularRecipeLogic()) {

    /** 当前等级（0 = 无模块 / 不合法）。**先于 [moduleSlot] 初始化**：槽位挂载时可能回调 [onModuleChanged]。 */
    @field:SaveField
    @field:SyncToClient
    var moduleTier: Int = 0
        private set

    /** 模块槽：只收 1 个、不参与配方 IO；内容一变就重算等级并重检结构。 */
    @field:SaveField
    val moduleSlot: NotifiableItemStackHandler = attachTrait(
        object : NotifiableItemStackHandler(1, IO.NONE, IO.NONE) {
            override fun onContentsChanged() {
                super.onContentsChanged()
                onModuleChanged()
            }
        }
    )

    init {
        // 具名 substructure 只是「图案仓库」：留下的 `PatternState` 会被
        // `checkAndFormStructure()` 挨个检测并独立成型（它遍历整张 patternStates 表），
        // 于是同一台机器会同时立起 3³ 与 7³ 两套结构。只留 main。
        for (name in patternStates.keys.toList()) {
            if (name != MAIN_SUBSTRUCTURE) patternStates.remove(name)
        }
    }

    /**
     * 模块物品 → 等级；返回 0 表示这个物品不是合法模块。
     *
     * 默认走全局规则表 [ETModuleTiers]（支持「物品 / 标签 / tagprefix」三种登记方式，
     * 查询顺序：**物品 → tagprefix → 标签**）。想完全自定义算法的子类照旧 `override` 本方法。
     */
    protected open fun tierOfModule(stack: ItemStack): Int = ETModuleTiers.tierOf(stack)

    /** 等级 → 结构图案；子类按档位给图案（通常就写 `MultiblockPatternBuilder`）。 */
    protected abstract fun patternOfTier(tier: Int): IBlockPattern

    /**
     * [patternOfTier] 的公开入口：高级终端的「模块搭建」按档位取第 N 套结构时要能调到它。
     * 非法档位的表现由子类决定（可能抛异常），调用方自己兜。
     */
    fun patternForTier(tier: Int): IBlockPattern = patternOfTier(tier)

    /** 该等级允许的配方电压等级上限（`GTValues` 里的档位）；返回 -1 表示不限。 */
    open fun maxRecipeTier(): Int = moduleTier - 1

    /**
     * `main` 这一份 substructure 的图案 = **当前档位**那份（见类注释）。
     *
     * 等级 0（无模块 / 不合法）返回 `null`：`checkStructurePattern` 见到图案为空会直接早退，
     * 于是既不会成型、也不会报结构错误 —— 正是「等级不合法 = 结构不成型」。
     */
    override fun getSubstructurePattern(name: String): IBlockPattern? {
        if (name != MAIN_SUBSTRUCTURE) return super.getSubstructurePattern(name)
        val tier = moduleTier
        if (tier <= 0) return null
        return super.getSubstructurePattern(substructureName(tier))
    }

    /**
     * 模块槽内容变了：重算等级 → 换结构（拆旧立新）→ 让配方重新判定。
     *
     * ⚠️ 用 `invalidateStructure()` 而不是只请求复检：8.0.0 的
     * `checkAndFormStructure()` 对「已成型且无错」的 substructure **直接跳过**，
     * 只抬 `shouldUpdate` 闸门的话，换模块后旧结构会一直立着。
     *
     * ⚠️ 机器还没进世界（构造期槽位回调）时直接返回：那时没有结构可拆，也没有 `Level` 可用。
     */
    fun onModuleChanged() {
        if (level == null) return
        val stack = moduleSlot.getStackInSlot(0)
        val newTier = if (stack.isEmpty) 0 else tierOfModule(stack)
        if (newTier == moduleTier) return
        moduleTier = newTier
        // `@field:SyncToClient` 只是「允许同步」，值改完必须显式报脏，否则客户端一直看旧值
        syncDataHolder.markClientSyncFieldDirty("moduleTier")

        invalidateStructure()
        // ⚠️ 手动重检必须自己抬这道闸门：`checkStructurePattern` 只在 `shouldUpdate()` 为真时才真检测
        //    （`TerminalBehavior.kt:16-22` 记的同一件事）
        defaultPatternState?.setShouldUpdate(true)
        checkAndFormStructure()
        recipeLogic.markLastRecipeDirty()
        recipeLogic.updateTickSubscription()
    }

    companion object {

        /** 成型判定与部件挂载都认这个名字（`MultiblockControllerMachine#formStructure`）。 */
        const val MAIN_SUBSTRUCTURE: String = "main"

        /** 第 2 档起的 substructure 名前缀。 */
        private const val TIER_SUBSTRUCTURE_PREFIX: String = "tier_"

        /**
         * 第 [tier] 档的 substructure 名。
         *
         * ⚠️ 第 1 档必须叫 [MAIN_SUBSTRUCTURE] —— 只有 `main` 那一份 `PatternState` 会置 `isFormed`。
         */
        @JvmStatic
        fun substructureName(tier: Int): String =
            if (tier <= 1) MAIN_SUBSTRUCTURE else TIER_SUBSTRUCTURE_PREFIX + tier

        /** 档位号 → 档位名（越界夹取；同步值还没下来、或档位为 -1 时会落到第一档名）。 */
        @JvmStatic
        fun tierName(tier: Int): String = GTValues.VN[tier.coerceIn(0, GTValues.VN.size - 1)]
    }
}

/**
 * 模块化多方块的配方逻辑：**等级不够的配方判为不可用**。
 *
 * 拦在 [matchRecipe]（内容匹配）这一层而不是 `doModifyRecipe`，原因有两条：
 * 1. ⚠️ GTM 的 `WorkableMultiblockMachine#doModifyRecipe` 是多方块里唯一可覆写的入口，但它在
 *    「找到配方之后」才跑，拦不下「压根不该被选中」的配方；
 * 2. 在这里返回 [ActionResult.fail] 会带上原因文本，GTM 会把它写进 `failureReasons` ——
 *    **Jade 与机器面板都能看到「为什么这条配方不跑」**，比静默返回 `null` 好得多。
 *
 * ⚠️ 8.0.0 的 `RecipeLogic` 只有无参构造（`RecipeLogic.java`），机器是构造时由基类
 * `WorkableMultiblockMachine(info, recipeLogic)` 绑上去的，本类通过 `getRLMachine()` 取回来。
 */
class ETModularRecipeLogic : RecipeLogic() {

    /** 绑定的那台模块机；基类构造期还没绑上时返回 `null`（那时也不会有配方匹配）。 */
    private val modular: ETModularMachine? get() = getRLMachine() as? ETModularMachine

    override fun matchRecipe(recipe: GTRecipe): ActionResult {
        val machine = modular ?: return super.matchRecipe(recipe)
        val cap = machine.maxRecipeTier()
        if (cap >= 0 && RecipeHelper.getRecipeEUtTier(recipe) > cap) {
            val tierName = GTValues.VN[cap.coerceIn(0, GTValues.VN.size - 1)]
            return ActionResult.fail(Component.translatable(ModuleLang.TIER_TOO_LOW, tierName), null, null)
        }
        return super.matchRecipe(recipe)
    }
}
