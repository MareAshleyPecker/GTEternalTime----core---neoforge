package rain.fox.gtetcore.mixin.modularui.debug;

import brachy.modularui.screen.viewport.ModularGuiContext;
import brachy.modularui.theme.WidgetThemeEntry;
import brachy.modularui.widget.sizer.Area;
import brachy.modularui.widgets.SchemaWidget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rain.fox.gtetcore.client.mui.PreviewDebug;

/**
 * TEMP(诊断)：量 `SchemaWidget` 自身的区域与它在屏幕上的绝对位置，用来算「3D 视口 vs 预览框」的偏移。
 *
 * <p>只读日志，`draw` 的行为一行不改。区域变化时才打一行，避免刷屏。
 * `require = 0`：注入失败只丢日志，不会崩。定案后整类删。
 */
@Mixin(value = SchemaWidget.class, remap = false)
public class SchemaWidgetDebugMixin {

    private static String gtetcore$lastKey;

    @Inject(method = "draw", at = @At("HEAD"), remap = false, require = 0)
    private void gtetcore$logArea(ModularGuiContext context, WidgetThemeEntry<?> theme, CallbackInfo ci) {
        try {
            Area area = ((SchemaWidget) (Object) this).getArea();
            Area abs = area.createCopy();
            // Area 的相对坐标要过一遍 viewport 栈才是屏幕坐标，跟 BaseSchemaRenderer.draw 里
            // `transformX + screenArea.x` 用的是同一套变换
            abs.transformAndRectanglerize(context);
            Area screen = context.getScreenArea();

            String key = area.x + "," + area.y + "," + area.width + "," + area.height + "|" + abs.x + "," + abs.y;
            if (key.equals(gtetcore$lastKey)) return;
            gtetcore$lastKey = key;

            PreviewDebug.log("SchemaWidget 区域 area=(" + area.x + "," + area.y + "," + area.width + "x" + area.height
                    + ") rel=(" + area.rx + "," + area.ry + ")"
                    + " 绝对=(" + abs.x + "," + abs.y + "," + abs.width + "x" + abs.height + ")"
                    + " MUI屏幕区=(" + screen.x + "," + screen.y + "," + screen.width + "x" + screen.height + ")");
        } catch (Throwable ignored) {
            // 诊断日志不能影响渲染
        }
    }
}
