package rain.fox.gtetcore.mixin.gtm;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.mui.MultiblockSchemaInfo;
import com.gregtechceu.gtceu.integration.recipeviewer.widgets.MultiblockPreviewWidget;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rain.fox.gtetcore.client.mui.PreviewCameraFit;
import rain.fox.gtetcore.client.mui.PreviewControls;

/**
 * 给 GTM 的多方块 3D 预览换一个「按结构尺寸自适应」的初始相机。
 *
 * <p>GTM 自己那句 `camera().setPosAndLookAt(0, 0, -10, center)`（MultiblockPreviewWidget.java:166-167）
 * 是死代码：`SchemaWidget.draw` 每帧都会用 `camera().setLookAtAndAngle(getFocus() + offset, scale, yaw, pitch)`
 * 把相机重设一遍，而 `SchemaWidget` 构造器里 `scale`（= `Camera.dist`）固定 10.0f、`yaw` = 0
 * —— 结构一高就怼进去、一矮就缩成一小块。
 *
 * <p>这里只给「这一个 widget」设初始 `scale` / `yaw`，不动 `SchemaWidget.draw` 的通用行为
 * （本 mod 自己的 3D 输入输出配置页显式设了 `.scale(2.0f)`，必须保持生效）。
 *
 * <p>只影响 `<init>` 走完的那个 widget：JEI / EMI 的 GT 多方块信息页、GTM 终端里的预览，
 * 以及全屏预览自己新建的那一份。
 *
 * <p>顺带在同一处挂上「全屏 / 退出 + 重置视角」按钮条（[PreviewControls]）。
 */
@Mixin(value = MultiblockPreviewWidget.class, remap = false)
public class MultiblockPreviewWidgetMixin {

    @Shadow
    private MultiblockSchemaInfo multiblockSchemaInfo;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void gtetcore$fitPreviewCamera(MultiblockMachineDefinition definition, MultiblockSchemaInfo schemaInfo,
                                           int width, int height, CallbackInfo ci) {
        // 非客户端线程时构造器提前 return，multiblockSchemaInfo 与控件树都还没建
        if (!GTCEu.isClientThread()) return;

        try {
            MultiblockSchemaInfo info = this.multiblockSchemaInfo;
            PreviewCameraFit.applyInitial(info.getMultiSchema(), info.getMapSchema());
        } catch (Throwable ignored) {
            // 取景算不出来就用 MUI 的默认值，不能连带预览界面打不开
        }

        // mixin 里的 `this` 静态类型是 mixin 自己，得先过一遍 Object 才能转成目标类型
        PreviewControls.attach((MultiblockPreviewWidget) (Object) this, definition);
    }
}
