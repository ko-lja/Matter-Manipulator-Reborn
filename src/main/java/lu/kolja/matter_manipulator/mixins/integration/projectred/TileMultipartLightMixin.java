package lu.kolja.matter_manipulator.mixins.integration.projectred;

import codechicken.multipart.block.TileMultipart;
import lu.kolja.mixinloadconditions.LoadCondition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@LoadCondition(loadIf = "cb_multipart")
@Mixin(value = TileMultipart.class, remap = false)
public abstract class TileMultipartLightMixin {

    @Inject(method = "recalcLight", at = @At("HEAD"), cancellable = true)
    private void matter_manipulator$queueLightCheck(boolean sky, boolean block, CallbackInfo ci) {
        var tile = (BlockEntity) (Object) this;
        if (tile.getLevel() instanceof ServerLevel level) {
            level.getLightEngine().checkBlock(tile.getBlockPos());
            ci.cancel();
        }
    }
}
