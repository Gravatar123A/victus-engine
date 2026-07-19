package cloud.victus.core.config;

/** Redstone dust engine (Paper already bundles all three). See docs/phase-1/01-redstone.md. */
public enum RedstoneImpl {
    VANILLA,
    ALTERNATE_CURRENT,
    EIGENCRAFT;

    public static RedstoneImpl fromConfig(String s) {
        if (s == null) return VANILLA;
        switch (s.trim().toLowerCase().replace('_', '-')) {
            case "vanilla":           return VANILLA;
            case "alternate-current": return ALTERNATE_CURRENT;
            case "eigencraft":        return EIGENCRAFT;
            default:
                throw new IllegalArgumentException("unknown optimizations.redstone: " + s
                        + " (expected vanilla|alternate-current|eigencraft)");
        }
    }
}
