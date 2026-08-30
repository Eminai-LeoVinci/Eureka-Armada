package org.valkyrienskies.eureka.blockentity.renderer

import java.lang.reflect.Method

/**
 * Whether Iris is drawing the SHADOW MAP right now, rather than the view the player actually sees.
 *
 * Iris renders the world twice: once from the sun for the shadow map and once from the camera. Block
 * entities are drawn in BOTH -- and a cannon's barrel is a block entity, so a sixty-gun ship pays for
 * every barrel twice a frame. Profiling a shader session put block entities at 52% of the whole render
 * thread, the cannon renderer alone at 44%, and the shadow half of that at roughly 17-22%. That is why
 * shaders plus a full crew halved the frame rate while either one alone was fine: the crew were ordinary,
 * the budget was already spent.
 *
 * What the shadow pass loses by skipping the barrel is small and specific. Only the BARREL is a block
 * entity; the carriage under it is ordinary chunk geometry and still casts its shadow normally. So the
 * gun keeps a shadow, it simply stops casting a separate one for the muzzle.
 *
 * ## Why reflection
 * Armada does not compile against Iris and must run identically with it absent. `ShadowRenderingState` is
 * Iris's own published answer to this question. A plain [Method] is used rather than a MethodHandle
 * because `invokeExact` is signature-polymorphic and Kotlin does not reliably reproduce that -- the
 * handle version compiles and then throws at runtime. Resolution happens once; every failure (Iris
 * missing, class renamed, method gone) latches to null and answers "not the shadow pass" from then on,
 * which is exactly what a world without Iris should say.
 */
object IrisShadowPass {

    private var resolved = false
    private var probe: Method? = null

    private fun probe(): Method? {
        if (!resolved) {
            resolved = true
            probe = try {
                Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState")
                    .getMethod("areShadowsCurrentlyBeingRendered")
            } catch (ignored: Throwable) {
                null
            }
        }
        return probe
    }

    /** True only while Iris is filling the shadow map. False whenever Iris is absent or unsure. */
    fun active(): Boolean {
        val m = probe() ?: return false
        return try {
            m.invoke(null) as? Boolean ?: false
        } catch (ignored: Throwable) {
            false
        }
    }
}
