package rain.fox.gtetcore.mixin.gtm;

import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * GTM 样板总成上那个**私有成员**的访问桥。
 *
 * <p>8.0.0 只剩一件需要补而 GTM 没开口子的东西：
 * <ul>
 * <li>{@code customName}：父类只有 {@code setCustomName(String)}，没有 getter。我们的面板与
 * AE 终端都要在「未成型」分支读它（未成型时父类会把图标与名字写死成 GTM 自己的
 * {@code me_pattern_buffer}，本 mod 要显示自己这一档）。</li>
 * </ul>
 *
 * <p>⚠️ 7.5.3 时代这里还有第二个口子 {@code @Invoker("onPatternChange")}；8.0.0 里
 * {@code onPatternChange(int)} 已经改成 **public**（javap：{@code public void onPatternChange(int)}），
 * 子类直接调用即可，那个 invoker 删掉了。
 *
 * <p>用 {@code @Accessor} 而不是注入器：Mixin 0.8.5 的注解处理器对**接口 mixin 里的注入器**
 * 是硬拒绝的，但 accessor 正是接口 mixin 支持的用法。方法名带 {@code gtet$} 前缀，
 * 避免将来 GTM 自己加了同名成员时撞车。
 *
 * <p>{@code remap = false}：目标是 GTM 自己的成员（不是 MC 覆写点），生产环境里名字不变。
 *
 * @author rain fox
 */
@Mixin(value = MEPatternBufferPartMachine.class, remap = false)
public interface IMEPatternBufferAccess {

    /** 读父类的私有 {@code customName}（GTM 只生成了 setter）。 */
    @Accessor("customName")
    String gtet$getCustomName();
}
