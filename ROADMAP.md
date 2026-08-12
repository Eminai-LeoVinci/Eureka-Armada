# Eureka Armada — Feature Roadmap

Consolidation of six design notes written over the week of 2026-08-04, handed over out of
order. They overlap by design — Blueprints and the Shipwright are named inside several of the
others — so this document exists to find the shared primitive, build it once, and put the six
features in an order where nothing gets written twice.

**Appendix A holds the original notes verbatim.** Where a note specifies behaviour (the
cannonball damage tables especially), the note is the source of truth and this document is
the derivation.

---

## 1. Locked decisions

| Question | Decision | Why |
|---|---|---|
| Where a captured ship lives | UUID + manifest on the item, blocks as a vanilla `.nbt` on disk | Item components sync to every client in render distance; a real ship is megabytes |
| What the Shipwright is | Villager profession, not a custom entity | Reuses the Crewman machinery wholesale; harbor-only spawning falls out for free |
| Timtenth commission scope | Harbors and docks only | Keeps his deliverable aligned with the last phase; pirate hulls stay in-house so nothing blocks |
| Phase order | Cannons before pirates and harbors | Front-loads the biggest unknown, and means pirate hulls can be authored with guns aboard |

---

## 2. The shared primitive

**Blueprints, Ship-in-a-Bottle, Shipwright build/repair, and Pirate ship generation are all
the same operation: a ship serialized to data and restored.** Written separately that is the
same block-copying code five times, and the five copies will drift.

VS2 already ships most of it. It mixes a duck-interface into vanilla's `StructureTemplate`:

```
org/valkyrienskies/mod/util/StructureTemplateFillFromVoxelSet.java
    vs$fillFromVoxelSet(level, voxels, shipsBeingCopied, centerPositions, min, max)

implemented by
org/valkyrienskies/mod/mixin/feature/structure_template/StructureTemplateMixin.java
```

This is what `ShipAssembler.moveBlocksFromTo` uses internally on every assemble, replayed with
`template.placeInWorld(level, corner, corner, settings, random, Block.UPDATE_CLIENTS)`.

It captures block states, block-entity NBT, and `ICopyableBlock.onCopy` tags. Because the
result is a **plain vanilla `StructureTemplate`**, we inherit `save` / `load` and
`StructureTemplateManager` `.nbt` files for nothing — and that is the same format vanilla
worldgen and jigsaw consume. One format therefore serves blueprints, bottled ships, pirate
ship worldgen, and any hull a builder authors for us.

### What it does not capture, and we must add

- **Entities.** `StructureTemplateMixin` explicitly calls `this.entityInfoList.clear()`.
- **Ship transform / scale / velocity / slug / VS attachments.** These live on `ServerShip`.
  `ShipAssembler.assembleToShipFull` shows the restore path via `unsafeSetKinematics`.
- **A bill of materials.** Nothing in the codebase counts arbitrary blocks by item today.

### VSSchematicEvents

`org/valkyrienskies/mod/common/assembly/VSAssemblyEvents.kt` declares `onCopy`,
`onPasteBeforeBlocksAreLoaded`, `onPasteAfterBlocksAreLoaded` and `pasteSurvivalCost` — and
**never fires any of them anywhere in VS2.** They are the contract a schematic mod is expected
to drive. We should fire them from our copy/paste path so third-party `ICopyableBlock`
implementations work against us.

### Storage, concretely

`StructureTemplateManager` is the whole answer:

- `save(ResourceLocation)` writes to `<world>/generated/<namespace>/structures/<name>.nbt`
- `get(id)` reads from **either** a datapack **or** that generated directory

So mod-shipped pirate hulls live in the jar under `data/vs_eureka/structure/`, player
blueprints and bottled ships write to `<world>/generated/`, and both load through one API. A
player-made blueprint is a real `.nbt` file you can pull off disk and hand to a builder.

The item carries only a UUID and the manifest — name, dimensions, mass, top speed, material
list. The block payload never touches an item component.

> **Note on size:** the 48×48×48 limit is a *structure block* constraint, not a format or
> placement one. We write the NBT in code, so ship templates can be any size.

---

## 3. Dependency graph

```
   ┌───────────────────────────────────────────┐
   │ F0  ShipTemplate   (capture/restore/.nbt) │ ◄── the foundation
   │ F1  BillOfMaterials                       │
   │ F2  PlacementCheck (fits without clipping)│
   │ F3  standalone-item scaffolding           │
   └──┬──────┬──────────────┬──────────────────┘
      │      │              │
 Bottle ─────┘              │
 Blueprint ─────────────────┘
 Shipwright ──── needs Blueprint
 Pirates ─────── needs worldgen + F0
 Harbors ─────── needs worldgen + Timtenth
 Cannons ─────── independent of F0 entirely
```

---

## 4. Build order

### Phase 0 — Ship template core *(nothing player-facing)*

New package `common/src/main/kotlin/org/valkyrienskies/eureka/template/`.

| Piece | Notes |
|---|---|
| `ShipTemplate` | Wraps `StructureTemplate` + `vs$fillFromVoxelSet`; adds entity capture, ship transform/scale/slug, and a `ShipManifest` (name, dims H/W/L, mass, top speed) |
| `ShipTemplateStore` | Thin layer over `server.structureManager`, UUID-named. Manifests persist in a `SavedData` following `path/PathStore.kt` — the established idiom here (`SavedData` + `SavedDataType` with `CompoundTag.CODEC.xmap`) |
| `BillOfMaterials` | Extract the inline census at `ShipHelmBlockEntity.kt:961-974` into a reusable `census(level, positions): Map<Item, Int>` |
| `PlacementCheck` | Bounded volume scan. Model on `armada/SubAir.kt:92` `fill(...)`, which already walks `ship.shipAABB` ±1 with a `BooleanArray` and a `MAX_FILL_CELLS` cap |

Fire `VSSchematicEvents` on copy and paste. Debug commands `/vs template save|load|list`,
registered alongside `ShipWeightCommand.kt`.

**Also in this phase, timeboxed — the cannon damage spike.** One question, answered before
cannons are on the critical path: can we destroy a specific block on a *moving, rotating* ship
from a projectile hit, reliably, without desync? Throw a snowball at a hull and delete what it
hits. No model, no items, nothing shippable. If this is a wall, cannons get redesigned early
rather than late.

### Phase 1 — Ship in a Bottle *(first playable)*

The thinnest complete consumer of Phase 0 — no worldgen, no mob, no container GUI — so it
proves capture **and** restore end to end.

- **First standalone items in the mod.** `EurekaItems.kt` registers only auto-generated
  `BlockItem`s today; the unused `private infix fun Item.byName` helper is there for exactly
  this. `textures/item/` does not exist yet.
- **First custom entity:** the thrown bottle, modelled on `EyeOfEnder`. `EurekaEntities.kt` is
  an empty-but-working registry whose builder DSL is already written, and
  `registerRenderers()` is never called from `EurekaModFabric.Client` — wire it.

Behaviour:

- `ship_bottle` (empty) → SHIFT+left-click a helm to mark that ship.
- Throw → flies and hovers exactly as an eye of ender does → at the hover point the ship
  aligns to world, disassembles, and vanishes → bottle drops, retrievable.
- Now `bottled_ship`; hovering shows the contained ship's name.
- SHIFT+right-click to throw. On land: hovers where momentum stops, `PlacementCheck` runs, and
  either the ship assembles with its bottom keel centre at the hover point, or the bottle falls
  back retrievable with an "area too small" message.
- On water: does not rise above the surface, idles with a fishing-bob, then after a few seconds
  assembles **flush with the local water surface** — read the actual water level, never a
  hardcoded y=64.

**The crew goes in the bottle with the ship.** Whoever was signed on via that helm musters back
aboard when the ship is released and reassembles.

This should be much cheaper than it sounds — do **not** capture crew as template entities.
`CrewLedger` already persists berths (with `CrewSnapshot` copies of each villager) keyed to the
helm, and `CrewMuster.muster` already runs on assembly and restores anyone missing. So bottling
only needs to **skip the `CrewMuster.standDown` call** that disassembly normally makes, and
carry the ledger key along with the template. Release reassembles, muster fires as usual, and
the crew walks back on deck through machinery that already exists and is already tested.

Template entity capture is still needed in Phase 0 — but for item frames, armour stands and the
like, not for crew.

### Phase 2 — Blueprints

- `blueprint` item — 1 paper + 1 lapis lazuli, blue re-tint of the paper texture.
- SHIFT+left-click a helm → capture a read-only template + manifest of whatever ship that helm
  is mounted to.
- Blueprint screen shows: **material list** (the important part — it is what you carry to the
  shipwright), dimensions H/W/L, ship name, weight, top speed.
- Code-drawn, following `client/crew/CrewManifestScreen.kt` conventions: plain `Screen`,
  `guiGraphics.fill` panels, hand hit-testing rather than widget soup, shared
  `gui/shiphelm/ShipHelmButton.kt`.
- Copyable like books — copy the component; the template UUID is shared and immutable.

### Phase 3 — Shipwright

A villager profession, reusing the Crewman machinery wholesale (`crew/CrewProfession.kt`:
profession + POI + texture resolved by profession id, registered Fabric-side via
`PointOfInterestHelper.register`, routed by the
`data/minecraft/tags/point_of_interest_type/acquirable_job_site.json` tag).

**A "Shipwright's Bench" block is the job site.** Place it only in harbors and the villagers
there become Shipwrights — harbor-only spawning then falls out for free via the same route
unemployed villagers already take to helms, with no custom spawn rules at all. It should be
uncraftable at first; that is what makes "only found at harbors" true rather than aspirational.

Menu (its own, not a trade screen):

- lists ships in progress and ships finished but unclaimed
- click a ship name → the blueprint's information, plus what materials remain
- a **Give Materials** button appears only when the player is holding something on the list;
  pressing it hands over everything relevant from the inventory at once. Creative auto-pull
  should mirror `EurekaAssembler.apply`, where `player.abilities.instabuild` skips the
  inventory gate
- materials accepted in installments, progress persisted per player
- on completion: **build in the world (not assembled)** at the designated dock spot, *or* mint
  a Bottled Ship
- blueprints are always consumed; the shipwright remembers no ship you have not paid for

**Repair / restore:** the ship needs only to be **partially** within the harbor's dimensions —
not wholly contained. Baseline is the harbor's natural dimensions plus some wiggle room, and
the accepted range can be widened later if it proves fussy in practice. Name must match,
dimensions must match, and the majority of non-air blocks must match the blueprint — otherwise
the shipwright rejects it, stating it is not the same ship. *The "majority" percentage is still
to be picked (see §7).*

Spawn eggs for both Shipwright and Crewman; no `SpawnEggItem` exists in the mod today.

### Phase 4 — Cannons

Independent of the template foundation, moved ahead of pirates so combat exists before the
threat does — which also means pirate hulls can be authored with guns already aboard.

**The block.** Two-block footprint like a bed. Container GUI templated on the Engine —
`EngineBlock` / `EngineBlockEntity` / `gui/engine/EngineScreenMenu` / `EngineScreen`, using
`GuiUtil.inventorySlots` and `KtContainerData` for synced numbers.

> ⚠ **Pass explicit atlas dimensions on every `blit`.** Documented at `EngineScreen.kt:18-25`:
> the engine atlas is 512×512, and implicit-256 blits (cancelled out by a 2× pose scale) broke
> on 1.21.11's render layers.

**Slots.** Gunpowder left, up to 64. Cannonballs right, up to 64. Cannonballs stack to 16 in a
player's inventory, like snowballs.

**Base cannonball damage.** Destroyed blocks per hit — the first value is guaranteed, the rest
roll independently:

| Ball | Range | Guaranteed | Probabilities |
|---|---|---|---|
| Copper | 1–3 | 1 | 2 @ 75%, 3 @ 25% |
| Iron | 2–5 | 2 | 3 @ 75%, 4 @ 50%, 5 @ 25% |
| Steel *(later)* | 3–7 | 3 | 4 @ 80%, 5 @ 60%, 6 @ 40%, 7 @ 20% |
| Gold | 2–8 | 2 | 3 @ 90%, 4 @ 80%, 5 @ 70%, 6 @ 40%, 7 @ 20%, 8 @ 10% |
| Netherite | 6–12 | 6 | 7 @ 80%, 8 @ 70%, 9 @ 60%, 10 @ 40%, 11 @ 20%, 12 @ 10% |

Design intent from the note: copper is the weak opener, **iron is the sweet spot**, steel is
the consistent choice (higher average than iron, tighter spread than gold), gold is heavy but
swingy, netherite is the hammer.

**Recipes.** Plus shape of ingots (5) with matching nuggets in the 4 corners → 8 cannonballs.
Netherite uses the plus shape with **raw gold** in the corners. Steel is 5 raw iron plus 4
charcoal or coal.

**Variants.** Both are 4 matching cannonballs + the respective raw material in the upper-left
corner (ancient debris for netherite) + 4 of the charge in the remaining slots.

- **Explosive** (gunpowder): +4 blocks total. Two go to raising the guaranteed count by +2.
  The other two roll separately — a third at 60% and a fourth at 30% — rather than extending
  the base probability ladder.
- **Incendiary** (blaze powder): sets blocks alight *after* destruction is resolved, chosen
  from the survivors, so burning never adds free damage. Copper 2, iron 3, steel 4, gold 5,
  netherite 6.

**Aiming: fixed, always.** Cannons do not track. The *ship* is responsible for angling the
shot, which makes fights about positioning and turns the existing pursuit code into a combat
AI almost for free.

**Trigger.** A button placed on the cannon, toward the back. Start with a player-placed button
— easiest, and it defers the question of whether the model needs an overhaul to carry one.

**Crew duties — the crew system's first real jobs.** A crew member can be assigned to *fire
the cannons* or to *put out fires*, and assignment is exclusive: one task per crew member, only
that task. Two jobs for now, more later with conditions attached.

This is the feature the crew system has been waiting for, and the UI slots already exist —
`gui.vs_eureka.crew_assignment` ("Assignment  —") and `gui.vs_eureka.crew_station`
("Station  —") are already in `en_us.json`, unused. The Crew Manifest card is where assignment
belongs.

**One crewman per cannon.** A six-gun broadside costs six berths, and berths are bought with
Hearts of the Sea — so a real gun deck is a serious investment.

**Cannons are auto-numbered by position relative to the bow**, and the labels appear in the
crew menu once cannons are detected aboard:

| Group | Meaning | Ordering |
|---|---|---|
| `L1 L2 L3 …` | Port side | L1 closest to the bow, ascending toward the stern |
| `R1 R2 R3 …` | Starboard side | R1 closest to the bow, ascending toward the stern |
| `F1 F2 F3 …` | Bow, facing forward | left to right, facing the bow (port → starboard) |
| `B1 B2 B3 …` | Stern, facing aft | left to right, facing the bow (port → starboard) |

Note the loop this closes: incendiary cannonballs set your deck alight, and a crew member
assigned to firefighting puts it out. The two features make each other worth having.

**Fire on ships is hard-capped at one block — always.** Fire does spread on ships today, and
rapidly if the server has not restricted it. Regardless of server settings, any fire on a ship
burns only the block that was lit, and only if that block is flammable. It may be destroyed if
left unattended, and it never propagates.

This applies to **every** ignition source, not just incendiary rounds — flint and steel
included. Otherwise a flint and steel becomes the cheap answer to any warship, and containing
all ship fire uniformly makes limited durability solve that problem on its own.

**Firing.** One shot per cannon every 8 seconds. Note this is *per cannon* — a six-gun
broadside is effectively a shot every 1.3 s, so gun count, not fire rate, is what actually sets
the difficulty of a pirate encounter. A per-ship stagger or global cooldown is likely needed on
top.

**Pirate ammunition.** Do **not** give pirates their own cannon block id. Breaking the helm is
supposed to hand you the vessel — and the guns are most of what makes that a prize. Use one
cannon block for everyone and drive ammunition off *ship ownership* instead: while a ship is
pirate-controlled its cannons draw from an infinite source, and the moment it is conquered they
become ordinary cannons that need feeding. Same block, same GUI, same capture-into-template
behaviour; only the supply changes hands.

**Other new ground.** First sounds in the mod — there is no `Registries.SOUND_EVENT` register
and no `sounds.json`. The model source is a `.bbmodel` (Blockbench-native, the good case) at
`MC Models or NBT's\Minecraft - Old Cannon`; if it uses arbitrary rotations — vanilla JSON
allows only 0/±22.5/±45 on one axis per element — it needs a `BlockEntityRenderer`, for which
`ShipHelmBlockEntityRenderer` is the in-repo precedent.

Cannon block-entity contents are captured automatically by `vs$fillFromVoxelSet`, so a bottled
or blueprinted ship keeps its guns loaded with no extra work.

### Phase 5 — Worldgen plumbing + Pirate Ships

**The mod has zero worldgen today** — no `worldgen` package, no `data/vs_eureka/worldgen/`,
and a repo-wide grep for `StructureTemplate|PlacedFeature|BiomeModification` returns nothing.
All new surface, which is why it gets proven against small self-authored pirate hulls rather
than against the commissioned harbors.

- Structure + structure_set + biome tags. Ships generate high and dry: bottom keel just above
  the water with no air gap.
- One helm per ship. Pillagers are already aboard the unassembled hull.
- **Pirate helm appearance — a three-state hub.** Add an `EnumProperty` to `ShipHelmBlock` with
  a matching hub value on `ShipHelmWheelBlock`:

  | State | Hub texture | Meaning |
  |---|---|---|
  | `NORMAL` | existing blue (Heart of the Sea derived) | an ordinary helm |
  | `PIRATE` | black concrete powder | pillagers alive — **helm is indestructible** |
  | `TAKEN` | white concrete powder | all pillagers dead — helm can now be broken |

  Still dramatically cheaper than new blocks: no new items, recipes, loot tables or tags, and
  `CrewProfession.helmBlocks()`, the `SHIP_HELM` block-entity set and
  `tags/block/ship_helms.json` all stay untouched. The hub is a single model element
  (`"name": "center"` in `models/block/template_ship_helm_wheel.json:53-61`) bound to texture
  key `gold_block` → `textures/block/helm_wheel_hub.png`; add two siblings. Pirate states are
  not in creative and not obtainable.

- **The helm cannot be broken while any pillager from that ship lives.** Attempting it shows a
  message stating the player must defeat all pillagers, **including how many remain**. That
  message is rate-limited to its own display life — roughly 4 seconds from appearance through
  fade-out, repeatable on the fifth second.

  This is what stops a pirate ship being stolen without a fight, and it has to be enforced at
  one place alongside the menu/hearts/crew gate — see the note on gating below.
- **Proximity sphere** centred on the helm, radius scaled from the ship's footprint, moving
  with the helm if the ship ever assembles and sails. Invisible in the finished feature;
  a temporary `/vs pirate-zones` wireframe toggle for testing, following the existing
  `/vs ship-shadows` debug-toggle precedent and marked for removal at cleanup.
- Player enters → 20-second countdown to leave → on expiry the ship **assembles** and gives
  chase.

**Pursuit is nearly free.** `follow/ShipFollower` already has the retry/timeout scaffolding the
note describes: `secondsAdrift` (must hold *continuously*, resets on re-acquire), a `bestError`
ratchet that tests whether the pursuer is still making ground, and `followBreakRange` /
`followBreakGrace` config. The work is refactoring `ShipFollows.begin()` (`:262`) to split the
player-raycast gate from the bind itself, giving `bind(own, target, ownerId = null)`.
`stopShip(ship)` (`:370`) is already a player-free unbind.

Pirate-specific behaviour on top: leader lost → 3 re-acquire attempts, 10 s apart → on the
third failure, abandon pursuit and circle slowly for 2 minutes → self-disassemble. It can then
begin the whole cycle again if a player re-enters the zone. (The circle-then-shutdown pattern
is lifted from Rust's unmounted boats.)

**Helm access is gated — implement the gate ONCE.** No menu, no Hearts of the Sea, no crew
info. Any attempt:

> This is a pirate ship, you cannot access the Helm. Destroy it to conquer the vessel!

> ⚠ **The gate must also cover bottle-marking and blueprint capture.** Both are SHIFT+left-click
> on a helm — the same interaction. If the gate lives in one place and covers all of them,
> pirates are safe by construction. If each feature checks separately, a Ship Bottle becomes a
> free ship: walk up, click, throw, and the vessel is yours without a fight.

Breaking the helm is the objective. While it stands, the ship respawns pillagers — but only
once all of them are dead and have been dead for 2 minutes.

### Why anyone fights a pirate — the Heart of the Sea economy

Berths cost Hearts of the Sea, and a gun deck costs one crewman per cannon, so an armada is
gated on Heart supply. There are three sources:

1. **Buried treasure** — vanilla, slow, finite per world.
2. **Crewman trades** — aboard ships, and at docks and harbors. Deliberately limited, and
   restocking requires sailing to a harbor to reset trade cooldowns.
3. **Pirate ship loot** — the fast route.

This closes a loop cleanly: you need Hearts for gunners, gunners are what let you take pirate
ships, and pirate ships are the best source of Hearts. Taking one is meant to hand a player
most of what they need to expand an armada in one go.

> **PENDING INPUT — loot tables.** A full loot list is coming once the pirate ship schematic is
> built. Worth knowing now: loot table *references* can live inside the structure `.nbt`, so
> chests roll fresh per generated ship rather than shipping fixed contents.

### Breaking the pirate helm — the conquest window

**Pirate ships only.** This triggers on the black-centre helm and nothing else; ordinary ships
keep their current behaviour on helm loss, unchanged.

1. The pirate helm breaks. The ship's **position is held** for 2 minutes — frozen, not
   drifting, and with no visible indication that a clock is running.
2. After 2 minutes the hold releases and physics simply takes over. No special slow-sink
   behaviour — it ragdolls, leans onto whatever side it is heaviest on, rolls, and goes down.
3. **Placing a helm of any kind within those 2 minutes claims the vessel.** That is the whole
   conquest mechanic, and the prize includes the gun deck (see Phase 4).

Players are expected to learn this by trial and error, which is reasonable — placing a helm on
a stricken ship is already how you stop a free fall today.

> ⚠ **Risk.** This is the only feature that assembles ships unattended, on a timer, out in the
> world, repeatedly. Assembly is heavy and has a history of edge cases here — deck
> fall-through, hanging entities dropping. It needs a hard cap on concurrent pirate ships, a
> chunk-loaded precondition, and a global cooldown.

### Phase 6 — Harbors & Docks *(gated on the commission)*

**Harbors** generate on beach shores, in three themes:

| Theme | Palette | Biomes |
|---|---|---|
| Sandstone — subtle Babylon | sandstones | **exclusive** to desert, badlands, savanna |
| Stone — subtle medieval/castle | various stones, tuff, basalt, deepslate | rocky, forest, windswept, snow, generic |
| Wood — subtle pirates' cove | woods | jungle and bamboo exclusively, plus generic |

The stone and wood themes **share** part of their biome range — where both qualify, which one
generates is luck of the draw. Only the sandstone theme is locked to its three biomes.

**Docks** are about a quarter the size, function identically, and lean inland toward lakes and
large bodies of water; harbors stay strictly coastal.

Contents: a dozen-plus mobs, market tents, fisherman-heavy with some blacksmith, fletcher and
farmer. Librarians rare to absent — you do not put a library on a shoreline.

- **The designated build pier** — a wooden dock running out into open water, guaranteed clear,
  so a shipwright-built ship never intersects the dock or terrain. This is a hard constraint on
  the commission, not a nice-to-have.
- Shipwright's Benches are placed here; that is the whole mechanism by which Shipwrights are
  harbor-only.
- **On entering a harbor's bounds:** reset every crew member's trade cooldowns, once per crew,
  re-armable after 20 minutes. Call off any pirate pursuit in progress.

---

## 5. The Timtenth commission — harbors and docks only

> **ACTION ITEM — not yet done.** Draft the outreach message and the brief itself. He has not
> been contacted. Nothing below exists as a document yet.

Runs in parallel from Phase 0, because his calendar is the one input we do not control. Pirate
hulls stay in-house so no code phase blocks on him; a pirate fleet becomes a second commission
once the system exists and its constraints are actually known.

The brief must state:

- harbor and dock footprints (W/H/L) per theme
- the build pier's clear-water requirement, in explicit dimensions
- block palette per theme
- where the Shipwright's Bench sits in each layout
- delivery format — vanilla `.nbt` preferred, since that is already our template format;
  `.schem` acceptable via FAWE import
- credit and licensing terms

Context worth stating to him: getting the Osirion map onto a Terrain Diffusion world is what
required FAWE, and porting FAWE is where the VS2 and Eureka work started. This commission is
downstream of that same line of work.

---

## 6. Reference — what already exists that we are reusing

| Need | Existing code |
|---|---|
| Ship → data | `vs$fillFromVoxelSet` (VS2 mixin on `StructureTemplate`) |
| Data → ship | `StructureTemplate.placeInWorld`, `ShipAssembler.assembleToShipFull` |
| Block enumeration on a ship | Chunk-section walk in `util/ShipAssembler.kt:212,231` (`unfillShip`) |
| Bounded volume scan | `armada/SubAir.kt:92` `fill(...)` |
| NBT persistence idiom | `path/PathStore.kt` (`SavedData` + `SavedDataType` + codec) |
| Pursuit, retry, break-off | `follow/ShipFollows.kt`, `follow/ShipFollower.kt` |
| Player-free unbind | `ShipFollows.stopShip(ship)` (`:370`) |
| Validate-then-commit material spend | `util/EurekaAssembler.kt` `apply(...)` |
| Mass / buoyancy figures | `util/BuoyancyMath.kt`, `BlockStateInfo.get(state)` |
| Villager profession + POI | `crew/CrewProfession.kt`, `fabric/CrewRegistrationsFabric.kt:50` |
| Code-drawn screen conventions | `client/crew/CrewManifestScreen.kt`, `gui/shiphelm/ShipHelmButton.kt` |
| Container GUI | `gui/engine/*` (mind the explicit atlas dimensions) |
| Networking payloads | `fabric/PathNetworkingFabric.kt` |
| Block-entity renderer | `blockentity/renderer/ShipHelmBlockEntityRenderer.kt` |
| Debug toggle command precedent | `/vs ship-shadows`, `/vs ship-emissive` |

---

## 6b. Deferred — known, understood, not urgent

**Duplicate ship names are still possible.** Nothing enforces uniqueness. `/vs rename` will
happily give two ships the same name, and a helm that legitimately remembers a player-given name
will apply it to a second hull while the first is still afloat. Either case makes *both* ships
unreachable by name: `ShipArgument.getShip` returns a ship only when exactly one matches and
otherwise throws `ERROR_MANY_SHIP_FOUND`, so teleport, rename and everything else that takes a
ship argument stop working until you dig out with `@v[id=...]`.

Agreed shape when it gets built: one shared "is this name taken" check against `allShips`, wired
into three places — the Keep Name re-apply at assembly, the helm's rename box, and `/vs rename`
(which lives in VS2, so it needs a mixin). On a clash, refuse and tell the player the name is in
use rather than silently renaming.

Note this is *separate* from the template name strip, which is already shipped and prevents the
common case: a captured template carries no name, so copies never collide.

**`VSSchematicEvents` are still not fired.** VS2 declares `onCopy`,
`onPasteBeforeBlocksAreLoaded`, `onPasteAfterBlocksAreLoaded` and `pasteSurvivalCost` and never fires
any of them; we don't either. The contract hands a mod a `MutableMap<String, CompoundTag>` at copy
and gives it back at paste — but copy and paste are separated by a *file*, and
`StructureTemplate.load()` drops any key it doesn't recognise, so honouring it means taking over
both ends of serialization: build the tag by hand, write via `NbtIo` to
`createAndValidatePathToGeneratedStructure`, and read the file separately on load to recover the
extra data. That is a parallel save/load path living next to `StructureTemplateManager`.

Firing `onCopy` and discarding what a mod writes would be worse than not firing — it reports data
as saved when it isn't. Nothing consumes these today (`@ApiStatus.Experimental`, `//TODO finish` in
VS2), so this waits until something real needs it.

**Armour stands glide about a block on disassembly.** Cosmetic. Armour stands go through the
rider pass, which calls `teleportTo` -- an instant server-side snap -- but clients interpolate
position for non-player entities, so a correction renders as a slide. The distance is the
rotation snap: disassembly rounds the hull's heading to the nearest 90 degrees and moves riders
by the delta, so a harder alignment swing means a longer glide. Nothing is lost or duplicated.

## 7. Open questions

1. **A pirate ship can self-disassemble with a player standing on it.** The abandon sequence is
   3 failed re-acquires → circle 2 minutes → disassemble. But boarding to fight the pillagers
   is exactly what makes it lose its leader: your own ship drifts off unattended while you are
   over there swinging a sword. The timer then runs to completion underneath you, and
   disassembly with a player aboard is the deck fall-through case. **Self-disassembly must be
   blocked while any player is aboard.**
2. **Repair threshold.** The harbor-bounds half is settled (partial overlap suffices). Still
   open: what percentage of non-air blocks must match, and whether it is measured per
   block-type or as a total count.
3. **Does a cannon's group come from its facing or its position?** A cannon sitting on the port
   rail but pointed forward could be `L2` or `F1`. Facing is the more useful answer for fixed
   guns — you want the label to tell a gunner where the shot goes — but the note describes the
   groups positionally.
4. **Harbors on Terrain Diffusion worlds.** TD is a full custom overworld generator; beach-shore
   placement may not behave as it does in vanilla. Needs testing before the commission is
   finalised, since it could change the footprint requirements.
5. **Sketchfab licence for the cannon model** must be verified before publishing, not merely
   credited. "Free" on Sketchfab covers several different licences, some of which restrict
   redistribution — which is what shipping it inside a mod is.
   Source: `https://sketchfab.com/3d-models/minecraft-wagon-cannon-free-a2b7adacddf647cab2f8d12c75df5d64`
6. **Scope.** These six features turn Eureka Armada from a ship-physics addon into a full
   content mod — worldgen, mobs, structures, combat. Worth being a deliberate decision rather
   than a drift.

---

## Appendix A — original notes, verbatim

### A.1 Pirate ships

> I've already dabled in structure generation mods, one specifically adds boats to the ocean,
> quite an eclectic range of boats too. I've even seen the rare grouping of roughly 8 ships,
> though they're mostly medium to small boats. So foundational what I seek is already proven to
> be possible. For our Eureka Armada project, I'd like to add Pillager Pirate ships. Pillagers
> will already be on this unassembled ship. There is already a Valkyrien Pirates mod in
> existence, but we're not looking at the code. I don't want to copy, we will approach with our
> own code! The concept the old Valkyrien skies pirates mod used is nothing new of the game
> development world. These generated pirate ships will start with a few templates to choose
> from, but will grow in time. eventually pillagers will have their own Armada's! For now,
> single ships are a safer & easier start. The ships will generate like normal structures, for
> now we will make sure that the ships generate high and dry, so the bottom most keel is just
> above the water with no air gap. it will have one helm on every ship (more on the Helm later).
> pillagers pirate ships will have an invisible proximity. The proximity will center around the
> helm, and will move with the helm if it ever gets assembled and moves. The proximity will be a
> sphere, but the size will scale depending on ship size. The size of the sphere is calculated
> by the footprint of the ship. If a player enters the proximity, a countdown of 20 seconds will
> begin for the player to evacuate. The proximity should NOT be visible for players as an end
> result, but for testing purposes we will initially have it a visible wire-frame sphere (for
> the testing phase, let's make a temporary command to toggle this pirate proximity zone) If the
> players(s) hasn't left the proximity in time, the ship will assemble! Once assembled it will
> specifically use the follow feature we implemented- same mechanics. So the message about being
> followed will still appear on the leader ship (players ship) that its being pursued. If the
> pillager pirate ship EVER loses its leader, it should try to look for it within its allowed
> follow range (this retry feature is specific to the pirate ships). If it fails 3 times (10
> second buffer between each attempt) than the ship will hault the pursuit entirely and start
> moving in a circle slowly (like it does in the game Rust, where after some time when a boat is
> unmounted, it will go in a circle before shutting off). It will continue to go in a circle for
> 2 whole minute before disassembling itself! (Again, like Rust) Where it can begin the process
> all over again if a player enters the proximity zone for 20 seconds again. Now back to the
> helm, even though the helm isnt a "new item", it won't will be as such. This identical helm
> will have limited access, however. Players cannot access this pirate helm menu, cannot offer
> any hearts of the Sea, cannot see crew info. If a player attempts any of this, a message will
> appear: "This is a pirate ship, you cannot access the Helm. Destroy it to conquer the
> vessel!". So per the message, the goal is to break the Helm on the pirate ship, if the helm is
> not broken. It will Spawn more pillagers, but it only Spawn them if all pillagers are dead-
> and have been dead for 2 minutes. To distinguish from regular Eureka Helms with Pirate Helms,
> let's make the center black shaded instead of the blue we changes. Before when we changed it
> to blue, we used the texture for the heart of the sea, which gave us the perfect 3 shades of
> blue it uses. For the pirate wheel center, let's use black concrete poweder texture for pirate
> helms only. This shouldn't appear in creative mode and the item is unobtainable. When using
> schematics for the generated

*(note ends mid-sentence)*

### A.2 Ship Blueprints

> Players can have blueprints of their ships via pages. Blueprint pages are made by crafting 1
> paper with 1 lapis lazuli- making 1 Blueprint. We'll use the paper item as a foundation, and
> change its appearance to blue for the blueprint item. a player can SHIFT and left click the
> blueprint onto a Ship helm- whatever ship that helm is mounted too, it will copy every block
> info and save it- this can be done however you see fit, but be sure to run the options by me
> to confirm the approach. The blueprint can be brought to a Shipwrite, where only with the
> shipwtight can a ship be brought to life. The page can be viewed by the player, however it
> will only display specific information to the players about the ship. Such as the material
> list for the shipwright, the dimensions (H, W, L), ships name, ships weight, and top speed!
> But the material list is the most important! As it tells the player what to bring to the
> shipwright, the shipwright will consume the blueprint when SHIFT leftclicking on him with it
> held in hand. The shipwright will have a menu interface showing ships the player is in
> progress of building, or has completed the material requirements and have not claimed the
> ship. The shipwright can take materials in installments, and clicking on the ships name
> (provided from the blueprint when given) it will show what remains. And a button feature will
> appear only when the player has materials to give that are a part of the ships requirement.
> the Give Materials button when pressed will give all related items from inventory
> (automatically if in creative like it does for the balloon and floater assembler). Once
> completing the mats req, the shipwright give the player the fresh built ship via a Bottled
> Ship. blueprints can be copies like books can.

### A.3 Ship in a Bottle

> I'd like to add 2 new items. A Bottled Ship and a Ship Bottle. A ship bottle is empty, it
> looks exactly like a bottle but names Ship Bottle. The empty Ship bottle must be SHIFT left
> clicked on a ship helm to mark that ship for the bottle to take. A player can then throw the
> bottle, and it will act like an eye of ender, where once thrown it will gentle fly exactly as
> the eye of ender does. It will fly to the ships center, where it will hover just like the eye
> of ender does. However right at it reaches the point where it will hover. The ship will align
> to the world, then disassembled- after its disassembled it will disappear (we can work on a
> visual effect later that makes it look like it's getting sucked into the bottlw). Once the
> ship has disappeared, the bottle will fall like the eye of ender does. And the player can pick
> it up. Where it the item will be called Bottle Ship- and hovering over it will display the
> ships name that's contained. If a player is holding a bottled ship, they can SHIFT and right
> click to throw it! If it doesnt make contact with the water with the throw it will act like
> the eye of ender, but will just come to it's hover point whenever the momentum of the throw
> stops. Once at the peak of hovering, it will check it the ship can be assembled without
> colliding with any blocks. If it can't, it will fall back down- a message will display saying
> the area is too small for the ship, and the player can pick it up again. If it can assemble
> the ship, it does so where the bottom center keel meets the floating bottled ship is the air.
> If they throw the bottled ship in the water, it will act similar to the eye of ender but will
> not rise above the waters surface, it will gentle Bob like the fishing rod Bob does. After a
> few seconds of throwing it, the ship will assemble FLUSH with the water surface. This isn't
> struck to y=64 as lakes can be higher or lower depending on the map or mods. Same rule- if the
> area can't fit the ship it displays a message and the item is retrievable.

### A.4 Shipwright mob

> I'd like to introduce a new Mob called the Shipwright. Shipwrights when first introduced can
> only be spawned by eggs or commands (well need to introduce eggs for the Crewman mob and
> Shipwright). For now they can only naturally Spawn within Harbors and Docks which generate
> similar to village structures. Shipwrites can do a multitude of things. To start, they can be
> brought ship blueprints. Which gives the player a couple of options, the first being the
> ability to build the ship for the player (NOT ASSEMBLE FOR THEM, just built in the overworld).
> The shipwright has a menu different from the other villagers. It will display the ship
> blueprints to the player. Where clicking on a ships name (provided by the blueprint), it will
> display information about the ship, like it would when reading the blue prints yourself. It
> will have the option to build the ship for the player, or place the ship in a bottle for them.
> To build a ship for the player, the player must bring all materials- planks, logs, floaters,
> basically all blocks! The player can bring the items in installments, and shipwright will log
> the progress until the req is met. Once all material requirements are met, then the player may
> choose to build it (not assemble) at the designated Harbor or Dock area- this will be a spot
> that leads out into the water, it will always be clear when the harbor is generated so player
> ships can be build in this designated spot. If they choose the bottled option, it acts just as
> a Bottled Ship would. Blueprints are always consumed, shipwright does NOT remember a ship
> you've ask them to build. The second feature a shipwright can so with blueprints is repairing.
> How this will work is if the ship is withing the boundaries of the harbor (W, H, L), and
> brings a blueprint of the same ship. The shipwright can "repair" or "restore" the ship per the
> blueprints. The ship has to atleast match the dimensions of the blueprints, and match the
> name! The majority of the ships materials (excluding the air block), must match the
> blueprints. If it doesn't, the shipwright will reject, stating its not the same ship.

### A.5 Harbors and Docks

> Harbors and Docks will be generated structures as a part of our mod. When entering a Harbors
> structure dimention area (Width, Height, Legth). Than it resets all crewmans trade cooldowns.
> This is a one time event per crew, and the harbor/dock restock effect resets after 20 minutes.
> Any pursuit from a pirate will be called off. Harbors will house at least over a dozen mobs.
> But the one most notable is the Shipwright Villager Mob. This Mob can only be found here at
> harbors or docks. The harbor will contain various market tents and mobs. Mostly fisherman, but
> Blacksmith, fetching, and farmer villagers too. Something like a librarian would be rare on
> non-existent here. You don't see a library close to ghe ocean for good reason. My goal is to
> have 3 types of harbors. One for sandstone harbor, a subtle Babylon style for sand, badlands &
> savanna biomes. A stone one, made of various stones, tuff and maybe basalt or deepslate. These
> will appear where the area is rocky or can be be placed in biomes marked as a generic location
> (we can disclose that later) but it can be forrest, windswept, rocky and even various snow
> biomes. This 2nd harbor will take on a subtle medieval / castle theme. The 3rd will be a
> wooden one, it will have a subtle pirates cove theme that can fit in a generic list as well as
> specific biomes. Such as jungle & bamboo being exclusive to the wooden harbor.
>
> Docks will be smaller, by a quarter of the size compared to a harbor. It functions the same as
> a harbor, and Spawn chances are more for lakes and large bodies of water. Harbors Spawn
> strictly on beach shores. Docks are hybrid but lean more on bodies of water inland. There will
> be a designated spot for the shipwright and where the shipwright builds a ship for the player-
> which will be a wooden dock reaching out into the water so, when building a ship, doesn't
> intersect with the dock or terrain.

### A.6 Cannons and cannonballs

> Let's add a new deployable item. I want to add a cannon, just like a bed, i want the cannot to
> take two spots on the ground. I luckily found a minecraft friendly 3D model that's absolutely
> free, and we will give credit to the author of the 3D modeler when we publish the mod out of
> respect. I want there to be 2 item slots when accessing the cannon. Accessing the cannons
> interface will look like a furnace or how a dispenser will look. The left slot will be
> gunpowder, where it can hold a stack if 64. the right slot will be for cannonballs. Cannon
> balls are a stack of 16 only when in a players inventory- like snowballs or buckets of water,
> but the cannon itself can hold 64 total on the right slot. Cannonballs are made by making a
> plus shape with ingots, than the nuggets for the matching ingots- netherite cannonballs are
> made with the plus shape as well but with raw gold at the corners. Each recipe makes 8
> cannonballs. So that makes 4 types of cannon balls- copper, iron, gold, and netherite
> initially, may add steel later. Eventually we will work on damage for the ship and test how
> explosions work, i think it does react to blasts by pushing the ship back a bit- but I
> digress. Essentially each variant will have their own destructive power. So copper is the
> weakest since its not as dense as the rest, so a small blast impact. Blocks destroyed will
> range from 1-3 blocks on an opponents ship. Where 1 is guaranteed, 2 blocks are 75% chance,
> and 3 is a 25% chance. Iron will be the sweet spot choice, it's dense and and will have bigger
> blast, say taking out 2-5 blocks. 2 blocks are guaranteed to be destroyed, 3 blocks have a 75%
> chance, 4 has a 50% chance, and 5 blocks have a 25% chance. Gold is heavy! But malleable! It
> ranges from destroying 2-8 blocks So its guaranteed to hit a 2 unlike copper, but 3 blocks has
> a 90% change, 4 has a 80% chance, 5 has 70% chance, 6 has a 40% chance, 7 has 20%, and 8 has a
> 10% chance. Netherite is the most powerful, guaranteed to destroy 6 blocks on impact. Block
> destruction is between 6-12 blocks! 7 has a 80% chance. 8 has a 70% chance. 9 has a 60%
> chance. 10 has a 40% chance. 11 has a 20% chance, and 12 blocks destroyed has a 10% chance!
>
> Later on I'd like to add blast effects to the cannon balls as well as steel cannon balls:
>
> Steel cannon balls are made with 5 raw iron and Charcoal or coal for the remaining 4. Steel
> cannonballs will hit higher on average than iron and more consistent than gold. The blast will
> guarantee 3, the range will be 3-7. 4 will be 80% chance, 5 will be 60%. 6 will be 40%. And 7
> will be 20%. So very consistent damage!
>
> As for the blast effects:
>
> Let's start with Explosive Cannonballs. Where they have an extra blast radius thus higher
> chance on average to destroy more blocks. It gives an extra 4 blocks to destroy on impact, 2
> will be allocated to increasing the guaranteed by +2, the other 2 will not be added onto the
> probability chance on max hit. Rather it will roll its own chance, the 3rd will have a 60%
> chance of destroying and the 4th will have 30%. It's made with 4 cannon balls of any kind (all
> 4 balls must match) the upper left corner will be the raw material of the respective ore. If
> netherite it will be ancient dabrie. And the remaining 4 slots will be gunpowder.
>
> Next is Incendiary Cannonballs. Same recipe but with blaze power instead of gunpowder. The
> blocks to be set on fire are chosen after the destroyed ones, so the ones set a-blaze will not
> immediately get destroyed, thus not giving extra damage for the materials. For copper
> cannonballs, it will set 2 on fire. For iron, it will set 3 on fire. For steel, 4. For gold 5.
> And for netherite, 6 blocks will be set on fire after the explosion.
