package lu.kolja.matter_manipulator.common.integration;

import lu.kolja.matter_manipulator.api.IntegrationRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class IntegrationRegistration {
    private static final List<Consumer<IntegrationRegistrar>> REGISTRATIONS = new ArrayList<>();
    private static boolean closed;

    private IntegrationRegistration() {}

    public static synchronized void register(Consumer<IntegrationRegistrar> registration) {
        if (closed) {
            throw new IllegalStateException("Integration registration is closed; register from your mod constructor");
        }
        REGISTRATIONS.add(Objects.requireNonNull(registration, "registration"));
    }

    static synchronized List<Consumer<IntegrationRegistrar>> close() {
        if (closed) {
            throw new IllegalStateException("Integration initialization already started");
        }
        closed = true;
        var registrations = List.copyOf(REGISTRATIONS);
        REGISTRATIONS.clear();
        return registrations;
    }
}
