package lu.kolja.matter_manipulator.api;

import java.util.UUID;

public interface CraftingIntegration {

    boolean request(IntegrationContext.Crafting context);

    default void tick() {}

    /** Drops every request so nothing leaks into the next server. May be called more than once. */
    default void serverStopping() {}

    /** Drops the player's requests. May be called more than once, or with nothing pending. */
    default void playerLoggedOut(UUID player) {}
}
