package rain.fox.gtetcore.mixin.gtm;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.mui.MultiblockSchemaInfo;
import com.gregtechceu.gtceu.client.mui.schema.MutableSchema;
import com.gregtechceu.gtceu.integration.recipeviewer.widgets.MultiblockPreviewWidget;

import brachy.modularui.widgets.SchemaWidget;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * <p>只影响 `<init>` 走完的那个 widget：JEI / EMI 的 GT 多方块信息页与 GTM 终端里的预览。
 */
@Mixin(value = MultiblockPreviewWidget.class, remap = false)
public class MultiblockPreviewWidgetMixin {

    /** `BaseSchemaRenderer.setupCamera` 里写死的垂直 FOV：1.0471976f = 60°。 */
    private static final float FOV = 1.0471976f;
    /** 包围球刚好填满视口高度再留 10% 余量。 */
    private static final float FIT_MARGIN = 1.1f;
    private static final float FIT_MIN = 3.0f;
    private static final float FIT_MAX = 160.0f;
    /** `yaw` 45° + `pitch` 默认 45°，能同时看到三个面的等轴视角。 */
    private static final float DEFAULT_YAW = 0.7853982f;

    @Shadow
    private MultiblockSchemaInfo multiblockSchemaInfo;

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void gtetcore$fitPreviewCamera(MultiblockMachineDefinition definition, MultiblockSchemaInfo schemaInfo,
                                           int width, int height, CallbackInfo ci) {
        try {
            // 非客户端线程时构造器提前 return，getMultiSchema() 还是 null
            SchemaWidget widget = this.multiblockSchemaInfo.getMultiSchema();
            if (widget == null) return;
            widget.scale(gtetcore$fitDistance(this.multiblockSchemaInfo.getMapSchema())).yaw(DEFAULT_YAW);
        } catch (Throwable ignored) {
            // 取景算不出来就用 MUI 的默认值，不能连带预览界面打不开
        }
    }

    /** `SchemaWidget.scale` 就是 `Camera.dist`；包围球半径 / tan(fov/2) 即刚好入画的距离。 */
    private static float gtetcore$fitDistance(MutableSchema schema) {
        if (schema == null) return FIT_MIN;
        Pair<BlockPos, BlockPos> bounds = schema.getBounds();
        if (bounds == null) return FIT_MIN;

        BlockPos min = bounds.getFirst();
        BlockPos max = bounds.getSecond();
        float dx = max.getX() - min.getX() + 1f;
        float dy = max.getY() - min.getY() + 1f;
        float dz = max.getZ() - min.getZ() + 1f;
        if (dx <= 0f || dy <= 0f || dz <= 0f) return FIT_MIN;

        float diagonal = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float exact = diagonal / (2f * (float) Math.tan(FOV / 2f));
        return Math.max(FIT_MIN, Math.min(FIT_MAX, exact * FIT_MARGIN));
    }
}
