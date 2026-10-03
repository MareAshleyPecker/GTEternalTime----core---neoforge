package rain.fox.gtetcore.test

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI
import com.lowdragmc.lowdraglib2.gui.ui.UI
import com.lowdragmc.lowdraglib2.gui.ui.UIElement
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites
import dev.vfyjxf.taffy.style.AlignContent
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * LDLib2 冒烟道具（S1）：验证 LDLib2 与 MUI 共存、界面开得起来。S1 过了就删。
 *
 * LDLib2 的手持物品判定是 `item instanceof HeldItemUIMenuType.HeldItemUI`（接口在 Item 实例上，不是组件），
 * 开界面要在服务端调 `HeldItemUIMenuType.openUI(player, hand)`（照 LDLib2 的 `TestItem.java:49-54`）。
 */
class Ldlib2ProbeItem(properties: Properties) : Item(properties), HeldItemUIMenuType.HeldItemUI {

    /** 照 LDLib2 的 TestItem 写。 */
    override fun use(
        level: Level,
        player: Player,
        usedHand: InteractionHand,
    ): InteractionResultHolder<ItemStack> {
        if (player is ServerPlayer) {
            HeldItemUIMenuType.openUI(player, usedHand)
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(usedHand), level.isClientSide)
    }

    override fun createUI(holder: HeldItemUIMenuType.HeldItemUIHolder): ModularUI {
        val root = UIElement()
            .layout { layout ->
                // Taffy 的尺寸方法收 float：Java 侧隐式加宽能过，Kotlin 必须写 160f
                layout.width(160f)
                    .height(72f)
                    .paddingAll(4f)
                    .gapAll(2f)
                    .justifyContent(AlignContent.CENTER)
            }
            .style { style -> style.backgroundTexture(Sprites.BORDER) }

        root.addChild(Label().setText(Component.literal("GTET × LDLib2 冒烟：MUI 仍在，两套共存")))
        root.addChild(Label().setText(Component.literal("hand = ${holder.hand}, item = ${holder.itemStack.hoverName.string}")))
        root.addChild(TextField())

        return ModularUI(UI.of(root), holder.player)
    }
}
