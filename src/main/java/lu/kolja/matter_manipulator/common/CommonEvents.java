package lu.kolja.matter_manipulator.common;

import lu.kolja.matter_manipulator.MatterManipulator;
import lu.kolja.matter_manipulator.common.blueprint.BlueprintServerCache;
import lu.kolja.matter_manipulator.common.build.BlockDropGuard;
import lu.kolja.matter_manipulator.common.build.BuildManager;
import lu.kolja.matter_manipulator.common.integration.DeferredIntegrationWork;
import lu.kolja.matter_manipulator.common.integration.IntegrationDispatcher;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = MatterManipulator.MOD_ID)
public final class CommonEvents {
    private CommonEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity item
                && !event.loadedFromDisk()
                && BlockDropGuard.intercept(event.getLevel().dimension(), item.blockPosition(), item.getItem())) {
            item.setItem(ItemStack.EMPTY);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        runEndOfTick(BuildManager::tick, IntegrationDispatcher::tickCrafting);
        BuildManager.runDeferredPlans(event.getServer());
        BlueprintServerCache.tick(event.getServer().getTickCount());
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            BuildManager.forgetCompiledPlans();
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        BuildManager.forget(event.getEntity().getUUID());
        BlueprintServerCache.forget(event.getEntity().getUUID());
        IntegrationDispatcher.playerLoggedOut(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearServerState();
    }

    public static void clearServerState() {
        BuildManager.clear();
        BlueprintServerCache.clear();
        DeferredIntegrationWork.clear();
        IntegrationDispatcher.clearServerState();
    }

    static void runEndOfTick(Runnable buildTick, Runnable craftingTick) {
        DeferredIntegrationWork.runPending();
        buildTick.run();
        craftingTick.run();
    }
}
