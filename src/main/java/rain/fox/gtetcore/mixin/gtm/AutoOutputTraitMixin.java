package rain.fox.gtetcore.mixin.gtm;

import com.gregtechceu.gtceu.api.sync_system.SyncDataHolder;
import com.gregtechceu.gtceu.common.machine.trait.AutoOutputTrait;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修 `AutoOutputTrait.setItemOutputDirection` 里写错的那个同步字段名 —— 这是「设了物品输出面但客户端一直显示/渲染成旧面」的根因。
 *
 * <p>`SyncDataHolder.markClientSyncFieldDirty(String)`（SyncDataHolder.java:49-52）只是把字符串塞进
 * `dirtySyncFields`，而 `shouldSyncFieldToClient`（:198-202）拿它跟 **Java 字段名** `field.fieldName` 比。
 * `setItemOutputDirection`（AutoOutputTrait.java:225）标记的是 `"outputFacingItems"`，可字段真名是
 * `itemOutputDirection`（:64）—— 永远匹配不上，于是物品输出面的改动**从来不会同步给客户端**：
 * 界面与世界里的覆盖贴图都停在 `onMachineLoad`（:127-130）设的默认值（正面之反，机器朝南时就是北面），
 * 看起来就是「怎么点都卡在北面」。流体那边（:214）标记的是 `"fluidOutputDirection"`，与字段名一致，所以只有物品这一半是坏的。
 *
 * <p>不在 `@Redirect` 里保留原参数：只有改成真字段名才会被 `shouldSyncFieldToClient` 认到。
 */
@Mixin(value = AutoOutputTrait.class, remap = false)
public class AutoOutputTraitMixin {

    @Redirect(method = "setItemOutputDirection", at = @At(
                    value = "INVOKE",
                    target = "Lcom/gregtechceu/gtceu/api/sync_system/SyncDataHolder;markClientSyncFieldDirty(Ljava/lang/String;)V",
                    remap = false),
            remap = false)
    private void gtetcore$fixItemOutputSyncFieldName(SyncDataHolder holder, String wrongName) {
        holder.markClientSyncFieldDirty("itemOutputDirection");
    }
}
