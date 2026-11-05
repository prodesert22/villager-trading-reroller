package dev.pedro.vprng.roll;

import dev.pedro.vprng.i18n.I18n;
import dev.pedro.vprng.VprngClient;
import dev.pedro.vprng.bridge.VtpBridge;
import dev.pedro.vprng.config.PoolStore;
import dev.pedro.vprng.config.VprngConfig;
import dev.pedro.vprng.plan.RoundPlanner;
import dev.pedro.vprng.plan.SyncTracker;
import dev.pedro.vprng.vjf.VjfDetection;
import dev.pedro.vprng.vjf.VjfSupport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Closed-loop reroller with a pool of burner villagers.
 *
 * <p>One <em>cycle</em>: place a lectern in every free cell, let villagers claim them, open each claimed villager
 * (one at a time) to make the server generate and send its trades, compare with the VTP targets, then break all
 * lecterns, wait for the villagers to lose their job and for the items to be picked up, and repeat. The first
 * villager whose real offers match a target is the keeper: nothing is broken and the loop stops.</p>
 *
 * <p>With a pool of N villagers each cycle burns up to N rounds of the shared sequence for roughly the same
 * claim/unemploy latency a single villager pays for one, which is where the speed-up comes from. Prediction
 * (see {@link VtpBridge#forecast}) only supplies K / ETA and the "impossible target" abort; the stop condition
 * is always an observed match.</p>
 *
 * <p>All methods must be called on the client thread.</p>
 */
public final class PoolController {
    public enum State { IDLE, PLACE, WAIT_CLAIM, OPEN, WAIT_OFFERS, BREAK, WAIT_RESET, COLLECT, PAUSED, FOUND }

    private static final int MAX_PLACE_FAILS = 3;
    private static final int DEFAULT_CYCLE_TICKS = 160;

    /** Per-villager bookkeeping, keyed by entity id. */
    private static final class VState {
        boolean opened;   // already used in this cycle
        boolean failed;   // open timed out / unreachable in this cycle
        boolean locked;   // has trade XP: cannot lose its job, never usable
        boolean ignored;  // refused to lose its job (holds a lectern outside the pool)
    }

    /** Offers received for the villager we just opened, waiting to be evaluated on the next tick. */
    private record PendingOffers(MerchantOffers offers, int xp) {}

    private PendingOffers pendingOffers;
    private final List<Cell> cells = new ArrayList<>();
    private final Map<Integer, VState> villagers = new HashMap<>();
    private final SyncTracker sync = new SyncTracker();

    private State state = State.IDLE;
    private String status = I18n.tr("st.idle");
    private int stateTicks;
    private int cooldown;
    private long runTicks;
    private long cycleStartTick;
    private double avgCycleTicks = DEFAULT_CYCLE_TICKS;

    private static final int COLLECT_AFTER_TICKS = 25;   // let vanilla's own pickup (10-tick delay) happen first
    /** Max horizontal offset from the starting spot before we walk back (hitbox must clear the cells). */
    private static final double HOME_TOLERANCE = 0.3;

    private InteractionHand lecternHand = InteractionHand.OFF_HAND;
    private int lecternTotal;          // lecterns we own: whole inventory + placed in the pool
    private boolean creative;          // infinite materials: placing consumes nothing, breaking drops nothing
    private Vec3 homePos;              // where the run started; we return here after collecting drops
    private boolean walking;
    private int walkTicks;
    private Vec3 walkLastPos;
    private int stuckTicks;
    private int escapes;
    private int sidestepTicks;
    private boolean sidestepLeft;
    private boolean hop;
    private int originalSlot = -1;     // hotbar selection to restore when the run ends or pauses
    private final ContainerCollector collector = new ContainerCollector();
    private BlockPos containerPos;     // chest/hopper/barrel holding the broken lecterns; null = floor pickup
    private State collectReturn = State.WAIT_RESET;
    private long lastCollectTick = -1_000_000L;
    private boolean sneaking;
    private Boolean savedPauseOnLostFocus; // the player's own setting while we override it
    private int sneakTicks;
    private int kStart = RoundPlanner.UNKNOWN;
    private boolean warnedOutOfWindow;
    private int blockedTicks;          // ticks a cell has been occupied by an entity
    private int vjfTriggers;           // /trigger commands sent in this cycle
    private long lastVjfTick;
    private long lastVjfCheckTick;
    private boolean vjfWarned;
    private Object aimKey;
    private Cell pendingPlace;
    private int pendingPlaceTicks;
    private int placeFails;
    private BlockPos breaking;
    private int breakTicks;

    private int currentOpenId = -1;
    private UUID currentOpenUuid;
    private int nextRoundBefore = -1;
    private String calibrationNote = "";
    private int calibrationFailures;
    private int calibrations;
    private boolean sawMerchantScreen;
    private int lastEmployed;
    private long lastProgressTick;
    private float lastHealth;
    private boolean warnedDay;

    private int opens;
    private int cycles;
    private VtpBridge.Forecast forecast = VtpBridge.Forecast.none(I18n.tr("forecast.notStarted"));
    private String lastSeen = "";
    private String found = "";

    // ---------------------------------------------------------------- pool management

    public List<Cell> cells() {
        return List.copyOf(cells);
    }

    public void loadPool(Minecraft mc) {
        cells.clear();
        cells.addAll(PoolStore.load(mc));
        containerPos = PoolStore.loadContainer(mc);
    }

    /** Chest/hopper/barrel where the broken lecterns end up (via hoppers); null when picking up from the floor. */
    public BlockPos containerPos() {
        return containerPos;
    }

    public void setContainer(Minecraft mc, BlockPos pos) {
        containerPos = pos == null ? null : pos.immutable();
        PoolStore.saveContainer(mc, containerPos);
    }

    /** @return false if the cell was already registered. */
    public boolean addCell(Minecraft mc, Cell cell) {
        if (cells.stream().anyMatch(c -> c.pos().equals(cell.pos()))) return false;
        cells.add(cell);
        PoolStore.save(mc, cells);
        return true;
    }

    /** @return false if no such cell. */
    public boolean removeCell(Minecraft mc, BlockPos pos) {
        boolean removed = cells.removeIf(c -> c.pos().equals(pos));
        if (removed) PoolStore.save(mc, cells);
        return removed;
    }

    public void clearPool(Minecraft mc) {
        cells.clear();
        PoolStore.save(mc, cells);
    }

    // ---------------------------------------------------------------- lifecycle

    public boolean running() {
        return state != State.IDLE && state != State.PAUSED && state != State.FOUND;
    }

    public State state() {
        return state;
    }

    public void toggle(Minecraft mc) {
        if (state == State.IDLE || state == State.FOUND) {
            say(mc, start(mc));
        } else if (state == State.PAUSED) {
            say(mc, resume(mc));
        } else {
            stop(mc, I18n.tr("ctl.stopped"));
        }
    }

    /** @return a user-facing message (error or "Started ..."). */
    public String start(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || mc.gameMode == null) return I18n.tr("act.joinWorld");
        if (running()) return I18n.tr("ctl.alreadyRunning");

        VtpBridge.selfTest();
        if (cells.isEmpty()) {
            return I18n.tr("ctl.poolEmpty");
        }
        if (VtpBridge.targets().isEmpty()) {
            return I18n.tr("ctl.noTargets");
        }
        VprngConfig cfg = VprngConfig.get();
        creative = p.hasInfiniteMaterials();
        String toolProblem = toolProblem(p, level);
        if (toolProblem != null) return toolProblem;
        ItemStack lecternStack = lecternStack(p);
        boolean haveLecterns = !lecternStack.isEmpty();
        int placed = 0;
        for (Cell c : cells) {
            if (!p.isWithinBlockInteractionRange(c.pos(), 0.0)) {
                return I18n.tr("ctl.cellUnreachable", c.pos().toShortString());
            }
            if (isLectern(level, c.pos())) placed++;
        }
        if (containerPos != null && !p.isWithinBlockInteractionRange(containerPos, 0.0)) {
            return I18n.tr("ctl.containerUnreachable", containerPos.toShortString());
        }
        int missing = cells.size() - placed;
        // With a container the lecterns can start inside it and the stack never needs to stay non-empty
        // (nothing is picked up from the floor), so those two checks only apply to floor pickup.
        if (missing > 0 && containerPos == null) {
            if (!haveLecterns) {
                return I18n.tr("ctl.putLecterns");
            }
            if (!creative && lecternStack.getCount() < missing + 1) {
                return I18n.tr("ctl.needStack", missing + 1, lecternStack.getCount());
            }
        }
        // Count the whole inventory, not just one hand: picked-up items may land in any slot.
        lecternTotal = countLecterns(p) + placed;
        homePos = p.position();
        walkTicks = 0;
        originalSlot = p.getInventory().getSelectedSlot();

        kStart = RoundPlanner.UNKNOWN;
        warnedOutOfWindow = false;
        lastCollectTick = -1_000_000L;
        VtpBridge.clearCalibration();
        calibrationNote = "";
        calibrationFailures = 0;
        calibrations = 0;
        vjfWarned = false;
        lastVjfCheckTick = 0;
        VjfSupport.invalidate();
        if (cfg.useVillagerJobFix) VjfSupport.check(mc);

        villagers.clear();
        sync.reset();
        if (mc.getSingleplayerServer() != null) sync.assumeSynced();
        opens = 0;
        cycles = 0;
        runTicks = 0;
        found = "";
        lastSeen = "";
        warnedDay = false;
        lastHealth = p.getHealth();
        refreshForecast(mc);
        ignoreFocusLoss(mc);
        beginCycle(mc);
        String note = forecast.hit().isPresent() ? "" : I18n.tr("ctl.noPrediction", forecast.note());
        String message = I18n.tr("ctl.started", cells.size()) + note
                + (cfg.useVillagerJobFix ? I18n.tr("ctl.vjfChecking") : "");
        if (VprngConfig.get().dayGuard != VprngConfig.DayGuard.OFF && inWorkWindow(level)) {
            warnedDay = true;
            message += I18n.tr("ctl.workWarnStart");
        }
        return message;
    }

    public String resume(Minecraft mc) {
        if (state != State.PAUSED) return I18n.tr("ctl.notPaused");
        if (mc.player == null) return I18n.tr("act.joinWorld");
        LocalPlayer p = mc.player;
        // Same guard as start(): never resume into breaking a block by hand when it needs a real tool.
        creative = p.hasInfiniteMaterials();
        String toolProblem = mc.level == null ? null : toolProblem(p, mc.level);
        if (toolProblem != null) return toolProblem;
        lastHealth = p.getHealth();
        // Re-baseline on the current reality: the player may have repositioned or picked things up by hand.
        originalSlot = p.getInventory().getSelectedSlot();
        lecternTotal = countLecterns(p) + (mc.level == null ? 0 : placedLecterns(mc.level));
        homePos = p.position();
        walkTicks = 0;
        ignoreFocusLoss(mc);
        beginCycle(mc);
        return I18n.tr("ctl.resumed");
    }

    public void stop(Minecraft mc, String reason) {
        releaseBreak(mc);
        stopWalking(mc);
        setSneak(mc, false);
        restoreFocusSetting(mc);
        restoreSlot(mc);
        if (state != State.IDLE) log(mc, reason);
        state = State.IDLE;
        status = reason;
        resetActionState();
    }

    public void pause(Minecraft mc, String reason) {
        releaseBreak(mc);
        stopWalking(mc);
        setSneak(mc, false);
        restoreFocusSetting(mc);
        restoreSlot(mc);
        state = State.PAUSED;
        status = I18n.tr("st.paused", reason);
        log(mc, I18n.tr("ctl.pausedLog", reason));
        resetActionState();
    }

    private void beginCycle(Minecraft mc) {
        for (VState s : villagers.values()) {
            s.opened = false;
            s.failed = false;
        }
        cycleStartTick = runTicks;
        vjfTriggers = 0;
        lastVjfTick = -1_000_000L; // the first trigger of a cycle is not rate limited
        resetActionState();
        setState(State.PLACE, I18n.tr("st.placing"));
    }

    private void resetActionState() {
        aimKey = null;
        pendingPlace = null;
        pendingPlaceTicks = 0;
        placeFails = 0;
        breaking = null;
        breakTicks = 0;
        currentOpenId = -1;
        pendingOffers = null;
        sawMerchantScreen = false;
        cooldown = 0;
    }

    private void setState(State next, String text) {
        state = next;
        status = text;
        stateTicks = 0;
    }

    /** Our own stop must get through the mixin that otherwise blocks vanilla's per-tick reset. */
    private void releaseBreak(Minecraft mc) {
        BlockPos was = breaking;
        breaking = null;
        if (was != null && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    /**
     * True while a pool lectern is being mined. Queried by {@code MultiPlayerGameModeMixin} to cancel
     * vanilla's every-tick {@code stopDestroyBlock()} (see that class). Clearing {@code breaking} first
     * in {@link #releaseBreak} is what lets our own stop call through.
     */
    public boolean suppressStopDestroy() {
        return state == State.BREAK && breaking != null;
    }

    // ---------------------------------------------------------------- tick

    public void tick(Minecraft mc) {
        if (state == State.IDLE || state == State.PAUSED || state == State.FOUND) return;
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || mc.gameMode == null) {
            stopWalking(mc);
            setSneak(mc, false);
            restoreFocusSetting(mc);
            state = State.IDLE;
            status = I18n.tr("st.disconnected");
            resetActionState();
            return;
        }
        runTicks++;
        stateTicks++;
        if (!guardsPass(mc, p, level)) return;
        if (state != State.PLACE) setSneak(mc, false); // sneaking is only for placing (it would stop us opening things)
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        switch (state) {
            case COLLECT -> tickCollect(mc, p, level);
            case PLACE -> tickPlace(mc, p, level);
            case WAIT_CLAIM -> tickWaitClaim(mc, p, level);
            case OPEN -> tickOpen(mc, p, level);
            case WAIT_OFFERS -> tickWaitOffers(mc, p);
            case BREAK -> tickBreak(mc, p, level);
            case WAIT_RESET -> tickWaitReset(mc, p, level);
            default -> { }
        }
    }

    /** Safety checks every tick; returns false (after pausing) when it is not safe to act. */
    private boolean guardsPass(Minecraft mc, LocalPlayer p, ClientLevel level) {
        VprngConfig cfg = VprngConfig.get();
        if (p.isSpectator() || !p.getAbilities().mayBuild) {
            pause(mc, p.isSpectator() ? I18n.tr("pause.spectator") : I18n.tr("pause.noBuild"));
            return false;
        }
        float health = p.getHealth();
        boolean hurt = health < lastHealth - 0.01f;
        lastHealth = health;
        if (cfg.stopOnDamage && hurt) {
            pause(mc, I18n.tr("pause.damage"));
            return false;
        }
        Screen screen = mc.gui.screen();
        if (state == State.COLLECT) {
            if (screen != null && !(screen instanceof AbstractContainerScreen)) {
                pause(mc, ownScreen(screen) ? I18n.tr("pause.ownScreen") : I18n.tr("pause.otherScreen"));
                return false;
            }
            if (screen == null && collector.opened()) {
                pause(mc, I18n.tr("pause.containerClosed"));
                return false;
            }
        } else if (screen instanceof MerchantScreen) {
            sawMerchantScreen = true;
        } else if (screen != null) {
            pause(mc, ownScreen(screen) ? I18n.tr("pause.ownScreen") : I18n.tr("pause.otherScreen"));
            return false;
        } else if (state == State.WAIT_OFFERS && sawMerchantScreen) {
            // The trade screen disappeared without us closing it: the player pressed ESC. Treat as emergency stop.
            pause(mc, I18n.tr("pause.tradeClosed"));
            return false;
        }
        if (cfg.dayGuard == VprngConfig.DayGuard.BLOCK && inWorkWindow(level)) {
            pause(mc, I18n.tr("pause.workTime"));
            return false;
        }
        if (cfg.dayGuard == VprngConfig.DayGuard.WARN && !warnedDay && inWorkWindow(level)) {
            warnedDay = true;
            log(mc, I18n.tr("ctl.workWarn"));
        }
        return true;
    }

    private static boolean ownScreen(Screen screen) {
        return screen.getClass().getName().startsWith("dev.pedro.vprng.");
    }

    private boolean act(Object key, Vec3 target, LocalPlayer p) {
        if (!VprngConfig.get().aimFirst) return true;
        if (key.equals(aimKey)) return true;
        Actions.lookAt(p, target);
        aimKey = key;
        return false;
    }

    private void afterAction() {
        aimKey = null;
        cooldown = VprngConfig.get().actionDelayTicks;
    }

    // ---- COLLECT (take the lecterns out of the container instead of walking to pick them up)

    private void beginCollect(State returnTo) {
        collectReturn = returnTo;
        collector.begin();
        status = I18n.tr("st.collecting");
        state = State.COLLECT;
    }

    private void tickCollect(Minecraft mc, LocalPlayer p, ClientLevel level) {
        if (containerPos == null) {
            state = collectReturn;
            return;
        }
        switch (collector.tick(mc, p, level, containerPos)) {
            case RUNNING -> { }
            case FAILED -> {
                if (mc.gui.screen() != null) p.closeContainer();
                pause(mc, collector.failure());
            }
            case DONE -> {
                lastCollectTick = runTicks;
                // The container may have held spares: never expect fewer lecterns than we now actually own.
                lecternTotal = Math.max(lecternTotal, countLecterns(p) + placedLecterns(level));
                state = collectReturn; // keep stateTicks running so the caller's timeouts still add up
                status = collectReturn == State.PLACE ? I18n.tr("st.placing") : I18n.tr("st.waitReset");
            }
        }
    }

    // ---- PLACE

    private void tickPlace(Minecraft mc, LocalPlayer p, ClientLevel level) {
        VprngConfig cfg = VprngConfig.get();
        if (pendingPlace != null) {
            if (isLectern(level, pendingPlace.pos())) {
                pendingPlace = null;
                placeFails = 0;
            } else if (++pendingPlaceTicks > cfg.placeConfirmTicks) {
                pendingPlace = null;
                if (++placeFails >= MAX_PLACE_FAILS) {
                    pause(mc, I18n.tr("pause.placeRefused"));
                    return;
                }
            } else {
                return;
            }
        }
        Cell next = null;
        for (Cell c : cells) {
            if (!isLectern(level, c.pos())) {
                next = c;
                break;
            }
        }
        if (next == null) {
            setState(State.WAIT_CLAIM, I18n.tr("st.waitClaim"));
            return;
        }
        InteractionHand hand = resolveHand(p, true);
        if (hand == null && containerPos != null) {
            // Out of lecterns in hand: fetch them from the container (retrying while they travel down the hoppers).
            if (stateTicks > cfg.containerTimeoutTicks) {
                pause(mc, I18n.tr("pause.noLecternsAnywhere", containerPos.toShortString()));
            } else if (runTicks - lastCollectTick >= cfg.containerRetryTicks) {
                beginCollect(State.PLACE);
            }
            return;
        }
        if (hand == null) {
            if (stateTicks > cfg.pickupTimeoutTicks) {
                int have = countLecterns(p);
                pause(mc, have > 0 ? I18n.tr("pause.lecternsNotInHand", have)
                        : creative ? I18n.tr("pause.noLecternLeftAny") : I18n.tr("pause.noLecternLeft", lecternTotal));
            }
            return;
        }
        lecternHand = hand;

        // Always place from the spot the run started from, and never with the player's hitbox inside the cell:
        // vanilla refuses to place a block where an entity stands, which looked like "cannot place the lectern".
        // With a container the player stays put by design: never walk, just report if something is wrong.
        boolean mayWalk = cfg.autoCollect && containerPos == null;
        if (mayWalk && homePos != null && horizontalDistance(p, homePos) > HOME_TOLERANCE) {
            stateTicks--; // freeze the pickup timers while we reposition
            if (walkToward(mc, p, homePos, HOME_TOLERANCE * 0.5, true)) walkTicks = 0;
            else if (++walkTicks > cfg.walkTimeoutTicks) {
                stopWalking(mc);
                pause(mc, I18n.tr("pause.homeBlocked"));
            }
            return;
        }
        if (overlapsCell(p, next) && !mayWalk) {
            pause(mc, I18n.tr("pause.overlapNoWalk", next.pos().toShortString()));
            return;
        }
        if (overlapsCell(p, next)) {
            stateTicks--;
            Vec3 away = p.position().subtract(Vec3.atCenterOf(next.pos()));
            double len = Math.hypot(away.x, away.z);
            Vec3 target = len < 1.0e-3 ? p.position().add(0.6, 0.0, 0.0)
                    : p.position().add(away.x / len * 0.6, 0.0, away.z / len * 0.6);
            if (!walkToward(mc, p, target, 0.1, false) && ++walkTicks > cfg.walkTimeoutTicks) {
                stopWalking(mc);
                pause(mc, I18n.tr("pause.overlapStep", next.pos().toShortString()));
            }
            return;
        }
        stopWalking(mc);
        walkTicks = 0;
        if (!p.isWithinBlockInteractionRange(next.pos(), 0.0)) {
            pause(mc, I18n.tr("pause.cellUnreachable", next.pos().toShortString()));
            return;
        }
        // Vanilla refuses to place a block where an entity stands. The usual culprit is a burner villager that
        // stepped into the cell when its lectern (the wall of its pen) was broken. Wait for it instead of failing.
        List<Entity> blockers = level.getEntities((Entity) null, new AABB(next.pos()).deflate(1.0E-3),
                e -> e.blocksBuilding && !e.isRemoved() && e != p);
        if (!blockers.isEmpty()) {
            String who = blockers.getFirst().getName().getString();
            if (++blockedTicks > cfg.placeBlockedTimeoutTicks) {
                pause(mc, I18n.tr("pause.blocked", who, next.pos().toShortString(), blockedTicks / 20));
            } else {
                status = I18n.tr("st.waitCell", who, next.pos().toShortString());
            }
            return;
        }
        blockedTicks = 0;
        // A non-replaceable block in the cell (e.g. something built there after the pool was registered) can never
        // take a lectern; say so instead of failing three times.
        BlockState occupant = level.getBlockState(next.pos());
        if (!occupant.isAir() && !occupant.canBeReplaced()) {
            pause(mc, I18n.tr("pause.occupied", next.pos().toShortString(), occupant.getBlock().getName().getString()));
            return;
        }
        if (level.getBlockState(next.support()).isAir()) {
            pause(mc, I18n.tr("pause.noSupport", next.pos().toShortString()));
            return;
        }
        // Clicking a hopper/chest (or door, crafting table...) as the support would USE it instead of placing against
        // it, unless we are sneaking. The server learns about sneaking from a packet, so give it a few ticks.
        if (cfg.sneakWhenPlacing) {
            if (!sneaking) {
                setSneak(mc, true);
                return;
            }
            if (++sneakTicks < 3) return;
        }
        if (!act(next, next.placeHit().getLocation(), p)) return;
        InteractionResult result = Actions.place(mc, next, lecternHand);
        if (result.consumesAction()) {
            pendingPlace = next;
            pendingPlaceTicks = 0;
        } else if (++placeFails >= MAX_PLACE_FAILS) {
            // Say exactly what the client saw, so the cause is visible instead of guessed.
            String why = I18n.tr("pause.cannotPlace", next.pos().toShortString(), result.getClass().getSimpleName(),
                    level.getBlockState(next.pos()).getBlock().getName().getString(),
                    level.getBlockState(next.support()).getBlock().getName().getString(),
                    String.valueOf(p.isShiftKeyDown()), String.valueOf(lecternHand),
                    p.getItemInHand(lecternHand).getHoverName().getString());
            VprngClient.LOGGER.warn("[VPRNG] {}", why);
            pause(mc, why);
        }
        afterAction();
    }

    // ---- WAIT_CLAIM / OPEN

    private void tickWaitClaim(Minecraft mc, LocalPlayer p, ClientLevel level) {
        List<Villager> candidates = scanVillagers(p, level);
        if (candidates.stream().anyMatch(this::ready)) {
            lastProgressTick = runTicks;
            lastEmployed = employed(candidates);
            setState(State.OPEN, I18n.tr("st.opening"));
            return;
        }
        forceJobs(mc, candidates, level);
        if (candidates.isEmpty() && stateTicks > 40) {
            pause(mc, I18n.tr("pause.noVillager"));
        } else if (stateTicks > VprngConfig.get().claimTimeoutTicks) {
            pause(mc, I18n.tr("pause.noClaim"));
        }
    }

    private void tickOpen(Minecraft mc, LocalPlayer p, ClientLevel level) {
        List<Villager> candidates = scanVillagers(p, level);
        int employedNow = employed(candidates);
        if (employedNow > lastEmployed) lastProgressTick = runTicks;
        lastEmployed = employedNow;

        List<Villager> ready = candidates.stream().filter(this::ready)
                .sorted(Comparator.comparingDouble(v -> v.distanceToSqr(p))).toList();
        if (ready.isEmpty()) {
            boolean jobless = candidates.stream().anyMatch(v -> v.getVillagerData().profession().is(VillagerProfession.NONE));
            boolean waiting = jobless && placedLecterns(level) > employedNow
                    && runTicks - lastProgressTick < VprngConfig.get().claimGraceTicks;
            if (waiting) forceJobs(mc, candidates, level);
            else setState(State.BREAK, I18n.tr("st.resetting"));
            return;
        }
        Villager v = ready.get(0);
        if (!act(v.getId(), Actions.center(v), p)) return;
        nextRoundBefore = VtpBridge.nextRound(level);
        currentOpenId = v.getId();
        currentOpenUuid = v.getUUID();
        sawMerchantScreen = false;
        Actions.open(mc, v);
        afterAction();
        setState(State.WAIT_OFFERS, I18n.tr("st.waitTrades"));
    }

    private void tickWaitOffers(Minecraft mc, LocalPlayer p) {
        if (pendingOffers != null) {
            processOffers(mc, p);
            return;
        }
        if (stateTicks <= VprngConfig.get().offersTimeoutTicks) return;
        // The offers never arrived (villager busy with another player, packet lost, ...). Do not stall the pool.
        VtpBridge.clearInteraction();
        if (mc.gui.screen() != null) p.closeContainer();
        VState s = villagers.computeIfAbsent(currentOpenId, id -> new VState());
        s.failed = true;
        currentOpenId = -1;
        sawMerchantScreen = false;
        setState(State.OPEN, I18n.tr("st.opening"));
    }

    /**
     * Called from the {@code handleMerchantOffers} mixin. It only records the packet; the work happens on the
     * next controller tick ({@link #processOffers}). Deferring one tick means VTP has certainly finished its own
     * handling of the same packet, so its tracked sequence position is up to date no matter how the two mixins
     * are ordered, and no heavy work runs inside the network handler.
     */
    public void onOffers(Minecraft mc, ClientboundMerchantOffersPacket packet) {
        if (state != State.WAIT_OFFERS || pendingOffers != null) return;
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (!(p.containerMenu instanceof MerchantMenu menu) || menu.containerId != packet.getContainerId()) return;
        pendingOffers = new PendingOffers(packet.getOffers(), packet.getVillagerXp());
    }

    private void processOffers(Minecraft mc, LocalPlayer p) {
        PendingOffers received = pendingOffers;
        pendingOffers = null;
        ClientLevel level = mc.level;
        if (received == null || level == null) return;
        try {
            VState vs = villagers.computeIfAbsent(currentOpenId, id -> new VState());
            vs.opened = true;
            if (received.xp() > 0) vs.locked = true;
            opens++;

            int after = VtpBridge.nextRound(level);
            boolean singleplayer = mc.getSingleplayerServer() != null;
            // VTP aligned itself on this open iff its position advanced by exactly one round.
            boolean alignedByVtp = nextRoundBefore >= 0 && after == nextRoundBefore + 1;
            if (singleplayer) sync.assumeSynced();
            else sync.onOpen(nextRoundBefore, after);

            lastSeen = OfferMatcher.summarize(received.offers());
            Optional<OfferMatcher.Match> match = OfferMatcher.find(received.offers(), VtpBridge.targets());
            if (match.isPresent()) {
                onFound(mc, p, match.get());
                return;
            }
            if (!singleplayer) autoCalibrate(mc, received, alignedByVtp);
            p.closeContainer();
            sawMerchantScreen = false;
            currentOpenId = -1;
            refreshForecast(mc);
            cooldown = VprngConfig.get().actionDelayTicks;
            setState(State.OPEN, I18n.tr("st.opening"));
        } catch (RuntimeException | LinkageError e) {
            VprngClient.LOGGER.error("Error while handling merchant offers", e);
            pause(mc, I18n.tr("pause.internal"));
        }
    }

    /**
     * Multiplayer only. When VTP did not align its sequence position on this open, feed the observation to VTP's
     * own calibration (same transaction as {@code /vtp calibrate}) so the prediction becomes trustworthy without
     * the player typing observations. Applied only when exactly one position matches.
     */
    private void autoCalibrate(Minecraft mc, PendingOffers received, boolean alignedByVtp) {
        if (alignedByVtp) {
            // In step: nothing to fix, and older observations are no longer consecutive with future ones.
            VtpBridge.clearCalibration();
            calibrationNote = "";
            return;
        }
        if (!VprngConfig.get().autoCalibrate) return;
        if (mc.level != null && inWorkWindow(mc.level)) {
            // By day burners generate their own trades while "working", so rounds are consumed in an order we do
            // not control and a "consecutive" run is not consecutive: calibrating would mis-set VTP's position.
            VtpBridge.clearCalibration();
            calibrationNote = I18n.tr("cal.daytime");
            return;
        }
        VtpBridge.Calibration result = VtpBridge.calibrate(mc, currentOpenUuid, received.offers());
        if (result.calibrated()) {
            sync.assumeSynced();
            calibrationNote = "";
            calibrations++;
            log(mc, I18n.tr("ctl.calibrated", result.round(), result.note()));
        } else {
            calibrationNote = result.note();
            // Do not spam chat: report the first failure and then every fifth.
            if (calibrationFailures++ % 5 == 0) log(mc, I18n.tr("ctl.calibrating", result.note()));
        }
    }

    private void onFound(Minecraft mc, LocalPlayer p, OfferMatcher.Match match) {
        restoreSlot(mc);
        restoreFocusSetting(mc);
        found = match.describe();
        state = State.FOUND;
        status = I18n.tr("st.found", found);
        stateTicks = 0;
        resetActionState();
        say(mc, I18n.tr("ctl.found", found, opens, cycles));
        if (VprngConfig.get().foundSound) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0F));
        }
    }

    // ---- BREAK / WAIT_RESET

    private void tickBreak(Minecraft mc, LocalPlayer p, ClientLevel level) {
        BlockPos target = null;
        for (Cell c : cells) {
            if (isLectern(level, c.pos())) {
                target = c.pos();
                break;
            }
        }
        if (target == null) {
            breaking = null;
            setState(State.WAIT_RESET, I18n.tr("st.waitReset"));
            return;
        }
        if (!p.isWithinBlockInteractionRange(target, 0.0)) {
            pause(mc, I18n.tr("pause.lecternUnreachable", target.toShortString()));
            return;
        }
        if (!target.equals(breaking)) {
            // Pick the fastest suitable tool in the hotbar before starting (never switch mid-break: it resets
            // the progress). A block that needs the right tool must never be mined by hand: it would vanish.
            BlockState state = level.getBlockState(target);
            ToolChoice tool = bestTool(p, state);
            if (!creative && state.requiresCorrectToolForDrops() && !tool.correct()) {
                pause(mc, noToolMessage(state));
                return;
            }
            selectSlot(p, tool.slot());
            // Aim once (one tick ahead), then start; while the same block keeps breaking we just continue.
            if (!act(target, Vec3.atCenterOf(target), p)) return;
            Actions.startBreak(mc, target);
            breaking = target;
            breakTicks = 0;
            aimKey = null;
        } else {
            Actions.continueBreak(mc, target);
            if (++breakTicks > VprngConfig.get().breakTimeoutTicks) {
                pause(mc, I18n.tr("pause.breakSlow"));
            }
        }
    }

    private void tickWaitReset(Minecraft mc, LocalPlayer p, ClientLevel level) {
        VprngConfig cfg = VprngConfig.get();
        List<Villager> candidates = scanVillagers(p, level);
        boolean stillEmployed = candidates.stream()
                .anyMatch(v -> v.getVillagerData().profession().is(VillagerProfession.LIBRARIAN));
        // Whole-inventory count: the dropped lecterns may merge into any slot, not necessarily the one in hand.
        int owned = countLecterns(p);
        int expected = lecternTotal - placedLecterns(level);
        boolean pickedUp = creative || owned >= expected; // creative: nothing is consumed and nothing drops

        boolean viaContainer = containerPos != null;
        // 0. Container mode: the player never moves. The lecterns travel down the hoppers into the container and we
        //    take them out; retry until they have all arrived (a hopper moves one item roughly every 8 ticks).
        if (viaContainer && !pickedUp && stateTicks > COLLECT_AFTER_TICKS
                && runTicks - lastCollectTick >= cfg.containerRetryTicks) {
            beginCollect(State.WAIT_RESET);
            return;
        }
        // 1. Lecterns still lying around: walk onto them (vanilla picks them up when we are close enough).
        if (!viaContainer && !pickedUp && stateTicks > COLLECT_AFTER_TICKS) {
            ItemEntity drop = nearestDroppedLectern(p, level, cfg.collectRadius);
            if (drop != null) {
                if (!cfg.autoCollect) {
                    if (stateTicks > cfg.pickupTimeoutTicks) {
                        pause(mc, I18n.tr("pause.dropsOut"));
                    }
                    return;
                }
                stateTicks--; // freeze the reset timers while we move
                if (walkToward(mc, p, drop.position(), 0.3, false)) walkTicks = 0;
                else if (++walkTicks > cfg.walkTimeoutTicks) {
                    stopWalking(mc);
                    pause(mc, I18n.tr("pause.dropsBlocked"));
                }
                return;
            }
        }
        // 2. Everything is back in the inventory: return to the spot the pool was set up from.
        if (!viaContainer && pickedUp && cfg.autoCollect && homePos != null
                && horizontalDistance(p, homePos) > HOME_TOLERANCE) {
            stateTicks--;
            if (walkToward(mc, p, homePos, HOME_TOLERANCE * 0.5, true)) walkTicks = 0;
            else if (++walkTicks > cfg.walkTimeoutTicks) {
                stopWalking(mc);
                pause(mc, I18n.tr("pause.homeStart"));
            }
            return;
        }
        stopWalking(mc);
        walkTicks = 0;

        if (!stillEmployed && pickedUp) {
            endCycle(mc);
            return;
        }
        if (viaContainer && !pickedUp && stateTicks > cfg.containerTimeoutTicks) {
            pause(mc, I18n.tr("pause.notInContainer", containerPos.toShortString(), owned, expected));
            return;
        }
        if (!viaContainer && !pickedUp && stateTicks > cfg.pickupTimeoutTicks) {
            pause(mc, I18n.tr("pause.missing", owned, expected));
            return;
        }
        if (stillEmployed && stateTicks > cfg.resetTimeoutTicks) {
            for (Villager v : candidates) {
                if (v.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)) {
                    villagers.computeIfAbsent(v.getId(), id -> new VState()).ignored = true;
                }
            }
            log(mc, I18n.tr("ctl.keptJob"));
        }
    }

    private void endCycle(Minecraft mc) {
        cycles++;
        double ticks = runTicks - cycleStartTick;
        avgCycleTicks = cycles == 1 ? ticks : avgCycleTicks * 0.7 + ticks * 0.3;
        int max = VprngConfig.get().maxCycles;
        if (max > 0 && cycles >= max) {
            stop(mc, I18n.tr("ctl.maxCycles", max));
            return;
        }
        refreshForecast(mc);
        // No wanted book inside VTP's search window is NOT a reason to stop: every open advances the sequence and the
        // window slides with it, so keep burning until a target comes into range (or an open shows the real match).
        if (isOutOfWindow() && !warnedOutOfWindow) {
            warnedOutOfWindow = true;
            log(mc, I18n.tr("ctl.outOfWindow", forecast.note()));
        } else if (!isOutOfWindow()) {
            warnedOutOfWindow = false; // tell the player again if it ever falls out of range later
        }
        beginCycle(mc);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * With the Villager Job Fix data pack enabled in the config and detected on the server, nudge jobless
     * villagers into the placed lecterns right away ({@code /trigger vjf_single|vjf_multiple}) instead of waiting
     * for them to walk there. Rate limited per cycle; a no-op when the option is off or the pack is missing.
     */
    private void forceJobs(Minecraft mc, List<Villager> candidates, ClientLevel level) {
        VprngConfig cfg = VprngConfig.get();
        if (!cfg.useVillagerJobFix) return;
        VjfDetection.Result r = VjfSupport.result();
        switch (r.status()) {
            case CHECKING -> {
                return;
            }
            case UNKNOWN -> {
                // Not asked yet or no reply (e.g. chat typing cancelled it): ask again, but not every tick.
                if (runTicks - lastVjfCheckTick > 100) {
                    lastVjfCheckTick = runTicks;
                    VjfSupport.check(mc);
                }
                return;
            }
            case ABSENT -> {
                if (!vjfWarned) {
                    vjfWarned = true;
                    log(mc, I18n.tr("ctl.vjfMissing", r.describe()));
                }
                return;
            }
            default -> { }
        }
        if (vjfTriggers >= cfg.vjfMaxTriggersPerCycle || runTicks - lastVjfTick < cfg.vjfRetryTicks) return;
        // Nothing to force unless a lectern is in place and a jobless villager is waiting for one.
        boolean jobless = candidates.stream()
                .anyMatch(v -> v.getVillagerData().profession().is(VillagerProfession.NONE));
        if (!jobless || placedLecterns(level) == 0) return;

        boolean multiple = switch (cfg.vjfMode) {
            case MULTIPLE -> true;
            case SINGLE -> false;
            case AUTO -> cells.size() > 1;
        };
        if (multiple && !r.multiple()) multiple = false;      // use whichever trigger the pack actually offers
        else if (!multiple && !r.single()) multiple = true;
        if (VjfSupport.trigger(mc, multiple)) {
            vjfTriggers++;
            lastVjfTick = runTicks;
            status = I18n.tr("st.vjf", multiple ? "multiple" : "single");
        }
    }

    /** Total lecterns in the whole inventory (hotbar, main slots and off hand). */
    static int countLecterns(LocalPlayer p) {
        Inventory inv = p.getInventory();
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.is(Items.LECTERN)) total += stack.getCount();
        }
        ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
        if (off.is(Items.LECTERN)) total += off.getCount();
        return total;
    }

    /** Stack lecterns are placed from: off hand, else the selected slot, else any hotbar slot; EMPTY if none. */
    private static ItemStack lecternStack(LocalPlayer p) {
        Inventory inv = p.getInventory();
        ItemStack off = inv.getItem(Inventory.SLOT_OFFHAND);
        if (off.is(Items.LECTERN)) return off;
        ItemStack selected = inv.getItem(inv.getSelectedSlot());
        if (selected.is(Items.LECTERN)) return selected;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.is(Items.LECTERN)) return stack;
        }
        return ItemStack.EMPTY;
    }

    /**
     * The hand to place lecterns with. The off hand if it holds them, otherwise the main hand, switching the
     * hotbar selection to a slot that holds lecterns when needed (the tool slot is selected again for breaking).
     * Returns null if there are none in the off hand or hotbar.
     */
    private InteractionHand resolveHand(LocalPlayer p, boolean switchSlot) {
        Inventory inv = p.getInventory();
        if (inv.getItem(Inventory.SLOT_OFFHAND).is(Items.LECTERN)) return InteractionHand.OFF_HAND;
        if (inv.getItem(inv.getSelectedSlot()).is(Items.LECTERN)) return InteractionHand.MAIN_HAND;
        for (int slot = 0; slot < 9; slot++) {
            if (inv.getItem(slot).is(Items.LECTERN)) {
                if (switchSlot) selectSlot(p, slot);
                return InteractionHand.MAIN_HAND;
            }
        }
        return null;
    }

    private record ToolChoice(int slot, float speed, boolean correct) {}

    /**
     * Best hotbar slot to break {@code state} with: a tool that is "correct" for the block first (matters when the
     * block needs one to drop), then the fastest, keeping the current slot on ties.
     */
    private static ToolChoice bestTool(LocalPlayer p, BlockState state) {
        Inventory inv = p.getInventory();
        int selected = inv.getSelectedSlot();
        int bestSlot = selected;
        float bestSpeed = -1.0F;
        boolean bestCorrect = false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inv.getItem(slot);
            float speed = stack.getDestroySpeed(state);
            boolean correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
            boolean better = (correct && !bestCorrect)
                    || (correct == bestCorrect && speed > bestSpeed + 1.0e-4F)
                    || (bestSlot != selected && slot == selected && correct == bestCorrect
                            && Math.abs(speed - bestSpeed) <= 1.0e-4F);
            if (bestSpeed < 0 || better) {
                bestSlot = slot;
                bestSpeed = speed;
                bestCorrect = correct;
            }
        }
        return new ToolChoice(bestSlot, bestSpeed, bestCorrect);
    }

    private static String noToolMessage(BlockState state) {
        return I18n.tr("ctl.noTool", state.getBlock().getName().getString());
    }

    /** Null if breaking the pool's blocks is safe, otherwise the user-facing reason it is not. */
    private String toolProblem(LocalPlayer p, ClientLevel level) {
        if (creative) return null; // creative breaks instantly and drops nothing anyway
        BlockState state = Blocks.LECTERN.defaultBlockState();
        for (Cell c : cells) {
            if (isLectern(level, c.pos())) {
                state = level.getBlockState(c.pos());
                break;
            }
        }
        ToolChoice tool = bestTool(p, state);
        if (state.requiresCorrectToolForDrops() && !tool.correct()) return noToolMessage(state);
        return null;
    }

    /** While running, stop vanilla from opening the pause screen when the window loses focus (our guard would pause). */
    private void ignoreFocusLoss(Minecraft mc) {
        if (!VprngConfig.get().runWhenUnfocused || savedPauseOnLostFocus != null) return;
        savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
        mc.options.pauseOnLostFocus = false;
    }

    private void restoreFocusSetting(Minecraft mc) {
        if (savedPauseOnLostFocus == null) return;
        mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
        savedPauseOnLostFocus = null;
    }

    private void setSneak(Minecraft mc, boolean on) {
        if (sneaking == on) return;
        mc.options.keyShift.setDown(on);
        sneaking = on;
        sneakTicks = 0;
    }

    private void selectSlot(LocalPlayer p, int slot) {
        Inventory inv = p.getInventory();
        if (inv.getSelectedSlot() == slot) return;
        inv.setSelectedSlot(slot);
        p.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }

    /** Puts the hotbar selection back where the player had it when the run started. */
    private void restoreSlot(Minecraft mc) {
        if (mc.player != null && originalSlot >= 0 && originalSlot <= 8) selectSlot(mc.player, originalSlot);
    }

    private static boolean overlapsCell(LocalPlayer p, Cell cell) {
        return p.getBoundingBox().intersects(new AABB(cell.pos()));
    }

    /** Closest dropped lectern near the player and the spot the run started from (never chases far items). */
    private ItemEntity nearestDroppedLectern(LocalPlayer p, ClientLevel level, double radius) {
        ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(radius),
                e -> e.getItem().is(Items.LECTERN))) {
            if (Math.abs(item.getY() - p.getY()) > 1.5) continue; // do not walk off ledges after it
            if (homePos != null && Math.hypot(item.getX() - homePos.x, item.getZ() - homePos.z) > radius) continue;
            double distance = item.distanceToSqr(p);
            if (distance < bestDistance) {
                best = item;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static double horizontalDistance(LocalPlayer p, Vec3 target) {
        return Math.hypot(target.x - p.getX(), target.z - p.getZ());
    }

    /**
     * Walks toward {@code target} (horizontal). Returns true once within {@code tolerance} blocks; with
     * {@code settle} it also waits until the player has stopped sliding, and releases "forward" early so friction
     * carries the player onto the spot instead of overshooting it (this is what keeps the player aligned with
     * the cells). If the player stops making progress (caught on a block corner) it sidesteps and then hops.
     * Always bounded by the caller's walk timeout.
     */
    private boolean walkToward(Minecraft mc, LocalPlayer p, Vec3 target, double tolerance, boolean settle) {
        double distance = horizontalDistance(p, target);
        double speed = p.getDeltaMovement().horizontalDistance();
        if (distance <= tolerance) {
            stopWalking(mc);
            return !settle || speed < 0.02;
        }
        Actions.lookAt(p, new Vec3(target.x, p.getEyeY(), target.z));
        boolean coast = settle && speed > 0.03 && distance <= speed * 1.3 + tolerance;
        mc.options.keyUp.setDown(!coast);
        walking = true;
        updateUnstick(mc, p, !coast);
        return false;
    }

    /** Escalating escape from block corners: after ~6 ticks without movement, strafe, then strafe the other way and hop. */
    private void updateUnstick(Minecraft mc, LocalPlayer p, boolean pushing) {
        Vec3 pos = p.position();
        boolean stuck = pushing && walkLastPos != null && Math.hypot(pos.x - walkLastPos.x, pos.z - walkLastPos.z) < 0.01;
        walkLastPos = pos;
        stuckTicks = stuck ? stuckTicks + 1 : 0;
        if (stuckTicks >= 6) {
            stuckTicks = 0;
            escapes++;
            sidestepTicks = 8;
            sidestepLeft = escapes % 2 == 1;
            hop = escapes >= 2;
        }
        if (sidestepTicks > 0) {
            sidestepTicks--;
            mc.options.keyLeft.setDown(sidestepLeft);
            mc.options.keyRight.setDown(!sidestepLeft);
            mc.options.keyJump.setDown(hop);
        } else {
            mc.options.keyLeft.setDown(false);
            mc.options.keyRight.setDown(false);
            mc.options.keyJump.setDown(false);
        }
    }

    private void stopWalking(Minecraft mc) {
        if (!walking) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        walking = false;
        walkLastPos = null;
        stuckTicks = 0;
        escapes = 0;
        sidestepTicks = 0;
        hop = false;
    }

    private void refreshForecast(Minecraft mc) {
        forecast = VtpBridge.forecast(mc);
        int burn = roundsToBurn();
        if (burn != RoundPlanner.UNKNOWN && (kStart == RoundPlanner.UNKNOWN || burn > kStart)) kStart = burn;
    }

    /** True when VTP searched its whole window and found no wanted book: the run keeps burning until one appears. */
    public boolean isOutOfWindow() {
        return forecast.hit().isEmpty() && forecast.outOfWindow();
    }

    /** Largest rounds-to-burn seen this run: the reference for the HUD progress bar. */
    public int roundsToBurnStart() {
        return kStart;
    }

    private boolean ready(Villager v) {
        VState s = villagers.get(v.getId());
        if (s != null && (s.opened || s.failed || s.locked || s.ignored)) return false;
        return v.getVillagerData().profession().is(VillagerProfession.LIBRARIAN);
    }

    private int employed(List<Villager> candidates) {
        int n = 0;
        for (Villager v : candidates) {
            if (v.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)) n++;
        }
        return n;
    }

    private int placedLecterns(ClientLevel level) {
        int n = 0;
        for (Cell c : cells) {
            if (isLectern(level, c.pos())) n++;
        }
        return n;
    }

    private static boolean isLectern(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.LECTERN);
    }

    /** Villagers near enough to open that could serve as burners: adults, level 1, jobless or librarian. */
    private List<Villager> scanVillagers(LocalPlayer p, ClientLevel level) {
        double radius = VprngConfig.get().scanRadius;
        List<Villager> result = new ArrayList<>();
        for (Villager v : level.getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(radius))) {
            if (!v.isAlive() || v.isBaby()) continue;
            var data = v.getVillagerData();
            if (data.level() != 1) continue;
            boolean jobless = data.profession().is(VillagerProfession.NONE);
            boolean librarian = data.profession().is(VillagerProfession.LIBRARIAN);
            if (!jobless && !librarian) continue;
            VState s = villagers.get(v.getId());
            if (s != null && (s.locked || s.ignored)) continue;
            if (!p.isWithinEntityInteractionRange(v, 0.0)) continue;
            result.add(v);
        }
        return result;
    }

    /** Villagers generate their own trades while working (roughly ticks 2000-9000 of the day). */
    static boolean inWorkWindow(ClientLevel level) {
        try {
            long t = Math.floorMod(level.getOverworldClockTime(), 24000L);
            return t >= 2000 && t < 9000;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A direct answer to something the player did, or the goal being reached: always shown. */
    private void say(Minecraft mc, String message) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[VPRNG] " + message));
    }

    /** Progress/diagnostic message (pauses, calibration, warnings...): shown only when {@code chatLogs} is on. */
    private void log(Minecraft mc, String message) {
        if (VprngConfig.get().chatLogs) say(mc, message);
    }

    // ---------------------------------------------------------------- HUD / status

    public String status() {
        return status;
    }

    public int opens() {
        return opens;
    }

    public int cycles() {
        return cycles;
    }

    public double opensPerMinute() {
        return runTicks <= 0 ? 0 : opens / (runTicks / 1200.0);
    }

    public VtpBridge.Forecast forecast() {
        return forecast;
    }

    public SyncTracker sync() {
        return sync;
    }

    /** Why automatic calibration has not aligned VTP yet (empty when in step). */
    public String calibrationNote() {
        return calibrationNote;
    }

    public int calibrations() {
        return calibrations;
    }

    public String lastSeen() {
        return lastSeen;
    }

    public String foundText() {
        return found;
    }

    /**
     * Seconds until the predicted hit at the measured cycle speed, or a negative number if there is no
     * prediction. Only trustworthy while {@code sync().synced()}; the HUD marks it unverified otherwise.
     */
    public double etaSeconds() {
        if (forecast.hit().isEmpty()) return -1;
        return RoundPlanner.etaSeconds(forecast.hit().get().roundsToBurn(), cells.size(), avgCycleTicks / 20.0);
    }

    /** Rounds still to burn before the predicted hit, or {@link RoundPlanner#UNKNOWN}. */
    public int roundsToBurn() {
        return forecast.hit().map(VtpBridge.Hit::roundsToBurn).orElse(RoundPlanner.UNKNOWN);
    }

    public boolean nextOpenIsKeeper() {
        return RoundPlanner.nextOpenIsKeeper(roundsToBurn(), sync.synced());
    }
}
