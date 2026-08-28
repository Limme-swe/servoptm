package dev.headroom.command;

import dev.headroom.HeadroomPlugin;
import dev.headroom.runtime.HorizonEngine;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * Administrative command surface.
 */
public final class HeadroomCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("status", "pause", "resume", "reload");

    private final HeadroomPlugin plugin;

    public HeadroomCommand(HeadroomPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {
        String subcommand = arguments.length == 0
                ? "status"
                : arguments[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "status" -> {
                sendStatus(sender);
                yield true;
            }
            case "pause" -> {
                this.plugin.engine().pause();
                sender.sendMessage(Component.text("Headroom paused and player settings restored.")
                        .color(NamedTextColor.YELLOW));
                yield true;
            }
            case "resume" -> {
                this.plugin.engine().resume();
                sender.sendMessage(Component.text("Headroom resumed.")
                        .color(NamedTextColor.GREEN));
                yield true;
            }
            case "reload" -> {
                try {
                    this.plugin.reloadHeadroom();
                    sender.sendMessage(Component.text("Headroom configuration reloaded.")
                            .color(NamedTextColor.GREEN));
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    sender.sendMessage(Component.text("Reload rejected: " + exception.getMessage())
                            .color(NamedTextColor.RED));
                }
                yield true;
            }
            default -> false;
        };
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] arguments) {
        if (arguments.length != 1) {
            return List.of();
        }
        String prefix = arguments[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream()
                .filter(candidate -> candidate.startsWith(prefix))
                .collect(Collectors.toUnmodifiableList());
    }

    private void sendStatus(CommandSender sender) {
        HorizonEngine.Status status = this.plugin.engine().status();
        sender.sendMessage(Component.text("Headroom")
                .color(NamedTextColor.AQUA)
                .append(Component.text(" — " + (status.paused() ? "paused" : "running"))
                        .color(status.paused() ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
        sender.sendMessage(Component.text(
                "Backend: " + status.backend()
                        + " | players: " + status.managedPlayers()
                        + " | leases: " + status.warmLeases()
                        + " | in-flight: " + status.inFlightLoads()));
        sender.sendMessage(Component.text(
                "Tick pressure: " + status.latency().pressure()
                        + " | p95: " + format(status.latency().p95Millis()) + " ms"
                        + " | mean: " + format(status.latency().meanMillis()) + " ms"
                        + " | distance penalty: " + status.latency().distancePenalty()));
        sender.sendMessage(Component.text(
                "Promotions: " + status.metrics().ringPromotions()
                        + " | complete: " + status.metrics().ringCompletions()
                        + " | rollbacks: " + status.metrics().ringRollbacks()
                        + " | shared load joins: " + status.metrics().sharedLoadJoins()));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
