package dev.pedro.vprng.ui;

import dev.pedro.vprng.i18n.I18n;
import dev.pedro.vprng.VprngClient;
import dev.pedro.vprng.roll.Cell;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Pool/container operations shared by the chat commands and the GUI. Each returns the message to show the player.
 * The "looked at" variants read the crosshair, so the GUI must close its screen first (see {@code VprngClient.later}).
 */
public final class PoolActions {
    private PoolActions() {}

    /** ChestBlock covers the normal, trapped and every copper chest (all oxidation / waxed variants). */
    public static boolean isContainerBlock(BlockState state) {
        return state.getBlock() instanceof ChestBlock || state.is(Blocks.BARREL) || state.is(Blocks.HOPPER)
                || state.is(Blocks.DROPPER) || state.is(Blocks.DISPENSER) || state.getBlock() instanceof ShulkerBoxBlock;
    }

    public static String addLookedAt(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return I18n.tr("act.lookLectern");
        }
        BlockPos pos = hit.getBlockPos();
        if (!level.getBlockState(pos).is(Blocks.LECTERN)) return I18n.tr("act.notLectern");
        Cell cell = Cell.derive(level, pos);
        if (cell == null) return I18n.tr("act.noSupport");
        return VprngClient.controller().addCell(mc, cell)
                ? I18n.tr("act.added", pos.toShortString(), VprngClient.controller().cells().size())
                : I18n.tr("act.already");
    }

    public static String scan(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return I18n.tr("act.joinWorld");
        BlockPos center = mc.player.blockPosition();
        int added = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-5, -5, -5), center.offset(5, 5, 5))) {
            if (!level.getBlockState(pos).is(Blocks.LECTERN)) continue;
            if (!mc.player.isWithinBlockInteractionRange(pos, 0.0)) continue;
            Cell cell = Cell.derive(level, pos.immutable());
            if (cell != null && VprngClient.controller().addCell(mc, cell)) added++;
        }
        return I18n.tr("act.scanned", added, VprngClient.controller().cells().size());
    }

    public static String setContainerLookedAt(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return I18n.tr("act.lookContainer");
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!isContainerBlock(state)) {
            return I18n.tr("act.notContainer", state.getBlock().getName().getString());
        }
        VprngClient.controller().setContainer(mc, pos);
        return I18n.tr("act.containerSet", pos.toShortString(), (int) mc.player.blockInteractionRange());
    }
}
