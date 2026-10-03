package rain.fox.gtetcore.integration.jade.provider;

import net.minecraft.network.chat.Component;
import snownee.jade.api.ITooltip;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.IElementHelper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

import com.gregtechceu.gtceu.utils.FormattingUtil;

/**
 * 两条 provider 共用的进度条画法 —— 抄 GTM 的 `ElectricContainerBlockProvider.java:58-68`，
 * 只把颜色与语言键做成入参。
 */
public final class JadeStoredBar {

    private JadeStoredBar() {}

    /** 超过这个数就改用科学计数法（与 GTM 同口径）。 */
    private static final BigInteger SCI_THRESHOLD = BigInteger.valueOf((long) 1e12);

    /**
     * 画一条「存量 / 容量」进度条。
     *
     * ⚠️ 语言键的占位符必须用 `%s`：两个格式化后的数字是 String，用 `%d` 会抛
     * `IllegalFormatConversionException`（GTM 的 zh_cn 里那条 `%d / %d EU` 是坏的，别抄）。
     */
    public static void add(ITooltip tooltip, BigInteger stored, BigInteger capacity,
                           String langKey, int color) {
        if (capacity.signum() <= 0) return;
        var storedStr = FormattingUtil.formatNumberOrSic(stored, SCI_THRESHOLD);
        var capacityStr = FormattingUtil.formatNumberOrSic(capacity, SCI_THRESHOLD);
        // 夹到 [0,1]：主控塔重建成更矮之后存量可以超过当前容量（`MasterTowerMachine.kt:76-77` 允许），
        // 不夹的话进度条会画出格子。
        var progress = Math.clamp(
                new BigDecimal(stored).divide(new BigDecimal(capacity), MathContext.DECIMAL32).floatValue(),
                0.0F, 1.0F);

        var helper = IElementHelper.get();
        tooltip.add(
                helper.progress(
                        progress,
                        Component.translatable(langKey, storedStr, capacityStr),
                        helper.progressStyle().color(color, color).textColor(-1),
                        BoxStyle.GradientBorder.DEFAULT_VIEW_GROUP,
                        true));
    }
}
