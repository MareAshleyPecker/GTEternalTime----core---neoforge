package rain.fox.gtetcore.mixin.modularui.debug;

import brachy.modularui.api.widget.IWidget;
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
 * TEMP(诊断)：量 3D 控件的摆放。**每实例只写一行**（`@Unique` once 标记，不靠 key，避免刷屏）。
 *
 * <p>⚠️ 只打**原始** `Area` 字段（x/y/w/h 与 rx/ry），**不要**用 `Area.transformAndRectanglerize`：
 * `IWidget.transform` 默认实现是 `stack.translate(area.rx, area.ry, 0)`，
 * 而 `Area.applyPos(parentX, parentY)` 是 `x = parentX + rx` ⇒ `Area.x/y` 本来就是绝对坐标，
 * 再过一遍 viewport 栈等于**二次平移**（上一版探针的"控件绝对/面板绝对"两列就是这么错的）。
 *
 * <p>只读日志，`draw` 行为一行不改；`require = 0` 保证注入失败只丢日志、不崩游戏。定案后整类删。
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
            Area screen = context.getScreenArea();

            // BaseSchemaRenderer.draw 真正用的视口原点/尺寸（drawAtZeroPadded 传 area.padding + paddedSize）
            int viewX = context.transformX(area.getPadding().left(), area.getPadding().top()) + screen.x;
            int viewY = context.transformY(area.getPadding().left(), area.getPadding().top()) + screen.y;
            // 当前 viewport 栈的原点（= 该控件自身绝对位置，见类注释）
            int originX = context.transformX(0, 0);
            int originY = context.transformY(0, 0);

            StringBuilder ancestors = new StringBuilder();
            IWidget parent = widget.getParent();
            for (int i = 0; i < 3 && parent != null; i++) {
                Area pa = parent.getArea();
                ancestors.append(" < ").append(parent.getClass().getSimpleName())
                        .append("(x=").append(pa.x).append(",y=").append(pa.y).append(" ")
                        .append(pa.width).append("x").append(pa.height)
                        .append(" rx=").append(pa.rx).append(",ry=").append(pa.ry).append(")");
                parent = parent.getParent();
            }

            String panel;
            ModularPanel<?> modularPanel = widget.getPanel();
            if (modularPanel == null) {
                panel = "null";
            } else {
                Area p = modularPanel.getArea();
                panel = "x=" + p.x + ",y=" + p.y + " " + p.width + "x" + p.height + " (rx=" + p.rx + ",ry=" + p.ry + ")";
            }

            ModularScreen scr = widget.getScreen();
            String screenId = scr == null ? "null" : scr.getOwner() + "#" + scr.getName();
            Window window = Minecraft.getInstance().getWindow();

            PreviewDiag.line(String.format(Locale.ROOT,
                    "[GTET] 控件=%s 屏=%s | 控件area=(x=%d,y=%d %dx%d rx=%d,ry=%d) 栈原点=(%d,%d) "
                            + "渲染器视口原点=(%d,%d) 视口尺寸=%dx%d | 面板area=(%s) | 屏幕区=(%d,%d %dx%d) "
                            + "| GUI=%dx%d FB=%dx%d guiScale=%.3f | scale=%.3f yaw=%.3f pitch=%.3f offset=(%.3f,%.3f,%.3f) "
                            + "| 祖先链=%s",
                    widget.getClass().getSimpleName(), screenId,
                    area.x, area.y, area.width, area.height, area.rx, area.ry, originX, originY,
                    viewX, viewY, area.paddedWidth(), area.paddedHeight(),
                    panel,
                    screen.x, screen.y, screen.width, screen.height,
                    window.getGuiScaledWidth(), window.getGuiScaledHeight(),
                    window.getWidth(), window.getHeight(),
                    (double) window.getWidth() / Math.max(1, window.getGuiScaledWidth()),
                    widget.getScale(), widget.getYaw(), widget.getPitch(),
                    widget.getOffset().x(), widget.getOffset().y(), widget.getOffset().z(),
                    ancestors));
        } catch (Throwable ignored) {
            // 诊断日志不能影响渲染
        }
    }
}
