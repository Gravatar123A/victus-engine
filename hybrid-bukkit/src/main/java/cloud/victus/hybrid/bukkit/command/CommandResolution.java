// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.bukkit.command;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CommandResolution(Status status, Optional<CommandDefinition> command, List<String> candidates) {
    public enum Status { RESOLVED, NOT_FOUND, NAMESPACE_REQUIRED }

    public CommandResolution {
        Objects.requireNonNull(status, "status");
        command = command == null ? Optional.empty() : command;
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
        if ((status == Status.RESOLVED) != command.isPresent()) {
            throw new IllegalArgumentException("Only RESOLVED may contain a command");
        }
    }

    public static CommandResolution resolved(CommandDefinition command) {
        return new CommandResolution(Status.RESOLVED, Optional.of(command), List.of(command.qualifiedLabel()));
    }

    public static CommandResolution notFound() {
        return new CommandResolution(Status.NOT_FOUND, Optional.empty(), List.of());
    }

    public static CommandResolution namespaceRequired(List<String> candidates) {
        return new CommandResolution(Status.NAMESPACE_REQUIRED, Optional.empty(), candidates);
    }
}
