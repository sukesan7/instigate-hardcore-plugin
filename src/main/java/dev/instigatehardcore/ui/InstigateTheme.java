package dev.instigatehardcore.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class InstigateTheme {

    /*
     * ------------------------------------------------------------
     * PALETTE
     * ------------------------------------------------------------
     *
     * Azure / purple primary identity with neutral text.
     *
     * Red is intentionally reserved for actual failures,
     * dangerous actions and error states.
     */

    public static final TextColor AZURE =
        TextColor.color(
            0x58A6FF
        );

    public static final TextColor PURPLE =
        TextColor.color(
            0xA78BFA
        );

    public static final TextColor TEXT =
        TextColor.color(
            0xE4E7EC
        );

    public static final TextColor SECONDARY =
        TextColor.color(
            0xAAB2BF
        );

    public static final TextColor MUTED =
        TextColor.color(
            0x68707D
        );

    public static final TextColor ERROR =
        TextColor.color(
            0xE78284
        );

    private InstigateTheme() {
    }

    /*
     * ------------------------------------------------------------
     * BRAND
     * ------------------------------------------------------------
     */

    public static Component brand() {
        return Component.text(
            "INSTIGATE CAFE",
            AZURE
        ).decorate(
            TextDecoration.BOLD
        );
    }

    /**
     * Prefix used for ordinary plugin-originated chat messages.
     *
     * [Instigate Cafe]
     */
    public static Component prefix() {
        return Component.text()
            .append(
                Component.text(
                    "[",
                    MUTED
                )
            )
            .append(
                Component.text(
                    "Instigate",
                    AZURE
                )
            )
            .append(
                Component.text(
                    " Cafe",
                    PURPLE
                )
            )
            .append(
                Component.text(
                    "]",
                    MUTED
                )
            )
            .build();
    }

    /**
     * Prepends the standard chat prefix to a component.
     */
    public static Component chat(
        Component message
    ) {
        return Component.text()
            .append(
                prefix()
            )
            .append(
                Component.space()
            )
            .append(
                message
            )
            .build();
    }

    public static Component chat(
        String message
    ) {
        return chat(
            Component.text(
                message,
                TEXT
            )
        );
    }

    /*
     * ------------------------------------------------------------
     * COMMON COMPONENTS
     * ------------------------------------------------------------
     */

    public static Component attempt(
        int attempt
    ) {
        return Component.text(
            "Attempt #"
                + attempt,
            PURPLE
        );
    }

    public static Component text(
        String text
    ) {
        return Component.text(
            text,
            TEXT
        );
    }

    public static Component secondary(
        String text
    ) {
        return Component.text(
            text,
            SECONDARY
        );
    }

    public static Component muted(
        String text
    ) {
        return Component.text(
            text,
            MUTED
        );
    }

    public static Component error(
        String text
    ) {
        return Component.text(
            text,
            ERROR
        );
    }

    public static Component heading(
        String text
    ) {
        return Component.text(
            text,
            AZURE
        ).decorate(
            TextDecoration.BOLD
        );
    }

    public static Component subheading(
        String text
    ) {
        return Component.text(
            text,
            PURPLE
        ).decorate(
            TextDecoration.BOLD
        );
    }

    public static Component divider() {
        return Component.text(
            "────────────────────────────",
            MUTED
        );
    }
}