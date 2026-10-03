package rain.fox.gtetcore.common.machine.multiblock.modular

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import java.util.Collections
import java.util.WeakHashMap

/**
 * 模块化多方块的**无线对接表**（GTET 版的 `IIWirelessInteractor`）。
 *
 * 主机成型时登记、失效/卸载时注销；单元按「同维度 + 距离」找最近的一台。
 *
 * ⚠️ 三处生命周期必须成对（成型登记、失效注销、卸载注销），漏一处就会在表里留悬空引用，
 * 之后每台单元都要白测一遍。只在服务端线程访问，所以不做并发保护。
 *
 * @author rain fox
 */
object ETModuleNetwork {

    /** 已成型的主机（弱引用：机器被回收时自己掉出去）。 */
    private val hosts: MutableSet<ETModuleHostMachine> = Collections.newSetFromMap(WeakHashMap())

    @JvmStatic
    fun addHost(host: ETModuleHostMachine) {
        hosts.add(host)
    }

    @JvmStatic
    fun removeHost(host: ETModuleHostMachine) {
        hosts.remove(host)
    }

    /** 在 [range] 格内找一台已成型的主机；同距离取先登记的，找不到返回 null。 */
    @JvmStatic
    fun findHost(level: Level, pos: BlockPos, range: Int): ETModuleHostMachine? {
        var best: ETModuleHostMachine? = null
        var bestDist = Double.MAX_VALUE
        val rangeSqr = range.toDouble() * range
        for (host in hosts) {
            if (!host.isFormed || host.level !== level) continue
            val dist = host.blockPos.distSqr(pos)
            if (dist > rangeSqr) continue
            if (dist < bestDist) {
                bestDist = dist
                best = host
            }
        }
        return best
    }
}
