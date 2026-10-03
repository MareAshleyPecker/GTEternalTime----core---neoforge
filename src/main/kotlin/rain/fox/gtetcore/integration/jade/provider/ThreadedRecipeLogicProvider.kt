package rain.fox.gtetcore.integration.jade.provider

import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.common.machine.multiblock.thread.ThreadedRecipeLogic
import rain.fox.gtetcore.common.machine.multiblock.thread.ThreadedRecipeStatus
import rain.fox.gtetcore.data.lang.ThreadHatchLang
import snownee.jade.api.BlockAccessor
import snownee.jade.api.IBlockComponentProvider
import snownee.jade.api.IServerDataProvider
import snownee.jade.api.ITooltip
import snownee.jade.api.config.IPluginConfig
import snownee.jade.api.ui.BoxStyle
import snownee.jade.api.ui.IElementHelper

/**
 * GTET 自己的 Jade provider：把多线程配方逻辑的整机线程状态摆到提示里。
 *
 * 显示：`线程 N（在用 M）` + 整机「同时处理 N 次配方运行」+ 整机「Σ EU/t」+ 逐**配方组**两行
 * （第一行是整条绿色进度条，条内 `#槽位 进度/时长 t`；第二行是**产物图标 + 名字 + 数量** + 线程条数
 * + 该组 EU/t）。组的定义与合并规则见 [ThreadedRecipeStatus]。
 *
 * 为什么要自己写：GTM 的 `ParallelProvider` / `RecipeOutputProvider` 读的都是单个配方对象
 * （`recipeLogic.getLastRecipe()` / `getLastUnrolledRecipe()`），而线程数是**机器级**的量，
 * 那个提示结构上装不下。
 *
 * 服务端 / 客户端分工是 Jade 的标准写法：[appendServerData] 在服务端跑（此时才读得到真的线程表，
 * 它不是同步字段），把数字与最多 [GROUP_LIMIT] 条组行写进 NBT；[appendTooltip] 在客户端只读 NBT。
 *
 * ⚠️ 本文件属于 `integration.jade` 包，只在 Jade 在场时被 `@WailaPlugin` 扫描加载；
 * 别处一律不许引用本类（Jade 是可选依赖）。
 */
class ThreadedRecipeLogicProvider private constructor() :
    IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    override fun appendServerData(data: CompoundTag, accessor: BlockAccessor) {
        val logic = threadLogic(accessor) ?: return
        val limit = logic.threadLimit
        // 没装线程仓的机器不参与显示（那种机器没有线程概念），连 NBT 都不写
        if (limit <= 1) return

        val ops = accessor.level.registryAccess().createSerializationContext(NbtOps.INSTANCE)
        data.putInt(NBT_LIMIT, limit)
        data.putInt(TAG_RUNNING, logic.runningThreadCount)
        data.putLong(TAG_TOTAL_RUNS, logic.runningTotalRuns)
        data.putLong(TAG_TOTAL_EUT, ThreadedRecipeStatus.totalEutPerTick(logic))

        val page = ThreadedRecipeStatus.groupSnapshots(logic, GROUP_LIMIT)
        data.putInt(TAG_GROUPS, page.totalGroups)

        val list = ListTag()
        for (group in page.groups) {
            val entry = CompoundTag()
            entry.putInt(TAG_SLOT, group.slot)
            entry.putInt(TAG_GROUP_THREADS, group.threadCount)
            entry.putLong(TAG_GROUP_EUT, group.eutPerTick)
            entry.putInt(TAG_PROGRESS, group.progress)
            entry.putInt(TAG_DURATION, group.duration)
            entry.putInt(TAG_HIDDEN_KINDS, group.hiddenKinds)

            val outputs = ListTag()
            for (output in group.outputs) {
                // ⚠️ 1.21 的物品栈必须过 RegistryOps 才能编解码（`ItemStack.CODEC` 要 registryAccess）
                val tag = ItemStack.CODEC.encodeStart(ops, output.stack.copyWithCount(1))
                    .result().orElse(null) as? CompoundTag ?: continue
                tag.putLong(TAG_OUT_MIN, output.min)
                tag.putLong(TAG_OUT_MAX, output.max)
                tag.putBoolean(TAG_OUT_CHANCED, output.chanced)
                outputs.add(tag)
            }
            entry.put(TAG_OUTPUTS, outputs)
            list.add(entry)
        }
        data.put(TAG_THREADS, list)
    }

    override fun appendTooltip(tooltip: ITooltip, accessor: BlockAccessor, config: IPluginConfig) {
        val data = accessor.serverData
        if (!data.contains(NBT_LIMIT)) return

        val limit = data.getInt(NBT_LIMIT)
        val running = data.getInt(TAG_RUNNING)
        tooltip.add(Component.translatable(ThreadHatchLang.LANG_STATUS, limit, running))
        if (running <= 0) return

        tooltip.add(
            Component.translatable(
                ThreadHatchLang.LANG_TOTAL_RUNS,
                FormattingUtil.formatNumbers(data.getLong(TAG_TOTAL_RUNS)),
            )
        )
        tooltip.add(
            Component.translatable(
                ThreadHatchLang.LANG_TOTAL_EUT,
                FormattingUtil.formatNumbers(data.getLong(TAG_TOTAL_EUT)),
            )
        )

        val ops = accessor.level.registryAccess().createSerializationContext(NbtOps.INSTANCE)
        val helper = IElementHelper.get()
        val list = data.getList(TAG_THREADS, Tag.TAG_COMPOUND.toInt())
        for (i in 0 until list.size) {
            val entry = list.getCompound(i)
            val progress = entry.getInt(TAG_PROGRESS)
            val duration = entry.getInt(TAG_DURATION)
            // 组行第一行 = 整条进度条（条内文字沿用 GTM 的白字，绿底绿字看不清），后面不接任何文字
            tooltip.add(
                helper.progress(
                    if (duration <= 0) 0f else (progress.toFloat() / duration).coerceIn(0f, 1f),
                    Component.translatable(
                        ThreadHatchLang.LANG_PROGRESS,
                        entry.getInt(TAG_SLOT),
                        progress,
                        duration,
                    ),
                    helper.progressStyle().color(PROGRESS_BAR_ARGB, PROGRESS_BAR_ARGB).textColor(-1),
                    BoxStyle.GradientBorder.DEFAULT_VIEW_GROUP,
                    true,
                )
            )
            appendOutputs(tooltip, helper, entry, ops)
        }

        val groups = data.getInt(TAG_GROUPS)
        if (groups > list.size) {
            tooltip.add(Component.translatable(ThreadHatchLang.LANG_MORE, groups - list.size, list.size))
        }
    }

    /**
     * 一组的产物画成「图标 + 名字 + 数量」的**一行**（画法照 GTM 的 `RecipeOutputProvider`：
     * `add(helper.smallItem(stack))` 起行、`append(...)` 续同一行）。
     *
     * ⚠️ 只有第一件产物用 `add`，之后图标与文字全部用 `append` —— 否则一件产物一行，
     * 6 组 × 4 种就是 24 行，悬浮框会盖住半个屏幕。
     */
    private fun appendOutputs(
        tooltip: ITooltip,
        helper: IElementHelper,
        entry: CompoundTag,
        ops: com.mojang.serialization.DynamicOps<Tag>,
    ) {
        val outputs = readOutputs(entry, ops)
        val green = ChatFormatting.GREEN

        if (outputs.isEmpty()) {
            tooltip.add(Component.translatable(ThreadHatchLang.LANG_NO_OUTPUT).withStyle(green))
        }
        outputs.forEachIndexed { index, output ->
            val icon = helper.smallItem(output.stack)
            if (index == 0) tooltip.add(icon) else tooltip.append(icon)
            val text = Component.literal(" ")
            if (index > 0) text.append(Component.translatable(ThreadHatchLang.LANG_OUTPUT_SEP))
            text.append(output.name).append(" ").append(ThreadedRecipeStatus.countText(output))
            tooltip.append(text.withStyle(green))
        }
        val hidden = entry.getInt(TAG_HIDDEN_KINDS)
        if (hidden > 0) {
            tooltip.append(Component.translatable(ThreadHatchLang.LANG_OUTPUT_MORE, hidden).withStyle(green))
        }
        tooltip.append(
            Component.translatable(
                ThreadHatchLang.LANG_GROUP_TAIL,
                entry.getInt(TAG_GROUP_THREADS),
                FormattingUtil.formatNumbers(entry.getLong(TAG_GROUP_EUT)),
            ).withStyle(green)
        )
    }

    /** 客户端按 NBT 重建产物条目（名字与图标都由客户端自己解析）。 */
    private fun readOutputs(
        entry: CompoundTag,
        ops: com.mojang.serialization.DynamicOps<Tag>,
    ): List<ThreadedRecipeStatus.OutputSnapshot> {
        val list = entry.getList(TAG_OUTPUTS, Tag.TAG_COMPOUND.toInt())
        if (list.isEmpty()) return emptyList()
        val outputs = ArrayList<ThreadedRecipeStatus.OutputSnapshot>(list.size)
        for (i in 0 until list.size) {
            val output = list.getCompound(i)
            // 物品没了（整合包换过料）就跳过，别画个空图标
            val stack = ItemStack.CODEC.parse(ops, output).result().orElse(null) ?: continue
            if (stack.isEmpty) continue
            outputs.add(
                ThreadedRecipeStatus.OutputSnapshot(
                    stack,
                    output.getLong(TAG_OUT_MIN),
                    output.getLong(TAG_OUT_MAX),
                    output.getBoolean(TAG_OUT_CHANCED),
                )
            )
        }
        return outputs
    }

    override fun getUid(): ResourceLocation = GTETSCore.id(UID_PATH)

    /** 从被看的方块实体上取多线程配方逻辑；不是（或不是这台机器）就返回 `null`。 */
    private fun threadLogic(accessor: BlockAccessor): ThreadedRecipeLogic? {
        // ⚠️ 8.0.0 的 MetaMachine 自己就是 BlockEntity（`MetaMachine extends ManagedSyncBlockEntity`），
        //    老工程那个 `MetaMachineBlockEntity` 包装类已经不在了
        val machine = accessor.blockEntity as? MetaMachine ?: return null
        return (machine as? IRecipeLogicMachine)?.recipeLogic as? ThreadedRecipeLogic
    }

    companion object {

        @JvmField
        val INSTANCE = ThreadedRecipeLogicProvider()

        /** provider 的 uid 路径（与 `GTETSCore.ID` 一起构成 uid `gtetscore:threaded_recipe_logic`）。 */
        const val UID_PATH: String = "threaded_recipe_logic"

        /** 本 provider 写在 Jade `serverData` 根上的线程数上限（服务端算的比客户端现扫部件表可靠）。 */
        const val NBT_LIMIT: String = "gtet_thread_limit"

        /**
         * 绿色进度条的填充色（ARGB）。取 `0xFF4CBB17`：它就是 GTM「机器在跑」那条进度条的绿，
         * 玩家看起来与别的 GT 机器是同一套观感。
         */
        private val PROGRESS_BAR_ARGB: Int = 0xFF4CBB17.toInt()

        /**
         * 明细最多列几条**配方组**。
         *
         * ⚠️ 用户硬性要求 ≤ 6：Jade 提示跟着准星走，行数一多会盖住半个屏幕，而线程条数上限能到 512。
         */
        private const val GROUP_LIMIT: Int = 6

        private const val TAG_RUNNING: String = "gtet_thread_running"
        private const val TAG_TOTAL_RUNS: String = "gtet_thread_total_runs"
        private const val TAG_TOTAL_EUT: String = "gtet_thread_total_eut"
        private const val TAG_GROUPS: String = "gtet_thread_groups"
        private const val TAG_THREADS: String = "gtet_thread_list"
        private const val TAG_SLOT: String = "slot"
        private const val TAG_GROUP_THREADS: String = "threads"
        private const val TAG_GROUP_EUT: String = "eut"
        private const val TAG_PROGRESS: String = "progress"
        private const val TAG_DURATION: String = "duration"
        private const val TAG_HIDDEN_KINDS: String = "hidden_kinds"
        private const val TAG_OUTPUTS: String = "outputs"
        private const val TAG_OUT_MIN: String = "min"
        private const val TAG_OUT_MAX: String = "max"
        private const val TAG_OUT_CHANCED: String = "chanced"
    }
}
