package rain.fox.gtetcore.api.capability

import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility

/**
 * GTET 自己的多方块部件能力表。
 *
 * GTM 的 `PartAbility(String)` 构造函数是 public、内部只有「名字 + tier→方块集合」，
 * 没有全局注册表也没有单例限制，所以直接 new 即可，不需要 mixin 往 GTM 里塞字段。
 * 真正把方块登记进能力表的是 `MachineBuilder#abilities(...)`。
 *
 * ⚠️ 名字都带 `gtet_` 前缀避免与 GTM / 其它 addon 撞名；三者**必须互相独立**：
 * 超频、线程、时序是正交的三件事，复用同一个能力会让它们在结构里互斥。
 */
object ETPartAbility {

    /** 超频仓专用能力。 */
    @JvmField
    val OVERCLOCK_HATCH: PartAbility = PartAbility("gtet_overclock_hatch")

    /** 线程仓专用能力（线程是「在并行仓之上再叠一层」，不能复用并行仓的能力）。 */
    @JvmField
    val THREAD_HATCH: PartAbility = PartAbility("gtet_thread_hatch")

    /** 时序仓（TF 供给仓）专用能力 —— 同时充当「这台多方块支持 TF」的标记位。 */
    @JvmField
    val TF_HATCH: PartAbility = PartAbility("gtet_tf_hatch")
}
