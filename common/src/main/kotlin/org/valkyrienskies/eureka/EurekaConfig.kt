package org.valkyrienskies.eureka

import com.github.imifou.jsonschema.module.addon.annotation.JsonSchema

object EurekaConfig {
    @JvmField
    val CLIENT = Client()

    // Two control presets. ADVANCED holds the current (overhauled) defaults -- the engine-independent turn
    // law, the 3-set engage-to-latch cruise, and the retuned engine/elevation values. VANILLA restores the
    // pre-overhaul 833d445 feel: it COPIES ADVANCED and overrides only the mode-affected fields whose default
    // changed. A per-ship `vanillaControls` flag on EurekaShipControl selects which preset that ship reads its
    // mode-affected physics off of (see EurekaShipControl.cfg and EurekaShipControl.engineCfg, the latter read
    // by EngineBlockEntity for per-ship engine force/heat).
    //
    // maxShipBlocks, blockBlacklist and terrainPocketMaxBlocks stay GLOBAL: all are consumed at ASSEMBLY
    // time (ShipHelmBlockEntity), where no ship -- and therefore no per-ship mode -- exists yet, so they
    // CANNOT be per-ship. Editing the "serverVanilla" copies of them does nothing. They are
    // deliberately NOT overridden here; the only knobs that matter live on EurekaConfig.SERVER (=== ADVANCED).
    @JvmField
    val ADVANCED = Server()

    @JvmField
    val VANILLA = Server().apply {
        enginePowerLinear = 500000f
        engineHeatGain = 0.03f
        engineBoost = 0.2
        engineBoostOffset = 2.5
        maxReverseSpeedFromEngines = 24.0 // real m/s; was 8.0 back when the physics tripled it
        baseImpulseElevationRate = 2.0
        baseImpulseDescendRate = 4.0
        // The ADVANCED turn defaults were retuned to 0.75 / 6.0; pin the original 833d445 values here so
        // Vanilla mode keeps the faithful pre-overhaul (engine-dependent) turn feel.
        turnSpeed = 3.0
        turnAcceleration = 10.0
    }

    // SERVER is an ALIAS for the ADVANCED preset. The ~40 non-mode-affected reads (and EngineBlockEntity /
    // ShipHelmBlockEntity, which read engine-heat / ship-size / blacklist globally) all use SERVER and keep
    // reading the live ADVANCED preset -- which is what the config file's "server" key edits. Global toggles
    // (water-altitude-hold, debugCruiseCancel) also live on SERVER === ADVANCED.
    @JvmField
    val SERVER = ADVANCED

    class Client {
        @JsonSchema(description = "Master toggle for the piloted-ship HUD. When off, the Speed/Altitude/Heading readouts are all hidden (and greyed out in the helm menu).")
        var displayHud = true

        @JsonSchema(description = "Show the piloted ship's speed as small text at the top-center of the screen.")
        var displaySpeed = false

        @JsonSchema(description = "Show the piloted ship's altitude (Y) at the top-center of the screen.")
        var displayAltitude = false

        @JsonSchema(description = "Show the piloted ship's compass heading at the top-center of the screen.")
        var displayHeading = false
    }

    class Server {

        @JsonSchema(description = "Movement power per engine when heated fully")
        var enginePowerLinear: Float = 100000f

        @JsonSchema(description = "Movement power per engine with minimal heat")
        var enginePowerLinearMin: Float = 10000f

        @JsonSchema(description = "Turning power per engine when heated fully")
        var enginePowerAngular = 1.0f

        @JsonSchema(description = "Turning power per engine when minimal heat")
        var enginePowerAngularMin = 0.0f

        @JsonSchema(description = "The amount of heat a engine loses per tick")
        var engineHeatLoss = 0.01f

        @JsonSchema(description = "The amount of heat a gain per tick (when burning)")
        var engineHeatGain = 0.09f

        @JsonSchema(description = "Increases heat gained at low heat level, and increased heat decreases when at high heat and not consuming fuel")
        var engineHeatChangeExponent = 0.1f

        @JsonSchema(description = "Pause fuel consumption and power when block is powered")
        var engineRedstoneBehaviorPause = false

        @JsonSchema(description = "Number of Balloons a single engine can power. 0 disables the feature")
        var maxBalloonsPerEngine = 0

        @JsonSchema(description = "Avoids consuming fuel when heat is 100%")
        var engineFuelSaving = false

        @JsonSchema(description = "Increasing this value will result in more items being able to converted to fuel")
        var engineMinCapacity = 2000

        @JsonSchema(description = "Fuel burn time multiplier")
        var engineFuelMultiplier = 2f

        @JsonSchema(description = "Extra engine power for when having multiple engines per engine")
        var engineBoost = 4.0

        @JsonSchema(description = "At what amount of engines the boost will start taking effect")
        var engineBoostOffset = 5.0

        @JsonSchema(description = "The final linear boost will be raised to the power of 2, and the result of the delta is multiple by this value")
        var engineBoostExponentialPower = 0.000001

        // These are real m/s now. They used to be tripled by baseSpeed on the way into the physics, so the
        // old 24.0/12.0 delivered ~72/~36; the defaults are raised to match, leaving ship speeds unchanged.
        @JsonSchema(description = "Max speed in m/s of a ship with engines (actual max speed varies with engines and mass.)")
        var maxSpeedFromEngines = 70.0

        @JsonSchema(description = "Max reverse speed in m/s of a ship with engines")
        var maxReverseSpeedFromEngines = 36.0

        @JsonSchema(description = "The speed at which the ship stabilizes")
        var stabilizationSpeed = 10.0

        @JsonSchema(description = "The amount extra that each floater will make the ship float, per kg mass")
        var floaterBuoyantFactorPerKg = 50_000.0

        @JsonSchema(description = "The maximum amount extra each floater will multiply the buoyant force by, irrespective of mass")
        var maxFloaterBuoyantFactor = 1.0

        @JsonSchema(description = "how much the mass decreases the speed.")
        var speedMassScale = 1.0

        // The velocity any ship at least can move at.
        @JsonSchema(description = "The speed a ship with no engines can move at")
        var baseSpeed = 3.0

        @JsonSchema(description = "Forward/backward thrust multiplier for a ship grounded on land, to overcome ground friction. 1 = no assist")
        var landThrustAssist = 5.33

        @JsonSchema(description = "Forward/backward thrust multiplier for a ship travelling on water. 1 = no assist")
        var waterThrustAssist = 8.0

        // Sensitivity of the up/down impulse buttons.
        // TODO maybe should be moved to VS2 client-side config?
        @JsonSchema(description = "Vertical sensitivity when ascending")
        var baseImpulseElevationRate = 5.0

        @JsonSchema(description = "Vertical sensitivity when descending")
        var baseImpulseDescendRate = 10.0

        @JsonSchema(description = "The max elevation speed boost gained by having extra extra balloons")
        var balloonElevationMaxSpeed = 5.5

        // Higher numbers make the ship accelerate to max speed faster
        @JsonSchema(description = "Ascend and descend acceleration")
        var elevationSnappiness = 1.0

        // Allow Eureka controlled ships to be affected by fluid drag
        @JsonSchema(description = "Allow Eureka controlled ships to be affected by fluid drag")
        var doFluidDrag = false

        // Do i need to explain? the mass 1 baloon gets to float
        @JsonSchema(description = "Amount of mass in kg a balloon can lift")
        var massPerBalloon = 5000.0

        @JsonSchema(
            description = "Multiplier on balloon FLIGHT LIFT -- the anti-gravity up-force balloons apply " +
                "in air and water alike. This is NOT water buoyancy. 1 = normal lift, 0 = balloons provide " +
                "no lift (debug lever for ships hovering above the waterline). Staying afloat on water is a " +
                "separate system: see floaterBuoyantFactorPerKg / maxFloaterBuoyantFactor."
        )
        var balloonLiftMultiplier = 1.0

        @JsonSchema(
            description = "Water altitude-hold: a HYBRID ship (has both floaters AND balloons) pins its " +
                "current Y the moment its keel touches water, so it sails on the surface instead of balloon " +
                "lift floating it back into the air. Hands off the helm (or cruise) = hold; press descend/" +
                "ascend to choose a new depth, which re-latches when you let go; rising clear of the water " +
                "returns to normal in-air hover. Vertical-only, so sailing speed is unaffected."
        )
        var enableWaterAltitudeHold = true

        @JsonSchema(
            description = "Stiffness of the water altitude-hold spring. Higher pins the Y tighter and faster " +
                "but can feel abrupt; lower is softer and may sag slightly. Critically damped, so it never " +
                "oscillates. Default 9.0."
        )
        var waterAltitudeHoldStiffness = 9.0

        @JsonSchema(
            description = "How submerged the ship must be (fraction 0..1) for the water altitude-hold to " +
                "ENGAGE. Once engaged it stays until the hull fully clears the water. Small = engages as soon " +
                "as the keel touches. Default 0.05."
        )
        var waterAltitudeHoldMinOverlap = 0.05

        // The amount of speed that the ship can move at when the left/right impulse button is held down.
        @JsonSchema(
            description = "Base turn rate -- the turning speed for taps and short holds. Holding ramps the ship " +
                "up to this rate over turnAccelDelay seconds, so a TAP only reaches a small fraction of it " +
                "(fine, pixel-level steering at low values). Engines no longer affect turning, so this and " +
                "turnAcceleration fully control it. Low = slow, gentle turns; high = fast base turns."
        )
        var turnSpeed = 0.5

        @JsonSchema(
            description = "Extra turn sharpness that engages ONLY after holding a turn longer than " +
                "turnAccelDelay -- then the turn rate climbs beyond the turnSpeed base, sharper the longer you " +
                "hold. Tapping never triggers it. 0 = turns stay at the turnSpeed base rate with no acceleration."
        )
        var turnAcceleration = 8.0

        @JsonSchema(
            description = "How long (seconds) a turn key must be held before turnAcceleration kicks in. Below " +
                "this, only turnSpeed applies -- so tap-tap-tap gives small, fine orbit adjustments while a " +
                "sustained hold ramps the turn sharper. Default 0.6."
        )
        var turnAccelDelay = 0.6

        @JsonSchema(
            description = "Seconds you must hold the OPPOSITE turn (A/D) to cancel a locked orbit while cruising " +
                "(leaving horizontal/vertical cruise running). Taps/shorter holds only ADD influence and never " +
                "cancel. Canceling the last active set turns cruise off. Default 3.0."
        )
        var turnCancelHold = 3.0

        @JsonSchema(
            description = "Seconds you must hold the OPPOSITE forward/back input (W/S) to cancel the horizontal " +
                "cruise set while cruising (leaving turn/vertical running). Taps only ADD speed influence. " +
                "Canceling the last active set turns cruise off. Default 3.0."
        )
        var horizontalCancelHold = 3.0

        @JsonSchema(
            description = "Seconds you must hold the OPPOSITE ascend/descend input (Space/V) to cancel the " +
                "vertical cruise set while cruising (leaving horizontal/turn running). Taps only ADD climb " +
                "influence. Canceling the last active set turns cruise off. Default 3.0."
        )
        var verticalCancelHold = 3.0

        @JsonSchema(
            description = "Lock-in turn cruise: while CRUISING, holding a turn key spins the ship up and the " +
                "achieved turn RATE is latched when you release -- so the ship holds a constant-radius circle " +
                "hands-off instead of straightening. A brief tap = a gentle wide orbit; a longer hold = a " +
                "sharper orbit (up to turnSpeed). Steer the opposite way to widen or straighten out. false = " +
                "turning while cruising just steers live and brakes back to straight on release (legacy)."
        )
        var enableTurnCruise = true

        @JsonSchema(
            description = "The maximum distance from center of mass to one end of the ship considered by " +
                "the turn speed. At it's default of 16, it ensures that really large ships will turn at the same " +
                "speed as a ship with a center of mass only 16 blocks away from the farthest point in the ship. " +
                "That way, large ships do not turn painfully slowly"
        )
        var maxSizeForTurnSpeedPenalty = 16.0

        // The strength used when trying to level the ship
        @JsonSchema(description = "How much torque a ship will apply to try and keep level")
        var stabilizationTorqueConstant = 15.0

        // Max anti-velocity used when trying to stop the ship
        @JsonSchema(description = "How fast a ship will stop. 1 = fast stop, 0 = slow stop")
        var linearStabilizeMaxAntiVelocity = 1.0

        // Instability scaled with mass and squared speed
        @JsonSchema(description = "Stronger stabilization with higher mass, less at higher speeds.")
        var scaledInstability = 70.0

        // Unscaled linear instability cased by speed
        @JsonSchema(description = "Less stabilization at higher speed.")
        var unscaledInstability = 0.1

        @JsonSchema(description = "How fast a ship will stop and accelerate.")
        var linearMassScaling = 0.0002

        // Must be positive. higher value will case slower acceleration and deceleration.
        @JsonSchema(description = "Base mass for linear acceleration in Kg.")
        var linearBaseMass = 50.0

        //when value is same as linearMaxMass, actual value will be 1/3. actual value will be close to linearMaxMass when 5 times over
        @JsonSchema(description = "Max smoothing value, will smooth out before reaching max value.")
        var linearMaxMass = 10000.0

        @JsonSchema(description = "Max unscaled speed in m/s without engines.")
        var linearCasualSpeed = 3.0

        // Anti-velocity mass relevance when stopping the ship
        // Max 10.0 (means no mass irrelevance)
        @JsonSchema(description = "How much inertia affects Eureka ships. Max 10 = full inertia")
        var antiVelocityMassRelevance = 0.8

        // Chance that if side will pop, its this chance per side
        @JsonSchema(description = "Chance for popped balloons to pop adjacent balloons, per side")
        var popSideBalloonChance = 0.3

        @JsonSchema(description = "Whether the ship helm assembles diagonally connected blocks or not")
        var diagonals = true

        @JsonSchema(description = "Weight of ballast when lowest redstone power")
        var ballastWeight: Double = 10000.0

        @JsonSchema(description = "Weight of ballast when highest redstone power")
        var ballastNoWeight: Double = 1000.0

        @JsonSchema(description = "Whether or not disassembly is permitted")
        var allowDisassembly = true

        @JsonSchema(description = "Maximum number of blocks allowed in a ship. Set to 0 for no limit")
        var maxShipBlocks = 50000

        @JsonSchema(description = "Blocks that never assemble, on top of the vs_eureka:assemble_blacklist block tag (fluids, portals, world-guard blocks). Absolute -- nothing overrides an entry here.")
        var blockBlacklist : Set<String> = setOf(
            "minecraft:water", "minecraft:lava", "minecraft:fire", "minecraft:bedrock"
        )

        @JsonSchema(
            description = "How large a connected patch of natural-terrain-type blocks (the " +
                "vs_eureka:assemble_terrain block tag: stone, dirt, sand, ice, vegetation...) can be and still " +
                "assemble as part of the ship. Minecraft records nothing about who placed a block, so player " +
                "builds are told apart from the landscape by extent: a grass deck is a bounded pocket, a beach " +
                "goes on past any budget. A patch that stays within this many blocks sails with the ship; a " +
                "patch that exceeds it is the world and stays. The trade-off runs both ways -- a natural islet " +
                "smaller than this reads as a build and will be taken if the hull touches it, and a deck that " +
                "physically touches the shore reads as the world and stays behind. 0 disables terrain assembly " +
                "entirely (the old behavior: these blocks never assemble)."
        )
        var terrainPocketMaxBlocks = 4096

        @JsonSchema(description = "Dev: action-bar a message each time a per-set cruise HOLD-cancel fires (Horizontal/Vertical/Turn). Read globally off EurekaConfig.SERVER; toggle in-game with /vs cruise-cancel-debug <bool>.")
        var debugCruiseCancel = false

        // region Eureka Assembler
        // The Eureka Assembler (enabled per-player with /vs eureka-assembler <floater|balloon> <true|false>) auto-fills
        // a ship at assembly time by REPLACING existing hull blocks with floaters/balloons -- never adding new volume --
        // and only if the player has the required floater/balloon items in their inventory. These two ORDERED lists are
        // the replacement whitelists (anything not listed is never replaced). Order = priority: the assembler exhausts
        // each block id in list order (bottom-up within a type) before moving to the next -- all oak planks, then spruce
        // planks, ... then logs. Two wooden slabs forming a full block are a DIFFERENT block id (*_slab) and are never
        // matched. Consumed at ASSEMBLY time (ShipHelmBlockEntity), so like maxShipBlocks/blockBlacklist these are read
        // GLOBALLY off EurekaConfig.SERVER, never per-ship.

        @JsonSchema(
            description = "Eureka Assembler: the target ASCEND speed in m/s the 'balloon' auto-fill sizes for. " +
                "Sizing balloons for neutral hover (= 0 here, the old behavior) lets a heavy ship hold itself up but " +
                "never climb, because the ship's usable climb force is only the balloon lift SURPLUS over its weight. " +
                "This adds mass-proportional lift headroom so every assembled ship -- light hull or heavy -- can " +
                "actually ascend at about this rate. Higher = climbs faster but converts more hull blocks to balloons; " +
                "0 = neutral hover only (may not climb). /vs get-ship-weight uses the same target, so its reported " +
                "'needed' balloon count matches what the assembler places."
        )
        var assemblerBalloonAscendRate = 2.0

        @JsonSchema(
            description = "Eureka Assembler: ordered block ids the 'floater' auto-fill replaces, in priority order " +
                "(planks first -- oak..pale_oak -- then logs/stripped/bark). Add modded wood ids here to make them " +
                "convertible. Wooden slabs are intentionally excluded (a double slab is not a plank)."
        )
        var assemblerFloaterReplaceWhitelist: List<String> = listOf(
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:jungle_planks",
            "minecraft:acacia_planks", "minecraft:dark_oak_planks", "minecraft:crimson_planks", "minecraft:warped_planks",
            "minecraft:mangrove_planks", "minecraft:cherry_planks", "minecraft:bamboo_planks", "minecraft:pale_oak_planks",
            "minecraft:oak_log", "minecraft:stripped_oak_log", "minecraft:oak_wood", "minecraft:stripped_oak_wood",
            "minecraft:spruce_log", "minecraft:stripped_spruce_log", "minecraft:spruce_wood", "minecraft:stripped_spruce_wood",
            "minecraft:birch_log", "minecraft:stripped_birch_log", "minecraft:birch_wood", "minecraft:stripped_birch_wood",
            "minecraft:jungle_log", "minecraft:stripped_jungle_log", "minecraft:jungle_wood", "minecraft:stripped_jungle_wood",
            "minecraft:acacia_log", "minecraft:stripped_acacia_log", "minecraft:acacia_wood", "minecraft:stripped_acacia_wood",
            "minecraft:dark_oak_log", "minecraft:stripped_dark_oak_log", "minecraft:dark_oak_wood", "minecraft:stripped_dark_oak_wood",
            "minecraft:crimson_stem", "minecraft:stripped_crimson_stem", "minecraft:crimson_hyphae", "minecraft:stripped_crimson_hyphae",
            "minecraft:warped_stem", "minecraft:stripped_warped_stem", "minecraft:warped_hyphae", "minecraft:stripped_warped_hyphae",
            "minecraft:mangrove_log", "minecraft:stripped_mangrove_log", "minecraft:mangrove_wood", "minecraft:stripped_mangrove_wood",
            "minecraft:cherry_log", "minecraft:stripped_cherry_log", "minecraft:cherry_wood", "minecraft:stripped_cherry_wood",
            "minecraft:bamboo_block", "minecraft:stripped_bamboo_block",
            "minecraft:pale_oak_log", "minecraft:stripped_pale_oak_log", "minecraft:pale_oak_wood", "minecraft:stripped_pale_oak_wood"
        )

        @JsonSchema(
            description = "Eureka Assembler: ordered block ids the 'balloon' auto-fill replaces, in priority order. " +
                "Defaults to every wool color (first), then every concrete color. Add block ids here to expand " +
                "what balloons may be built from."
        )
        var assemblerBalloonReplaceWhitelist: List<String> = listOf(
            "minecraft:white_wool", "minecraft:orange_wool", "minecraft:magenta_wool", "minecraft:light_blue_wool",
            "minecraft:yellow_wool", "minecraft:lime_wool", "minecraft:pink_wool", "minecraft:gray_wool",
            "minecraft:light_gray_wool", "minecraft:cyan_wool", "minecraft:purple_wool", "minecraft:blue_wool",
            "minecraft:brown_wool", "minecraft:green_wool", "minecraft:red_wool", "minecraft:black_wool",
            "minecraft:white_concrete", "minecraft:orange_concrete", "minecraft:magenta_concrete",
            "minecraft:light_blue_concrete", "minecraft:yellow_concrete", "minecraft:lime_concrete",
            "minecraft:pink_concrete", "minecraft:gray_concrete", "minecraft:light_gray_concrete",
            "minecraft:cyan_concrete", "minecraft:purple_concrete", "minecraft:blue_concrete",
            "minecraft:brown_concrete", "minecraft:green_concrete", "minecraft:red_concrete", "minecraft:black_concrete"
        )
        // endregion

        // region Armada
        @JsonSchema(
            description = "Armada: collide the whole formation with the world. A bound child's pose is written from " +
                "its parent every tick, so the physics engine cannot collide it -- with this on, the parent refuses " +
                "any motion that would put a child inside terrain, and the armada stops, slides and grinds as one " +
                "hull. Turn it off and children fly through the landscape."
        )
        var armadaChildTerrainCollision = true

        @JsonSchema(
            description = "Armada: how many sample points may stand in for one child's hull. Higher = finer contact " +
                "(the armada stops closer to what it hits, and thin spires are less likely to be missed) at a " +
                "proportional per-tick cost. Ships bigger than the budget are sampled more coarsely rather than " +
                "costing more, so this is a hard ceiling on the work per child."
        )
        var armadaCollisionSampleBudget = 4096

        @JsonSchema(
            description = "Armada: fraction of the formation's ALONG-the-surface speed scrubbed off per tick while " +
                "it is in contact with the world. 0 = frictionless glide, 1 = stops dead the moment it touches " +
                "anything. Speed heading INTO a surface is always removed regardless of this."
        )
        var armadaCollisionSlideFriction = 0.0

        @JsonSchema(
            description = "Armada: peak speed (m/s) of the push that eases the formation back out of anything it has " +
                "ended up INSIDE. No sampled hull is exact, so held against a surface long enough some part of it " +
                "eventually ends up a block or two in; this frees it. Merely touching a surface never triggers it. " +
                "0 disables the push entirely."
        )
        var armadaCollisionRepelSpeed = 3.0

        @JsonSchema(
            description = "Armada: ticks the repel takes to build from nothing to its full speed, so it reads as a " +
                "magnet easing the formation out rather than a shove. It cuts out the instant nothing is inside any " +
                "more -- it never fades out. 20 = one second."
        )
        var armadaCollisionRepelRampTicks = 20

        @JsonSchema(
            description = "Armada: how many ticks ahead of itself the formation is tested. 1 stops as late as " +
                "possible; higher stops further out and makes it harder for a fast armada to step over a thin wall " +
                "between two ticks."
        )
        var armadaCollisionLookaheadTicks = 2
        // endregion
    }
}
