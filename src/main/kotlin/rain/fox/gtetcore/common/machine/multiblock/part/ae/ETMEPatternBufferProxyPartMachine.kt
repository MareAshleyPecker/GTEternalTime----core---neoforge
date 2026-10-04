package rain.fox.gtetcore.common.machine.multiblock.part.ae

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferProxyPartMachine
import net.minecraft.MethodsReturnNonnullByDefault
import rain.fox.gtetcore.config.GtetConfig
import javax.annotation.ParametersAreNonnullByDefault

/**
 * 「ME 样板总成镜像」：贴在多方块里的代理部件，把配方输入转发到别处的
 * [ETMEPatternBufferPartMachine]。
 *
 * ## 8.0.0 里本类只剩一件事
 *
 * 老工程那 249 行的镜像类 + 480 行的 `ETProxySlotRecipeHandler`，做的事情是「照着 GTM 镜像的形状
 * 自己写一份」，因为 7.5.3 的 GTM 镜像有两个死穴：
 * 1. 构造器把槽位数写死成 `MEPatternBufferPartMachine.MAX_PATTERN_COUNT`（27），而我们的宿主是
 *    216 格；槽级代理表必须在**构造期**定长（多方块只在成型那一刻收集一次部件处理器表），
 *    所以只能自己写一张「按已登记最大容量建死」的表；
 * 2. 它转发时点名 `InternalSlotRecipeHandler.SlotRHL`（protected 嵌套类，跨包不可见）。
 *
 * **8.0.0 把这两个死穴都填了**（上游源码 `ProxySlotRecipeHandler.java`，与 jar 逐字对应）：
 * - 整个代理表只剩**一条** `ProxyRHL`（`this.proxySlotHandlers = List.of(proxyRHL)`），
 *   没有「每槽一条」这回事，也就没有「表长必须按最大容量建死」的取舍；
 * - 那条 RHL 的 `handleRecipe` 把**整张 contents map** 直接转交给
 *   `buffer.getBufferRecipeHandler().handleRecipe(io, recipe, contents, simulate)`，
 *   槽级转发下沉进总成自己的 `BufferRecipeHandlerList`（它自己遍历 `workers`）；
 * - 它认宿主用的是 `instanceof MEPatternBufferPartMachine`（上游 79 行），而我们的总成**就是**
 *   它的子类，所以它天然认得。
 *
 * 于是本类只需继承 GTM 的 [MEPatternBufferProxyPartMachine]，唯一补的是共享闸门
 * [canShared]（基类默认 `true` = 随便共享）。**镜像能连任何一档宿主**这件事在 8.0.0 里是白来的：
 * 转发逻辑完全在宿主那一侧，与镜像无关。
 *
 * ## 用法（与 GTM 的 `me_pattern_buffer_proxy` 完全同一套）
 * 用**闪存**对着样板总成右键（总成的 `onDataStickShiftUse` 会把坐标写进闪存的
 * `gtceu:data_copy_pos` 数据组件），再对着镜像右键绑定；之后多方块从镜像这里拿输入，
 * 实际数据在总成里。一个总成可以挂多个镜像（各机器共享同一批样板）。
 *
 * ## 已知的（仅外观）缺口
 * GTM 的 Jade 插件用 `instanceof MEPatternBufferProxyPartMachine` 判断镜像，我们**继承**了它，
 * 所以镜像上的 Jade 提示能正常显示；但总成那边数「已连接镜像数」用的是
 * `getProxies()`（`Set<MEPatternBufferProxyPartMachine>`，8.0.0 里是 `ReferenceOpenHashSet`），
 * 我们的镜像也在其中 —— 这一条在 8.0.0 反而是通的。
 *
 * @author rain fox
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
class ETMEPatternBufferProxyPartMachine(info: BlockEntityCreationInfo) : MEPatternBufferProxyPartMachine(info) {

    /**
     * **仓室隔离**：禁止这一件镜像被两个多方块同时占用（防串配方）。
     *
     * 镜像是**装在消费侧多方块里**的那一件（`getRecipeHandlers()` 把配方输入转发给宿主总成的
     * 库存）。所以"共享"在这里的含义是：同一格镜像方块同时算作两个已成型结构的一部分 ——
     * 两个控制器从**同一条**转发链（也就是宿主总成里那同一批库存槽）取料。跟总成那边一样，
     * 谁先跑谁吃掉，这就是串配方。
     *
     * 「一个宿主总成挂多个镜像、多个机器各用各的镜像」这条**主要用法完全不受影响**：
     * 每台机器的镜像都是各自那一格。这道闸门只在「同一格属于两个已成型结构」时才触发
     * （`BlockPattern#checkPatternAt` 那一刻读一次）。
     *
     * **闸门由配置 `multiblock.partsShareable` 兜底**（默认 false = 仍然隔离）：配置打开后同一格
     * 镜像可同时算作两个已成型结构的一部分，上面那条串配方风险重新出现。
     */
    override fun canShared(controller: MultiblockControllerMachine?, substructureName: String?): Boolean =
        GtetConfig.partsShareable()
}
