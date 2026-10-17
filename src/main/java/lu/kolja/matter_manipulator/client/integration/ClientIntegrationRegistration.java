package lu.kolja.matter_manipulator.client.integration;

import lu.kolja.matter_manipulator.api.client.ClientIntegrationRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class ClientIntegrationRegistration {
    private static final List<Consumer<ClientIntegrationRegistrar>> REGISTRATIONS = new ArrayList<>();
    private static boolean closed;

    private ClientIntegrationRegistration() {}

    public static synchronized void register(Consumer<ClientIntegrationRegistrar> registration) {
        if (closed) {
            throw new IllegalStateException("Integration registration is closed; register from your mod constructor");
        }
        REGISTRATIONS.add(Objects.requireNonNull(registration, "registration"));
    }

    static synchronized List<Consumer<ClientIntegrationRegistrar>> close() {
        if (closed) {
            throw new IllegalStateException("Integration initialization already started");
        }
        closed = true;
        var registrations = List.copyOf(REGISTRATIONS);
        REGISTRATIONS.clear();
        return registrations;
    }
}
