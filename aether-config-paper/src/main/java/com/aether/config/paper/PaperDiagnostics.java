package com.aether.config.paper;

import com.aether.config.core.ResourceProvider;
import com.aether.config.diagnostic.ConfigDiagnostics;
import com.aether.config.diagnostic.DiagnosticEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/**
 * Paper logging, command feedback, and plugin resource adapters.
 */
public final class PaperDiagnostics {

    private PaperDiagnostics() {
    }

    public static ResourceProvider resourceProvider(Plugin plugin) {
        return plugin::getResource;
    }

    public static void flush(ConfigDiagnostics diagnostics, ComponentLogger logger) {
        for (DiagnosticEntry entry : diagnostics.entries()) {
            switch (entry.severity()) {
                case ERROR -> logger.error(entry.format());
                case WARN -> logger.warn(entry.format());
                case INFO -> logger.info(entry.format());
            }
        }
    }

    public static String format(ConfigDiagnostics diagnostics) {
        return diagnostics.formatAll();
    }

    public static void send(ConfigDiagnostics diagnostics, CommandSender sender) {
        for (DiagnosticEntry entry : diagnostics.entries()) {
            NamedTextColor color = switch (entry.severity()) {
                case ERROR -> NamedTextColor.RED;
                case WARN -> NamedTextColor.YELLOW;
                case INFO -> NamedTextColor.GRAY;
            };
            sender.sendMessage(Component.text(entry.format(), color));
        }
    }
}
