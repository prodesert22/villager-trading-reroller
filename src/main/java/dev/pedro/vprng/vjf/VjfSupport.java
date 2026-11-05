package dev.pedro.vprng.vjf;

import dev.pedro.vprng.i18n.I18n;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.pedro.vprng.VprngClient;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/**
 * Detects and drives the <a href="https://www.curseforge.com/minecraft/customization/villager-job-fix">Villager
 * Job Fix</a> data pack, which makes villagers take the workstation next to them immediately instead of walking
 * to it. We never touch the pack, we only use its documented player triggers as an ordinary player would:
 * {@code /trigger vjf_single} (closest villager within 20 blocks) and {@code /trigger vjf_multiple}
 * (the 16 closest within 20 blocks).
 *
 * <p>Detection is side-effect free: it asks the server for the tab-completions of {@code trigger vjf_}.
 * Call everything on the client thread.</p>
 */
public final class VjfSupport {
    private static final String PROBE = "trigger vjf_";

    private static VjfDetection.Result result = VjfDetection.Result.unknown(I18n.tr("vjf.notChecked"));
    /** Bumped on every check/invalidate so a late reply from an old world or request is dropped. */
    private static int generation;

    private VjfSupport() {}

    public static VjfDetection.Result result() {
        return result;
    }

    /** Forget the cached answer (new world / server, or the player toggled the option). */
    public static void invalidate() {
        generation++;
        result = VjfDetection.Result.unknown(I18n.tr("vjf.notChecked"));
    }

    public static void check(Minecraft mc) {
        check(mc, ignored -> { });
    }

    /**
     * Asks the server whether {@code vjf_single}/{@code vjf_multiple} are available to this player.
     * {@code done} runs on the client thread with the final result (also on failure or timeout).
     */
    public static void check(Minecraft mc, Consumer<VjfDetection.Result> done) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) {
            result = VjfDetection.Result.unknown(I18n.tr("vjf.notConnected"));
            done.accept(result);
            return;
        }
        int mine = ++generation;
        result = VjfDetection.Result.checking();
        try {
            CommandDispatcher<ClientSuggestionProvider> dispatcher = connection.getCommands();
            ParseResults<ClientSuggestionProvider> parse = dispatcher.parse(PROBE, connection.getSuggestionsProvider());
            dispatcher.getCompletionSuggestions(parse)
                    .orTimeout(5, TimeUnit.SECONDS)
                    .whenCompleteAsync((suggestions, error) -> {
                        if (mine != generation) return; // superseded by a newer check or a world change
                        if (error != null || suggestions == null) {
                            // Typing in chat shares the same pending-suggestion slot and can cancel ours.
                            result = VjfDetection.Result.unknown(I18n.tr("vjf.noReply"));
                        } else {
                            List<String> names = suggestions.getList().stream().map(Suggestion::getText).toList();
                            result = VjfDetection.interpret(names);
                        }
                        done.accept(result);
                    }, mc);
        } catch (RuntimeException e) {
            VprngClient.LOGGER.warn("Villager Job Fix probe failed: {}", e.toString());
            result = VjfDetection.Result.unknown(I18n.tr("vjf.probeFailed", String.valueOf(e.getMessage())));
            done.accept(result);
        }
    }

    /**
     * Sends {@code /trigger vjf_multiple} or {@code /trigger vjf_single} as the player.
     * Whether the pack actually works on this Minecraft version is up to the pack; this only sends the command.
     *
     * @return false if not connected
     */
    public static boolean trigger(Minecraft mc, boolean multiple) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null) return false;
        connection.sendCommand("trigger " + (multiple ? VjfDetection.MULTIPLE : VjfDetection.SINGLE));
        return true;
    }
}
