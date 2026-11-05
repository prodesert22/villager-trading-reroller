package dev.pedro.vprng.bridge;

import dev.pedro.vprng.i18n.I18n;
import dev.pedro.vprng.VprngClient;
import dev.pedro.vprng.plan.ObservationWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.manaz.vtp.prediction.CalibrationService;
import net.manaz.vtp.prediction.EnchantmentTarget;
import net.manaz.vtp.prediction.PredictionEngine;
import net.manaz.vtp.mixin.accessor.VillagerTradeAccessor;
import net.manaz.vtp.prediction.PredictionResult;
import net.manaz.vtp.render.VillagerHudRenderer;
import net.manaz.vtp.render.VillagerTracker;
import net.manaz.vtp.seed.SeedProvider;
import net.manaz.vtp.trade.ObservedOffer;
import net.manaz.vtp.trade.TradeSimulator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * The ONLY class that touches Villager Trading Predictor internals.
 *
 * <p>VTP has no stable public API (and is "all rights reserved"), so everything we call is funnelled through
 * here: we only compile against the user's own jar (compile-only), never copy or ship VTP code. If a future
 * VTP changes a signature, {@link #selfTest()} flips {@link #apiOk()} to false and the controller keeps
 * working in pure verification mode (no prediction) instead of crashing.</p>
 */
public final class VtpBridge {
    /** One offer of the predicted round: icon, readable text, and whether it is the wanted one. */
    public record Offer(ItemStack icon, String text, int price, boolean match) {}

    /**
     * A predicted hit: absolute sequence round, how many rounds must be burned before it, the wanted target and
     * every offer the villager will have in that round.
     */
    public record Hit(int hitRound, int roundsToBurn, EnchantmentTarget target, List<Offer> offers) {}

    /** Prediction result, or the reason there is none. */
    public record Forecast(Optional<Hit> hit, String note, boolean outOfWindow) {
        public static Forecast none(String why) {
            return new Forecast(Optional.empty(), why, false);
        }

        /** VTP searched its whole window and found no wanted book: the run keeps burning until one comes into range. */
        public static Forecast outOfWindow(String why) {
            return new Forecast(Optional.empty(), why, true);
        }
    }

    /** Outcome of one automatic calibration attempt. */
    public record Calibration(boolean calibrated, int round, int candidates, String note) {
        static Calibration skipped(String note) {
            return new Calibration(false, -1, 0, note);
        }
    }

    private static final int CALIBRATION_WINDOW = 12;

    private static boolean apiOk;
    private static final String NOT_CHECKED = "not checked yet"; // internal marker; shown through bridge.notChecked
    private static String apiProblem = NOT_CHECKED;
    private static final ObservationWindow<List<ObservedOffer>> observations =
            new ObservationWindow<>(CALIBRATION_WINDOW);

    private VtpBridge() {}

    /** Touches every VTP member we depend on once; never throws. */
    public static boolean selfTest() {
        try {
            PredictionEngine.getMaxRerolls();
            VillagerHudRenderer.getTargets();
            SeedProvider.getSeed();
            PredictionEngine.class.getMethod("predictFrom", long.class, Holder.class, int.class,
                    EnchantmentTarget.class, int.class);
            PredictionEngine.class.getMethod("getForecastRounds", long.class, Holder.class, int.class, int.class);
            PredictionEngine.class.getMethod("compatibilityProblem", Holder.class, int.class);
            VillagerTracker.class.getMethod("getNextRoundForLevel", Holder.class, int.class);
            VillagerTracker.class.getMethod("clearMerchantInteraction");
            VillagerTracker.class.getMethod("getData", UUID.class);
            CalibrationService.class.getMethod("problem", VillagerData.class);
            CalibrationService.class.getMethod("search", UUID.class, VillagerData.class, List.class);
            CalibrationService.class.getMethod("confirm", UUID.class, VillagerData.class, int.class);
            PredictionEngine.CalibrationSpec.class.getMethod("captured", List.class);
            ObservedOffer.class.getMethod("copyOf", List.class);
            apiOk = true;
            apiProblem = "";
        } catch (Exception | LinkageError e) {
            apiOk = false;
            apiProblem = e.toString();
            VprngClient.LOGGER.warn("VTP API self-test failed, prediction disabled: {}", e.toString());
        }
        return apiOk;
    }

    public static boolean apiOk() {
        return apiOk;
    }

    public static String apiProblem() {
        return NOT_CHECKED.equals(apiProblem) ? I18n.tr("bridge.notChecked") : apiProblem;
    }

    public static OptionalLong seed() {
        if (!apiOk) return OptionalLong.empty();
        try {
            return SeedProvider.getSeed();
        } catch (Exception | LinkageError e) {
            return degrade(e, OptionalLong.empty());
        }
    }

    /** Targets the user configured in VTP (/vtp target ... or its settings screen). */
    public static List<EnchantmentTarget> targets() {
        try {
            return List.copyOf(VillagerHudRenderer.getTargets());
        } catch (Exception | LinkageError e) {
            return degrade(e, List.of());
        }
    }

    /** Next sequence round VTP expects a fresh librarian to receive, or -1 if unknown. */
    public static int nextRound(ClientLevel level) {
        if (!apiOk || level == null) return -1;
        try {
            return VillagerTracker.getNextRoundForLevel(librarian(level), 1);
        } catch (Exception | LinkageError e) {
            return degrade(e, -1);
        }
    }

    /** The accent colour the player chose in VTP's settings (ARGB), so our panels match theirs; green by default. */
    public static int accent() {
        try {
            return net.manaz.vtp.gui.VtpAccent.color();
        } catch (Exception | LinkageError e) {
            return 0xFF55FF55;
        }
    }

    /** Forget the recorded observations (new run, or the position is in sync again). */
    public static void clearCalibration() {
        observations.clear();
    }

    /**
     * Multiplayer auto-calibration, the same transaction as {@code /vtp calibrate}: record the offers of a freshly
     * opened level-1 librarian, search VTP's simulated sequence for the run of consecutive rounds that matches all
     * recorded observations, and apply it only when exactly one position fits. Call it only for opens where VTP
     * did not already align itself. The villager must be the one whose offers were just observed.
     */
    public static Calibration calibrate(Minecraft mc, UUID uuid, MerchantOffers offers) {
        if (!apiOk) return Calibration.skipped(I18n.tr("forecast.apiUnavailable"));
        if (mc.getSingleplayerServer() != null) return Calibration.skipped(I18n.tr("cal.singleplayer"));
        try {
            Optional<VillagerData> found = VillagerTracker.getData(uuid);
            if (found.isEmpty()) return Calibration.skipped(I18n.tr("cal.noData"));
            VillagerData data = found.get();
            if (!data.profession().is(VillagerProfession.LIBRARIAN) || data.level() != 1) {
                return Calibration.skipped(I18n.tr("cal.notLibrarian"));
            }
            String problem = CalibrationService.problem(data);
            if (problem != null && !problem.isEmpty()) return Calibration.skipped(problem);

            observations.add(ObservedOffer.copyOf(offers));
            List<Integer> matches = search(uuid, data);
            if (matches.isEmpty() && observations.size() > 1) {
                // Most likely a gap in the run (someone else used the sequence): restart from the newest round.
                observations.keepOnlyLatest();
                matches = search(uuid, data);
            }
            if (matches.size() == 1) {
                int round = matches.getFirst();
                if (CalibrationService.confirm(uuid, data, round)) {
                    int used = observations.size();
                    observations.clear();
                    return new Calibration(true, round, 1, I18n.tr("cal.aligned", used));
                }
                return new Calibration(false, -1, 1, I18n.tr("cal.rejected"));
            }
            if (matches.isEmpty()) {
                return new Calibration(false, -1, 0, I18n.tr("cal.noMatch"));
            }
            return new Calibration(false, -1, matches.size(), I18n.tr("cal.ambiguous", matches.size()));
        } catch (Exception | LinkageError e) {
            return degrade(e, Calibration.skipped(I18n.tr("cal.callFailed", e.toString())));
        }
    }

    private static List<Integer> search(UUID uuid, VillagerData data) {
        List<PredictionEngine.CalibrationSpec> specs = observations.items().stream()
                .map(PredictionEngine.CalibrationSpec::captured).toList();
        return CalibrationService.search(uuid, data, specs);
    }

    /** Drops VTP's pending "which villager am I looking at" state after a failed/timed-out open. */
    public static void clearInteraction() {
        if (!apiOk) return;
        try {
            VillagerTracker.clearMerchantInteraction();
        } catch (Exception | LinkageError e) {
            degrade(e, null);
        }
    }

    /**
     * Rounds until the first wanted enchantment, over all VTP targets (nearest wins).
     * Multiplayer: absolute rounds via {@code predictFrom}. Singleplayer: index into VTP's live snapshot forecast.
     */
    public static Forecast forecast(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) return Forecast.none(I18n.tr("common.noWorld"));
        if (!apiOk) return Forecast.none(I18n.tr("forecast.apiUnavailable"));
        try {
            List<EnchantmentTarget> targets = VillagerHudRenderer.getTargets();
            if (targets.isEmpty()) return Forecast.none(I18n.tr("forecast.noTargets"));
            Holder<VillagerProfession> librarian = librarian(level);
            String problem = PredictionEngine.compatibilityProblem(librarian, 1);
            if (problem != null && !problem.isEmpty()) return Forecast.none("VTP: " + problem);
            OptionalLong seed = SeedProvider.getSeed();
            if (seed.isEmpty()) return Forecast.none(I18n.tr("forecast.noSeed"));

            Hit best = null;
            if (mc.getSingleplayerServer() != null) {
                var rounds = PredictionEngine.getForecastRounds(seed.getAsLong(), librarian, 1,
                        PredictionEngine.getMaxRerolls());
                if (rounds.isEmpty()) return Forecast.none(I18n.tr("forecast.snapshot"));
                for (EnchantmentTarget target : targets) {
                    for (int i = 0; i < rounds.size(); i++) {
                        if (roundMatches(rounds.get(i), target)) {
                            if (best == null || i < best.roundsToBurn()) {
                                best = new Hit(i, i, target, describe(rounds.get(i), target));
                            }
                            break;
                        }
                    }
                }
            } else {
                int next = VillagerTracker.getNextRoundForLevel(librarian, 1);
                for (EnchantmentTarget target : targets) {
                    PredictionResult r = PredictionEngine.predictFrom(seed.getAsLong(), librarian, 1, target, next);
                    if (!r.found()) continue;
                    int round = r.rerollsNeeded().orElseThrow();
                    int burn = Math.max(0, round - next);
                    if (best == null || burn < best.roundsToBurn()) {
                        best = new Hit(round, burn, target, describe(r.targetRollOffers(), target));
                    }
                }
            }
            if (best == null) {
                return Forecast.outOfWindow(I18n.tr("forecast.noTargetWithin", PredictionEngine.getMaxRerolls()));
            }
            return new Forecast(Optional.of(best), "", false);
        } catch (Exception | LinkageError e) {
            return degrade(e, Forecast.none(I18n.tr("forecast.callFailed", e.toString())));
        }
    }

    /** Turns VTP's simulated round into icon + text lines (enchantment, level, base emerald price). */
    private static List<Offer> describe(List<TradeSimulator.SimulatedOffer> round, EnchantmentTarget target) {
        List<Offer> result = new ArrayList<>();
        for (TradeSimulator.SimulatedOffer offer : round) {
            ItemStack icon = ItemStack.EMPTY;
            String name = I18n.tr("offer.generic");
            try {
                Object trade = offer.tradeDefinition().value();
                if (trade instanceof VillagerTradeAccessor accessor) {
                    ItemStack gives = accessor.vtp$getGives().create();
                    icon = gives;
                    name = gives.getHoverName().getString() + (gives.getCount() > 1 ? " ×" + gives.getCount() : "");
                }
            } catch (RuntimeException | LinkageError e) {
                // keep the generic label; the enchantment below is still shown
            }
            var enchant = offer.enchantment();
            if (enchant.isPresent()) {
                var e = enchant.get();
                String text = Enchantment.getFullname(e.enchantment(), e.level()).getString();
                result.add(new Offer(icon.isEmpty() ? new ItemStack(Items.ENCHANTED_BOOK) : icon, text,
                        e.emeraldPrice(), target.matches(e.enchantment(), e.level(), e.emeraldPrice())));
            } else {
                result.add(new Offer(icon, name, -1, false));
            }
        }
        return List.copyOf(result);
    }

    private static boolean roundMatches(List<TradeSimulator.SimulatedOffer> offers, EnchantmentTarget target) {
        return offers.stream().anyMatch(o -> o.enchantment()
                .filter(e -> target.matches(e.enchantment(), e.level(), e.emeraldPrice())).isPresent());
    }

    private static Holder<VillagerProfession> librarian(ClientLevel level) {
        return level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION)
                .getOrThrow(VillagerProfession.LIBRARIAN);
    }

    private static <T> T degrade(Throwable e, T fallback) {
        if (apiOk) {
            apiOk = false;
            apiProblem = e.toString();
            VprngClient.LOGGER.warn("VTP call failed, prediction disabled for this session", e);
        }
        return fallback;
    }
}
