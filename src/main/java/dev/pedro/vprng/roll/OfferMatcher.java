package dev.pedro.vprng.roll;

import dev.pedro.vprng.i18n.I18n;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.manaz.vtp.prediction.EnchantmentTarget;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Decides whether a villager's real (server-sent) offers contain a wanted enchanted book.
 * Uses VTP's own {@link EnchantmentTarget#matches(Holder, int, int)} and the same base price VTP uses
 * ({@code getBaseCostA().getCount()}, i.e. before discounts), so "wanted" means exactly what VTP shows.
 */
public final class OfferMatcher {
    public record Match(EnchantmentTarget target, Holder<Enchantment> enchantment, int level, int price) {
        public String describe() {
            return I18n.tr("match.describe", Enchantment.getFullname(enchantment, level).getString(), price);
        }
    }

    private OfferMatcher() {}

    public static Optional<Match> find(MerchantOffers offers, List<EnchantmentTarget> targets) {
        for (MerchantOffer offer : offers) {
            ItemEnchantments books = books(offer);
            if (books.isEmpty()) continue;
            int price = offer.getBaseCostA().getCount();
            for (Holder<Enchantment> enchantment : books.keySet()) {
                int level = books.getLevel(enchantment);
                for (EnchantmentTarget target : targets) {
                    if (target.matches(enchantment, level, price)) {
                        return Optional.of(new Match(target, enchantment, level, price));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Human readable list of every enchanted book on offer, for logs and the HUD ("last seen"). */
    public static String summarize(MerchantOffers offers) {
        List<String> parts = new ArrayList<>();
        for (MerchantOffer offer : offers) {
            ItemEnchantments books = books(offer);
            int price = offer.getBaseCostA().getCount();
            for (Holder<Enchantment> enchantment : books.keySet()) {
                parts.add(Enchantment.getFullname(enchantment, books.getLevel(enchantment)).getString()
                        + " " + price + "e");
            }
        }
        return parts.isEmpty() ? I18n.tr("offer.noBook") : String.join(", ", parts);
    }

    private static ItemEnchantments books(MerchantOffer offer) {
        return offer.getResult().getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
    }
}
