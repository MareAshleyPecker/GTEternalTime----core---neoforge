package rain.fox.gtetcore.integration.jade.provider;

import rain.fox.gtetcore.GTETSCore;
import rain.fox.gtetcore.data.lang.JadeLang;

import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IEnergyInfoProvider;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

import java.math.BigInteger;

/**
 * Jade 上的「机器存了多少 EU」进度条。
 *
 * ⚠️ **踩过的坑**：复用不了 GTM 的 `ElectricContainerBlockProvider` —— 它从方块能力取数
 * （`ElectricContainerBlockProvider.java:35-37`），而那条能力的处理器会先过
 * `MachineTrait#hasCapability(side)`（`MetaMachineBlock.java:562-572`），它就是
 * `capabilityValidator.test(side)`（`MachineTrait.java:111-112`）；无线能源仓把外部能力关了
 * （`WirelessEnergyHatchPartMachine.kt:94` 的 `setCapabilityValidator { false }`）⇒ 能力恒 null
 * ⇒ GTM 那条在无线仓上一片空白。所以这里绕开能力，直接扫 trait 表。
 *
 * ⚠️ 也不能把 GTM 的 provider 再注册一遍：它本来就注册在 `Block.class` 上、已覆盖全部方块
 * （`GTJadePlugin.java:112-120`），重注册只会撞 UID。
 *
 * ⚠️ 判据里那句「GTM 能取到能力就让位」是为了不出现两条能量条 —— 我们只在它画不到的地方补位。
 *
 * @author rain fox
 */
public class ETEnergyStorageProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    public static final ETEnergyStorageProvider INSTANCE = new ETEnergyStorageProvider();

    private static final ResourceLocation UID = GTETSCore.id("energy_storage");
    private static final String KEY = UID.toString();
    private static final String STORED = "Stored";
    private static final String CAPACITY = "Capacity";

    /** 能量条颜色，与 GTM 自己那条一致（`ElectricContainerBlockProvider.java:64`）。 */
    private static final int COLOR = 0xFFEEE600;

    private ETEnergyStorageProvider() {}

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor blockAccessor) {
        if (!(blockAccessor.getBlockEntity() instanceof MetaMachine machine)) return;
        if (gtmAlreadyShows(blockAccessor)) return;

        var provider = findEnergy(machine);
        if (provider == null) return;
        var info = provider.getEnergyInfo();
        if (info.capacity().signum() <= 0) return;

        var tag = new CompoundTag();
        tag.putByteArray(STORED, info.stored().toByteArray());
        tag.putByteArray(CAPACITY, info.capacity().toByteArray());
        data.put(KEY, tag);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor block, IPluginConfig config) {
        var serverData = block.getServerData();
        if (!serverData.contains(KEY, Tag.TAG_COMPOUND)) return;

        var tag = serverData.getCompound(KEY);
        var stored = new BigInteger(tag.getByteArray(STORED));
        var capacity = new BigInteger(tag.getByteArray(CAPACITY));
        JadeStoredBar.add(tooltip, stored, capacity, JadeLang.ENERGY_STORED, COLOR);
    }

    /**
     * GTM 的 provider 能不能在这台机器上取到能量能力（不分面 + 六个面，与它自己的取数范围一致）。能取到就让它画。
     */
    private static boolean gtmAlreadyShows(BlockAccessor blockAccessor) {
        var level = blockAccessor.getLevel();
        var pos = blockAccessor.getPosition();
        if (GTCapabilityHelper.getEnergyInfoProvider(level, pos, null) != null) return true;
        for (Direction side : Direction.values()) {
            if (GTCapabilityHelper.getEnergyInfoProvider(level, pos, side) != null) return true;
        }
        return false;
    }

    /**
     * 找出这台机器对外藏着的那份能量缓冲：① trait 表（`getTraitsByInterface` 只按类型筛、不看
     * `hasCapability`，`MachineTraitHolder.java:52-63`，这正是能拿到无线仓容器的原因）；
     * ② 多方块控制器的能量是现算的 `EnergyContainerList`、不是 trait（`WorkableElectricMultiblockMachine.java:180-190`）。
     */
    private static IEnergyInfoProvider findEnergy(MetaMachine machine) {
        for (IEnergyInfoProvider provider : machine.getTraitHolder()
                .getTraitsByInterface(IEnergyInfoProvider.class)) {
            if (provider.isOneProbeHidden()) continue;
            if (provider.getEnergyInfo().capacity().signum() > 0) return provider;
        }
        if (machine instanceof WorkableElectricMultiblockMachine electric) {
            var container = electric.getEnergyContainer();
            if (container != null && container.getEnergyCapacity() > 0L) return container;
        }
        return null;
    }
}
