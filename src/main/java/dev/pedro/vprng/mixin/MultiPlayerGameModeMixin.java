package dev.pedro.vprng.mixin;

import dev.pedro.vprng.VprngClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's {@code Minecraft.continueAttack(false)} calls {@code stopDestroyBlock()} on EVERY tick while the
 * attack key is up, which resets the mining progress. A block that needs more than one tick to break would
 * therefore never finish when we drive {@code continueDestroyBlock} ourselves. While (and only while) the
 * controller is breaking one of its own pool lecterns, that reset is suppressed. We target the exact block
 * position, so crosshair/entity state is irrelevant and no other block can be mined by accident.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "stopDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void vprng$keepBreakingPoolLectern(CallbackInfo ci) {
        if (VprngClient.controller().suppressStopDestroy()) ci.cancel();
    }
}
