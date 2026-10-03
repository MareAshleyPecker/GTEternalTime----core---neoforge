package rain.fox.gtetcore.api.capability

import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine

/**
 * 「可跑多线程配方」多方块控制器的标记接口。
 *
 * 接线方式（8.0.0）：机器实现本接口，并把配方逻辑交给基类构造器
 * —— `WorkableMultiblockMachine(info, ThreadedRecipeLogic())`
 * （8.0.0 删掉了 `createRecipeLogic` 工厂，改成构造器注入，见 `AssemblyLineMachine.java:48`）。
 *
 * ⚠️ 实现本接口的机器必须同时是多方块控制器：默认实现要拿 `getParts()` 现扫部件表，
 * 而 `parts` 属于 `MultiblockControllerMachine`（`MultiblockControllerMachine.java:163`）。
 *
 * ⚠️ 两个成员都是 Kotlin 接口默认实现（编译产物是 `DefaultImpls`），**Java 机器必须自己实现它们**；
 * 本工程的机器都是 Kotlin，不受影响。
 */
interface IThreadedRecipeMachine {

    /** 这台机器当前挂着的线程仓；没装（或结构没成型）时为 `null`。 */
    val threadHatch: IThreadHatch?
        get() {
            // 现扫 getParts()：部件的装卸发生在成型/失效时，现扫不会留下悬空引用。
            // 装多个线程仓时取 threadCount 最大的那个（不叠加）。
            val controller = this as? MultiblockControllerMachine ?: return null
            var best: IThreadHatch? = null
            for (part in controller.parts) {
                val hatch = part as? IThreadHatch ?: continue
                if (best == null || hatch.threadCount > best!!.threadCount) best = hatch
            }
            return best
        }

    /** 当前生效的线程数上限；没装线程仓时返回 1（行为退化回 GTM 原版的单配方机器）。 */
    val threadCount: Int get() = threadHatch?.threadCount ?: 1
}
