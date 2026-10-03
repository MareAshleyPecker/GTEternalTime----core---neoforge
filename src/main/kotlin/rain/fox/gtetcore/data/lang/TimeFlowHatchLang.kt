package rain.fox.gtetcore.data.lang

import net.minecraft.resources.ResourceLocation
import rain.fox.gtetcore.GTETSCore
import rain.fox.gtetcore.api.timeflow.ETTimeFlowCapability
import rain.fox.gtetcore.util.lang.LangUtil

/**
 * 时序仓（TF 供给仓）用到的双语条目。
 *
 * 分两类：
 * 1. **配方能力名** [RECIPE_CAPABILITY_NAME] —— 这条键不是本仓自己拼的，而是 GTM 的
 *    `RecipeCapability#getName()` 拼出来的：**8.0.0 是**
 *    `Component.translatable(id.toLanguageKey("recipe_capability"))`（`RecipeCapability.java:125-127`），
 *    即 `recipe_capability.<命名空间>.<路径>`（老工程 7.5.3 那套
 *    `recipe.capability.<name>.name` 在 8.0.0 已经不会被任何代码拼出来）。
 *    GTM 自带的 5 个能力（item / fluid / eu / cwu / block_state）在 GTM 自己的 lang 里有译文
 *    （`assets/gtceu/lang/en_us.json` 里就是 `recipe_capability.gtceu.item` 这种形状），
 *    **addon 能力没有** ⇒ 不登记这条键时，TF 不足的报错会直接把原始 key 显示给玩家。这里补上。
 * 2. **绑定手势 / tooltip / 面板行**（[TOOLTIP] / [BOUND] / [UNBOUND] / [CLOCK_UNBOUND] /
 *    [WRONG_DIMENSION] / [PANEL_*]）—— 运行时按坐标插值，只能在服务端 / 客户端求值后发给玩家。
 *
 * 登记时机：由 `CommonProxy.initLang` 调用一次 —— 必须在**数据生成之前**，
 * 与 `TimeClockLang` / `AdvancedTerminalLang` 同一套约定（本工程这套入口叫 `register()`）。
 *
 * @author rain fox
 */
object TimeFlowHatchLang {

    /**
     * TF 的配方能力显示名（键由 GTM 的 `RecipeCapability#getName()` 拼出，见类注释）。
     *
     * ⚠️ **不手写字符串**：直接按 GTM 的拼法算出来，免得哪天能力改名 / 换命名空间后两边对不上。
     * 这里用的两个来源都是 `const`（编译期内联、不会触发 `GTETSCore` / `ETTimeFlowCapability`
     * 的类初始化 —— 本 object 是在 mod 构造期被初始化的，那个时机不适合再拉别的类起来）。
     * 算出来的结果是 `recipe_capability.gtetscore.time_flow`。
     */
    @JvmField
    val RECIPE_CAPABILITY_NAME: String =
        ResourceLocation.fromNamespaceAndPath(GTETSCore.ID, ETTimeFlowCapability.NAME)
            .toLanguageKey("recipe_capability")

    private const val PREFIX = "gtetscore.time_flow_hatch."

    /** 方块 tooltip：怎么把仓绑到主控塔。 */
    const val TOOLTIP: String = PREFIX + "tooltip.0"

    /** 绑定成功的聊天提示（带塔坐标）。 */
    const val BOUND: String = PREFIX + "bound"

    /** 解绑成功的聊天提示。 */
    const val UNBOUND: String = PREFIX + "unbound"

    /** 手里的时序钟还没绑塔时的提示。 */
    const val CLOCK_UNBOUND: String = PREFIX + "clock_unbound"

    /** 时序钟上绑的塔在别的维度时的提示（本期不做跨维度供能）。 */
    const val WRONG_DIMENSION: String = PREFIX + "wrong_dimension"

    /** 部件面板：容量那一行（`容量上限 %s TF`，档位值从构造参数来，两端一致、不需要同步）。 */
    const val PANEL_CAPACITY: String = PREFIX + "panel.capacity"

    /** 部件面板：已绑定主控塔那一行（`已绑定主控塔 (%s, %s, %s)`，绑定字段是同步到客户端的）。 */
    const val PANEL_BOUND: String = PREFIX + "panel.bound"

    /** 部件面板：未绑定那一行。 */
    const val PANEL_UNBOUND: String = PREFIX + "panel.unbound"

    /**
     * 幂等登记（同名键重复登记只是覆盖同一张表）。
     *
     * 必须在 mod 构造期调用一次（由 `CommonProxy.initLang` 调），早于 `runData` 的数据生成。
     */
    @JvmStatic
    fun register() {
        // ① 配方能力名：TF 不足时报错文案里的那个名字
        LangUtil.add(RECIPE_CAPABILITY_NAME, "Time Flow", "时间流")

        // ② 绑定手势与 tooltip
        LangUtil.add(
            TOOLTIP,
            "Right-click with a time clock that is bound to a master tower to bind this hatch",
            "手持已绑定主控塔的时序钟右键本仓即可绑定（潜行右键解绑）"
        )
        LangUtil.add(
            BOUND,
            "Time flow hatch bound to master tower at (%s, %s, %s)",
            "时序仓已绑定主控塔：(%s, %s, %s)"
        )
        LangUtil.add(UNBOUND, "Time flow hatch unbound", "时序仓已解除绑定")
        LangUtil.add(
            CLOCK_UNBOUND,
            "Bind the time clock to a master tower first",
            "请先把时序钟绑定到主控塔"
        )
        LangUtil.add(
            WRONG_DIMENSION,
            "That master tower is in another dimension - a time flow hatch only draws from a tower in its own dimension",
            "那座主控塔在别的维度 —— 时序仓只从同维度的塔取用"
        )

        // ③ 部件面板
        LangUtil.add(PANEL_CAPACITY, "Capacity %s TF", "容量上限 %s TF")
        LangUtil.add(PANEL_BOUND, "Bound tower (%s, %s, %s)", "已绑定主控塔 (%s, %s, %s)")
        LangUtil.add(PANEL_UNBOUND, "Not bound to a master tower", "未绑定主控塔")
    }
}
