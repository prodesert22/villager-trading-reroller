package dev.pedro.vprng.roll;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One workstation slot of the pool: where the lectern goes and which neighbouring block face we click to place it.
 * {@code support = pos.relative(face.getOpposite())}; clicking {@code face} of the support places the block at {@code pos}.
 */
public record Cell(BlockPos pos, Direction face) {

    public BlockPos support() {
        return pos.relative(face.getOpposite());
    }

    /** The click used to place the lectern (centre of the support's {@code face} side). */
    public BlockHitResult placeHit() {
        BlockPos support = support();
        Vec3 location = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        return new BlockHitResult(location, face, support, false);
    }

    /**
     * Picks a face whose support block exists (floor first, then walls, ceiling last), or {@code null} if the
     * lectern is floating with nothing around it to click.
     */
    public static Cell derive(Level level, BlockPos pos) {
        Direction[] order = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};
        for (Direction face : order) {
            BlockPos support = pos.relative(face.getOpposite());
            var state = level.getBlockState(support);
            if (!state.isAir() && state.getFluidState().isEmpty()) {
                return new Cell(pos.immutable(), face);
            }
        }
        return null;
    }
}
