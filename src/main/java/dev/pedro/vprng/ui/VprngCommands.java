package dev.pedro.vprng.ui;

import dev.pedro.vprng.i18n.I18n;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.pedro.vprng.VprngClient;
import dev.pedro.vprng.bridge.VtpBridge;
import dev.pedro.vprng.config.VprngConfig;
import dev.pedro.vprng.roll.Cell;
import dev.pedro.vprng.roll.PoolController;
import dev.pedro.vprng.vjf.VjfDetection;
import dev.pedro.vprng.vjf.VjfSupport;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class VprngCommands {
    private VprngCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("vprng")
                        .executes(VprngCommands::status)
                        .then(ClientCommands.literal("status").executes(VprngCommands::status))
                        .then(ClientCommands.literal("start").executes(ctx -> {
                            Minecraft mc = ctx.getSource().getClient();
                            feedback(ctx, VprngClient.controller().start(mc));
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            VprngClient.controller().stop(ctx.getSource().getClient(), I18n.tr("ctl.stopped"));
                            if (!VprngConfig.get().chatLogs) feedback(ctx, I18n.tr("ctl.stopped")); // otherwise the log already said it
                            return 1;
                        }))
                        .then(ClientCommands.literal("pause").executes(ctx -> {
                            PoolController c = VprngClient.controller();
                            if (c.running()) {
                                c.pause(ctx.getSource().getClient(), I18n.tr("pause.byCommand"));
                                if (!VprngConfig.get().chatLogs) feedback(ctx, I18n.tr("cmd.paused"));
                            } else {
                                feedback(ctx, I18n.tr("cmd.notRunning"));
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("resume").executes(ctx -> {
                            feedback(ctx, VprngClient.controller().resume(ctx.getSource().getClient()));
                            return 1;
                        }))
                        .then(ClientCommands.literal("reload").executes(ctx -> {
                            VprngConfig.load();
                            feedback(ctx, I18n.tr("cmd.reloaded"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("vjf")
                                .executes(VprngCommands::vjfStatus)
                                .then(ClientCommands.literal("check").executes(VprngCommands::vjfStatus))
                                .then(ClientCommands.literal("on").executes(ctx -> vjfEnable(ctx, true)))
                                .then(ClientCommands.literal("off").executes(ctx -> vjfEnable(ctx, false)))
                                .then(ClientCommands.literal("single").executes(ctx -> vjfSend(ctx, false)))
                                .then(ClientCommands.literal("multiple").executes(ctx -> vjfSend(ctx, true))))
                        .then(ClientCommands.literal("gui").executes(ctx -> {
                            VprngClient.openGui(dev.pedro.vprng.ui.screen.VprngScreen.Tab.CONTROL);
                            return 1;
                        }))
                        .then(ClientCommands.literal("language")
                                .then(ClientCommands.literal("auto").executes(ctx -> language(ctx, I18n.AUTO)))
                                .then(ClientCommands.literal("en").executes(ctx -> language(ctx, "en")))
                                .then(ClientCommands.literal("pt").executes(ctx -> language(ctx, "pt")))
                                .then(ClientCommands.literal("es").executes(ctx -> language(ctx, "es")))
                                .executes(ctx -> language(ctx, I18n.nextSetting(VprngConfig.get().language))))
                        .then(ClientCommands.literal("logs")
                                .then(ClientCommands.literal("on").executes(ctx -> chatLogs(ctx, true)))
                                .then(ClientCommands.literal("off").executes(ctx -> chatLogs(ctx, false)))
                                .executes(ctx -> chatLogs(ctx, !VprngConfig.get().chatLogs)))
                        .then(ClientCommands.literal("highlight")
                                .then(ClientCommands.literal("on").executes(ctx -> highlight(ctx, true)))
                                .then(ClientCommands.literal("off").executes(ctx -> highlight(ctx, false)))
                                .executes(ctx -> highlight(ctx, !VprngConfig.get().showHighlights)))
                        .then(ClientCommands.literal("hud")
                                .then(ClientCommands.literal("edit").executes(ctx -> {
                                    VprngClient.later(2, () -> Minecraft.getInstance().gui.setScreen(
                                            new dev.pedro.vprng.ui.screen.HudEditScreen(null)));
                                    return 1;
                                }))
                                .then(ClientCommands.argument("x", IntegerArgumentType.integer(0, 4000))
                                        .then(ClientCommands.argument("y", IntegerArgumentType.integer(0, 4000))
                                                .executes(ctx -> {
                                                    VprngConfig cfg = VprngConfig.get();
                                                    cfg.hudX = IntegerArgumentType.getInteger(ctx, "x");
                                                    cfg.hudY = IntegerArgumentType.getInteger(ctx, "y");
                                                    VprngConfig.save();
                                                    feedback(ctx, I18n.tr("cmd.hudMoved", cfg.hudX, cfg.hudY));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("toggle").executes(ctx -> {
                                    VprngConfig cfg = VprngConfig.get();
                                    cfg.hudEnabled = !cfg.hudEnabled;
                                    VprngConfig.save();
                                    feedback(ctx, I18n.tr(cfg.hudEnabled ? "cmd.hudShown" : "cmd.hudHidden"));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("container")
                                .executes(VprngCommands::containerShow)
                                .then(ClientCommands.literal("show").executes(VprngCommands::containerShow))
                                .then(ClientCommands.literal("set").executes(VprngCommands::containerSet))
                                .then(ClientCommands.literal("clear").executes(ctx -> {
                                    VprngClient.controller().setContainer(ctx.getSource().getClient(), null);
                                    feedback(ctx, I18n.tr("cmd.containerCleared"));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("pool")
                                .then(ClientCommands.literal("add").executes(VprngCommands::poolAdd))
                                .then(ClientCommands.literal("scan").executes(VprngCommands::poolScan))
                                .then(ClientCommands.literal("list").executes(VprngCommands::poolList))
                                .then(ClientCommands.literal("clear").executes(ctx -> {
                                    VprngClient.controller().clearPool(ctx.getSource().getClient());
                                    feedback(ctx, I18n.tr("cmd.poolCleared"));
                                    return 1;
                                })))));
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft mc = ctx.getSource().getClient();
        PoolController c = VprngClient.controller();
        VtpBridge.selfTest();
        feedback(ctx, I18n.tr("cmd.statusState", c.state(), c.status()));
        feedback(ctx, I18n.tr("cmd.statusVtp",
                VtpBridge.apiOk() ? I18n.tr("cmd.apiOk") : I18n.tr("cmd.apiBad", VtpBridge.apiProblem()),
                VtpBridge.seed().isPresent() ? I18n.tr("cmd.seedSet") : I18n.tr("cmd.seedMissing"),
                VtpBridge.targets().size()));
        feedback(ctx, I18n.tr("cmd.statusPool", c.cells().size(), c.cycles(), c.opens()));
        feedback(ctx, I18n.tr("cmd.statusVjf", onOff(VprngConfig.get().useVillagerJobFix), VjfSupport.result().describe()));
        var forecast = VtpBridge.forecast(mc);
        feedback(ctx, forecast.hit()
                .map(h -> I18n.tr("cmd.statusPrediction", h.roundsToBurn(), h.hitRound()))
                .orElse(I18n.tr("cmd.statusNoPrediction", forecast.note())));
        return 1;
    }

    /** Asks the server whether the Villager Job Fix triggers are available to you and reports the answer. */
    private static int vjfStatus(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft mc = ctx.getSource().getClient();
        feedback(ctx, I18n.tr("cmd.vjfConfig", onOff(VprngConfig.get().useVillagerJobFix), VprngConfig.get().vjfMode));
        VjfSupport.check(mc, result -> feedback(ctx, I18n.tr("cmd.vjfServer", result.describe())));
        return 1;
    }

    private static int vjfEnable(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        VprngConfig.get().useVillagerJobFix = on;
        VprngConfig.save();
        VjfSupport.invalidate();
        feedback(ctx, I18n.tr(on ? "cmd.vjfEnabled" : "cmd.vjfDisabled"));
        if (on) {
            VjfSupport.check(ctx.getSource().getClient(),
                    result -> feedback(ctx, I18n.tr("cmd.vjfServer", result.describe())));
        }
        return 1;
    }

    /** Manual one-off trigger, handy to test the data pack. Sent even if the pack was not detected. */
    private static int vjfSend(CommandContext<FabricClientCommandSource> ctx, boolean multiple) {
        Minecraft mc = ctx.getSource().getClient();
        var known = VjfSupport.result();
        if (known.status() == VjfDetection.Status.ABSENT) {
            feedback(ctx, I18n.tr("cmd.vjfNote", known.note()));
        }
        feedback(ctx, VjfSupport.trigger(mc, multiple)
                ? I18n.tr("cmd.vjfSent", multiple ? VjfDetection.MULTIPLE : VjfDetection.SINGLE)
                : I18n.tr("cmd.notConnected"));
        return 1;
    }

    private static int chatLogs(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        VprngConfig.get().chatLogs = on;
        VprngConfig.save();
        feedback(ctx, I18n.tr(on ? "cmd.logsOn" : "cmd.logsOff"));
        return 1;
    }

    private static int highlight(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        VprngConfig.get().showHighlights = on;
        VprngConfig.save();
        feedback(ctx, I18n.tr(on ? "cmd.highlightOn" : "cmd.highlightOff"));
        return 1;
    }

    private static int containerShow(CommandContext<FabricClientCommandSource> ctx) {
        BlockPos pos = VprngClient.controller().containerPos();
        feedback(ctx, pos == null ? I18n.tr("cmd.containerNone") : I18n.tr("cmd.containerAt", pos.toShortString()));
        return 1;
    }

    /** Registers the block under the crosshair as the place the broken lecterns are collected from. */
    private static int containerSet(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx, PoolActions.setContainerLookedAt(ctx.getSource().getClient()));
        return 1;
    }

    private static int poolAdd(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx, PoolActions.addLookedAt(ctx.getSource().getClient()));
        return 1;
    }

    private static int poolScan(CommandContext<FabricClientCommandSource> ctx) {
        feedback(ctx, PoolActions.scan(ctx.getSource().getClient()));
        return 1;
    }

    private static int poolList(CommandContext<FabricClientCommandSource> ctx) {
        var cells = VprngClient.controller().cells();
        if (cells.isEmpty()) feedback(ctx, I18n.tr("cmd.poolEmpty"));
        for (Cell c : cells) feedback(ctx, I18n.tr("cmd.poolFace", c.pos().toShortString(), c.face().getName()));
        return cells.size();
    }

    private static String onOff(boolean value) {
        return I18n.tr(value ? "common.on" : "common.off");
    }

    /** {@code /vprng language [auto|en|pt|es]}: language of this mod only (the game's own language is untouched). */
    private static int language(CommandContext<FabricClientCommandSource> ctx, String setting) {
        VprngConfig.get().language = setting;
        VprngConfig.save();
        feedback(ctx, I18n.tr("cmd.language", languageName(setting)));
        return 1;
    }

    public static String languageName(String setting) {
        if (I18n.AUTO.equals(setting)) return I18n.tr("lang.auto");
        I18n.Lang lang = I18n.Lang.byCode(setting);
        return lang == null ? setting : lang.nativeName;
    }

    private static void feedback(CommandContext<FabricClientCommandSource> ctx, String message) {
        ctx.getSource().sendFeedback(Component.literal("[VPRNG] " + message));
    }
}
