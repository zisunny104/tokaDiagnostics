package dev.toka.diagnostics;

import dev.toka.i18n.Messages;
import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Button labels carry no colour codes; only a leading red code is allowed (close). */
public class ButtonTextTest {
    private static final String[] BUTTON_KEYS = {
            "ui.close", "ui.back", "ui.try", "ui.kind.simple", "ui.kind.image", "ui.kind.custom", "ui.kind.structure", "ui.kind.modal",
            "ex.function", "ex.byPath", "ex.byUrl"};

    @Test public void buttonLabelsHaveNoColourCodesExceptLeadingRed() {
        for (String lang : new String[]{"zh-TW", "en-US"}) {
            Messages messages = new Messages(getClass().getClassLoader(), Path.of("target", "no-lang-folder"), lang, new ArrayList<String>()::add);
            for (String key : BUTTON_KEYS) {
                String text = messages.text(key);
                String rest = text.startsWith("§c") ? text.substring(2) : text;
                assertFalse(lang + " " + key + " has a colour code: " + text, rest.contains("§"));
            }
        }
    }
}
