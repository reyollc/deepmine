package de.reyollc.deepmine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Alles, was ueber /deepmine eingestellt wird, plus Laden und Speichern nach
 * config/deepmine.json. Gespeichert wird bei jeder Aenderung, geladen beim
 * Start des Spiels.
 *
 * Slots stehen in der Datei 1-basiert wie in den Befehlen, 0 heisst "aus".
 * Intern sind sie 0-basiert, -1 heisst "aus". Items und Bloecke stehen als
 * Registry-ID drin (minecraft:gravel), damit die Datei von Hand lesbar bleibt.
 */
final class Settings {

    private static final Logger LOG = LoggerFactory.getLogger("deepmine");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Standard-Hotbar: 1 Schwert, 2 Picke, 3 Silk, 4 Fackeln, 5 Baumaterial,
    // 6 Shulker fuer alles ausser Bruchtiefenschiefer, 7 Shulker nur dafuer, 8 Essen.
    int swordSlot = 0, mineSlot = 1, silkSlot = 2, torchSlot = 3, floorSlot = 4, foodSlot = 7;
    int minDurability = 40;
    final Map<Integer, BoxFilter> boxes = new LinkedHashMap<>();
    final Set<Block> silkExtra = new LinkedHashSet<>();

    Settings() {
        BoxFilter rest = new BoxFilter(BoxFilter.Mode.EXCEPT);
        rest.add(Items.COBBLED_DEEPSLATE);
        boxes.put(5, rest);
        BoxFilter deepslate = new BoxFilter(BoxFilter.Mode.ONLY);
        deepslate.add(Items.COBBLED_DEEPSLATE);
        boxes.put(6, deepslate);
        // Kies droppt mit Glueck III nur noch Feuerstein, deshalb Silk Touch.
        silkExtra.add(Blocks.GRAVEL);
    }

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("deepmine.json");
    }

    void applyTo(MinerTask t) {
        t.setSlots(mineSlot, silkSlot, torchSlot);
        t.setSwordSlot(swordSlot);
        t.setFloorSlot(floorSlot);
        t.setFoodSlot(foodSlot);
        t.setMinDurability(minDurability);
        t.setBoxes(boxes);
        t.setSilkExtra(silkExtra);
    }

    /** Mehrere Zeilen fuer /deepmine config, in der Spielsprache. */
    List<Component> describe() {
        List<Component> out = new ArrayList<>();
        out.add(Chat.tr("deepmine.config.slots",
                Chat.hl(mineSlot + 1), Chat.hl(silkSlot + 1), Chat.hl(torchSlot + 1),
                slotText(swordSlot), slotText(floorSlot), slotText(foodSlot), Chat.hl(minDurability)));
        out.add(boxesLine());
        out.add(silkLine());
        out.add(Chat.tr("deepmine.config.file", Chat.hl(file())));
        return out;
    }

    /** "Shulker: [Slot 6 -> ...] [Slot 7 -> ...]" oder "Keine Shulker eingetragen." */
    MutableComponent boxesLine() {
        if (boxes.isEmpty()) return Chat.tr("deepmine.box.none");
        MutableComponent entries = Component.empty();
        for (Map.Entry<Integer, BoxFilter> e : boxes.entrySet()) {
            entries.append(Chat.tr("deepmine.box.entry", Chat.hl(e.getKey() + 1), e.getValue().describe()));
        }
        return Chat.tr("deepmine.box.list", entries);
    }

    /** "Silk Touch: alle Erze, zusaetzlich Kies." */
    MutableComponent silkLine() {
        MutableComponent list = Component.empty();
        boolean first = true;
        for (Block b : silkExtra) {
            if (!first) list.append(", ");
            list.append(name(b));
            first = false;
        }
        if (silkExtra.isEmpty()) list.append(Chat.tr("deepmine.nothing"));
        return Chat.tr("deepmine.silk.list", list);
    }

    private static MutableComponent slotText(int slot) {
        return slot < 0 ? Chat.tr("deepmine.off") : Chat.hl(slot + 1);
    }

    static MutableComponent name(Block b) {
        return Component.translatable(b.getDescriptionId());
    }

    static MutableComponent name(Item i) {
        return Component.translatable(i.getDescriptionId());
    }

    // ------------------------------ speichern ------------------------------

    void save() {
        JsonObject o = new JsonObject();
        o.addProperty("mineSlot", mineSlot + 1);
        o.addProperty("silkSlot", silkSlot + 1);
        o.addProperty("torchSlot", torchSlot + 1);
        o.addProperty("swordSlot", swordSlot < 0 ? 0 : swordSlot + 1);
        o.addProperty("floorSlot", floorSlot < 0 ? 0 : floorSlot + 1);
        o.addProperty("foodSlot", foodSlot < 0 ? 0 : foodSlot + 1);
        o.addProperty("minDurability", minDurability);

        JsonArray bx = new JsonArray();
        for (Map.Entry<Integer, BoxFilter> e : boxes.entrySet()) {
            JsonObject b = new JsonObject();
            b.addProperty("slot", e.getKey() + 1);
            b.addProperty("mode", e.getValue().mode().name());
            b.addProperty("fullStacksOnly", e.getValue().fullStacksOnly());
            JsonArray items = new JsonArray();
            for (Item i : e.getValue().items()) items.add(BuiltInRegistries.ITEM.getKey(i).toString());
            b.add("items", items);
            bx.add(b);
        }
        o.add("boxes", bx);

        JsonArray silk = new JsonArray();
        for (Block b : silkExtra) silk.add(BuiltInRegistries.BLOCK.getKey(b).toString());
        o.add("silkExtra", silk);

        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOG.warn("Konnte {} nicht schreiben: {}", file(), ex.toString());
        }
    }

    // -------------------------------- laden --------------------------------

    static Settings load() {
        Settings s = new Settings();
        Path f = file();
        if (!Files.exists(f)) return s;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            s.mineSlot = slot(o, "mineSlot", s.mineSlot, false);
            s.silkSlot = slot(o, "silkSlot", s.silkSlot, false);
            s.torchSlot = slot(o, "torchSlot", s.torchSlot, false);
            s.swordSlot = slot(o, "swordSlot", s.swordSlot, true);
            s.floorSlot = slot(o, "floorSlot", s.floorSlot, true);
            s.foodSlot = slot(o, "foodSlot", s.foodSlot, true);
            if (o.has("minDurability")) {
                s.minDurability = Math.max(1, Math.min(500, o.get("minDurability").getAsInt()));
            }

            if (o.has("boxes")) {
                s.boxes.clear();
                for (JsonElement el : o.getAsJsonArray("boxes")) {
                    JsonObject b = el.getAsJsonObject();
                    int slot = b.get("slot").getAsInt() - 1;
                    if (slot < 0 || slot > 8) continue;
                    BoxFilter fl = new BoxFilter(BoxFilter.Mode.valueOf(b.get("mode").getAsString()));
                    if (b.has("fullStacksOnly")) fl.setFullStacksOnly(b.get("fullStacksOnly").getAsBoolean());
                    for (JsonElement it : b.getAsJsonArray("items")) {
                        Item item = item(it.getAsString());
                        if (item != null) fl.add(item);
                    }
                    s.boxes.put(slot, fl);
                }
            }
            if (o.has("silkExtra")) {
                s.silkExtra.clear();
                for (JsonElement el : o.getAsJsonArray("silkExtra")) {
                    Block b = block(el.getAsString());
                    if (b != null) s.silkExtra.add(b);
                }
            }
            LOG.info("DeepMine: Einstellungen aus {} geladen", f);
        } catch (Exception ex) {
            // Eine kaputte Datei darf den Mod nicht lahmlegen. Standardwerte,
            // und beim naechsten Speichern wird sie sauber ueberschrieben.
            LOG.warn("DeepMine: konnte {} nicht lesen, benutze Standardwerte: {}", f, ex.toString());
            return new Settings();
        }
        return s;
    }

    private static int slot(JsonObject o, String key, int fallback, boolean optional) {
        if (!o.has(key)) return fallback;
        int v = o.get(key).getAsInt();
        if (v <= 0) return optional ? -1 : fallback;
        return Math.min(v, 9) - 1;
    }

    private static Item item(String id) {
        Identifier rl = Identifier.tryParse(id);
        Item i = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
        if (i == null) LOG.warn("DeepMine: unbekanntes Item in deepmine.json: {}", id);
        return i;
    }

    private static Block block(String id) {
        Identifier rl = Identifier.tryParse(id);
        Block b = rl == null ? null : BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
        if (b == null) LOG.warn("DeepMine: unbekannter Block in deepmine.json: {}", id);
        return b;
    }
}
