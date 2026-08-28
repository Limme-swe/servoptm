package dev.headroom.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.PluginManager;

/**
 * Optional, reflection-only Bedrock detection.
 *
 * <p>Headroom has no hard Geyser or Floodgate dependency. More importantly, the runtime
 * never bypasses Paper's normal packet path, so Geyser can translate every chunk packet
 * produced after a Headroom promotion.</p>
 */
public final class ClientClassifier {

    @FunctionalInterface
    private interface Detector {
        boolean isBedrock(UUID playerId)
                throws IllegalAccessException, InvocationTargetException;
    }

    private final PluginManager pluginManager;
    private final Logger logger;
    private final boolean enabled;
    private final boolean logFailures;

    private List<Detector> detectors = List.of();
    private boolean warned;

    public ClientClassifier(
            PluginManager pluginManager,
            Logger logger,
            boolean enabled,
            boolean logFailures) {
        this.pluginManager = pluginManager;
        this.logger = logger;
        this.enabled = enabled;
        this.logFailures = logFailures;
        refresh();
    }

    public void refresh() {
        if (!this.enabled) {
            this.detectors = List.of();
            return;
        }

        List<Detector> discovered = new ArrayList<>();
        if (this.pluginManager.getPlugin("floodgate") != null) {
            discoverFloodgate().ifPresent(discovered::add);
        }
        if (this.pluginManager.getPlugin("Geyser-Spigot") != null) {
            discoverGeyser().ifPresent(discovered::add);
        }
        this.detectors = List.copyOf(discovered);
    }

    public boolean isBedrock(UUID playerId) {
        for (Detector detector : this.detectors) {
            try {
                if (detector.isBedrock(playerId)) {
                    return true;
                }
            } catch (IllegalAccessException | InvocationTargetException exception) {
                warnOnce("Bedrock client detection failed; using the Java ceiling for this session", exception);
            }
        }
        return false;
    }

    private Optional<Detector> discoverFloodgate() {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            Method isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            return Optional.of(playerId -> (boolean) isFloodgatePlayer.invoke(api, playerId));
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | IllegalAccessException
                | InvocationTargetException
                | ClassCastException exception) {
            warnOnce("Floodgate was found but its public API could not be initialized", exception);
            return Optional.empty();
        }
    }

    private Optional<Detector> discoverGeyser() {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Method apiMethod = apiClass.getMethod("api");
            Object api = apiMethod.invoke(null);

            try {
                Method isBedrockPlayer = apiClass.getMethod("isBedrockPlayer", UUID.class);
                return Optional.of(playerId -> (boolean) isBedrockPlayer.invoke(api, playerId));
            } catch (NoSuchMethodException ignored) {
                Method connectionByUuid = apiClass.getMethod("connectionByUuid", UUID.class);
                return Optional.of(playerId -> {
                    Object result = connectionByUuid.invoke(api, playerId);
                    if (result instanceof Optional<?> optional) {
                        return optional.isPresent();
                    }
                    return result != null;
                });
            }
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | IllegalAccessException
                | InvocationTargetException
                | ClassCastException exception) {
            warnOnce("Geyser was found but its public API could not be initialized", exception);
            return Optional.empty();
        }
    }

    private void warnOnce(String message, Exception exception) {
        if (this.warned || !this.logFailures) {
            return;
        }
        this.warned = true;
        this.logger.log(Level.WARNING, message, exception);
    }
}
