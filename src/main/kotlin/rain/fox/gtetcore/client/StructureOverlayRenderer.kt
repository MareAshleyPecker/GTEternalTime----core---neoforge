package rain.fox.gtetcore.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.debug.DebugRenderer
import net.minecraft.core.BlockPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.common.NeoForge
import org.slf4j.Logger
import rain.fox.gtetcore.common.item.tool.StructureDetectBehavior
import rain.fox.gtetcore.common.item.tool.StructureWriteBehavior
import rain.fox.gtetcore.config.GtetConfig

/**
 * 客户端渲染器：手持结构工具时绘制选区半透明立方体，手持结构检测工具时绘制蓝色单方块线框。
 *
 * 老工程（1.20.1 / Forge）→ 新工程（1.21.1 / NeoForge）的改动只有事件源：
 * `RenderLevelStageEvent` 从 `net.minecraftforge.client.event` 挪到 `net.neoforged.neoforge.client.event`
 * （阶段常量 `AFTER_TRANSLUCENT_BLOCKS`、`getPoseStack()`、`getCamera()` 都还在），
 * 事件总线从 `MinecraftForge.EVENT_BUS` 换成 `NeoForge.EVENT_BUS`（登记在 [rain.fox.gtetcore.init.ClientProxy]）。
 *
 * 下面是老实现踩过的两个坑，原样保留：
 *
 * 1. 所有顶点都交给原版 **RenderType 管线**绘制，不直接用 `Tesselator` + `RenderSystem`：
 *    手写顶点时必须自己乘 pose 矩阵，而 [LevelRenderer.renderLineBox] 这类原版工具内部已经乘过了，
 *    两者混用会让一部分几何被平移一整个相机坐标（飘出视野）。
 * 2. 线框走 [RenderType.lines]（`POSITION_COLOR_NORMAL` + `rendertype_lines` 着色器，
 *    由法线把线段展开成屏幕空间四边形）。旧的 `GameRenderer.getPositionColorShader()` 配 `Mode.LINES`
 *    在现代 GL 下只能是 1px 发丝线，`RenderSystem.lineWidth` 对它完全无效。该 RenderType 还带
 *    `VIEW_OFFSET_Z_LAYERING`，顺带解决线框与方块面重合导致的闪烁。
 *
 * @author rain fox
 */
@OnlyIn(Dist.CLIENT)
object StructureOverlayRenderer {

    private val LOGGER: Logger = LogUtils.getLogger()

    // ⚠️ 临时诊断状态（查「线框不渲染」，查清后整段删）
    private var diagEventSeen: Boolean = false
    private var diagCooldown: Int = 0

    // ── 覆盖层颜色 ──
    // 配在 config/gtetscore-common.toml 的 [overlay] 段，一行一个颜色：
    //   writeColor  = "R;G;B;线透明度;填充透明度"  ← 选区导出（线框 + 半透明填充）
    //   detectColor = "R;G;B;透明度"               ← 结构检测错误位置（线框）
    // 渲染每帧都要取一次颜色，所以按「上次解析过的原始串」缓存，配置改了才重新解析。

    /** 默认：选区绿色线框 + 淡绿填充。 */
    private val DEFAULT_WRITE_COLOR = OverlayColor(0.2F, 0.9F, 0.2F, 1.0F, 0.15F)

    /** 默认：错误位置蓝色线框。 */
    private val DEFAULT_DETECT_COLOR = OverlayColor(0.2F, 0.4F, 1.0F, 1.0F, 1.0F)

    @Volatile
    private var writeColorRawCache: String? = null

    @Volatile
    private var writeColorCache: OverlayColor = DEFAULT_WRITE_COLOR

    @Volatile
    private var detectColorRawCache: String? = null

    @Volatile
    private var detectColorCache: OverlayColor = DEFAULT_DETECT_COLOR

    /** 登记到 GAME 总线；由 [rain.fox.gtetcore.init.ClientProxy] 在客户端设置阶段调一次。 */
    @JvmStatic
    fun register() {
        // ⚠️ GAME 总线的监听一律用 addListener 手工挂（不用 @EventBusSubscriber，见 CommonProxy 的类注释）
        NeoForge.EVENT_BUS.addListener(StructureOverlayRenderer::onRender)
    }

    fun onRender(event: RenderLevelStageEvent) {
        // ⚠️ 临时诊断（查「线框不渲染」，查清后整段删）：先确认事件到底有没有进来
        if (!diagEventSeen) {
            diagEventSeen = true
            LOGGER.info("[GTET-OVERLAY] 收到 RenderLevelStageEvent（首次），stage={}", event.stage)
        }
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return

        val player = Minecraft.getInstance().player ?: return

        val mainStack = player.mainHandItem
        val offStack = player.offhandItem

        val writeStack = if (StructureWriteBehavior.isItemStructureWriter(mainStack)) mainStack
        else if (StructureWriteBehavior.isItemStructureWriter(offStack)) offStack else null
        val detectStack = if (StructureDetectBehavior.isItem(mainStack)) mainStack
        else if (StructureDetectBehavior.isItem(offStack)) offStack else null

        // ⚠️ 临时诊断：每秒一次，把手持物与两份组件在**客户端**读到的东西打出来
        if (--diagCooldown <= 0) {
            diagCooldown = 20
            LOGGER.info(
                "[GTET-OVERLAY] main={} writer={} writePos={} | off={} detect={} detectPos={} time={}",
                mainStack.item, StructureWriteBehavior.isItemStructureWriter(mainStack),
                writeStack?.let { StructureWriteBehavior.getPos(it)?.contentToString() },
                offStack.item, StructureDetectBehavior.isItem(offStack),
                detectStack?.let { StructureDetectBehavior.getPos(it)?.size },
                detectStack?.let { StructureDetectBehavior.getTime(it) },
            )
        }

        if (writeStack == null && detectStack == null) return

        val buffers = Minecraft.getInstance().renderBuffers().bufferSource()
        val poseStack = event.poseStack
        val camPos: Vec3 = event.camera.position

        // 原版给的 pose stack 是相机空间且未做相机平移（原版画方块轮廓时也是手动减相机坐标），
        // 所以这里平移一次，之后一律用世界坐标。
        poseStack.pushPose()
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z)
        try {
            if (writeStack != null) {
                renderWriteBox(poseStack, buffers, writeStack)
            }
            if (detectStack != null) {
                renderDetectBoxes(poseStack, buffers, detectStack)
            }
        } finally {
            poseStack.popPose()
            // 半透明面先画、线框后画，避免线被面糊上一层
            buffers.endBatch(RenderType.debugFilledBox())
            buffers.endBatch(RenderType.lines())
        }
    }

    /** 结构工具：选区立方体（半透明面 + 线框，颜色走配置）。 */
    private fun renderWriteBox(poseStack: PoseStack, buffers: MultiBufferSource, stack: ItemStack) {
        val pos = StructureWriteBehavior.getPos(stack) ?: return

        // pos[0] / pos[1] 是含端点的最小 / 最大方块，所以 +1 才是覆盖整块的包围盒
        val box = blockRangeBox(pos[0], pos[1])

        val color = writeColor()
        DebugRenderer.renderFilledBox(
            poseStack, buffers, box,
            color.r, color.g, color.b, color.fillAlpha
        )
        LevelRenderer.renderLineBox(
            poseStack, buffers.getBuffer(RenderType.lines()), box,
            color.r, color.g, color.b, color.lineAlpha
        )
    }

    /** 结构检测工具：逐个错误位置的线框（颜色走配置；超过配置的停留时间就不再画）。 */
    @Suppress("SENSELESS_COMPARISON")
    private fun renderDetectBoxes(poseStack: PoseStack, buffers: MultiBufferSource, stack: ItemStack) {
        if (detectOverlayExpired(stack)) return

        val errors = StructureDetectBehavior.getPos(stack)
        if (errors.isNullOrEmpty()) return

        val color = detectColor()
        val lines = buffers.getBuffer(RenderType.lines())
        for (p in errors) {
            // 数组里理论上不会有 null（getPos 返回的 BlockPos 都是新建的），保留原实现的防御分支
            if (p == null) continue
            LevelRenderer.renderLineBox(
                poseStack, lines, AABB(p),
                color.r, color.g, color.b, color.lineAlpha
            )
        }
    }

    /**
     * 错误框是不是已经超过停留时间。
     *
     * 停留时长配在 `overlay.detectBoxLifetime`（秒，`0` = 不自动消失）。
     * 时间戳是检测那一刻由服务端写进物品的**游戏刻**（[StructureDetectBehavior.getTime]），
     * 这里拿客户端自己的游戏刻相减：两边的游戏刻同步推进、暂停时都不走，所以「停留 N 秒」和玩家直觉一致。
     *
     * 物品上没有时间戳（老存档里的旧物品、或者手改的组件）按「早过期」处理，免得残留的框一直挂在世界上。
     */
    private fun detectOverlayExpired(stack: ItemStack): Boolean {
        val seconds = GtetConfig.detectBoxLifetime()
        if (seconds <= 0) return false // 0 = 不自动消失

        val written = StructureDetectBehavior.getTime(stack)
        if (written < 0) return true

        val level = Minecraft.getInstance().level ?: return true
        return level.gameTime - written > seconds * 20L
    }

    // ── 颜色解析 ──

    /** 选区颜色（跟随配置；配置串非法时退回默认值）。 */
    private fun writeColor(): OverlayColor {
        val raw: String = GtetConfig.writeOverlayColor()
        if (raw != writeColorRawCache) {
            writeColorRawCache = raw
            writeColorCache = parseColor(raw, DEFAULT_WRITE_COLOR, "writeColor")
        }
        return writeColorCache
    }

    /** 错误位置颜色（跟随配置；配置串非法时退回默认值）。 */
    private fun detectColor(): OverlayColor {
        val raw: String = GtetConfig.detectOverlayColor()
        if (raw != detectColorRawCache) {
            detectColorRawCache = raw
            detectColorCache = parseColor(raw, DEFAULT_DETECT_COLOR, "detectColor")
        }
        return detectColorCache
    }

    /**
     * 解析一行颜色配置：`R;G;B`，后面可以按顺序再跟透明度（先是线框、后是填充）。
     *
     * 分量用分号或逗号分隔、取值 0~1（超范围会被夹回去）；某个分量写错了就用默认值，
     * 只有连 `R;G;B` 都凑不齐时才整条退回默认颜色，并记一条警告。
     */
    private fun parseColor(raw: String?, fallback: OverlayColor, key: String): OverlayColor {
        if (raw.isNullOrBlank()) return fallback

        val parts = raw.trim().split(Regex("[;,]"))
        if (parts.size < 3) {
            LOGGER.warn(
                "[gtetscore] overlay.{} 要写成 R;G;B（可再跟透明度），当前是 \"{}\"，已退回默认颜色",
                key, raw
            )
            return fallback
        }

        return OverlayColor(
            component(parts[0], fallback.r),
            component(parts[1], fallback.g),
            component(parts[2], fallback.b),
            if (parts.size > 3) component(parts[3], fallback.lineAlpha) else fallback.lineAlpha,
            if (parts.size > 4) component(parts[4], fallback.fillAlpha) else fallback.fillAlpha
        )
    }

    /** 单个分量：解析失败用默认值，数值夹到 0~1。 */
    private fun component(raw: String, fallback: Float): Float {
        return try {
            0.0F.coerceAtLeast(1.0F.coerceAtMost(raw.trim().toFloat()))
        } catch (e: NumberFormatException) {
            fallback
        }
    }

    /** 解析好的覆盖层颜色：RGB + 线框透明度 + 填充透明度。 */
    private data class OverlayColor(
        val r: Float,
        val g: Float,
        val b: Float,
        val lineAlpha: Float,
        val fillAlpha: Float,
    )

    /** 由选区两个端点方块（含端点）得到包围盒。 */
    private fun blockRangeBox(min: BlockPos, max: BlockPos): AABB {
        return AABB(
            min.x.toDouble(), min.y.toDouble(), min.z.toDouble(),
            max.x + 1.0, max.y + 1.0, max.z + 1.0
        )
    }
}
