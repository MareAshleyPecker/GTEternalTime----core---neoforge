package rain.fox.gtetcore.mixin.modularui.debug;

import brachy.modularui.drawable.schema.BaseSchemaRenderer;
import brachy.modularui.screen.viewport.GuiContext;
import brachy.modularui.theme.WidgetTheme;
import brachy.modularui.widget.sizer.Area;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rain.fox.gtetcore.client.mui.PreviewDebug;

import java.nio.IntBuffer;

/**
 * TEMP(诊断)：量 `BaseSchemaRenderer.draw` 真正用的 3D 视口，以及 `setupCamera` 之后 GL 里实际的 viewport。
 *
 * <p>原方法（`BaseSchemaRenderer.draw` 偏移 26-61）算的就是
 * `x' = context.transformX(x, y) + context.getScreenArea().x()`、`y'` 同理，尺寸用 `area.paddedWidth/Height()`；
 * 这里只是把同样的算式重算一遍 + 读回 GL viewport，**不改 draw / setupCamera 的任何行为**。
 *
 * <p>`require = 0`：诊断注入失败只丢日志，绝不让游戏崩在第一次渲染 3D 上。定案后整类删。
 */
@Mixin(value = BaseSchemaRenderer.class, remap = false)
public class BaseSchemaRendererDebugMixin {

    @Unique
    private static final IntBuffer gtetcore$glViewport = BufferUtils.createIntBuffer(16);

    @Unique
    private static String gtetcore$lastKey;

    @Inject(method = "draw", at = @At("HEAD"), remap = false, require = 0)
    private void gtetcore$logViewport(GuiContext context, int x, int y, int width, int height, WidgetTheme widgetTheme,
                                      CallbackInfo ci) {
        try {
            Area screen = context.getScreenArea();
            int viewportX = context.transformX(x, y) + screen.x;
            int viewportY = context.transformY(x, y) + screen.y;

            String key = x + "," + y + "," + width + "," + height + "|" + viewportX + "," + viewportY + "|" + screen.x + ","
                    + screen.y + "," + screen.width + "," + screen.height;
            if (key.equals(gtetcore$lastKey)) return;
            gtetcore$lastKey = key;

            // 视口原点按 GUI 坐标给，同时报 GUI scale 与帧缓冲尺寸，便于判断是不是缩放/原点换算问题
            Minecraft mc = Minecraft.getInstance();
            Window window = mc.getWindow();

            PreviewDebug.log("3D视口 " + this.getClass().getSimpleName()
                    + " 视口原点=(" + viewportX + "," + viewportY + ") 视口尺寸=" + width + "x" + height
                    + " draw参数=(" + x + "," + y + ")"
                    + " MUI屏幕区=(" + screen.x + "," + screen.y + "," + screen.width + "x" + screen.height + ")"
                    + " MC屏=" + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName())
                    + " GUI=" + window.getGuiScaledWidth() + "x" + window.getGuiScaledHeight()
                    + " FB=" + window.getWidth() + "x" + window.getHeight());
        } catch (Throwable ignored) {
            // 诊断日志不能影响渲染
        }
    }

    /** `setupCamera` 跑完、`resetCamera` 还没跑的这一刻，GL viewport 就是 3D 场景真正的绘制矩形。 */
    @Inject(method = "draw", at = @At(
                    value = "INVOKE",
                    target = "Lbrachy/modularui/drawable/schema/BaseSchemaRenderer;setupCamera(II)V",
                    shift = At.Shift.AFTER),
            remap = false,
            require = 0)
    private void gtetcore$logGlViewport(GuiContext context, int x, int y, int width, int height, WidgetTheme widgetTheme,
                                        CallbackInfo ci) {
        try {
            gtetcore$glViewport.clear();
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, gtetcore$glViewport);
            PreviewDebug.log("GL viewport(帧缓冲像素)=" + gtetcore$glViewport.get(0) + "," + gtetcore$glViewport.get(1)
                    + "," + gtetcore$glViewport.get(2) + "x" + gtetcore$glViewport.get(3)
                    + "  ← 该 3D 控件的 GUI 尺寸是 " + width + "x" + height);
        } catch (Throwable ignored) {
            // 诊断日志不能影响渲染
        }
    }
}
