package rain.fox.gtetcore.common.machine.multiblock.part

import com.gregtechceu.gtceu.api.GTValues
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo
import com.gregtechceu.gtceu.api.capability.recipe.IO
import com.gregtechceu.gtceu.api.machine.TickableSubscription
import com.gregtechceu.gtceu.api.machine.feature.IMuiMachine
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine
import com.gregtechceu.gtceu.api.machine.multiblock.part.TieredIOPartMachine
import com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableEnergyContainer
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient
import com.gregtechceu.gtceu.utils.ExtendedUseOnContext
import com.gregtechceu.gtceu.utils.FormattingUtil
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionResult
import rain.fox.gtetcore.api.timeflow.ETTimeFlow
import rain.fox.gtetcore.api.timeflow.ITimeFlowTower
import rain.fox.gtetcore.api.timeflow.TimeFlowTowers
import rain.fox.gtetcore.common.item.timeflow.TimeClockData
import rain.fox.gtetcore.common.machine.multiblock.timeflow.MasterTowerRegistry
import rain.fox.gtetcore.config.GtetConfig
import rain.fox.gtetcore.data.lang.WirelessEnergyHatchLang
import java.util.Locale
import java.util.function.IntSupplier
import java.util.function.LongSupplier
import java.util.function.Supplier

import brachy.modularui.api.drawable.Text
import brachy.modularui.factory.PosGuiData
import brachy.modularui.screen.UISettings
import brachy.modularui.value.sync.IntSyncValue
import brachy.modularui.value.sync.LongSyncValue
import brachy.modularui.value.sync.PanelSyncManager
import brachy.modularui.widget.ParentWidget
import brachy.modularui.widgets.layout.Flow

/**
 * 「无线能源仓」多方块部件 —— 把主控塔的**时间流（TF）换成本仓的 EU**。
 *
 * 每 tick 向绑定的塔要一笔 TF，塔把那笔 TF 折成 EU 还回来（换算只在塔里做，
 * [ITimeFlowTower.extractTimeFlowAsEu]），本仓再灌进自己的输入侧能量容器。
 *
 * 骨架对着 GTM 8.0.0 的 `EnergyHatchPartMachine.java` 抄：基类 `TieredIOPartMachine`（`IO.IN`）、
 * 容器用 `NotifiableEnergyContainer.receiverContainer(...)`（`:45-46`）、
 * 用 `attachTrait(...)` 把容器挂成 trait（`:29`）、容量口径 `V[tier] × 64L × amperage`（`:70-72`）。
 *
 * ⚠️ 「能被多方块聚合」靠的是容器**是这台机器的 trait**（`MultiblockPartMachine.java:118` 直接遍历
 * trait 表取 `IRecipeHandlerTrait`，**不看 `hasCapability`**）+ `handlerIO != IO.NONE`
 * （`NotifiableEnergyContainer.java:69-71`：电压 / 电流都为 0 才是 `IO.NONE`）+ 容器电压非 0
 * （多方块的 `getMaxVoltage()` 读它）。把外部能力关掉（`setCapabilityValidator { false }`）只挡
 * 外部电缆，聚合照旧 —— 无线仓的电只该来自塔。
 *
 * ⚠️ 本族**没有虚档位**：交给 GTM 的 `tier` 就是变体表那个真实档位，不存在「逻辑档位夹回 MAX」。
 *
 * ## 本期只做同维度
 * 跨维度要连接塔，还没实现，所以塔在别的维度时**一律不供电**（放行等于绕掉连接塔与它的跨维度损耗）。
 *
 * @param tier     电压档位（IV ~ MAX，真实档位）
 * @param amperage 安培档（1 / 16 / 64 / 256 … / 4194304）
 */
class WirelessEnergyHatchPartMachine(
    info: BlockEntityCreationInfo,
    tier: Int,
    val amperage: Int,
) : TieredIOPartMachine(info, tier, IO.IN), IMuiMachine {

    /**
     * 本仓缓冲容量（EU）= `V[tier] × 64 × amperage`（与 `EnergyHatchPartMachine.java:112-114` 同口径）。
     *
     * ⚠️ 必须全程 `Long`：`V[MAX] = 2147483647`，`× 64 × 4194304 ≈ 5.76E+17`，`Int` 中间量直接溢出。
     */
    val capacityEu: Long = GTValues.V[tier] * 64L * amperage.toLong()

    /**
     * 输入侧能量容器 —— 本仓唯一的功能性 trait，也是多方块取电的口子。
     *
     * 参数顺序：容量 / 最大输入电压 / 最大输入电流；电压必须给 `V[tier]`（多方块的 `getMaxVoltage()` 读它）。
     * `@SaveField` 标在这个字段上，sync 系统会**递归**进去存容器自己的 `energyStored`
     * （`NotifiableEnergyContainer.java:42-43`；GTM 自己的 `EnergyHatchPartMachine.java:21-22` 同款）。
     */
    @field:SaveField
    val energyContainer: NotifiableEnergyContainer = attachTrait(
        // ⚠️ 8.0.0 的 `receiverContainer` 是**静态三参**重载，不收 machine（旧版收）：机器由 `attachTrait` 绑
        //    （`NotifiableEnergyContainer.java:79-82` / `EnergyHatchPartMachine.java:29,45`）。
        NotifiableEnergyContainer.receiverContainer(capacityEu, GTValues.V[tier], amperage.toLong())
    ).apply {
        // 只关外部电缆（见类注释），不影响多方块聚合
        setCapabilityValidator { false }
    }

    /** 每 tick 的取电订阅（未订阅状态见 [onUnload]）。 */
    private var tickSubscription: TickableSubscription? = null

    // ================================================================
    //  绑定（维度 + 坐标；与 TimeFlowHatchPartMachine 同型）
    // ================================================================

    /** 绑定塔所在维度（`minecraft:overworld` 这种）；空串 = 未绑定。 */
    @field:SaveField
    @field:SyncToClient
    var boundDim: String = ""
        private set

    /** 绑定塔的 X 坐标。 */
    @field:SaveField
    @field:SyncToClient
    var boundX: Int = 0
        private set

    /** 绑定塔的 Y 坐标。 */
    @field:SaveField
    @field:SyncToClient
    var boundY: Int = 0
        private set

    /** 绑定塔的 Z 坐标。 */
    @field:SaveField
    @field:SyncToClient
    var boundZ: Int = 0
        private set

    /** 是否已显式绑定一座塔（与「能不能取电」不是一回事，后者还要看唯一塔自动连接）。 */
    val isBound: Boolean get() = boundDim.isNotEmpty()

    /** 读回绑定好的塔；未绑定 / 维度串坏了返回 `null`。 */
    fun getBoundTower(): GlobalPos? {
        if (!isBound) return null
        val dim = ResourceLocation.tryParse(boundDim) ?: return null
        return GlobalPos.of(
            ResourceKey.create(Registries.DIMENSION, dim),
            BlockPos(boundX, boundY, boundZ)
        )
    }

    /** 绑定到一座塔；返回是否真的改了（重复绑同一座塔返回 `false`）。 */
    fun bindTower(tower: GlobalPos): Boolean {
        val dim = tower.dimension().location().toString()
        val pos = tower.pos()
        if (dim == boundDim && pos.x == boundX && pos.y == boundY && pos.z == boundZ) return false
        boundDim = dim
        boundX = pos.x
        boundY = pos.y
        boundZ = pos.z
        markBindingDirty()
        return true
    }

    /** 解绑；返回是否真的改了。 */
    fun unbindTower(): Boolean {
        if (!isBound) return false
        boundDim = ""
        boundX = 0
        boundY = 0
        boundZ = 0
        markBindingDirty()
        return true
    }

    /**
     * ⚠️ `@field:SyncToClient` 只是「允许同步」，**不会**自己发现变化：值改完必须显式
     * `markClientSyncFieldDirty(字段名)`，否则客户端一直看旧值（与 `TimeFlowHatchPartMachine` 同款）。
     */
    private fun markBindingDirty() {
        syncDataHolder.markClientSyncFieldDirty("boundDim")
        syncDataHolder.markClientSyncFieldDirty("boundX")
        syncDataHolder.markClientSyncFieldDirty("boundY")
        syncDataHolder.markClientSyncFieldDirty("boundZ")
    }

    /**
     * 本仓这一 tick 该去哪座塔取电：**显式绑定优先**，没绑定时回落到全服唯一的那座塔
     * （[MasterTowerRegistry.autoTower]；多塔模式下它恒 `null`，那时必须显式绑定）。
     */
    fun resolveTowerPos(): GlobalPos? = getBoundTower() ?: MasterTowerRegistry.autoTower()

    // ================================================================
    //  损耗（基础按档位 2% ~ 8% + 距离税）
    // ================================================================

    /**
     * 基础损耗：从 IV 的 8% 逐档线性降到 MAX 的 2%（两个端点写成字面量，档位值即 `GTValues.IV` / `MAX`）。
     */
    private fun baseLoss(tier: Int): Double {
        val span = (BASE_LOSS_LOW_TIER - BASE_LOSS_HIGH_TIER).toDouble()
        val fromHigh = (BASE_LOSS_LOW_TIER - tier).toDouble()
        return BASE_LOSS_HIGH + (BASE_LOSS_LOW - BASE_LOSS_HIGH) * (fromHigh / span)
    }

    /**
     * 距离税：免计 128 格、4096 格封顶、系数上限 +15%。
     *
     * ⚠️ 分母取 `4096 − 128` 而不是 `4096`：这样 `d = 4096` 时距离项**正好** +15%。
     * ⚠️ 距离用**切比雪夫距离**（三轴差值取最大），不是欧氏距离 —— 免计距离写的是「8 区块」，
     * 区块本身就是方形口径。
     */
    private fun distanceLoss(from: BlockPos, to: BlockPos): Double {
        val distance = chebyshev(from, to).toDouble()
        val billed = (distance - DISTANCE_FREE).coerceIn(0.0, DISTANCE_BILLABLE)
        return billed / DISTANCE_BILLABLE * DISTANCE_LOSS_MAX
    }

    private fun chebyshev(from: BlockPos, to: BlockPos): Int = maxOf(
        kotlin.math.abs(from.x - to.x),
        kotlin.math.abs(from.y - to.y),
        kotlin.math.abs(from.z - to.z),
    )

    /** 本仓到 [towerPos] 的总损耗（基础 + 距离），夹到 `[0, MAX_LOSS]` 免得后面当除数时炸掉。 */
    private fun lossAt(towerPos: BlockPos): Double =
        (baseLoss(tier) + distanceLoss(blockPos, towerPos)).coerceIn(0.0, MAX_LOSS)

    // ================================================================
    //  每 tick 取电
    // ================================================================

    /**
     * 一 tick 的取电：**拿 TF 去塔里换 EU，再灌进自己的缓冲**。
     *
     * 顺序（每一步都别换）：
     * 1. 客户端 / 满仓 / 上限为 0 ⇒ 直接返回；
     * 2. **空转保护**：没有正在干活的控制器、且缓冲已经够跑一 tick ⇒ 一点 TF 都不换
     *    （换汇不可逆，闲着也把缓冲顶满等于强迫玩家消费，还会把塔侧储备抽干）；
     * 3. 找塔 → 维度不同 / 塔不在 / 没成型 / 没权限 ⇒ 不取电（原因在 [resolveLink]，面板里会写出来）；
     * 4. **先算该扣多少 TF**：`wantTf = ceil(wantEu / (EU_PER_TF × (1 − loss)))`，向上取整保证覆盖得住损耗；
     * 5. 向塔要：[ITimeFlowTower.extractTimeFlowAsEu] 返回**真实到账的 EU**，塔里 TF 不够时有少给少、
     *    **不能假设取满**；
     * 6. 按真实到账量反算并灌入：`delivered = floor(gotEu × (1 − loss))` → `changeEnergy(+delivered)`。
     *
     * ⚠️ 这样走下来**不会出现「扣了不足 1 TF 的零头」**：TF 侧整数向上取整，EU 侧从实际到账量反算，
     * 零头留在塔里、下一 tick 继续算。
     */
    private fun pullTick() {
        if (isRemote) return
        val lvl = level ?: return
        if (lvl.isClientSide) return

        val stored = energyContainer.energyStored
        val room = energyContainer.energyCapacity - stored
        if (room <= 0L) return

        val perTickEu = throughputEu()
        if (perTickEu <= 0L) return

        // 空转保护：不干活、而且缓冲够跑一 tick ⇒ 这一 tick 一点 TF 都不换
        if (!hasWorkingController() && stored >= perTickEu) return

        val link = resolveLink()
        val tower = link.tower ?: return
        val towerPos = link.towerPos ?: return

        val wantEu = minOf(room, perTickEu)
        if (wantEu <= 0L) return

        val loss = lossAt(towerPos)
        val euPerTf = euPerTfAfterLoss(loss)
        if (euPerTf <= 0L) return

        val wantTf = ceilDiv(wantEu, euPerTf)
        if (wantTf <= 0L) return

        val gotEu = tower.extractTimeFlowAsEu(wantTf)
        if (gotEu <= 0L) return

        val delivered = applyLoss(gotEu, loss)
        if (delivered <= 0L) return

        energyContainer.changeEnergy(delivered)
    }

    /** 本档一 tick 的通过上限（EU）= `V[tier] × amperage`；也是「低于多少就算缺电」那条线。 */
    private fun throughputEu(): Long = GTValues.V[tier] * amperage.toLong()

    /**
     * 每个 TF 扣掉损耗后**实际到账**多少 EU（向下取整，至少 1）。
     *
     * 损耗用**百万分之一（ppm）的整数**表达再参与整除，避免浮点参与取整判定；
     * 整除截断让每 TF 的到账量偏小 ⇒ `wantTf` 偏大一点点，宁可多要一丁点也不让仓拿不够电。
     */
    private fun euPerTfAfterLoss(loss: Double): Long {
        val ppm = (loss * PPM).toLong().coerceIn(0L, PPM - 1L)
        return (ETTimeFlow.EU_PER_TF * (PPM - ppm)) / PPM
    }

    /** `value × (1 − loss)` 向下取整。走 Double 只是为了不溢出（`value` 可以到 9E+15）。 */
    private fun applyLoss(value: Long, loss: Double): Long {
        if (value <= 0L) return 0L
        return Math.floor(value.toDouble() * (1.0 - loss)).toLong().coerceAtLeast(0L)
    }

    /** 向上取整的整数除法（`b > 0`）。 */
    private fun ceilDiv(a: Long, b: Long): Long = if (a <= 0L) 0L else (a + b - 1L) / b

    /**
     * 本仓现在有没有「正在干活」的控制器（空转保护的第 ① 条）。
     *
     * 「在干活」= 已成型 **且** 配方逻辑处于 `WORKING`（`RecipeLogic.java:526`）。只看 `isFormed`
     * 不够 —— 成型但闲置的结构每 tick 都会让本仓去换电，那正是空转耗电。
     */
    private fun hasWorkingController(): Boolean {
        for (controller in controllers) {
            if (!controller.isFormed) continue
            val workable = controller as? WorkableMultiblockMachine ?: continue
            if (workable.recipeLogic.isWorking) return true
        }
        return false
    }

    // ================================================================
    //  链接状态（tick 与面板共用同一份判定，免得两边说法不一致）
    // ================================================================

    /** 本仓与塔的连接状态；[LinkState.OK] 之外一律不取电，且每一种都在面板里有对应文案。 */
    enum class LinkState { OK, UNBOUND, WRONG_DIMENSION, NO_TOWER, NO_PERMISSION }

    /** [resolveLink] 的结果：状态 + 塔坐标 + 塔本身（只有 OK 时塔非空）。 */
    private data class LinkInfo(val state: LinkState, val towerPos: BlockPos?, val tower: ITimeFlowTower?)

    /**
     * 把「本仓现在能不能从塔取电」一次性判完（**无副作用**；tick 与面板都调它）。
     *
     * 判定顺序（每一步都对应面板里一条提示）：找不到目标塔 ⇒ 未接线；塔在别的维度 ⇒ 不供电；
     * 塔不在（区块未加载 / 那一格不是塔，[TimeFlowTowers.find] 不强加载）或**没成型** ⇒ 不供电；
     * 放置者没有取用权限 ⇒ 不供电。
     *
     * ⚠️ 权限按**放置者**判：tick 时没有 `Player`，只有 [MetaMachine.getOwnerUUID] 记下的 ownerUUID。
     * ⚠️ 「成型」在 8.0.0 是 `MultiblockControllerMachine#isFormed`（`IMultiController` 已删）。
     */
    private fun resolveLink(): LinkInfo {
        val lvl = level ?: return LinkInfo(LinkState.NO_TOWER, null, null)
        val target = resolveTowerPos() ?: return LinkInfo(LinkState.UNBOUND, null, null)
        if (target.dimension() != lvl.dimension()) {
            return LinkInfo(LinkState.WRONG_DIMENSION, target.pos(), null)
        }
        val tower = TimeFlowTowers.find(lvl, target.pos())
            ?: return LinkInfo(LinkState.NO_TOWER, target.pos(), null)
        if ((tower as? MultiblockControllerMachine)?.isFormed != true) {
            return LinkInfo(LinkState.NO_TOWER, target.pos(), null)
        }
        val owner = ownerUUID
        if (owner != null && !tower.canUseTimeFlowByUuid(owner)) {
            return LinkInfo(LinkState.NO_PERMISSION, target.pos(), null)
        }
        return LinkInfo(LinkState.OK, target.pos(), tower)
    }

    // ================================================================
    //  绑定手势（手持已绑塔的时序钟右键本仓）
    // ================================================================

    /**
     * GTM 的机器右键钩子。
     *
     * ⚠️ 8.0.0 删掉了 `IInteractedMachine`，对应物是 `MetaMachine` 上的 `onUseWithItem` /
     * `onUse`（`MetaMachine.java:532,557`）；两者都排在 `tryOpenUI(...)` **之前**，
     * 返回非 `PASS` 就直接 return，所以这个手势不会被部件自己的 MUI 面板吃掉。
     * 时序钟是**物品** ⇒ 走 `onUseWithItem`。
     *
     * ⚠️ `ExtendedUseOnContext#getPlayer()` 在 Java 侧是 `@UnknownNullability`，Kotlin 得按可空处理。
     *
     * 手势：手里的时序钟**已绑塔** → 非潜行 = 本仓绑到那座塔、潜行 = 解绑；
     * 钟没绑塔 / 塔在别的维度 ⇒ 只发提示、右键交还给方块（`PASS`）；手里不是时序钟 ⇒ 原样交给 `super`。
     */
    override fun onUseWithItem(context: ExtendedUseOnContext): InteractionResult {
        val stack = context.itemInHand
        if (!TimeClockData.isTimeClock(stack)) return super.onUseWithItem(context)

        val player = context.player ?: return super.onUseWithItem(context)
        val lvl = context.level
        if (!lvl.isClientSide) {
            val tower = TimeClockData.getBoundTower(stack)
            if (tower == null) {
                player.displayClientMessage(
                    Component.translatable(WirelessEnergyHatchLang.CLOCK_UNBOUND).withStyle(ChatFormatting.RED), true
                )
                return InteractionResult.PASS
            }
            if (!player.isShiftKeyDown && tower.dimension() != lvl.dimension()) {
                // 本仓本期只做同维度供电，跨维度的绑定先拦下来（免得绑了却永远拉不到电）
                player.displayClientMessage(
                    Component.translatable(WirelessEnergyHatchLang.WRONG_DIMENSION).withStyle(ChatFormatting.RED), true
                )
                return InteractionResult.PASS
            }

            if (player.isShiftKeyDown) {
                unbindTower()
                player.displayClientMessage(Component.translatable(WirelessEnergyHatchLang.UNBOUND), true)
            } else {
                bindTower(tower)
                val p = tower.pos()
                player.displayClientMessage(
                    Component.translatable(WirelessEnergyHatchLang.BOUND, p.x, p.y, p.z), true
                )
            }
        }
        // 两端都返回「已消耗」，免得客户端再跑一遍同样的手势
        return InteractionResult.sidedSuccess(lvl.isClientSide)
    }

    // ================================================================
    //  面板（MUI）
    // ================================================================

    /**
     * 部件面板：名字 + 缓冲 / 容量 / 本档上限 + 连接状态行。
     *
     * ⚠️ 老工程是往**控制器面板**里追加文本（`IMultiPart#addMultiText`）；8.0.0 把 `IMultiPart` /
     * `IDisplayUIMachine` / `addMultiText` **整条链都删了**（GTM 自己的 `LargeTurbineMachine.java:121-122`
     * 也把那一段注释掉了），没有对应物 ⇒ 改成部件自己的 MUI 面板（与 `TimeFlowHatchPartMachine` 同型）。
     *
     * ⚠️ **只有服务端才知道**的三个量（缓冲存量 / 连接状态 / 塔坐标）各挂一个同步取值控件
     * （写法同 `MasterTowerMachine` 的 `getOrCreateSyncHandler`）；容量、本档上限、损耗都是
     * `tier` / `amperage` / `blockPos` 的纯函数，两端算得出同一个值，不额外发包。
     */
    override fun buildMainUI(
        mainWidget: ParentWidget<*>,
        guiData: PosGuiData,
        syncManager: PanelSyncManager,
        settings: UISettings,
    ) {
        val stored = syncManager.getOrCreateSyncHandler(
            "wirelessEnergyStored", LongSyncValue::class.java,
            Supplier { LongSyncValue(LongSupplier { energyContainer.energyStored }) },
        )
        val state = syncManager.getOrCreateSyncHandler(
            "wirelessEnergyLinkState", IntSyncValue::class.java,
            Supplier { IntSyncValue(IntSupplier { resolveLink().state.ordinal }) },
        )
        val towerPos = syncManager.getOrCreateSyncHandler(
            "wirelessEnergyTowerPos", LongSyncValue::class.java,
            Supplier { LongSyncValue(LongSupplier { resolveLink().towerPos?.asLong() ?: NO_TOWER_POS }) },
        )

        mainWidget.child(
            Flow.column()
                .coverChildren()
                .child(Text.lang("block.gtetscore.${definition.name}").asWidget().margin(4))
                .child(
                    Text.dynamic(Supplier {
                        Component.translatable(
                            WirelessEnergyHatchLang.PANEL_BUFFER,
                            num(stored.longValue),
                            num(capacityEu),
                            num(throughputEu()),
                        ).withStyle(ChatFormatting.AQUA)
                    }).asWidget().margin(4)
                )
                .child(
                    Text.dynamic(Supplier { linkLine(state.intValue, towerPos.longValue) })
                        .asWidget().margin(4)
                )
        )
    }

    /** 面板里的连接状态行：OK 时列出塔坐标 / 距离 / 损耗明细，其余四种各显示一条「为什么不供电」。 */
    private fun linkLine(stateOrdinal: Int, packedTowerPos: Long): Component {
        val state = LinkState.entries.getOrNull(stateOrdinal) ?: LinkState.UNBOUND
        if (state != LinkState.OK) {
            val key = when (state) {
                LinkState.UNBOUND -> WirelessEnergyHatchLang.PANEL_UNBOUND
                LinkState.WRONG_DIMENSION -> WirelessEnergyHatchLang.PANEL_WRONG_DIMENSION
                LinkState.NO_TOWER -> WirelessEnergyHatchLang.PANEL_NO_TOWER
                LinkState.NO_PERMISSION -> WirelessEnergyHatchLang.PANEL_NO_PERMISSION
                LinkState.OK -> return Component.empty()
            }
            return Component.translatable(key).withStyle(ChatFormatting.RED)
        }
        val tower = BlockPos.of(packedTowerPos)
        return Component.translatable(
            WirelessEnergyHatchLang.PANEL_LINK,
            tower.x.toString(), tower.y.toString(), tower.z.toString(),
            chebyshev(blockPos, tower).toString(),
            percent(lossAt(tower)),
            percent(baseLoss(tier)),
            percent(distanceLoss(blockPos, tower)),
        ).withStyle(ChatFormatting.GRAY)
    }

    private fun num(value: Long): String = FormattingUtil.formatNumbers(value)

    /** 损耗打成一个百分数（两位小数、固定小数点，免得不同语言环境打出逗号）。 */
    private fun percent(value: Double): String = String.format(Locale.ROOT, "%.2f", value * 100.0)

    /**
     * 部件共享闸门：返回全局开关 [GtetConfig.partsShareable]（默认 `false` = 禁止共享）。
     *
     * 打开配置就恢复串用电风险：本仓的缓冲与绑定的塔都是每件独立的，被两个已成型结构共享时
     * 两个控制器会同时从同一份缓冲里取电。
     * ⚠️ 8.0.0 的签名比老工程多了两个参数（`MultiblockPartMachine.java:158`）；该值只在结构检测
     * 那一刻被读，改配置后要等下一次结构检测才生效。
     */
    override fun canShared(controller: MultiblockControllerMachine?, substructureName: String?): Boolean =
        GtetConfig.partsShareable()

    // ================================================================
    //  生命周期
    // ================================================================

    /**
     * 建立 tick 订阅（客户端不订阅）。
     *
     * ⚠️ **不用** `ConditionalSubscriptionHandler`：它的条件只在 `updateSubscription()` 被显式调到时
     * 才重算，而本仓的取电前提会在**我们收不到通知**的时刻变化（唯一塔被建起 / 结构被打散），
     * 条件卡在 `false` 就再也不会翻回来。老老实实每 tick 调一次 [pullTick]，它开头只是几次整数比较。
     *
     * ⚠️ `super` 必须指名：本类多实现了一个 [IMuiMachine]，它的上游接口链里也有 `onLoad()` 的
     * 接口 default，不指名会报 `Multiple supertypes available`。
     */
    override fun onLoad() {
        super<TieredIOPartMachine>.onLoad()
        if (isRemote) return
        tickSubscription = subscribeServerTick { pullTick() }
    }

    /** 卸载时退订，免得区块滚出视野后还在吃 tick。 */
    override fun onUnload() {
        unsubscribe(tickSubscription)
        tickSubscription = null
        super.onUnload()
    }

    // ⚠️ 8.0.0 没有 `getFieldHolder()` / `MANAGED_FIELD_HOLDER` 了（LDLib syncdata 已弃用）：
    //    存档范围完全由 `@SaveField` 决定，不需要老工程那套「把本类挂到父类字段持有者后面」的样板。

    companion object {

        /** 基础损耗低端档位 = `GTValues.IV`；写成字面量是因为 GTM 的静态常量在 Kotlin `const` 里用不了。 */
        private const val BASE_LOSS_LOW_TIER: Int = 5

        /** 基础损耗高端档位 = `GTValues.MAX`。 */
        private const val BASE_LOSS_HIGH_TIER: Int = 14

        /** 基础损耗在 IV 的取值：8%。 */
        private const val BASE_LOSS_LOW: Double = 0.08

        /** 基础损耗在 MAX 的取值：2%。 */
        private const val BASE_LOSS_HIGH: Double = 0.02

        /** 距离税的免计距离：128 格 = 8 区块。 */
        private const val DISTANCE_FREE: Double = 128.0

        /** 距离税的封顶距离：4096 格。 */
        private const val DISTANCE_CAP: Double = 4096.0

        /** 距离项分母 = `封顶距离 − 免计距离`，让 `d = 4096` 时正好爬到系数上限（见 [distanceLoss]）。 */
        private const val DISTANCE_BILLABLE: Double = DISTANCE_CAP - DISTANCE_FREE

        /** 距离系数上限：+15%。 */
        private const val DISTANCE_LOSS_MAX: Double = 0.15

        /** 总损耗硬上限（基础损耗与距离税之和再兜一道），也是 [applyLoss] 的分母下限。 */
        private const val MAX_LOSS: Double = 0.999999

        /** 百万分之一，把损耗变成整数再参与整除（见 [euPerTfAfterLoss]）。 */
        private const val PPM: Long = 1_000_000L

        /** 面板同步用的「没有塔」哨兵：只在状态不是 OK 时出现，不会被解码成坐标。 */
        private const val NO_TOWER_POS: Long = Long.MIN_VALUE
    }
}
