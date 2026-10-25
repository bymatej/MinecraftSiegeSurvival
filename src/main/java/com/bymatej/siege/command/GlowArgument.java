package com.bymatej.siege.command;

import java.util.Locale;

/**
 * The optional argument of {@code /siege glow}: an explicit on/off, or no
 * argument at all, which toggles the glow. Kept free of Bukkit types so it
 * can be unit tested.
 */
public enum GlowArgument {
    /** No argument: switch the glow. */
    TOGGLE,
    ON,
    OFF,
    /** Something that is neither on nor off. */
    INVALID;

    /** Parses the argument; null or blank means a toggle. */
    public static GlowArgument parse(String argument) {
        if (argument == null || argument.isBlank()) return TOGGLE;
        return switch (argument.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> ON;
            case "off", "false", "no" -> OFF;
            default -> INVALID;
        };
    }
}
