// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Deterministic command lookup preserving every qualified command during collisions. */
public final class CommandBridge {
    private final CollisionPolicy policy;
    private final Map<String, CommandDefinition> qualified;
    private final Map<String, List<CommandDefinition>> labels;

    public CommandBridge(CollisionPolicy policy, Collection<CommandDefinition> commands) {
        this.policy = Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(commands, "commands");
        Map<String, CommandDefinition> qualifiedIndex = new HashMap<>();
        Map<String, List<CommandDefinition>> labelIndex = new HashMap<>();
        for (CommandDefinition command : commands) {
            CommandDefinition previous = qualifiedIndex.putIfAbsent(command.qualifiedLabel(), command);
            if (previous != null) throw new IllegalArgumentException("Duplicate qualified command " + command.qualifiedLabel());
            labelIndex.computeIfAbsent(command.label(), ignored -> new ArrayList<>()).add(command);
        }
        labelIndex.replaceAll((label, values) -> values.stream()
                .sorted(Comparator.comparing(CommandDefinition::qualifiedLabel)).toList());
        this.qualified = Map.copyOf(qualifiedIndex);
        this.labels = Map.copyOf(labelIndex);
    }

    public CommandResolution resolve(String inputLabel) {
        Objects.requireNonNull(inputLabel, "inputLabel");
        String label = inputLabel.trim().toLowerCase(Locale.ROOT);
        if (label.indexOf(':') >= 0) {
            CommandDefinition command = qualified.get(label);
            return command == null ? CommandResolution.notFound() : CommandResolution.resolved(command);
        }
        List<CommandDefinition> candidates = labels.get(label);
        if (candidates == null) return CommandResolution.notFound();
        if (candidates.size() == 1) return CommandResolution.resolved(candidates.getFirst());
        CommandOrigin preferred = switch (policy) {
            case BUKKIT_WINS -> CommandOrigin.BUKKIT;
            case MOD_WINS -> CommandOrigin.MOD;
            case REQUIRE_NAMESPACE -> null;
        };
        if (preferred != null) {
            List<CommandDefinition> preferredCommands = candidates.stream()
                    .filter(command -> command.origin() == preferred).toList();
            if (preferredCommands.size() == 1) return CommandResolution.resolved(preferredCommands.getFirst());
        }
        return CommandResolution.namespaceRequired(candidates.stream().map(CommandDefinition::qualifiedLabel).toList());
    }
}
