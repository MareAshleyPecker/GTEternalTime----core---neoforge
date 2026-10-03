package rain.fox.gtetcore.api.timeflow

import com.gregtechceu.gtceu.api.machine.MetaMachine
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * 主控塔侧的 TF 取用契约 —— **塔本期不做**，这里只把接口与字段留好。
 *
 * 时序钟的两条扣费规则都落在它身上（设定 §5）：
 * - **同维度**：直接从绑定的塔扣，钟内 TF 不动；要求塔所在区块已加载（不做强加载）；
 * - **跨维度**：只能用钟内 TF。
 *
 * 所有者 / 白名单（设定 §6.1）也在这里：[getOwnerUuid] 与 [canUseTimeFlow]。
 * 塔只有 `masterTowerUnique = false` 时才会同时存在多座，**每座各自独立记账**，
 * 所以接口是按「一座塔」定义的，不是一个全服共享池。
 */
interface ITimeFlowTower : ITimeFlowStorage {

    /** 塔的所有者；未设置时返回 `null`。 */
    fun getOwnerUuid(): UUID?

    /**
     * 这个 UUID 是否有权从这座塔取用 TF —— **本人 / 白名单 / 同队**（设定 §2.4）。
     *
     * ## 为什么要有「按 UUID」这一版
     * [canUseTimeFlow] 是**玩家入口**（时序钟右键塔时手边就有个 `Player`）。
     * 但仓是**每 tick 取电**的，那一刻没有任何玩家在场，手上只有一个「放置者 UUID」。
     * 于是权限判定必须能在**没有 Player 实例**的情况下做，这一版就是为它准备的：
     * 仓在 tick 里拿自己的放置者 UUID 来这里问，语义与玩家版完全一致。
     *
     * ## 默认实现
     * - 塔还没认领（`getOwnerUuid() == null`）⇒ 放行（与 [canUseTimeFlow] 的旧行为一致）；
     * - 本人 ⇒ 放行；
     * - 同队 ⇒ 放行（**本切片留桩**，见下）。
     *
     * **白名单不在这里**：它对每座塔是不同的（`MasterTowerWhitelist` 是塔实现自己的东西），
     * 所以留给塔 override 时叠加 —— 见 `MasterTowerMachine` 的同名覆写。
     */
    fun canUseTimeFlowByUuid(uuid: UUID): Boolean {
        val owner = getOwnerUuid() ?: return true
        if (owner == uuid) return true
        // TODO(主塔切片)：老工程这里走 `ETTeamAccess.sameTeam(owner, uuid)` ——
        //  装了 FTB Teams 且同队即视为同一人。FTB Teams 在本工程是**软依赖**（build.gradle 里还没接），
        //  所以本切片恒返回 false；主塔切片落地时把 `ETTeamAccess`（含它的「没装就不加载」两层惰性写法）
        //  一起搬过来，这里换成那一句即可。塔没做之前这个默认实现没有任何调用方。
        return false
    }

    /** 这位玩家是否有权从这座塔取用 TF（所有者 / 白名单 / 同队）。 */
    fun canUseTimeFlow(player: Player): Boolean = canUseTimeFlowByUuid(player.uuid)

    /**
     * 从塔取出 [tf] TF，并把**实际取到的**那一部分折成 EU 返回。
     *
     * ## 为什么折算放在塔这一侧
     * 设定 §3.1：**换算只在主控塔里发生**（塔是唯一闸口）。仓只负责「该扣多少 TF」的算术，
     * 至于「扣到的这些 TF 值多少 EU」由塔说了算 —— 潮汐汇率（§3.2）将来就是在这条线上加乘子，
     * 仓一行都不用改。
     *
     * ## 契约
     * - 入参 `tf <= 0` ⇒ 返回 0，**不碰存储**；
     * - 返回值是**真实到账的 EU**：塔里的 TF 不够时按 [ITimeFlowStorage.extractTimeFlow] 的语义
     *   「有多少给多少」，所以返回值可能小于 `tf` 折算出来的量 —— 调用方必须按返回值算账，
     *   不能假设一定取满（这正是「别出现扣了不足 1 TF 的零头」那条约束的落点）。
     */
    fun extractTimeFlowAsEu(tf: Long): Long {
        if (tf <= 0L) return 0L
        return ETTimeFlow.tfToEu(extractTimeFlow(tf))
    }

    /** 余额够不够扣 [amount] TF。 */
    fun hasTimeFlow(amount: Long): Boolean = amount <= 0L || getTimeFlow() >= amount
}

/**
 * 从世界里的方块位置找 **[ITimeFlowTower]**。
 *
 * 现在是**真的查**（走 [MetaMachine.getMachine]），只是还没有任何机器实现 [ITimeFlowTower]，
 * 所以恒返回 `null`。塔落地后只要让塔机器实现 [ITimeFlowTower]，这里不用改。
 *
 * ⚠️ 先查 [Level.isLoaded]：未加载的区块上取机器会触发同步加载（设定要求「塔所在区块已加载」，不破例做强加载）。
 */
object TimeFlowTowers {

    /** 取该位置的塔；不是塔 / 区块未加载 / 机器不存在都返回 `null`。 */
    @JvmStatic
    fun find(level: Level, pos: BlockPos): ITimeFlowTower? {
        if (!level.isLoaded(pos)) return null
        return MetaMachine.getMachine(level, pos) as? ITimeFlowTower
    }
}
