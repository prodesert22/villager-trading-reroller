package dev.pedro.vprng.mixin;

import dev.pedro.vprng.VprngClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Delivers the trade offers the server sends after we open a villager.
 *
 * <p>{@code priority = 1100} makes this RETURN callback run after Villager Trading Predictor's own
 * RETURN callback on the same method (default priority 1000), so VTP has already recorded the observation
 * and advanced its tracked sequence position when we read it.</p>
 */
@Mixin(value = ClientPacketListener.class, priority = 1100)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleMerchantOffers", at = @At("RETURN"))
    private void vprng$onMerchantOffers(ClientboundMerchantOffersPacket packet, CallbackInfo ci) {
        VprngClient.controller().onOffers(Minecraft.getInstance(), packet);
    }
}
