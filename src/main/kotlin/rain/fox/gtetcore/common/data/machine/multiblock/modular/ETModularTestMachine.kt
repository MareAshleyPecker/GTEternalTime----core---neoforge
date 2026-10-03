package rain.fox.gtetcore.common.data.machine.multiblock.modular

import brachy.modularui.api.drawable.Text
import brachy.modularui.api.widget.IWidget
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.widgets.slot.ItemSlot
import brachy.modularui.widgets.slot.ModularSlot
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.data.tag.TagPrefix
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.multiblock.Predicates
import com.gregtechceu.gtceu.api.multiblock.pattern.IBlockPattern
import com.gregtechceu.gtceu.api.multiblock.pattern.MultiblockPatternBuilder
import com.gregtechceu.gtceu.api.multiblock.util.RelativeDirection
import com.gregtechceu.gtceu.common.data.GTBlocks
import com.gregtechceu.gtceu.common.data.GTItems
import com.gregtechceu.gtceu.common.data.GTMaterials
import com.gregtechceu.gtceu.common.mui.GTGuiTextures
import com.gregtechceu.gtceu.data.recipe.CustomTags
import net.minecraft.network.chat.Component
import rain.fox.gtetcore.common.machine.multiblock.modular.ETModularMachine
import rain.fox.gtetcore.common.machine.multiblock.modular.ETModuleTiers
import rain.fox.gtetcore.data.lang.ModuleLang
import java.util.function.Supplier

/**
 * 模块化多方块试验台 —— [ETModularMachine] 的活样本。
 *
 * - **模块物品决定等级**：金锭 = MK1、钛锭 = MK2、中子素锭 = MK3，换模块会换结构（3³ / 5³ / 7³）。
 *   判定不写在这里，而是登记进全局规则表 [ETModuleTiers]（见 `companion object` 的 `init`）——
 *   那里把**三种判定方式各登记了一份**当示例：
 *   ① `item` 具体物品（LV 电动马达）、② `prefix` 材料 + 形态（金 / 钛 / 中子素锭）、
 *   ③ `tag` 标签（GT 的 `#gtceu:circuits/lv|hv|iv` 电路板）；
 * - **结构决定规模**：中间层机壳墙是「模块方块」，每 8 个把配方电压等级上限抬 1 档
 *   （3³ 有 8 个 → +1、5³ 有 16 个 → +2、7³ 有 24 个 → +3）；
 * - **面板右侧有模块槽**（[getWidgetsForDisplay]），不然玩家没地方放模块。
 *
 * ⚠️ tagprefix 那一组是**有意收窄**过的：老实现直接比材料的**任意形态**（粉 / 粒 / 块…），
 * 规则表的三种登记方式里没有「只看材料」这一种，所以现在只有**登记过的形态**算模块。
 * 要让别的形态也算，在 `init` 里继续 `prefix(...)` 登记即可。
 *
 * ⚠️ 8.0.0 删了 `createUIWidget()`（LDLib 那套），机器面板走 MUI：扩展点是
 * `WorkableElectricMultiblockMachine#getWidgetsForDisplay(PanelSyncManager)`。
 *
 * @author rain fox
 */
class ETModularTestMachine(info: BlockEntityCreationInfo) : ETModularMachine(info) {

    /**
     * 结构里检出的模块方块数量。
     *
     * ⚠️ 老工程是「图案里的收集型谓词往 `MultiblockState#getMatchContext()` 写坐标、成型时读出来」，
     * 而 8.0.0 删了 `MultiblockState#getMatchContext()`（本项目尚未移植 `ETStructureData`）。
     * 本机的图案是 [boxPattern] **程序生成**的，模块方块就是中间层那圈机壳墙，
     * 数量 = 该层周长 = `4 × 边长 − 4`（3³→8、5³→16、7³→24，与老实现数出来的完全相同），
     * 所以这里直接按档位算，不引入新的结构数据通道。
     */
    val moduleBlocks: Int get() = moduleBlocksOfTier(moduleTier)

    /** 基准档位 + 模块方块数带来的加成（结构越大，允许的配方电压越高）。 */
    override fun maxRecipeTier(): Int {
        val base = when (moduleTier) {
            1 -> GTValues.LV
            2 -> GTValues.HV
            3 -> GTValues.IV
            else -> return -1
        }
        return (base + moduleBlocks / MODULE_BLOCKS_PER_TIER).coerceAtMost(GTValues.MAX)
    }

    /** 每档一套结构：`definition` 里的具名 substructure 是图案仓库（见 `ETModularMachine` 类注释）。 */
    override fun patternOfTier(tier: Int): IBlockPattern = boxPattern(sizeOfTier(tier), definition)

    /**
     * 标准多方块面板 + **模块槽** + 三行状态。
     *
     * ⚠️ 槽位走 `PanelSyncManager#getOrCreateSlot`（MUI 自己的具名同步槽），与 GTM 的
     * `GTMuiWidgets#createBatterySlot` 同一套写法：`ItemSlotSyncHandler` + `ModularSlot`，
     * 前者登记进面板、后者指向 trait 的存储。
     */
    override fun getWidgetsForDisplay(syncManager: PanelSyncManager): List<IWidget> {
        val widgets = super.getWidgetsForDisplay(syncManager)

        // moduleTier 标了 @SyncToClient，客户端手上就是最新的，直接读，不必再挂同步值
        widgets.add(
            Text.dynamic(Supplier { Component.translatable(ModuleLang.TEST_TIER, moduleTier) }).asWidget()
        )
        widgets.add(
            Text.dynamic(Supplier { Component.translatable(ModuleLang.TEST_MODULES, moduleBlocks) }).asWidget()
        )
        widgets.add(
            Text.dynamic(Supplier {
                val cap = maxRecipeTier()
                if (cap < 0) Component.empty()
                else Component.translatable(ModuleLang.TEST_CAP, ETModularMachine.tierName(cap))
            }).asWidget()
        )

        val slotHandler = syncManager.getOrCreateSlot(
            KEY_MODULE_SLOT,
            Supplier { ModularSlot(moduleSlot.storage, 0) },
        )
        widgets.add(
            ItemSlot()
                .syncHandler(slotHandler)
                .size(SLOT_SIZE)
                .background(GTGuiTextures.SLOT)
        )
        return widgets
    }

    companion object {

        /** 机器 id（注册与语言键共用）。 */
        const val ID: String = "modular_test_machine"

        /** 每多少个「模块方块」把配方电压等级上限抬一档。 */
        private const val MODULE_BLOCKS_PER_TIER: Int = 8

        /** 模块槽控件的边长（像素，与 MUI 标准槽一致）。 */
        private const val SLOT_SIZE: Int = 18

        /** 模块槽的同步处理器名（同一面板里唯一即可）。 */
        private const val KEY_MODULE_SLOT: String = "gtetModuleSlot"

        /**
         * 模块 → 等级登记：**三种判定方式各来一份**，等于 [ETModuleTiers] 的活示例。
         *
         * 1. **物品** —— 具体物品直接当模块（参数用 `Supplier`，理由见下）；
         * 2. **tagprefix** —— 「材料 + 形态」；反查出来的是两个维度，形态必须一起登记；
         * 3. **标签** —— 交给整合包按标签喂（MC 标签自带层级，`#gtceu:circuits` 这种父标签也会一并命中）。
         *
         * ⚠️ 登记放在 `companion object` 的 `init` 里，Kotlin 会把它编进**外层类的 `<clinit>`**，
         * 而本类首次被加载就是机器注册期（[ETModularTestMultiblocks.register] 第一行的 `initLang`）——
         * 那时 `GTItems` 还没进注册表（GTM 是 `GTMachines.init()` 在 `GTItems.init()` 之前），
         * 直接写 `GTItems.X.asItem()` 会抛 `Registry entry not present`。
         * 所以 ① 用 `Supplier` 包一层，把取物品推迟到机器运行时；
         * ② ③ 直接登记：材料 / 标签常量那时都已就绪（老工程同款，未变）。
         */
        init {
            ETModuleTiers
                // ① 物品：一个具体物品直接当模块（Supplier 是必须的：登记期读不到物品注册表）
                .item(1, Supplier { GTItems.ELECTRIC_MOTOR_LV.asItem() })
                // ② tagprefix：材料 + 形态
                .prefix(1, TagPrefix.ingot, GTMaterials.Gold)
                .prefix(2, TagPrefix.ingot, GTMaterials.Titanium)
                .prefix(3, TagPrefix.ingot, GTMaterials.Neutronium)
                // ③ 标签：GT 的电路标签（LV / HV / IV 电路板）
                .tag(1, CustomTags.LV_CIRCUITS)
                .tag(2, CustomTags.HV_CIRCUITS)
                .tag(3, CustomTags.IV_CIRCUITS)
        }

        private fun sizeOfTier(tier: Int): Int = when (tier) {
            3 -> 7
            2 -> 5
            else -> 3
        }

        /** 该档中间层机壳墙（模块方块）的格数 = 该层周长。 */
        private fun moduleBlocksOfTier(tier: Int): Int {
            val size = sizeOfTier(tier)
            return 4 * size - 4
        }

        /**
         * 程序生成一套 `size³` 的空心机壳盒（比手写三层 `slice` 好维护，改尺寸只改一个数）。
         *
         * 字符表：`X` 机壳（+ 自动能力仓）、`M` 中间层的机壳墙（模块方块）、
         * `S` 控制器（中间层、最后一行正中）、空格 = 内部空气。
         *
         * ⚠️ 8.0.0 的 `aisle` 改叫 `slice`，且三轴方向要显式给：这里照 `ETMasterTower` 的定论
         * `start(UP, FRONT, RIGHT)` —— **行是从后往前排的**，所以 `S` 写在**最后一行**才落在
         * 最靠前那一行，控制器正面朝外。写成默认的 `start()`（sliceDir = BACK）会把结构排到控制器
         * **前面**、控制器正面朝里（`ETTestMultiblocks` 那个已知问题的同款坑）。
         */
        @JvmStatic
        fun boxPattern(size: Int, definition: MultiblockMachineDefinition): IBlockPattern {
            val builder = MultiblockPatternBuilder.start(
                RelativeDirection.UP,
                RelativeDirection.FRONT,
                RelativeDirection.RIGHT,
            )
            val mid = size / 2
            val casing = GTBlocks.CASING_STEEL_SOLID

            for (y in 0 until size) {
                val rows = Array(size) { z ->
                    var row = buildString {
                        for (x in 0 until size) {
                            val wall = x == 0 || x == size - 1 || z == 0 || z == size - 1 || y == 0 || y == size - 1
                            append(
                                when {
                                    !wall -> ' '
                                    y == mid -> 'M'
                                    else -> 'X'
                                },
                            )
                        }
                    }
                    if (y == mid && z == size - 1) {
                        row = row.substring(0, mid) + 'S' + row.substring(mid + 1)
                    }
                    row
                }
                builder.slice(*rows)
            }

            return builder
                .where('S', Predicates.controller(definition))
                .where(
                    'X',
                    Predicates.blocks(casing.get())
                        .setMinGlobalLimited(1)
                        // ⚠️ 8.0.0 用 `.and(...)` 而不是老工程的 `.or(...)`：要的是「机壳 ≥ 1 **且**
                        //    能源仓 / 输入输出仓 / 维护仓按配方类型自动配」（`AndPredicate#testGlobalMin`
                        //    逐个查下限；GTM 自己的多方块也是这么写的）
                        .and(Predicates.autoAbilities(*definition.recipeTypes)),
                )
                .where('M', Predicates.blocks(casing.get()))
                .where(' ', Predicates.air())
                .build()
        }
    }
}
