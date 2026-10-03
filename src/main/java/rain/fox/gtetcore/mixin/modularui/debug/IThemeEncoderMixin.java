package rain.fox.gtetcore.mixin.modularui.debug;

import brachy.modularui.api.ITheme;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修 MUI 自带调试覆盖层的空指针（分类：mixin/modularui/debug = 打 MUI、且只影响它的调试工具）。
 *
 * <p>勾选调试覆盖层的「Print Theme json」后，点任意按钮都会走
 * {@code DebugOverlay.logTheme} → {@code ITheme.ENCODER}，而那个编码器无条件读
 * {@code getParentTheme().getId()}；根主题没有父主题 → NullPointerException，
 * 异常从事件总线抛出会把客户端直接打崩。
 *
 * <p>这里只把「父主题那次 getId 调用」（字节码里第二个 ITheme#getId，ordinal = 1）
 * 改成空安全：父主题为空时写字符串 "null"。主题 JSON 本来就只是给人看的调试输出，够用了。
 */
@Mixin(targets = "brachy.modularui.api.ITheme$1", remap = false)
public class IThemeEncoderMixin {

    @Redirect(method = "encode*", at = @At(
                    value = "INVOKE",
                    target = "Lbrachy/modularui/api/ITheme;getId()Ljava/lang/String;",
                    ordinal = 1),
            remap = false)
    private String gtetcore$parentIdOrNull(ITheme parentTheme) {
        return parentTheme == null ? "null" : parentTheme.getId();
    }
}
