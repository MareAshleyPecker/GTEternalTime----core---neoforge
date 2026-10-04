package rain.fox.gtetcore.integration.ae2

import com.gregtechceu.gtceu.api.machine.MachineDefinition
import com.gregtechceu.gtceu.api.machine.MetaMachine
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine
import net.minecraft.resources.ResourceLocation
import org.jetbrains.annotations.Nullable
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.integration.ae2.ETPatternBufferCapacities.NATIVE
import java.util.concurrent.ConcurrentHashMap

/**
 * 「多阶段 ME 样板总成」的容量表：**方块定义 id → 样板槽位数**。
 *
 * ## 为什么要有这张表（而不是把容量当构造参数传）
 * GTCEu 8.0.0 的 [MEPatternBufferPartMachine] 仍然把容量写成
 * `protected static final int MAX_PATTERN_COUNT = 27` —— 编译期常量，于是被**内联**进五处：
 * ```
 * <init>            bipush 27 × 3   patternInventory / internalInventory / patternSlotDetails
 * syncWorkerCount   bipush 27   × 1   Mth.clamp(proxies.size() + 1, 1, MAX_PATTERN_COUNT)
 * addWorker         bipush 27   × 1   if (idx >= MAX_PATTERN_COUNT) return;
 * ```
 * （`javap -p -c libs/gtceu-1.21.1-8.0.0-SNAPSHOT.jar` 可复核；另外 `buildMainUI` 与
 * `getAvailablePatterns` 各有一处，前者被本类的面板覆写取代、后者只是 `ArrayList` 的容量提示，无害。）
 *
 * 字段初始化发生在**父类构造期**，子类此时一个字段都还没赋值，所以「继承 + 覆写」这条路走不通。
 * 本项目的做法不是复制那 1192 行，而是由 `MixinMEPatternBufferCapacity` 把上面那五处内联常量
 * 换成 `ETPatternBufferCapacities.of(getDefinition())`，让存储**一出生**就是本档容量 ——
 * 于是父类自己的构造 / 读档 / AE 终端 / 取回 / worker 数逻辑一行都不用改。
 *
 * ## 为什么能在构造期问出「我是哪一档」
 * `MetaMachine` 的构造器第一件事就是赋值 `holder`，而 `IMachineBlockEntity#getDefinition()`
 * 读的是**方块**（不是机器实例）；`MetaMachineBlockEntity` 又是先
 * `getDefinition().createMetaMachine(info)` 再继续构造。所以父类字段初始化跑的时候，
 * `getDefinition()` 一定可用、且一定是本档的定义。改常量那几行都在 `super(...)` 之后。
 *
 * ## 表必须在使用前填好
 * 条目由注册代码（`ETMEPatternBufferHatches`）在 `.register()` **之前**写入；机器实例只会在
 * 方块实体创建时构造，必然晚于注册，所以顺序是安全的。万一没填到，退回 [NATIVE] 并给一次警告
 * —— 不静默降级。
 *
 * ## 8.0.0 里不再需要「最大容量」
 * 7.5.3 的镜像有一张**每槽一条 RHL** 的代理表，必须按「已登记的最大容量」建死；8.0.0 的
 * `ProxySlotRecipeHandler` 只剩**一条** RHL，其 `handleRecipe` 整张 map 转交给
 * `buffer.getBufferRecipeHandler()`（上游源码 ProxySlotRecipeHandler.java:82-89），槽级转发下沉到
 * 总成自己的 `BufferRecipeHandlerList`。所以「低档镜像连高档总成」这件事在 8.0.0 里**天然成立**，
 * 老工程的 `maxCapacity()` 与那 480 行 `ETProxySlotRecipeHandler` 一起被删掉了。
 *
 * @author rain fox
 */
object ETPatternBufferCapacities {

    /**
     * GTM 原生 `me_pattern_buffer` 的容量。
     *
     * ⚠️ 数值必须与 `MEPatternBufferPartMachine.MAX_PATTERN_COUNT` 一致（8.0.0 = 27）；
     * 本 mod 之外的任何一个 [MEPatternBufferPartMachine] 实例（GTM 自己的、别的附属的）
     * 都按这个数走，行为与打 mixin 之前**完全一样**。
     */
    const val NATIVE = 27

    /** 容量的合理上限：只用来把注册期的笔误挡在门外，不是玩法限制。 */
    private const val MAX_REASONABLE = 4096

    private val BY_DEFINITION: MutableMap<ResourceLocation, Int> = ConcurrentHashMap()

    /** 本 mod 注册过的命名空间：用来区分「我们漏登记了」和「这本来就是别人的机器」。 */
    private val OWN_NAMESPACES: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** 已经警告过的定义 id，避免每放一个方块刷一次日志。 */
    private val WARNED: MutableSet<ResourceLocation> = ConcurrentHashMap.newKeySet<ResourceLocation>()

    /**
     * 登记一档容量。
     *
     * @param definitionId 方块定义的 id（必须与 `MachineDefinition#getId()` 逐字一致）
     * @param capacity     样板槽位数（≥ [NATIVE]，且 ≤ 4096）
     */
    @JvmStatic
    fun register(definitionId: ResourceLocation, capacity: Int) {
        if (capacity !in NATIVE..MAX_REASONABLE) {
            throw IllegalArgumentException(
                "[gtetcore] 样板总成容量越界：" + definitionId + " -> " + capacity +
                    "（允许 " + NATIVE + " ~ " + MAX_REASONABLE + "）"
            )
        }
        OWN_NAMESPACES.add(definitionId.namespace)
        BY_DEFINITION[definitionId] = capacity
    }

    /**
     * 取某个方块定义的容量；**不是在册的定义一律返回 GTM 原生的 27**。
     *
     * 这个方法就是 mixin 在父类构造器里调用的那一个，所以它不能抛异常
     * （否则 GTM 自己的方块一放就崩）、要够快（一台机器构造期会调 3 次）。
     *
     * @param definition 方块定义，可为 null（方块状态还不是 GT 机器方块等异常情形）
     */
    @JvmStatic
    fun of(@Nullable definition: MachineDefinition?): Int {
        if (definition == null) return NATIVE
        val id = definition.id
        val capacity = BY_DEFINITION[id]
        if (capacity != null) return capacity
        // 本 mod 自己的机器命中这条分支 = 注册漏了 / 写错了 id，必须吵一次，不能静默按 27 建出来
        if (OWN_NAMESPACES.contains(id.namespace) && WARNED.add(id)) {
            GTETSCore.LOGGER.warn(
                "[gtetcore] {} 没在 ETPatternBufferCapacities 里登记容量，已按 GTM 原生 {} 个样板槽建立；" +
                    "请检查是否有注册处漏调 ETPatternBufferCapacities.register", id, NATIVE
            )
        }
        return NATIVE
    }

    /**
     * 运行时自检：机器的实际存储格子数与表里登记的是否一致。
     *
     * ⚠️ 这是给「mixin 没生效」准备的**探针**：mixin 若因 GTM 版本变化没能改写那几处常量，
     * 存储会静默按 27 建出来（面板与 AE 终端也只有 27 格），这条警告是唯一能立刻看出问题的地方。
     *
     * @param machine 刚构造完的样板总成
     */
    @JvmStatic
    fun verify(machine: MetaMachine) {
        if (machine !is MEPatternBufferPartMachine) return
        val definition = machine.definition
        val expected = of(definition)
        val actual = machine.patternInventory.slots
        if (actual != expected && WARNED.add(definition.id)) {
            GTETSCore.LOGGER.warn(
                "[gtetcore] {} 的样板槽位数是 {}，容量表登记的是 {}；" +
                    "通常意味着 MixinMEPatternBufferCapacity 没生效（GTM 版本变了？）",
                definition.id, actual, expected
            )
        }
    }
}
