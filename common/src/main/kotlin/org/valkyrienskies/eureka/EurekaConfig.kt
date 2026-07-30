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

        @JsonSchema(
            description = "Show every saved ship path in this dimension as a glowing line. Toggled in-game " +
                "with SHIFT+O; a route being recorded or flown is always drawn regardless of this."
        )
        var showAllPaths = false

        @JsonSchema(
            description = "How far away, in blocks, path lines stay visible. Path lines are drawn through " +
                "their own unfogged pipeline with an extended far plane, so this can far exceed your render " +
                "distance. A few thousand line segments costs well under a millisecond. Default 4096."
        )
        var pathRenderDistance = 4096.0

        @JsonSchema(description = "Thickness of drawn path lines. Default 2.0.")
        var pathLineWidth = 2.0

        @JsonSchema(
            description = "Seconds each path message stays on screen before fading. Messages stack rather " +
                "than overwriting one another, so a burst of them is all readable. Default 6.0."
        )
        var pathMessageSeconds = 6.0
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

        // region Path recording
        // Ship paths: a pilot records a loop by flying it (SHIFT+R on deck), the recording closes when the ship
        // returns to its start, and any ship can then fly it (SHIFT+P). Geometry only -- speed stays cruise
        // control's job, and heading comes from the recorded line's own tangent. See the `path` package.

        @JsonSchema(
            description = "Path recording: how far the ship must travel before another point is recorded, in " +
                "blocks. Corners are always recorded regardless. Lower = finer detail and a bigger save file; " +
                "higher = coarser. Smoothing runs after recording either way. Default 2.0."
        )
        var pathSampleSpacing = 2.0

        @JsonSchema(
            description = "Path recording: hard cap on recorded points before the recording gives up. At the " +
                "default spacing this is roughly 16 km of route. Exceeding it cancels the recording rather " +
                "than silently truncating it."
        )
        var pathMaxPoints = 8192

        @JsonSchema(
            description = "Path recording: minimum route length in blocks before the loop can close. Stops the " +
                "recording snapping shut the moment you set off, since you start inside the snap radius. " +
                "Default 48.0."
        )
        var pathMinLoopLength = 48.0

        @JsonSchema(
            description = "Path recording: radius in blocks of the glowing sphere at the route's start point. " +
                "The loop closes when the ship's keel sphere touches it. Bigger = easier to close, less exact " +
                "about where. Default 2.0."
        )
        var pathSnapRadius = 2.0

        @JsonSchema(
            description = "Path recording: how many blocks of the route's tail are replaced by a curve that " +
                "runs smoothly into the start point, so a looping ship gets no steering kick each lap. About a " +
                "chunk works well. Default 16.0."
        )
        var pathSeamBlend = 16.0

        @JsonSchema(
            description = "Path smoothing: the filter width in blocks. Wobbles shorter than about twice this " +
                "are erased; curves longer than it survive. Raise it if hand-steering correction still shows " +
                "in the flown route, lower it if deliberate curves are being rounded off. Default 8.0."
        )
        var pathSmoothWindow = 8.0

        @JsonSchema(
            description = "Path smoothing: how many filter passes to run. More passes deepen the effect " +
                "without needing a wider window. Default 3."
        )
        var pathSmoothIterations = 3

        @JsonSchema(
            description = "Path smoothing: how jagged a stretch must be, in degrees of average turn per point, " +
                "before it is fully smoothed. Stretches calmer than this are smoothed proportionally less, and " +
                "dead-straight ones not at all. Lower = smooths more of the route. Default 6.0."
        )
        var pathSmoothJagThreshold = 6.0

        @JsonSchema(
            description = "Path smoothing: the furthest, in blocks, smoothing may move any point from where it " +
                "was actually recorded. This is the guard that stops a smoothed corner cutting through the " +
                "terrain you steered around. Default 2.5."
        )
        var pathMaxSmoothDeviation = 2.5

        @JsonSchema(
            description = "Path playback: how far from a route a ship may be, in blocks, and still start " +
                "following it. Whatever offset it has at that moment is KEPT for the whole run -- the ship " +
                "flies the same shape displaced -- so park close if you want it exactly on the line. Default 32.0."
        )
        var pathEngageRange = 32.0

        @JsonSchema(
            description = "Path playback: seconds of travel ahead the ship aims for. Higher = smoother and " +
                "wider cornering; lower = tighter tracking but twitchier. Default 1.5."
        )
        var pathLookaheadSeconds = 1.5

        @JsonSchema(description = "Path playback: shortest aim-ahead distance in blocks, used at low speed. Default 8.0.")
        var pathLookaheadMin = 8.0

        @JsonSchema(description = "Path playback: longest aim-ahead distance in blocks, used at high speed. Default 40.0.")
        var pathLookaheadMax = 40.0

        @JsonSchema(
            description = "Path playback: steering strength -- commanded turn rate per radian of heading error. " +
                "Higher = corrects back onto the line harder, but too high oscillates. Default 1.2."
        )
        var pathTurnGain = 1.2

        @JsonSchema(
            description = "Path playback: climb/dive strength -- commanded vertical speed per block of altitude " +
                "error. Higher = holds the recorded elevation more tightly. Default 0.5."
        )
        var pathVerticalGain = 0.5

        @JsonSchema(
            description = "Path playback: fraction per second of the start-of-run offset to bleed away, so a " +
                "ship that engaged off to one side gradually converges onto the line. 0 = keep the offset for " +
                "the whole run (the default, and what makes 'fly a parallel course' predictable)."
        )
        var pathOffsetDecay = 0.0

        @JsonSchema(
            description = "Path playback: slow into corners the hull cannot hold at its current speed, so it " +
                "tracks the line instead of running wide and converging back. Speed is still the pilot's -- " +
                "this only ever asks for LESS. Default true."
        )
        var pathCornerSlowdown = true

        @JsonSchema(
            description = "Path playback: fraction of the theoretical cornering speed to actually allow. " +
                "1.0 corners at the hull's absolute yaw limit with nothing in hand for waves or tracking " +
                "error; lower is more conservative and hugs the line harder. Default 0.8."
        )
        var pathCornerSpeedMargin = 0.8

        @JsonSchema(
            description = "Path playback: floor in m/s for the corner slowdown, so a hairpin slows a ship " +
                "down rather than stopping it dead. Default 2.0."
        )
        var pathCornerMinSpeed = 2.0

        @JsonSchema(
            description = "Path playback: seconds a manual turn or ascend/descend input must be HELD to stop " +
                "following the route. Brief inputs still steer -- the route simply re-acquires afterwards -- " +
                "so this is what separates a nudge from letting go. Default 0.6."
        )
        var pathManualCancelHold = 0.6

        @JsonSchema(
            description = "Path playback: a ship keeps following its route across a world reload, or after " +
                "drifting out of simulation and back. Note that turning this OFF does not stop a reloaded ship " +
                "-- cruise persists on its own -- it only stops it STEERING, which is how a route used to be " +
                "lost. Default true."
        )
        var pathResumeOnLoad = true
        // endregion

        // region Ship following (Sneak+F -- see the org.valkyrienskies.eureka.follow package)
        // Station-keeping on another ship. Unlike a route, this owns the THROTTLE as well as the wheel, because
        // holding position beside a moving vessel is a speed problem before it is a steering one.

        @JsonSchema(
            description = "Ship following: how far Sneak+F will reach to pick up a target ship, in blocks. The " +
                "ray is blocked by terrain and by hulls in the way, so you must actually be able to see what " +
                "you are pointing at. May exceed followBreakRange: a pursuit begun beyond that distance is " +
                "given until it stops closing before it is called off. Default 160.0."
        )
        var followTargetRange = 160.0

        @JsonSchema(
            description = "Ship following: hull-to-hull standoff held alongside the leader, in blocks. Measured " +
                "between the two hulls' beams rather than between their centres, so big ships and small ones " +
                "leave the same gap. Default 11.0."
        )
        var followGap = 11.0

        @JsonSchema(
            description = "Ship following: centre-to-centre distance at which a follower gives up and coasts to " +
                "a stop, in blocks. This is what happens when a leader is simply faster -- the follower is never " +
                "given speed it doesn't have, so it falls behind until this trips. A pursuit that started " +
                "further out than this keeps going while it is still closing, and only breaks off once it " +
                "starts losing ground again. Default 90.0."
        )
        var followBreakRange = 90.0

        @JsonSchema(
            description = "Ship following: distance from station, in blocks, over which steering blends from " +
                "chasing the station point to matching the leader's heading. Higher = lines up with the leader " +
                "from further out; lower = chases the point harder and squares up late. Default 40.0."
        )
        var followBlendRange = 40.0

        @JsonSchema(description = "Ship following: commanded yaw rate per radian of heading error. Default 1.2.")
        var followTurnGain = 1.2

        @JsonSchema(
            description = "Ship following: m/s of closing speed asked for per block of along-track error. This " +
                "is the 'gradually come alongside' knob -- lower is a more patient approach. Default 0.25."
        )
        var followClosingGain = 0.25

        @JsonSchema(
            description = "Ship following: cap in m/s on how much FASTER than the leader a follower will try to " +
                "go while closing. A cap on the closing rate rather than on absolute speed, so a follower can " +
                "still chase a fast leader -- it just doesn't rush the last stretch. Default 6.0."
        )
        var followClosingSpeed = 6.0

        @JsonSchema(
            description = "Ship following: how hard a follower may back up, in m/s, when it has overshot its " +
                "station. Small on purpose -- normally it just eases off and lets the leader draw ahead. " +
                "Default 2.0."
        )
        var followReverseSpeed = 2.0

        @JsonSchema(
            description = "Ship following: commanded climb/dive speed in m/s per block of altitude error, for " +
                "matching the leader's level. Default 0.5."
        )
        var followVerticalGain = 0.5

        @JsonSchema(
            description = "Ship following: altitude error in blocks below which no vertical is commanded at all. " +
                "Not just a nicety -- the water altitude hold latches its depth only while the commanded " +
                "vertical is exactly zero, so without a deadband a ship at station would flick the hold on and " +
                "off every tick. Default 1.5."
        )
        var followVerticalDeadband = 1.5

        @JsonSchema(
            description = "Ship following: seconds a manual input must be HELD to break off the follow. Steering " +
                "AND throttle count here, unlike a route -- following owns the throttle, so a pilot pushing it " +
                "is arguing with the ship and has to be able to win. Brief nudges still just nudge; the station " +
                "re-acquires afterwards. Default 3.0."
        )
        var followCancelHold = 3.0

        @JsonSchema(
            description = "Ship following: how far onto the leader's OTHER side a follower must end up, as a " +
                "fraction of the standoff, before it gives up its side and takes station on that one. This is " +
                "hysteresis: a follower sitting directly astern is on neither side, and without a margin it " +
                "would flip between port and starboard every tick. Raise it to make sides stickier. Default 0.5."
        )
        var followSideFlipMargin = 0.5
        // endregion

        // Armada world collision is engine-resolved: a child is welded to its parent by a rigid VSFixedJoint and
        // collides with the world as a normal physics body, so there is nothing here to tune.
    }
}
