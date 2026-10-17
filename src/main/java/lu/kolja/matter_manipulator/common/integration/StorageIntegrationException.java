package lu.kolja.matter_manipulator.common.integration;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

public final class StorageIntegrationException extends IllegalStateException {

    private final Identifier integrationId;
    private final ItemStack callerOwned;
    private final ItemStack uncertain;

    public StorageIntegrationException(
            Identifier integrationId,
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

    public Identifier integrationId() {
        return integrationId;
    }

    public ItemStack callerOwned() {
        return callerOwned.copy();
    }

    public ItemStack uncertain() {
        return uncertain.copy();
    }
}
