package rain.fox.gtetcore.test

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredPartMachine
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient

/**
 * 最小共存测试（阶段 3）：继承 GTM 8.0.0 的部件机基类，并用它**自研**的同步注解。
 *
 * 对应 GTET 在 1.20.1 里的 `@Persisted` / `@DescSynced` 组合：8.0.0 已弃用 LDLib 的 syncdata，
 * 改用自己的 `api/sync_system`。日志验证方式见类尾注释。
 */
class TestSyncPartMachine(info: BlockEntityCreationInfo, tier: Int) : TieredPartMachine(info, tier) {

    /** 持久化 + 同步到客户端（= 旧的 `@Persisted @DescSynced`）。 */
    @field:SaveField
    @field:SyncToClient
    var testValue: Int = 0

    private var ticks = 0

    override fun onLoad() {
        super.onLoad()
        // MetaMachine#serverTick 是 final，服务端逻辑只能这样订阅
        if (!isRemote) subscribeServerTick(::serverTickTask)
        GTCEu.LOGGER.info("[GTET-TEST] 部件机载入：testValue = {}（世界重进后不是 0 就说明 @SaveField 生效）", testValue)
    }

    /** 服务端每 10 秒 +1 并标脏；值一直涨说明机器真的在跑、且写回了存档。 */
    private fun serverTickTask() {
        if (++ticks % 200 != 0) return
        testValue++
        syncDataHolder.markClientSyncFieldDirty("testValue")
        GTCEu.LOGGER.info("[GTET-TEST] 服务端 testValue = {}", testValue)
    }

    /** 客户端每 10 秒打印一次：值跟着服务端涨说明 @SyncToClient 生效。 */
    override fun clientTick() {
        super.clientTick()
        if (++ticks % 200 == 0) {
            GTCEu.LOGGER.info("[GTET-TEST] 客户端 testValue = {}", testValue)
        }
    }
}
