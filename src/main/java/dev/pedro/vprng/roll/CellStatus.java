package dev.pedro.vprng.roll;

import dev.pedro.vprng.i18n.I18n;
import dev.pedro.vprng.ui.PoolActions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Is a registered workstation cell (or the lectern container) usable right now? Drives the green/red highlight in
 * the world and the status chips in the GUI, so both always agree with what the reroller itself would do.
 */
public record CellStatus(BlockPos pos, boolean ok, String reason) {

    public static CellStatus ofCell(Minecraft mc, Cell cell) {
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        BlockPos pos = cell.pos();
        if (level == null || p == null) return new CellStatus(pos, false, I18n.tr("common.noWorld"));
        if (!level.hasChunkAt(pos)) return new CellStatus(pos, false, I18n.tr("cell.chunk"));
        BlockState state = level.getBlockState(pos);
        boolean lectern = state.is(Blocks.LECTERN);
        if (!lectern) {
            if (!state.isAir() && !state.canBeReplaced()) {
                return new CellStatus(pos, false, I18n.tr("cell.occupied", state.getBlock().getName().getString()));
            }
            if (level.getBlockState(cell.support()).isAir()) return new CellStatus(pos, false, I18n.tr("cell.noSupport"));
        }
        if (!p.isWithinBlockInteractionRange(pos, 0.0)) {
            double distance = Math.sqrt(net.minecraft.world.phys.Vec3.atCenterOf(pos).distanceToSqr(p.getEyePosition()));
            return new CellStatus(pos, false, I18n.tr("cell.unreachable", distance));
        }
        return new CellStatus(pos, true, lectern ? I18n.tr("cell.placed") : I18n.tr("cell.free"));
    }

    public static CellStatus ofContainer(Minecraft mc, BlockPos pos) {
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        if (level == null || p == null) return new CellStatus(pos, false, I18n.tr("common.noWorld"));
        if (!level.hasChunkAt(pos)) return new CellStatus(pos, false, I18n.tr("cell.chunk"));
        BlockState state = level.getBlockState(pos);
        if (!PoolActions.isContainerBlock(state)) {
            return new CellStatus(pos, false, state.isAir() ? I18n.tr("cell.noBlock")
                    : I18n.tr("cell.notContainer", state.getBlock().getName().getString()));
        }
        if (!p.isWithinBlockInteractionRange(pos, 0.0)) {
            double distance = Math.sqrt(net.minecraft.world.phys.Vec3.atCenterOf(pos).distanceToSqr(p.getEyePosition()));
            return new CellStatus(pos, false, I18n.tr("cell.unreachable", distance));
        }
        return new CellStatus(pos, true, state.getBlock().getName().getString());
    }
}
