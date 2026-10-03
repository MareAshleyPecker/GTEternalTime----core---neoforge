package rain.fox.gtetcore.client.mui

import brachy.modularui.widgets.SchemaWidget
import com.gregtechceu.gtceu.api.mui.MultiblockSchemaInfo
import com.gregtechceu.gtceu.client.mui.schema.MutableSchema
import org.apache.logging.log4j.Level
import rain.fox.gtetcore.GTETSCore
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

    /** 「重置视角」：恢复 [applyInitial] 的取景，并把中键平移带跑的 `offset` 归零。 */
    @JvmStatic
    fun reset(widget: SchemaWidget?, schema: MutableSchema?) {
        if (widget == null) return
        widget.scale(fitDistance(schema)).yaw(DEFAULT_YAW).pitch(DEFAULT_PITCH).offset(0f, 0f, 0f)
    }

    /**
     * TEMP(诊断)：打出取景的输入与结果，定位「内嵌预览里 3D 内容位置不对」；查清后删。
     *
     * `SchemaWidget.draw` 每帧用 `focus + offset` 当 lookAt、`scale` 当距离（`SchemaWidget.draw` 偏移 0-77），
     * 所以焦点 / 平移 / 距离三项就决定了 3D 内容在框里的落点。
     */
    @JvmStatic
    fun debugLog(label: String, info: MultiblockSchemaInfo?, schemaWidth: Int) {
        try {
            val schema = info?.mapSchema
            val widget = info?.multiSchema
            val bounds = schema?.bounds
            GTETSCore.LOGGER.log(
                Level.INFO,
                "[GTET-TEST] 预览取景({}) 3D控件宽={} bounds={}..{} focus=({}, {}, {}) " +
                    "scale={} yaw={} pitch={} offset=({}, {}, {}) 重算fit={}",
                label, schemaWidth,
                bounds?.first?.toShortString() ?: "null", bounds?.second?.toShortString() ?: "null",
                schema?.focus?.x() ?: -1f, schema?.focus?.y() ?: -1f, schema?.focus?.z() ?: -1f,
                widget?.scale ?: -1f, widget?.yaw ?: -1f, widget?.pitch ?: -1f,
                widget?.offset?.x ?: -1f, widget?.offset?.y ?: -1f, widget?.offset?.z ?: -1f,
                fitDistance(schema)
            )
        } catch (t: Throwable) {
            // 诊断日志本身不能影响开界面
        }
    }
}
