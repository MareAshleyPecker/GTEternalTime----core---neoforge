package rain.fox.gtetcore.test

import com.gregtechceu.gtceu.GTCEu
import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.data.RotationState
import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.registry.registrate.entry.MachineEntry
import rain.fox.gtetcore.registry.ETRegistrate
import rain.fox.gtetcore.registry.machineBuilder

/** 最小测试（阶段 3）：注册一台真正的部件机，验证「注册 → 放置 → 存档 → 同步」整条链路。 */
object TestMachines {

    @JvmField
    val TEST_SYNC_PART: MachineEntry<MachineDefinition> = ETRegistrate.REGISTRATE
        .machineBuilder("test_sync_part") { info -> TestSyncPartMachine(info, GTValues.LV) }
        .langValue("Test Sync Part")
        .tier(GTValues.LV)
        .rotationState(RotationState.ALL)
        .colorOverlayTieredHullModel(GTCEu.id("block/overlay/machine/overlay_item_hatch_input"))
        .register()
}
