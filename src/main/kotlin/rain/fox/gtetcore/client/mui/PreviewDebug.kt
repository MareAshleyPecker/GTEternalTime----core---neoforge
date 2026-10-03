package rain.fox.gtetcore.client.mui

import com.gregtechceu.gtceu.api.mui.MultiblockSchemaInfo
import org.apache.logging.log4j.Level
import rain.fox.gtetcore.GTETSCore

/**
 * TEMP(诊断)：定位「JEI 内嵌预览里 3D 内容位置错位」，定案后整个文件连同两处调用一起删。
 *
 * 只读日志，不参与任何布局/取景计算。
 *
 * @author rain fox
 */
object PreviewDebug {

    @JvmStatic
    fun log(message: String) {
        GTETSCore.LOGGER.log(Level.INFO, "[GTET-TEST] {}", message)
    }

    /**
     * 取景的输入与结果。
     *
     * `SchemaWidget.draw` 每帧用 `focus + offset` 当 lookAt、`scale` 当距离（`SchemaWidget.draw` 偏移 0-77），
     * 所以焦点 / 平移 / 距离三项就决定了 3D 内容在框里的落点。
     */
    @JvmStatic
    fun camera(label: String, info: MultiblockSchemaInfo?, schemaWidth: Int) {
        try {
            val schema = info?.mapSchema
            val widget = info?.multiSchema
            val bounds = schema?.bounds
            log(
                "预览取景(" + label + ") 3D控件宽=" + schemaWidth +
                    " bounds=" + (bounds?.first?.toShortString() ?: "null") + ".." +
                    (bounds?.second?.toShortString() ?: "null") +
                    " focus=(" + (schema?.focus?.x() ?: -1f) + ", " + (schema?.focus?.y() ?: -1f) + ", " +
                    (schema?.focus?.z() ?: -1f) + ")" +
                    " scale=" + (widget?.scale ?: -1f) + " yaw=" + (widget?.yaw ?: -1f) +
                    " pitch=" + (widget?.pitch ?: -1f) +
                    " offset=(" + (widget?.offset?.x ?: -1f) + ", " + (widget?.offset?.y ?: -1f) + ", " +
                    (widget?.offset?.z ?: -1f) + ")" +
                    " 重算fit=" + PreviewCameraFit.fitDistance(schema)
            )
        } catch (t: Throwable) {
            // 诊断日志本身不能影响开界面
        }
    }
}
