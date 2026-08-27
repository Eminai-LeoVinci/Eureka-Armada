package org.valkyrienskies.eureka

import org.valkyrienskies.eureka.armada.ArmadaShipControl
import org.valkyrienskies.eureka.crew.CrewProfession
import org.valkyrienskies.eureka.path.PathBinding
import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.eureka.shipwright.ShipwrightProfession
import org.valkyrienskies.mod.common.ValkyrienSkiesMod

object EurekaMod {
    const val MOD_ID = "vs_eureka"

    @JvmStatic
    fun init() {
        // Load config/vs_eureka_armada.json first so any registration code that reads EurekaConfig
        // sees user-tuned values. VS 2.5 vs-core removed registerConfig; this is our
        // stand-in until ModConfigSpec is wired up.
        EurekaConfigLoader.loadOrCreate()
        // The loot tables ride their own file (they are lists a hundred entries long); loaded here,
        // before item registration, because stack sizes bake at registration and read config.
        EurekaLootLoader.loadOrCreate()

        EurekaBlocks.register()
        EurekaBlockEntities.register()
        EurekaItems.register()
        EurekaScreens.register()
        EurekaEntities.register()
        EurekaRecipes.register()
        EurekaWeights.register()

        // The Crewman profession. Pure vanilla registry work, so it belongs here rather than in the loader
        // layer; the POI and the trades need Fabric API and are registered from EurekaModFabric instead.
        CrewProfession.registerProfession()

        // The Shipwright. Same deal as the Crewman above: plain registry work here, POI in the loader module.
        ShipwrightProfession.registerProfession()

        // VS 2.5+ vs-core requires every attachment class to be registered during mod init
        // before it can be set on a ship. Without this, assembling a ship crashes with
        // "attempted to set an attachment with unregistered class EurekaShipControl".
        // Legacy serializer matches this class's @JsonAutoDetect(fieldVisibility = ANY) design.
        ValkyrienSkiesMod.vsCore.registerAttachment(EurekaShipControl::class.java) {
            useLegacySerializer()
        }

        // Armada parent/child bind state, PERSISTED (Jackson) so a bound child re-joins its parent after a
        // world reload. Only the child-side bind (parent id + offset) is serialized; the runtime follow
        // provider that positions each child is re-installed every server tick by ArmadaBindings.reconcile.
        // Legacy serializer matches this class's @JsonAutoDetect(fieldVisibility = ANY) design.
        ValkyrienSkiesMod.vsCore.registerAttachment(ArmadaShipControl::class.java) {
            useLegacySerializer()
        }

        // Which recorded route a ship is flying, PERSISTED so following survives a world reload. Same division
        // of labour as the armada bind above: the relationship is saved here, and the runtime machinery that
        // acts on it (a PathFollower, rather than a physics weld) is rebuilt by ShipPaths.tick once the ship and
        // its route store are both loaded.
        ValkyrienSkiesMod.vsCore.registerAttachment(PathBinding::class.java) {
            useLegacySerializer()
        }
    }

    @JvmStatic
    fun initClient() {
        EurekaClientScreens.register()
    }
}
