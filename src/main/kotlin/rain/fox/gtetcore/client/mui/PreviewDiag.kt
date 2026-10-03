package rain.fox.gtetcore.client.mui

import net.minecraft.client.Minecraft
import java.io.File

/**
 * TEMP(诊断)：把预览几何写进 `run/gtet-preview-diag.log`（独立文件，不污染 latest.log）。
 *
 * 每个控件实例**只写一次**（调用方自己用 once 标记保证），所以不存在刷屏。定案后整个文件删。
 *
 * @author rain fox
 */
object PreviewDiag {

    @Volatile
    private var headerWritten = false

    @JvmStatic
    fun line(message: String) {
        try {
            val file = File(Minecraft.getInstance().gameDirectory, "gtet-preview-diag.log")
            if (!headerWritten) {
                headerWritten = true
                file.appendText("=== GTET 预览几何诊断（每实例一次）===" + System.lineSeparator())
            }
            file.appendText(message + System.lineSeparator())
        } catch (t: Throwable) {
            // 诊断本身绝不能影响渲染
        }
    }
}
