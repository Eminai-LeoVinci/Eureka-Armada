package org.valkyrienskies.eureka.fabric.mixin.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.valkyrienskies.eureka.fabric.client.CrewPlated;

/** Carries "this is a crew plate" from the extract phase, where the entity is known, to the submit phase. */
@Mixin(EntityRenderState.class)
public class MixinEntityRenderStateCrewPlate implements CrewPlated {

    @Unique
    private boolean vs_eureka$crewPlate;

    @Override
    public boolean vs_eureka$isCrewPlate() {
        return this.vs_eureka$crewPlate;
    }

    @Override
    public void vs_eureka$setCrewPlate(final boolean crewPlate) {
        this.vs_eureka$crewPlate = crewPlate;
    }
}
