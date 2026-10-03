package rain.fox.gtetcore.mixin.gtm;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.mui.MachineUIPanel;
import com.gregtechceu.gtceu.api.machine.mui.MachineUIPanelBuilder;

import brachy.modularui.screen.UISettings;
import brachy.modularui.value.sync.PanelSyncManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rain.fox.gtetcore.GTETSCore;
import rain.fox.gtetcore.client.mui.MachineIoConfig;

/**
 * 给所有走 `MachineUIPanelBuilder` 的 GTM 机器挂上 3D 输入输出配置页。
 *
 * <p>目标方法 `MachineUIPanelBuilder.java:78` 是 `IMuiMachine.buildUI:29` 的收口调用 ——
 * 连自己覆写 `buildUI` 的机器（LargeMinerMachine.java:195-198）也会经过它。
 *
 * <p>没有选 `IMuiMachine#getPanelBuilder` 的 RETURN：在那里设的 `mainContents` 会被紧接着的
 * `IMuiMachine.buildUI:28` 覆盖掉。`MachineUIPanelBuilder` 本身没有任何分页 API，只有
 * left / rightConfigurators 与 mainContents 两个 Consumer。
 *
 * <p>`definition.getUI() != null` 走自定义 `PanelFactory` 的机器（MachineUIFactory.java:60-66）
 * 不经过这里，覆盖不到。
 */
@Mixin(value = MachineUIPanelBuilder.class, remap = false)
public class MachineUIPanelBuilderMixin {

    /** 与 `GTETSCore.LOGGER` 同名；那个字段在 Kotlin 侧是 private，Java 摸不到。 */
    private static final Logger LOGGER = LogManager.getLogger(GTETSCore.ID);

    @Shadow
    @Final
    private MetaMachine machine;

    @Inject(method = "build", at = @At("RETURN"), remap = false)
    private void gtetcore$attachIoConfigPage(PanelSyncManager syncManager, UISettings settings,
                                             CallbackInfoReturnable<MachineUIPanel> cir) {
        try {
            MachineIoConfig.attach(cir.getReturnValue(), machine, syncManager);
        } catch (Throwable t) {
            // 注入失败绝不能连带 GTM 自己的机器界面打不开
            LOGGER.log(Level.WARN, "[GTET-TEST] 挂载 3D 输入输出配置页失败", t);
        }
    }
}
