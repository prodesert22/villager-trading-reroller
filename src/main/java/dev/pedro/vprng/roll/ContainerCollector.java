package dev.pedro.vprng.roll;

import dev.pedro.vprng.i18n.I18n;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Takes the lecterns out of a chest / hopper / barrel the way a player would: right-click it, wait for the
 * screen and its contents, shift-click (or swap into the empty off hand) every lectern stack, close it.
 * One slot per tick. Used instead of walking over to pick the broken lecterns up from the floor, so the
 * player never has to move.
 *
 * <p>Drive it with {@link #begin()} once, then {@link #tick} every client tick until it stops returning
 * {@link Result#RUNNING}. Client thread only.</p>
 */
public final class ContainerCollector {
    public enum Result { RUNNING, DONE, FAILED }

    private enum Step { OPEN, WAIT_SCREEN, SYNC, TAKE, CLOSE }

    private static final int OPEN_TIMEOUT_TICKS = 40;
    private static final int SYNC_TICKS = 3; // the slot contents arrive in a packet right after the screen opens
    private static final int OFFHAND_BUTTON = 40;

    private Step step = Step.OPEN;
    private int ticks;
    private int containerId;
    private boolean opened;
    private int taken;
    private String failure = "";
    private final Deque<Integer> lecternSlots = new ArrayDeque<>();

    public void begin() {
        step = Step.OPEN;
        ticks = 0;
        opened = false;
        taken = 0;
        failure = "";
        lecternSlots.clear();
    }

    /** True once the container screen is up (so a missing screen afterwards means the player closed it). */
    public boolean opened() {
        return opened;
    }

    public int taken() {
        return taken;
    }

    public String failure() {
        return failure;
    }

    public Result tick(Minecraft mc, LocalPlayer p, ClientLevel level, BlockPos pos) {
        ticks++;
        switch (step) {
            case OPEN -> {
                if (level.getBlockState(pos).isAir()) return fail(I18n.tr("container.none", pos.toShortString()));
                if (!p.isWithinBlockInteractionRange(pos, 0.0)) {
                    return fail(I18n.tr("container.unreachable", pos.toShortString()));
                }
                Direction face = Actions.breakFace(p, pos);
                Vec3 at = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                Actions.lookAt(p, at);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(at, face, pos, false));
                step = Step.WAIT_SCREEN;
                ticks = 0;
            }
            case WAIT_SCREEN -> {
                if (mc.gui.screen() instanceof AbstractContainerScreen<?> && p.containerMenu != p.inventoryMenu) {
                    containerId = p.containerMenu.containerId;
                    opened = true;
                    step = Step.SYNC;
                    ticks = 0;
                } else if (ticks > OPEN_TIMEOUT_TICKS) {
                    return fail(I18n.tr("container.cannotOpen", pos.toShortString()));
                }
            }
            case SYNC -> {
                if (p.containerMenu.containerId != containerId) return fail(I18n.tr("container.closed"));
                if (ticks >= SYNC_TICKS) {
                    for (Slot slot : p.containerMenu.slots) {
                        if (slot.container != p.getInventory() && slot.getItem().is(Items.LECTERN)) {
                            lecternSlots.add(slot.index);
                        }
                    }
                    step = Step.TAKE;
                }
            }
            case TAKE -> {
                if (p.containerMenu.containerId != containerId) return fail(I18n.tr("container.closed"));
                Integer slot = lecternSlots.poll();
                if (slot == null) {
                    step = Step.CLOSE;
                } else {
                    // The first stack goes into an empty off hand (where placing expects it); the rest shift-click.
                    boolean offhandEmpty = p.getInventory().getItem(Inventory.SLOT_OFFHAND).isEmpty();
                    if (taken == 0 && offhandEmpty) {
                        mc.gameMode.handleContainerInput(containerId, slot, OFFHAND_BUTTON, ContainerInput.SWAP, p);
                    } else {
                        mc.gameMode.handleContainerInput(containerId, slot, 0, ContainerInput.QUICK_MOVE, p);
                    }
                    taken++;
                }
            }
            case CLOSE -> {
                p.closeContainer();
                return Result.DONE;
            }
        }
        return Result.RUNNING;
    }

    private Result fail(String reason) {
        failure = reason;
        return Result.FAILED;
    }
}
