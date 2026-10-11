package de.reyollc.deepmine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Merkt sich den laufenden Auftrag in config/deepmine-job.json, damit
 * /deepmine resume auch nach Neustart oder Serverwechsel weiss, wo es weitergeht.
 *
 * Gespeichert wird nicht die Besucht-Liste (bis zu 10.000 Zellen), sondern nur
 * Startwerte und Schrittzahl: die Spiralregel ist deterministisch, Spiral.restore
 * spielt die Schritte nach und landet exakt im selben Zustand.
 */
final class JobStore {

    private static final Logger LOG = LoggerFactory.getLogger("deepmine");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("deepmine-job.json");
    }

    static void save(MinerTask t) {
        Spiral s = t.spiral();
        JsonObject o = new JsonObject();
        o.addProperty("dimension", t.dimension());
        o.addProperty("beaconX", t.origin().getX());
        o.addProperty("beaconY", t.origin().getY());
        o.addProperty("beaconZ", t.origin().getZ());
        o.addProperty("floorY", t.floorY());
        o.addProperty("height", t.height());
        o.addProperty("radius", s.radius());
        o.addProperty("coreRadius", s.coreRadius());
        o.addProperty("startX", s.startX());
        o.addProperty("startZ", s.startZ());
        o.addProperty("startFacing", s.startFacing());
        o.addProperty("steps", s.steps());
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOG.warn("DeepMine: konnte {} nicht schreiben: {}", file(), ex.toString());
        }
    }

    /** Auftrag von der Platte, gestoppt und bereit fuer resume. null wenn keiner da ist. */
    static MinerTask load() {
        Path f = file();
        if (!Files.exists(f)) return null;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            Spiral s = Spiral.restore(
                    o.get("radius").getAsInt(), o.get("coreRadius").getAsInt(),
                    o.get("startX").getAsInt(), o.get("startZ").getAsInt(),
                    o.get("startFacing").getAsInt(), o.get("steps").getAsInt());
            BlockPos beacon = new BlockPos(
                    o.get("beaconX").getAsInt(), o.get("beaconY").getAsInt(), o.get("beaconZ").getAsInt());
            MinerTask t = new MinerTask(beacon, o.get("floorY").getAsInt(), o.get("height").getAsInt(),
                    o.get("dimension").getAsString(), s);
            LOG.info("DeepMine: Auftrag aus {} geladen, {} Schritte", f, s.steps());
            return t;
        } catch (Exception ex) {
            LOG.warn("DeepMine: konnte {} nicht lesen: {}", f, ex.toString());
            return null;
        }
    }

    static void clear() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException ex) {
            LOG.warn("DeepMine: konnte {} nicht loeschen: {}", file(), ex.toString());
        }
    }
}
