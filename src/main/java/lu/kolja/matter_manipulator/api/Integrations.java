package lu.kolja.matter_manipulator.api;

import lu.kolja.matter_manipulator.common.integration.IntegrationRegistration;

import java.util.function.Consumer;

/** Addon entry point. Common registration never loads optional-mod or client classes. */
public final class Integrations {
    private Integrations() {}

    /**
     * Call from the mod constructor. The callback runs in common setup, after block registration. IDs must be
     * unique per category, and registering late fails.
     */
    public static void register(Consumer<IntegrationRegistrar> registration) {
        IntegrationRegistration.register(registration);
    }
}
