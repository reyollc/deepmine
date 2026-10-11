package de.reyollc.deepmine;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Graebt einen 1 breiten, standardmaessig 4 hohen Gang als Auswaertsspirale um
 * einen Beacon, bis die Haste-II-Zone zu Ende ist.
 *
 * Abgebaut wird ausschliesslich ueber gameMode.continueDestroyBlock, also mit
 * genau der Methode, die Vanilla beim Halten der linken Maustaste benutzt.
 * Der Mod ist damit per Konstruktion nicht schneller als von Hand. Dasselbe
 * gilt fuer Setzen, Oeffnen und Abbauen der Shulker: alles ueber useItemOn und
 * continueDestroyBlock, keine handgebauten Packets.
 */
public final class MinerTask {

    private static final Set<Block> ORES = Set.of(
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);

    private static final Set<Block> SCULK = Set.of(
            Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SCULK_SENSOR,
            Blocks.SCULK_SHRIEKER, Blocks.SCULK_CATALYST);

    /** Angriffsreichweite im Ueberlebensmodus. Weiter weg wird nicht zugeschlagen. */
    private static final double ATTACK_RANGE = 3.0;
    /** Ab dieser Entfernung wird ein Gegner angegangen, nicht erst nach dem ersten Treffer. */
    private static final double ENGAGE_RANGE = 4.0;
    /** Umkreis, in dem nach einem Angreifer gesucht wird. */
    private static final double TARGET_SEARCH = 10.0;
    /** Ohne erreichbares Ziel so lange warten, dann weitergraben. */
    private static final int FIGHT_GIVEUP_TICKS = 40;
    /** Harte Obergrenze fuer einen Kampf, damit nichts endlos haengt. */
    private static final int FIGHT_MAX_TICKS = 400;
    /** Beim Heilen frueher essen: Regeneration laeuft erst ab Hunger 18. */
    private static final int EAT_AT_HEAL = 17;
    /** Heilen endet bei diesem Leben, bewusst ueber der 12er-Schwelle (Hysterese). */
    private static final float HEAL_UNTIL = 16.0f;
    /** Laenger als eine Minute ohne Erholung: dann stimmt etwas nicht. */
    private static final int HEAL_MAX_TICKS = 1200;
    /** Ab diesem Hungerwert (von 20) wird gegessen. Unter 6 kann man nicht sprinten. */
    private static final int EAT_AT = 12;
    /** Wartezeit auf Serverantworten beim Shulker-Handling. */
    private static final int STORE_TIMEOUT_TICKS = 100;
    /** Slots 0..26 eines Shulker-Menues sind die Kiste, alles danach der Spieler. */
    private static final int SHULKER_SLOTS = 27;

    private enum State { PICK, DIG, BRIDGE, WALK, LIGHT, FIGHT, HEAL, STORE, EAT, DONE }
    private enum StoreStep { PLACE, OPEN, MOVE, SETTLE, CLOSE, BREAK, RECLAIM }

    private static final Logger LOG = LoggerFactory.getLogger("deepmine");

    private final Minecraft mc = Minecraft.getInstance();
    private final Deque<BlockPos> digQueue = new ArrayDeque<>();

    private final BlockPos origin;   // Beacon
    private final int floorY;        // Y der Gangsohle
    private final int height;        // Ganghoehe, Standard 4
    private final String dimension;  // z.B. minecraft:overworld, damit resume nicht im Nether losläuft
    private final Spiral spiral;

    /** Wird bei Start, jedem betretenen Feld und am Ende gerufen - zum Speichern. */
    private Runnable progressListener;

    // Hotbar-Slots, 0-basiert. Bewusst manuell konfiguriert statt per
    // Verzauberungspruefung: die Enchantment-API wandert von Version zu Version.
    private int mineSlot = 0;
    private int silkSlot = 1;
    private int torchSlot = 2;
    private int swordSlot = -1;      // -1 = kein Schwert, dann wird nicht gekaempft
    private int floorSlot = -1;      // Baumaterial fuer Loecher im Boden, -1 = keins
    private int foodSlot = -1;       // Essen, -1 = keins, dann bleibt nur der Hunger-Abbruch

    // Hotbar-Slot mit leerem Shulker -> Item, das hineinsoll. Reihenfolge zaehlt.
    private final Map<Integer, BoxFilter> boxes = new LinkedHashMap<>();

    // Bloecke, die zusaetzlich zu den Erzen mit der Silk-Touch-Picke abgebaut
    // werden. Der Klassiker ist Kies: mit Glueck III droppt er nur noch Feuerstein.
    private final Set<Block> silkExtra = new HashSet<>();

    private int minDurability = 40;
    private double reach = 4.2;
    private float healthFloor = 12.0f;
    private double walkSpeed = 0.20;

    private State state = State.PICK;
    private State resumeState = State.PICK;   // wohin nach Kampf oder Einlagern zurueck
    private int[] currentStep;
    private BlockPos targetCell;
    private boolean oresScanned;
    private int noHasteTicks;
    private int lightDelay;
    private int stallTicks;
    private double lastDist = Double.MAX_VALUE;
    private boolean running;

    private float lastHealth = -1.0f;
    private int fightTicks;
    private int healTicks;
    private int fightCooldown;   // nach einem Timeout-Kampf kurz nicht wieder anfangen
    private int noTargetTicks;

    private StoreStep storeStep = StoreStep.PLACE;
    private BlockPos boxPos;
    private int boxSlot = -1;
    private BoxFilter boxFilter;
    private int storeWait;
    private int shulkersBeforeBreak;
    private int movedStacks;

    // Eine Kiste, die stehen bleiben musste, weil kein Platz fuer sie frei war.
    private BlockPos parkedPos;
    private int parkedSlot = -1;
    private BoxFilter parkedFilter;

    private int bridgeWait;

    private int settleWait;
    private boolean gravelTorchPlaced;

    private int eatTicks;
    private boolean wasEating;

    public MinerTask(BlockPos origin, int floorY, int radius, int coreRadius,
                     int height, int startX, int startZ, int facing, String dimension) {
        this(origin, floorY, height, dimension, new Spiral(radius, coreRadius, startX, startZ, facing));
    }

    /** Fuer JobStore: mit einer bereits nachgespielten Spirale. */
    MinerTask(BlockPos origin, int floorY, int height, String dimension, Spiral spiral) {
        this.origin = origin.immutable();
        this.floorY = floorY;
        this.height = height;
        this.dimension = dimension;
        this.spiral = spiral;
    }

    public BlockPos origin() { return origin; }
    public int floorY() { return floorY; }
    public int height() { return height; }
    public String dimension() { return dimension; }
    public Spiral spiral() { return spiral; }
    public boolean isDone() { return state == State.DONE; }

    public void setProgressListener(Runnable r) { this.progressListener = r; }

    private void progressed() {
        if (progressListener != null) progressListener.run();
    }

    /** Naechste Zelle: die angefangene, sonst die, die die Spirale als naechstes waehlt. */
    public BlockPos nextCell() {
        if (targetCell != null) return targetCell;
        int[] step = spiral.peek();
        if (step == null) return null;
        return new BlockPos(origin.getX() + step[0], floorY, origin.getZ() + step[1]);
    }

    public void setSlots(int mine, int silk, int torch) {
        this.mineSlot = mine;
        this.silkSlot = silk;
        this.torchSlot = torch;
    }

    public void setSwordSlot(int slot) { this.swordSlot = slot; }

    public void setFloorSlot(int slot) { this.floorSlot = slot; }

    public void setFoodSlot(int slot) { this.foodSlot = slot; }

    public void setSilkExtra(Set<Block> src) {
        silkExtra.clear();
        silkExtra.addAll(src);
    }

    /** Erz immer mit Silk Touch (Erzblock statt Fortune-Drops), dazu die Zusatzliste. */
    private boolean needsSilk(Block b) {
        return ORES.contains(b) || silkExtra.contains(b);
    }

    public void setBoxes(Map<Integer, BoxFilter> src) {
        boxes.clear();
        boxes.putAll(src);
    }

    public void setMinDurability(int v) { this.minDurability = v; }

    public boolean isRunning() { return running; }

    public void start() {
        running = true;
        state = State.PICK;
        noHasteTicks = 0;
        lastHealth = -1.0f;
        progressed();
    }

    /**
     * Weitermachen - nach Stopp, Ausleeren, Neustart oder Serverwechsel. Der
     * Spieler muss in der Naehe der naechsten Zelle stehen; sonst wird nur
     * gesagt, wo sie ist, statt loszulaufen. Liefert false, wenn nicht gestartet.
     */
    public boolean resume() {
        BlockPos next = nextCell();
        if (next == null) {
            Chat.info("deepmine.resume.done");
            return false;
        }
        if (mc.player != null) {
            double d = Math.sqrt(mc.player.blockPosition().distSqr(next));
            if (d > 6.0) {
                Chat.warn("deepmine.resume.too_far", Chat.pos(next), Chat.hl((int) d));
                return false;
            }
        }

        running = true;
        noHasteTicks = 0;
        stallTicks = 0;
        lastHealth = -1.0f;
        if (targetCell != null) {
            digQueue.clear();
            for (int dy = height - 1; dy >= 0; dy--) digQueue.add(targetCell.above(dy));
            oresScanned = false;
            settleWait = 0;
            gravelTorchPlaced = false;
            state = State.DIG;
        } else {
            state = State.PICK;
        }
        // Der Spieler hat umgepackt: Shulker neu verteilen, dann eine geparkte Kiste zuerst holen.
        if (mc.player != null) assignShulkers(mc.player);
        collectParked(mc.player);
        return true;
    }

    public void stop(Component reason) {
        running = false;
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        // Nie mit gedrueckter Benutzen-Taste aufhoeren, sonst isst oder baut
        // der Spieler nach dem Stopp von allein weiter.
        if (state == State.EAT) mc.options.keyUse.setDown(false);
        Chat.send(ChatFormatting.RED, reason);
    }

    public int ring() { return spiral.ring(); }
    public int cellsDone() { return spiral.cellsDone(); }

    /** Zustandsname in der Spielsprache. */
    public MutableComponent stateName() {
        return Chat.tr("deepmine.state." + state.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** "Ring 3, 120 Zellen erledigt, Zustand graebt." */
    public MutableComponent statusLine() {
        return Chat.tr("deepmine.status.line", Chat.hl(spiral.ring()), Chat.hl(spiral.cellsDone()), stateName());
    }

    // ------------------------------------------------------------------

    public void tick() {
        if (!running) return;

        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || mc.gameMode == null) {
            stop(Chat.tr("deepmine.stop.no_world"));
            return;
        }

        // Kampf hat Vorrang vor ALLEM, auch vor den Abbruchgruenden. Wer bei
        // niedrigem Leben stehen bleibt statt sich zu wehren, stirbt - und genau
        // das ist einmal passiert. Gekaempft wird, sobald ein Gegner in Reichweite
        // kommt oder Schaden ankommt, nicht erst nach dem ersten Treffer.
        if (fightCooldown > 0) fightCooldown--;
        if (swordSlot >= 0 && state != State.FIGHT && fightCooldown == 0) {
            LivingEntity threat = nearestHostile(p, level);
            boolean engage = threat != null
                    && (p.distanceTo(threat) <= ENGAGE_RANGE || tookDamage(p));
            if (engage) beginFight(p);
        }
        lastHealth = p.getHealth();

        Component abort = safetyCheck(p, level);
        if (abort != null && state != State.FIGHT) {
            stop(Chat.tr("deepmine.stop.at", abort, Chat.pos(p.blockPosition())));
            return;
        }

        // Niedriges Leben ohne Gegner in der Naehe: nicht stoppen, sondern heilen.
        // Ein gestoppter Mod hilft einem AFK-Spieler nicht, ein wartender schon.
        if (isMining() && p.getHealth() < healthFloor) {
            mc.gameMode.stopDestroyBlock();
            resumeState = state;
            state = State.HEAL;
            healTicks = 0;
            Chat.warn("deepmine.heal.start", Chat.hl((int) p.getHealth()));
        }

        // Hunger: essen, bevor der Abbruch greift. Nicht mitten im Kampf oder mit
        // offener Kiste. Beim Heilen frueher, weil Regeneration Hunger >= 18 braucht.
        int eatAt = state == State.HEAL ? EAT_AT_HEAL : EAT_AT;
        if (state != State.EAT && state != State.FIGHT && state != State.STORE
                && p.getFoodData().getFoodLevel() <= eatAt && hasFood(p)) {
            mc.gameMode.stopDestroyBlock();
            resumeState = state;
            state = State.EAT;
            eatTicks = 0;
            wasEating = false;
        }

        // Volles Inventar: erst versuchen, in einen mitgefuehrten Shulker einzulagern.
        if (isMining() && p.getInventory().getFreeSlot() < 0) {
            if (beginStore(p)) {
                mc.gameMode.stopDestroyBlock();
            } else if (parkedPos != null) {
                stop(Chat.tr("deepmine.stop.parked", Chat.pos(parkedPos)));
                return;
            } else {
                stop(Chat.tr("deepmine.stop.inventory_full", Chat.pos(p.blockPosition())));
                return;
            }
        }

        switch (state) {
            case PICK -> pick();
            case DIG -> dig(p, level);
            case BRIDGE -> bridge(p, level);
            case EAT -> eat(p);
            case WALK -> walk(p, level);
            case LIGHT -> light(p, level);
            case FIGHT -> fight(p, level);
            case HEAL -> heal(p, level);
            case STORE -> store(p, level);
            case DONE -> running = false;
        }
    }

    /** Die eigentlichen Grab-Zustaende, aus denen Sonderzustaende starten duerfen. */
    private boolean isMining() {
        return state == State.PICK || state == State.DIG || state == State.BRIDGE
                || state == State.WALK || state == State.LIGHT;
    }

    /** Gibt einen Abbruchgrund zurueck, oder null wenn alles in Ordnung ist. */
    private Component safetyCheck(LocalPlayer p, ClientLevel level) {
        // Ohne Schwert bleibt niedriges Leben ein Abbruchgrund - mehr als stehen
        // bleiben und melden kann der Mod dann nicht. Mit Schwert uebernehmen
        // Kampf und Heilen, siehe tick().
        if (swordSlot < 0 && p.getHealth() <= healthFloor) return Chat.tr("deepmine.abort.health", Chat.hl((int) healthFloor));
        if (p.getFoodData().getFoodLevel() <= 6 && !hasFood(p)) {
            return foodSlot < 0
                    ? Chat.tr("deepmine.abort.hunger_no_slot")
                    : Chat.tr("deepmine.abort.hunger_empty", Chat.hl(foodSlot + 1));
        }

        ItemStack tool = p.getInventory().getItem(mineSlot);
        if (tool.isEmpty()) return Chat.tr("deepmine.abort.no_tool", Chat.hl(mineSlot + 1));
        if (tool.isDamageableItem()
                && tool.getMaxDamage() - tool.getDamageValue() <= minDurability) {
            return Chat.tr("deepmine.abort.durability", Chat.hl(tool.getMaxDamage() - tool.getDamageValue()));
        }

        // Haste II ist die eigentliche Reichweitengrenze. Der Effekt haelt 17s und
        // wird alle 4s erneuert, deshalb erst nach 5s ohne ihn abbrechen.
        MobEffectInstance haste = p.getEffect(MobEffects.HASTE);
        if (haste == null || haste.getAmplifier() < 1) {
            if (++noHasteTicks > 100) return Chat.tr("deepmine.abort.no_haste");
        } else {
            noHasteTicks = 0;
        }

        // Der frueher hier stehende Abbruch "feindlicher Mob im Umkreis von 12" ist
        // entfallen: mit Schwert wird gekaempft statt gestoppt, und untertage haette
        // er ohnehin dauernd ausgeloest. Ohne konfiguriertes Schwert bleibt das
        // Leben-unter-12 der Notausstieg.
        return null;
    }

    /** true, wenn seit dem letzten Tick Leben verloren ging. */
    private boolean tookDamage(LocalPlayer p) {
        return lastHealth >= 0.0f && p.getHealth() < lastHealth;
    }

    private void pick() {
        int[] step = spiral.peek();
        if (step == null) {
            state = State.DONE;
            running = false;
            Chat.ok("deepmine.done", Chat.hl(spiral.cellsDone()), Chat.hl(spiral.ring()));
            progressed();
            return;
        }
        currentStep = step;
        targetCell = new BlockPos(origin.getX() + step[0], floorY, origin.getZ() + step[1]);
        digQueue.clear();
        for (int dy = height - 1; dy >= 0; dy--) digQueue.add(targetCell.above(dy));
        oresScanned = false;
        settleWait = 0;
        gravelTorchPlaced = false;
        state = State.DIG;
    }

    private void dig(LocalPlayer p, ClientLevel level) {
        // Rieselt gerade etwas in die Saeule (Kies, Sand)? Dann nichts anfassen:
        // der naechste Abbau wuerde nur den naechsten Block loesen, bevor der
        // vorige gelandet ist. Waehrenddessen, wenn moeglich, der Fackel-Trick.
        if (fallingNear(p, level)) {
            gravelTorch(p, level);
            if (++settleWait > 300) {
                stop(Chat.tr("deepmine.stop.falling", Chat.pos(targetCell)));
            }
            return;
        }

        // Fertige Bloecke aussortieren.
        while (!digQueue.isEmpty() && level.getBlockState(digQueue.peek()).isAir()) {
            digQueue.poll();
        }

        if (digQueue.isEmpty() && !oresScanned) {
            // Bevor die Saeule als fertig gilt: alles wieder einreihen, was
            // waehrend des Grabens hineingerutscht ist. Eine Fackel vom Trick
            // zaehlt mit, die ist in einem Tick weg.
            for (int dy = height - 1; dy >= 0; dy--) {
                BlockPos c = targetCell.above(dy);
                if (!level.getBlockState(c).isAir()) digQueue.add(c);
            }
            if (digQueue.isEmpty() && aboutToFall(level)) {
                // Der Block ueber der Saeule hat keinen Halt mehr. Vanilla laesst
                // ihn in zwei Ticks los; darauf warten statt in ihn hineinzulaufen.
                if (++settleWait > 300) {
                    stop(Chat.tr("deepmine.stop.hanging", Chat.pos(targetCell)));
                }
                return;
            }
            if (digQueue.isEmpty()) {
                oresScanned = true;
                Component danger = scanNeighbours(level);
                if (danger != null) { stop(danger); return; }
                // Gefundenes Erz faellt unten direkt in den Abbau, ohne Wartetick.
            }
        }
        if (digQueue.isEmpty()) {
            mc.gameMode.stopDestroyBlock();
            bridgeWait = 0;
            state = State.BRIDGE;
            bridge(p, level); // Boden sofort pruefen, nicht erst naechsten Tick
            return;
        }

        BlockPos pos = digQueue.peek();
        BlockState st = level.getBlockState(pos);

        if (!inReach(p, pos)) {
            stop(Chat.tr("deepmine.stop.out_of_reach", Chat.pos(pos)));
            return;
        }

        Component fluid = fluidNextTo(level, pos);
        if (fluid != null) {
            stop(Chat.tr("deepmine.stop.fluid_behind", fluid, Chat.pos(pos)));
            return;
        }

        // Erz und Zusatzliste mit Silk Touch, alles andere mit der normalen Picke.
        selectSlot(p, needsSilk(st.getBlock()) ? silkSlot : mineSlot);

        lookAt(p, Vec3.atCenterOf(pos));
        if (mc.gameMode.continueDestroyBlock(pos, faceToward(p.getEyePosition(), pos))) {
            Compat.swingAttack(p);
        }
    }

    /**
     * Nachbarn der frisch geoeffneten Saeule absuchen. Erz kommt in die Queue,
     * Sculk fuehrt zum sofortigen Abbruch: der Mod schleicht nicht und wuerde
     * jeden Shrieker ausloesen.
     */
    private Component scanNeighbours(ClientLevel level) {
        for (int dy = 0; dy < height; dy++) {
            BlockPos c = targetCell.above(dy);
            // Beim Abbauen wird das schon vorher geprueft. Hier faengt es den Fall,
            // dass die Saeule bereits offen war (natuerliche Hoehle) und deshalb
            // nie ein Block abgebaut und nie geprueft wurde.
            Component fluid = fluidNextTo(level, c);
            if (fluid != null) {
                return Chat.tr("deepmine.stop.fluid_next", fluid, Chat.pos(c));
            }
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                Block b = level.getBlockState(n).getBlock();
                if (SCULK.contains(b)) {
                    return Chat.tr("deepmine.stop.sculk", Chat.pos(n));
                }
                if (ORES.contains(b) && !digQueue.contains(n)) {
                    digQueue.add(n);
                }
            }
        }
        return null;
    }

    // ------------------------- Nachrutschendes --------------------------

    /** Faellt gerade ein Block (als Entity) in oder ueber der Saeule? */
    private boolean fallingNear(LocalPlayer p, ClientLevel level) {
        AABB box = new AABB(targetCell.getX(), floorY, targetCell.getZ(),
                            targetCell.getX() + 1, floorY + height + 12, targetCell.getZ() + 1);
        return !level.getEntities(p, box, e -> e instanceof FallingBlockEntity).isEmpty();
    }

    /**
     * Haengt ueber der fertigen Saeule ein Kies/Sand ohne Halt? Geprueft mit
     * FallingBlock.isFree, also exakt Vanillas eigener Regel dafuer, ob er
     * beim naechsten Tick loslaesst.
     */
    private boolean aboutToFall(ClientLevel level) {
        BlockPos top = targetCell.above(height);
        if (!(level.getBlockState(top).getBlock() instanceof FallingBlock)) return false;
        return FallingBlock.isFree(level.getBlockState(top.below()));
    }

    /**
     * Der Fackel-Trick, wie von Hand: sobald der erste Kies faellt, eine Fackel
     * in den obersten Platz der Saeule stellen. Jeder fallende Block landet
     * darauf, kann dort nicht zum Block werden und droppt stattdessen als
     * Item - ein ganzes Nest rieselt so in Sekunden durch, statt Block fuer
     * Block mit der Picke abgebaut zu werden.
     *
     * Wichtig ist das "sobald der erste faellt": eine Fackel, die schon da
     * ist, bevor er loslaesst, stuetzt ihn stattdessen (isFree ist dann false).
     * Deshalb wird erst gesetzt, wenn ein FallingBlockEntity zu sehen ist.
     * Klappt es nicht, greift der langsame Weg oben von selbst.
     */
    private void gravelTorch(LocalPlayer p, ClientLevel level) {
        if (gravelTorchPlaced || torchSlot < 0) return;
        if (p.getInventory().getItem(torchSlot).isEmpty()) return;

        BlockPos top = targetCell.above(height - 1);
        BlockPos under = top.below();
        if (!level.getBlockState(top).isAir()) return;
        if (!level.getBlockState(under).isFaceSturdy(level, under, Direction.UP)) return;
        if (!inReach(p, top)) return;

        gravelTorchPlaced = true; // ein Versuch pro Saeule
        Vec3 hit = new Vec3(under.getX() + 0.5, under.getY() + 1.0, under.getZ() + 0.5);
        selectSlot(p, torchSlot);
        lookAt(p, hit);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.UP, under, false));
        Compat.swingUse(p);
    }

    private void walk(LocalPlayer p, ClientLevel level) {
        double gx = targetCell.getX() + 0.5;
        double gz = targetCell.getZ() + 0.5;
        double dx = gx - p.getX();
        double dz = gz - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist < 0.15) {
            p.setDeltaMovement(0.0, p.getDeltaMovement().y, 0.0);
            spiral.commit(currentStep);
            targetCell = null; // ab hier ist die naechste Zelle wieder Sache der Spirale
            progressed();
            // Die Saeule wurde vor dem Laufen gegraben, das Licht hatte also die
            // ganze Laufzeit zum Nachziehen. Kein Extra-Warten: Fackel pruefen und
            // die naechste Zelle waehlen passiert noch im selben Tick, gegraben
            // wird ab dem naechsten.
            lightDelay = 0;
            state = State.LIGHT;
            light(p, level);
            if (state == State.PICK) pick();
            return;
        }

        // Steckenbleiben erkennen statt endlos gegen eine Wand zu laufen.
        if (dist >= lastDist - 0.001) {
            if (++stallTicks > 60) {
                stop(Chat.tr("deepmine.stop.stuck", Chat.pos(p.blockPosition())));
                return;
            }
        } else {
            stallTicks = 0;
        }
        lastDist = dist;

        lookAt(p, new Vec3(gx, p.getEyeY(), gz));
        double v = Math.min(walkSpeed, dist);
        p.setDeltaMovement(dx / dist * v, p.getDeltaMovement().y, dz / dist * v);
    }

    /**
     * Fackel nur bei Blocklicht 0. Das ist seit 1.18 exakt die Spawn-Bedingung
     * fuer feindliche Mobs, und weil eine Fackel 14 gibt und pro Block eins
     * verliert, ergibt das von selbst rund alle 14 Bloecke eine.
     */
    private void light(LocalPlayer p, ClientLevel level) {
        if (lightDelay-- > 0) return; // Lichtengine hinkt dem Abbau ein paar Ticks hinterher
        state = State.PICK;

        if (torchSlot < 0) return;
        if (p.getInventory().getItem(torchSlot).isEmpty()) return;

        BlockPos feet = p.blockPosition();
        if (level.getBrightness(LightLayer.BLOCK, feet) > 0) return;

        BlockPos floor = feet.below();
        if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) return;

        selectSlot(p, torchSlot);
        Vec3 hit = new Vec3(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5);
        lookAt(p, hit);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.UP, floor, false));
        Compat.swingUse(p);
    }

    // ---------------------------- Essen --------------------------------

    /** Liegt im Essen-Slot etwas Essbares? Geprueft ueber die FOOD-Komponente. */
    private boolean hasFood(LocalPlayer p) {
        if (foodSlot < 0) return false;
        ItemStack st = p.getInventory().getItem(foodSlot);
        return !st.isEmpty() && st.has(DataComponents.FOOD);
    }

    /**
     * Essen heisst in Vanilla: Rechtsklick 32 Ticks lang HALTEN. Minecraft.tick
     * prueft jeden Tick "isUsingItem && !keyUse.isDown -> releaseUsingItem", ein
     * blosser useItem-Aufruf wuerde also im naechsten Tick wieder abgebrochen.
     * Deshalb wird die Benutzen-Taste fuer die Dauer des Essens gedrueckt gehalten
     * und danach wieder losgelassen - in jedem Ausgang dieser Methode.
     */
    private void eat(LocalPlayer p) {
        eatTicks++;

        if (!hasFood(p)) {
            release();
            state = resumeState;
            return;
        }
        if (eatTicks > 120) {
            release();
            stop(Chat.tr("deepmine.stop.eat_failed", Chat.hl(foodSlot + 1), Chat.pos(p.blockPosition())));
            return;
        }

        selectSlot(p, foodSlot);
        // Blick zur Decke: dort ist garantiert nichts, womit ein Rechtsklick
        // interagieren koennte.
        p.setXRot(-90.0f);

        if (p.isUsingItem()) {
            wasEating = true;
            mc.options.keyUse.setDown(true);
            return;
        }
        if (wasEating) {
            // Fertig gegessen. Reicht es noch nicht, loest der Tick-Check
            // einfach die naechste Runde aus.
            release();
            state = resumeState;
            return;
        }
        mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
    }

    private void release() {
        mc.options.keyUse.setDown(false);
        if (mc.player != null && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
    }

    // ---------------------------- Kampf --------------------------------

    /**
     * In den Kampf wechseln, aus jedem Zustand. Offene Kisten werden geschlossen
     * und die Einlagerung spaeter an derselben Stelle fortgesetzt, Essen wird
     * losgelassen. Was gerade lief, wird danach wieder aufgenommen.
     */
    private void beginFight(LocalPlayer p) {
        mc.gameMode.stopDestroyBlock();
        switch (state) {
            case STORE -> {
                // Kiste zu, aber gesetzt lassen: storePlace findet sie wieder und
                // macht bei OPEN weiter, statt einen zweiten Shulker zu setzen.
                if (p.containerMenu instanceof ShulkerBoxMenu menu) {
                    p.connection.send(new ServerboundContainerClosePacket(menu.containerId));
                    mc.setScreenAndShow(null);
                }
                // Ist die Kiste schon abgebaut, geht es nach dem Kampf beim Zurueckholen
                // weiter, nicht beim Setzen - sonst wuerde ein zweiter Shulker gesetzt.
                boolean broken = boxPos != null && mc.level != null && mc.level.getBlockState(boxPos).isAir();
                if (storeStep == StoreStep.BREAK && broken) storeStep = StoreStep.RECLAIM;
                if (storeStep != StoreStep.RECLAIM) storeStep = StoreStep.PLACE;
                storeWait = 0;
                resumeState = State.STORE;
            }
            case EAT -> {
                release();
                // resumeState zeigt schon dorthin, wo das Essen hin zurueck wollte.
            }
            case HEAL -> resumeState = State.HEAL;
            default -> resumeState = state;
        }
        state = State.FIGHT;
        fightTicks = 0;
        noTargetTicks = 0;
        Chat.warn("deepmine.fight.start");
    }

    /**
     * Zugeschlagen wird nur bei vollem Angriffsbalken, sonst waere der Schaden
     * ein Bruchteil - genau wie von Hand. Wer ausser Reichweite steht (Skelett,
     * zoegernder Zombie), wird Schritt fuer Schritt angegangen, jeder Schritt
     * nur auf gepruefen Boden. Creeper werden nie angelaufen. Niedriges Leben
     * bricht den Kampf NICHT ab: stehen bleiben waere immer schlechter.
     */
    private void fight(LocalPlayer p, ClientLevel level) {
        if (++fightTicks > FIGHT_MAX_TICKS) {
            fightCooldown = 100;
            endFight(p, "deepmine.fight.timeout");
            return;
        }

        LivingEntity target = nearestHostile(p, level);
        if (target == null) {
            p.setDeltaMovement(0.0, p.getDeltaMovement().y, 0.0);
            if (++noTargetTicks > FIGHT_GIVEUP_TICKS) endFight(p, null);
            return;
        }
        noTargetTicks = 0;
        selectSlot(p, swordSlot);
        lookAt(p, target.getEyePosition());

        if (p.distanceTo(target) > ATTACK_RANGE) {
            approach(p, level, target);
            return;
        }
        p.setDeltaMovement(0.0, p.getDeltaMovement().y, 0.0);

        if (p.getAttackStrengthScale(0.0f) >= 1.0f) {
            mc.gameMode.attack(p, target);
            Compat.swingAttack(p);
        }
    }

    private void endFight(LocalPlayer p, String msgKey) {
        p.setDeltaMovement(0.0, p.getDeltaMovement().y, 0.0);
        state = resumeState;
        noTargetTicks = 0;
        if (msgKey != null) Chat.warn(msgKey);
    }

    /**
     * Einen Schritt auf den Gegner zu, aber nur, wenn der Block unter dem
     * naechsten Schritt traegt. Im 1 breiten Gang ist das praktisch immer der
     * eigene Gang; in eine Hoehle hinein fuehrt es nie, weil dort der Boden
     * fehlen kann und die Pruefung dann verweigert.
     */
    private void approach(LocalPlayer p, ClientLevel level, LivingEntity target) {
        if (target instanceof Creeper) {
            p.setDeltaMovement(0.0, p.getDeltaMovement().y, 0.0);
            return;
        }
        double dx = target.getX() - p.getX();
        double dz = target.getZ() - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) return;

        BlockPos next = BlockPos.containing(p.getX() + dx / dist, p.getY(), p.getZ() + dz / dist);
        BlockPos under = next.below();
        if (level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
            p.setDeltaMovement(0.0, p.getDeltaMovement().y, 0.0);
            return;
        }
        double v = Math.min(walkSpeed, dist);
        p.setDeltaMovement(dx / dist * v, p.getDeltaMovement().y, dz / dist * v);
    }

    // ---------------------------- Heilen -------------------------------

    /**
     * Bei niedrigem Leben ohne Gegner: stehen bleiben und regenerieren lassen.
     * Essen uebernimmt der Tick-Check mit hoeherer Schwelle, weil Regeneration
     * erst ab Hunger 18 laeuft. Taucht ein Gegner auf, uebernimmt der Kampf und
     * kommt danach hierher zurueck.
     */
    private void heal(LocalPlayer p, ClientLevel level) {
        p.setDeltaMovement(0.0, p.getDeltaMovement().y, 0.0);

        if (p.getHealth() >= HEAL_UNTIL) {
            state = resumeState;
            Chat.ok("deepmine.heal.done", Chat.hl((int) p.getHealth()));
            return;
        }
        if (p.getHealth() < 6.0f && nearestHostile(p, level) == null) {
            stop(Chat.tr("deepmine.stop.losing_health", Chat.hl((int) p.getHealth())));
            return;
        }
        if (++healTicks > HEAL_MAX_TICKS) {
            stop(Chat.tr("deepmine.stop.no_regen", Chat.hl((int) p.getHealth())));
        }
    }

    private LivingEntity nearestHostile(LocalPlayer p, ClientLevel level) {
        AABB box = p.getBoundingBox().inflate(TARGET_SEARCH);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        // Nur mit Sichtlinie: durch eine Wand hindurch kann weder er uns noch wir
        // ihn treffen, und ein Angriff durch die Wand koennte ein Mensch auch nicht.
        for (Entity e : level.getEntities(p, box, x -> x instanceof Enemy && x.isAlive() && p.hasLineOfSight(x))) {
            if (!(e instanceof LivingEntity le)) continue;
            double d = p.distanceToSqr(e);
            if (d < bestDist) { bestDist = d; best = le; }
        }
        return best;
    }

    // ---------------------------- Boden --------------------------------

    /**
     * Zwischen Graben und Laufen: liegt unter der Zielzelle etwas, worauf man
     * stehen kann? Wenn nicht, erst Baumaterial setzen. Geprueft wird die
     * Kollisionsform, nicht "ist Luft" - ein Loch ist alles, was den Spieler
     * nicht traegt, egal was darin herumsteht.
     */
    private void bridge(LocalPlayer p, ClientLevel level) {
        BlockPos below = targetCell.below();
        BlockState st = level.getBlockState(below);

        if (!st.getCollisionShape(level, below).isEmpty()) {
            lastDist = Double.MAX_VALUE;
            stallTicks = 0;
            state = State.WALK;
            return;
        }

        if (floorSlot < 0) {
            stop(Chat.tr("deepmine.stop.hole_no_slot", Chat.pos(below)));
            return;
        }
        if (p.getInventory().getItem(floorSlot).isEmpty()) {
            stop(Chat.tr("deepmine.stop.hole_empty", Chat.pos(below), Chat.hl(floorSlot + 1)));
            return;
        }
        if (!st.canBeReplaced()) {
            stop(Chat.tr("deepmine.stop.hole_blocked", Chat.pos(below), Settings.name(st.getBlock())));
            return;
        }
        if (++bridgeWait > STORE_TIMEOUT_TICKS) {
            stop(Chat.tr("deepmine.stop.hole_failed", Chat.pos(below)));
            return;
        }
        // Alle 10 Ticks ein Versuch; dazwischen Zeit fuer die Serverantwort.
        if (bridgeWait % 10 != 1) return;

        if (!tryPlace(p, level, below, floorSlot)) {
            stop(Chat.tr("deepmine.stop.hole_no_support", Chat.pos(below)));
        }
    }

    /**
     * Setzt den Block aus slot auf pos, indem eine angrenzende feste Flaeche
     * angeklickt wird - so, wie es Vanilla beim Rechtsklick auch tut. Unten
     * zuerst, dann die Seiten: beim Bodenbau ist der Block unter der eigenen
     * Zelle fast immer der Halt. Liefert false, wenn nichts zum Anklicken da ist.
     */
    private boolean tryPlace(LocalPlayer p, ClientLevel level, BlockPos pos, int slot) {
        Direction[] order = { Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                              Direction.WEST, Direction.EAST, Direction.UP };
        for (Direction d : order) {
            BlockPos support = pos.relative(d);
            Direction face = d.getOpposite();
            if (!level.getBlockState(support).isFaceSturdy(level, support, face)) continue;

            selectSlot(p, slot);
            Vec3 hit = Vec3.atCenterOf(support).add(
                    face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            lookAt(p, hit);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, face, support, false));
            Compat.swingUse(p);
            return true;
        }
        return false;
    }

    // -------------------------- Einlagern -------------------------------

    /**
     * Sucht den naechsten benutzbaren Shulker-Slot und startet den Einlagerzyklus:
     * setzen, oeffnen, fuellen, schliessen, abbauen, aufsammeln. Kein Rueckweg,
     * kein Depot, keine Wegfindung - der Shulker kommt mit.
     */
    private boolean beginStore(LocalPlayer p) {
        // Erst die Shulker nach Inhalt auf die Slots verteilen: ein voller im Slot
        // wird gegen einen passenden Ersatz aus dem Inventar getauscht.
        assignShulkers(p);

        // Die Kiste nehmen, die am meisten Platz schafft - nicht die erste, die
        // irgendetwas nehmen wuerde. Sonst gewinnt eine "alles ausser X"-Kiste
        // schon wegen eines einzelnen Tuff, waehrend das Inventar voller X ist.
        int bestSlot = -1;
        int bestGain = 0;
        for (Map.Entry<Integer, BoxFilter> e : boxes.entrySet()) {
            int slot = e.getKey();
            if (parkedPos != null && slot == parkedSlot) continue;
            ItemStack st = p.getInventory().getItem(slot);
            // Nur ein Shulker, dessen Inhalt zum Filter passt - sonst wuerde die
            // Kiste gemischt, und genau das soll die Sortierung ja verhindern.
            if (candidateScore(st, e.getValue()) < 0) continue;

            int gain = Math.min(countStorable(p, e.getValue()), shulkerFreeSlots(st));
            if (gain > bestGain) {
                bestGain = gain;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) return false;

        boxSlot = bestSlot;
        boxFilter = boxes.get(bestSlot);
        boxPos = null;
        storeStep = StoreStep.PLACE;
        storeWait = 0;
        resumeState = state;
        state = State.STORE;
        return true;
    }

    /** Wie viele Stapel dieser Filter aus Hotbar und Hauptinventar nehmen wuerde. */
    private int countStorable(LocalPlayer p, BoxFilter filter) {
        int n = 0;
        // Nur die 36 Plaetze, die ein Kistenmenue ueberhaupt zeigt. Ruestung und
        // Zweithand zaehlen nicht, die kann der Shift-Klick nicht erreichen.
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (storable(i, p.getInventory().getItem(i), filter)) n++;
        }
        return n;
    }

    /**
     * Freie Plaetze in einem Shulker-Item, gelesen aus der CONTAINER-Komponente.
     * Ein wieder aufgesammelter voller Shulker landet sonst im selben Slot und
     * wuerde beim naechsten Mal erneut gesetzt, geoeffnet und leer wieder abgebaut.
     */
    private static int shulkerFreeSlots(ItemStack st) {
        ItemContainerContents c = st.get(DataComponents.CONTAINER);
        if (c == null) return SHULKER_SLOTS;
        return SHULKER_SLOTS - (int) c.nonEmptyItemCopyStream().count();
    }

    /**
     * Darf der Stapel aus Inventarplatz invSlot in die Kiste?
     *
     * Der Schutz haengt am Slot, nicht am Item: sonst wuerde ein EXCEPT- oder
     * ALL-Filter beim ersten Einlagern Picke, Fackeln und Schwert wegpacken.
     * Shulker selbst bleiben immer draussen, egal wo sie liegen - sonst wandert
     * der naechste leere Shulker in den gerade gesetzten hinein.
     */
    private boolean storable(int invSlot, ItemStack st, BoxFilter filter) {
        if (st.isEmpty()) return false;
        if (invSlot == mineSlot || invSlot == silkSlot || invSlot == torchSlot
                || invSlot == swordSlot || invSlot == floorSlot || invSlot == foodSlot) return false;
        if (boxes.containsKey(invSlot)) return false;
        if (Block.byItem(st.getItem()) instanceof ShulkerBoxBlock) return false;
        // Massenkiste: angebrochene Stapel bleiben, bis sie voll sind.
        if (filter.fullStacksOnly() && st.getCount() < st.getMaxStackSize()) return false;
        return filter.accepts(st.getItem());
    }

    private void store(LocalPlayer p, ClientLevel level) {
        switch (storeStep) {
            case PLACE -> storePlace(p, level);
            case OPEN -> storeOpen(p, level);
            case MOVE -> storeMove(p);
            case SETTLE -> storeSettle(p, level);
            case CLOSE -> storeClose(p);
            case BREAK -> storeBreak(p, level);
            case RECLAIM -> storeReclaim(p, level);
        }
    }

    private void storePlace(LocalPlayer p, ClientLevel level) {
        if (boxPos != null && level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock) {
            storeStep = StoreStep.OPEN;
            storeWait = 0;
            return;
        }
        if (++storeWait > STORE_TIMEOUT_TICKS) {
            abortStore("deepmine.store.place_failed");
            return;
        }

        BlockPos base = p.blockPosition();
        // Kopfhoehe plus eins und darueber sind im 4 hohen Gang frei. Der Boden
        // bleibt fuer die Fackel reserviert.
        for (BlockPos cand : new BlockPos[] { base.above(2), base.above(3) }) {
            if (!level.getBlockState(cand).isAir()) continue;
            if (tryPlace(p, level, cand, boxSlot)) {
                boxPos = cand.immutable();
                return;
            }
        }
        abortStore("deepmine.store.no_space");
    }

    private void storeOpen(LocalPlayer p, ClientLevel level) {
        if (p.containerMenu instanceof ShulkerBoxMenu) {
            storeStep = StoreStep.MOVE;
            storeWait = 0;
            movedStacks = 0;
            return;
        }
        if (++storeWait > STORE_TIMEOUT_TICKS) {
            abortStore("deepmine.store.open_failed");
            return;
        }
        if (!(level.getBlockState(boxPos).getBlock() instanceof ShulkerBoxBlock)) {
            abortStore("deepmine.store.gone");
            return;
        }
        // Nur alle 10 Ticks anklopfen, sonst prasseln Oeffnen-Pakete auf den Server.
        if (storeWait % 10 != 1) return;

        Vec3 hit = Vec3.atCenterOf(boxPos);
        lookAt(p, hit);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(hit, Direction.UP, boxPos, false));
    }

    /**
     * Ein Stapel pro Tick per Shift-Klick hinueber. Die ersten 27 Menue-Slots sind
     * der Shulker, alles danach ist das Spielerinventar.
     */
    private void storeMove(LocalPlayer p) {
        if (!(p.containerMenu instanceof ShulkerBoxMenu menu)) {
            storeStep = StoreStep.BREAK;
            storeWait = 0;
            return;
        }
        if (++storeWait > STORE_TIMEOUT_TICKS * 3) {
            storeStep = StoreStep.CLOSE;
            storeWait = 0;
            return;
        }

        if (!boxFull(menu) && quickMoveOne(p, menu, boxFilter)) return; // einer pro Tick
        storeStep = StoreStep.SETTLE;
        storeWait = 0;
    }

    /**
     * Bevor die Kiste zugeht: sicherstellen, dass der Shulker nachher auch wieder
     * ins Inventar passt. Das Inventar war voll, also liegen rundherum Drops, die
     * jeden frei werdenden Platz sofort besetzen - und ein aufgesammeltes Item
     * landet im ERSTEN freien Slot, nicht in dem, aus dem es kam. Deshalb hier,
     * solange die Kiste noch offen ist: Drops in Reichweite hereinkommen lassen
     * und passende Stapel weiter hineinschieben. Erst wenn ein Platz frei ist und
     * nichts mehr herumliegt, wird geschlossen.
     *
     * In die Kiste kommt NUR, was ihr Filter erlaubt. Ist kein Platz frei und
     * nichts Passendes mehr da, ist das schlicht die falsche Kiste fuer das, was
     * im Inventar liegt: sie bleibt stehen, die andere Kiste ist dran, und
     * hinterher wird diese hier abgeholt (siehe parkBox / collectParked).
     */
    private void storeSettle(LocalPlayer p, ClientLevel level) {
        if (!(p.containerMenu instanceof ShulkerBoxMenu menu)) {
            storeStep = StoreStep.BREAK;
            storeWait = 0;
            return;
        }
        boolean freeSlot = p.getInventory().getFreeSlot() >= 0;
        boolean dropsNear = !level.getEntities(p, p.getBoundingBox().inflate(1.0, 0.5, 1.0),
                e -> e instanceof ItemEntity).isEmpty();

        if ((freeSlot && !dropsNear) || ++storeWait > 200) {
            storeStep = StoreStep.CLOSE;
            storeWait = 0;
            return;
        }
        if (!freeSlot) {
            if (!boxFull(menu) && quickMoveOne(p, menu, boxFilter)) return;
            parkBox(p);
        }
        // Sonst: Platz ist frei, Drops kommen gerade herein. Naechsten Tick nochmal.
    }

    /**
     * Kiste schliessen und gesetzt lassen. Der Slot gilt als verbraucht (er ist
     * ja leer), damit beginStore die andere Kiste waehlt. Sobald ein Zyklus
     * Platz gemacht hat, holt collectParked sie ab.
     */
    private void parkBox(LocalPlayer p) {
        if (p.containerMenu instanceof ShulkerBoxMenu menu) {
            p.connection.send(new ServerboundContainerClosePacket(menu.containerId));
        }
        mc.setScreenAndShow(null);
        parkedPos = boxPos;
        parkedSlot = boxSlot;
        parkedFilter = boxFilter;
        state = resumeState;
        Chat.warn("deepmine.store.parked", Chat.pos(parkedPos));
    }

    /** Geparkte Kiste wieder aufnehmen: sie steht noch, also direkt bei OPEN weiter. */
    private boolean collectParked(LocalPlayer p) {
        if (parkedPos == null) return false;
        if (mc.level == null || !(mc.level.getBlockState(parkedPos).getBlock() instanceof ShulkerBoxBlock)) {
            parkedPos = null; // weg, nichts zu holen
            return false;
        }
        boxPos = parkedPos;
        boxSlot = parkedSlot;
        boxFilter = parkedFilter;
        parkedPos = null;
        storeStep = StoreStep.OPEN;
        storeWait = 0;
        if (state != State.STORE) resumeState = state;
        state = State.STORE;
        return true;
    }

    private static boolean boxFull(ShulkerBoxMenu menu) {
        for (int i = 0; i < SHULKER_SLOTS && i < menu.slots.size(); i++) {
            if (!menu.getSlot(i).hasItem()) return false;
        }
        return true;
    }

    /** Einen passenden Stapel aus dem Spielerinventar per Shift-Klick in die Kiste. */
    private boolean quickMoveOne(LocalPlayer p, ShulkerBoxMenu menu, BoxFilter filter) {
        for (int i = SHULKER_SLOTS; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            // Nur Slots des Spielerinventars, und davon den Inventarplatz ueber
            // getContainerSlot(). NICHT slot.index: das ist die Position im Menue,
            // Hotbar 1 hat dort die 54 - damit griff der Slot-Schutz frueher nie.
            if (slot.container != p.getInventory()) continue;
            if (!storable(slot.getContainerSlot(), slot.getItem(), filter)) continue;
            LOG.debug("DeepMine: schiebe {}x{} aus Inventarplatz {} in die Kiste (Menue-Slot {})",
                    slot.getItem().getCount(), slot.getItem().getItem(), slot.getContainerSlot(), i);
            mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
            movedStacks++;
            return true;
        }
        return false;
    }

    private void storeClose(LocalPlayer p) {
        shulkersBeforeBreak = countShulkers(p);
        if (p.containerMenu instanceof ShulkerBoxMenu menu) {
            LOG.info("DeepMine: Kiste zu nach {} Verschiebungen, Client-Sicht: {}", movedStacks, boxCounts(menu));
            p.connection.send(new ServerboundContainerClosePacket(menu.containerId));
        }
        mc.setScreenAndShow(null);
        storeStep = StoreStep.BREAK;
        storeWait = 0;
    }

    private void storeBreak(LocalPlayer p, ClientLevel level) {
        if (boxPos == null || level.getBlockState(boxPos).isAir()) {
            mc.gameMode.stopDestroyBlock();
            storeStep = StoreStep.RECLAIM;
            storeWait = 0;
            return;
        }
        if (++storeWait > STORE_TIMEOUT_TICKS * 3) {
            abortStore("deepmine.store.break_failed");
            return;
        }
        if (!inReach(p, boxPos)) {
            abortStore("deepmine.store.out_of_reach");
            return;
        }

        selectSlot(p, mineSlot);
        lookAt(p, Vec3.atCenterOf(boxPos));
        if (mc.gameMode.continueDestroyBlock(boxPos, faceToward(p.getEyePosition(), boxPos))) {
            Compat.swingAttack(p);
        }
    }

    /**
     * Auf das Aufsammeln des abgebauten Shulkers warten, dann die Shulker nach
     * Inhalt neu verteilen. Wird er binnen drei Sekunden nicht aufgesammelt,
     * geht es trotzdem weiter: Items liegen fuenf Minuten, der naechste Zyklus
     * macht Platz, und die Verteilung sortiert ihn dann ein.
     */
    private void storeReclaim(LocalPlayer p, ClientLevel level) {
        if (countShulkers(p) > shulkersBeforeBreak || ++storeWait > 60) finishStore(p);
    }

    private void finishStore(LocalPlayer p) {
        state = resumeState;
        // Was steckt jetzt wirklich drin? Aus der Komponente des aufgesammelten
        // Items, also die Server-Wahrheit - nicht die Client-Vorhersage.
        int stacks = 0, items = 0, smallest = Integer.MAX_VALUE;
        ItemStack box = findShulkerAnywhere(p, boxFilter);
        if (box != null) {
            ItemContainerContents c = box.get(DataComponents.CONTAINER);
            if (c != null) {
                for (ItemStack s : (Iterable<ItemStack>) c.nonEmptyItemCopyStream()::iterator) {
                    stacks++;
                    items += s.getCount();
                    smallest = Math.min(smallest, s.getCount());
                }
            }
            LOG.info("DeepMine: Shulker nach dem Aufsammeln: {} Stapel, {} Items, kleinster Stapel {}",
                    stacks, items, stacks == 0 ? 0 : smallest);
        }
        Chat.ok("deepmine.store.done", boxFilter.describe(), Chat.hl(stacks), Chat.hl(items));
        assignShulkers(p);
        collectParked(p);
    }

    /** Der Shulker, der zu diesem Filter passt und die meisten Stapel enthaelt - vermutlich der gerade gefuellte. */
    private ItemStack findShulkerAnywhere(LocalPlayer p, BoxFilter filter) {
        ItemStack best = null;
        int bestUsed = -1;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!isShulker(st) || !fits(st, filter)) continue;
            int used = SHULKER_SLOTS - shulkerFreeSlots(st);
            if (used > bestUsed) { bestUsed = used; best = st; }
        }
        return best;
    }

    /** Client-Sicht der Kistenplaetze als Liste von Stapelgroessen, fuers Log. */
    private static String boxCounts(ShulkerBoxMenu menu) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < SHULKER_SLOTS && i < menu.slots.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(menu.getSlot(i).getItem().getCount());
        }
        return sb.append(']').toString();
    }

    private static boolean isShulker(ItemStack st) {
        return !st.isEmpty() && Block.byItem(st.getItem()) instanceof ShulkerBoxBlock;
    }

    private static int countShulkers(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (isShulker(p.getInventory().getItem(i))) n++;
        }
        return n;
    }

    /** Passt der Inhalt eines Shulker-Items zu diesem Filter? Leer passt zu allem. */
    private static boolean fits(ItemStack shulker, BoxFilter filter) {
        ItemContainerContents c = shulker.get(DataComponents.CONTAINER);
        if (c == null) return true;
        return c.nonEmptyItemCopyStream().allMatch(s -> filter.accepts(s.getItem()));
    }

    /**
     * Wie gut eignet sich ein Item als Shulker fuer diesen Filter?
     * -1: kein Shulker, passt nicht oder ist voll.
     * Sonst die Zahl belegter Plaetze: Angefangene werden bevorzugt, damit erst
     * einer vollgemacht wird, bevor der naechste leere angebrochen wird.
     */
    private static int candidateScore(ItemStack st, BoxFilter filter) {
        if (!isShulker(st) || !fits(st, filter)) return -1;
        int free = shulkerFreeSlots(st);
        if (free <= 0) return -1;
        return SHULKER_SLOTS - free;
    }

    /**
     * Shulker nach Inhalt auf die registrierten Slots verteilen. In den Inhalt
     * wird ueber die CONTAINER-Komponente geschaut - dieselbe Information wie im
     * Tooltip - ohne den Shulker zu setzen. Jeder Slot bekommt den am besten
     * passenden Shulker mit freiem Platz; liegt im Slot ein voller, unpassender
     * oder gar kein Shulker, wird per Hotbar-Tausch (SWAP, wie die Zahlentaste
     * ueber einem Slot) ein besserer aus dem Inventar geholt. So kommen auch
     * Ersatz-Shulker aus dem Hauptinventar automatisch dran.
     */
    private void assignShulkers(LocalPlayer p) {
        for (Map.Entry<Integer, BoxFilter> e : boxes.entrySet()) {
            int slot = e.getKey();
            BoxFilter filter = e.getValue();
            if (parkedPos != null && slot == parkedSlot) continue;

            int bestIdx = -1;
            int bestScore = candidateScore(p.getInventory().getItem(slot), filter);
            for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                if (i == slot) continue;
                ItemStack st = p.getInventory().getItem(i);
                // Ein Shulker, der in seinem eigenen Stammslot richtig liegt, bleibt dort.
                if (boxes.containsKey(i) && isShulker(st) && fits(st, boxes.get(i))) continue;
                int score = candidateScore(st, filter);
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0) swapIntoHotbar(p, bestIdx, slot);
        }
    }

    /** Inventarplatz invIndex mit Hotbar-Slot hotbar tauschen - der Vanilla-Klick "Zahlentaste ueber Slot". */
    private void swapIntoHotbar(LocalPlayer p, int invIndex, int hotbar) {
        int menuSlot = menuSlotOf(p.inventoryMenu, p, invIndex);
        if (menuSlot < 0) return;
        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlot, hotbar, ContainerInput.SWAP, p);
    }

    /** Menueposition eines Inventarplatzes, ohne Annahmen ueber die Slot-Anordnung. */
    private static int menuSlotOf(net.minecraft.world.inventory.AbstractContainerMenu menu,
                                  LocalPlayer p, int invIndex) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.getSlot(i);
            if (s.container == p.getInventory() && s.getContainerSlot() == invIndex) return i;
        }
        return -1;
    }

    /** Bricht den Einlagerzyklus ab und stoppt. Der Shulker bleibt, wo er ist. */
    private void abortStore(String reasonKey) {
        mc.gameMode.stopDestroyBlock();
        stop(Chat.tr("deepmine.store.abort", Chat.tr(reasonKey)));
    }

    // ------------------------------------------------------------------

    private boolean inReach(LocalPlayer p, BlockPos pos) {
        return p.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
    }

    private Component fluidNextTo(ClientLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            FluidState fs = level.getBlockState(pos.relative(d)).getFluidState();
            if (fs.isEmpty()) continue;
            return Chat.tr(fs.is(FluidTags.LAVA) ? "deepmine.lava" : "deepmine.water");
        }
        return null;
    }

    private void selectSlot(LocalPlayer p, int slot) {
        if (slot < 0 || slot > 8) return;
        if (p.getInventory().getSelectedSlot() != slot) {
            p.getInventory().setSelectedSlot(slot);
        }
    }

    /** Ohne Direction.getNearest gebaut, weil die Signatur sich zwischen Versionen bewegt hat. */
    private static Direction faceToward(Vec3 eye, BlockPos pos) {
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void lookAt(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setXRot((float) (-Math.toDegrees(Math.atan2(dy, flat))));
    }

}
