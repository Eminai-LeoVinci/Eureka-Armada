package org.valkyrienskies.eureka.fabric.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.eureka.crew.CrewLedger;
import org.valkyrienskies.eureka.crew.GunStations;

/**
 * A crew member who dies leaves their berth.
 *
 * <p>The rest of the crew code has always been written as though this already happened -- the broadside
 * muster says outright that "a stationed one whose villager has died has already had the berth struck and
 * the binding with it" -- but nothing actually struck it. A berth outlived its villager, so the articles
 * went on counting heads that no longer existed: a crew of sixty read as sixty-four after four of them were
 * killed and replaced, and the wheel went on naming a crew with nobody left in it.
 *
 * <p>DEATH, not removal, is deliberately the signal. Eureka takes crew OUT of the world constantly and none
 * of it is dying: mustering, bottling, disassembly and salvage all {@code discard} their villagers on
 * purpose (each of those call sites says "nobody died" in as many words), and every one of them means to
 * keep the berth so the same crew member comes back on the other side. {@code discard} never routes through
 * here, so those paths are untouched. Vanilla's own conversions -- a villager zombified, or cured back --
 * also use {@code convertTo} rather than dying, and are already handled by the ledger's rekey.
 *
 * <p>What DOES route through here is every real death: drowned, shot, blown up with the hull, and
 * {@code /kill}, which is the one that found this. A player clearing out crew with a command should not have
 * to also repair the articles by hand.
 *
 * <p>The seat goes first. {@link GunStations#unseat} kills the mount a gunner was riding, which would
 * otherwise be left holding a claim for a villager the ledger no longer knows about; the orphan sweep would
 * eventually take it, but a corpse should not leave a chair behind for a second.
 */
@Mixin(Villager.class)
public class MixinVillagerCrewDeath {

    @Inject(method = "die", at = @At("HEAD"))
    private void vs_eureka$strikeBerthOnDeath(final DamageSource source, final CallbackInfo ci) {
        final Villager self = (Villager) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        final MinecraftServer server = self.level().getServer();
        if (server == null) {
            return;
        }
        final CrewLedger ledger = CrewLedger.Companion.get(server);
        if (ledger.crewOf(self.getUUID()) == null) {
            return;
        }
        if (self.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            GunStations.INSTANCE.unseat(serverLevel, self.getUUID());
        }
        ledger.payOff(self.getUUID());
    }
}
