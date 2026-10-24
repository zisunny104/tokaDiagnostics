package dev.toka.diagnostics;

import cn.nukkit.Player;
import cn.nukkit.form.element.*;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.form.window.FormWindowSimple;
import dev.toka.i18n.Messages;
import java.util.List;

/** Admin guide to the form types the core supports; every entry opens a live example. */
final class UiGuide {
    private static final String[] KINDS = {"simple", "image", "custom", "structure", "modal"};
    private final Messages messages;
    private final Ui ui = new Ui();

    UiGuide(Messages messages) { this.messages = messages; }

    Ui ui() { return ui; }

    private String t(String key, Object... args) { return messages.text(key, args); }

    void open(Player player) {
        var window = new FormWindowSimple(t("ui.title"), "");
        for (String kind : KINDS) window.addButton(Ui.button(t("ui.kind." + kind), null));
        window.addButton(Ui.button(t("ui.close"), null));
        ui.show(player, window, answer -> {
            int id = ((cn.nukkit.form.response.FormResponseSimple) answer).getClickedButtonId();
            if (id >= 0 && id < KINDS.length) note(player, KINDS[id]);
        });
    }

    /** The note says when to use the type; its button opens the example. */
    private void note(Player player, String kind) {
        var window = new FormWindowSimple(t("ui.kind." + kind), t("ui.note." + kind));
        window.addButton(Ui.button(t("ui.back"), null));
        window.addButton(Ui.button(t("ui.try"), "textures/ui/color_plus"));
        ui.show(player, window, answer -> {
            if (((cn.nukkit.form.response.FormResponseSimple) answer).getClickedButtonId() == 1) example(player, kind);
            else open(player);
        });
    }

    private void example(Player player, String kind) {
        switch (kind) {
            case "simple" -> {
                var w = new FormWindowSimple(t("ex.title"), t("ex.simpleBody"));
                w.addButton(Ui.button(t("ex.function"), null));
                w.addButton(Ui.button(t("ui.close"), null));
                ui.show(player, w, a -> note(player, kind));
            }
            case "image" -> {
                var w = new FormWindowSimple(t("ex.title"), t("ex.imageBody"));
                w.addButton(Ui.button(t("ex.byPath"), "textures/items/compass_item"));
                w.addButton(new ElementButton(t("ex.byUrl"), new ElementButtonImageData(ElementButtonImageData.IMAGE_DATA_TYPE_URL, "https://www.minecraft.net/favicon.ico")));
                w.addButton(Ui.button(t("ui.close"), null));
                ui.show(player, w, a -> note(player, kind));
            }
            case "custom" -> {
                var w = new FormWindowCustom(t("ex.title"));
                w.addElement(new ElementInput(t("ex.input"), t("ex.inputHint"), ""));
                w.addElement(new ElementToggle(t("ex.toggle"), true));
                w.addElement(new ElementDropdown(t("ex.dropdown"), List.of("A", "B", "C")));
                w.addElement(new ElementSlider(t("ex.slider"), 0, 100, 5, 50));
                w.addElement(new ElementStepSlider(t("ex.step"), List.of("S", "M", "L")));
                ui.show(player, w, a -> note(player, kind));
            }
            case "structure" -> {
                var w = new FormWindowCustom(t("ex.title"));
                w.addElement(new ElementHeader(t("ex.header")));
                w.addElement(new ElementLabel(t("ex.label")));
                w.addElement(new ElementDivider());
                w.addElement(new ElementHeader(t("ex.header2")));
                w.addElement(new ElementDivider(t("ex.divider")));
                w.addElement(new ElementLabel(t("ex.hint")));
                ui.show(player, w, a -> note(player, kind));
            }
            default -> {
                var w = new FormWindowModal(t("ex.title"), t("ex.modalBody"), t("ex.function"), t("ui.close"));
                ui.show(player, w, a -> note(player, kind));
            }
        }
    }
}
