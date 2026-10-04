package rain.fox.gtetcore.client.mui

import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.multiblock.pattern.BlockPattern
import com.gregtechceu.gtceu.api.multiblock.pattern.ExpandablePattern
import com.gregtechceu.gtceu.api.multiblock.pattern.IBlockPattern
import com.gregtechceu.gtceu.api.multiblock.util.AbstractStructureHelper
import com.gregtechceu.gtceu.api.multiblock.util.BlockInfo
import com.gregtechceu.gtceu.integration.recipeviewer.widgets.MultiblockPreviewWidget
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.BlockState

/**
 * 「同一台机器的多档 substructure」预览支持。
 *
 * 8.0.0 的 `MultiblockMachineDefinition` 没有 `shapes`（javap 无此成员）：一台机器有几档结构现在体现为
 * **具名 substructure 表** `Map<String, Supplier<IBlockPattern>>`（`getStructurePatterns()`），
 * 但 GTM 的预览只认 `main` —— `MultiblockSchemaInfo.refreshSchema` 里是
 * `getStructurePatterns().get("main")`（javap 常量池可见字面量 `"main"`），
 * `MultiblockPreviewWidget` 构造器里也是同一句，所以模块化试验台只看得到 `main`（3³）。
 *
 * JEI/EMI 的分页与本表无关：`MultiblockInfoJeiCategory.registerRecipes` 每台机器只注册 **1 个 recipe**
 * （`MultiblockInfoJeiCategory.java:47-53`），所以内嵌页永远是「第 1 页，共 1 页」，做不出档位分页。
 * 档位切换因此放在我们自己的全屏页里（[MultiblockPreviewFullscreen]）。
 *
 * 换档走 GTM **自己那套刷新机制**：原地改 `mapSchema` + `renderer.notifyRecompile()`，
 * 与玩家拖 GTM 的 slice 重复数滑条时 `refreshSchema()` + `refreshViewWidget()` 是同一条路
 * （`MultiblockSchemaInfo.refreshSchema` 最后也是 `mapSchema.setBlocks(...)`），
 * 所以不重建控件、不新建渲染器、不产生需要 dispose 的新对象。
 *
 * @author rain fox
 */
object PreviewSubstructure {

    /** 该机器注册的全部 substructure 名，`main` 排第一（GTM 的默认结构，也是预览默认渲染的那一套）。 */
    @JvmStatic
    fun names(definition: MultiblockMachineDefinition): List<String> =
        try {
            definition.structurePatterns.keys.sortedWith(
                compareBy({ it != MultiblockControllerMachine.DEFAULT_STRUCTURE }, { it })
            )
        } catch (t: Throwable) {
            emptyList()
        }

    /** 把 `name` 那一档结构灌进 `preview` 的 schema；成功返回 `true`。 */
    @JvmStatic
    fun apply(preview: MultiblockPreviewWidget, definition: MultiblockMachineDefinition, name: String): Boolean {
        val info = preview.multiblockSchemaInfo ?: return false
        val mapSchema = info.mapSchema ?: return false
        val pattern = patternOf(definition, name) ?: return false
        val helper = helperFor(pattern) ?: return false

        // 与 `MultiblockPreviewWidget.<init>` 同款算法：该控件对这三个字段只有 setter，读不回来；
        // 全屏页从不翻转，isFlipped 恒为 false
        val rotation = definition.rotationState
        val upFacing = if (rotation == RotationState.Y_AXIS) Direction.NORTH else Direction.UP

        val resultStructure = HashMap<BlockPos, BlockInfo>()
        helper.populate(
            resultStructure, pattern, info.userGlobalBlockPreferences,
            rotation.defaultDirection, upFacing, false,
        )

        // 后半段照抄 `MultiblockSchemaInfo.refreshSchema`：mapSchema / blockCounts / structureBlocks 三处
        val schemaMap = Long2ReferenceOpenHashMap<BlockState>()
        info.blockCounts.clear()
        for ((pos, blockInfo) in resultStructure) {
            val state = blockInfo.blockState
            schemaMap[pos.asLong()] = state
            info.blockCounts.put(state.block, info.blockCounts.getInt(state.block) + 1)
        }
        mapSchema.setBlocks(schemaMap)
        info.structureBlocks.clear()
        info.structureBlocks.putAll(resultStructure)
        info.renderer?.notifyRecompile()
        PreviewCameraFit.refit(info.multiSchema, mapSchema)
        return true
    }

    private fun patternOf(definition: MultiblockMachineDefinition, name: String): IBlockPattern? =
        try {
            definition.structurePatterns[name]?.get()
        } catch (t: Throwable) {
            null
        }

    /**
     * 与 `MultiblockSchemaInfo.refreshSchema` 里建 helper 的写法一致。
     *
     * ⚠️ helper 必须按**当前这一档**的 slice 重复数新建，不能复用 `main` 那一个：`BlockPatternHelper`
     * 用自己的 `sliceRepeats` 把图案摊平（`flattenBlockPattern` 里 `totalSlices = sliceRepeats.values().sum()`，
     * 再按 `sliceRepeats.getOrDefault(sliceIndex, 1)` 逐个写），档位不同 slice 数就不同 ⇒ 复用会写越界。
     */
    private fun helperFor(pattern: IBlockPattern): AbstractStructureHelper? = when (pattern) {
        is BlockPattern -> {
            val repeats = Int2IntArrayMap()
            for (i in pattern.slices.indices) repeats[i] = pattern.slices[i].minRepeats
            AbstractStructureHelper.blockPattern(repeats)
        }
        is ExpandablePattern -> {
            val constraints = pattern.boundsConstraints ?: return null
            val dimensions = IntArrayList()
            for (pair in constraints.apply()) dimensions.add(pair.leftInt())
            AbstractStructureHelper.expandable(dimensions)
        }
        else -> null
    }
}
