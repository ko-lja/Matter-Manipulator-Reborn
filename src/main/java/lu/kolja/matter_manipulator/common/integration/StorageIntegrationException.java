package lu.kolja.matter_manipulator.common.integration;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public final class StorageIntegrationException extends IllegalStateException {

    private final ResourceLocation integrationId;
    private final ItemStack callerOwned;
    private final ItemStack uncertain;

    public StorageIntegrationException(
            ResourceLocation integrationId,
            String operation,
            ItemStack callerOwned,
            ItemStack uncertain,
            Throwable cause
    ) {
        super("Storage integration " + integrationId + " failed during " + operation
                + "; outcome unknown, refusing to retry", cause);
        this.integrationId = integrationId;
        this.callerOwned = callerOwned.copy();
        this.uncertain = uncertain.copy();
    }

    public ResourceLocation integrationId() {
        return integrationId;
    }

    public ItemStack callerOwned() {
        return callerOwned.copy();
    }

    public ItemStack uncertain() {
        return uncertain.copy();
    }
}
