package rain.fox.gtetcore.integration.jade;

import rain.fox.gtetcore.GTETSCore;
import rain.fox.gtetcore.integration.jade.provider.ETEnergyStorageProvider;
import rain.fox.gtetcore.integration.jade.provider.ETTimeFlowStorageProvider;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * GTET 的 Jade 插件：把机器里的能量（EU）与时间流（TF）存量画到 HUD 上。
 *
 * ⚠️ Jade 是可选依赖：本包只在 Jade 在场时由它按 `@WailaPlugin` 扫描加载，
 * 别处（代理、机器类…）一律不许引用本包的类。
 *
 * 注册范围取 `MetaMachine` / `MetaMachineBlock`，覆盖本工程全部机器
 * （机器方块一律是 `MetaMachineBlock`，`MachineBuilder.java:165-166`）。
 *
 * @author rain fox
 */
@WailaPlugin(GTETSCore.ID)
public class ETJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(ETEnergyStorageProvider.INSTANCE, MetaMachine.class);
        registration.registerBlockDataProvider(ETTimeFlowStorageProvider.INSTANCE, MetaMachine.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ETEnergyStorageProvider.INSTANCE, MetaMachineBlock.class);
        registration.registerBlockComponent(ETTimeFlowStorageProvider.INSTANCE, MetaMachineBlock.class);
    }
}
