package rain.fox.gtetcore.integration.ae2

import appeng.api.networking.IManagedGridNode
import appeng.api.networking.security.IActionSource
import appeng.api.stacks.GenericStack
import com.gregtechceu.gtceu.api.machine.feature.IMuiMachine

/**
 * 「本 mod 的 ME 库存部件」给**库存槽**看的那一面：标签判定器 + 每次拉取上限 + 三样 AE 侧句柄。
 *
 * ## 为什么要单独一个接口
 *
 * 库存槽必须自己完成 AE 抽取（GTM 把那段逻辑写在 `MEStockingBusPartMachine` 的
 * **private 内部类** 里，跨包继承不到，只能自己写一份），而抽取要用到
 * `isOnline()` / `getMainNode()` / `getActionSource()`，所以本接口把它们与 GTET 自己的两个配置项
 * （标签、每批上限）打包成一个槽能持有的视图，免得槽去依赖具体的机器类型。
 *
 * ⚠️ 继承 [IMuiMachine] 是为了让实现类直接进 GTM 8.0.0 的面板装配点：
 * `IMEStockingPart#getPanelBuilder`（IMEStockingPart.java:33-38）会把面板交给
 * `MachineUIPanelBuilder`，[ETTagFilterConfigurator] 就挂在那上面。
 *
 * ⚠️ **不要在构造期读 [getBatchSize]**：库存是在 `super(...)` 构造期间由
 * `createInventory` / `createTank` 造出来的，那时子类的字段还没初始化。
 * 本接口的实现方（部件类）只在运行期（`syncME` / 取数）读它，不受影响。
 *
 * @author rain fox
 */
interface IMEStockingHost : IETTagFilterPart, IMuiMachine {

    /** 本部件连的 AE 网络节点是否在线。 */
    fun isOnline(): Boolean

    /**
     * AE 网络节点。
     *
     * ⚠️ 8.0.0 里 `MEBusPartMachine` 有 public 的 `getMainNode()` / `getActionSource()`
     * （MEBusPartMachine.java:24,33），但 **`MEHatchPartMachine` 两个都没有**，只有
     * protected 的 `nodeHolder` / `actionSource` 字段（MEHatchPartMachine.java:12-14）。
     * 所以流体侧的实现类得自己转发 [GridNodeHolder.getMainNode]（等价路径）。
     */
    fun getMainNode(): IManagedGridNode

    /** AE 抽取用的动作来源（物品侧即 `MEBusPartMachine#getActionSource()`）。 */
    fun getActionSource(): IActionSource

    /**
     * 是否处于自动拉取模式（GTM 的 `IAutoPullPart#isAutoPull()`）。
     *
     * 库存列表要用它回答 GTM 配置面板的 `isAutoPull()`（只在 GUI 上用：autoPull 模式下配置槽是只读的显示位）。
     */
    fun isAutoPull(): Boolean

    /**
     * 「每次拉 N 个」的 N。**0 = 不限制**（默认，行为与 GTM 原版库存部件完全一致）。
     *
     * 语义是**本仓一次从网络里备多少**，不是「保底补到 N」——保底那件事 GTM 已经做了
     * （`IMEStockingPart#getMinStackSize` / `#setMinStackSize`，面板在
     * `IMEStockingPart#getPanelBuilder` 的 `stocking_settings` 弹窗里）。
     * 所以两者会互相影响：⚠️ 若 N 小于保底数量，本仓永远备不满（备出来的量达不到保底，`syncME` 会把 stock 清空）。
     */
    fun getBatchSize(): Int

    /** 换 N（0 = 不限制）。 */
    fun setBatchSize(size: Int)

    /**
     * 该 stack 是否已经配置在**同一个多方块里的另一个库存部件**上（GTM 的
     * `MEStockingBusPartMachine#testConfiguredInOtherPart` / `MEStockingHatchPartMachine` 同名方法，两者都是 public）。
     *
     * 库存列表要用它补上 GTM 自己那份列表的 `hasStackInConfig(stack, true)` 语义（见两件列表的覆写）：
     * GTM 的库存列表是 private 内部类，我们换掉了它，就得把这条语义一起搬过来，否则面板上
     * 「这个物品已经配置在别的库存件上了」的判断会静默失效。
     */
    fun testConfiguredInOtherPart(config: GenericStack?): Boolean

    /** [getBatchSize] 的 long 形式：0 翻成「无限」。 */
    fun batchLimit(): Long {
        val size = getBatchSize()
        return if (size <= 0) Long.MAX_VALUE else size.toLong()
    }

    /**
     * 「本件能不能被别的多方块占用」—— 就是 `MultiblockPartMachine#canShared(controller, substructureName)`
     * 的返回值来源。
     *
     * ⚠️ 8.0.0 的签名比老工程多了两个参数（MultiblockPartMachine.java:158），且基类默认返回
     * `true`（MultiblockPartMachine.java:157-160），所以不覆写就等于「随便共享」。
     *
     * ⚠️ 这是**整个部件一个**开关，不是「每一侧一个」：`canShared` 是机器级的一个方法，
     * 二合一那条总成虽然物品 / 流体各有一套标签与定量，共享开关也只有一个。
     *
     * ⚠️ 只在**结构检查那一刻**被读，所以这个值改了以后，已成型结构不会凭空变化 —— 见 [setCanBeShared] 的说明。
     */
    fun canBeShared(): Boolean

    /**
     * 拨动共享开关（true = 允许别的多方块占用本件，false = 隔离）。
     *
     * ⚠️ 实现方应当**在服务端**改值，并顺手让本件所属的每个多方块立刻复检一次结构
     * （`MultiblockControllerMachine#checkAndFormStructure()`，MultiblockControllerMachine.java）——
     * 否则玩家拨完开关要等下一次结构复检（周期检查 / 方块变化）才看得到效果。
     *
     * ⚠️ 与 [setBatchSize] 不同，这里的值**不能**由客户端自己写：它进存档，客户端改了会在下一次同步时被服务端覆盖。
     */
    fun setCanBeShared(shared: Boolean)
}
