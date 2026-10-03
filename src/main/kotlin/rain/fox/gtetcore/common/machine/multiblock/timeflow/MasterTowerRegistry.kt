package rain.fox.gtetcore.common.machine.multiblock.timeflow

import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import rain.fox.gtetcore.config.GtetConfig
import java.util.UUID
import java.util.function.BiFunction
import java.util.function.Supplier

/**
 * 主控塔「全服唯一」的记账处 —— **两套数据**：
 * - [snapshot]：进程内 `@Volatile` 的不可变快照，给成型判定与 [autoTower] 读；
 * - [MasterTowerSavedData]：主世界 `DataStorage`（存档里），**只在服务端线程**读写。
 *
 * 唯一性语义：
 * - `masterTowerUnique = false`：不设占位者，谁都能成型，成型过的坐标记进 [Snapshot.formedTowers]；
 * - `true`：先成型的那座占住 [Snapshot.uniqueTower]，没占位者时新塔仍可成型；
 * - **不追溯**：坐标只要在 [Snapshot.formedTowers] 里就永远放行（配置 false → true 时已有塔不碎）；
 * - 占位者在**控制器方块被拆掉**时释放（[onTowerControllerRemoved]），结构失效不释放。
 *
 * @author rain fox
 */
object MasterTowerRegistry {

    /**
     * 内存快照。
     *
     * @param uniqueTower 当前占住唯一名额的塔；`null` = 还没人占（或已释放）
     * @param formedTowers 曾经成型过的塔坐标（编码串，见 [encode]）；这些塔永远放行
     */
    data class Snapshot(val uniqueTower: GlobalPos?, val formedTowers: Set<String>) {

        /** 这个坐标现在能不能成型。 */
        fun allows(pos: GlobalPos): Boolean =
            formedTowers.contains(encode(pos)) || uniqueTower == null || uniqueTower == pos

        companion object {
            val EMPTY = Snapshot(null, emptySet())
        }
    }

    /** 当前快照；整体替换，读侧不加锁。 */
    @Volatile
    private var snapshot: Snapshot = Snapshot.EMPTY

    /** 已经把 [MasterTowerSavedData] 读进内存的那个服务器实例（只在服务端线程读写）。 */
    private var syncedServer: MinecraftServer? = null

    /** 只读快照。 */
    @JvmStatic
    fun snapshot(): Snapshot = snapshot

    /**
     * 「零操作自动连接」该连的那座塔（时序仓未显式绑定时用它）。
     * 多塔模式（`masterTowerUnique = false`）恒返回 `null` —— 那时必须显式绑定。
     *
     * ⚠️ 时刻可能变（塔成型 / 拆控制器都改写快照），每次现取，不要缓存。
     */
    @JvmStatic
    fun autoTower(): GlobalPos? =
        if (GtetConfig.masterTowerUnique()) snapshot().uniqueTower else null

    /** 把存档里的记账读进内存（**只在服务端线程**）；同一个服务器实例只读一次。 */
    @JvmStatic
    fun ensureLoaded(server: MinecraftServer) {
        if (syncedServer === server) return
        val data = MasterTowerSavedData.get(server)
        syncedServer = server
        snapshot = Snapshot(data.uniqueTower, HashSet(data.formedTowers))
    }

    /** 一座塔成型了（**服务端线程**）：记进「曾经成型过」名单，顺便占唯一名额（如果还空着）。 */
    @JvmStatic
    fun onTowerFormed(server: MinecraftServer, pos: GlobalPos) {
        ensureLoaded(server)
        val data = MasterTowerSavedData.get(server)
        data.formedTowers.add(encode(pos))
        if (data.uniqueTower == null) data.uniqueTower = pos
        data.setDirty(true)
        snapshot = Snapshot(data.uniqueTower, HashSet(data.formedTowers))
    }

    /**
     * 一座塔的**控制器方块被拆掉**了（**服务端线程**）：只有它是当前占位者时才释放名额。
     * 只敲掉一格机壳（结构失效但控制器还在）不释放。
     */
    @JvmStatic
    fun onTowerControllerRemoved(server: MinecraftServer, pos: GlobalPos) {
        ensureLoaded(server)
        val data = MasterTowerSavedData.get(server)
        if (data.uniqueTower == pos) {
            data.uniqueTower = null
            data.setDirty(true)
            snapshot = Snapshot(null, HashSet(data.formedTowers))
        }
    }

    /** 服务器停了：清空内存快照，免得单人游戏切存档时把上一个存档的记账带过去。 */
    @JvmStatic
    fun clearMemory() {
        syncedServer = null
        snapshot = Snapshot.EMPTY
    }

    /** 服务器起来：把记账读进内存。挂载点见 `CommonProxy.kotlinInit`（`NeoForge.EVENT_BUS.addListener`）。 */
    @JvmStatic
    fun onServerStarted(event: ServerStartedEvent) {
        ensureLoaded(event.server)
    }

    /** 服务器停下：清掉内存快照（见 [clearMemory]）。 */
    @JvmStatic
    fun onServerStopping(event: ServerStoppingEvent) {
        clearMemory()
    }

    // ======================== 坐标编解码 ========================

    /** 坐标 → 字符串（`维度|x|y|z`）。存进 NBT 的就是它。 */
    @JvmStatic
    fun encode(pos: GlobalPos): String =
        "${pos.dimension().location()}|${pos.pos().x}|${pos.pos().y}|${pos.pos().z}"

    /** 字符串 → 坐标；格式坏了返回 `null`（坏数据直接忽略，不让整份记账报废）。 */
    @JvmStatic
    fun decode(raw: String): GlobalPos? {
        val parts = raw.split('|')
        if (parts.size != 4) return null
        val dim = ResourceLocation.tryParse(parts[0]) ?: return null
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        val z = parts[3].toIntOrNull() ?: return null
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos(x, y, z))
    }
}

/**
 * 主控塔记账的存档载体 —— 挂在**主世界**的 `DataStorage` 上，所以是全服一份（跨维度）。
 *
 * ⚠️ 1.21 的 `SavedData` 与老工程 1.20.1 差两处（已对 1.21.1 联机 jar 核实）：`save` 多一个
 * `HolderLookup.Provider` 参数；取实例走 `computeIfAbsent(SavedData.Factory, name)`。
 */
class MasterTowerSavedData : SavedData() {

    /** 占住唯一名额的那座塔；`null` = 没人占。 */
    var uniqueTower: GlobalPos? = null

    /** 曾经成型过的塔坐标（编码串）；重启后靠它「不追溯」。 */
    val formedTowers: MutableSet<String> = LinkedHashSet()

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        uniqueTower?.let { tag.putString(KEY_UNIQUE, MasterTowerRegistry.encode(it)) }
        val list = ListTag()
        formedTowers.forEach { list.add(StringTag.valueOf(it)) }
        tag.put(KEY_FORMED, list)
        return tag
    }

    companion object {

        /** 存档键；改名 = 老存档的记账丢失。 */
        const val DATA_ID: String = "gtetscore_master_tower"

        private const val KEY_UNIQUE = "unique"
        private const val KEY_FORMED = "formed"

        /** `computeIfAbsent` 用的工厂：空构造 + NBT 还原。 */
        private val FACTORY: SavedData.Factory<MasterTowerSavedData> = SavedData.Factory(
            Supplier { MasterTowerSavedData() },
            BiFunction { tag: CompoundTag, _: HolderLookup.Provider -> load(tag) },
        )

        /** 从 NBT 还原；坏坐标静默跳过。 */
        @JvmStatic
        fun load(tag: CompoundTag): MasterTowerSavedData {
            val data = MasterTowerSavedData()
            if (tag.contains(KEY_UNIQUE)) {
                data.uniqueTower = MasterTowerRegistry.decode(tag.getString(KEY_UNIQUE))
            }
            val list = tag.getList(KEY_FORMED, Tag.TAG_STRING.toInt())
            for (i in 0 until list.size) {
                val decoded = MasterTowerRegistry.decode(list.getString(i)) ?: continue
                data.formedTowers.add(MasterTowerRegistry.encode(decoded))
            }
            return data
        }

        /** 取（或建）那份记账。**只在服务端线程调用**。 */
        @JvmStatic
        fun get(server: MinecraftServer): MasterTowerSavedData =
            server.overworld().dataStorage.computeIfAbsent(FACTORY, DATA_ID)
    }
}

/**
 * 主控塔白名单：配置里那份逗号分隔的 UUID 列表（所有者与同队判定在塔机器那边）。
 * 按配置原文缓存，配置热重载换了串就自动失效重解析。
 */
object MasterTowerWhitelist {

    private var cachedRaw: String? = null
    private var cached: Set<UUID> = emptySet()

    @JvmStatic
    @Synchronized
    fun contains(uuid: UUID): Boolean {
        val raw = GtetConfig.towerWhitelist()
        if (raw != cachedRaw) {
            cachedRaw = raw
            cached = raw.split(',')
                .mapNotNull { part ->
                    val trimmed = part.trim()
                    if (trimmed.isEmpty()) null else try {
                        UUID.fromString(trimmed)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
                .toSet()
        }
        return cached.contains(uuid)
    }
}
