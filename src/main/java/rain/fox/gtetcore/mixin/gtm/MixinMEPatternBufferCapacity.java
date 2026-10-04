package rain.fox.gtetcore.mixin.gtm;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import rain.fox.gtetcore.integration.ae2.ETPatternBufferCapacities;

/**
 * 「多阶段 ME 样板总成」的容量注入点：把 GTM 样板总成里**内联的 27** 换成按档取值。
 *
 * <h2>为什么只能这么改</h2>
 * GTM 的容量是 {@code protected static final int MAX_PATTERN_COUNT = 27} —— 编译期常量，
 * 于是它被**内联**进五个地方。用 {@code javap -p -c} 反汇编 8.0.0 的
 * {@code MEPatternBufferPartMachine} 可以看到：
 * <pre>
 * &lt;init&gt;            bipush 27 × 3   patternInventory / internalInventory / patternSlotDetails
 * syncWorkerCount   bipush 27 × 1   Mth.clamp(proxies.size() + 1, 1, MAX_PATTERN_COUNT)
 * addWorker         bipush 27 × 1   if (idx &gt;= MAX_PATTERN_COUNT) return;
 * buildMainUI       bipush 27 × 1   new Grid().height(18 * (MAX_PATTERN_COUNT / 9))   ← 被面板覆写取代
 * getAvailablePatterns bipush 27 × 1  new ArrayList&lt;&gt;(MAX_PATTERN_COUNT)             ← 只是容量提示
 * </pre>
 * 前五处必须改：`&lt;init&gt;` 那三处决定**存储**大小（改漏一处就是
 * `ArrayIndexOutOfBoundsException`：`onPatternChange(index)` 会拿 index 去索引
 * `patternSlotDetails`）；`syncWorkerCount` / `addWorker` 决定**同时在飞的样板数**上限
 * （不改的话 216 档也只有 27 个 worker，第 28 个之后的样板推不进去）。
 * 后两处不用改：`buildMainUI` 整体被本 mod 的面板覆写取代（见
 * {@code ETMEPatternBufferPartMachine#buildMainUI}），`getAvailablePatterns` 里那个只是
 * {@code ArrayList} 的初始容量，写小了会自己扩容、没有行为差异。
 *
 * <h2>为什么在构造期取值是安全的</h2>
 * `&lt;init&gt;` 里那三处 bipush 都在 {@code super(...)} 之后，而 `MetaMachine` 的构造器第一件事
 * 就是赋值 `holder`，`IMachineBlockEntity#getDefinition()` 读的又是方块状态上的方块（不是机器
 * 实例），`MetaMachineBlockEntity` 也是先 `getDefinition().createMetaMachine(info)` 再继续自己的
 * 构造 —— 所以此处 `getDefinition()` 已经可用。详见 {@link ETPatternBufferCapacities} 的类注释。
 *
 * <h2>对 GTM 自己的机器零影响</h2>
 * 查表命不中（例如 {@code gtceu:me_pattern_buffer}、别的附属的样板总成）一律返回
 * {@link ETPatternBufferCapacities#NATIVE}（27），与打 mixin 之前逐字节等价。
 *
 * <p>⚠️ {@code require}：GTM 若改动这几个地方的初始化（常量个数变了、或不再内联），mixin 会在
 * 类加载阶段**直接报错**，而不是静默退回 27。这是刻意的：容量是本功能的核心，
 * 宁可启动失败也不能悄悄做成 27 格。
 *
 * @author rain fox
 */
@Mixin(value = MEPatternBufferPartMachine.class, remap = false)
public class MixinMEPatternBufferCapacity {

    /**
     * 把构造器里内联的 {@code 27} 换成按档容量（三处：patternInventory / internalInventory /
     * patternSlotDetails）。
     *
     * @param original 原常量（27）
     * @return 本机器对应的样板槽位数；非本 mod 的定义为 27
     */
    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 27), require = 3)
    private int gtet$patternCapacity(int original) {
        return ETPatternBufferCapacities.of(((MetaMachine) (Object) this).getDefinition());
    }

    /**
     * 把 worker 数上限里内联的 {@code 27} 换成按档容量。
     *
     * <p>{@code syncWorkerCount()} 里那一句是 {@code Mth.clamp(proxies.size() + 1, 1, MAX_PATTERN_COUNT)}；
     * 上限不改的话，216 档总成最多也只有 27 个 worker 能承接 AE 推来的样板。
     */
    @ModifyConstant(method = "syncWorkerCount", constant = @Constant(intValue = 27), require = 1)
    private int gtet$workerCap(int original) {
        return ETPatternBufferCapacities.of(((MetaMachine) (Object) this).getDefinition());
    }

    /** {@code addWorker()} 里的越界闸门 {@code if (idx >= MAX_PATTERN_COUNT) return;}。 */
    @ModifyConstant(method = "addWorker", constant = @Constant(intValue = 27), require = 1)
    private int gtet$workerLimit(int original) {
        return ETPatternBufferCapacities.of(((MetaMachine) (Object) this).getDefinition());
    }
}
