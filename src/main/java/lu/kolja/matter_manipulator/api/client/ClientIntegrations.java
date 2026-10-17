package lu.kolja.matter_manipulator.api.client;

import lu.kolja.matter_manipulator.client.integration.ClientIntegrationRegistration;

import java.util.function.Consumer;

/** Addon entry point for the physical client only. Never reference it from a dedicated server entry point. */
public final class ClientIntegrations {
    private ClientIntegrations() {}

    /** Queue this from client-only mod construction, before client setup starts. */
    public static void register(Consumer<ClientIntegrationRegistrar> registration) {
        ClientIntegrationRegistration.register(registration);
    }
}
