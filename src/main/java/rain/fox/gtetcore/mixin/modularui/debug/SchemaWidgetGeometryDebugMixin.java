package rain.fox.gtetcore.mixin.modularui.debug;

import brachy.modularui.screen.ModularPanel;
import brachy.modularui.screen.ModularScreen;
import brachy.modularui.screen.viewport.ModularGuiContext;
import brachy.modularui.theme.WidgetThemeEntry;
import brachy.modularui.widget.sizer.Area;
import brachy.modularui.widgets.SchemaWidget;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rain.fox.gtetcore.client.mui.PreviewDiag;

import java.util.Locale;

/**
 * TEMP(诊断)：量 3D 控件的摆放（MUI 布局层）。
 *
 * <p>`@Unique` once 标记保证**每个控件实例只写一行**（上一轮按 key 节流会刷屏，这次不靠 key）。
 * 只读日志，`draw` 行为一行不改；`require = 0` 保证注入失败只丢日志、不崩游戏。定案后整类删。
 */
@Mixin(value = SchemaWidget.class, remap = false)
public class SchemaWidgetGeometryDebugMixin {

    @Unique
    private boolean gtetcore$geometryLogged;

    @Inject(method = "draw", at = @At("HEAD"), remap = false, require = 0)
    private void gtetcore$logGeometry(ModularGuiContext context, WidgetThemeEntry<?> theme, CallbackInfo ci) {
        if (this.gtetcore$geometryLogged) return;
        this.gtetcore$geometryLogged = true;
        try {
            SchemaWidget widget = (SchemaWidget) (Object) this;
            Area area = widget.getArea();
            Area abs = area.createCopy();
            abs.transformAndRectanglerize(context);
            Area screen = context.getScreenArea();

            // BaseSchemaRenderer.draw 取的视口原点/尺寸（drawAtZeroPadded 传的就是 area.padding + paddedSize）
            int viewX = context.transformX(area.getPadding().left(), area.getPadding().top()) + screen.x;
            int viewY = context.transformY(area.getPadding().left(), area.getPadding().top()) + screen.y;

            String panel;
            ModularPanel<?> modularPanel = widget.getPanel();
            if (modularPanel == null) {
                panel = "null";
            } else {
                Area panelAbs = modularPanel.getArea().createCopy();
                panelAbs.transformAndRectanglerize(context);
                panel = panelAbs.x + "," + panelAbs.y + " " + panelAbs.width + "x" + panelAbs.height;
            }

            ModularScreen scr = widget.getScreen();
            String screenId = scr == null ? "null" : scr.getOwner() + "#" + scr.getName();

            Window window = Minecraft.getInstance().getWindow();
            PreviewDiag.line(String.format(Locale.ROOT,
                    "[GTET] 控件=%s 屏=%s | 控件绝对=(%d,%d) 控件尺寸=%dx%d | 渲染器视口原点=(%d,%d) 视口尺寸=%dx%d "
                            + "| 面板绝对=(%s) | 屏幕区=(%d,%d,%dx%d) | GUI=%dx%d FB=%dx%d | scale=%.3f yaw=%.3f pitch=%.3f offset=(%.3f,%.3f,%.3f)",
                    widget.getClass().getSimpleName(), screenId,
                    abs.x, abs.y, abs.width, abs.height,
                    viewX, viewY, area.paddedWidth(), area.paddedHeight(),
                    panel,
                    screen.x, screen.y, screen.width, screen.height,
                    window.getGuiScaledWidth(), window.getGuiScaledHeight(),
                    window.getWidth(), window.getHeight(),
                    widget.getScale(), widget.getYaw(), widget.getPitch(),
                    widget.getOffset().x(), widget.getOffset().y(), widget.getOffset().z()));
        } catch (Throwable ignored) {
            // 诊断日志不能影响渲染
        }
    }
}
