package org.valkyrienskies.eureka.fabric.client;

import net.minecraft.world.phys.Vec3;

/**
 * Reads the camera's world position, which 1.21.11 offers no accessor for -- {@code Camera} keeps {@code position}
 * as a private field and the {@code getPosition()} getter that older versions had is gone. Implemented by
 * {@link org.valkyrienskies.eureka.fabric.mixin.client.MixinCameraSubAir}, which already shadows the field.
 */
public interface CameraPositionDuck {
    Vec3 vs_eureka$cameraPosition();
}
