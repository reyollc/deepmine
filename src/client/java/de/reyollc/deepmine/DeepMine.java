package de.reyollc.deepmine;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class DeepMine implements ClientModInitializer {

    /** Aus der fabric.mod.json, damit /deepmine status zeigt, welche Jar geladen ist. */
    private static final String VERSION = FabricLoader.getInstance().getModContainer("deepmine")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("?");

    private static final int BEACON_SEARCH_RADIUS = 24;
    private static final int CORE_RADIUS = 4;   // 9x9-Pyramidenbasis belegt Radius 0..4
    private static final int DEFAULT_RADIUS = 50; // Beacon Stufe 4
    private static final int DEFAULT_HEIGHT = 4;
    private static final int HELP_LINES = 14;

    private static MinerTask task;
    private static Settings cfg = new Settings();

    @Override
    public void onInitializeClient() {
        cfg = Settings.load();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (task != null && task.isRunning()) task.tick();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("deepmine")
                .executes(ctx -> help())
                .then(ClientCommands.literal("help").executes(ctx -> help()))
                .then(ClientCommands.literal("start")
                    .executes(ctx -> start(DEFAULT_RADIUS, DEFAULT_HEIGHT))
                    .then(ClientCommands.argument("radius", IntegerArgumentType.integer(6, 64))
                        .executes(ctx -> start(IntegerArgumentType.getInteger(ctx, "radius"), DEFAULT_HEIGHT))
                        .then(ClientCommands.argument("height", IntegerArgumentType.integer(2, 6))
                            .executes(ctx -> start(
                                IntegerArgumentType.getInteger(ctx, "radius"),
                                IntegerArgumentType.getInteger(ctx, "height"))))))
                .then(ClientCommands.literal("stop").executes(ctx -> {
                    if (task != null && task.isRunning()) task.stop(Chat.tr("deepmine.stopped"));
                    else Chat.info("deepmine.not_running");
                    return 1;
                }))
                .then(ClientCommands.literal("resume").executes(ctx -> resume()))
                .then(ClientCommands.literal("status").executes(ctx -> {
                    loadJobIfNeeded();
                    if (task == null) {
                        Chat.info("deepmine.status.none", Chat.hl(VERSION));
                    } else {
                        Chat.info("deepmine.status.task", Chat.hl(VERSION), Chat.hl(task.ring()),
                                Chat.hl(task.cellsDone()), task.stateName());
                        if (!task.isRunning() && task.nextCell() != null) {
                            Chat.info("deepmine.status.next", Chat.pos(task.nextCell()), Chat.hl(task.dimension()));
                        }
                    }
                    return 1;
                }))
                .then(ClientCommands.literal("forget").executes(ctx -> {
                    if (task != null && task.isRunning()) task.stop(Chat.tr("deepmine.stopped"));
                    task = null;
                    JobStore.clear();
                    Chat.info("deepmine.forgotten");
                    return 1;
                }))
                .then(ClientCommands.literal("config")
                    .executes(ctx -> {
                        Chat.info("deepmine.config.header", Chat.hl(VERSION));
                        for (Component line : cfg.describe()) Chat.send(ChatFormatting.WHITE, line);
                        return 1;
                    })
                    .then(ClientCommands.literal("reset").executes(ctx -> {
                        cfg = new Settings();
                        changed();
                        Chat.ok("deepmine.config.reset");
                        return 1;
                    })))
                .then(ClientCommands.literal("slots")
                    .then(ClientCommands.argument("pick", IntegerArgumentType.integer(1, 9))
                        .then(ClientCommands.argument("silk", IntegerArgumentType.integer(1, 9))
                            .then(ClientCommands.argument("torch", IntegerArgumentType.integer(1, 9))
                                .executes(ctx -> {
                                    cfg.mineSlot = IntegerArgumentType.getInteger(ctx, "pick") - 1;
                                    cfg.silkSlot = IntegerArgumentType.getInteger(ctx, "silk") - 1;
                                    cfg.torchSlot = IntegerArgumentType.getInteger(ctx, "torch") - 1;
                                    changed();
                                    Chat.ok("deepmine.slots.set", Chat.hl(cfg.mineSlot + 1),
                                            Chat.hl(cfg.silkSlot + 1), Chat.hl(cfg.torchSlot + 1));
                                    return 1;
                                })))))
                .then(ClientCommands.literal("sword")
                    .then(ClientCommands.literal("off").executes(ctx -> {
                        cfg.swordSlot = -1;
                        changed();
                        Chat.warn("deepmine.sword.off");
                        return 1;
                    }))
                    .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, 9))
                        .executes(ctx -> {
                            cfg.swordSlot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
                            changed();
                            Chat.ok("deepmine.sword.set", Chat.hl(cfg.swordSlot + 1));
                            return 1;
                        })))
                .then(ClientCommands.literal("floor")
                    .then(ClientCommands.literal("off").executes(ctx -> {
                        cfg.floorSlot = -1;
                        changed();
                        Chat.warn("deepmine.floor.off");
                        return 1;
                    }))
                    .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, 9))
                        .executes(ctx -> {
                            cfg.floorSlot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
                            changed();
                            Chat.ok("deepmine.floor.set", Chat.hl(cfg.floorSlot + 1));
                            return 1;
                        })))
                .then(ClientCommands.literal("food")
                    .then(ClientCommands.literal("off").executes(ctx -> {
                        cfg.foodSlot = -1;
                        changed();
                        Chat.warn("deepmine.food.off");
                        return 1;
                    }))
                    .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, 9))
                        .executes(ctx -> {
                            cfg.foodSlot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
                            changed();
                            Chat.ok("deepmine.food.set", Chat.hl(cfg.foodSlot + 1));
                            return 1;
                        })))
                .then(ClientCommands.literal("silk")
                    .then(ClientCommands.literal("list").executes(ctx -> {
                        Chat.send(ChatFormatting.WHITE, cfg.silkLine());
                        return 1;
                    }))
                    .then(ClientCommands.literal("clear").executes(ctx -> {
                        cfg.silkExtra.clear();
                        changed();
                        Chat.ok("deepmine.silk.cleared");
                        return 1;
                    }))
                    .then(ClientCommands.argument("block", ItemArgument.item(registryAccess))
                        .executes(ctx -> {
                            Item item = ItemArgument.getItem(ctx, "block").item().value();
                            Block b = Block.byItem(item);
                            if (b == Blocks.AIR) {
                                Chat.fail("deepmine.silk.not_block", Settings.name(item));
                                return 1;
                            }
                            cfg.silkExtra.add(b);
                            changed();
                            Chat.ok("deepmine.silk.added", Settings.name(b));
                            return 1;
                        })))
                .then(ClientCommands.literal("box")
                    .then(ClientCommands.literal("list").executes(ctx -> {
                        Chat.send(ChatFormatting.WHITE, cfg.boxesLine());
                        return 1;
                    }))
                    .then(ClientCommands.literal("clear").executes(ctx -> {
                        cfg.boxes.clear();
                        changed();
                        Chat.ok("deepmine.box.cleared");
                        return 1;
                    }))
                    .then(ClientCommands.argument("slot", IntegerArgumentType.integer(1, 9))
                        .then(ClientCommands.literal("fullstacks")
                            .then(ClientCommands.literal("on").executes(ctx -> fullStacks(ctx, true)))
                            .then(ClientCommands.literal("off").executes(ctx -> fullStacks(ctx, false))))
                        .then(ClientCommands.literal("all").executes(ctx -> {
                            int slot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
                            cfg.boxes.put(slot, new BoxFilter(BoxFilter.Mode.ALL));
                            changed();
                            Chat.ok("deepmine.box.all", Chat.hl(slot + 1));
                            return 1;
                        }))
                        .then(ClientCommands.literal("only")
                            .then(ClientCommands.argument("item", ItemArgument.item(registryAccess))
                                .executes(ctx -> addToBox(ctx, BoxFilter.Mode.ONLY))))
                        .then(ClientCommands.literal("except")
                            .then(ClientCommands.argument("item", ItemArgument.item(registryAccess))
                                .executes(ctx -> addToBox(ctx, BoxFilter.Mode.EXCEPT))))
                        // Kurzform ohne Modus bedeutet "nur dieses Item".
                        .then(ClientCommands.argument("item", ItemArgument.item(registryAccess))
                            .executes(ctx -> addToBox(ctx, BoxFilter.Mode.ONLY)))))
                .then(ClientCommands.literal("durability")
                    .then(ClientCommands.argument("left", IntegerArgumentType.integer(1, 500))
                        .executes(ctx -> {
                            cfg.minDurability = IntegerArgumentType.getInteger(ctx, "left");
                            changed();
                            Chat.ok("deepmine.durability.set", Chat.hl(cfg.minDurability));
                            return 1;
                        })))));
    }

    /** Nach jeder Aenderung: auf Platte schreiben und einem laufenden Auftrag mitgeben. */
    private static void changed() {
        cfg.save();
        if (task != null) cfg.applyTo(task);
    }

    private static int help() {
        Chat.info("deepmine.help.header", Chat.hl(VERSION));
        for (int i = 1; i <= HELP_LINES; i++) {
            Chat.helpLine("deepmine.help." + i + ".cmd", "deepmine.help." + i + ".desc");
        }
        Chat.info("deepmine.help.footer");
        return 1;
    }

    /**
     * Traegt ein Item in den Filter eines Slots ein. Mehrfach aufrufbar, die
     * Items sammeln sich. Ein Moduswechsel wirft die bisherige Liste weg, weil
     * sie dann das Gegenteil bedeuten wuerde.
     */
    private static int addToBox(CommandContext<FabricClientCommandSource> ctx, BoxFilter.Mode mode) {
        int slot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
        Item item = ItemArgument.getItem(ctx, "item").item().value();
        BoxFilter f = cfg.boxes.computeIfAbsent(slot, k -> new BoxFilter(mode));
        f.setMode(mode);
        f.add(item);
        changed();
        Chat.ok("deepmine.box.set", Chat.hl(slot + 1), f.describe());
        return 1;
    }

    /** Nur volle Stapel in diese Kiste - ein/aus. Ohne Eintrag fuer den Slot gibt es nichts umzuschalten. */
    private static int fullStacks(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        int slot = IntegerArgumentType.getInteger(ctx, "slot") - 1;
        BoxFilter f = cfg.boxes.get(slot);
        if (f == null) { Chat.fail("deepmine.box.no_entry", Chat.hl(slot + 1)); return 1; }
        f.setFullStacksOnly(on);
        changed();
        Chat.ok(on ? "deepmine.box.fullstacks.on" : "deepmine.box.fullstacks.off", Chat.hl(slot + 1));
        return 1;
    }

    private static int start(int radius, int height) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) return 1;

        BlockPos beacon = findBeacon(level, p.blockPosition());
        if (beacon == null) {
            Chat.fail("deepmine.start.no_beacon", Chat.hl(BEACON_SEARCH_RADIUS));
            return 1;
        }

        int dx = p.blockPosition().getX() - beacon.getX();
        int dz = p.blockPosition().getZ() - beacon.getZ();
        int r = Math.max(Math.abs(dx), Math.abs(dz));
        if (r <= CORE_RADIUS) {
            Chat.fail("deepmine.start.on_pyramid", Chat.hl(CORE_RADIUS + 1));
            return 1;
        }
        if (r > radius) {
            Chat.fail("deepmine.start.out_of_range", Chat.hl(r), Chat.hl(radius));
            return 1;
        }

        task = new MinerTask(beacon, p.blockPosition().getY(), radius, CORE_RADIUS,
                height, dx, dz, facingIndex(p.getYRot()), dimensionOf(level));
        cfg.applyTo(task);
        task.setProgressListener(DeepMine::persist);
        task.start();

        Chat.ok("deepmine.start.ok", Chat.pos(beacon), Chat.hl(radius), Chat.hl(height));
        return 1;
    }

    /**
     * Weitermachen, auch nach Neustart oder Serverwechsel: liegt kein Auftrag im
     * Speicher, kommt er von der Platte. Die Dimension muss stimmen, und der
     * Spieler muss nahe der naechsten Zelle stehen - das prueft MinerTask.resume.
     */
    private static int resume() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 1;
        loadJobIfNeeded();
        if (task == null) { Chat.fail("deepmine.resume.nothing"); return 1; }
        if (task.isRunning()) { Chat.info("deepmine.resume.running", task.statusLine()); return 1; }

        String here = dimensionOf(mc.level);
        if (!here.equals(task.dimension())) {
            Chat.fail("deepmine.resume.wrong_dimension", Chat.hl(task.dimension()), Chat.hl(here));
            return 1;
        }
        if (task.resume()) Chat.ok("deepmine.resume.ok", task.statusLine());
        return 1;
    }

    /** Auftrag aus config/deepmine-job.json holen, falls gerade keiner im Speicher ist. */
    private static void loadJobIfNeeded() {
        if (task != null) return;
        task = JobStore.load();
        if (task != null) {
            cfg.applyTo(task);
            task.setProgressListener(DeepMine::persist);
        }
    }

    /** Nach jedem Fortschritt auf die Platte; ist der Auftrag fertig, weg damit. */
    private static void persist() {
        if (task == null) return;
        if (task.isDone()) JobStore.clear();
        else JobStore.save(task);
    }

    private static String dimensionOf(ClientLevel level) {
        return level.dimension().identifier().toString();
    }

    /** Naechste Himmelsrichtung: 0=Sued 1=Ost 2=Nord 3=West, passend zu Spiral. */
    private static int facingIndex(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90.0f), 4); // 0=Sued 1=West 2=Nord 3=Ost
        return switch (q) {
            case 0 -> 0; // Sued
            case 1 -> 3; // West
            case 2 -> 2; // Nord
            default -> 1; // Ost
        };
    }

    private static BlockPos findBeacon(ClientLevel level, BlockPos around) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int r = BEACON_SEARCH_RADIUS;
        for (BlockPos pos : BlockPos.betweenClosed(around.offset(-r, -r, -r), around.offset(r, r, r))) {
            if (!level.getBlockState(pos).is(Blocks.BEACON)) continue;
            double d = pos.distSqr(around);
            if (d < bestDist) { bestDist = d; best = pos.immutable(); }
        }
        return best;
    }
}
