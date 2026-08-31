# Eureka Armada

> **Unofficial — not affiliated with or endorsed by the Valkyrien Skies team.** Use at your own risk, support will be handled by me. Source & issues: https://github.com/Eminai-LeoVinci/Eureka-Armada
Original VS2 mod: https://valkyrienskies.org/
Original Eureka Ships: https://www.curseforge.com/minecraft/mc-mods/eureka-ships

*MC 1.20.1 · 1.21.1 · 1.21.11 — Fabric only*
Requires the matching **Valkyrien Skies 2** build. Armada replaces Eureka! Ships! — don't run both.

---

## New here? What Eureka is

Eureka Ships is an add-on for Valkyrien Skies. You build a ship out of ordinary Minecraft blocks, place a Ship
Helm, and assemble it — your build becomes a simulated moving vessel you can sail, fly, or dive. Floaters and
balloons give it lift, engines give it power, and the helm is where you steer from.

**Armada is Eureka plus a crew, a cannondeck, and an pirate enemies.** Everything below is what Armada adds on top.

---

## Cannons

A cannon is two blocks long and points where you were looking when you placed it. Everything you do to a
gun happens at the gun:

| Do this | Get this |
|---|---|
| **Flint and steel** on the gun | Fires it |
| **Gunpowder or a cannonball** in hand | Loads it where it stands |
| **Empty hand** | Opens the magazine |
| **Crouch + empty hand** | Cycles the barrel's elevation |
| **G** at the helm | Fires every manned gun aboard |
| **Crouch + G** anywhere on a ship | Fires every manned gun aboard |

Elevation runs from -45° to +45° in 5° steps. The barrel visibly swings to the new angle instead of
snapping to it.

**Power** is how much gunpowder sits behind the ball — 1x, 2x or 3x. More powder means a faster, flatter
shot, and each setting has its own arc and its own reload time. Set it in the magazine screen.

---

## Cannonballs

Five metals — copper, iron, steel, gold, netherite — each in four kinds:

- **Round shot** — breaks blocks.
- **Explosive** — bursts in a sphere on impact.
- **Incendiary** — sets fire to what's left standing.
- **Armor-piercing** — punches through several walls, losing a quarter of its bite each time.

### How damage rolls work

Every ball has a **guaranteed** number of blocks it always breaks, then a ladder of **extra chances** —
separate percentage rolls for one more block each.

Iron ships with `guaranteed 2` and `extra-chances 80,70,50`. So an iron ball always breaks 2 blocks, then
rolls 80% for a 3rd, 70% for a 4th, and 50% for a 5th. Two to five blocks, most often three.

Change it live:

```bash
/armada cannonballs iron extra-chances 90,80,70,60
```

That's now 2 guaranteed plus four rolls, so 2–6 blocks. Add rungs to make a ball stronger, remove them to
make it weaker. The tooltip on the item updates to match.

---

## Crew

Villagers can sign on as crew. They run your guns, fight fires, and keep working while you steer.

- **Sneak + C** at a villager signs them on. **Sneak + Ctrl + C** signs on everyone the ship is carrying.
- **Sneak + C** cursor pointed at a wheel opens the **Crew Manifest**.
- You start with 4 berths per ship. Offer a **Heart of the Sea** to the wheel to buy one more, up to 64.
- Crew keep their name, their trades, and their post through assembly, disassembly, and bottling.

---

## Crews & Operations

The book at the wheel has three tabs: **Operations**, **Roster**, and **Crews**.

Operations is where you give orders to the whole ship at once instead of clicking thirty guns. Most orders
take a **side** (Left / Right / Both) and a **deck**, so you can work one battery at a time.

Guns are named by deck and side: `L1 - D1` is the first port gun on the lowest gundeck, `R3 - D2` the
third starboard gun a deck up. `F` is the bow, `B` is the stern.

### Assigning gunners

Three modes, and the difference matters:

- **Keep Assigned** — fills the gap from idle hands only. Nobody already on a gun is touched.
  *"Assign 6 gunners, Left, Deck 2, Keep Assigned"* mans Deck 2's port guns without stripping Deck 1.
- **Reassign** — pulls the entire gun crew off every gun and deals the whole ship again from scratch.
- **Release** — sends that scope back to general duties. *"Release, Left, Deck 2"* frees Deck 2's port
  gunners and posts nobody in their place.

### Locking a crew member

Open anyone's card in the Roster and press **Lock**. A locked berth is a "do not touch": bulk orders count
them but never re-task them, and their gun keeps its own angle, power and ammunition no matter what you
order for the deck around it. Restocks still top them up — powder is powder. Press **Unlock** to release.

Use it for your bow chasers, or for the one gun you've set to a different angle on purpose.

### Per-deck gun controls

**Set Angle** and **Set Power** are scoped the same way as assignment. You can give Deck 1 a flat 0° for
hull-raking and Deck 2 a +15° for rigging, on the same ship, in two clicks.

**Restock Cannonballs** is scoped too, and you pick which round goes where — steel on the lower deck,
incendiary up top. **Restock Gunpowder** and **Refuel Engines** fill the whole ship.

**Fire at Will** lets gunners pick their own targets and fire on their own timer.

The book remembers how you last left it, on that ship, at any wheel, through relogs and restarts.

---

## Container tags

Chests and barrels aboard learn what they're for from what you put in them.

Put cannonballs in a barrel and it becomes a **Shot** barrel. Gunpowder makes a **Powder** chest. Coal or
anything else an engine burns makes it **Fuel**. Restock orders then draw from the right boxes and leave
the rest alone, so your magazine stops filling up with coal.

Tags are categories, not variants — a box tagged for shot takes every metal and every kind. A box can hold
more than one tag. The tag lives on the box itself, so renumbering never confuses it.

Boxes aboard an assembled ship are numbered the same way guns are: `Chest 2 - D1`, `Barrel 1 - D3`. Chests
and barrels count separately.

---

## Bottled Ships

A **Ship Bottle** picks a whole ship up and carries it in your inventory. Max stack size is 16, single use on releasing a ship!

1. **Sneak + right-click a wheel** to mark that ship.
2. **Toss to Capture** when throwing a marked bottle via right-click. It's tracks the helm you marked, hovers over it for a few second, then captures the ship! you can tag the same helm multiple times, tags bottles of the sane helm & same ship can stack, helpful for quick escapes to have some at the ready.

**Releasing Bottled Ships** right-click tosses the bottled ship. It flies about twenty blocks and releases wherever it lands:
afloat if that's water, keel on the ground if it isn't.
you can launch a ship from a shore or flying above the ocean! if theres not enough room to release the ship, the bottle comes back to you with the ship still inside.

**Everything comes with her.** Cargo stays in the chests, coal stays in the engines, powder and shot stay
in the guns, and the crew come back at the posts they were standing when you bottled her. She keeps her
name too.

### What it's for

- **Move a ship overland.** Bottle her on one coast, walk or fly across the continent, throw her into
  another ocean.
- **Park a fleet.** A bottled ship isn't loaded, isn't ticking, and isn't running physics. Six ships in a
  chest cost nothing; six ships in a harbour cost every tick.
- **Get her through the awkward bit.** A river too shallow, a canyon, a nether portal — bottle, carry,
  throw.
- **Keep her safe.** A ship in your inventory can't be shot, sunk, or forgot where you assembled it!
- **Take a prize home.** Bottle a captured pirate ship where she floats and unpack her at your own harbour. Treasure stays inside :)

A bottle **moves** a ship — there's only ever one of her, and the hull stops existing while she's inside.
If you want to build the same ship again and again, use a Blueprint with a shipwright, after material costs are met, you can provide a bottle for the exact same ship!

---

## Blueprints & the Shipwright

A **Ship Blueprint** is a ship written down. Unlike a bottle, it doesn't move the ship — the original sails
on, and you can build from the page as many times as you like.

1. **Sneak + right-click a wheel** with a blank blueprint to draft it.
2. **Right-click the page** to read it — dimensions, weight, speed, and the full material list. This is
   instant and works in any inventory, even after the ship it describes has sunk.
3. Hand it to a **Shipwright's Bench** to file the plans. The page is consumed; the plans are permanent and
   available at *every* bench in the world, not just that one. You get 3 plan slots to start and buy more
   with Hearts of the Sea.
4. Press **Give Materials**, then **Build**.

**You can't lose progress by closing the screen.** Everything you've handed over is recorded against the
plans. Walk off, go mine what's missing, fetch the bottle or blank blueprint you forgot, come back to any
bench — the bar is exactly where you left it. Materials are spent per build; the plans never are.

Other things the bench does:

- **Build it from…** — swap a material for anything of its kind before building. Build the oak ship out of
  spruce, or leave the stained glass out entirely.
- **Save as New / Take Blueprint** — save your altered version as its own set of plans, or take it away as
  a page.
- **Bottle** — get the finished ship as a Bottled Ship instead of a hull in the water. Needs an unmarked
  Ship Bottle.
- **Repair** — mend a damaged hull back to its plans.
- **Dismantle** — break a ship down and claim the materials back, sorted into **Ship**, **Cargo** and
  **Kept**. Click rows to claim, or Claim All.

---

## Pirate ships

Pillager ships now generate in the ocean. They sit dormant until you get close, then wake up, come after
you, and fire back.

- Their guns and engines never run dry while they're fighting, so a long chase doesn't end in a whimper.
- Breaking a pirate gun or engine gives a token amount, not the whole magazine. They're not a coal mine.
- **Kill the crew, then break the wheel** to take the ship. You get a one-minute window to claim her.
- Their chests carry loot. Shot-down ships sink and can be salvaged.
- Damaged ships lose speed and settle in the water. A ship whose helm is destroyed falls.

You can't open a pirate's cannons or engines until you've broken her wheel. Take the ship first.

---

## Make your own pirate ships

The commands that authored the shipped hulls are still in. Requires OP (gamemaster).

**Build a hull, then capture it:**

```bash
/vs pirate capture <ship> pirate/myship1
```

The `pirate/` folder matters — a hull saved without it lands somewhere worldgen isn't looking. The command
will stop you if you forget.

Before capturing, make sure:

- The ship has **exactly one** wheel.
- Her crew are **standing on the deck** — whoever's aboard becomes her complement.
- Every gun has **powder and shot in it**, and every engine has **fuel in the slot**. An empty gun is
  silent and an empty engine is cold, even for a pirate. Don't test-fire or run the engines after
  stocking, or you'll capture them empty.

**Then place her:**

```bash
/vs template load pirate/myship1
```

To have her show up at existing pirate sites, add her to `pirateHulls` in the config with a weight:
`"pirate/myship1*20"`. To have her generate in fresh oceans, add her to the template pool in a datapack.

**The rest of the pirate toolkit:**

- `/vs pirate test-hull` — builds a small reference sloop at your feet. A working example to copy.
- `/vs pirate list` — every pirate site the world knows about, and what state it's in.
- `/vs pirate arm` — wakes the nearest dormant pirate ship immediately, for testing.
- `/vs pirate regen` — regenerates the nearest destroyed site, drawing a hull from `pirateHulls`.
- `/vs pirate prune` — clears out abandoned berths whose wheel is gone.
- `/vs pirate aim <x> <y> <z>` — stand on any armed ship and every gun that can bear solves its arc and
  fires at that point. Prints why each silent gun stayed silent.
- `/vs pirate set-mark <normal|pirate|taken>` — marks the wheel you're looking at. Handy for testing the
  pirate gates; note a hand-marked wheel has no crew papers, so she'll never wake or give chase.

**Templates in general:** `/vs template save|load|list|info|check|delete`. These are plain ship templates
and work for any ship, pirate or not.

---

## Other commands

- `/armada cannons <reload|speed|gravity|drag> <1x|2x|3x|all> [value]` — tune a powder charge. Bare prints
  the current value. `/armada cannons info` prints the lot.
- `/armada cannons fire-at-will <crew|pirate> [seconds]` — how long between shots when guns fire on their
  own. Crew and pirates tune separately.
- `/armada cannonballs <metal> <guaranteed|extra-chances|incendiary> [value]` — see the cannonballs
  section above.
- `/armada cannonballs explosive <guaranteed|chances|radius|max-radius|sphere>` — blast tuning.
- `/armada cannonballs fire spreads <true|false>` — whether cannon fire spreads on ships. While it spreads,
  crews will not douse it.
- `/armada bind <parent> <child>` / `/armada unbind` / `/armada list` — weld ships into one vessel. A bound
  fleet moves and turns as a single hull.
- `/armada route list|info|rename|delete|stop` — manage recorded flight paths.
- `/vs eureka-assembler <floater|balloon> <true|false>` — toggle the auto-shipwright.
- `/vs get-ship-weight <ship> <floater|balloon>` — a ship's mass, and the lift it needs.

Gunnery and config-writing commands need OP. `bind`, `list` and `route` are open to everyone.

---

## Keybinds

| Key | What it does |
|---|---|
| **G** | Fire! Every manned gun aboard. Works while seated at the wheel. |
| **Sneak + C** | Sign on a villager, or open a wheel's crew manifest. **+Ctrl** signs on everyone aboard. |
| **Sneak + F** | Follow the ship you're looking at. |
| **Sneak + R** | Record a route. Hold to discard. |
| **Sneak + P** | Fly the recorded route. **+Ctrl** replays it exactly. Hold to release. |
| **Sneak + H** | Show saved routes. |

Fly a loop once and any ship can fly it back. Ships can also station themselves alongside a leader and keep
formation.

---

## Config

`config/vs_eureka_armada.json` generates on first launch with everything tunable — gunnery, crew, pirate
rarity and hull mix, damage, wrecks, fire. A malformed file falls back to defaults without overwriting
yours.

Two worth knowing:

- **`pirateShipSpacing` / `pirateShipSeparation`** — how often pirate ships generate, in chunks. Default
  30 and 10. Bigger spacing is rarer. Takes effect next launch, and only on newly generated ground.
- **`pirateHulls`** — which hulls generate and how often, weighted: `"pirate/pilpirsmall1*60"`.

There are 38 message toggles if you'd rather the crew reported less.

---

## Known issues

- Ship physics and multiplayer drift are handled on the **VS2** side — see its Known Issues.
- ModMenu integration is compile-only on 1.21.11, so config is edited via the JSON file there.
- If you have several ships and you notice a stutter in movement / performance issues, try using the command "vs backend lodDetail 1024" default is 8192.
