package rain.fox.gtetcore.mixin.modularui.debug;

import brachy.modularui.drawable.schema.BaseSchemaRenderer;
import brachy.modularui.screen.viewport.GuiContext;
import brachy.modularui.theme.WidgetTheme;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rain.fox.gtetcore.client.mui.PreviewDiag;

import java.nio.IntBuffer;
import java.util.Locale;

/**
 * TEMP(诊断)：量 3D 场景**实际**被画进哪个 GL viewport（帧缓冲像素）。
 *
 * <p>读点在 `setupCamera` 之后、`resetCamera` 之前，此刻 GL viewport 就是 3D 的绘制矩形；
 * 与控件 GUI 坐标矩形一比就能算出「偏了多少 / 缩了多少」（GUI scale 换算）。
 * **每个 renderer 实例只写一行**；`require = 0` 保证注入失败只丢日志、不崩游戏。定案后整类删。
 */
@Mixin(value = BaseSchemaRenderer.class, remap = false)
public class SchemaGlViewportDebugMixin {

    @Unique
    private static final IntBuffer gtetcore$glViewport = BufferUtils.createIntBuffer(16);

    @Unique
    private boolean gtetcore$glLogged;

    @Inject(method = "draw", at = @At(
                    value = "INVOKE",
                    target = "Lbrachy/modularui/drawable/schema/BaseSchemaRenderer;setupCamera(II)V",
                    shift = At.Shift.AFTER),
            remap = false,
            require = 0)
    private void gtetcore$logGlViewport(GuiContext context, int x, int y, int width, int height, WidgetTheme theme,
                                        CallbackInfo ci) {
        if (this.gtetcore$glLogged) return;
        this.gtetcore$glLogged = true;
        try {
            gtetcore$glViewport.clear();
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, gtetcore$glViewport);
            Window window = Minecraft.getInstance().getWindow();
            double guiScale = (double) window.getWidth() / Math.max(1, window.getGuiScaledWidth());
            PreviewDiag.line(String.format(Locale.ROOT,
                    "[GTET] GL视口(帧缓冲px)=(%d,%d) %dx%d | 该控件 GUI 尺寸=%dx%d | GUIscale=%.3f "
                            + "| 折算成 GUI 坐标=(%.1f,%.1f) %.1fx%.1f",
                    gtetcore$glViewport.get(0), gtetcore$glViewport.get(1),
                    gtetcore$glViewport.get(2), gtetcore$glViewport.get(3),
                    width, height, guiScale,
                    gtetcore$glViewport.get(0) / guiScale, gtetcore$glViewport.get(1) / guiScale,
                    gtetcore$glViewport.get(2) / guiScale, gtetcore$glViewport.get(3) / guiScale));
        } catch (Throwable ignored) {
            // 诊断日志不能影响渲染
        }
    }
}
