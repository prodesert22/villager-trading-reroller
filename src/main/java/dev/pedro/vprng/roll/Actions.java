package dev.pedro.vprng.roll;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Thin wrappers over the vanilla client calls a real player would trigger. Each one uses the same entry point
 * as the matching mouse click ({@code useItemOn}, {@code startDestroyBlock}, {@code interact}), so servers and
 * VTP's own mixins see exactly what they see for manual play.
 */
public final class Actions {
    private Actions() {}

    public static void lookAt(LocalPlayer player, Vec3 target) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
    }

    public static void swing(LocalPlayer player, InteractionHand hand) {
        player.swing(hand, SwingAnimation.DEFAULT, true);
    }

    /** Right-click the support block's face to put a lectern at {@code cell.pos()}. */
    public static InteractionResult place(Minecraft mc, Cell cell, InteractionHand hand) {
        LocalPlayer player = mc.player;
        InteractionResult result = mc.gameMode.useItemOn(player, hand, cell.placeHit());
        if (result.consumesAction()) swing(player, hand);
        return result;
    }

    public static void startBreak(Minecraft mc, BlockPos pos) {
        mc.gameMode.startDestroyBlock(pos, breakFace(mc.player, pos));
        swing(mc.player, InteractionHand.MAIN_HAND);
    }

    public static void continueBreak(Minecraft mc, BlockPos pos) {
        mc.gameMode.continueDestroyBlock(pos, breakFace(mc.player, pos));
        swing(mc.player, InteractionHand.MAIN_HAND);
    }

    /** Right-click a villager: this is what makes the server generate (and send) its trades. */
    public static InteractionResult open(Minecraft mc, Villager villager) {
        EntityHitResult hit = new EntityHitResult(villager, center(villager));
        return mc.gameMode.interact(mc.player, villager, hit, InteractionHand.MAIN_HAND);
    }

    public static Vec3 center(Villager villager) {
        return villager.position().add(0.0, villager.getBbHeight() * 0.5, 0.0);
    }

    /** The side of the block that faces the player. */
    public static Direction breakFace(LocalPlayer player, BlockPos pos) {
        return Direction.getApproximateNearest(player.getEyePosition().subtract(Vec3.atCenterOf(pos)));
    }
}
