package rain.fox.gtetcore.client.mui

import brachy.modularui.widgets.SchemaWidget
import com.gregtechceu.gtceu.client.mui.schema.MutableSchema
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 多方块 3D 预览的自适应取景（S1.2）。
 *
 * 初始取景（`MultiblockPreviewWidgetMixin`）与「重置视角」按钮共用这一份算式，
 * 免得两个地方各算一遍、数值漂移。
 *
 * @author rain fox
 */
object PreviewCameraFit {

    /** `BaseSchemaRenderer.setupCamera` 里写死的垂直 FOV：1.0471976f = 60°。 */
    private const val FOV = 1.0471976f

    /** 包围球刚好填满视口高度再留 10% 余量。 */
    private const val FIT_MARGIN = 1.1f
    private const val FIT_MIN = 3.0f
    private const val FIT_MAX = 160.0f

    /** `yaw` 45°：等轴视角，能同时看到三个面。 */
    const val DEFAULT_YAW: Float = 0.7853982f

    /** `SchemaWidget` 构造器里写死的初始 pitch（javap：`<init>` 里 pitch = 0.7853982f）。 */
    const val DEFAULT_PITCH: Float = 0.7853982f

    /** `SchemaWidget.scale` 就是 `Camera.dist`；包围球半径 / tan(fov/2) 即刚好入画的距离。 */
    @JvmStatic
    fun fitDistance(schema: MutableSchema?): Float {
        val bounds = schema?.bounds ?: return FIT_MIN
        val min = bounds.first ?: return FIT_MIN
        val max = bounds.second ?: return FIT_MIN

        val dx = (max.x - min.x + 1).toFloat()
        val dy = (max.y - min.y + 1).toFloat()
        val dz = (max.z - min.z + 1).toFloat()
        if (dx <= 0f || dy <= 0f || dz <= 0f) return FIT_MIN

        val diagonal = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        val exact = diagonal / (2f * tan((FOV / 2f).toDouble()).toFloat())
        return maxOf(FIT_MIN, minOf(FIT_MAX, exact * FIT_MARGIN))
    }

    /** 构造期的初始取景：只动 `scale` / `yaw`，`pitch` / `offset` 保持 MUI 默认。 */
    @JvmStatic
    fun applyInitial(widget: SchemaWidget?, schema: MutableSchema?) {
        if (widget == null) return
        widget.scale(fitDistance(schema)).yaw(DEFAULT_YAW)
    }

    /**
     * 同一台机器换档（换 substructure）之后重新取景。
     *
     * 只重算距离：`SchemaWidget.draw` 每帧都用 `schema().getFocus()` 重建注视点，而 `getFocus()` 就是
     * `MutableSchema.center`（`setBlocks` 会更新），结构中心变了相机会自动跟过去；玩家的 yaw / pitch /
     * 中键平移保持不变，只把新的包围球重新塞进视口。
     */
    @JvmStatic
    fun refit(widget: SchemaWidget?, schema: MutableSchema?) {
        if (widget == null) return
        widget.scale(fitDistance(schema))
    }

    /** 「重置视角」：恢复 [applyInitial] 的取景，并把中键平移带跑的 `offset` 归零。 */
    @JvmStatic
    fun reset(widget: SchemaWidget?, schema: MutableSchema?) {
        if (widget == null) return
        widget.scale(fitDistance(schema)).yaw(DEFAULT_YAW).pitch(DEFAULT_PITCH).offset(0f, 0f, 0f)
    }
}
