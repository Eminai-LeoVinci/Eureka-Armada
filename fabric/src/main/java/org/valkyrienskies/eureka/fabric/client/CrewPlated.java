package org.valkyrienskies.eureka.fabric.client;

/**
 * A duck on {@code EntityRenderState} recording that this frame's name tag is a crew plate.
 *
 * Extraction and submission are separate phases of a frame: the entity is in scope for the first and long gone
 * by the second. The flag has to ride the render state across that gap, because the render state is the only
 * thing that survives it.
 */
public interface CrewPlated {

    boolean vs_eureka$isCrewPlate();

    void vs_eureka$setCrewPlate(boolean crewPlate);
}
