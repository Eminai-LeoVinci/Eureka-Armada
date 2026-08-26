package org.valkyrienskies.eureka

import org.valkyrienskies.eureka.ship.EurekaShipControl
import org.valkyrienskies.mod.common.ValkyrienSkiesMod

object EurekaMod {
    const val MOD_ID = "vs_eureka"

    @JvmStatic
    fun init() {
        // Load config/vs_eureka.json first so any registration code that reads EurekaConfig
        // sees user-tuned values. VS 2.5 vs-core removed registerConfig; this is our
        // stand-in until ModConfigSpec is wired up.
        EurekaConfigLoader.loadOrCreate()

        EurekaBlocks.register()
        EurekaBlockEntities.register()
        EurekaItems.register()
        EurekaScreens.register()
        EurekaEntities.register()
        EurekaWeights.register()

        // VS 2.5+ vs-core requires every attachment class to be registered during mod init
        // before it can be set on a ship. Without this, assembling a ship crashes with
        // "attempted to set an attachment with unregistered class EurekaShipControl".
        // Legacy serializer matches this class's @JsonAutoDetect(fieldVisibility = ANY) design.
        ValkyrienSkiesMod.vsCore.registerAttachment(EurekaShipControl::class.java) {
            useLegacySerializer()
        }
    }

    @JvmStatic
    fun initClient() {
        EurekaClientScreens.register()
    }
}
