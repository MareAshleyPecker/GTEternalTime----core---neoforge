package rain.fox.gtetcore.integration.jade.provider;

import rain.fox.gtetcore.GTETSCore;
import rain.fox.gtetcore.api.timeflow.ITimeFlowStorage;
import rain.fox.gtetcore.data.lang.JadeLang;

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
 * Jade 上的「仓 / 塔里存了多少 TF」进度条。取数走 [ITimeFlowStorage]，时序仓与主控塔天然都在范围内。
 *
 * ⚠️ TF 存量没同步到客户端（`ETTimeFlowHandler.storedTf` 只标 `@SaveField`，
 * 理由见 `TimeFlowHatchPartMachine.kt:317-323`）⇒ 必须走 Jade 的服务端取数通道，
 * 客户端只负责画。
 *
 * ⚠️ 塔没成型时容量是 0，直接不画（`MasterTowerMachine.kt:90`）。
 *
 * @author rain fox
 */
public class ETTimeFlowStorageProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

    public static final ETTimeFlowStorageProvider INSTANCE = new ETTimeFlowStorageProvider();

    private static final ResourceLocation UID = GTETSCore.id("time_flow_storage");
    private static final String KEY = UID.toString();
    private static final String STORED = "Stored";
    private static final String CAPACITY = "Capacity";

    /** TF 条用青色，与能量条（黄）区分开。 */
    private static final int COLOR = 0xFF00E5FF;

    private ETTimeFlowStorageProvider() {}

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor blockAccessor) {
        if (!(blockAccessor.getBlockEntity() instanceof ITimeFlowStorage storage)) return;
        if (storage.getTimeFlowCapacity() <= 0L) return;

        var tag = new CompoundTag();
        tag.putLong(STORED, storage.getTimeFlow());
        tag.putLong(CAPACITY, storage.getTimeFlowCapacity());
        data.put(KEY, tag);
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor block, IPluginConfig config) {
        var serverData = block.getServerData();
        if (!serverData.contains(KEY, Tag.TAG_COMPOUND)) return;

        var tag = serverData.getCompound(KEY);
        JadeStoredBar.add(
                tooltip,
                BigInteger.valueOf(tag.getLong(STORED)),
                BigInteger.valueOf(tag.getLong(CAPACITY)),
                JadeLang.TIME_FLOW_STORED,
                COLOR);
    }
}
