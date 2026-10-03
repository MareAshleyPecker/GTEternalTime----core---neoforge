package rain.fox.gtetcore.mixin.modularui.debug;

import brachy.modularui.drawable.schema.BaseSchemaRenderer;
import brachy.modularui.screen.viewport.GuiContext;
import brachy.modularui.theme.WidgetTheme;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
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
    private static final IntBuffer gtetcore$glScissor = BufferUtils.createIntBuffer(16);

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
            gtetcore$glScissor.clear();
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, gtetcore$glScissor);
            Window window = Minecraft.getInstance().getWindow();
            double guiScale = (double) window.getWidth() / Math.max(1, window.getGuiScaledWidth());
            // GL 的 y 从帧缓冲底部算起，要跟 MUI 的 area.x/y（左上原点）比就得翻过来
            double leftGui = gtetcore$glViewport.get(0) / guiScale;
            double topGui = (window.getHeight() - gtetcore$glViewport.get(1) - gtetcore$glViewport.get(3)) / guiScale;
            // 3D 渲染期间真正生效的裁剪与模型视图（用来判"视口对了但裁剪/矩阵还在局部坐标"）
            org.joml.Matrix4fStack mv = RenderSystem.getModelViewStack();
            PreviewDiag.line(String.format(Locale.ROOT,
                    "[GTET] GL视口(帧缓冲px)=(%d,%d) %dx%d | 该控件 GUI 尺寸=%dx%d | guiScale=%.3f "
                            + "| 折算成 GUI 左上角=(%.1f,%.1f) %.1fx%.1f "
                            + "| scissor=(%s %d,%d %dx%d) | stencilTest=%s | 模型视图平移=(%.1f,%.1f,%.1f)",
                    gtetcore$glViewport.get(0), gtetcore$glViewport.get(1),
                    gtetcore$glViewport.get(2), gtetcore$glViewport.get(3),
                    width, height, guiScale,
                    leftGui, topGui,
                    gtetcore$glViewport.get(2) / guiScale, gtetcore$glViewport.get(3) / guiScale,
                    GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),
                    gtetcore$glScissor.get(0), gtetcore$glScissor.get(1),
                    gtetcore$glScissor.get(2), gtetcore$glScissor.get(3),
                    GL11.glIsEnabled(GL11.GL_STENCIL_TEST),
                    mv.m30(), mv.m31(), mv.m32()));
        } catch (Throwable ignored) {
            // 诊断日志不能影响渲染
        }
    }
}
