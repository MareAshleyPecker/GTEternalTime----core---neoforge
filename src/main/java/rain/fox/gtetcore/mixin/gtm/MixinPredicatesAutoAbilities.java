package rain.fox.gtetcore.mixin.gtm;

import com.gregtechceu.gtceu.api.multiblock.MultiPredicate;
import com.gregtechceu.gtceu.api.multiblock.Predicates;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rain.fox.gtetcore.api.capability.ETPartAbility;

/**
 * 让「能插并行仓的多方块」也能插 GTET 的**超频仓**与**时序仓**（含 GTM / GCYM 自己的多方块）。
 *
 * <p>关键前提：一个部件能被哪些结构接受，**只由多方块自己的结构谓词说了算** ——
 * 部件注册时的 {@code MachineBuilder#abilities(...)} 只声明「它属于哪个能力」，
 * 不会让任何结构接受它。GTM 给多方块配通用部件槽的工厂方法是
 * {@code Predicates.autoAbilities(boolean checkMaintenance, boolean checkMuffler, boolean checkParallel)}
 * （{@code Predicates.java}，8.0.0 的三个布尔是**维护仓 / 消音仓 / 并行仓**，
 * 逐条 {@code getstatic PartAbility.MAINTENANCE|MUFFLER|PARALLEL_HATCH}，javap 核实），
 * 而它**没有任何 hook / SPI 扩展点** ⇒ 想追加我们的能力只能靠 mixin。
 *
 * <p>本 mixin 在 {@code @At("RETURN")} 处，仅当 {@code checkParallel == true} 时把返回值再
 * {@code .or(...)} 两条 GTET 能力，规格与并行仓一致（最多 1 个、预览 1 个）。
 * 8.0.0 的 {@code MultiPredicate#or(other)} 语义是「任一谓词通过或存在即可」
 * （{@code combine(this, Logic.OR, other)}，{@code MultiPredicate.java:293}），
 * 与老工程 7.5.3 的 {@code TraceabilityPredicate#or(...)} 同义；返回的是**新对象**，
 * 不动 GTM 原返回值。
 *
 * <p><b>为什么线程仓不在这里追加</b>：线程仓只在实现了 {@code IThreadedRecipeMachine} 的 GTET
 * 多方块上才有意义，追加到这里会让它在 GTM / GCYM 的多方块上变成「能插但不生效」的装饰部件；
 * 它由 GTET 自己的多方块在机壳谓词上显式加槽（见 {@code ETTestMultiblocks}）。
 *
 * <p><b>时序仓为什么走这里</b>：它是「给任意机器供 TF」的通用部件，插上去**一定生效** ——
 * 只要配方的 tick 输入里带 {@code gtetscore:time_flow}，扣费就由仓自己的
 * {@code IRecipeHandlerTrait} 完成，与多方块是不是 GTET 的无关。
 *
 * <p>⚠️ 只注入 {@code (ZZZ)} 这一个重载：其它重载（如 {@code autoAbilities(GTRecipeType...)}）
 * 本来就不加 {@code PARALLEL_HATCH}，那些多方块连并行仓都插不了，保持原样。
 *
 * <p>⚠️ 目标是 **static** 方法，注入处理器必须也是 {@code private static}；{@code remap = false}
 * （GTM 是 mod，名字不混淆，但描述符要按运行时原名写全，因为 {@code autoAbilities} 有多个重载）。
 */
@Mixin(value = Predicates.class, remap = false)
public class MixinPredicatesAutoAbilities {

    /** {@code autoAbilities(ZZZ)} 的完整描述符（8.0.0 的返回类型是 {@code MultiPredicate}）。 */
    @Inject(
            method = "autoAbilities(ZZZ)Lcom/gregtechceu/gtceu/api/multiblock/MultiPredicate;",
            at = @At("RETURN"),
            cancellable = true,
            remap = false)
    private static void gtetcore$addGtetHatches(boolean checkMaintenance, boolean checkMuffler,
                                                boolean checkParallel,
                                                CallbackInfoReturnable<MultiPredicate> cir) {
        if (!checkParallel) return;

        MultiPredicate original = cir.getReturnValue();
        if (original == null) return;

        // 与并行仓同规格：全局最多 1 个、JEI 预览 1 个
        MultiPredicate overclockHatch = Predicates.abilities(ETPartAbility.OVERCLOCK_HATCH)
                .setMaxCount(1)
                .setPreviewCount(1);
        // 一台多方块一个 TF 仓就够（两个仓会自动合池，见 ETTimeFlowHandler#handleRecipeInner）
        MultiPredicate timeFlowHatch = Predicates.abilities(ETPartAbility.TF_HATCH)
                .setMaxCount(1)
                .setPreviewCount(1);

        cir.setReturnValue(original.or(overclockHatch).or(timeFlowHatch));
    }
}
