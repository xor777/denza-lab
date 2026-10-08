package dev.denza.apps;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Carries a call from any thread onto a bound service's own thread, and runs it only if that
 * instance is still the bound one when the call gets there. The static hooks of
 * {@link SimulcastAccessibilityService} are called from the access repair's executor and from the
 * UI, while what rides on the service (the HUD monitor, the media key) is owned by the main thread.
 */
final class ServiceInstanceHop {
    interface Poster<S> {
        void post(S service, Runnable call);
    }

    private ServiceInstanceHop() {
    }

    /** Returns false when no instance is bound, and nothing is queued. */
    static <S> boolean post(Supplier<S> current, Poster<S> poster, Consumer<S> call) {
        S service = current.get();
        if (service == null) {
            return false;
        }
        poster.post(service, () -> {
            if (current.get() == service) {
                call.accept(service);
            }
        });
        return true;
    }
}
