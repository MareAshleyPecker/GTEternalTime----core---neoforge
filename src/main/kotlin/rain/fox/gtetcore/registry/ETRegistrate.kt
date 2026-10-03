package rain.fox.gtetcore.registry

import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.MachineInstanceFactory
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate
import com.gregtechceu.gtceu.api.registry.registrate.builder.MachineBuilder
import com.tterrag.registrate.providers.ProviderType
import rain.fox.gtetcore.GTETCore
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * GTET 自己的 GTRegistrate 实例：GTM 要求每个 addon 用自己的一份，官方的只服务 gtceu 本体。
 *
 * `create(modId)` 会自动把注册/数据生成监听器挂到 gtetcore 自己的 mod 事件总线上。
 */
object ETRegistrate {

    @JvmField
    val REGISTRATE: GTRegistrate = GTRegistrate.create(GTETCore.ID)

    init {
        // 双语条目分工：英文进 registrate 的 en_us，中文由 ZhCnLangProvider 写进 zh_cn。
        REGISTRATE.addDataGenerator(ProviderType.LANG) { provider ->
            LangUtil.CUSTOM_LANG.forEach { (key, pair) -> provider.add(key, pair.first) }
        }
    }
}

/**
 * 注册机器的 Kotlin 入口。
 *
 * GTM 的 `GTRegistrate.machine(...)` 带自引用泛型 `S extends MachineBuilder<..., S>`，Kotlin 推不出来
 * （直接写会报 `Not enough information to infer type argument for 'S'`）；这里显式落到星投影的
 * `MachineBuilder` 上，调用方就能一条链写完。运行时它本来就是普通 `MachineBuilder`（GTM 内部 unchecked cast）。
 */
fun <M : MetaMachine> GTRegistrate.machineBuilder(
    name: String,
    factory: MachineInstanceFactory<M>,
): MachineBuilder<MachineDefinition, M, *> = machine(name, factory)
