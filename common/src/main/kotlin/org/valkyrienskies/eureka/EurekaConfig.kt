package org.valkyrienskies.eureka

import com.github.imifou.jsonschema.module.addon.annotation.JsonSchema

object EurekaConfig {
    @JvmField
    val CLIENT = Client()

    // Which REPORTS this player wants on the HUD, written to the config file as "clientMessages".
    //
    // Every key is one subcategory of thing-that-happened, and every one defaults ON: these switches are
    // for a captain who has learned what their own ship sounds like, not a wall of opt-ins for somebody
    // who has just installed the mod. The case they exist for is a boarding action with a full crew,
    // where death lines alone bury everything else being said at the time.
    //
    // Nothing here can silence a REFUSAL. A line explaining why an order did not happen carries
    // PathMessages.Topic.ALWAYS and is addressed by no key below -- a captain who turned off "the guns
    // are laid" still gets told the guns would not lay, because the alternative is a dead button and no
    // way to find out why. See PathMessages.Topic, which is where a topic and its key are married.
    //
    // CLIENT-side, like the timer that governs how long they hold: a streamer quiets their own screen
    // without touching anybody else's. On a dedicated server this block is read and then never consulted
    // -- the same inert copy the "client" block above is, and for the same reason.
    @JvmField
    val MESSAGES = Messages()

    // The BASE settings block, written to the config file as "server". It holds every knob that is NOT
    // per-category, and there are three kinds of those:
    //
    //  - ASSEMBLY-TIME knobs (maxShipBlocks, blockBlacklist, terrainPocketMaxBlocks, diagonals, the
    //    Auto-Shipwright whitelists). These are consumed by ShipHelmBlockEntity while the ship is being
    //    built, when no ship -- and therefore no category -- exists yet, so they CANNOT be per-category.
    //  - BUOYANCY AND LIFT (floaterBuoyantFactorPerKg, maxFloaterBuoyantFactor, massPerBalloon,
    //    balloonLiftMultiplier, maxBalloonsPerEngine, assemblerBalloonAscendRate). These are deliberately
    //    global even though they could technically be split. A hybrid ship changes category in mid-air the
    //    moment its keel touches water; if lift changed with it, the ship would sink or leap at the
    //    waterline. These same numbers are also what sizes a hull at assembly time. Categories change how a
    //    ship HANDLES, never whether it floats.
    //  - Everything shared: path/follow gains, cruise hold times, engine internals, debug toggles.
    //
    // Editing a per-category knob here does nothing once the file has been written once -- the categories
    // carry their own copies. See EurekaConfigLoader for how they are seeded.
    @JvmField
    val SERVER = Server()

    // The three SHIP CATEGORIES, written as "serverBoat" / "serverAirship" / "serverSubmarine". Each is a
    // full copy of the same field set, so any of them can be tuned independently in the config file, and a
    // ship reads its handling off exactly one of them -- picked from what the ship is built out of:
    //
    //    floaters, no balloons  -> BOAT        balloons, no floaters -> AIRSHIP
    //    both (a "hybrid")      -> BOAT while it is touching water, AIRSHIP once it is clear of it
    //
    // See ControlProfile and EurekaShipControl.cfg (and .engineCfg, which EngineBlockEntity reads for
    // per-category engine force and heat).
    @JvmField
    val BOAT = ShipHandling().also { boatDefaults(it) }

    @JvmField
    val AIRSHIP = ShipHandling().also { airshipDefaults(it) }

    @JvmField
    val SUBMARINE = ShipHandling().also { submarineDefaults(it) }

    // Boats & Ships are the REFERENCE tuning -- every value below is the pre-category default, restated so
    // the three presets can be read side by side. The fastest and most manoeuvrable of the three.
    @JvmStatic
    fun boatDefaults(s: ShipHandling) {
        s.maxSpeedFromEngines = 50.0
        s.maxReverseSpeedFromEngines = 50.0
        s.turnSpeed = 1.0
        s.turnAcceleration = 7.0
        s.waterThrustAssist = 8.0
        s.enableWaterAltitudeHold = true
        s.doFluidDrag = false
        s.enginePowerLinear = 120000f
        s.turnAccelDelay = 1.2
    }

    // Airships take BOAT HANDLING as their base and then a short list of flight deltas, below. Those deltas
    // were arrived at by hand in serverAirship over a lot of flying, and they now ship as defaults rather
    // than living only in one person's config file -- a preset nobody else receives is not a preset.
    //
    // The first cut seeded this preset from the retired Vanilla engine numbers and produced an airship that
    // crawled -- ~3 m/s piloted (i.e. baseSpeed and nothing more), ~8 under cruise. The culprit was
    // engineHeatGain 0.03f, and it is the same trap that turnSpeed 3.0 was: Vanilla's numbers were tuned
    // against a law this build no longer runs.
    //
    // An engine's force is lerp(enginePowerLinearMin, enginePowerLinear, heat/100). Heat gains
    // (100*engineHeatChangeExponent - heat*..., + 1) * engineHeatGain per tick -- 11 * gain from cold -- and
    // drains by `consumed`, which is ~0.1 per physics tick spent at throttle. Upstream had deleted that drain
    // (a2f1f7b), so under Vanilla heat only ever climbed and 0.03 was free; this build restored it. At 0.03
    // the gain from cold is 0.33/tick against a drain of ~0.3 plus cooling, so heat never leaves the floor,
    // every engine sits at enginePowerLinearMin -- 10000f, a fiftieth of the 500000f the preset advertised --
    // and enginePowerLinear being high made it worse a second time by lifting the boost threshold
    // (engineBoostOffset * enginePowerLinear) past anything a cold engine can reach.
    //
    // So: engineHeatGain is a floor, not a dial. Below roughly 0.05 an engine cannot outrun its own drain at
    // full throttle, whatever enginePowerLinear says. Raise power, not patience.
    //
    // enableWaterAltitudeHold comes along with the boat values and reads `true` here, which is inert: the
    // hold is gated on activeProfile == BOAT at its one use site and the helm checkbox writes BOAT directly,
    // so this preset's copy of the key is never read.
    @JvmStatic
    fun airshipDefaults(s: ShipHandling) {
        boatDefaults(s)
        // Power, because an airship carries its weight on engines rather than on water, and the heat floor
        // above means the way to make one move is more power and not more patience. engineHeatGain itself is
        // deliberately left at the base value -- that is the trap the note describes, not a dial.
        //
        // These numbers are sized against the heat an engine SETTLES at under load (~68 on stock heat
        // settings, see EurekaShipControl.steadyStateHeat), not against a full tank it never reaches. Sized
        // against 100 instead, a hull lands about 11% short of the figure the helm quotes.
        s.enginePowerLinear = 4900000f
        s.enginePowerLinearMin = 245000f
        // Deliberately linear past the threshold. engineBoostExponentialPower squares the surplus, which on a
        // large bank overtook every other term and made five more engines a step change rather than a gain --
        // impossible to tune a speed class around. Zero here; the offset carries the "an airship needs a real
        // bank of engines before it pays off" idea on its own.
        s.engineBoost = 1.0
        s.engineBoostOffset = 8.0
        s.engineBoostExponentialPower = 0.0
        // 40 is a CEILING, not the target: sized so a 35-50 engine hull sits at roughly twice the power ratio
        // and the throttle therefore maps onto speed nearly linearly. Pushed higher, the atan saturates and a
        // few percent of throttle commands nearly full speed -- which is what makes a launch violent and a
        // coast long, because the commanded speed hangs at the top while the throttle bleeds off.
        s.maxSpeedFromEngines = 50.0
        s.maxReverseSpeedFromEngines = 50.0
        // Doubles the stiffness of the velocity controller (acceleration is speedMassScale * velocity error),
        // which firms up stopping far more than it costs on the launch, since a launch is throttle-limited.
        // enginePowerLinear above is scaled to match -- speedMassScale divides engine power as well.
        s.speedMassScale = 2.0
        // Vertical authority: an airship climbs and dives on its own lift rather than on a waterline.
        s.baseImpulseElevationRate = 15.0
        s.baseImpulseDescendRate = 15.0
        s.balloonElevationMaxSpeed = 15.0
        // Quicker than a hull, well short of the engine-independent 24/r ceiling. turnAcceleration is the one
        // that decides whether a sustained hold reaches that ceiling; at 10 it got there in about two seconds,
        // which reads as a ship on a swivel.
        s.turnSpeed = 2.0
        s.turnAcceleration = 4.0
        // Throttle ramp. linearMassScaling is what dominates on a heavy hull -- at 2e-4 a 1.5M kg ship draws
        // 300 against linearBaseMass's 70 -- so halving it, not raising the base, is what lets an airship
        // spool up better than a boat of the same mass.
        s.linearMassScaling = 0.0001
        s.linearBaseMass = 70.0
        // A stronger brake for a hull nobody is steering.
        s.linearStabilizeMaxAntiVelocity = 8.0
        // Restored to the pre-category value: boatDefaults above now sets these for a
        // boat, and this category is built on top of it, so leaving them inherited would
        // hand a boat's tuning to a hull that is not one.
        s.turnAccelDelay = 0.6
    }

    // PLACEHOLDER. Submarine handling is not implemented -- ControlProfile.SUBMARINE is never selected, so
    // nothing here is read yet. The block exists now so the config file's shape is final and the values are
    // ready to tune when the submarine work lands. Boat handling, slowed down, with more vertical authority.
    @JvmStatic
    fun submarineDefaults(s: ShipHandling) {
        boatDefaults(s)
        s.maxSpeedFromEngines = 50.0
        s.maxReverseSpeedFromEngines = 50.0
        s.turnSpeed = 0.4
        s.turnAcceleration = 6.0
        s.baseImpulseElevationRate = 3.0
        s.baseImpulseDescendRate = 6.0
        s.elevationSnappiness = 1.5
        s.doFluidDrag = true
        // Restored to the pre-category value: boatDefaults above now sets these for a
        // boat, and this category is built on top of it, so leaving them inherited would
        // hand a boat's tuning to a hull that is not one.
        s.enginePowerLinear = 100000f
        s.turnAccelDelay = 0.6
    }

    class Client {
        @JsonSchema(description = "The piloted-ship HUD: speed, altitude and compass heading as small text at the top-center of the screen. One switch for the whole line -- toggled from the helm menu.")
        var displayHud = true

        @JsonSchema(
            description = "How far away a cannonball in flight stays visible, in blocks. Vanilla culled a " +
                "shot-sized entity at about 77 blocks, which made long shots vanish mid-arc. Visibility " +
                "no longer bends to render distance; the server stops tracking shots past 1024 blocks, " +
                "so values beyond that show nothing further. Default 1024."
        )
        var cannonShotRenderDistance = 1024

        @JsonSchema(
            description = "How fast a cannon's barrel visibly swings to a new elevation order, in degrees " +
                "per second. Purely cosmetic -- shots always fly at the ordered angle, even mid-swing. At " +
                "the default 35 a lay from level to full elevation takes about 1.3 seconds, and a full " +
                "-45 to +45 traverse about 2.6. Zero or below snaps the barrel instantly."
        )
        var cannonBarrelSlewDegreesPerSecond = 35.0

        @JsonSchema(
            description = "Skip drawing a cannon barrel that is behind you. The barrel is a virtual block " +
                "drawn per gun per frame, and the renderer opts out of chunk-section culling so that a " +
                "long gun is never chopped in half at a section edge -- which also means every gun on " +
                "every loaded ship is drawn whichever way you are facing. On a 120-gun ship that was the " +
                "single largest cost in the frame. The test is a plane through the camera, not a cone, so " +
                "nothing that could appear on screen at any field of view is ever dropped and there is " +
                "no popping at the edge of vision. Off restores the old draw-everything behaviour. " +
                "Default true."
        )
        var cannonBarrelCullBehindCamera = true

        @JsonSchema(
            description = "Draw cannon barrels into the shader SHADOW MAP as well as the visible view. " +
                "Shaders render the world twice, and a barrel is a block entity, so every gun costs twice " +
                "over -- on a sixty-gun ship that second pass was about a fifth of the whole frame, which " +
                "is what made shaders plus a full crew halve the frame rate. Off, a gun still casts the " +
                "shadow of its carriage (ordinary block geometry); it just stops casting a separate one " +
                "for the muzzle. Costs nothing and does nothing without a shader pack."
        )
        var cannonBarrelCastsShadows = false

        @JsonSchema(
            description = "Show every saved ship path in this dimension as a glowing line. Toggled in-game " +
                "with SHIFT+H; a route being recorded or flown is always drawn regardless of this."
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
            description = "How far away, in blocks, crew nameplates stay drawn. Nameplates are toggled with " +
                "SHIFT+C while standing on a ship and show only YOUR crew on THAT ship, so this is purely " +
                "about legibility: a full 32-strong crew seen from across the water is a wall of text. " +
                "Vanilla stops drawing its own name tags at 64. Default 48.0."
        )
        var crewNameplateRange = 48.0

        @JsonSchema(
            description = "Seconds EVERY Armada message stays on screen before fading -- crew, gunnery, " +
                "stores, pirates, the shipwright, all of it. Messages stack rather than overwriting one " +
                "another, so a burst of them is all readable. Was 6.0 and per-feature: the restock receipt " +
                "carried its own 3.5 because six seconds of chest names is clutter, and once one message " +
                "had its own answer the rest wanted one too. One number instead, at the shorter value -- " +
                "raise it if you would rather read than glance. Which messages appear at all is the " +
                "clientMessages block. Default 3.5."
        )
        var messageSeconds = 3.5

        @JsonSchema(
            description = "Seconds SHIFT+R or SHIFT+P must be HELD for the destructive half of the key -- " +
                "discarding a recording, releasing a ship from its route. Tapping either does the ordinary " +
                "thing instead (start recording; play, pause or resume). A ring around the crosshair fills as " +
                "you hold, and the action fires when it closes. Default 2.0."
        )
        var pathHoldSeconds = 2.0

        @JsonSchema(
            description = "Whether the ship hotkeys (record, fly route, show routes, follow, crew) require " +
                "Sneak to be held. Keep it on for a keyboard -- the Sneak chord is what stops R, P, H, F and " +
                "C firing ship actions during ordinary play. Turn it OFF for a controller profile: bind the " +
                "actions to spare buttons in your controller mod and each fires on a plain press, no " +
                "crouch-chord needed. With it off, pressing the bare KEYBOARD keys fires them too, so this " +
                "belongs on the machine the controller is plugged into. Broadside never needed Sneak either " +
                "way. Default true."
        )
        var hotkeysNeedSneak = true
    }

    /**
     * Per-subcategory switches for the things the mod REPORTS. See the KDoc on [MESSAGES].
     *
     * Grouped by what the message is about, not by where in the code it comes from, because "I do not want
     * to hear about crew deaths" is a sentence about crew deaths and not about `CrewRegistrationsFabric`.
     * How long each one holds is [Client.messageSeconds]; whether it appears at all is here.
     */
    class Messages {

        // region Crew
        @JsonSchema(
            description = "Report when a hand signs on or is paid off, and what the articles then muster. " +
                "The confirmation for a deliberate act, so it is mostly worth having -- turn it off if you " +
                "recruit in batches and would rather read the crew menu once than forty lines in a row. " +
                "Default true."
        )
        var crewRecruiting = true

        @JsonSchema(
            description = "Report when a crew member is lost and their berth falls vacant. This is the one " +
                "that fires HARDEST, and it fires worst in a boarding action -- a dozen hands can go in as " +
                "many seconds, one line each, on top of everything else being said at the time. Turning it " +
                "off loses the line and never the berth: the articles are kept either way and the crew menu " +
                "still reads true. Default true."
        )
        var crewDeaths = true

        @JsonSchema(
            description = "Report a berth bought with a Heart of the Sea, and the new size of the largest " +
                "crew you can muster. Rare and deliberate -- the refusal when the articles are already " +
                "full is a separate line and always shows. Default true."
        )
        var crewBerths = true

        @JsonSchema(
            description = "Report the result of a muster: who came aboard, who was already there, who was " +
                "paid off for want of a berth. One line at the end of an assembly, a summon or a bottle " +
                "coming out, not one per hand. Default true."
        )
        var crewMuster = true

        @JsonSchema(
            description = "Report a crew member changing duty -- to the guns, to the fire watch, off duty " +
                "-- and being locked or unlocked against bulk orders. One line per villager, so a captain " +
                "re-organising a full crew from the manifest is the case for turning it off. Default true."
        )
        var crewDuties = true

        @JsonSchema(
            description = "Report the fire party putting fires out. Fires come in bursts, and this is one " +
                "line per pass to EVERYONE aboard -- so on a ship taking incendiary fire it is the second " +
                "noisiest thing after the deaths themselves. Default true."
        )
        var crewFireBrigade = true

        @JsonSchema(
            description = "Report the crew nameplates being marked on or off with the crew key. Default true."
        )
        var crewMarkers = true

        @JsonSchema(
            description = "Report a crew standing down into the articles -- which happens three different " +
                "ways: a ship disassembled, a ship bottled, or a hull broken up by a shipwright. Its own " +
                "key rather than one of the three above, because it is the same event whichever door it " +
                "came through. Default true."
        )
        var crewStandDown = true
        // endregion

        // region Gunnery
        @JsonSchema(
            description = "Report a broadside going off: how many guns spoke of how many, and how many " +
                "stood unmanned. Sent to the whole deck, so a passenger on a fighting ship hears every " +
                "volley. Default true."
        )
        var gunneryBroadside = true

        @JsonSchema(
            description = "Report the Fire at Will standing order being given or lifted. Twice a battle at " +
                "most, and it is a standing order -- worth knowing it is still standing. Default true."
        )
        var gunneryFireAtWill = true

        @JsonSchema(
            description = "Report gunners taking or leaving their stations, one line per villager. Manning " +
                "a two-decker in one order is the case for turning this off. Default true."
        )
        var gunneryStations = true

        @JsonSchema(
            description = "Report a battery being laid to an elevation or set to a powder measure. One " +
                "line per bulk order, not per gun. Default true."
        )
        var gunneryOrders = true

        @JsonSchema(
            description = "Report a gunner standing down because the gun they served is gone. Fires when a " +
                "battery is shot away, which is exactly when the HUD is busiest. Default true."
        )
        var gunneryGunLost = true
        // endregion

        // region Stores
        @JsonSchema(
            description = "Report what a restock or refuel actually moved -- how many guns were loaded " +
                "with what, or how many engines were stoked. The first line of the receipt; the second is " +
                "storesReceipts. Default true."
        )
        var storesResults = true

        @JsonSchema(
            description = "Show the second line of a restock: which numbered chests the stock came out of " +
                "and went back into. Replaces the old server-side restockMessages, which switched receipts " +
                "off for a whole world at once -- a HUD is a per-player thing, so the switch belongs where " +
                "the reading happens. A captain who knows their own hold plan is the case for turning it " +
                "off. Default true."
        )
        var storesReceipts = true
        // endregion

        // region Pirates
        @JsonSchema(
            description = "Report a pirate taking up the chase, and losing it again. Default true."
        )
        var piratesPursuit = true

        @JsonSchema(
            description = "Report the turns of a fight for a pirate hull: her crew wiped, her wheel open " +
                "to be taken, a fresh crew mustered, her wheel finally given out. Default true."
        )
        var piratesConquest = true

        @JsonSchema(
            description = "Show the countdown while your ship sits inside a sleeping pirate's water. It is " +
                "a running prompt rather than a one-shot, so it refreshes every tick you stay. Turning it " +
                "off does not stop them waking -- it stops the warning. Default true."
        )
        var piratesZones = true
        // endregion

        // region Following another ship
        @JsonSchema(
            description = "Report a ship being put into pursuit of another, or breaking off. Told to both " +
                "decks, so the ship being followed hears it too. Default true."
        )
        var followBinding = true

        @JsonSchema(
            description = "Report how a pursuit is going: coming alongside, circling a stopped leader, " +
                "falling astern, losing contact. Default true."
        )
        var followStatus = true
        // endregion

        // region Recorded routes
        @JsonSchema(
            description = "Report a route recording starting, saving or being discarded. Default true."
        )
        var routesRecording = true

        @JsonSchema(
            description = "Report a ship flying a route: engaging, holding at a recorded stop, being " +
                "released, resuming after a world reload. Default true."
        )
        var routesReplay = true

        @JsonSchema(
            description = "Report routes being shown or hidden with the route key. Default true."
        )
        var routesVisibility = true
        // endregion

        // region Cruise control
        @JsonSchema(
            description = "Report cruise control and auto-pilot being engaged and disengaged. Default true."
        )
        var cruiseStatus = true
        // endregion

        // region Shipwright
        @JsonSchema(
            description = "Report plans being filed, discarded, or saved beside the original. Default true."
        )
        var shipwrightPlans = true

        @JsonSchema(
            description = "Report materials handed to a shipwright, the fee taken, and how much of the " +
                "bill is settled. Fires once per handover, and a hull is paid for over many trips. " +
                "Default true."
        )
        var shipwrightMaterials = true

        @JsonSchema(
            description = "Report a commissioned ship being built beside the bench or handed over bottled. " +
                "Default true."
        )
        var shipwrightDelivery = true

        @JsonSchema(
            description = "Report plans being altered, reset, or drawn up onto a fresh page, and materials " +
                "handed back when a row is struck off. Default true."
        )
        var shipwrightAlterations = true
        // endregion

        // region Repair
        @JsonSchema(
            description = "Report materials handed over toward a repair, and how much of it they cover. " +
                "Default true."
        )
        var repairProgress = true

        @JsonSchema(
            description = "Report a hull mended, and how many blocks went back into her. Default true."
        )
        var repairComplete = true

        @JsonSchema(
            description = "Report a partial repair: what the pot covered, and how much of the hull is " +
                "still wanting. Default true."
        )
        var repairPartial = true
        // endregion

        // region Salvage
        @JsonSchema(
            description = "Report a hull broken up: how many kinds are waiting to be claimed, and what is " +
                "being kept whole rather than counted. Default true."
        )
        var salvageDismantle = true

        @JsonSchema(
            description = "Report salvage carried aboard or thrown back into the sea, one line per claim. " +
                "Clearing a long claim list is the case for turning it off. Default true."
        )
        var salvageClaims = true
        // endregion

        // region Ship bottles
        @JsonSchema(
            description = "Report a bottle being marked with a ship's wheel. Default true."
        )
        var bottleMarking = true

        @JsonSchema(
            description = "Report a ship going into the bottle, and her crew standing down with her. " +
                "Default true."
        )
        var bottleCapture = true

        @JsonSchema(
            description = "Report a ship coming back out of the bottle. Default true."
        )
        var bottleRelease = true
        // endregion

        // region Blueprints
        @JsonSchema(
            description = "Report a blueprint being drafted from a ship's wheel. Default true."
        )
        var blueprintDrafting = true
        // endregion
    }

    /**
     * The settings a ship reads its HANDLING off: engine force and heat gain, speed caps, throttle ramp,
     * turning, stabilization, vertical response and thrust assists.
     *
     * Split out from [Server] so the three category blocks in the config file carry ONLY the keys a category
     * actually governs. They used to be full copies of [Server], which wrote every global key into all three
     * -- inert copies that read like knobs, so tuning a cannon in `serverAirship` looked like it should work
     * and silently did nothing. A separate type makes that a compile error rather than a puzzle.
     *
     * [Server] extends this, so EurekaConfig.SERVER still answers for both halves: its handling half is the
     * reference tuning the three presets are seeded from, and the fallback an engine reads when it is not on
     * a ship at all.
     */
    open class ShipHandling {

        @JsonSchema(description = "Movement power per engine when heated fully")
        var enginePowerLinear: Float = 100000f

        @JsonSchema(description = "Movement power per engine with minimal heat")
        var enginePowerLinearMin: Float = 10000f

        @JsonSchema(description = "The amount of heat a gain per tick (when burning)")
        var engineHeatGain = 0.09f

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

        // Cannons used to keep their muzzle velocity and reload here, on the rule that a gun belongs to a
        // ship and a ball does not. That rule was right and is now moot: a shot's arc is cut by POWDER
        // CHARGE instead, and how much powder is behind the ball is not a fact about the hull under it. All
        // twelve cannon numbers live on Server -- see the Cannons region there.
    }

    /**
     * Everything that is NOT per-category, plus -- by inheritance -- the handling block that is.
     *
     * Three kinds of thing live here. ASSEMBLY-TIME knobs, consumed while a ship is being built, when no
     * ship (and therefore no category) exists yet. BUOYANCY AND LIFT, deliberately global because a hybrid
     * changes category the moment its keel touches water, and a ship whose lift changed with it would sink
     * or leap at the waterline. And everything simply shared: path servo gains, cruise hold times, engine
     * internals, crew, fire, ballast, debug toggles.
     */
    class Server : ShipHandling() {

        @JsonSchema(description = "Turning power per engine when heated fully")
        var enginePowerAngular = 1.0f

        @JsonSchema(description = "Turning power per engine when minimal heat")
        var enginePowerAngularMin = 0.0f

        @JsonSchema(description = "The amount of heat a engine loses per tick")
        var engineHeatLoss = 0.01f

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

        @JsonSchema(description = "The amount extra that each floater will make the ship float, per kg mass")
        var floaterBuoyantFactorPerKg = 50_000.0

        @JsonSchema(description = "The maximum amount extra each floater will multiply the buoyant force by, irrespective of mass")
        var maxFloaterBuoyantFactor = 1.0

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
            description = "How submerged the ship must be (fraction 0..1) for the water altitude-hold to " +
                "ENGAGE. Once engaged it stays until the hull fully clears the water. Small = engages as soon " +
                "as the keel touches. Default 0.05."
        )
        var waterAltitudeHoldMinOverlap = 0.05

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

        // region Eureka Auto-Shipwright
        // The Eureka Auto-Shipwright (enabled per-player from the helm menu, or with
        // /vs auto-shipwright <floater|balloon|balloon-replace-all> <true|false>) fits out
        // a ship at assembly time by REPLACING existing hull blocks with floaters/balloons -- never adding new volume --
        // and only if the player has the required floater/balloon items in their inventory. These two ORDERED lists are
        // the replacement whitelists (anything not listed is never replaced). Order = priority: the assembler exhausts
        // each block id in list order (bottom-up within a type) before moving to the next -- all oak planks, then spruce
        // planks, ... then logs. Two wooden slabs forming a full block are a DIFFERENT block id (*_slab) and are never
        // matched. Consumed at ASSEMBLY time (ShipHelmBlockEntity), so like maxShipBlocks/blockBlacklist these are read
        // GLOBALLY off EurekaConfig.SERVER, never per-ship.

        @JsonSchema(
            description = "Eureka Auto-Shipwright: the target ASCEND speed in m/s the 'balloon' auto-fill sizes for. " +
                "Sizing balloons for neutral hover (= 0 here, the old behavior) lets a heavy ship hold itself up but " +
                "never climb, because the ship's usable climb force is only the balloon lift SURPLUS over its weight. " +
                "This adds mass-proportional lift headroom so every assembled ship -- light hull or heavy -- can " +
                "actually ascend at about this rate. Higher = climbs faster but converts more hull blocks to balloons; " +
                "0 = neutral hover only (may not climb). /vs get-ship-weight uses the same target, so its reported " +
                "'needed' balloon count matches what the assembler places."
        )
        var assemblerBalloonAscendRate = 2.0

        @JsonSchema(
            description = "Eureka Auto-Shipwright: ordered block ids the 'floater' auto-fill replaces, in priority order " +
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
            description = "Eureka Auto-Shipwright: ordered block ids the 'balloon' auto-fill replaces, in priority order. " +
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
        // returns to its start, and any ship can then fly it. Heading always comes from the recorded line's own
        // tangent; SPEED depends on which of the two playback modes is used. SHIFT+P flies the geometry alone and
        // leaves the throttle to the pilot's cruise setting (the original behaviour); CTRL+SHIFT+P replays the
        // whole recording, including how fast the ship was going at each point and how long it sat still. See
        // the `path` package.

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
            description = "Path recording: radius in blocks of the glowing sphere at the route's start point, " +
                "for the SMALLEST ships. The loop closes when the ship's keel sphere touches it. Bigger = " +
                "easier to close, less exact about where. Scaled up for larger hulls by pathMarkerScaleStep. " +
                "Default 2.0."
        )
        var pathSnapRadius = 2.0

        @JsonSchema(
            description = "Path recording: how much bigger both snap spheres get per 5 blocks of the recording " +
                "ship's footprint, as a multiple of their base size. At the default 0.5 a raft up to 5 blocks " +
                "draws them at 1x, a 10-block hull at 1.5x, a 38-block one at 4.5x. This is not decoration: " +
                "the spheres ARE the distance at which a loop closes, and a big ship cannot park its keel " +
                "within two blocks of where it started. Set to 0 for the old fixed size. Default 0.5."
        )
        var pathMarkerScaleStep = 0.5

        @JsonSchema(
            description = "Path recording: also record WHEN the ship reached each point, and how long it sat " +
                "still, so the route can be replayed exactly as flown (CTRL+SHIFT+P). Costs a little save file " +
                "per route. Turning this off does not affect SHIFT+P, which never used timing. Default true."
        )
        var pathRecordTiming = true

        @JsonSchema(
            description = "Path recording: how much the recorded timing may be simplified, in seconds. A leg " +
                "flown at a steady pace collapses to two numbers; an acceleration keeps its shape. Lower = more " +
                "faithful and a bigger save file. Default 0.15."
        )
        var pathTimeEpsilon = 0.15

        @JsonSchema(
            description = "Path recording: speed in m/s below which the ship counts as STOPPED rather than " +
                "merely slow. Measured through the world, so a ship rising straight up is moving. Default 0.4."
        )
        var pathDwellSpeed = 0.4

        @JsonSchema(
            description = "Path recording: how many seconds a ship must sit still before the stop is recorded " +
                "as a deliberate pause -- loading cargo, waiting for a lock -- that playback will reproduce. " +
                "Below this it is just a hesitation and is ignored. Default 1.5."
        )
        var pathDwellMinSeconds = 1.5

        @JsonSchema(
            description = "Path recording: longest pause that can be recorded, in seconds. Stops an idle pilot " +
                "baking a twenty-minute wait into a route they then have to fly. Default 300.0."
        )
        var pathDwellMaxSeconds = 300.0

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
                "error. Note this now only corrects ERROR: the bulk of a climb or dive comes from the route's " +
                "own slope (see pathVerticalLookahead), so this no longer has to be large. Default 0.5."
        )
        var pathVerticalGain = 0.5

        @JsonSchema(
            description = "Path playback: how far ahead the ship reads the route's ELEVATION, in blocks -- kept " +
                "separate from the steering lookahead, which is ten times longer. Steering has to aim ahead " +
                "because a hull cannot turn instantly; altitude is a direct up/down command and does not. " +
                "Sharing the steering value is what made a route that dives to the ground and climbs back get " +
                "flown as an average of itself, never reaching the bottom. Raise it if the ship pitches into " +
                "dips too abruptly. Default 4.0."
        )
        var pathVerticalLookahead = 4.0

        @JsonSchema(
            description = "Path playback: vertical speed in m/s below which the ship is commanded to hold level " +
                "exactly. Small but load-bearing: the water altitude hold only latches its depth while the " +
                "commanded vertical is precisely zero, so a permanently jittering near-zero command would knock " +
                "it out of hold every tick and the ship would slowly sink. Default 0.15."
        )
        var pathVerticalDeadband = 0.15

        @JsonSchema(
            description = "Path playback: slow down for a climb or dive steeper than the hull can physically " +
                "manage, so it follows the slope instead of sailing over it. The vertical twin of " +
                "pathCornerSlowdown, and the same kind of limit: a ship that can only descend at 5 m/s cannot " +
                "hold a 45-degree dive at 20 m/s forward, whatever it is told. Default true."
        )
        var pathVerticalSlowdown = true

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
            description = "Replay playback (CTRL+SHIFT+P): how fast the recording's own clock runs, so one " +
                "recording can be flown briskly or gently without re-recording. The SHAPE is kept either way -- " +
                "a careful take-off stays proportionally careful, and recorded stops still last as recorded. " +
                "1.0 flies it exactly as recorded. Default 1.0."
        )
        var pathReplaySpeedScale = 1.0

        @JsonSchema(
            description = "Replay playback: seconds the ship takes to ease from wherever it was onto the route " +
                "line. A replay flies the drawn route EXACTLY, with no offset, so this is the one moment it is " +
                "anywhere else. Default 3.0."
        )
        var pathReplayJoinSeconds = 3.0

        @JsonSchema(
            description = "Replay playback: how hard being off the line is turned into speed back toward it, in " +
                "1/s. The recorded velocity does most of the work; this only closes what the last tick missed. " +
                "Higher = tighter tracking and a harsher response to being knocked about. Default 2.0."
        )
        var pathServoPosGain = 2.0

        @JsonSchema(
            description = "Replay playback: how hard a velocity error is turned into force, in 1/s. This is the " +
                "one that makes a replay reproduce a recording rather than approximate it, and it is why a " +
                "replayed take-off no longer accelerates like the helm's cruise box. Default 12.0."
        )
        var pathServoVelGain = 12.0

        @JsonSchema(
            description = "Replay playback: ceiling in m/s on the speed a replay will command, however far off " +
                "its line the ship has been pushed. Default 60.0."
        )
        var pathServoMaxSpeed = 60.0

        @JsonSchema(
            description = "Replay playback: ceiling in m/s^2 on the acceleration a replay will command, so a " +
                "collision cannot turn a tracking error into a catapult. Default 40.0."
        )
        var pathServoMaxAccel = 40.0

        @JsonSchema(
            description = "Replay playback: how hard a heading or heel error is turned into a turn rate, in " +
                "1/s. Default 3.0."
        )
        var pathServoRotGain = 3.0

        @JsonSchema(
            description = "Replay playback: how hard a turn-rate error is turned into torque, in 1/s. " +
                "Default 12.0."
        )
        var pathServoOmegaGain = 12.0

        @JsonSchema(
            description = "Replay playback: ceiling in rad/s on the turn rate a replay will command. " +
                "Default 1.5."
        )
        var pathServoMaxOmega = 1.5

        @JsonSchema(
            description = "Replay playback: ceiling in rad/s^2 on the angular acceleration a replay will " +
                "command. Default 8.0."
        )
        var pathServoMaxAlpha = 8.0

        @JsonSchema(
            description = "Replay playback: how far off its line a ship may be, in blocks, before it counts as " +
                "BLOCKED -- terrain or a build that was not there when the route was recorded. Default 8.0."
        )
        var pathReplayMaxError = 8.0

        @JsonSchema(
            description = "Replay playback: how many seconds a ship must stay that far off before it gives up, " +
                "releases itself and says the route is blocked, rather than grinding against the obstruction " +
                "with nobody told. Default 4.0."
        )
        var pathReplayBlockedSeconds = 4.0

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
            description = "Ship following: how far Sneak+F reaches to pick up a target, in blocks, for the " +
                "SMALLEST ships. Bigger hulls see further -- every 5 blocks of your own ship's footprint adds " +
                "followTargetRangeStep, up to followTargetRangeMax. The ray is blocked by terrain and by hulls " +
                "in the way, so you must actually be able to see what you are pointing at. May exceed " +
                "followBreakRange: a pursuit begun beyond that distance runs for as long as it is still making " +
                "ground (see followBreakSlack). Default 80.0."
        )
        var followTargetRange = 80.0

        @JsonSchema(
            description = "Ship following: blocks of reach each 5 blocks of footprint adds to followTargetRange. " +
                "Measured on YOUR ship, not the target -- the reach is how far a vessel can pick something out " +
                "and set off after it, which is a fact about the vessel giving the order. At the defaults a " +
                "5-block raft reaches 80, a 20-block hull reaches 200 and a 40-block one reaches 360. " +
                "Default 40.0."
        )
        var followTargetRangeStep = 40.0

        @JsonSchema(
            description = "Ship following: the furthest Sneak+F will ever reach, in blocks. Hit at a footprint " +
                "of 60 with the default base and step; past that the biggest hulls all see the same distance. " +
                "Default 520.0."
        )
        var followTargetRangeMax = 520.0

        @JsonSchema(
            description = "Ship following: smallest gap of clear water held alongside the leader, in blocks. " +
                "The gap is measured hull to hull, not centre to centre -- both hulls' own widths are added on " +
                "top of it -- and it is what two of the smallest possible ships would hold. Default 4.0."
        )
        var followGapBase = 4.0

        @JsonSchema(
            description = "Ship following: blocks of hull footprint that buy one more block of gap. A ship's " +
                "footprint is the mean of its two horizontal spans, and the two ships' footprints are averaged, " +
                "so a 38-block ship following a 22-block one is sized as 30. The result is rounded DOWN, so " +
                "at the default 5.0 a footprint of 38 holds 11 blocks and only 40 reaches 12. Default 5.0."
        )
        var followGapStep = 5.0

        @JsonSchema(
            description = "Ship following: the widest the gap may ever open, in blocks. Reached at a footprint " +
                "of 60 with the default base and step; past that the biggest hulls hold station no further out " +
                "than each other. Default 12.0."
        )
        var followGapMax = 12.0

        @JsonSchema(
            description = "Ship following: how far from its STATION a follower may be dragged, in blocks, before " +
                "it gives up and coasts to a stop. Measured from the station rather than from the leader, so a " +
                "big hull holding a wide berth is not already half way to its limit while perfectly in formation. " +
                "This is what happens when a leader is simply faster -- the follower is never given speed it " +
                "doesn't have, so it falls behind until this trips. Default 160.0."
        )
        var followBreakRange = 160.0

        @JsonSchema(
            description = "Ship following: seconds the follower must be adrift CONTINUOUSLY before the pursuit " +
                "is called off. Anything shorter than this is a leader manoeuvring, not a leader escaping -- a " +
                "hard turn swings the station point away faster than any hull can follow, and without this the " +
                "pursuit would die on the first corner. Reset to zero the moment the ship is back inside its " +
                "limit. Default 4.0."
        )
        var followBreakGrace = 4.0

        @JsonSchema(
            description = "Ship following: blocks of ground a distant pursuit may LOSE before it counts as " +
                "adrift. Only matters while further from station than followBreakRange, which is where a " +
                "long-range order starts: there the test is against the closest that pursuit has managed rather " +
                "than the flat limit, and this is the slack in it. Without slack any tick that ended a hair " +
                "further out than the best so far would break the pursuit off. Default 20.0."
        )
        var followBreakSlack = 20.0

        @JsonSchema(
            description = "Ship following: how far ahead the follower aims, in blocks, when steering off a " +
                "sideways error. It sets how hard a ship crabs toward its station: the aim is offset by the " +
                "sideways error at this distance, so an error equal to this one is a 45-degree lean and the " +
                "angle eases off as it closes. Higher = a lazier, straighter join; lower = a sharper cut in. " +
                "Being ahead of or behind station never steers at all -- that is the throttle's job. Default 30.0."
        )
        var followLookahead = 30.0

        @JsonSchema(description = "Ship following: commanded yaw rate per radian of heading error. Default 0.8.")
        var followTurnGain = 0.8

        @JsonSchema(
            description = "Ship following: how much of the yaw the ship ALREADY has is taken back out of the " +
                "turn demand. Steering on heading error alone is a spring with no damper -- the hull is still " +
                "swinging hardest at the moment the error reaches zero, so it sails past and comes back. Raise " +
                "it if a follower wallows from side to side; lower it if it is slow to answer the wheel. Note " +
                "that it also softens the steady turn rate, by 1/(1 + this). Default 0.6."
        )
        var followTurnDamping = 0.6

        @JsonSchema(
            description = "Ship following: m/s of closing speed asked for per block of along-track error. This " +
                "is the 'gradually come alongside' knob -- lower is a more patient approach. Default 0.2."
        )
        var followClosingGain = 0.2

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
            description = "Ship following: hold station with the two hulls' BOTTOMS level rather than their " +
                "centres, so a deep ship coming alongside a shallow one doesn't sit half its draught under " +
                "water -- and a formation in the air reads as ships on one deck. Off = the old centre-to-" +
                "centre height. Default true."
        )
        var followMatchKeel = true

        @JsonSchema(
            description = "Ship following: commanded climb/dive speed in m/s per block of altitude error, for " +
                "matching the leader's level. Default 0.5."
        )
        var followVerticalGain = 0.5

        @JsonSchema(
            description = "Ship following: altitude error in blocks below which no vertical is commanded at all. " +
                "Not just a nicety -- the water altitude hold latches its depth only while the commanded " +
                "vertical is exactly zero, so without a deadband a ship at station would flick the hold on and " +
                "off every tick. Default 2.0."
        )
        var followVerticalDeadband = 1.5

        @JsonSchema(
            description = "Ship following: how fast the commanded speed may RISE, in m/s per second. This is " +
                "the launch feel -- a follower ordered after a moving leader spools up at this rate instead of " +
                "zooming straight to the leader's speed. Slowing down is never rate-limited (braking beside " +
                "another hull must not wait on a ramp), and a huge value restores the old instant zoom. " +
                "Default 5.0."
        )
        var followAcceleration = 5.0

        @JsonSchema(
            description = "Ship following: leader speed in m/s below which a follower that has caught up stops " +
                "keeping station and CIRCLES the leader instead. Two ships following each other circle " +
                "regardless of this. Default 1.5."
        )
        var followCircleBelow = 2.0

        @JsonSchema(
            description = "Ship following: orbit speed in m/s while circling a stopped leader or a mutual " +
                "follow partner. Slowish reads best -- these are laps of honour, not attack runs. Default 4.0."
        )
        var followCircleSpeed = 4.0

        @JsonSchema(
            description = "Ship following: blocks of clear water held while circling, on top of the hulls' " +
                "own sizes. The radius is the LEADER's half-diagonal (the follower passes every bearing, " +
                "so the leader's longest reach must clear) plus the FOLLOWER's half-beam (it flies " +
                "tangentially, presenting its side), plus this. May be NEGATIVE to pull an orbit tight -- " +
                "it is floored so ships can never circle through each other. Ignores followGapMax, which " +
                "governs the alongside station rather than the orbit. Default 0.0."
        )
        var followCircleGap = 0.0

        @JsonSchema(
            description = "Ship following: the widest a circle may get, in blocks ACROSS (0 = no cap). This " +
                "is the lever for keeping two big ships orbiting inside gun range instead of drifting out " +
                "to a radius neither can shoot across -- the hull sizes still set the floor, so a cap " +
                "tighter than the ships themselves is refused rather than obeyed. Default 140.0."
        )
        var followCircleMaxDiameter = 140.0

        @JsonSchema(
            description = "Ship following: how far onto the leader's OTHER side a follower must end up, as a " +
                "fraction of the standoff, before it gives up its side and takes station on that one. This is " +
                "hysteresis: a follower sitting directly astern is on neither side, and without a margin it " +
                "would flip between port and starboard every tick. Raise it to make sides stickier. Default 0.5."
        )
        var followSideFlipMargin = 0.5
        // endregion

        // region Crew (Sneak+C -- see the org.valkyrienskies.eureka.crew package)
        // A ship helm is a villager workstation like a barrel or a fletching table, except that it employs more
        // than one villager: a ship needs a crew, not a shopkeeper. Berths are bought with Hearts of the Sea and
        // belong to the PLAYER, but are counted per ship -- so one captain's eight can crew two vessels at four
        // apiece. Nothing here is per ship category, so all of it lives on the base `server` block.

        @JsonSchema(
            description = "How many villagers one ship helm can employ at once. This is the point-of-interest " +
                "ticket count, and it is the one thing that makes a helm unlike every vanilla job site: a barrel " +
                "employs one villager, a helm employs a crew. Read ONCE at startup and baked into each helm's POI " +
                "record the first time that block is seen, so lowering it does NOT evict villagers from helms " +
                "that already exist. Beware the interaction with villages: an unemployed villager will notice a " +
                "job site 48 blocks away, so a helm docked near one can pull this many villagers off their own " +
                "workstations. Default 64."
        )
        var crewmanHelmPoiTickets = 64

        @JsonSchema(
            description = "How close, in blocks, a villager must get to a helm for it to count as having reached " +
                "its workstation. 1 is what every vanilla job site uses and is almost certainly what you want; " +
                "raising it mostly just lets crewmen work from further down the deck. Default 1."
        )
        var crewmanHelmPoiRange = 1

        @JsonSchema(
            description = "How many shipwrights one Shipwright's Bench can employ at once. Like the helm above " +
                "this is a point-of-interest ticket count, and it carries the same two warnings: it is read " +
                "ONCE at startup and baked into each bench's POI record the first time that block is seen, so " +
                "changing it needs a full restart and does NOT evict shipwrights from benches that already " +
                "exist; and an unemployed villager will notice a job site 48 blocks away, so a bench built " +
                "near a village can pull villagers off their own workstations. Only the middle block of the " +
                "bottom row is the job site, so this is the count for the whole desk and not for each of its " +
                "six blocks. Default 2."
        )
        var shipwrightsBenchPoiTickets = 2

        @JsonSchema(
            description = "How many crew a player can command on any ONE ship before offering a single Heart of " +
                "the Sea to a helm. The limit is per ship, not a global budget: with eight berths you can crew " +
                "one hull to six and another to eight. Raising this applies retroactively to anyone who has " +
                "never spent a heart. Default 4."
        )
        var crewSlotsBase = 4

        @JsonSchema(
            description = "The most berths any player can ever hold, and so the most hands any ONE crew can " +
                "muster -- a crew can never outgrow its captain's berths. Each Heart of the Sea offered to a " +
                "helm buys exactly one, so the default pair means a full crew costs 60 hearts. Default 64."
        )
        var crewSlotsMax = 64

        @JsonSchema(
            description = "Ender pearls charged per head when a crew is called to a ship they were not already " +
                "serving on. Calling the crew a wheel ALREADY keeps is free, however often -- so ordinary " +
                "sailing, reassembling a ship and letting a bottled ship out never cost anything. Paid out of " +
                "the ship's chests and barrels first, and only then out of the captain's own pockets. Set to 0 " +
                "to make swapping crews free. Default 1."
        )
        var crewPassagePearls = 1

        // region Cannons
        // The restock receipt used to be switched here, world-wide, with its own duration beside it. Both
        // moved to the client: `clientMessages.storesResults` and `.storesReceipts` for whether the two
        // lines appear, and `client.messageSeconds` for how long they hold. A HUD is a per-player thing,
        // and an operator deciding what every captain reads was the wrong shape for it.

        // A gunner picks how much powder goes behind the ball at the breech -- 1x, 2x or 3x -- and each of
        // the three carries its OWN four numbers, repeated below rather than scaled off one base arc.
        //
        // Scaling would have been less config to read, but it would also make the three levels the same
        // curve sampled at three points: steepening the light charge into a proper lob would flatten the
        // heavy one by the same act, because there would only ever be one arc. Independent sets are what
        // let 1x be a mortar and 3x a rifle, which is the entire reason a gunner is offered the choice.
        //
        // They interact within a level: speed sets the range, gravity sets how far the shot bends on the
        // way, and drag decides how quickly it stops being fast enough for gravity not to matter.
        //
        // Gravity, drag and reload ship IDENTICAL across the three so that out of the box only the muzzle
        // velocity differs -- a starting point that changes one thing rather than four. Tune from there.

        @JsonSchema(
            description = "1x charge (1 gunpowder): blocks a cannonball travels each tick. Sets the range and " +
                "how flat the shot looks. Default 3.5."
        )
        var cannonShotSpeed1x = 3.5

        @JsonSchema(
            description = "1x charge: blocks per tick squared the shot falls. The main control over the ARC -- " +
                "raise it to lob, lower it toward a straight line. Default 0.025."
        )
        var cannonShotGravity1x = 0.025

        @JsonSchema(
            description = "1x charge: fraction of its speed a shot keeps each tick. Below 1.0 it slows in " +
                "flight, which shortens the range and steepens the tail of the arc rather than changing its " +
                "start. 1.0 is no drag at all. Default 0.97."
        )
        var cannonShotDrag1x = 0.97

        @JsonSchema(
            description = "1x charge: seconds a cannon takes to reload. PER GUN -- a six-gun broadside fires " +
                "six times in this window -- so gun count, not this number, sets a ship's weight of fire. " +
                "Default 2.0."
        )
        var cannonReloadSeconds1x = 2.0

        @JsonSchema(description = "2x charge (2 gunpowder): blocks a cannonball travels each tick. Default 5.0.")
        var cannonShotSpeed2x = 5.0

        @JsonSchema(description = "2x charge: blocks per tick squared the shot falls. Default 0.05.")
        var cannonShotGravity2x = 0.05

        @JsonSchema(description = "2x charge: fraction of its speed a shot keeps each tick. Default 0.98.")
        var cannonShotDrag2x = 0.98

        @JsonSchema(description = "2x charge: seconds a cannon takes to reload, per gun. Default 4.0.")
        var cannonReloadSeconds2x = 4.0

        @JsonSchema(description = "3x charge (3 gunpowder): blocks a cannonball travels each tick. Default 6.5.")
        var cannonShotSpeed3x = 6.5

        @JsonSchema(description = "3x charge: blocks per tick squared the shot falls. Default 0.075.")
        var cannonShotGravity3x = 0.075

        @JsonSchema(description = "3x charge: fraction of its speed a shot keeps each tick. Default 0.99.")
        var cannonShotDrag3x = 0.99

        @JsonSchema(description = "3x charge: seconds a cannon takes to reload, per gun. Default 6.0.")
        var cannonReloadSeconds3x = 6.0

        @JsonSchema(
            description = "Seconds a cannonball may fly before it is spent and vanishes. The ceiling on " +
                "range for slow, low-gravity lobs: raise it and a shot can cross an ocean; the gunnery AI " +
                "still plans at most ten seconds ahead as a CPU guard, so crews simply will not USE arcs " +
                "longer than that -- hand-laid guns will. Default 24.0."
        )
        var cannonShotMaxFlightSeconds = 24.0

        @JsonSchema(
            description = "Whether a flying cannonball keeps the chunks along its path loaded and " +
                "simulating (the ender-pearl treatment vanilla gave pearls in 1.21.2). Off, a shot that " +
                "outruns the loaded area hangs frozen at the simulation edge until somebody comes near, " +
                "impact delayed accordingly. Costs chunk loads along the flight line of every airborne " +
                "shot; flip it live with /armada cannons chunk-loading. Default true."
        )
        var cannonballChunkLoading = true

        @JsonSchema(
            description = "How many cannonballs stack in an inventory slot, 1 to 99. Applied when items " +
                "register, so changing it needs a game restart. The guns' own magazines hold 64 a slot " +
                "regardless -- this is about hauling and hold space, which is where the balance lever " +
                "actually sits. Default 64."
        )
        var cannonballStackSize = 64

        @JsonSchema(
            description = "How many empty Ship Bottles stack in a slot, 1 to 64. Applied when items " +
                "register, so changing it needs a game restart. 16, like snowballs: a bottle is a whole " +
                "ship's worth of glasswork, not a trinket. (A FILLED bottle never merges with a bottle " +
                "holding a different ship regardless -- the ship rides the item's data.) Default 16."
        )
        var shipBottleStackSize = 16

        // Cannonball damage. Damage is a LADDER, not a range: the guaranteed blocks always go, then every
        // entry in the chances list is rolled independently for one more block -- so a round lands near its
        // floor most shots and touches its ceiling rarely. The list is written as whole percents,
        // "80,70,50": each entry is one extra block's chance, and HOW MANY entries there are is the most
        // the ladder can add. All of these read live, so a /reload retunes every round in the air and
        // every tooltip. The shipped numbers are tuned so each tier's average is guaranteed + (sum of
        // chances) -- keep that sum in mind when adjusting a rung, or a tier quietly changes power while
        // its printed range stays put.

        @JsonSchema(description = "Copper ball: blocks always destroyed. Default 1.")
        var cannonballCopperGuaranteed = 1

        @JsonSchema(description = "Copper ball: extra-block chances, in percent. Default \"75,50,25\" (range 1-4, average 2.5).")
        var cannonballCopperExtraChances = "75,50,25"

        @JsonSchema(description = "Copper incendiary round: surviving blocks set alight after the hole is made. Default 2.")
        var cannonballCopperIncendiary = 2

        @JsonSchema(description = "Iron ball: blocks always destroyed. Default 2.")
        var cannonballIronGuaranteed = 2

        @JsonSchema(description = "Iron ball: extra-block chances, in percent. Default \"80,70,50\" (range 2-5, average 4.0).")
        var cannonballIronExtraChances = "80,70,50"

        @JsonSchema(description = "Iron incendiary round: surviving blocks set alight. Default 3.")
        var cannonballIronIncendiary = 3

        @JsonSchema(description = "Steel ball: blocks always destroyed. Default 3.")
        var cannonballSteelGuaranteed = 3

        @JsonSchema(description = "Steel ball: extra-block chances, in percent. Default \"80,60,40,20\" (range 3-7, average 5.0).")
        var cannonballSteelExtraChances = "80,60,40,20"

        @JsonSchema(description = "Steel incendiary round: surviving blocks set alight. Default 4.")
        var cannonballSteelIncendiary = 4

        @JsonSchema(description = "Gold ball: blocks always destroyed. Default 2.")
        var cannonballGoldGuaranteed = 2

        @JsonSchema(description = "Gold ball: extra-block chances, in percent. The long descending tail is gold's whole character -- a low floor and a high, rare ceiling. Default \"90,85,75,65,50,25,10\" (range 2-9, average 6.0).")
        var cannonballGoldExtraChances = "90,85,75,65,50,25,10"

        @JsonSchema(description = "Gold incendiary round: surviving blocks set alight. Default 5.")
        var cannonballGoldIncendiary = 5

        @JsonSchema(description = "Netherite ball: blocks always destroyed. Default 6.")
        var cannonballNetheriteGuaranteed = 6

        @JsonSchema(description = "Netherite ball: extra-block chances, in percent. Default \"80,70,60,40,20,10\" (range 6-12, average 8.8).")
        var cannonballNetheriteExtraChances = "80,70,60,40,20,10"

        @JsonSchema(description = "Netherite incendiary round: surviving blocks set alight. Default 9.")
        var cannonballNetheriteIncendiary = 9

        @JsonSchema(
            description = "Explosive rounds: blocks added to every metal's guaranteed floor. The charge is " +
                "the same gunpowder whatever ball carries it, so the bonus is identical across metals. " +
                "Default 3."
        )
        var cannonExplosiveBonusGuaranteed = 3

        @JsonSchema(
            description = "Explosive rounds: extra-block chances added as their own short ladder, in " +
                "percent. Default \"60,30\" (so an explosive round is +2 guaranteed and up to +4 total)."
        )
        var cannonExplosiveBonusChances = "60,30"

        @JsonSchema(
            description = "Explosive rounds: whether the charge craters as a SPHERE at the point of impact " +
                "instead of eating its way through whatever it reached. On, the rolled block count buys a " +
                "radius and everything destructible inside it goes -- a shell buried in a hillside spends " +
                "about its whole count, one that lands on the surface leaves a crater of the same width with " +
                "only the ground half taken, which is how TNT and creepers read. Off, an explosive round goes " +
                "back to the solid shot's flood fill, which bores rather than craters. Flip it live with " +
                "/armada cannonballs explosive sphere. Default true."
        )
        var cannonExplosiveBlastSphere = true

        @JsonSchema(
            description = "Explosive rounds: blast radius in blocks, overriding the roll. Zero lets the " +
                "rolled block count choose the radius, which is what keeps the damage ladders meaningful; " +
                "set a number here and every explosive round bursts to exactly that width whatever it rolled " +
                "and whatever metal carried it. Default 0.0."
        )
        var cannonExplosiveBlastRadius = 0.0

        @JsonSchema(
            description = "Explosive rounds: the largest blast radius allowed, however big the roll or the " +
                "override. A burst is a cube's worth of block reads and writes inside a single tick, so this " +
                "is the line past which one shell would stall the server rather than damage it. Set far above " +
                "anything the shipped ladders can reach -- past about 12 the carve is already a visible hitch. " +
                "Default 24.0."
        )
        var cannonExplosiveMaxBlastRadius = 24.0

        @JsonSchema(
            description = "Armor-piercing rounds: each strike's share of the OPENING hit's roll, in " +
                "percent, first entry first. How many entries there are is how many times the round " +
                "strikes before it is spent; every share is of the first roll, never of the last strike, " +
                "and no strike ever takes less than one block. Default \"100,75,50,25\" (four strikes, " +
                "losing steam)."
        )
        var cannonArmorPiercingStrikePercents = "100,75,50,25"

        @JsonSchema(
            description = "Whether a cannonball exploding against WORLD terrain pushes the crater into " +
                "Voxy's distant LODs right away (ship hits never do; disassembly already has its own " +
                "path). Costs a chunk re-ingest per touched chunk per volley, so it is a toggle -- flip " +
                "it live with /armada cannons voxy-lod. Does nothing when Voxy is not installed. " +
                "Default true."
        )
        var cannonballVoxyLodUpdates = true
        // endregion

        @JsonSchema(
            description = "Whether fire on an assembled ship behaves like fire anywhere else. Off (the " +
                "default), a fire aboard a ship burns away only the block it is actually attached to and " +
                "then goes out -- it never lights a neighbour and never leaves a new fire behind, so an " +
                "incendiary round costs you hull rather than the whole vessel. Turn it on for vanilla " +
                "fire, and expect a wooden ship to burn to the waterline. Ships only; fire ashore is " +
                "never touched. Default false."
        )
        var shipFireSpreads = false

        // How long a contained fire takes to eat through what it caught on. Each fire rolls its own
        // duration once, uniformly between these two, so a burning ship reads as a scatter of holes
        // opening at different moments rather than a row of them going at once. Ignored entirely when
        // shipFireSpreads is on, since vanilla then owns the timing.

        @JsonSchema(
            description = "Fewest seconds a fire on a ship takes to burn through the block it is attached " +
                "to. Default 3.0."
        )
        var shipFireBurnSecondsMin = 3.0

        @JsonSchema(
            description = "Most seconds a fire on a ship takes to burn through the block it is attached to. " +
                "Raise both of these to make a hull fire something a crew can answer, lower them to make an " +
                "incendiary round bite immediately. Default 20.0."
        )
        var shipFireBurnSecondsMax = 20.0

        // The fire party (see the crew package's FireBrigade). Note the whole watch only sees fires while
        // shipFireSpreads is OFF -- the containment bookkeeping it reads from is what tracks them -- so with
        // vanilla spreading on, the crew stand by while the ship burns, which is arguably also realistic.

        @JsonSchema(
            description = "How far along the deck, in blocks of horizontal distance, a firefighter notices a " +
                "fire from wherever they happen to be standing. Default 28.0."
        )
        var fireWatchHorizontalBlocks = 28.0

        @JsonSchema(
            description = "How far above or below them, in blocks, a firefighter notices a fire. Deliberately " +
                "much shorter than the horizontal reach so a flame at the masthead does not send the whole " +
                "party up the rigging -- burning sails are nobody's job. Default 14.0."
        )
        var fireWatchVerticalBlocks = 14.0

        @JsonSchema(
            description = "Within this many blocks of the flame, in any direction, a firefighter is close " +
                "enough to put it out -- a fire one deck below their feet is doused through the planks. " +
                "Default 4.0."
        )
        var fireWatchDouseBlocks = 4.0

        @JsonSchema(
            description = "Seconds a firefighter pauses after dousing a fire before making for the next one. " +
                "Default 2.0."
        )
        var fireWatchRestSeconds = 2.0

        @JsonSchema(
            description = "How many sets of ship plans a player can keep with shipwrights before buying more. " +
                "Plans are held per player and readable at every bench in the world, not per bench. Default 3."
        )
        var shipwrightSlotsStart = 3

        @JsonSchema(
            description = "The most sets of ship plans any player can ever hold. Each Heart of the Sea offered " +
                "to a Shipwright's Bench buys exactly one, so the default pair means a full shelf costs 29 " +
                "hearts. Deliberately the same currency and ceiling as crew berths. Default 32."
        )
        var shipwrightSlotsMax = 32

        @JsonSchema(
            description = "Whether shipwrights take repair work at all. Off, the book has no Yard page and " +
                "the bench builds only. Default true."
        )
        var shipwrightRepair = true

        @JsonSchema(
            description = "Whether a shipwright will start mending before the whole repair bill is paid. On, " +
                "the Repair button works with whatever has been handed over, and blocks go back keel-up -- " +
                "lowest first, bow to stern, port to starboard -- until the pot runs dry. Off, a repair is " +
                "all-or-nothing as a build is. Default true."
        )
        var shipwrightPartialRepair = true

        @JsonSchema(
            description = "Whether a Shipwright-profession villager can be signed on as crew. Off, Sneak+C " +
                "refuses them -- a shipwright's place is the bench. Default true."
        )
        var shipwrightCrew = true

        @JsonSchema(
            description = "How much of a hull must still match a set of plans before a shipwright accepts it " +
                "as the same vessel, as a percentage, 1 to 99. Counted as a total of the plans' non-air " +
                "blocks, not per block-type. Lower it to let a shipwright take on a wreck; raise it to stop " +
                "one set of plans being used to rebuild a different ship. Clamped: anything at or below 0 " +
                "reads as 1, anything at or above 100 reads as 99 -- 0 would let any hull pass as any ship, " +
                "and 100 would accept only a hull with nothing wrong with it. Default 20."
        )
        var shipwrightRepairPercentage = 20

        @JsonSchema(
            description = "How far from a Shipwright's Bench, in blocks, a shipwright can see and work on a " +
                "ship. Every assembled hull inside it is listed at once, so a captain can moor a whole " +
                "armada off a harbor and have all of it in the book -- and an armada's children come along " +
                "whenever their parent is in range, however far out they trail. Default 100."
        )
        var shipwrightRepairBlockRange = 100.0

        @JsonSchema(
            description = "Whether right-clicking a shipwright VILLAGER opens the shipwright's book. Off, " +
                "the villager waves you toward a bench instead -- but still files blueprints and still " +
                "sells shelf space, so progression never dead-ends. Default true."
        )
        var shipwrightVillagerAccess = true

        @JsonSchema(
            description = "Whether right-clicking a Shipwright's Bench empty-handed opens the shipwright's " +
                "book directly, no villager needed. The bench uses its own range " +
                "(shipwrightBenchBlockRange), so a bench on the pier and the villager wandering behind it " +
                "each see their own yard. Default true."
        )
        var shipwrightBenchAccess = true

        @JsonSchema(
            description = "How far from a Shipwright's Bench, in blocks, the BENCH itself can see and work " +
                "on a ship when opened directly (shipwrightBenchAccess). The villager's own reach stays " +
                "shipwrightRepairBlockRange. Default 100."
        )
        var shipwrightBenchBlockRange = 100.0

        // region Altering a set of plans
        // A captain may strike decoration off a design and build the hull without it, and may build a design
        // out of a different material of the same kind. Both are lenses over the census as filed -- the page
        // itself never changes -- so anything switched off here simply stops being offered.

        @JsonSchema(
            description = "Whether a captain may leave decoration and furniture off a design when the " +
                "shipwright builds it. Off, every block on the page must be paid for. Default true."
        )
        var shipwrightExclude = true

        @JsonSchema(
            description = "Whether a required material may be swapped for another of the SAME KIND -- any " +
                "slab for any slab, any whole block for any whole block. Never across kinds: a slab does " +
                "not become a plank. Default true."
        )
        var shipwrightSwapMaterials = true

        @JsonSchema(
            description = "Whether the swap above also applies to STRUCTURE -- planks, logs, stone, the hull " +
                "itself -- rather than only to decoration and furniture. Default true: rebuilding a design " +
                "in another wood is most of the point of being able to swap a material at all."
        )
        var shipwrightSwapFoundational = true

        @JsonSchema(
            description = "Whether a shipwright will break a ship up for its materials. The hull is " +
                "counted into a claim list the captain draws down at their own pace -- nothing is " +
                "dropped -- but the ship itself is gone and cannot be brought back. Default true."
        )
        var shipwrightDismantle = true

        // The dismantle fee. A shipwright breaking a ship up is doing a job, and the yard charges for it by
        // the size of the hull -- so scrapping a raft is free and scrapping a first-rate is a real decision.
        //
        // Charged against the hull's LIVE block count, walked at the moment the book is opened, not against
        // her plans or the count she was assembled at. A ship shot half to pieces is half a ship, and the
        // bill says so. Cargo is not counted: the coal in her engines and the wheat in her hold are not
        // hull, and the claim list hands them straight back anyway.
        //
        // Paid out of the captain's own pockets. Two items are provided because one currency is a rule and
        // two is an economy -- leave the second blank for the ordinary case.

        @JsonSchema(
            description = "How many blocks of hull one unit of the dismantle fee covers. A hull is charged " +
                "one unit per WHOLE multiple and never rounds up, so at the default 1000 a 1278-block ship " +
                "pays 1 and a 19,879-block ship pays 19. 0 or less makes dismantling free. Default 1000."
        )
        var shipwrightDismantleFeeBlocks = 1000

        @JsonSchema(
            description = "Hulls smaller than this many blocks are broken up for nothing -- a rowboat is not " +
                "worth a shipwright's invoice. Default 1000, which lines up with the unit size so the first " +
                "unit is also the first charge. Set it to 0 to charge for EVERYTHING: a 30-block raft still " +
                "owes nothing by the unit maths, so it pays the minimum of one unit instead, which is how a " +
                "world makes even the smallest scrapping cost something."
        )
        var shipwrightDismantleFeeFreeBelow = 1000

        @JsonSchema(
            description = "The item a dismantle fee is paid in. Blank charges nothing. Default " +
                "minecraft:emerald -- at one per 1000 blocks and a 50,000-block assembly ceiling, the most " +
                "a ship can ever cost to scrap is 50."
        )
        var shipwrightDismantleFeeItem = "minecraft:emerald"

        @JsonSchema(
            description = "How many of shipwrightDismantleFeeItem one unit costs. Default 1."
        )
        var shipwrightDismantleFeeCount = 1

        @JsonSchema(
            description = "A SECOND item charged alongside the first, for worlds that want a dismantle to " +
                "cost more than one currency. Blank -- the default -- charges only the first."
        )
        var shipwrightDismantleFeeItem2 = ""

        @JsonSchema(
            description = "How many of shipwrightDismantleFeeItem2 one unit costs. Default 0."
        )
        var shipwrightDismantleFeeCount2 = 0
        // endregion

        // region Commissioning a ship
        // The build fee. Priced exactly as the dismantle fee is, off the size of the hull, and defaulting to
        // the same numbers -- a shipwright's labour is a shipwright's labour whichever direction it runs in.
        //
        // Charged UP FRONT, which is the one place the two differ. A dismantle is paid for after the hull is
        // gone, because charging for a job that did not happen is unforgivable; a build is paid for before
        // the first plank changes hands, so the yard never sits on a hull's worth of timber against an
        // unpaid invoice. Once paid it is not charged again however many trips the materials take, and it
        // is owed afresh the next time the same plans are commissioned.
        //
        // Quoted off the plans as DRAWN. Striking the decor off lowers the materials owed and not the fee:
        // the count on the card is the count in the quote, and a hull is the same size to build whatever a
        // captain decides to leave out of it.
        //
        // Paid out of the captain's own pockets. Two items are provided because one currency is a rule and
        // two is an economy -- leave the second blank for the ordinary case.

        @JsonSchema(
            description = "How many blocks of hull one unit of the build fee covers. A hull is charged one " +
                "unit per WHOLE multiple and never rounds up, so at the default 1000 a 2999-block ship pays " +
                "2 and a 19,879-block ship pays 19. 0 or less makes building free. Default 1000."
        )
        var shipwrightBuildFeeBlocks = 1000

        @JsonSchema(
            description = "Hulls smaller than this many blocks are built for nothing -- a rowboat is not " +
                "worth a shipwright's invoice. Default 1000, which lines up with the unit size so the first " +
                "unit is also the first charge. Set it to 0 to charge for EVERYTHING: a 30-block raft still " +
                "owes nothing by the unit maths, so it pays the minimum of one unit instead."
        )
        var shipwrightBuildFeeFreeBelow = 1000

        @JsonSchema(
            description = "The item a build fee is paid in. Blank charges nothing -- which is what a world " +
                "wanting the old materials-only shipwright sets. Default minecraft:emerald."
        )
        var shipwrightBuildFeeItem = "minecraft:emerald"

        @JsonSchema(
            description = "How many of shipwrightBuildFeeItem one unit costs. Default 1."
        )
        var shipwrightBuildFeeCount = 1

        @JsonSchema(
            description = "A SECOND item charged alongside the first, for worlds that want a build to cost " +
                "more than one currency. Blank -- the default -- charges only the first."
        )
        var shipwrightBuildFeeItem2 = ""

        @JsonSchema(
            description = "How many of shipwrightBuildFeeItem2 one unit costs. Default 0."
        )
        var shipwrightBuildFeeCount2 = 0
        // endregion

        // region Ship damage repercussions
        // Integrity = the ship's current block count as a percentage of its count at assembly, so 100 is
        // pristine and the numbers below are integrity thresholds, not damage amounts. Maintained live: every
        // block shot off, burned away or mined lowers it the moment it happens; repairs raise it the same way.

        @JsonSchema(
            description = "Whether damage slows, sinks and eventually ungoverns a ship at all. Off, a hull " +
                "shot half to pieces sails exactly like a new one. Default true."
        )
        var shipDamageRepercussions = true

        @JsonSchema(
            description = "Ship integrity (percent of its assembled block count) at which damage starts to " +
                "cost speed. Default 95 -- a ship feels its first scratches almost at once, which is what " +
                "makes the readout worth watching."
        )
        var damageSpeedLossStart = 95

        @JsonSchema(
            description = "Ship integrity at which the speed loss reaches its full damageSpeedLossMaxPercent " +
                "-- the loss ramps linearly between this and damageSpeedLossStart, and worse damage costs no " +
                "more. Default 45, which is a few points above the freefall line: a ship is at her slowest " +
                "for a little while before she stops answering at all."
        )
        var damageSpeedLossFull = 45

        @JsonSchema(
            description = "The most speed a damaged ship can lose, as a percent of its top speed. Default 50."
        )
        var damageSpeedLossMaxPercent = 50

        @JsonSchema(
            description = "Ship integrity at which a damaged ship starts to settle -- airships lose altitude, " +
                "boats lose buoyancy while their keel is in water. The rate ramps linearly from nothing here " +
                "to damageSinkMaxMetersPerSecond at damageSinkFull. Ascend still applies its full force, so " +
                "a ship with the power can fight the descent while repairs are made. Default 85."
        )
        var damageSinkStart = 85

        @JsonSchema(
            description = "Ship integrity at which the settle rate reaches its full " +
                "damageSinkMaxMetersPerSecond; worse damage sinks no faster. Default 45."
        )
        var damageSinkFull = 45

        @JsonSchema(
            description = "The fastest a damaged ship settles, in m/s, reached at damageSinkFull. Default 2.5."
        )
        var damageSinkMaxMetersPerSecond = 2.5

        @JsonSchema(
            description = "Below this integrity the ship goes ungoverned -- it ragdolls exactly as if every " +
                "helm aboard were broken, and cannot be driven until repaired back to this line, at which " +
                "point the gyro rights it and the wheel answers again. Default 45."
        )
        var damageFreefallBelow = 45

        @JsonSchema(
            description = "Whether a hull going ungoverned breaks every wheel aboard her and turns her " +
                "gunners off their guns. A ship that can no longer answer her helm has no further use for " +
                "one, and a crew still sighting down a barrel while the deck falls away reads as a bug. " +
                "Happens ONCE per descent, so a helm placed on a falling hull -- a boarder claiming a prize " +
                "-- is not smashed the moment it goes down. Applies to pirates and players alike. " +
                "Default true."
        )
        var damageFreefallBreaksHelm = true

        // A pirate hull answers to its own set of the same five numbers, because a pirate ship is a PRIZE:
        // the whole reason to shoot one is to take it, so the line where she stops answering her wheel is
        // the line where a boarding party can reach her -- not the line where a captain's own ship would
        // give up. Which set applies is decided by the WHEEL aboard, never by who is standing at it. A
        // pirate-marked helm makes the hull a pirate; breaking that wheel is what conquering one means,
        // and from that moment the prize sails under the player numbers above, repairs and all.

        @JsonSchema(
            description = "Whether pirate ships answer to their own damage thresholds (the pirateDamage* " +
                "keys below) rather than the player-ship ones above. Off, one set of numbers governs every " +
                "hull afloat. Default true."
        )
        var pirateDamageOwnThresholds = true

        @JsonSchema(
            description = "Pirate-ship integrity at which damage starts to cost speed. Default 100 -- a " +
                "raider pays for her damage from the first plank, so a broadside tells on her at once."
        )
        var pirateDamageSpeedLossStart = 100

        @JsonSchema(
            description = "Pirate-ship integrity at which the speed loss reaches its full " +
                "pirateDamageSpeedLossMaxPercent. Default 65, a few points above the freefall line: she " +
                "is already at her slowest for the last of her integrity, and still steering, before she " +
                "stops answering at all."
        )
        var pirateDamageSpeedLossFull = 65

        @JsonSchema(
            description = "The most speed a damaged pirate ship can lose, as a percent of her top speed. " +
                "Default 50."
        )
        var pirateDamageSpeedLossMaxPercent = 50

        @JsonSchema(
            description = "Pirate-ship integrity at which she starts to settle. Default 75."
        )
        var pirateDamageSinkStart = 75

        @JsonSchema(
            description = "Pirate-ship integrity at which the settle rate reaches its full " +
                "pirateDamageSinkMaxMetersPerSecond. Default 65."
        )
        var pirateDamageSinkFull = 65

        @JsonSchema(
            description = "The fastest a damaged pirate ship settles, in m/s. Default 2.5."
        )
        var pirateDamageSinkMaxMetersPerSecond = 2.5

        @JsonSchema(
            description = "Below this integrity a pirate ship goes ungoverned and ragdolls -- which is what " +
                "conquering one by gunfire means. Default 60: a player's ship must be shot to 45 before " +
                "she is anyone's, a raider only to 60, so a prize can be taken before she is scrap."
        )
        var pirateDamageFreefallBelow = 60

        @JsonSchema(
            description = "Below this integrity a pirate ship's wheel BREAKS ITSELF, which is the same " +
                "thing as being conquered by gunfire: the hull founders, comes apart where it lies, and " +
                "hands its berth back to the regeneration clock. Without it a raider shot to pieces at sea " +
                "simply drifts -- ungoverned, still assembled, waiting forever for a wheel nobody is coming " +
                "to break. Default 60, five points below pirateDamageFreefallBelow, so she goes ungoverned " +
                "first and is visibly dying before she gives up. 0 disables it."
        )
        var pirateHelmBreaksBelow = 60
        // endregion

        // region Helm-less foundering
        // A ship that loses its last wheel dies: it ragdolls (an airship falls), water drains its buoyancy
        // to nothing however many floaters it carries, it sinks slow under drag, and once it comes to rest
        // -- seabed or hillside -- it is taken apart where it lies. Placing a helm aboard at ANY point,
        // underwater included, makes it a working ship again on the very next tick. Applies to every ship,
        // the pirates' and the players' alike.

        @JsonSchema(
            description = "Whether a ship whose last helm breaks is given a grace period before the dying " +
                "starts. During the grace she rides on exactly as she was -- bobbing on the water, hanging " +
                "in the air. Off, the old behaviour: instant ragdoll and free fall. Default true."
        )
        var helmlessGraceEnabled = true

        @JsonSchema(
            description = "How long that grace lasts, in seconds. 0 means INDEFINITE: the ship never " +
                "founders and never breaks up, floating derelict until a helm returns. (Pirate conquests " +
                "use their own window, pirateConquestFreezeMinutes.) Default 15.0."
        )
        var helmlessGraceSeconds = 15.0

        @JsonSchema(
            description = "How long a helm-less hull touching water takes to lose ALL its buoyancy, in " +
                "seconds. Floaters, balloons and honest wood buy nothing here -- without a wheel the sea " +
                "always wins, only this slowly. Default 10.0."
        )
        var helmlessBuoyancyLossSeconds = 10.0

        @JsonSchema(
            description = "How long a helm-less hull must rest -- on the seabed, on the ground an airship " +
                "dropped onto -- before it breaks up into blocks where it lies, in seconds. The last " +
                "window to slap a helm on and save her. Default 10.0."
        )
        var helmlessSettleSeconds = 10.0
        // endregion

        // region Ship wrecks
        // A ship SHOT DOWN is a different thing from one whose wheels were merely broken, and only the first
        // gets any of this. There are two ways to be shot down: her integrity falls past damageFreefallBelow,
        // or she loses her WHEEL to an enemy and nobody claims her. The one death that is NOT a wreck is a
        // captain unmaking her own ship -- mine the wheel out of a hull you built and she comes apart the
        // way she always did.
        //
        // A wreck falls exactly as any dying hull falls, on her own collision, so the deck stays solid
        // underfoot the whole way down and riders still fall with her. What changes is where she ENDS UP.
        // As she breaks up she is laid onto her side and put UNDER the ground she came to rest on, so her
        // blocks merge with the landscape -- half in the sand, coral standing through her deck -- instead of
        // punching a ship-shaped hole in it.
        //
        // How far under is measured by a notional box far smaller than the hull: think of it as the only
        // part of her still solid enough to hold her weight, and she settles until THAT would have grounded.
        // The burial scales with the ship, so a first-rate goes most of a deck deeper than a raft does.

        @JsonSchema(
            description = "Whether a shot-down ship is laid on her side and buried when she breaks up. Off, " +
                "she comes apart upright and on the surface, exactly as a ship disassembled at her own wheel " +
                "does. A ship whose helms were merely broken is unaffected either way. Default true."
        )
        var wreckBurialEnabled = true

        @JsonSchema(
            description = "The wreck box for a small hull -- width, height, length in blocks, the length " +
                "laid along the hull's longer horizontal span. Deliberately far smaller than the ship: the " +
                "gap between the two is exactly how deep she buries herself. Default [1, 2, 3]."
        )
        var wreckBoxSmall: List<Int> = listOf(1, 2, 3)

        @JsonSchema(
            description = "The wreck box for a medium hull -- a fishing boat, a small raider. " +
                "Default [1, 3, 5]."
        )
        var wreckBoxMedium: List<Int> = listOf(1, 3, 5)

        @JsonSchema(
            description = "The wreck box for a large hull. Default [2, 5, 7]."
        )
        var wreckBoxLarge: List<Int> = listOf(2, 5, 7)

        @JsonSchema(
            description = "Assembled block count at or above which a wreck uses wreckBoxMedium rather than " +
                "wreckBoxSmall. Default 5000."
        )
        var wreckBoxMediumMinBlocks = 5000

        @JsonSchema(
            description = "Assembled block count at or above which a wreck uses wreckBoxLarge. Default 15000."
        )
        var wreckBoxLargeMinBlocks = 15000

        @JsonSchema(
            description = "How much of a wreck ends up UNDER the ground she came to rest on, as a fraction " +
                "of her height once she is laid on her side. 0.5 -- the default -- puts the surface through " +
                "her middle: half of her showing, half of her in the seabed. Measured against her ROLLED " +
                "height, so a ship 36 blocks wide that tips over shows about 18 of them whatever her masts " +
                "were doing beforehand. Push it past 0.6 and hulls start disappearing entirely."
        )
        var wreckBurialFraction = 0.5

        @JsonSchema(
            description = "How far a wreck is rolled onto her side as she breaks up, in degrees. Rounded to " +
                "a multiple of 90, because only those keep her blocks on the world grid. 0 lays her down " +
                "level -- buried, but still the right way up. Note that block STATES are never rolled: a " +
                "capsized hull keeps every stair, ladder and door facing the way it faced in the shipyard, " +
                "and vanilla quietly knocks off whatever no longer has anything to hang on. Default 90."
        )
        var wreckRollDegrees = 90

        @JsonSchema(
            description = "What a ship loses on the way down, so that salvaging a wreck is worth less than " +
                "taking the ship -- which is the whole argument for boarding one and putting your own wheel " +
                "on her instead of sinking her. Each line is a block id or a #block tag, then a space, then " +
                "a PERCENT chance that block is destroyed rather than laid down. Rolled independently per " +
                "block, so a wreck comes up ragged and holed rather than evenly thinned. Read IN ORDER and " +
                "the first matching line wins, because tags overlap and JSON guarantees no ordering. " +
                "Applies ONLY to a sunk ship: a captain taking her own hull apart at the wheel, underwater " +
                "or otherwise, loses nothing. An empty list disables it."
        )
        var wreckShatterChances: List<String> = listOf(
            "#vs_eureka:balloons 60",
            "#minecraft:wool 60",
            "#vs_eureka:wreck_lights 50",
            "vs_eureka:engine 40",
            "minecraft:chest 30",
            "minecraft:trapped_chest 30",
            "#vs_eureka:wreck_wood 20"
        )

        @JsonSchema(
            description = "How little a wreck must move, in blocks per second, to count as having touched " +
                "down. Looser than the helm-less settle test on purpose: a wreck is not being waited on to " +
                "see whether she can be saved, only to see where she finally lies. Default 0.35."
        )
        var wreckLandedEpsilon = 0.35

        @JsonSchema(
            description = "How long after touching down a wreck waits before breaking up, in seconds. The " +
                "clock starts on first contact and never restarts, so a hull that keeps sliding still comes " +
                "apart on schedule. She will still wait beyond this for anyone aboard to leave. Default 15.0."
        )
        var wreckGroundTimerSeconds = 15.0

        @JsonSchema(
            description = "How far outside a wreck, in blocks, still counts as being aboard her for the " +
                "purpose of holding off the break-up. Generous because a sunken wreck is looted by SWIMMING " +
                "through it, and a swimmer never registers as standing on a deck. Default 5.0."
        )
        var wreckPlayerInfluenceMargin = 5.0

        @JsonSchema(
            description = "Whether a pirate's wheel is proof against her own damage while any of her crew " +
                "still live -- so a raider must be emptied of hands before she can be shot down. Off, she " +
                "gives out on integrity alone as before. Default true."
        )
        var wreckPirateHelmNeedsCrewDead = true

        @JsonSchema(
            description = "Whether the one-minute conquest window happens only when a player is actually " +
                "aboard as the wheel breaks. On, a prize nobody boarded starts falling at once and is a " +
                "wreck to be salvaged rather than a hull to be claimed. Off, every conquest hangs for the " +
                "full window whether or not anyone came. Default true."
        )
        var wreckPirateHoldNeedsPlayer = true
        // endregion

        // region Pirate ships
        //
        // Generated pillager ships: how often they are placed, which hulls they draw from, proximity
        // zones, the 20-second warning, wake-up and pursuit. RARITY and the hull MIX are vanilla worldgen
        // data, so the JSON under data/vs_eureka/worldgen carries the shipped defaults and PirateWorldgen
        // writes these over the loaded registries as the server starts, before any chunk is generated --
        // a change therefore takes effect next launch, and only on ground generated after it. Note that
        // turning pirateShipsEnabled off stops the manager
        // (no zones, no wake-ups) but does not un-gate already-placed pirate helms: the black wheel is a
        // blockstate fact. /vs pirate set-mark reclaims one by hand if it comes to that.

        @JsonSchema(
            description = "Master switch for the pirate-ship machinery: berth adoption, proximity zones, " +
                "wake-up and pursuit. Generated hulls still appear in new chunks either way (that is " +
                "worldgen data, not code); with this off they simply never wake. Default true."
        )
        var pirateShipsEnabled = true

        @JsonSchema(
            description = "How rare generated pirate ships are: the side, in CHUNKS, of the grid cell each " +
                "one is placed in. Bigger is rarer. For scale, vanilla spaces a village at 34 and a " +
                "woodland mansion at 80. Takes effect on the next launch, and only in chunks generated " +
                "after it -- ground that already exists keeps the ships it was born with. Default 30."
        )
        var pirateShipSpacing = 30

        @JsonSchema(
            description = "The minimum gap, in CHUNKS, between two generated pirate ships -- how far into " +
                "its own cell a hull may be nudged. Must be less than pirateShipSpacing and is clamped " +
                "below it if it is not, because vanilla divides by the difference. The closer the two " +
                "numbers, the more evenly spread and less clustered the ships. Default 10."
        )
        var pirateShipSeparation = 10

        @JsonSchema(
            description = "Pirate proximity sphere: radius = the hull's horizontal half-diagonal times this. " +
                "Bigger ships see further, exactly as the follow reach does. A ship counts as inside the " +
                "moment any part of its hull crosses the line, not just its centre. Default 3.0."
        )
        var pirateZoneScale = 3.0

        @JsonSchema(
            description = "Pirate proximity sphere: the radius never shrinks below this many blocks, however " +
                "small the hull. Default 64.0."
        )
        var pirateZoneMinRadius = 64.0

        // REMOVED: pirateZoneClampToSimulationDistance.
        //
        // It capped every ring at the player's simulation distance, because a sleeping site could only
        // notice anyone while its wheel was ticking and a wheel ticks only inside that distance. The cap was
        // honest then and is obsolete now: sleeping sites are found in the PERSISTED store and measured
        // against the player directly, with no wheel and no loaded chunk (see `PirateShips.scanZones`).
        //
        // Worth remembering WHY it had to go rather than merely defaulting off: while it was on it silently
        // ate `pirateZoneScale`, so raising the scale did nothing on any ordinary simulation distance. A
        // setting that appears broken is worse than one that is documented as overridden, and leaving the
        // lever in place would have left the trap in place with it. Jackson ignores unknown keys, so an
        // existing vs_eureka.json carrying the old entry loads fine and the entry is dropped on next write.

        @JsonSchema(
            description = "How many seconds a player standing inside a pirate ship's zone is given to get " +
                "clear before the ship assembles and gives chase. Default 15."
        )
        var pirateCountdownSeconds = 15

        @JsonSchema(
            description = "Hard cap on pirate ships ASSEMBLED at once, server-wide. Assembly is " +
                "the heaviest thing this feature does unattended, so the cap is a safety rail, not a " +
                "difficulty dial. Default 3."
        )
        var pirateMaxAssembled = 3

        @JsonSchema(
            description = "Global cooldown between any two pirate wake-ups, in seconds, so a fleet of " +
                "dormant ships cannot all assemble in the same breath. Default 15.0."
        )
        var pirateAssemblyCooldownSeconds = 15.0

        @JsonSchema(
            description = "How long a pirate whose pursuit broke stays assembled, in minutes, waiting for " +
                "its quarry to wander back. A trespasser in the zone inside this window resumes the " +
                "pursuit at once, with no countdown; when it closes the ship disassembles itself and the " +
                "site goes back to the full 15-second ceremony. Default 2.0."
        )
        var pirateLingerMinutes = 2.0

        @JsonSchema(
            description = "An assembled pirate ship with no player within this many blocks disassembles " +
                "itself IMMEDIATELY, linger or no linger -- an assembled ship is live physics, and physics " +
                "for an audience of nobody is pure cost. Default 400.0."
        )
        var pirateStandDownRange = 400.0

        @JsonSchema(
            description = "How long after a pirate crew's last member falls before a fresh complement " +
                "respawns on the deck, in minutes -- while the wheel still stands. Killing the crew turns " +
                "the wheel white and breakable; this is the window to break it. Default 5.0."
        )
        var pirateRespawnMinutes = 5.0

        @JsonSchema(
            description = "How long a conquered pirate ship hangs FROZEN in place after its wheel breaks, " +
                "in minutes. Placing any helm aboard inside this window claims the vessel, guns and all; " +
                "letting it close cuts her loose to founder, settle on the seabed, and break up. Default 0.25."
        )
        var pirateConquestFreezeMinutes = 0.25

        @JsonSchema(
            description = "How long a conquered site waits before regenerating a fresh pirate ship, in " +
                "MINECRAFT DAYS on the day clock -- conquer one at dawn and its replacement arrives next " +
                "dawn. /time set walks the wait forward; setting time backwards re-anchors rather than " +
                "freezing the site. Default 1.0."
        )
        var pirateRegenDays = 1.0

        @JsonSchema(
            description = "A site will not regenerate while a player is within this many blocks of it -- " +
                "no ship materialising on somebody's head. Default 16.0."
        )
        var pirateRegenPlayerClearRadius = 16.0

        @JsonSchema(
            description = "The hulls a regenerating site draws from -- template names under " +
                "data/vs_eureka/structures/, WEIGHTED: 'pirate/sloop*3' draws three times as often as " +
                "'pirate/brig', and a bare name is weight 1. Keep names AND weights in step with the " +
                "worldgen template_pool JSON (data/vs_eureka/worldgen/template_pool/pirate/ships.json), " +
                "which vanilla reads separately for first generation. Default " +
                "[pirate/pilpirsmall1*60, pirate/pilpirmedium1*35, pirate/pilpirlarge1*10]."
        )
        var pirateHulls: List<String> = listOf(
            "pirate/pilpirsmall1*60",
            "pirate/pilpirmedium1*35",
            "pirate/pilpirlarge1*10"
        )

        @JsonSchema(
            description = "Pirate ships fight back: guns with living mounted gunners solve real arcs and " +
                "fire on any ship carrying a player inside engage range. Off restores the toothless chase. " +
                "Default true."
        )
        var pirateGunneryEnabled = true

        @JsonSchema(
            description = "Ticks between one pirate gun speaking and the next -- the rolling-broadside " +
                "rhythm, same feel as a crew volley's stagger. Also the AI's whole rate limit: one gun " +
                "per this many ticks, per ship. Default 10."
        )
        var pirateCannonStaggerTicks = 10

        @JsonSchema(
            description = "How long a pirate gun takes to reload while her crew are firing at will, in " +
                "seconds -- REPLACING the powder-derived reload every gun would otherwise keep. It is what " +
                "sets a raider's weight of fire: the stagger above only decides which gun speaks next, " +
                "while this decides how often each one can. 0 or less leaves every gun on its own reload. " +
                "Default 3.0."
        )
        var pirateFireAtWillFireRateSeconds = 3.0

        @JsonSchema(
            description = "How far out a pirate ship's guns will engage an enemy hull, in blocks. This is " +
                "the trigger range, not the ballistic limit. Set close to what a triple charge can actually " +
                "carry (the arithmetic asymptote is speed/(1-drag), about 107 blocks) and let the SOLVER " +
                "be the limit: a gun only fires when a real arc exists, so a generous gate costs nothing " +
                "in wasted powder but a tight one leaves pirates silent at ranges they could reach. " +
                "Default 160.0."
        )
        var pirateCannonEngageRange = 160.0

        @JsonSchema(
            description = "How many degrees off a gun's bore line the target may sit and still be fired on. " +
                "A gun never turns -- the ship aims it -- so this is when the broadside counts as bearing. " +
                "Wider means guns speak sooner and spread wider (the lateral miss grows with range); " +
                "narrower means tighter shooting a manoeuvring ship rarely achieves. Default 7.5."
        )
        var pirateCannonBearingToleranceDegrees = 7.5

        @JsonSchema(
            description = "How far the AI's aim point is scattered per shot, in blocks -- deliberate hand " +
                "-tremble laid over the ships' own bobbing, so successive solved shots straddle a deck " +
                "instead of boring one hole. 0 is machine precision. Default 1.5."
        )
        var pirateCannonJitterBlocks = 1.5

        @JsonSchema(
            description = "Pirate guns stop consuming powder and shot. The magazines must still be STOCKED " +
                "-- an empty gun stays silent, and authored hulls still decide which guns can speak -- but " +
                "nothing is deducted, so a long fight never runs the batteries dry and boarders find the " +
                "magazines as full as the author left them. Default TRUE: a raider is furniture of the " +
                "fight, not a magazine to be looted -- her crew never run dry, and her guns give nothing at all when " +
                "broken, so cannons are won from the holds and nowhere else."
        )
        var pirateCannonInfiniteAmmo = true

        @JsonSchema(
            description = "Pirate engines stop consuming fuel. The bunkers must still be STOCKED -- an " +
                "engine with an empty slot burns down and goes cold like any other, so an authored hull " +
                "still decides which engines run -- but nothing is deducted, and the heat climbs on the " +
                "same curve, so she makes exactly the power an honest engine would. Default TRUE, and for " +
                "the same reason her magazines are bottomless: a raider who runs out of coal halfway " +
                "through a chase is not a raider, she is scenery. Her bunkers give nothing when broken " +
                "while her wheel stands, and two coal per engine once it falls."
        )
        var pirateEngineInfiniteFuel = true

        @JsonSchema(
            description = "Ticks between one gun speaking and the next when the CAPTAIN calls the volley " +
                "-- Shift+G on deck, G at the wheel. The first gun answers the instant the order is given " +
                "and every gun after it waits this long, so 2 is a rolling broadside and 40 is a ship that " +
                "speaks once every two seconds. Deliberately its own key rather than shared with the Fire " +
                "at Will stagger below: a captain's own order can stay fast while the standing order is " +
                "toned down, or the other way about. A fresh order is refused while she is still firing, " +
                "so this really is her rate of fire under command and not merely a rhythm that can be " +
                "pressed through. Default 2."
        )
        var crewBroadsideStaggerTicks = 2

        // region Fire at Will
        // The captain's half of the same machine: a standing order, set from the Operations tab, that has
        // a ship's own villager gun crews lay and fire on the nearest raider without being told each time.
        // The gun-laying is literally the pirates' -- one shared solver -- so these keys exist to let a
        // player crew be tuned as better or worse hands than the pillagers, rather than as a second system.

        @JsonSchema(
            description = "Whether the Fire at Will order exists at all. Off, the toggle does nothing and " +
                "gun crews only ever fire when the captain calls the broadside. Default true."
        )
        var fireAtWillEnabled = true

        @JsonSchema(
            description = "How far out a crew under Fire at Will will engage a raider, in blocks, measured " +
                "hull to hull. The SOLVER is the real limit -- a gun that cannot reach simply refuses -- so " +
                "this is a leash on how eagerly the crew open up, not a range table. Default 160."
        )
        var fireAtWillEngageRange = 160.0

        @JsonSchema(
            description = "How far off its own bore a gun under Fire at Will will accept a target, in " +
                "degrees. A cannon's azimuth is the ship's business; this is how much slop the crew will " +
                "take before waiting for a better bearing. Default 7.5."
        )
        var fireAtWillBearingToleranceDegrees = 7.5

        @JsonSchema(
            description = "The hand-tremble on a Fire at Will shot, in blocks of scatter at the aim point. " +
                "Default 1.0 -- steadier than the pirates' 1.5, because a crew that berths cost Hearts of " +
                "the Sea should shoot a little better than press-ganged pillagers."
        )
        var fireAtWillJitterBlocks = 1.0

        @JsonSchema(
            description = "Ticks between one gun of a Fire at Will battery speaking and the next. Every " +
                "gun still reloads on its own clock, so this can only ever make a ship slower -- which is " +
                "exactly what it is for: at 2 it paces the thunder of a full battery, and at 40 it IS the " +
                "ship's weight of fire, one gun every two seconds however many are manned. Separate from " +
                "the captain's own volley above, so a standing order can be leashed without slowing the " +
                "broadside they call by hand. Default 10, the same roll a hand-called broadside has."
        )
        var fireAtWillStaggerTicks = 10

        @JsonSchema(
            description = "How long a gun takes to reload while Fire at Will is up, in seconds -- REPLACING " +
                "the powder-derived reload it keeps under a hand-called broadside. One rate for the whole " +
                "battery, whatever measure each breech is set to, so a captain who turns the order on knows " +
                "exactly how fast their ship speaks. 0 or less leaves every gun on its own reload. " +
                "Default 3.0."
        )
        var cannonFireAtWillFireRateSeconds = 3.0

        @JsonSchema(
            description = "A villager seated at a gun is put fully to sleep -- no brain, no pathfinding, " +
                "no looking about -- and wakes the instant they are stood down. A gunner bolted to a " +
                "cannon has nothing to think about, and thinking is expensive aboard a ship: every block " +
                "a pathfinder reads costs a ship-intersection query, and sixty seated gunners were " +
                "measured taking 95 percent of the server thread. The price is that they stop turning to " +
                "watch you and cannot be traded with while stationed. They still take damage, still die, " +
                "still hold their post. Default true."
        )
        var crewGunnerFreeze = true
        // endregion

        @JsonSchema(
            description = "How far out a pirate crew hand OPENS FIRE with a crossbow or bow, in blocks. " +
                "Vanilla's goals shoot at 8 and 15 -- rowboat distances; a ship's marksman needs deck-to-" +
                "deck reach. The projectile speed boost below is what makes the extra range actually " +
                "carry. Default 24.0."
        )
        var pirateCrewShootRange = 24.0

        @JsonSchema(
            description = "How far a pirate crew hand can SEE a target, in blocks (the follow-range " +
                "attribute stamped on every hand at adoption and respawn). Sets the outer limit on " +
                "acquiring players; the shoot range above is when they act on it. Default 48.0."
        )
        var pirateCrewSightRange = 48.0

        @JsonSchema(
            description = "Crew-fired projectiles (bolts, arrows) fly this many times faster than a " +
                "shore monster's, and twice as straight -- the flat arc that lets a deck marksman " +
                "actually reach the shoot range. 1.0 is vanilla. Default 1.5."
        )
        var pirateCrewProjectileSpeedMultiplier = 1.5
        // endregion

        // endregion

        // Armada world collision is engine-resolved: a child is welded to its parent by a rigid VSFixedJoint and
        // collides with the world as a normal physics body, so there is nothing here to tune.
    }
}
