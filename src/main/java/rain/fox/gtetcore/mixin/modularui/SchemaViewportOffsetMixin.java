package rain.fox.gtetcore.mixin.modularui;

import brachy.modularui.drawable.schema.BaseSchemaRenderer;
import brachy.modularui.screen.viewport.GuiContext;
import brachy.modularui.theme.WidgetTheme;
import brachy.modularui.widget.sizer.Area;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rain.fox.gtetcore.client.mui.PreviewDiag;

import java.nio.IntBuffer;
import java.util.Locale;

/**
 * 把 3D 的 **GL 视口原点**从「MUI 局部坐标」补成「真实屏幕坐标」，并接管这段时间的裁剪。
 *
 * <p>`BaseSchemaRenderer.draw` 偏移 32-46 算的是 `context.transformX(x, y) + screenArea.x()`；
 * 但 `GuiViewportStack.translate` 只改 MUI 自己的 `TransformationMatrix`（javap：只 `Matrix4f.translate` + `markDirty`），
 * **从不碰 `GuiGraphics` 的 pose** ⇒ 配方查看器把这块 MUI 内容平移进配方框时，那层平移只在 pose 里，
 * MUI 坐标是「配方框局部」的。2D 控件靠 pose 落位所以是对的，3D 走的是绝对帧缓冲坐标的 GL 视口 ⇒ 落到框外。
 *
 * <p>真值就取 MUI 自己公开的 `GuiContext.getLastGraphicsPose()`（= `graphics.pose().last().pose()`），
 * 于是缺失量 = `pose平移 − screenArea.xy`；差值为 0 时（普通 MUI 屏幕、我们自己的全屏 overlay）行为与原版逐字节一致。
 *
 * <p>只改「GL 视口矩形」这一处调用，不动 `draw` 的其它行为，也不动 GTM 源码。
 */
@Mixin(value = BaseSchemaRenderer.class, remap = false)
public class SchemaViewportOffsetMixin {

    @Unique
    private int gtetcore$viewportDeltaX;

    @Unique
    private int gtetcore$viewportDeltaY;

    @Unique
    private boolean gtetcore$offsetLogged;

    @Unique
    private static final IntBuffer gtetcore$scissorBox = BufferUtils.createIntBuffer(16);

    @Unique
    private boolean gtetcore$clipTakenOver;

    @Unique
    private boolean gtetcore$hadScissorTest;

    @Unique
    private boolean gtetcore$hadStencilTest;

    @Inject(method = "draw", at = @At("HEAD"), remap = false, require = 0)
    private void gtetcore$measureViewportDelta(GuiContext context, int x, int y, int width, int height,
                                               WidgetTheme theme, CallbackInfo ci) {
        this.gtetcore$viewportDeltaX = 0;
        this.gtetcore$viewportDeltaY = 0;
        try {
            Area screen = context.getScreenArea();
            Matrix4f pose = context.getLastGraphicsPose();
            int poseX = Math.round(pose.m30());
            int poseY = Math.round(pose.m31());
            // 位置缺失量 = pose 平移 − screenArea（不是 −算出的原点：算出的原点本身已经含 screenArea）
            this.gtetcore$viewportDeltaX = poseX - screen.x;
            this.gtetcore$viewportDeltaY = poseY - screen.y;

            if (!this.gtetcore$offsetLogged) {
                this.gtetcore$offsetLogged = true;
                int computedX = context.transformX(x, y) + screen.x;
                int computedY = context.transformY(x, y) + screen.y;
                PreviewDiag.line(String.format(Locale.ROOT,
                        "[GTET] 3D视口校正 renderer=%s 算出的原点=(%d,%d) pose平移=(%d,%d) 屏幕区=(%d,%d %dx%d) "
                                + "=> 校正=(%d,%d) 校正后原点=(%d,%d) pose缩放=(%.3f,%.3f)",
                        this.getClass().getSimpleName(), computedX, computedY, poseX, poseY,
                        screen.x, screen.y, screen.width, screen.height,
                        this.gtetcore$viewportDeltaX, this.gtetcore$viewportDeltaY,
                        computedX + this.gtetcore$viewportDeltaX, computedY + this.gtetcore$viewportDeltaY,
                        pose.m00(), pose.m11()));
            }
        } catch (Throwable ignored) {
            // 量不出来就按原版走
        }
    }

    /**
     * 把 `draw` 的第 1、2 个 int 参数（= `drawAtZeroPadded` 传进来的 padding）加上缺失量：
     * 方法内部 `vx = transformX(x, y) + screenArea.x()` 于是变成 `局部坐标 + pose平移` = 真实屏幕坐标。
     *
     * <p>用 `@ModifyVariable(argsOnly)` 而不是 `@Redirect`：对 `Viewport;calculateOpenGLViewportFromRectangle` 的
     * `@At(target=...)` 成员校验在本环境直接报 InvalidInjectionException（**`require = 0` 挡不住成员校验失败**，
     * runData 当场 FATAL），参数槽位则不需要成员校验、且槽号确定（this=0、context=1、x=2、y=3）。
     */
    @ModifyVariable(method = "draw", at = @At("HEAD"), argsOnly = true, index = 2, remap = false, require = 0)
    private int gtetcore$offsetViewportX(int x) {
        return x + this.gtetcore$viewportDeltaX;
    }

    @ModifyVariable(method = "draw", at = @At("HEAD"), argsOnly = true, index = 3, remap = false, require = 0)
    private int gtetcore$offsetViewportY(int y) {
        return y + this.gtetcore$viewportDeltaY;
    }

    /**
     * 裁剪也一起接管：把 GL scissor 设成**校正后**的那个矩形，并暂时关掉 MUI 的 stencil 测试。
     *
     * <p>理由：`Viewport.applyViewport` 只设 `RenderSystem.viewport`（javap），3D 的裁剪实际由 MUI 的 widget stencil 管；
     * 那层 mask 是 MUI 局部坐标系的产物 ⇒ 视口搬到框内后可能被旧位置的 mask 裁掉。这里显式用「视口矩形 ∩ scissor」把 3D 框住，
     * 结果不依赖 MUI 的 mask 对不对；`draw` 结束时原样还原。
     *
     * <p>只在缺失量 ≠ 0（= 被配方查看器平移的场景）时动 GL 状态；普通 MUI 屏幕与自建全屏 overlay 完全不碰。
     */
    @Inject(method = "draw", at = @At("HEAD"), remap = false, require = 0)
    private void gtetcore$takeOverClip(GuiContext context, int x, int y, int width, int height, WidgetTheme theme,
                                       CallbackInfo ci) {
        this.gtetcore$clipTakenOver = false;
        if (this.gtetcore$viewportDeltaX == 0 && this.gtetcore$viewportDeltaY == 0) return;
        try {
            Window window = Minecraft.getInstance().getWindow();
            double scale = window.getGuiScale();
            int vx = context.transformX(x, y) + context.getScreenArea().x() + this.gtetcore$viewportDeltaX;
            int vy = context.transformY(x, y) + context.getScreenArea().y() + this.gtetcore$viewportDeltaY;
            this.gtetcore$hadScissorTest = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            this.gtetcore$hadStencilTest = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
            gtetcore$scissorBox.clear();
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, gtetcore$scissorBox);

            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            // 与 Viewport.calculateOpenGLViewportFromRectangle 同一套换算（× GUI scale、y 自帧缓冲底部翻）
            GL11.glScissor((int) Math.ceil(vx * scale),
                    window.getHeight() - (int) Math.ceil((vy + height) * scale),
                    (int) Math.ceil(width * scale), (int) Math.ceil(height * scale));
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            this.gtetcore$clipTakenOver = true;
        } catch (Throwable ignored) {
            this.gtetcore$clipTakenOver = false;
        }
    }

    @Inject(method = "draw", at = @At("RETURN"), remap = false, require = 0)
    private void gtetcore$restoreClip(GuiContext context, int x, int y, int width, int height, WidgetTheme theme,
                                      CallbackInfo ci) {
        if (!this.gtetcore$clipTakenOver) return;
        this.gtetcore$clipTakenOver = false;
        try {
            if (this.gtetcore$hadStencilTest) GL11.glEnable(GL11.GL_STENCIL_TEST);
            else GL11.glDisable(GL11.GL_STENCIL_TEST);
            if (this.gtetcore$hadScissorTest) {
                GL11.glScissor(gtetcore$scissorBox.get(0), gtetcore$scissorBox.get(1),
                        gtetcore$scissorBox.get(2), gtetcore$scissorBox.get(3));
            } else {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        } catch (Throwable ignored) {
            // 还原失败也不能把异常抛回渲染链
        }
    }
}
