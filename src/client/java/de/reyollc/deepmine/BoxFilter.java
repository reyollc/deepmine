package de.reyollc.deepmine;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Was in einen Shulker hinein darf.
 *
 * ONLY   nur die aufgelisteten Items
 * EXCEPT alles ausser den aufgelisteten
 * ALL    alles
 *
 * Der Schutz von Werkzeug, Fackeln, Schwert und anderen Shulkern steckt bewusst
 * NICHT hier drin, sondern in MinerTask: er haengt am Hotbar-Slot, nicht am Item,
 * und gilt fuer jeden Modus gleichermassen. Sonst wuerde ein EXCEPT-Filter beim
 * ersten Einlagern die eigene Ausruestung wegpacken.
 */
public final class BoxFilter {

    public enum Mode { ONLY, EXCEPT, ALL }

    private Mode mode;
    private final Set<Item> items = new LinkedHashSet<>();

    /**
     * Nur volle Stapel hinein. Standard bei ONLY: eine Kiste fuer genau eine Sorte
     * ist eine Massenkiste, ein angebrochener Stapel bleibt im Inventar und ist
     * beim naechsten Mal voll. Bei EXCEPT/ALL aus: dort sind angebrochene Stapel
     * (drei Kies, zwei Feuerstein) normal und muessen weg, sonst verstopfen sie.
     */
    private boolean fullStacksOnly;

    public BoxFilter(Mode mode) {
        this.mode = mode;
        this.fullStacksOnly = mode == Mode.ONLY;
    }

    /** Modewechsel wirft die Liste weg, weil sie dann etwas anderes bedeutet. */
    public void setMode(Mode m) {
        if (m != mode) {
            mode = m;
            items.clear();
            fullStacksOnly = m == Mode.ONLY;
        }
    }

    public void add(Item item) { items.add(item); }

    public Mode mode() { return mode; }

    public boolean fullStacksOnly() { return fullStacksOnly; }

    public void setFullStacksOnly(boolean v) { fullStacksOnly = v; }

    /** Zum Speichern. Nicht veraendern, dafuer gibt es add und setMode. */
    public Set<Item> items() { return Collections.unmodifiableSet(items); }

    public boolean accepts(Item item) {
        return switch (mode) {
            case ALL -> true;
            case ONLY -> items.contains(item);
            case EXCEPT -> !items.contains(item);
        };
    }

    /** Lesbare Beschreibung in der Spielsprache, Itemnamen inklusive. */
    public MutableComponent describe() {
        MutableComponent out;
        if (mode == Mode.ALL) {
            out = Component.translatable("deepmine.filter.all");
        } else {
            MutableComponent list = Component.empty();
            boolean first = true;
            for (Item i : items) {
                if (!first) list.append(", ");
                list.append(Component.translatable(i.getDescriptionId()));
                first = false;
            }
            if (items.isEmpty()) list.append(Component.translatable("deepmine.nothing"));
            out = Component.translatable(
                    mode == Mode.ONLY ? "deepmine.filter.only" : "deepmine.filter.except", list);
        }
        if (fullStacksOnly) out.append(Component.translatable("deepmine.filter.fullstacks"));
        return out;
    }
}
