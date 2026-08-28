package dev.headroom.config;

import dev.headroom.core.PaperViewGeometry;
import dev.headroom.core.TailLatencyController;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable, validated runtime configuration.
 */
public record HeadroomConfig(
        boolean enabled,
        Horizon horizon,
        Scheduler scheduler,
        WarmLeases warmLeases,
        LoadControl loadControl,
        Geyser geyser,
        Worlds worlds) {

    private static final int SCHEMA_VERSION = 1;

    public static HeadroomConfig load(FileConfiguration configuration) {
        int schemaVersion = configuration.getInt("schema-version", -1);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported config schema-version " + schemaVersion + "; expected " + SCHEMA_VERSION);
        }

        Horizon horizon = new Horizon(
                configuration.getInt("horizon.max-java-distance"),
                configuration.getInt("horizon.max-bedrock-distance"),
                configuration.getBoolean("horizon.generate-new-chunks"),
                configuration.getInt("horizon.delivery-timeout-ticks"),
                configuration.getInt("horizon.retry-delay-ticks"));

        Scheduler scheduler = new Scheduler(
                configuration.getInt("scheduler.max-load-starts-per-tick"),
                configuration.getInt("scheduler.max-in-flight-loads"),
                configuration.getInt("scheduler.main-thread-budget-micros"),
                configuration.getDouble("scheduler.player-quantum"));

        WarmLeases warmLeases = new WarmLeases(
                configuration.getInt("warm-leases.max-chunks"),
                configuration.getInt("warm-leases.timeout-ticks"));

        LoadControl loadControl = new LoadControl(
                configuration.getInt("load-control.sample-window-ticks"),
                configuration.getDouble("load-control.soft-p95-ms"),
                configuration.getDouble("load-control.hard-p95-ms"),
                configuration.getDouble("load-control.critical-tick-ms"),
                configuration.getInt("load-control.unhealthy-ticks-to-retract"),
                configuration.getInt("load-control.healthy-ticks-to-expand"),
                configuration.getInt("load-control.distance-change-cooldown-ticks"),
                configuration.getInt("load-control.max-distance-penalty"));

        Geyser geyser = new Geyser(
                configuration.getBoolean("geyser.detect-bedrock-players"),
                configuration.getBoolean("geyser.log-detection-failures"));

        Set<String> disabledWorlds = new HashSet<>(configuration.getStringList("worlds.disabled"));
        Map<String, Integer> overrides = new HashMap<>();
        ConfigurationSection overrideSection =
                configuration.getConfigurationSection("worlds.max-distance-overrides");
        if (overrideSection != null) {
            for (String worldName : overrideSection.getKeys(false)) {
                overrides.put(worldName, overrideSection.getInt(worldName));
            }
        }
        Worlds worlds = new Worlds(disabledWorlds, overrides);

        return new HeadroomConfig(
                configuration.getBoolean("enabled"),
                horizon,
                scheduler,
                warmLeases,
                loadControl,
                geyser,
                worlds);
    }

    public int ceilingFor(World world, boolean bedrock) {
        int clientCeiling = bedrock
                ? this.horizon.maximumBedrockDistance()
                : this.horizon.maximumJavaDistance();
        return Math.min(clientCeiling, this.worlds.maximumDistance(world.getName(), clientCeiling));
    }

    public boolean isWorldEnabled(World world) {
        return !this.worlds.disabledWorlds().contains(world.getName());
    }

    public record Horizon(
            int maximumJavaDistance,
            int maximumBedrockDistance,
            boolean generateNewChunks,
            int deliveryTimeoutTicks,
            int retryDelayTicks) {

        public Horizon {
            validateDistance(maximumJavaDistance, "maximumJavaDistance");
            validateDistance(maximumBedrockDistance, "maximumBedrockDistance");
            if (deliveryTimeoutTicks < 20) {
                throw new IllegalArgumentException("deliveryTimeoutTicks must be at least 20");
            }
            if (retryDelayTicks < 1) {
                throw new IllegalArgumentException("retryDelayTicks must be positive");
            }
        }
    }

    public record Scheduler(
            int maximumLoadStartsPerTick,
            int maximumInFlightLoads,
            int mainThreadBudgetMicros,
            double playerQuantum) {

        public Scheduler {
            if (maximumLoadStartsPerTick < 1 || maximumLoadStartsPerTick > 128) {
                throw new IllegalArgumentException("maximumLoadStartsPerTick must be in [1, 128]");
            }
            if (maximumInFlightLoads < maximumLoadStartsPerTick || maximumInFlightLoads > 4096) {
                throw new IllegalArgumentException(
                        "maximumInFlightLoads must be at least the per-tick limit and at most 4096");
            }
            if (mainThreadBudgetMicros < 100 || mainThreadBudgetMicros > 20_000) {
                throw new IllegalArgumentException("mainThreadBudgetMicros must be in [100, 20000]");
            }
            if (!(playerQuantum > 0.0 && Double.isFinite(playerQuantum))) {
                throw new IllegalArgumentException("playerQuantum must be finite and positive");
            }
        }
    }

    public record WarmLeases(int maximumChunks, int timeoutTicks) {

        public WarmLeases {
            if (maximumChunks < 1 || maximumChunks > 100_000) {
                throw new IllegalArgumentException("maximumChunks must be in [1, 100000]");
            }
            if (timeoutTicks < 20) {
                throw new IllegalArgumentException("timeoutTicks must be at least 20");
            }
        }
    }

    public record LoadControl(
            int sampleWindowTicks,
            double softP95Millis,
            double hardP95Millis,
            double criticalTickMillis,
            int unhealthyTicksToRetract,
            int healthyTicksToExpand,
            int distanceChangeCooldownTicks,
            int maximumDistancePenalty) {

        public TailLatencyController.Thresholds thresholds() {
            return new TailLatencyController.Thresholds(
                    this.sampleWindowTicks,
                    this.softP95Millis,
                    this.hardP95Millis,
                    this.criticalTickMillis,
                    this.unhealthyTicksToRetract,
                    this.healthyTicksToExpand,
                    this.distanceChangeCooldownTicks,
                    this.maximumDistancePenalty);
        }
    }

    public record Geyser(boolean detectBedrockPlayers, boolean logDetectionFailures) {
    }

    public record Worlds(Set<String> disabledWorlds, Map<String, Integer> maximumDistanceOverrides) {

        public Worlds {
            disabledWorlds = Set.copyOf(disabledWorlds);
            maximumDistanceOverrides = Map.copyOf(maximumDistanceOverrides);
            for (Map.Entry<String, Integer> entry : maximumDistanceOverrides.entrySet()) {
                validateDistance(entry.getValue(), "world override for " + entry.getKey());
            }
        }

        public int maximumDistance(String worldName, int fallback) {
            return this.maximumDistanceOverrides.getOrDefault(worldName, fallback);
        }
    }

    private static void validateDistance(int distance, String name) {
        if (distance < PaperViewGeometry.MIN_RADIUS || distance > PaperViewGeometry.MAX_RADIUS) {
            throw new IllegalArgumentException(
                    name + " must be in [" + PaperViewGeometry.MIN_RADIUS + ", "
                            + PaperViewGeometry.MAX_RADIUS + "]");
        }
    }
}
