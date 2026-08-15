package org.valkyrienskies.eureka.ship

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import org.joml.*
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.attachment.getAttachment
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ServerTickListener
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.eureka.armada.ArmadaBody
import org.valkyrienskies.eureka.armada.ArmadaShipControl
import org.valkyrienskies.mod.api.SeatedControllingPlayer
import org.valkyrienskies.mod.common.util.toJOMLD
import kotlin.math.*

@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE
)
@JsonIgnoreProperties(ignoreUnknown = true)
class EurekaShipControl : ShipPhysicsListener, ServerTickListener {

    @JsonIgnore
    internal var ship: LoadedServerShip? = null

    // Engine power, refreshed from this ship's engines once per SERVER tick and read on the PHYSICS thread --
    // by this ship, and by the parent when this one is a welded armada member. Volatile for that cross-thread,
    // cross-ship read.
    @Volatile
    private var extraForceLinear = 0.0
    @Volatile
    private var extraForceAngular = 0.0

    /**
     * Engine linear power of the WHOLE armada this ship leads -- its own plus every welded member's. Engines
     * pool for the same reason balloons do: an armada is one vessel, so it is driven by every engine aboard it
     * against its total mass, exactly as a single assembled hull would be.
     *
     * Valid only after the gather loop at the top of physTick has refreshed [armadaMembers]; it is this ship's
     * own power alone for a lone ship.
     */
    private val pooledForceLinear: Double
        get() {
            var total = extraForceLinear
            for (member in armadaMembers) total += member.extraForceLinear
            return total
        }

    var aligning = false
    var disassembling = false // Disassembling also affects position
    private var physConsumption = 0f

    // Physics ticks counted since the last server tick, folded into [physTicksPerGameTick] by onServerTick.
    // Physics-thread only (incremented at the top of physTick, read and reset on the game thread once a tick;
    // a stale-by-one read costs nothing here because the result is smoothed).
    @JsonIgnore
    private var physTickCount = 0

    private var angleUntilAligned = 0.0
    private var positionUntilAligned = Vector3d()
    val canDisassemble
        get() = ship != null &&
            disassembling &&
            abs(angleUntilAligned) < DISASSEMBLE_THRESHOLD &&
            positionUntilAligned.distanceSquared(this.ship!!.transform.positionInWorld) < 4.0
    var consumed = 0f
        private set

    @JsonIgnore
    private var wasCruisePressed = false

    // Cruise PERSISTS across a world reload so the ship keeps sailing/flying its course on relog. The
    // live working course lives in [controlData] -- a nested data class we deliberately DON'T serialize
    // (@JsonIgnore) to avoid depending on Jackson handling the nested type + Direction enum. Instead the
    // course is mirrored into the flat persisted fields below every cruising tick and rebuilt into
    // controlData on load (see the "resume cruise" block in physTick). [isCruising] and [oldSpeed] (the
    // captured, frozen throttle) persist directly. Previously isCruising persisted but oldSpeed did not,
    // so a reloaded ship came back "cruising" at zero speed -- it sat still AND swallowed player input.
    @JsonProperty("cruise")
    var isCruising = false

    // What kind of vessel this is, and so which settings block it handles by. NOT persisted and never chosen
    // by the player: it is re-derived every physics tick from the pooled floater and balloon counts and the
    // keel's water contact (see the classification block in physTick). Written on the physics thread, read on
    // the game thread by the helm menu and by setCruiseValueMenu's clamps -- hence volatile.
    @JsonIgnore
    @Volatile
    var activeProfile = ControlProfile.BOAT
        private set

    // True when this vessel has BOTH floaters and balloons, i.e. it is a hybrid and its category can change
    // under it. Published only so the helm can say so; the physics reads [activeProfile].
    @JsonIgnore
    @Volatile
    var isHybrid = false
        private set

    // A hybrid's wet/dry state, with hysteresis: it goes wet the moment the keel reaches water and stays wet
    // until the hull has fully cleared it. Without that a ship sitting in surface chop would flicker between
    // two sets of handling several times a second. Physics thread only.
    @JsonIgnore
    private var hybridWet = false

    // The settings block this ship reads its per-category handling off of -- engine power, speed, turning,
    // vertical response, thrust assists, stabilization and the waterline hold. Everything else (buoyancy and
    // lift, assembly-time knobs, path/follow gains, cruise hold times, debug toggles) stays GLOBAL on
    // EurekaConfig.SERVER; see the comment there for why lift in particular must not be per-category.
    // Computed getter, no backing field -- not serialized (the class uses getterVisibility = NONE), like the
    // `canDisassemble` getter.
    private val cfg get() = activeProfile.preset

    // Public view of this ship's category preset, for off-class consumers that must honor it. EngineBlockEntity
    // reads it for enginePowerLinear (the force this attachment's boost threshold is scaled against) and
    // engineHeatGain, so an airship's engine force and heat match its own preset rather than the boat's. Same
    // instance as [cfg]; exposed read-only. Computed getter with no backing field, so it isn't serialized (the
    // class sets getterVisibility = NONE), like [cfg]/[canDisassemble].
    val engineCfg: EurekaConfig.ShipHandling get() = cfg

    @JsonIgnore
    private var controlData: ControlData? = null

    // Flat, persisted mirror of the cruise course (rebuilt into controlData on load). cruiseSeatDir is
    // the seat-facing Direction ordinal, -1 = no captured course.
    @JsonProperty("cruiseSeatDir")
    private var cruiseSeatDir = -1
    @JsonProperty("cruiseFwd")
    private var cruiseFwd = 0.0f
    @JsonProperty("cruiseLeft")
    private var cruiseLeft = 0.0f
    @JsonProperty("cruiseUp")
    private var cruiseUp = 0.0f
    @JsonProperty("cruiseSprint")
    private var cruiseSprint = false

    @JsonIgnore
    var seatedPlayer: Player? = null

    // region Path following (see the org.valkyrienskies.eureka.path and .follow packages)
    // Two things drive these: a recorded route (PathFollower) and station-keeping on another ship (ShipFollower).
    // They are deliberately the same plumbing, because as far as the hull is concerned both are the same thing --
    // guidance arriving from something that isn't a seated pilot -- and everything below that has to be
    // suppressed for one has to be suppressed for the other.
    // Written on the GAME thread once per tick, read on the PHYSICS thread in physTick --
    // hence @Volatile, matching turnHold/fwdHold/vertHold. Null means "no path input", which is what lets the
    // pilot's own controls and the cruise latches behave exactly as they always did when nothing is following.
    // None of it is persisted -- these are one tick's guidance, meaningless after a reload. What DOES survive is
    // the binding that generates them, saved on the ship as a PathBinding and re-armed by ShipPaths.tick, which
    // then calls pathBegin() again and starts issuing fresh commands.
    @JsonIgnore
    @Volatile
    var pathTurnOmega: Double? = null
        private set

    @JsonIgnore
    @Volatile
    var pathVerticalRate: Float? = null
        private set

    /**
     * True from [pathBegin] until [pathRelease]: this ship is under a recorded route's guidance.
     *
     * Distinct from `pathTurnOmega != null`, which only becomes true once the follower has actually issued a
     * command. That gap matters, because everything this flag suppresses would otherwise fire on the very first
     * tick -- before the first command exists -- and dismantle the course the follower is about to steer.
     *
     * A path-following ship has no seated pilot, so as far as the rest of physTick is concerned it looks
     * abandoned. Three things follow from that and all three are wrong here:
     *
     *  - `controlData` is cleared every tick when nobody is aboard and no cruise is set, which wiped the course
     *    [pathBegin] had just established and left the follower with nothing to steer.
     *  - [stabilize] brakes yaw for an unpiloted hull, cancelling the follower's commanded turn -- which is why
     *    turning appeared to need the helm's Turn checkbox, that being the one other thing that lifts the brake.
     *  - [stabilize] also brakes linear velocity, fighting the thrust that carries the ship along the route.
     */
    @JsonIgnore
    @Volatile
    var pathFollowing = false
        private set

    /**
     * Forward speed ceiling in m/s while following, or null for no ceiling.
     *
     * The follower lowers this into a corner so the hull can physically hold the line it is being asked to hold
     * (see [org.valkyrienskies.eureka.path.PathFollower]). Strictly a CEILING -- it never adds speed, so the
     * pilot's throttle or cruise setting still owns how fast the route is flown.
     */
    @JsonIgnore
    @Volatile
    var pathSpeedCap: Double? = null
        private set

    /**
     * Forward speed COMMAND in m/s while following, or null to leave the throttle alone.
     *
     * The one thing a route never needed. A route only ever lowered [pathSpeedCap], because the pilot's cruise
     * setting owned how fast the line was flown -- there was no right answer for the route to have an opinion
     * about. Station-keeping on another ship is the opposite: the whole task IS a speed, since holding position
     * beside a moving vessel means matching its speed and adding just enough to close the remaining gap.
     *
     * Held closed-loop against the ship's REAL forward speed in [getPlayerForwardVel], exactly as a typed cruise
     * speed is, so it converges on the number asked for rather than drifting with engine heat and fuel. It is a
     * request, not a guarantee: the throttle it drives is clamped to +/-1, so a hull simply cannot be commanded
     * past its own top speed. That is what makes a follower fall behind a faster leader instead of matching it,
     * with no code anywhere having to know either ship's capability.
     */
    @JsonIgnore
    @Volatile
    var pathTargetSpeed: Double? = null
        private set

    /**
     * Where the hull is being placed this tick, when a route is being REPLAYED rather than steered.
     *
     * Null for everything else, and null is the ordinary state: a plain route (SHIFT+P) and station-keeping on
     * another ship both go through the commands above, which ask the hull's own control law to fly somewhere.
     * A replay does not ask. It states where the ship is, because that is what a recording IS -- and the
     * moment it went through the control law instead, it inherited that law's acceleration (a typed cruise
     * speed reaches its target in six seconds where a pilot's hand takes forty), its lag on a dive, and its
     * fight with the anti-velocity brake at a stop.
     *
     * Read on the physics thread, written on the game thread, and replaced wholesale rather than mutated so
     * the reader can never see half of one tick's target and half of the next's.
     */
    @JsonIgnore
    @Volatile
    var pathServo: PathServo? = null

    /**
     * How far the hull is from where [pathServo] wants it, in blocks. Zero when nothing is servoing.
     *
     * The one number the game side needs back: a route can be blocked by terrain built since it was recorded,
     * and a servo pushing at something immovable has to be noticed by someone rather than grinding forever.
     */
    @JsonIgnore
    @Volatile
    var pathServoError = 0.0
        private set

    /**
     * True while the servo is asking for speed and the hull is making almost none of it.
     *
     * The other half of "is this route blocked", and the half that carries the meaning. Distance alone cannot
     * answer it: a servo chasing a moving station lags in proportion to how fast that station is going, so a
     * fixed distance reads a brisk leg and a brick wall alike. This reads the hull instead -- told to move,
     * not moving -- which is what being blocked actually is.
     */
    @JsonIgnore
    @Volatile
    var pathServoStalled = false
        private set

    /**
     * One tick's worth of "put the ship here".
     *
     * Immutable, and carries the keel anchor in SHIP space rather than a world position for it: the physics
     * thread has the hull's transform and can do that multiplication itself, where asking the game side to
     * pre-compute it would bake in a pose one tick stale.
     */
    class PathServo(
        /** Where the keel should be, in world space. */
        val keelTarget: Vector3dc,
        /** How fast it should be getting there, world space -- the feed-forward that does most of the work. */
        val velocity: Vector3dc,
        /** Which way the bow should point, horizontal and normalised. */
        val forward: Vector3dc,
        /** The keel anchor in ship space, so the physics thread can find where the keel actually is. */
        val keelLocal: Vector3dc
    )

    /** Fastest this hull climbs, m/s -- the ceiling a route's ascent has to fit inside. */
    val pathClimbRateMax: Double @JsonIgnore get() = cfg.baseImpulseElevationRate

    /** Fastest this hull descends, m/s. Separate from the climb rate, which is usually not the same number. */
    val pathDescendRateMax: Double @JsonIgnore get() = cfg.baseImpulseDescendRate

    /**
     * Seat facing last reported by this ship's helm ([org.valkyrienskies.eureka.blockentity.ShipHelmBlockEntity]).
     *
     * Path playback has no seated pilot, so it needs a forward to thrust along just as a helm-menu cruise does.
     * A ship that has cruised before already has one in [cruiseSeatDir]; a ship that never has would otherwise
     * have nowhere to get it, and would silently sit still when told to fly a route.
     */
    @JsonIgnore
    @Volatile
    var helmSeatDir: Direction? = null
    // endregion

    /**
     * Where this ship keeps its articles: the SHIPYARD block position of the crew-station helm, packed with
     * `BlockPos.asLong()`, or [NO_CREW_STATION].
     *
     * A ship may carry any number of helms and all of them steer; exactly one holds the crew, so bolting on
     * more wheels can never buy more berths. Claimed by the helm that assembles the ship, which is the "first
     * helm wins" rule stated plainly.
     *
     * A position rather than a search, because the alternative is walking a ship's block entities every time
     * anyone asks who is aboard -- and the roster itself lives on the block entity at this address, so a helm
     * that has been mined resolves to nothing and the ship correctly has no crew station until one is claimed
     * again.
     */
    @JsonProperty("crewHelm")
    var crewStationPos: Long = NO_CREW_STATION

    @JsonProperty("cruiseSpeed")
    var oldSpeed = 0.0

    // Exact forward speed (m/s) the pilot typed in the helm textbox, if any. getPlayerForwardVel trims the
    // throttle (oldSpeed) CLOSED-LOOP so the ship's REAL speed converges to it. The old open-loop path set
    // oldSpeed = typed / estimateTopSpeed(), but that estimate drifts with engine heat/fuel, so the same typed
    // number reached different real speeds (e.g. 4 -> 12). Cleared when the horizontal set is disarmed or a live
    // forward input takes over (the pilot drives). null = plain throttle mode (unchanged live driving).
    @JsonProperty("cruiseTargetSpeed")
    private var cruiseTargetSpeedMps: Double? = null

    // Additive correction (m/s) that lets the ship actually REACH the speed the helm advertises.
    // Forward thrust is a proportional controller on velocity error, so the ship settles wherever thrust
    // balances drag -- always short of the commanded velocity, by a factor that is a property of the hull
    // rather than anything the estimate can know. Integrating the shortfall drives that error to zero for
    // any hull without the estimate having to model drag at all, and it closes from both sides: a ship
    // running over the advertised figure trims down to it just as one running under trims up.
    // Most of what this was written to answer turned out to be the engine force being consumed on the
    // first physics tick that read it (see getPlayerForwardVel); what's left for it is genuinely small.
    // Transient: not persisted, rebuilds within a couple of seconds of driving.
    @JsonIgnore
    private var dragTrimMps = 0.0

    // Lock-in turn cruise -- the angular twin of oldSpeed. While cruising, holding a turn key spins the
    // ship up (turnAcceleration) and the achieved yaw rate is continuously latched here; releasing freezes
    // it so the ship holds a constant-radius circle hands-off instead of braking straight. Transient like
    // holdTargetY (rebuilt from the persisted mirror on reload). null = no orbit locked (straight cruise).
    @JsonIgnore
    private var cruiseTurnOmega: Double? = null

    // PERSISTED flag: true iff a turn rate is currently locked (cruiseTurnOmega non-null), kept in lockstep
    // with it via clearCruiseTurn()/the capture branch. Persisted as a primitive because the stabilize()
    // yaw-brake gate reads it on the first post-reload tick, which runs BEFORE the transient is rebuilt.
    @JsonProperty("cruiseTurning")
    private var cruiseTurning = false

    // Flat persisted numeric mirror of cruiseTurnOmega, written every cruising tick and read back into the
    // transient on the resume-cruise reload path (only meaningful when cruiseTurning is true).
    @JsonProperty("cruiseTurnRate")
    private var cruiseTurnOmegaSaved = 0.0

    // Per-set cruise latches. Cruise is THREE independent sets, each armed at activation and canceled
    // independently; only the C toggle clears the whole cruise. The TURN set is cruiseTurnOmega/cruiseTurning
    // above; these two are the HORIZONTAL (W/S) and VERTICAL (Space/V) sets. Persisted so they survive reload.
    @JsonProperty("cruiseHorizontal")
    private var cruiseHorizontalActive = false

    @JsonProperty("cruiseVertical")
    private var cruiseVerticalActive = false

    // Latched vertical climb/descend rate (additive: a held Space/V nudges it; release maintains). Replayed as
    // the upImpulse while the vertical set is latched, so the ship holds that climb/descend rate. Persisted.
    @JsonProperty("cruiseVerticalRate")
    private var cruiseVerticalRate = 0.0f

    // Per-set "hold the opposite input to cancel" trackers (physics-thread only). Each arms when a fresh hold
    // OPPOSES the set's current influence and cancels that set once the hold reaches the set's configured
    // seconds. See [CancelState] and the unified cancel block in physTick.
    @JsonIgnore
    private val fwdCancel = CancelState()
    @JsonIgnore
    private val vertCancel = CancelState()
    @JsonIgnore
    private val turnCancel = CancelState()

    // Scratch objects reused across phys ticks (fieldVisibility=ANY would otherwise serialize
    // them). Only intermediates live here — every vector handed to applyInvariantForce/Torque
    // stays freshly allocated because vs-core queues those by reference.
    @JsonIgnore
    private val scratchInvRotation = Quaterniond()

    @JsonIgnore
    private val scratchAxisAngle = AxisAngle4d()

    // Working vectors for the route servo (applyPathServo). Physics-thread only, like every other scratch here.
    @JsonIgnore
    private val servoKeel = Vector3d()

    @JsonIgnore
    private val servoVector = Vector3d()

    @JsonIgnore
    private val servoDrive = Vector3d()

    @JsonIgnore
    private val servoOmega = Vector3d()

    @JsonIgnore
    private val servoHeading = Vector3d()

    @JsonIgnore
    private val servoUp = Vector3d()

    // The armada this ship leads, seen as one rigid body -- just this ship when nothing is bound to it. Rebuilt
    // from the live members at the top of every physTick and reused, so a steady state allocates nothing. Only
    // the LEAD of an armada (or a lone ship) uses its own; a welded child returns before this is touched.
    @JsonIgnore
    private val armadaBody = ArmadaBody()

    // The welded children's controls, gathered alongside [armadaBody] each tick. Held so the pooled block
    // counts and the engine-fuel burn can reach them; empty for a lone ship. Physics-thread only.
    @JsonIgnore
    private val armadaMembers = ArrayList<EurekaShipControl>(3)

    // The armada's engine count and combined mass as of the last gather, for [estimateTopSpeed] -- written on
    // the physics thread, read on the game thread when the helm asks how fast this vessel can go. -1 means no
    // gather has run yet (a welded child, which returns long before it, or the ticks before the first one),
    // and the estimate then falls back to this hull's own numbers, which is what it always used.
    @JsonIgnore
    @Volatile
    private var pooledEngines = -1

    @JsonIgnore
    @Volatile
    private var pooledMass = 0.0

    // Water altitude-hold state. Transient (re-latches from the ship's current Y on load): while a
    // hybrid ship (floaters + balloons) has its keel in water, the vertical axis is pinned to
    // holdTargetY instead of letting balloon lift float it back above the surface. holdEngaged adds
    // hysteresis so surface chop doesn't flicker the mode on and off. Both touched only from physTick
    // (physics thread, sequential per ship), so no synchronization is needed.
    @JsonIgnore
    private var holdTargetY: Double? = null

    @JsonIgnore
    private var holdEngaged = false

    // Real-world water contact at the keel, sampled on the GAME thread (ShipHelmBlockEntity.tick) and
    // read here on the physics thread. VS2 core's liquidOverlap is the submerged fraction measured
    // against the dimension's flat sea-level plane, so it reads 0 for water bodies away from sea level
    // (man-made / elevated / sunken rivers + lakes). This direct keel-vs-water sample lets the altitude
    // hold engage -- and a hybrid pick the BOAT category -- on ANY body of water at any altitude.
    //
    // It samples the WORLD at the ship's world position, and a ship's own blocks live in the shipyard, so
    // water assembled INTO a hull is invisible to it by construction: an airship with a pool on deck can
    // never read as touching water.
    @JsonIgnore
    @Volatile
    var keelInWater = false

    // Is THIS hull entirely under water -- sampled game-side at the TOP of the ship's world box, so it is
    // true only once even the highest part of the ship is submerged. Written on the game thread.
    @JsonIgnore
    @Volatile
    var fullySubmerged = false

    // The same question asked of the whole VESSEL: every hull of the armada under water, pooled in physTick
    // and published back for the game thread. Nothing selects ControlProfile.SUBMARINE off it yet -- that is
    // the submarine installment's first line -- but the helm shows it, which is how the detection can be
    // checked in game before there is a submarine control law to check it with.
    @JsonIgnore
    @Volatile
    var vesselSubmerged = false
        private set

    // Environment readouts for the helm's per-category info boxes, in blocks, sampled game-side on a slow
    // stagger. -1 means "not known" -- out of range, an unloaded chunk, or not applicable -- and the helm
    // draws those as "--" rather than a number.
    @JsonIgnore @Volatile var seabedDistance = -1   // keel down to the floor under the water
    @JsonIgnore @Volatile var surfaceDistance = -1  // hull top up to open air
    @JsonIgnore @Volatile var groundDistance = -1   // keel down to whatever solid ground is below
    @JsonIgnore @Volatile var shoreDistance = -1    // nearest non-water column, horizontally

    // Per-axis hold state, written on the fixed-rate GAME thread (updateInputHolds, 20 TPS) and read on the
    // physics thread. SIGNED seconds: sign = held direction, magnitude = continuous-hold time (0 on release,
    // one tick on a fresh press / direction flip, else accumulating). Packed into ONE @Volatile each so the
    // physics thread reads direction + duration atomically (no torn rising-edge read).
    @JsonIgnore @Volatile var turnHold = 0.0
    @JsonIgnore @Volatile var fwdHold = 0.0
    @JsonIgnore @Volatile var vertHold = 0.0
    @JsonIgnore private var turnHoldLastGameTick = -1L

    // Per-set cruise "intent" direction (physics thread): the sign of each latched set's influence -- seeded
    // from the engaging input, then tracked from the influence whenever it leaves the dead-zone (retained
    // inside it). Used as the reference for hold-OPPOSITE-to-cancel so a set latched with near-zero influence
    // is still cancellable. 0 when the set isn't latched.
    @JsonIgnore private var fwdIntentSign = 0
    @JsonIgnore private var vertIntentSign = 0
    @JsonIgnore private var turnIntentSign = 0

    private data class ControlData(
        val seatInDirection: Direction,
        var forwardImpulse: Float = 0.0f,
        var leftImpulse: Float = 0.0f,
        var upImpulse: Float = 0.0f,
        var sprintOn: Boolean = false
    ) {
        companion object {
            fun create(player: SeatedControllingPlayer): ControlData {
                return ControlData(
                    player.seatInDirection,
                    player.forwardImpulse,
                    player.leftImpulse,
                    player.upImpulse,
                    player.sprintOn
                )
            }
        }
    }

    // Tracks a "hold the opposite input to cancel" gesture for one cruise set (physics-thread only). It arms
    // at the hold's RISING EDGE iff the held direction opposes the set's current influence sign -- captured
    // there so a mid-hold sign flip (the influence crossing zero as it's driven down) doesn't disarm it -- and
    // reports a cancel once that continuous hold reaches the set's duration.
    private class CancelState {
        private var prevHold = 0.0
        private var armed = false

        fun reset() {
            prevHold = 0.0
            armed = false
        }

        fun update(held: Double, heldSign: Int, infSign: Int, duration: Double): Boolean {
            val freshHold = held > 0.0 && (prevHold <= 0.0 || held < prevHold)
            if (freshHold) armed = heldSign != 0 && infSign != 0 && heldSign != infSign
            if (held <= 0.0) armed = false
            prevHold = held
            return armed && held >= duration
        }
    }

    @OptIn(VsBeta::class)
    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        // Before every early return below: this counts the physics rate itself, which is a property of the
        // server and not of whether this particular hull has anything to do this tick. [estimateTopSpeed]
        // needs it to work out how fast engines burn their heat off.
        physTickCount++
        if (helms < 1) {
            // Enable fluid drag if all the helms have been destroyed
            physShip.doFluidDrag = true
            return
        }
        // Disable fluid drag when helms are present, because it makes ships hard to drive. Per-category, and
        // this runs BEFORE the category is re-derived below, so it is one tick stale -- which costs nothing:
        // it is a boolean that only changes when a hybrid crosses the waterline, and the categories that
        // could differ on it are a tick late either way.
        physShip.doFluidDrag = cfg.doFluidDrag

        val ship = ship ?: return
        val armada = ArmadaShipControl.get(ship)

        // A welded child does NOT run its own control law. Its parent computes for the armada as ONE body and
        // applies this ship's share directly (see ArmadaBody), which is the only way the numbers can come out
        // right: every term here is sized from one hull's mass, inertia and block counts, so two instances
        // driving two rigidly welded bodies produce a result neither of them modelled. That is what pinned the
        // armada's heading, sank the child's side, and yawed the whole formation the moment the pilot stood up.
        if (armada != null && armada.isChild && armada.parentShip != null) {
            physShip.isStatic = armadaAnchored(armada.parentShip!!)
            return
        }

        // Gather the armada this ship leads as a single rigid body -- just this ship when nothing is bound to
        // it, in which case everything below is exactly the stock single-ship control law. The parent goes in
        // FIRST: it is the body's lead, so the armada steers, points and rights itself as the parent would.
        //
        // Block counts pool the same way the physics does. In one assembled Eureka ship it makes no difference
        // where the balloons sit; an armada is meant to be one vessel, so an over-ballooned parent carries an
        // under-ballooned child rather than each hull holding up only itself. Left un-pooled, each ship lifted
        // min(its own capacity, its own weight) -- not its share of the armada -- and the mismatch was a
        // standing roll the parent's own righting torque was far too small to clear.
        val body = armadaBody
        body.clear()
        body.add(physShip)
        armadaMembers.clear()

        var pooledBalloons = balloons
        var pooledFloaters = floaters
        var pooledKeelInWater = keelInWater
        // ANDed, not ORed, unlike the others: "the vessel is under water" has to mean EVERY hull of it is.
        var pooledFullySubmerged = fullySubmerged
        var pooledLiquidOverlap = physShip.liquidOverlap
        var pooledAnchorsActive = anchorsActive

        armada?.childShips?.forEach { (childId, childShip) ->
            val childControl = childShip.getAttachment(EurekaShipControl::class.java)

            // Anchors pool FIRST, before anything can drop a member from the rest of the gather: an anchor makes
            // its ship static, and a static member is exactly what the body below has to leave out. Read any
            // later and an anchored child would fall out of the gather, the armada would decide it wasn't
            // anchored, the child would go free, and the two would flip states against each other every tick.
            if (childControl != null) pooledAnchorsActive += childControl.anchorsActive

            val childPhys = physLevel.getShipById(childId) ?: return@forEach
            // Never include a static member: PhysShip.applyQueuedForces skips those, so its share would pile up
            // in a queue that is never drained instead of being applied or dropped.
            if (childPhys.isStatic) return@forEach

            // Into the body regardless of what it still has aboard: it is welded on, so its mass and inertia
            // are part of the armada whether or not it can contribute anything. A child stripped of its last
            // Eureka block drops its attachment, and leaving it out here would make it dead weight hanging off
            // one side -- which is exactly the off-centre load this whole design exists to avoid.
            body.add(childPhys)

            if (childControl == null) return@forEach
            armadaMembers.add(childControl)
            pooledBalloons += childControl.balloons
            pooledFloaters += childControl.floaters
            pooledKeelInWater = pooledKeelInWater || childControl.keelInWater
            pooledFullySubmerged = pooledFullySubmerged && childControl.fullySubmerged
            pooledLiquidOverlap = max(pooledLiquidOverlap, childPhys.liquidOverlap)
        }
        body.build()

        // What the vessel actually is, published for the game thread -- see [estimateTopSpeed]. The engines
        // and the mass have to be read off the SAME body the thrust is applied to, and until this was
        // gathered they weren't: a parent advertised its own engines against its own mass while the physics
        // divided the armada's pooled power across the armada's combined weight. A tug carrying a heavy
        // consort could therefore offer a top speed of twenty and hold three, with the helm insisting the
        // number had been accepted -- because it had, and the ship simply could not make it.
        var totalEngines = engines
        for (member in armadaMembers) totalEngines += member.engines
        pooledEngines = totalEngines
        pooledMass = body.mass

        // Anchors hold the VESSEL, so one dropped anywhere in the armada holds all of it -- see [armadaAnchored].
        val armadaAnchored = pooledAnchorsActive > 0

        val mass = body.mass
        val omega: Vector3dc = body.angularVelocity
        val vel: Vector3dc = body.velocity

        // Water contact, pooled: any hull of the armada in the water puts the whole vessel in the water.
        // keelInWater is sampled game-side from real blocks, so it sees rivers, lakes and man-made water at
        // any altitude; VS2's liquidOverlap is the submerged fraction against the dimension's flat sea-level
        // plane, so it only sees the ocean. Either counts. Hysteresis: it takes waterAltitudeHoldMinOverlap
        // to become wet but a FULL clear of the water to become dry again.
        //
        // waterAltitudeHoldMinOverlap is read GLOBALLY here and nowhere else -- it is what decides the
        // CATEGORY, and the category decides which preset to read, so taking it per-category would be
        // circular. It answers "is this vessel in the water", a fact about the world rather than handling.
        val inWater = pooledKeelInWater || pooledLiquidOverlap > EurekaConfig.SERVER.waterAltitudeHoldMinOverlap
        val stillInWater = pooledKeelInWater || pooledLiquidOverlap > 0.0

        // What kind of vessel this is. Everything below reads its handling off `cfg`, so this has to be
        // settled before the first `cfg.` read (the balloon/engine cap immediately after). Classified from
        // the POOLED counts, so a welded armada is one vessel of one category rather than a flagship
        // disagreeing with its consorts.
        //
        // Only a hybrid -- floaters AND balloons -- can change category under way, and it does so on the
        // hysteresis above: a boat while it is touching water, an airship once it is clear of it. A ship
        // built as one thing or the other never switches at all, so there is nothing to chatter.
        //
        // pooledFullySubmerged is deliberately NOT consulted yet: a fully-submerged hybrid is what will pick
        // ControlProfile.SUBMARINE, and until the submarine control law exists it stays a boat under water.
        // The detection and the config block are both live, so that is a one-line change when it lands.
        isHybrid = pooledBalloons > 0 && pooledFloaters > 0
        vesselSubmerged = pooledFullySubmerged
        hybridWet = inWater || (hybridWet && stillInWater)
        activeProfile = ControlProfile.classify(pooledBalloons, pooledFloaters, hybridWet)

        var balloonForceProvided = pooledBalloons * forcePerBalloon

        if (EurekaConfig.SERVER.maxBalloonsPerEngine > 0 && pooledBalloons > 0) {
            balloonForceProvided *= min(
                1.0,
                ( pooledForceLinear * EurekaConfig.SERVER.maxBalloonsPerEngine ) / ( cfg.enginePowerLinear * pooledBalloons )
            )
        }

        // Water altitude-hold ("Water Lock") engagement: a ship on the water pins its current Y instead of
        // letting buoyancy or balloon lift float it off the surface. It rides the same wet/dry hysteresis
        // computed above, so surface chop can't flicker it.
        //
        // The gate is the BOAT category rather than the old "has floaters AND balloons": the helm's Water
        // Lock checkbox now lives on the Boats & Ships tab and reads the boat preset's own
        // enableWaterAltitudeHold, so a hybrid gets the hold exactly while it is being a boat -- which is to
        // say while it is touching water -- and a plain floaters-only boat can hold its waterline too, which
        // it could not before.
        holdEngaged = cfg.enableWaterAltitudeHold && !disassembling && !armadaAnchored &&
            activeProfile == ControlProfile.BOAT && pooledFloaters > 0 &&
            (inWater || (holdEngaged && stillInWater))
        if (!holdEngaged) holdTargetY = null

        val buoyantFactorPerFloater = min(
            EurekaConfig.SERVER.floaterBuoyantFactorPerKg / 15.0 / mass,
            EurekaConfig.SERVER.maxFloaterBuoyantFactor
        )

        // While the altitude-hold owns the vertical axis, keep core buoyancy neutral (1.0) so the
        // hold isn't fighting a large floater-driven up-force; otherwise apply the floater buoyancy.
        // Every member gets the SAME armada-wide factor, from the pooled floaters and combined mass: floaters
        // hold up the vessel, not the hull they happen to sit in. The lead owns this for the whole formation --
        // a child never sets it, so the two can't race depending on which ship's physTick ran last.
        val buoyantFactor = if (holdEngaged) 1.0 else 1.0 + pooledFloaters * buoyantFactorPerFloater
        physShip.buoyantFactor = buoyantFactor
        for (i in 1 until body.size) body.memberAt(i).buoyantFactor = buoyantFactor

        // region Aligning

        val invRotation = physShip.transform.shipToWorldRotation.invert(scratchInvRotation)
        val invRotationAxisAngle = scratchAxisAngle.set(invRotation)
        // Floor makes a number 0 to 3, which corresponds to direction
        val alignTarget = floor((invRotationAxisAngle.angle / (PI * 0.5)) + 4.5).toInt() % 4
        angleUntilAligned = (alignTarget.toDouble() * (0.5 * PI)) - invRotationAxisAngle.angle
        if (disassembling) {
            val pos = ship.transform.positionInWorld
            positionUntilAligned = pos.floor(Vector3d())
            val direction = pos.sub(positionUntilAligned, Vector3d())
            body.applyForce(direction)
        }
        if ((aligning) && abs(angleUntilAligned) > ALIGN_THRESHOLD) {
            if (angleUntilAligned < 0.3 && angleUntilAligned > 0.0) angleUntilAligned = 0.3
            if (angleUntilAligned > -0.3 && angleUntilAligned < 0.0) angleUntilAligned = -0.3

            val idealOmega = Vector3d(invRotationAxisAngle.x, invRotationAxisAngle.y, invRotationAxisAngle.z)
                .mul(-angleUntilAligned)
                .mul(cfg.stabilizationSpeed)

            body.applyAngularAcceleration(idealOmega)
        }
        // endregion

        val controllingPlayer = ship.getAttachment(SeatedControllingPlayer::class.java)
        val validPlayer = controllingPlayer != null && !armadaAnchored


        if (armadaAnchored) {
            if (isCruising) {
                isCruising = false
                showCruiseStatus()
            }
            // Anchoring ends the whole cruise so un-anchoring starts clean.
            clearCruiseLatches()

            physShip.isStatic = true
            return
        }

        // A replayed route takes the hull over outright, so nothing below this line runs for it -- no
        // stabilize, no altitude hold, no thrust, no cruise. Deliberately AFTER the anchor branch: dropping an
        // anchor is a physical statement about the ship and outranks anything a recording has to say.
        pathServo?.let { servo ->
            applyPathServo(body, physShip, servo)
            return
        }

        // Braking and righting run against the armada as one body, so the brake is a single force through the
        // combined centre of mass (no lever arm, so no yaw) and the righting torque is sized for the whole
        // formation's inertia. This is the path that runs when nobody is at the helm, and getting it wrong is
        // what made a coasting armada wrench itself into a turn the moment the pilot stood up.
        stabilize(
            body,
            cfg,
            // A ship following a route counts as piloted for both brakes below: the follower IS the pilot, it
            // just isn't sitting down. Without this the anti-velocity brake fights the thrust carrying the ship
            // along its route.
            !validPlayer && !aligning && !pathFollowing,
            // Yaw is braked when there's no pilot -- EXCEPT while a turn-cruise orbit is locked, so a saved/
            // pilotless circling ship keeps its rate instead of being dragged straight. Reads the PERSISTED
            // cruiseTurning (this runs before the resume block rebuilds the transient on the first reload tick).
            // A route being followed lifts the brake for the same reason a locked orbit does -- something is
            // deliberately commanding yaw. Leaving it on is what made a followed route steer only while the
            // helm's Turn box happened to be ticked: the ship turned, and the brake immediately undid it.
            !validPlayer && !pathFollowing &&
                !(isCruising && cruiseTurning && EurekaConfig.SERVER.enableTurnCruise)
        )

        var idealUpwardVel = Vector3d(0.0, 0.0, 0.0)

        // Resume a cruise saved across a world reload: controlData (the live course) is transient, so
        // rebuild it from the persisted flat course the first tick after load. After this, the normal
        // control flow owns controlData. isCruising + oldSpeed (the cruise speed) were restored directly,
        // so the ship picks its course back up even before anyone (re)mounts the helm.
        if (isCruising && controlData == null) {
            if (cruiseSeatDir in 0..5) {
                controlData = ControlData(
                    Direction.values()[cruiseSeatDir],
                    cruiseFwd, cruiseLeft, cruiseUp, cruiseSprint
                )
                // Restore the locked turn rate (angular twin of oldSpeed) so a reloaded ship keeps circling
                // at the same rate; the release controller re-converges omega.y to it if physics drifted.
                cruiseTurnOmega =
                    if (cruiseTurning && EurekaConfig.SERVER.enableTurnCruise) cruiseTurnOmegaSaved else null
            } else {
                // isCruising was saved with no captured course (e.g. a helm-menu cruise activated with no
                // recorded inputs, or saved on the exact activation tick before any steering). Keep cruising
                // idle rather than dropping it -- the no-input auto-off was removed. controlData stays null so
                // the ship coasts until a player (re)mounts the helm and the live-control path rebuilds it, or
                // an armed set records a value. Rebuild the locked turn rate from the saved mirror if one was
                // armed.
                cruiseTurnOmega =
                    if (cruiseTurning && EurekaConfig.SERVER.enableTurnCruise) cruiseTurnOmegaSaved else null
            }
        }

        var liveControl: ControlData? = null
        if (validPlayer) {
            val player = controllingPlayer!!

            val live = getControlData(player)
            liveControl = live

            if (!isCruising) {
                // Only freeze the control while NOT cruising. On the tick cruise turns on this is
                // skipped, so controlData keeps the input the player held at activation -- the logged
                // course + direction. getPlayerForwardVel additionally freezes oldSpeed while cruising,
                // so the activation SPEED is held too. The captured TURN is NOT re-applied as a frozen
                // leftImpulse anymore -- it's owned by the lock-in turn cruise (cruiseTurnOmega), which
                // latches the achieved yaw RATE so releasing holds a constant-radius circle.
                controlData = live
            }

            wasCruisePressed = player.cruise
        } else {
            // A followed route holds the course open the same way a cruise does. Clearing it here is what made
            // binding a stationary, non-cruising ship to a route fail instantly: pathBegin() set the course, the
            // very next physics tick wiped it, and the follower reported having lost its way. Speed is untouched
            // either way -- the route steers, the pilot's throttle or cruise still drives.
            if (!isCruising && !pathFollowing) {
                // If the player isn't controlling the ship, and not cruising, reset the control data
                controlData = null
                oldSpeed = 0.0
                clearCruiseLatches()
            }
        }

        // Mirror the live cruise course into the flat persisted fields so it survives a world reload
        // (controlData itself is @JsonIgnore). Cheap -- the course is frozen while cruising, so this just
        // tracks the occasional turn-drop. oldSpeed (the cruise speed) persists on its own field.
        if (isCruising) {
            controlData?.let { cd ->
                cruiseSeatDir = cd.seatInDirection.ordinal
                cruiseFwd = cd.forwardImpulse
                cruiseLeft = cd.leftImpulse
                cruiseUp = cd.upImpulse
                cruiseSprint = cd.sprintOn
            }
            // Mirror the locked turn rate so it survives a reload (cruiseTurnOmega is @JsonIgnore;
            // cruiseTurning persists directly and is kept in lockstep via capture/clearCruiseTurn()).
            cruiseTurnOmegaSaved = cruiseTurnOmega ?: 0.0
        }

        // Forward/back thrust gets a different assist depending on what carries the ship.
        // Land travel must overcome ground friction; water travel is tuned separately so it
        // can stay faster than land without the two sharing one knob. A ship with enough
        // balloon lift to fly keeps the normal, unassisted thrust.
        val thrustMultiplier = when {
            balloonForceProvided >= mass * -GRAVITY -> 1.0
            physShip.liquidOverlap > 0.0 -> cfg.waterThrustAssist
            else -> cfg.landThrustAssist
        }

        // Vertical additive trim: while the vertical set is latched, a held Space/V nudges the held climb/
        // descend rate toward full (tap = small, hold = ramp); releasing keeps it, so the ship holds that rate.
        if (cruiseVerticalActive) {
            val liveUp = liveControl?.upImpulse ?: 0.0f
            if (liveUp != 0.0f) {
                cruiseVerticalRate =
                    (cruiseVerticalRate * (1f - VERTICAL_TRIM) + liveUp * VERTICAL_TRIM).coerceIn(-1.0f, 1.0f)
            }
        }

        // Cruise = three INDEPENDENT latched sets, all ADDITIVE while cruising: a held input nudges the held
        // value (oldSpeed / cruiseTurnOmega / cruiseVerticalRate), release maintains, and holding the OPPOSITE
        // input for the set's duration cancels just that set (unified cancel block below). A set NOT engaged at
        // activation stays free/live. Forward + turn pass the LIVE input through (so the additive ramps respond
        // and free sets steer); vertical replays the latched rate while latched, else live. Seat facing + the
        // held oldSpeed give the cruise heading/speed, so steering rotates the held velocity vector (circling).
        // A recorded route carries elevation, so while one is being followed it owns the vertical -- ahead of
        // the cruise latch, behind the pilot. Null when nothing is following, which leaves every case below
        // exactly as it was.
        val pathVertical = pathVerticalRate
        val liveUp = liveControl?.upImpulse ?: 0.0f

        val effective = if (isCruising) {
            controlData?.let { frozen ->
                ControlData(
                    frozen.seatInDirection,
                    liveControl?.forwardImpulse ?: 0.0f,
                    liveControl?.leftImpulse ?: 0.0f,
                    if (pathVertical != null && liveUp == 0.0f) pathVertical
                    else if (cruiseVerticalActive) cruiseVerticalRate
                    else liveUp,
                    frozen.sprintOn
                )
            }
        } else if (pathVertical != null) {
            // Cruise off, but still fly the route's elevation.
            controlData?.let { frozen ->
                ControlData(
                    frozen.seatInDirection,
                    frozen.forwardImpulse,
                    frozen.leftImpulse,
                    if (liveUp != 0.0f) liveUp else pathVertical,
                    frozen.sprintOn
                )
            }
        } else {
            controlData
        }

        // True when there's a COMMANDED vertical -- live pilot input, OR a latched cruise climb/descend rate --
        // so the water altitude-hold drives that rate; zero (a free set on release, or a latched rate trimmed
        // to 0) lets it spring-hold the current altitude.
        val verticalInputActive = (effective?.upImpulse ?: 0.0f) != 0.0f

        effective?.let { control ->
            applyPlayerControl(control, body, thrustMultiplier)
            idealUpwardVel = getPlayerUpwardVel(control, body.mass, balloonForceProvided)
        }

        // region Elevation
        if (holdEngaged) {
            // verticalInputActive (computed above) is true whenever the pilot actively presses
            // ascend/descend -- including while cruising -- so cruise holds the depth only until you
            // press a key, and ascend/descend never drops cruise.
            applyWaterAltitudeHold(body, idealUpwardVel, verticalInputActive)
        } else {
            val idealUpwardForce = (idealUpwardVel.y() - vel.y() - (GRAVITY / cfg.elevationSnappiness)) *
                    mass * cfg.elevationSnappiness

            body.applyForce(Vector3d(0.0,
                min(balloonForceProvided, max(idealUpwardForce, 0.0)) +
                // Add drag to the y-component
                vel.y() * -mass,
                0.0)
            )
        }
        // endregion

        // === Per-set "hold the opposite input to cancel" ===
        // Holding the input opposite a latched set's influence for the set's configured seconds unlatches just
        // that set (back to free/live), leaving the others AND leaving the whole cruise ON. Cruise no longer
        // auto-offs when the last set is canceled: with the helm-menu cruise, "cruising with no recorded sets"
        // is a valid idle state (the pilot re-records by driving or typing values). Cruise only ends on an
        // explicit off -- the C toggle, the helm "Cruise Control" master, or anchoring. A ship changing
        // CATEGORY deliberately does not end cruise: a hybrid crossing a shoreline under cruise keeps it.
        if (isCruising) {
            // Track each latched set's intent direction from its influence (retain inside the dead-zone), then
            // cancel the set when the OPPOSITE input is held for the set's duration. Reading abs()/sign() of one
            // signed @Volatile gives duration + held-direction atomically. Collect the set(s) canceled THIS tick
            // for the optional debug action-bar line (HOLD-cancels only -- the C toggle never enters this block).
            val canceledSets = ArrayList<String>(3)
            if (cruiseHorizontalActive) {
                signOfD(oldSpeed, 0.05).let { if (it != 0) fwdIntentSign = it }
                if (fwdCancel.update(abs(fwdHold), signOfD(fwdHold, 0.0), fwdIntentSign, EurekaConfig.SERVER.horizontalCancelHold)) {
                    cruiseHorizontalActive = false
                    canceledSets.add("Horizontal Disabled")
                }
            }
            if (cruiseVerticalActive) {
                signOfD(cruiseVerticalRate.toDouble(), 0.05).let { if (it != 0) vertIntentSign = it }
                if (vertCancel.update(abs(vertHold), signOfD(vertHold, 0.0), vertIntentSign, EurekaConfig.SERVER.verticalCancelHold)) {
                    cruiseVerticalActive = false
                    canceledSets.add("Vertical Disabled")
                }
            }
            if (cruiseTurning) {
                signOfD(cruiseTurnOmega ?: 0.0, 0.02).let { if (it != 0) turnIntentSign = it }
                if (turnCancel.update(abs(turnHold), signOfD(turnHold, 0.0), turnIntentSign, EurekaConfig.SERVER.turnCancelHold)) {
                    clearCruiseTurn()
                    canceledSets.add("Turn Disabled")
                }
            }
            // Dev lever: action-bar the set(s) just HOLD-canceled (fading overlay, like showCruiseStatus). Before
            // the auto-off line below so it always shows the specific set even when this was the last active set.
            if (EurekaConfig.SERVER.debugCruiseCancel && canceledSets.isNotEmpty()) {
                seatedPlayer?.displayClientMessage(
                    Component.literal("Cruise Control: " + canceledSets.joinToString(" | ")), true
                )
            }
            // (Removed) auto-off when every set is canceled: cruise now persists idle until an explicit off.
        }

        physShip.isStatic = armadaAnchored
    }

    /**
     * True when ANY ship in the armada led by [parent] has an anchor down -- [parent]'s own, or any welded
     * member's.
     *
     * Anchors hold the VESSEL, not the hull that happens to carry them, for the same reason balloons and engines
     * pool. Left per-hull it went wrong in both directions: a child that anchored itself went static while the
     * rest of the armada drove, so the welds turned it into a mooring the control law never saw -- the formation
     * simply refused to move, with nothing at the helm to say why. And an anchored parent went static while its
     * children stayed free, leaving them hanging off the joints.
     *
     * The lead reaches the same answer through its own pooled count, so this is only for the child side. Both
     * read the same game-thread fields rather than each other's results, so it holds whatever order their phys
     * ticks run in.
     */
    private fun armadaAnchored(parent: LoadedServerShip): Boolean {
        if ((parent.getAttachment(EurekaShipControl::class.java)?.anchorsActive ?: 0) > 0) return true
        val parentArmada = ArmadaShipControl.get(parent) ?: return false
        for (member in parentArmada.childShips.values) {
            if ((member.getAttachment(EurekaShipControl::class.java)?.anchorsActive ?: 0) > 0) return true
        }
        return false
    }

    // Vertical controller used while the water altitude-hold is engaged. It OWNS the Y axis and
    // applies gravity feed-forward (-GRAVITY * mass), so the ship neither sinks nor needs balloon lift
    // to stay put. On top of that it either drives a commanded velocity (player actively ascending/
    // descending) or holds a latched Y with a critically-damped spring (hands off / cruising). Unlike
    // the stock balloon force this net force can be up OR down, so it pins the ship against buoyancy
    // that would otherwise float it out of the water. The force is purely vertical, so horizontal
    // sailing speed and momentum are never touched -- no jolt, no slow-down.
    private fun applyWaterAltitudeHold(
        body: ArmadaBody,
        idealUpwardVel: Vector3dc,
        verticalInputActive: Boolean
    ) {
        val vel = body.velocity
        val mass = body.mass
        // The armada's own centre, so a formation holds ITS depth rather than the lead hull's -- and the hold
        // force, being distributed by mass share, lifts it level instead of tipping it about that hull.
        val currentY = body.centerOfMass.y()
        val appliedY = if (verticalInputActive) {
            // Actively moving: drive toward the same commanded vertical velocity the helm already uses
            // (so it feels identical), and keep the setpoint pinned here so releasing the key holds at
            // this exact depth. Net force = mass * elevationSnappiness * (targetVel - vel.y).
            holdTargetY = currentY
            ((idealUpwardVel.y() - vel.y()) * cfg.elevationSnappiness - GRAVITY) * mass
        } else {
            // Holding: critically-damped spring to the latched Y. -GRAVITY cancels weight; the spring
            // and damping (the net force after gravity) pull the ship back to exactly holdTargetY and
            // settle it there with no overshoot. Latch on first hold tick if not already set.
            val target = holdTargetY ?: currentY.also { holdTargetY = it }
            val k = cfg.waterAltitudeHoldStiffness
            val c = 2.0 * sqrt(k) // critical damping: firm hold, no oscillation/bounce
            ((target - currentY) * k - vel.y() * c - GRAVITY) * mass
        }
        body.applyForce(Vector3d(0.0, appliedY, 0.0))
    }

    private fun getControlData(player: SeatedControllingPlayer): ControlData {

        val currentControlData = ControlData.create(player)

        if (!wasCruisePressed && player.cruise) {
            if (!isCruising) {
                // Turn cruise ON, ENGAGE-TO-LATCH: latch each set whose input is active right now -- horizontal
                // (W/S), vertical (Space/V), turn (A/D). A set with no input stays free/live (turn auto-
                // straightens, vertical eases to a hover).
                val latchH = currentControlData.forwardImpulse != 0.0f
                val latchV = currentControlData.upImpulse != 0.0f
                // Only latch turn when turn-cruise is enabled -- otherwise the orbit rate is never captured and
                // the set would be stuck (uncancellable); the turn key stays free/live.
                val latchT = currentControlData.leftImpulse != 0.0f && EurekaConfig.SERVER.enableTurnCruise
                // Cruise now turns on even with NOTHING engaged (was: gated on latchH||latchV||latchT). The
                // no-recorded-input auto-off was removed so a helm-menu-activated cruise (and a bare C press)
                // stays on, idle, until the pilot records inputs by driving or types exact values in the helm.
                isCruising = true
                controlData = currentControlData
                cruiseHorizontalActive = latchH
                cruiseVerticalActive = latchV
                cruiseVerticalRate = if (latchV) currentControlData.upImpulse else 0.0f
                cruiseTurning = latchT
                if (!latchT) cruiseTurnOmega = null
                // Seed each latched set's intent direction so hold-opposite-to-cancel works from the start.
                fwdIntentSign = if (latchH) signOf(currentControlData.forwardImpulse) else 0
                vertIntentSign = if (latchV) signOf(currentControlData.upImpulse) else 0
                turnIntentSign = if (latchT) signOf(currentControlData.leftImpulse) else 0
                showCruiseStatus()
            } else {
                // Turn cruise OFF.
                isCruising = false
                clearCruiseLatches()
                showCruiseStatus()
            }
        }
        // Per-set cancel is no longer an instant opposite-input toggle: it's a HELD gesture (hold the opposite
        // input for the set's duration), handled uniformly for all three sets on the physics thread in physTick.

        return currentControlData
    }

    /**
     * [body] is the armada this ship leads seen as one rigid body, or just this ship when nothing is bound to
     * it. Note that the TURN RADIUS reference below stays the lead hull's own: the armada is deliberately
     * steered at the rate the parent would manage alone, rather than the slower rate its total size implies.
     * The body then delivers that commanded rate against the formation's real inertia.
     */
    private fun applyPlayerControl(control: ControlData, body: ArmadaBody, thrustMultiplier: Double) {

        val ship = ship ?: return
        val lead = body.lead
        val transform = lead.transform
        val aabb = ship.worldAABB
        val center = transform.positionInWorld

        // region Player controlled rotation
        val omega: Vector3dc = body.angularVelocity

        val largestDistance = run {
            var dist = center.distance(aabb.minX(), center.y(), aabb.minZ())
            dist = max(dist, center.distance(aabb.minX(), center.y(), aabb.maxZ()))
            dist = max(dist, center.distance(aabb.maxX(), center.y(), aabb.minZ()))
            dist = max(dist, center.distance(aabb.maxX(), center.y(), aabb.maxZ()))

            dist
        }.coerceIn(0.5, cfg.maxSizeForTurnSpeedPenalty)

        // === Turn law: engine-independent, with the TURN cruise set (one of the 3 independent sets) ===
        // largestDistance (computed above) = the ship's turn radius reference.
        // turnSpeed = base turn rate; turnAcceleration ramps in only after holding past turnAccelDelay.
        val r = largestDistance
        val baseCap = cfg.turnSpeed / r
        val delay = cfg.turnAccelDelay.coerceAtLeast(0.05)
        val held = abs(turnHold) // continuous turn-hold seconds (measured game-side at fixed 20 TPS)
        val turnKeyHeld = control.leftImpulse != 0.0f
        val dir = if (control.leftImpulse > 0.0f) 1.0 else if (control.leftImpulse < 0.0f) -1.0 else 0.0
        // Turn cruise is live only in free flight (never while disassembling/aligning, where yaw must snap to grid).
        val freeFlight = isCruising && !disassembling && !aligning && EurekaConfig.SERVER.enableTurnCruise
        // turnLatched = an orbit is armed (Case B: a turn was active at activation). Else Case A: free,
        // self-centering turning even while cruising (other sets unaffected). Canceling the orbit = hold the
        // OPPOSITE turn for turnCancelHold seconds (the unified per-set cancel block in physTick).
        val turnLatched = freeFlight && cruiseTurning

        // turnAcceleration engages only after holding past the delay (faster influence / sharper free turn).
        val accelerating = turnKeyHeld && held > delay
        val effCap = (if (accelerating) baseCap + (cfg.turnAcceleration / r) * (held - delay) else baseCap)
            .coerceAtMost(TURN_OMEGA_MAX_LINEAR / r)
        // Rate omega.y changes per second: base ramp reaches baseCap in `delay`s (so taps stay small), then the
        // turnAcceleration rate. Also the maintain/center brake rate.
        val chase = if (accelerating) max(baseCap / delay, cfg.turnAcceleration / r) else baseCap / delay
        // The maintain branch (a latched orbit rate with no live turn key) converges omega.y to
        // cruiseTurnOmega at a P-rate capped here. A C-captured orbit already sits at its rate, so its gap is
        // tiny and the gentle base cap is plenty. But a MENU-TYPED cruiseTurnOmega starts from omega.y = 0,
        // and on a large ship (r up to maxSizeForTurnSpeedPenalty) baseCap/delay is so small the ship crawls
        // -- it reads as "not turning". Cap the maintain approach at the turnAcceleration rate so a typed rate
        // is reached in ~a second regardless of ship size; the tiny-gap C-cruise hold is unaffected (its
        // error stays well under this cap, so the applied alpha is unchanged).
        val maintainChase = max(chase, cfg.turnAcceleration / r)

        // A live path command outranks both cruise cases but never the pilot: grabbing the wheel always
        // wins, and pure pursuit simply re-acquires the line once the key is released.
        val pathOmega = pathTurnOmega

        val idealAlphaY: Double = when {
            turnKeyHeld ->
                // Hold to turn: converge toward the turnSpeed cap (effCap) in the held direction. The ship
                // starts turning at the base rate IMMEDIATELY; only after turnAccelDelay of continuous hold
                // does effCap climb (turnAcceleration) for a sharper turn. Latched or not, the HOLD feel is
                // identical -- the ONLY difference is on RELEASE (below): an armed/latched turn keeps the rate
                // it reached, a free turn re-centers. (Previously a latched hold ADDED influence from zero, so
                // on a big ship nothing perceptible happened until acceleration kicked in and then it lurched;
                // this converges smoothly, with the 0.6 s delay gating only the acceleration as intended.)
                (effCap * dir - omega.y()).coerceIn(-chase, chase)
            pathOmega != null ->
                // Following a recorded route: converge to the rate the follower asked for. maintainChase
                // is the right limit here for the same reason it is for a MENU-TYPED cruise rate (see
                // above) -- the command arrives with omega.y at 0 and no hold to ramp it in, so the gentler
                // base cap would leave a large hull visibly failing to make the corner.
                (pathOmega - omega.y()).coerceIn(-maintainChase, maintainChase)
            turnLatched ->
                // Released while armed / a menu-typed rate: converge to and hold the locked orbit rate.
                ((cruiseTurnOmega ?: 0.0) - omega.y()).coerceIn(-maintainChase, maintainChase)
            else ->
                // Released while free (not cruising): brake back to center.
                (0.0 - omega.y()).coerceIn(-chase, chase)
        }

        // While the orbit is armed, track the achieved rate so a held/tapped adjustment sticks once released.
        if (turnLatched && turnKeyHeld) {
            cruiseTurnOmega = omega.y()
        }

        // The commanded yaw acceleration (which covers both the turn and the brake back to centre, since they
        // share idealAlphaY) plus the cosmetic roll into it. The two are about perpendicular axes, so they add
        // exactly and the body only has to distribute once. Sizing this for the armada's real inertia rather
        // than one hull's is the body's job -- nothing here needs to know how much is bound on.
        val idealAlpha = Vector3d(0.0, idealAlphaY, 0.0)
            .add(getPlayerControlledBankingAlpha(control, lead, -idealAlphaY))
        body.applyAngularAcceleration(idealAlpha)
        // endregion

        body.applyForce(getPlayerForwardVel(control, body).mul(thrustMultiplier))
    }

    /**
     * The roll INTO a turn, as an angular acceleration about the lead's left/right axis. Cosmetic: it banks the
     * hull the way an aircraft leans into a corner.
     *
     * Returns an acceleration rather than a torque because the caller hands it to the body, which is what knows
     * the inertia to convert it against -- one hull's, or the whole armada's.
     */
    private fun getPlayerControlledBankingAlpha(control: ControlData, lead: PhysShip, strength: Double): Vector3d {
        val rotationVector = control.seatInDirection.unitVec3i.toJOMLD()
        lead.transform.shipToWorldRotation.transform(rotationVector)
        rotationVector.y = 0.0
        rotationVector.mul(strength * 1.5)

        return rotationVector
    }

    // Player controlled forward and backward thrust
    private fun getPlayerForwardVel(control: ControlData, body: ArmadaBody): Vector3d {

        val lead = body.lead
        val scaledMass = body.mass * cfg.speedMassScale
        val vel: Vector3dc = body.velocity

        // region Player controlled forward and backward thrust
        // Heading is the LEAD's: the seat that reports "forward" is bolted to the parent.
        val forwardVector = control.seatInDirection.unitVec3i.toJOMLD()
        lead.transform.shipToWorldRotation.transform(forwardVector)
        forwardVector.normalize()

        val s = 1 / smoothingATanMax(
            cfg.linearMaxMass,
            body.mass * cfg.linearMassScaling + cfg.linearBaseMass
        )

        // Throttle smoothing. When the HORIZONTAL set is latched it's ADDITIVE: only update oldSpeed while a
        // forward/back input is held (a held key nudges the speed toward +/-1 -- tap = small, hold = ramp), and
        // HOLD it on release so the ship keeps that speed. When not latched it's live (decays to 0 on release).
        // Steering rotates the held velocity (forwardVector uses the live heading), so a turn makes it circle.
        // Follow guidance owns the throttle outright while it is engaged, which is the one way it differs from a
        // route (see [pathTargetSpeed]). Same closed loop as the typed cruise speed below, and deliberately
        // AHEAD of the latch test: a ship can be told to hold station whether or not it was ever cruising, so
        // gating this on cruiseHorizontalActive would silently do nothing on a ship that had never used cruise.
        //
        // Yielding the moment a real forward/back input appears is what makes the pilot's throttle authoritative
        // instantly -- the follow doesn't have to notice and let go first, so there is never a tick where the two
        // are pulling against each other. Holding that input is separately what ends the follow, but that is the
        // orchestrator's business and takes three seconds; this takes effect on the next physics tick.
        val followTarget = pathTargetSpeed
        if (followTarget != null && control.forwardImpulse == 0.0f) {
            val actualForward = vel.dot(forwardVector)
            val topRef = max(estimateTopSpeed(), 1.0)
            oldSpeed = (oldSpeed + (followTarget - actualForward) / topRef * CRUISE_SPEED_TRIM).coerceIn(-1.0, 1.0)
        } else if (!cruiseHorizontalActive || control.forwardImpulse != 0.0f) {
            oldSpeed = oldSpeed * (1 - s) + control.forwardImpulse.toDouble() * s // from -1 to 1.
            // A live forward/back input hands control back to the pilot: drop the exact typed target so driving
            // isn't fought by the trim below.
            if (control.forwardImpulse != 0.0f) cruiseTargetSpeedMps = null
        } else cruiseTargetSpeedMps?.let { target ->
            // Closed-loop trim: nudge the throttle so the ship's REAL forward speed converges to the typed m/s,
            // instead of the open-loop oldSpeed = typed / estimateTopSpeed() that drifted with engine heat/fuel.
            // Forward component of velocity (vel . heading) so a slight sideways drift doesn't skew the reading.
            val actualForward = vel.dot(forwardVector)
            val topRef = max(estimateTopSpeed(), 1.0)
            oldSpeed = (oldSpeed + (target - actualForward) / topRef * CRUISE_SPEED_TRIM).coerceIn(-1.0, 1.0)
        }
        // Target speed in REAL m/s from here down. The base term is the throttle fraction times the
        // engine-less speed the config allows: linearCasualSpeed/3 is the historical throttle scale and
        // baseSpeed converts it to m/s, so an engine-less ship still tops out at exactly baseSpeed.
        var speed = oldSpeed * cfg.linearCasualSpeed / 3 * cfg.baseSpeed

        val engineForceLinear = pooledForceLinear
        if (engineForceLinear != 0.0) {
            // engine boost
            val boost = max((engineForceLinear - cfg.enginePowerLinear * cfg.engineBoostOffset) * cfg.engineBoost, 0.0)
            // Kept in a local. extraForceLinear is a FIELD that onServerTick refreshes from the engines once
            // per SERVER tick, while this runs once per PHYSICS tick, and there are more of those. Boosting
            // and dividing it in place therefore CONSUMED it: the first physics tick after a server tick saw
            // the real engine force, and every one after it saw that force divided by the ship's mass a
            // second time, i.e. as good as nothing. Those ticks commanded the engine-less base speed, so the
            // controller braked with a force proportional to the ship's velocity -- which is exactly why the
            // shortfall behaved like textbook linear drag and kept the same ratio however the config was
            // scaled. It was never drag; it was the engine force being spent on the first tick that read it.
            val enginePower =
                (engineForceLinear + boost + boost * boost * cfg.engineBoostExponentialPower) /
                    scaledMass

            speed += if (speed < 0) {
                smoothingATanMax(cfg.maxReverseSpeedFromEngines, enginePower * oldSpeed)
            } else {
                smoothingATanMax(cfg.maxSpeedFromEngines, enginePower * oldSpeed)
            }

            // Engine heat drain: track how much of the engine power is being used this phys tick
            // (full when sprinting, else throttle fraction -- oldSpeed is the smoothed forward
            // impulse, -1..1). onServerTick converts the accumulated total into `consumed`, which
            // EngineBlockEntity subtracts from its heat. Upstream removed its equivalent line in
            // a2f1f7b ("Fixed ships flying way too fast"), silently making heat drain a no-op;
            // this restores the intended fuel-burn feedback loop.
            val consumption = if (control.sprintOn) 1f else min(abs(oldSpeed), 1.0).toFloat()
            physConsumption += consumption
            // Every welded member's engines run at the armada's throttle, so they burn their fuel at it too --
            // otherwise a child's engines would push the formation (its power is pooled above) for free.
            for (member in armadaMembers) member.physConsumption += consumption
        }

        // Drag trim: integrate the shortfall between the speed being asked for and the speed actually
        // being made, so the ship converges on the former instead of stalling out below it (see
        // dragTrimMps). Skipped while a typed cruise target is set, because that path already closes its
        // own loop on real speed by trimming the throttle -- two integrators on one plant would fight.
        // forwardVector is still the unit heading here, so this dot product is the forward component of
        // velocity, which ignores any sideways drift.
        // pathTargetSpeed is excluded for exactly the reason cruiseTargetSpeedMps is: it runs its own closed loop
        // on real speed above, and two integrators correcting the same shortfall wind each other up.
        if (cruiseTargetSpeedMps == null && pathTargetSpeed == null && abs(speed) > DRAG_TRIM_DEAD_ZONE) {
            val error = speed - vel.dot(forwardVector)
            // Only correct once the ship is in the last stretch of its run-up. Early in the run the error is
            // most of the target and isn't a shortfall at all, just acceleration still happening; integrating
            // it there would wind the correction far past anything the residual justifies and carry the ship
            // over the top speed the helm advertises, which is meant to be a ceiling. Outside the band the
            // trim is held rather than bled off, so it survives a burst of throttle or a knock off course.
            if (abs(error) < abs(speed) * DRAG_TRIM_BAND) {
                // Bounded so a ship that physically cannot make its speed -- grounded, anchored, or pushing
                // against something -- winds the trim up to a limit and stops, rather than without end.
                val maxTrim = abs(speed) * DRAG_TRIM_MAX_FRACTION
                dragTrimMps = (dragTrimMps + error * DRAG_TRIM_GAIN).coerceIn(-maxTrim, maxTrim)
            }
        } else {
            dragTrimMps *= DRAG_TRIM_RELEASE
        }
        speed += dragTrimMps

        // Corner ceiling: a route can ask for a turn tighter than the hull can hold at the speed it is making,
        // and the ship then runs wide and has to converge back. The follower works out the fastest speed at
        // which this hull can actually stay on the line through what is coming up, and that lands here as a
        // CEILING -- clamped, never raised, and never sign-flipped, so a slower throttle setting stays slower
        // and reverse stays reverse. The pilot keeps the throttle; the route only ever asks for less of it.
        pathSpeedCap?.let { cap -> speed = speed.coerceIn(-cap, cap) }

        // Target velocity, already in m/s. This used to be scaled by baseSpeed a SECOND time here, which
        // was right for the base term (it cancelled the /3 above) but silently tripled the engine term as
        // well -- so maxSpeedFromEngines = 24 actually commanded ~72 m/s, and the helm's "Top Speed"
        // readout, which mirrors this function up to that multiply, reported exactly a third of the real
        // cap. The base term now carries its own baseSpeed conversion, so the engine term is added in m/s
        // and the config key finally means what it says.
        forwardVector.mul(speed)

        val playerUpDirection = lead.transform.shipToWorldRotation.transform(Vector3d(0.0, 1.0, 0.0))
        val velOrthogonalToPlayerUp = vel.sub(playerUpDirection.mul(playerUpDirection.dot(vel)), Vector3d())

        // Velocity-error P controller: the target is a true ceiling, since overshooting it flips the sign.
        val forwardForce = forwardVector.sub(velOrthogonalToPlayerUp).mul(scaledMass)

        return forwardForce
    }

    // Player controlled elevation. [mass] and [balloonForceProvided] are the ARMADA's (pooled), so a formation
    // climbs on the lift it carries as a whole rather than on whatever the lead hull happens to hold.
    private fun getPlayerUpwardVel(control: ControlData, mass: Double, balloonForceProvided: Double): Vector3d {
        if (control.upImpulse != 0.0f) {

            return Vector3d(0.0, 1.0, 0.0)
                .mul(control.upImpulse.toDouble())
                .mul(
                    if (control.upImpulse < 0.0f) {
                        cfg.baseImpulseDescendRate
                    }
                    else {
                        cfg.baseImpulseElevationRate +
                                // Smoothing for how the elevation scales as you approaches the balloonElevationMaxSpeed
                                smoothing(2.0, cfg.balloonElevationMaxSpeed, balloonForceProvided / mass)
                    }
                )
        }
        return Vector3d(0.0, 0.0, 0.0)
    }

    private fun showCruiseStatus() {
        val cruiseKey = if (isCruising) "hud.vs_eureka.start_cruising" else "hud.vs_eureka.stop_cruising"
        seatedPlayer?.displayClientMessage(Component.translatable(cruiseKey), true)
    }

    // Clears any locked turn-cruise orbit. Called on EVERY cruise-end path (toggle off, opposite-input
    // cancel, anchor, dismount-while-not-cruising, reload with no captured course) so the persisted
    // cruiseTurning flag -- which the stabilize() yaw gate reads -- can never outlive the orbit.
    private fun clearCruiseTurn() {
        cruiseTurnOmega = null
        cruiseTurning = false
        turnIntentSign = 0
        turnCancel.reset()
    }

    // Clears ALL three cruise latch sets (horizontal / vertical / turn) -- used on the whole-cruise-end paths
    // (C toggled off, anchored, dismount-while-not-cruising, reload with no captured course).
    private fun clearCruiseLatches() {
        cruiseHorizontalActive = false
        cruiseVerticalActive = false
        cruiseVerticalRate = 0.0f
        cruiseTargetSpeedMps = null
        fwdIntentSign = 0
        vertIntentSign = 0
        fwdCancel.reset()
        vertCancel.reset()
        clearCruiseTurn()
    }

    private fun signOf(v: Float): Int = if (v > 0.0f) 1 else if (v < 0.0f) -1 else 0
    private fun signOfD(v: Double, eps: Double): Int = if (v > eps) 1 else if (v < -eps) -1 else 0

    // One game tick (1/20 s) of SIGNED continuous-hold accounting for an axis (sign = held direction, abs =
    // seconds): 0 on release, one signed tick on a fresh press / direction flip, else accumulate.
    private fun accumSigned(impulse: Float, prev: Double): Double {
        val sign = signOf(impulse)
        if (sign == 0) return 0.0
        return if (sign == signOfD(prev, 0.0)) prev + sign * 0.05 else sign * 0.05
    }

    // Accumulates per-axis signed hold time on the fixed-rate GAME thread (physics TPS is variable). Called
    // once per game tick from the helm (guarded against multiple helms). The physics thread reads abs() for the
    // turn-acceleration phase and abs()+sign() for the per-set hold-to-cancel gesture (one atomic read each).
    fun updateInputHolds(gameTime: Long, forwardImpulse: Float, leftImpulse: Float, upImpulse: Float) {
        if (gameTime == turnHoldLastGameTick) return
        turnHoldLastGameTick = gameTime
        turnHold = accumSigned(leftImpulse, turnHold)
        fwdHold = accumSigned(forwardImpulse, fwdHold)
        vertHold = accumSigned(upImpulse, vertHold)
    }

    var powerLinear = 0.0
    var powerAngular = 0.0
    var anchors = 0 // Amount of anchors
        set(v) {
            field = v; deleteIfEmpty()
        }

    // Counted on the GAME thread and read on the PHYSICS thread -- by this ship, and across the armada by every
    // other member, since one anchor holds the whole vessel ([armadaAnchored]). Volatile for that cross-thread,
    // cross-ship read.
    @Volatile
    var anchorsActive = 0 // Anchors that are active

    // balloons/floaters are counted on the GAME thread and read on the PHYSICS thread -- by this ship, and by
    // the parent when this one is a welded armada member, which pools them across the formation. Volatile for
    // that cross-thread, cross-ship read.
    @Volatile
    var balloons = 0 // Amount of balloons
        set(v) {
            field = v; deleteIfEmpty()
        }

    var helms = 0 // Amount of helms
        set(v) {
            field = v; deleteIfEmpty()
        }

    @Volatile
    var floaters = 0 // Amount of floaters * 15
        set(v) {
            field = v; deleteIfEmpty()
        }

    // Captured at assembly (ShipHelmBlockEntity.assemble) and persisted as part of this attachment so the
    // helm menu can show ship stats: `engines` drives the top-speed estimate, `assembledBlocks` is the
    // non-air block count shown as "Blocks:". Plain vars (no deleteIfEmpty) so they never drop the attachment.
    var engines = 0
    var assembledBlocks = 0

    // Engine fuel-tank aggregate for the helm's "Engine Power: X%" readout. Each engine reports its fuel level
    // (a 0..1 fraction of a full fuel slot) once per tick via [reportEngineFuel]; onServerTick averages last
    // tick's reports into [engineFuelPercent] (0..100). Double-buffered (accum vs accumNext) so the value is
    // order-independent of whether the engine block-entity ticks run before or after onServerTick. The per-
    // engine fraction counts the unburned reservoir PLUS the burning charge and is monotonic as it burns, so
    // the aggregate no longer sawtooths (it was bouncing 53<->71% off the per-item burn-time cycle before).
    @JsonIgnore private var engineFuelAccum = 0.0
    @JsonIgnore private var engineFuelCount = 0
    @JsonIgnore private var engineFuelAccumNext = 0.0
    @JsonIgnore private var engineFuelCountNext = 0
    @JsonIgnore
    var engineFuelPercent = 0
        private set

    /** Called by each [org.valkyrienskies.eureka.blockentity.EngineBlockEntity] once per tick with its fuel
     *  level as a 0..1 fraction of a full fuel slot (reservoir + burning charge). */
    fun reportEngineFuel(fraction: Double) {
        engineFuelAccumNext += fraction.coerceIn(0.0, 1.0)
        engineFuelCountNext++
    }

    private fun deleteIfEmpty() {
        if (helms <= 0 && floaters <= 0 && anchors <= 0 && balloons <= 0) {
            ship?.removeAttachment(EurekaShipControl::class.java)
        }
    }

    /**
     * f(x) = max - smoothing / (x + (smoothing / max))
     */
    private fun smoothing(smoothing: Double, max: Double, x: Double): Double = max - smoothing / (x + (smoothing / max))

    /**
     * g(x) = (tan^(-1)(x * smoothing)) / smoothing
     */
    private fun smoothingATan(smoothing: Double, x: Double): Double = atan(x * smoothing) / smoothing

    // limit x to max using ATan
    private fun smoothingATanMax(max: Double, x: Double): Double = smoothingATan(1 / (max * 0.638), x)

    // region Helm-menu cruise control (server-side; called from ShipHelmBlockEntity)
    // Read-only views of the three per-set latch flags so the helm checkboxes reflect the live state.
    val cruiseHorizontalArmed: Boolean @JsonIgnore get() = cruiseHorizontalActive
    val cruiseVerticalArmed: Boolean @JsonIgnore get() = cruiseVerticalActive
    val cruiseTurnArmed: Boolean @JsonIgnore get() = cruiseTurning

    // Current latched values in human units, for the helm textbox readouts (signed: +fwd/-rev, +up/-down,
    // +/- turn). Vertical/turn read their locked setpoints; speed reads the ship's ACTUAL velocity.
    //
    // Speed is sourced from ship.velocity.length() -- the exact same value the speed HUD shows
    // ([EurekaSpeedHud]) -- so the helm readout and the HUD always agree. The old throttle-derived estimate
    // (oldSpeed * topSpeed) drifted well under the realized speed (e.g. showed 9 while the HUD read 16.6).
    // Signed by the throttle direction so reverse cruise reads negative; ~0 while idle.
    fun cruiseSpeedMps(): Double {
        val v = ship?.velocity?.length() ?: 0.0
        return if (oldSpeed < 0.0) -v else v
    }
    fun cruiseVerticalMps(): Double {
        val rate = cruiseVerticalRate.toDouble()
        return if (rate >= 0.0) rate * cfg.baseImpulseElevationRate else rate * cfg.baseImpulseDescendRate
    }
    fun cruiseTurnDegPerSec(): Double = (cruiseTurnOmega ?: 0.0) * 180.0 / PI

    // A helm-menu cruise has no seated pilot, so there is no ControlData -- and without one physTick's `effective`
    // is null, applyPlayerControl never runs, and a typed speed/turn/vertical can't move the ship (thrust needs a
    // seatInDirection to push along). Seed a zero-input course facing the helm the FIRST time menu cruise touches
    // this ship; the latched set values then drive it, and a pilot who mounts later steers additively on top. The
    // seat direction matches a real pilot's exactly (VSGamePackets feeds seatInDirection = seat.direction.opposite,
    // and the helm seat faces HORIZONTAL_FACING, so ShipHelmBlockEntity passes facing.opposite here) -- so the menu
    // and the C key agree on "forward". cruiseSeatDir is stamped too so the course survives a world reload.
    private fun ensureMenuCourse(seatDir: Direction) {
        if (controlData == null) {
            controlData = ControlData(seatDir)
            cruiseSeatDir = seatDir.ordinal
        }
    }

    // Helm "Cruise Control" master. Unlike the C key this can enable cruise with NO sets latched, and it STAYS
    // on (the no-input auto-off was removed) so the pilot can then record inputs by driving or type exact
    // values. Turning it off cancels every set, exactly like a C toggle-off.
    fun setCruiseFromMenu(enable: Boolean, seatDir: Direction) {
        if (enable == isCruising) return
        isCruising = enable
        if (enable) ensureMenuCourse(seatDir) else clearCruiseLatches()
        showCruiseStatus()
    }

    // Arm/disarm one cruise set from a helm checkbox (0 = speed/horizontal, 1 = turn, 2 = vertical). Arming a
    // set with no recorded value leaves it free/live at 0 until the pilot drives or types a value. Arming any
    // set also switches cruise on.
    fun setCruiseAxisArmed(axis: Int, armed: Boolean, seatDir: Direction) {
        when (axis) {
            0 -> { cruiseHorizontalActive = armed; if (!armed) { fwdIntentSign = 0; fwdCancel.reset(); cruiseTargetSpeedMps = null } }
            1 -> { if (armed) cruiseTurning = true else clearCruiseTurn() }
            2 -> { cruiseVerticalActive = armed; if (!armed) { cruiseVerticalRate = 0.0f; vertIntentSign = 0; vertCancel.reset() } }
        }
        if (armed) { isCruising = true; ensureMenuCourse(seatDir) }
    }

    // Manual cruise value entry from a helm textbox, clamped SERVER-side to what the ship can physically do
    // (the client sends the raw typed value; the server owns the real config + ship size). axis: 0 = speed m/s
    // (+fwd/-rev), 1 = turn deg/s (+/-), 2 = vertical m/s (+up/-down). Arms the set and turns cruise on.
    fun setCruiseValueMenu(axis: Int, value: Double, seatDir: Direction) {
        isCruising = true
        ensureMenuCourse(seatDir)
        when (axis) {
            0 -> {
                // Bounded by what the CONFIG allows any ship, NOT by this hull's estimated ceiling.
                //
                // Clamping the typed number to estimateTopSpeed() turned a model into a hard limit, and the
                // model is wrong often enough to matter -- it reads a captured engine count and, until
                // recently, the wrong hull's mass entirely. The pilot typed 20, the box accepted it, the ship
                // sat at 3, and nothing anywhere said why. The typed target needs no such protection: it is
                // held closed-loop below, and the throttle it trims saturates at 1.0, so asking for more than
                // the hull has simply means full power. Which is exactly what asking for it should mean.
                val fwdMax = cfg.baseSpeed + cfg.maxSpeedFromEngines
                val revMax = cfg.baseSpeed + cfg.maxReverseSpeedFromEngines
                val clamped = value.coerceIn(-revMax, fwdMax)
                // Exact target held closed-loop by getPlayerForwardVel; oldSpeed is only the initial throttle
                // seed (open-loop estimate) that the trim then corrects onto the real speed. Seeded off the
                // ESTIMATE, since a throttle fraction is by definition a fraction of what this hull can do --
                // an optimistic estimate just starts the run at full power, which the trim then eases back.
                cruiseTargetSpeedMps = clamped
                val estimate = max(estimateTopSpeed(), 1.0)
                oldSpeed = (if (clamped >= 0.0) clamped / estimate
                            else (if (revMax > 0.0) clamped / revMax else 0.0)).coerceIn(-1.0, 1.0)
                cruiseHorizontalActive = true
                fwdIntentSign = signOfD(oldSpeed, 0.05)
            }
            1 -> {
                // Clamp the typed turn rate to the SMALLER of the ship's physical limit and a fixed
                // CRUISE_TURN_DEG_MAX ceiling. The physical cap alone reached ~86 deg/s on large ships, which
                // the pilot found far too aggressive; CRUISE_TURN_DEG_MAX keeps the tightest cruise turn sane.
                val physMax = TURN_OMEGA_MAX_LINEAR / turnRadiusEstimate() // rad/s
                val maxOmega = minOf(physMax, CRUISE_TURN_DEG_MAX * PI / 180.0)
                val omega = (value * PI / 180.0).coerceIn(-maxOmega, maxOmega)
                cruiseTurnOmega = omega
                // Also seed the persisted mirror: if the resume block ever rebuilds cruiseTurnOmega (it fires
                // when controlData reads null on a physics tick), it restores from cruiseTurnOmegaSaved -- which
                // was otherwise 0 until the next mirror pass, wiping a just-typed rate to zero (dead straight).
                cruiseTurnOmegaSaved = omega
                cruiseTurning = true
                turnIntentSign = signOfD(omega, 0.02)
            }
            2 -> {
                val upMax = cfg.baseImpulseElevationRate
                val downMax = cfg.baseImpulseDescendRate
                val clamped = value.coerceIn(-downMax, upMax)
                cruiseVerticalRate = (if (clamped >= 0.0) (if (upMax > 0.0) clamped / upMax else 0.0)
                                      else (if (downMax > 0.0) clamped / downMax else 0.0))
                    .coerceIn(-1.0, 1.0).toFloat()
                cruiseVerticalActive = true
                vertIntentSign = signOfD(cruiseVerticalRate.toDouble(), 0.05)
            }
        }
    }

    // region Path following (server-side; called from org.valkyrienskies.eureka.path.PathFollower)

    /** Turn radius estimate in blocks, for sizing the follower's aim-ahead distance to the hull. */
    /**
     * Whether this attachment is wired up to its hull yet, and so whether [pathForward] can answer at all.
     *
     * False on a freshly loaded ship, because an [EurekaShipControl] only learns its own ship on the first tick of
     * its helm block entity (`ShipHelmBlockEntity.tick`), and a ship appears in VS's loaded-ship index before its
     * shipyard chunks are ticking block entities. [physTick] returns early on the same condition, so nothing about
     * the hull is live in that window either -- waiting for the helm is simply how this mod comes up.
     *
     * Path playback has to check it because it is the one thing here that gets STARTED by something other than a
     * pilot: a binding re-armed from the save file (see `ShipPaths.restore`) has no seated player whose presence
     * would have implied the helm was already running.
     */
    val pathHullReady: Boolean @JsonIgnore get() = ship != null

    val pathTurnRadius: Double @JsonIgnore get() = turnRadiusEstimate()

    /** The physical yaw-rate ceiling for this hull, rad/s -- the same cap a typed cruise turn is clamped to. */
    val pathTurnCap: Double @JsonIgnore get() =
        minOf(TURN_OMEGA_MAX_LINEAR / turnRadiusEstimate(), CRUISE_TURN_DEG_MAX * PI / 180.0)

    /**
     * The world-space direction thrust actually pushes along, written into [dest]; null when the ship has no
     * course yet.
     *
     * Derived exactly as [getPlayerForwardVel] derives it -- the seat's facing rotated by the hull's rotation.
     * A follower that measured heading any other way (the hull's +Z, say, or the velocity vector) would be
     * steering in a frame the thrust does not use, and the ship would crab along the route at a fixed angle.
     */
    fun pathForward(dest: Vector3d): Vector3d? {
        val dir = controlData?.seatInDirection ?: return null
        val transform = ship?.transform ?: return null
        dest.set(dir.unitVec3i.toJOMLD())
        transform.shipToWorldRotation.transform(dest)
        dest.y = 0.0
        return if (dest.lengthSquared() < 1.0e-9) null else dest.normalize()
    }

    /**
     * Make sure a course exists so thrust can be applied, and report whether one could be established.
     *
     * Same requirement (and same fix) as a helm-menu cruise: with `controlData == null` physTick's `effective`
     * is null, [applyPlayerControl] never runs, and neither a typed speed nor a path command can move the ship.
     * See [ensureMenuCourse].
     */
    fun pathBegin(): Boolean {
        val dir = controlData?.seatInDirection
            ?: helmSeatDir
            ?: cruiseSeatDir.takeIf { it in 0..5 }?.let { Direction.values()[it] }
            ?: return false
        // BEFORE the course, not after. This runs on the game thread while physTick runs on the physics thread,
        // and physTick clears `controlData` for a hull that is neither cruising nor following -- so setting the
        // course first leaves a window where the physics thread sees a course with nothing yet claiming it, and
        // wipes it. Nothing re-establishes it after that, because by then the flag says a course exists.
        pathFollowing = true
        ensureMenuCourse(dir)
        // Stamp the flat mirror unconditionally, not just via ensureMenuCourse's controlData == null branch. The
        // mirror at the top of physTick only runs while CRUISING, so a bound-but-not-cruising ship would save
        // with cruiseSeatDir still -1 -- and then this method, which is the first thing a re-armed binding calls
        // after a reload, would have nothing left to derive a course from and refuse. `dir` is already whatever
        // course the ship is on, so writing it is only ever recording what is true.
        cruiseSeatDir = dir.ordinal
        return true
    }

    /**
     * The follower's per-tick command: a yaw RATE (rad/s) and a vertical RATE (m/s), both clamped here rather
     * than in the follower so the limits stay with the hull that owns them.
     *
     * [targetSpeed] defaults to null, which is the plain route case: forward speed stays the pilot's cruise
     * setting, so one recorded route can be flown fast or slow without re-recording. Station-keeping on another
     * ship passes it because the whole task IS a speed (see [pathTargetSpeed]). A REPLAYED route does not come
     * through here at all -- it goes to [pathServo], which places the hull rather than asking it to fly.
     */
    fun pathCommand(
        turnOmega: Double,
        verticalMps: Double,
        speedCap: Double?,
        targetSpeed: Double? = null
    ) {
        val cap = pathTurnCap
        pathTurnOmega = turnOmega.coerceIn(-cap, cap)
        pathSpeedCap = speedCap
        pathTargetSpeed = targetSpeed

        // Convert m/s to the -1..1 impulse units the elevation law works in, exactly as setCruiseValueMenu's
        // vertical axis does: the up and down rates differ, so each direction scales by its own maximum.
        val upMax = cfg.baseImpulseElevationRate
        val downMax = cfg.baseImpulseDescendRate
        val clamped = verticalMps.coerceIn(-downMax, upMax)
        pathVerticalRate = (if (clamped >= 0.0) (if (upMax > 0.0) clamped / upMax else 0.0)
                            else (if (downMax > 0.0) clamped / downMax else 0.0))
            .coerceIn(-1.0, 1.0).toFloat()
    }

    /**
     * Put the hull where a replayed recording says it should be, and point it the way the route runs.
     *
     * ## Why this is a servo and not a set of controls
     * Everything else in this class asks the ship to fly somewhere: a throttle, a yaw rate, a climb rate, all
     * of them mediated by engine power, drag, buoyancy and the closed loops that trim them. That is right for
     * a pilot and wrong for a recording, because a recording is not a request. Routed through the control law
     * a replay inherits the law's dynamics rather than the pilot's -- most visibly its acceleration, which
     * reaches a target speed in six seconds where the hand that recorded it took forty.
     *
     * So this bypasses the lot. It reads where the hull is, works out the velocity that would put it on the
     * line, and applies the force that produces that velocity. The ship is still a rigid body in the physics
     * engine -- it will shove another vessel aside and grind against terrain that was not there when the route
     * was recorded -- but nothing between the recording and the hull gets an opinion.
     *
     * ## Gains rather than a timestep
     * The textbook form of this is `F = m * (v_target - v) / dt`, which needs the physics timestep. [physTick]
     * is handed no timestep, so the gains below are rates in 1/s instead: a velocity error decays with a time
     * constant of `1 / pathServoVelGain`. That is stable for any `gain * dt < 2`, and at 60 Hz physics the
     * default 12 has two orders of magnitude in hand.
     *
     * Gravity is the one steady disturbance that a proportional loop alone would leave as standing sag, so it
     * is fed forward exactly. Buoyancy and fluid drag are simply switched off for the duration -- both are
     * re-set from scratch at the top of every [physTick], so releasing the route restores them by doing
     * nothing.
     */
    private fun applyPathServo(body: ArmadaBody, physShip: PhysShip, servo: PathServo) {
        if (body.mass <= 0.0) return
        // Named apart from the class's per-category `cfg` on purpose. The servo gains are deliberately GLOBAL
        // -- a recorded route is flown the same way whatever the ship is -- and a local called `cfg` would
        // shadow the category getter for this whole function, which is exactly the kind of silent capture
        // that survives a refactor unnoticed.
        val servoCfg = EurekaConfig.SERVER

        // An anchor drops out of this branch above, so a hull arriving here is meant to move -- including one
        // that was static when it was last anchored, since the line that clears that flag is the last of
        // physTick and this returns long before it.
        physShip.isStatic = false
        physShip.buoyantFactor = 0.0
        physShip.doFluidDrag = false
        for (i in 1 until body.size) {
            val member = body.memberAt(i)
            member.buoyantFactor = 0.0
            member.doFluidDrag = false
        }

        // region position and velocity
        val keel = servoKeel.set(servo.keelLocal)
        body.lead.transform.shipToWorld.transformPosition(keel)

        val error = servoVector.set(servo.keelTarget).sub(keel)
        pathServoError = error.length()

        // Told to move, and not moving -- see [pathServoStalled]. Both speeds are taken as plain magnitudes:
        // a hull grinding along a wall at right angles to where it is meant to be going is stuck by any
        // reading that matters, and a recorded pause asks for nothing, so it cannot read as stalled.
        val asked = servo.velocity.length()
        pathServoStalled = asked > STALL_MIN_SPEED && body.velocity.length() < asked * STALL_FRACTION

        // The recorded velocity does nearly all the work; the error term only closes whatever the last tick
        // failed to deliver. Capped as a whole, so a ship that has just been shoved a long way off its line
        // comes back briskly rather than at a speed of its own invention.
        val drive = servoDrive.set(servo.velocity).fma(servoCfg.pathServoPosGain, error)
        clampLength(drive, servoCfg.pathServoMaxSpeed)

        drive.sub(body.velocity).mul(servoCfg.pathServoVelGain)
        drive.y -= GRAVITY
        clampLength(drive, servoCfg.pathServoMaxAccel)
        body.applyForce(drive.mul(body.mass))
        // endregion

        // region heading and trim
        val omega = servoOmega.set(0.0, 0.0, 0.0)

        servoForward(physShip, servoHeading)?.let { heading ->
            // Signed yaw from where the bow points to where the route runs, about +Y -- the same atan2 of the
            // cross and dot the follower uses, already wrapped to +/-pi.
            val dx = servo.forward.x()
            val dz = servo.forward.z()
            val cross = heading.z * dx - heading.x * dz
            val dot = heading.x * dx + heading.z * dz
            omega.y = atan2(cross, dot) * servoCfg.pathServoRotGain
        }

        // Righting. The hull's own up crossed with the world's, `(-uz, 0, ux)`, is the axis that carries one
        // onto the other, with a magnitude that is the sine of how far out of level the ship is -- so a level
        // hull contributes nothing and a heeling one is turned upright about exactly the right axis. It comes
        // out horizontal, so it never fights the yaw term above.
        val up = servoUp.set(0.0, 1.0, 0.0)
        body.lead.transform.shipToWorldRotation.transform(up)
        omega.x -= up.z * servoCfg.pathServoRotGain
        omega.z += up.x * servoCfg.pathServoRotGain

        clampLength(omega, servoCfg.pathServoMaxOmega)
        omega.sub(body.angularVelocity).mul(servoCfg.pathServoOmegaGain)
        clampLength(omega, servoCfg.pathServoMaxAlpha)
        body.applyAngularAcceleration(omega)
        // endregion
    }

    /**
     * The world direction the bow points, taken from the PHYSICS transform rather than the game one.
     *
     * [pathForward] answers the same question for the game thread. The two cannot share an implementation
     * because they read different transforms, and mixing them would have the servo measuring its heading
     * error against a pose one tick stale from the one it is about to correct.
     */
    private fun servoForward(physShip: PhysShip, dest: Vector3d): Vector3d? {
        val dir = controlData?.seatInDirection ?: return null
        dest.set(dir.unitVec3i.toJOMLD())
        physShip.transform.shipToWorldRotation.transform(dest)
        dest.y = 0.0
        return if (dest.lengthSquared() < 1.0e-9) null else dest.normalize()
    }

    /** Shrink [v] to [max] if it is longer, leaving its direction alone. */
    private fun clampLength(v: Vector3d, max: Double) {
        val len = v.length()
        if (len > max && len > 1.0e-9) v.mul(max / len)
    }

    /**
     * Release path guidance. [stopShip] also drops cruise, so the ship coasts to rest instead of carrying on
     * in a straight line -- what "stop" means for a vessel the size of a ship near terrain.
     */
    fun pathRelease(stopShip: Boolean) {
        pathFollowing = false
        pathServo = null
        pathServoError = 0.0
        pathServoStalled = false
        pathTurnOmega = null
        pathVerticalRate = null
        pathSpeedCap = null
        // Whatever throttle the follow had wound up stays where it is; only the demand goes away. With nobody
        // seated, physTick's abandoned-hull branch now zeroes it and stabilize's (capped) anti-velocity brake
        // walks the ship down, and with a pilot aboard the throttle smoothing decays it toward their input. Both
        // are a gradual stop rather than a handbrake, which is what "come to a stop" has to mean for a vessel.
        pathTargetSpeed = null
        if (stopShip && isCruising) {
            isCruising = false
            clearCruiseLatches()
            showCruiseStatus()
        }
    }
    // endregion

    // Ship turn-radius estimate (blocks) for clamping a typed turn rate: half the horizontal diagonal of the
    // ship-local AABB, coerced to the same [0.5, maxSizeForTurnSpeedPenalty] band applyPlayerControl uses.
    private fun turnRadiusEstimate(): Double {
        val a = ship?.shipAABB ?: return cfg.maxSizeForTurnSpeedPenalty
        val dx = (a.maxX() - a.minX()) / 2.0
        val dz = (a.maxZ() - a.minZ()) / 2.0
        return sqrt(dx * dx + dz * dz).coerceIn(0.5, cfg.maxSizeForTurnSpeedPenalty)
    }
    // endregion

    /**
     * Estimated forward top speed (m/s) at full engine heat, for the helm-menu "Top Speed:" readout.
     * Mirrors the steady-state of [getPlayerForwardVel] at full throttle (oldSpeed -> 1): the casual base
     * speed plus the engine term, which asymptotes to maxSpeedFromEngines and shrinks with mass (heavier
     * or fewer engines -> slower). Returns baseSpeed for an engine-less ship.
     *
     * This is the velocity the controller TARGETS, so it is a genuine ceiling -- the ship can no longer
     * blow past it under manual throttle. It stays an estimate, and the UI keeps its "~", because drag
     * leaves a realized speed a little under the target and live engine heat/fuel moves the engine term.
     */
    fun estimateTopSpeed(): Double {
        // The ARMADA's engines against the ARMADA's mass, exactly as getPlayerForwardVel drives it. Read off
        // this hull alone -- which is all this could see before the gather published them -- the estimate is a
        // statement about a ship that is not the one being flown, and since it is also the CLAMP on the helm's
        // typed cruise speed, a pilot could type a number, watch it be accepted, and never come near it.
        val engineCount = if (pooledEngines >= 0) pooledEngines else engines
        val mass = (if (pooledMass > 0.0) pooledMass else ship?.inertiaData?.mass)
            ?: return cfg.baseSpeed
        return estimateTopSpeed(cfg, engineCount, mass)
    }

    companion object {
        /**
         * "This ship has no crew station."
         *
         * `BlockPos.asLong()` cannot produce this value -- it packs Y into 12 bits, so the sign bit of the
         * result is never set on its own like this -- which is what makes it safe as a sentinel rather than a
         * position somebody could theoretically stand at.
         */
        const val NO_CREW_STATION: Long = Long.MIN_VALUE

        /**
         * Estimated forward top speed (m/s) for a vessel of [mass] kg carrying [engineCount] engines, read off
         * the [cfg] settings block for its category.
         *
         * Split out from the instance method so a ship that does not exist yet can be quoted the same figure a
         * built one reads on its helm -- a blueprint is priced and rated before there is a hull to measure. Two
         * implementations of this would be two answers to "how fast is it", and the page would be the one
         * quietly lying.
         */
        /**
         * Physics ticks per server tick, as measured by any ship that is running (see [physTick]). Seeded at
         * the usual 3 so the figure is sane before the first sample and while nothing is loaded.
         *
         * A property of the server rather than of any one hull, which is why it is shared: engines burn heat
         * once per PHYSICS tick and shed it once per SERVER tick, so the ratio between the two is what decides
         * how hot an engine can stay -- see [steadyStateHeat].
         */
        @JvmStatic
        @Volatile
        var physTicksPerGameTick = 3.0
            private set

        /** `consumed = physConsumption * this`, per [onServerTick]. */
        private const val PHYS_CONSUMPTION_SCALE = 0.1

        /**
         * The heat (0..100) an engine settles at under sustained full throttle.
         *
         * The readout used to assume 100 -- every engine at its full [EurekaConfig.ShipHandling.enginePowerLinear] --
         * and no engine is ever near that while it is being used. Heat is a balance of three flows per server
         * tick: it gains `(100*exponent - heat*exponent + 1) * engineHeatGain`, sheds
         * `(heat*exponent + 1) * engineHeatLoss`, and is drained by `consumed`, which is one unit per physics
         * tick spent at full throttle scaled by [PHYS_CONSUMPTION_SCALE]. Setting gain equal to the two drains
         * and solving for heat gives the steady state directly.
         *
         * On stock numbers that lands at ~68, i.e. engines make ~68% of their advertised force under load --
         * which is very nearly the whole of the gap between the helm's quoted top speed and the speed a pilot
         * could actually reach.
         */
        @JvmStatic
        fun steadyStateHeat(cfg: EurekaConfig.ShipHandling): Double {
            // Sourced exactly as EngineBlockEntity sources them, which is NOT uniformly: the gain is taken off
            // the ship's own category preset, while the exponent and the loss are read globally. Reading all
            // three off one block would agree with the physics only for as long as the two blocks happen to
            // hold the same numbers, and would quietly start lying the first time either is tuned per category.
            val exponent = EurekaConfig.SERVER.engineHeatChangeExponent.toDouble()
            val gain = cfg.engineHeatGain.toDouble()
            val loss = EurekaConfig.SERVER.engineHeatLoss.toDouble()
            val drain = physTicksPerGameTick * PHYS_CONSUMPTION_SCALE
            val denominator = exponent * (gain + loss)
            // Degenerate config (no exponent, or no heat flow at all): the balance below has no solution, so
            // fall back to the old assumption when the engine can gain heat and to a dead cold one when it can't.
            if (denominator <= 0.0) return if (gain > drain + loss) 100.0 else 0.0
            return ((100.0 * exponent * gain + gain - drain - loss) / denominator).coerceIn(0.0, 100.0)
        }

        @JvmStatic
        fun estimateTopSpeed(cfg: EurekaConfig.ShipHandling, engineCount: Int, mass: Double): Double {
            val scaledMass = mass * cfg.speedMassScale
            var speed = cfg.linearCasualSpeed / 3.0 * cfg.baseSpeed // oldSpeed -> 1
            // What an engine ACTUALLY makes at the throttle this figure describes -- the same lerp
            // EngineBlockEntity applies, at the heat it settles to rather than at a full tank it never sees.
            val perEngine = cfg.enginePowerLinearMin +
                (cfg.enginePowerLinear - cfg.enginePowerLinearMin) * (steadyStateHeat(cfg) / 100.0)
            val fullPower = engineCount * perEngine
            if (fullPower > 0.0 && scaledMass > 0.0) {
                var extra = fullPower
                val boost = max(
                    (extra - cfg.enginePowerLinear * cfg.engineBoostOffset) * cfg.engineBoost,
                    0.0
                )
                extra += boost + boost * boost * cfg.engineBoostExponentialPower
                extra /= scaledMass
                val smoothing = 1 / (cfg.maxSpeedFromEngines * 0.638)
                speed += atan(extra * smoothing) / smoothing // * oldSpeed (=1)
            }
            return max(cfg.baseSpeed, speed)
        }

        fun getOrCreate(ship: LoadedServerShip): EurekaShipControl {
            return ship.getAttachment<EurekaShipControl>()
                ?: EurekaShipControl().also { ship.setAttachment(it) }
        }

        private const val ALIGN_THRESHOLD = 0.01
        private const val DISASSEMBLE_THRESHOLD = 0.02

        // Sanity cap on the turn-acceleration phase, as an edge LINEAR speed (m/s); divided by the ship's
        // turn radius to get the max lockable yaw rate. Stops a very long hold from spinning the ship absurdly.
        private const val TURN_OMEGA_MAX_LINEAR = 24.0

        // Hard ceiling (deg/s) on a turn rate typed into the helm's manual Turn box. The physical cap above
        // reaches ~86 deg/s on large ships, which is too aggressive for a cruise orbit; this bounds the typed
        // value so the tightest settable circle stays reasonable. Only limits the MENU set, not driving.
        private const val CRUISE_TURN_DEG_MAX = 16.0

        // Drag-trim dials (see dragTrimMps). GAIN is per physics tick and deliberately far slower than the
        // force controller it wraps, so the outer loop can't oscillate against the inner one; raise it for
        // a quicker settle onto top speed, lower it if the speed hunts. BAND is how close to the target the
        // ship must already be before the correction starts accumulating at all. MAX_FRACTION then caps it,
        // as anti-windup for a ship that cannot move. Both are deliberately small: this now answers only the
        // real residual, the large shortfall it was originally sized for having turned out to be the engine
        // force being consumed on the first physics tick that read it. RELEASE bleeds the trim away when the
        // pilot lets off, so it never carries a stale correction into the next run.
        private const val DRAG_TRIM_GAIN = 0.03
        private const val DRAG_TRIM_BAND = 0.25
        private const val DRAG_TRIM_MAX_FRACTION = 0.25
        private const val DRAG_TRIM_RELEASE = 0.9
        private const val DRAG_TRIM_DEAD_ZONE = 0.05

        // Per-tick gain of the closed-loop throttle trim that lands a menu-typed forward speed on its exact m/s
        // (see cruiseTargetSpeedMps). Small so the outer loop stays slower than the inner force P-controller and
        // doesn't oscillate; the ship converges over ~1-2 s. Tunable feel dial.
        private const val CRUISE_SPEED_TRIM = 0.15

        // Below this commanded speed (m/s) a replay is not really asking the hull to go anywhere, so nothing
        // it does or fails to do says whether it is blocked. Covers every recorded pause.
        private const val STALL_MIN_SPEED = 0.5

        // Fraction of the commanded speed a hull must be making to count as under way rather than stuck. Low
        // on purpose: half speed is a ship having a hard time, and a quarter is a ship going nowhere.
        private const val STALL_FRACTION = 0.25

        // Per-phys-tick blend of a held Space/V into the latched vertical climb/descend rate (tap = small nudge,
        // hold = ramp toward full). Higher = snappier vertical trim.
        private const val VERTICAL_TRIM = 0.06f
        // balloonLiftMultiplier scales every balloon-lift consumer coherently: the physTick lift
        // budget, the ascend-speed bonus, and the airborne/thrust-assist check. 0 = balloons provide
        // no lift at all (debug lever for "ship hovers above the water" investigations). This is
        // flight lift, NOT water buoyancy -- the latter is VS2's buoyantFactor (floaters/air pockets).
        private val forcePerBalloon
            get() = EurekaConfig.SERVER.massPerBalloon * -GRAVITY * EurekaConfig.SERVER.balloonLiftMultiplier

        private const val GRAVITY = -10.0
    }

    override fun onServerTick() {
        extraForceLinear = powerLinear
        powerLinear = 0.0

        extraForceAngular = powerAngular
        powerAngular = 0.0

        consumed = physConsumption * /* should be physics ticks based*/ 0.1f
        physConsumption = 0.0f

        // Fold this ship's physics-tick count into the server-wide rate [estimateTopSpeed] reads. Smoothed
        // because the count jitters by one under load, and skipped on a zero count so a ship whose physics
        // isn't running (unloaded, or the tick after assembly) can't drag the figure to nothing.
        if (physTickCount > 0) {
            physTicksPerGameTick = physTicksPerGameTick * 0.9 + physTickCount * 0.1
            physTickCount = 0
        }

        // Finalize last tick's engine fuel-tank average into the synced percent, then swap the double buffer.
        // Order-independent of the engine block-entity ticks (they fill accumNext; we read the settled accum).
        engineFuelPercent = if (engineFuelCount > 0)
            ((engineFuelAccum / engineFuelCount) * 100.0).roundToInt().coerceIn(0, 100) else 0
        engineFuelAccum = engineFuelAccumNext
        engineFuelCount = engineFuelCountNext
        engineFuelAccumNext = 0.0
        engineFuelCountNext = 0
    }
}
