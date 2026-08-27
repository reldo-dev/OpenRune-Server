# Hunter

How hunter creatures are modelled: the shared data tables every technique reads,
the catch-rate model, and per-technique notes on where each number came from,
what is a guess, and what is deliberately not modelled.

Sources are the OSRS wiki (pages pinned by `oldid` where a number was
transcribed) and the decoded client cache. Where a value has no published
source, the technique's section says so and explains the guess.

## Module layout

`content/skills/hunter` holds the gameplay code; its `pack` submodule declares
the dbtables the creature data packs into. Techniques are deliberately
independent of each other — rules they share live as top-level declarations in
`HunterShared.kt`, not as members of any one technique.

## Creature tables

Each technique's creatures are rows of a dbtable declared in `HunterTables.kt`.
Columns 0–7 are shared verbatim by every creature table — npc, level, xp,
success_low, success_high, caught_items, caught_min, caught_max — so a creature
row means the same thing whichever table it came from, and per-technique
columns all start at 8.

- Every `npc` and `obj` is a cache symbol confirmed via `config/npc` /
  `config/obj` lookups, never a wiki name transcribed directly — the two
  frequently differ (the wiki's "Crimson swift" is `npc.hunting_bird_jungle`).
- XP is stored ×10 so the fractional values the wiki quotes survive an int
  column; the content side divides by ten once, at the point it awards.

### Column ids must form a dense 0..n-1 set, per table

The gameval encoder writes a table's columns sorted by id and never writes the
id itself, just a name per column; on read, each `dbcol` is assigned its
ordinal purely from read position — a counter starting at 0, incremented per
column. Leave a gap in the ids and the two numbering schemes desync: every
ordinal past the gap resolves one column too low, the highest id has no ordinal
left to reach it and is silently dropped, and the pack still reports `BUILD
SUCCESSFUL` with no diagnostic. Ids are per-table, so sharing 0–7 across tables
is safe; numbering per-technique columns from a common base above the shared
block is exactly how a gap would get introduced, which is why they are declared
nested per table in `HunterTables`.

## Catch rates

Creatures carry a `(success_low, success_high)` pair interpolated by
`SkillingSuccessRate` against the player's Hunter level, with `maxLevel = 99`.
That constant is not a "max hunter level" rule: it is the scale of the
published catch-rate charts every pair was read from or fitted to, which run
from level 1 to 99. Where a pair was fitted or guessed rather than published,
the technique's section below says which and why.

## Trap cap

`TrapLadder` transcribes the "Multiple traps" table on the wiki's *Pitfall*
page (oldid=15201220): one trap below level 20, then 2, 3, 4 and 5 at levels
20, 40, 60 and 80, read from the effective (boostable) level. A technique whose
published cap table disagrees keeps its own ladder rather than reusing this
one — crab trapping's starts at 2 and has no below-20 rung, because its lowest
site needs level 21; folding it in would grant a rung its source does not have.

## Randomness

A fixed reward quantity (`first == last` in `rollQuantity`) consumes no random
draw at all. This is load-bearing, not an optimisation: the unit tests script
the RNG as a fixed sequence of draws, so an unconditional draw for a flat
quantity would shift every subsequent roll and change what the next one
returns.

## Bird snare and box trap: the trap engine

A laid trap is a controller anchored at its tile, the way woodcutting models a
felled tree. The controller, the loc state chain and the trap cap all resolve
from the tile, so there is no separate bookkeeping map to keep in sync. The
per-family scripts (`BirdSnareEvents`, `BoxTrapEvents`) register only the
player-facing ops — every op routed already exists on the cache type; nothing
invents an option the client does not draw. The tick handler is family-agnostic
and registered exactly once, in `BirdSnareEvents`; a second registration would
run every laid trap's tick twice per cycle.

### What a trap persists

A trap's whole state is three varcons on its controller — owner uid, family,
creature — plus up to five packed trap coords on the player.

- `varcon.hunter_trap_family` holds a `TrapFamily` *ordinal* and
  `varcon.hunter_trap_creature` an index into `HunterCreatures.all`, so the
  enum and the combined creature list are both append-only: inserting into
  either re-files every trap standing in the world at the next restart.
- `HunterCreatures.all` is sorted by dbrow id across all trap tables at once,
  not per table and concatenated. The two orderings agree only while each
  technique arrives as a whole block numbered above the last; sorting globally
  makes "give a new row an id above everything" the entire append-only rule,
  enforceable by choosing an id rather than a table.
- The trap cap is tracked as coords, not a counter. Controllers and timed locs
  are runtime-only, so a counter leaks: a trap that collapses while the player
  is away, or a server restart, never runs the decrement and permanently costs
  a slot. A coord can be re-checked against the world.
- An unset varcon reads 0, which is a legitimate index, so "armed and empty"
  and "sprung and failed" are negative sentinels (`CREATURE_NONE`,
  `CREATURE_FAILED`).

### Where the catch rates come from

The wiki publishes each bird's per-level success chart as a
`{{Skilling success chart}}` in its "Hunter info" section, on a `P(L) =
(floor(m·(L−1)/98) + c)/255` scale. The engine formula
(`SkillingSuccessRate.successRate`) is `(1 + floor(low·(99−L)/98 +
high·(L−1)/98 + 0.5))/256` — a 1/256 scale with a +1 bias, not the wiki's /255.
Each shipped `(success_low, success_high)` pair is that engine formula's
coefficients, fit to reproduce the creature's full charted curve (all ~48–58
points) exactly at every non-capped point. The three chinchompas state their
formulas directly, so those pairs are read off rather than fit.

Reproducing a chart does not pin a pair — a short chart is reproduced by many
pairs — so the wiki's own template parameters, recoverable from its Parsoid
transclusion metadata, are the authoritative source. They are checked in under
`src/test/resources/wiki-charts/published-params.tsv`, and
`HunterRateTablesTest` asserts every shipped pair *is* the published parameter,
as well as re-deriving every charted point. The charts are test resources, not
reads of the gitignored `.data` scratch dir: a chart the test cannot find must
fail loudly, not skip.

A negative `success_low` (the carnivorous and black chinchompas here) makes
"if the player's Hunter level is too low, the trap will always fail" fall out
of the formula on its own. A creature with a positive `success_low` (regular
chinchompa, +6) does not get that guard implicitly, which is why
`hunterTrapTick` also gates the roll on `owner.hunterLvl >= creature.level` —
without it a level-1 player would catch level-53 chinchompas at a small but
non-zero rate.

One value discrepancy is known: the cerulean twitch's own infobox states
64.5 xp where the parent *Bird snare* summary table states 64.6; the
creature-page value ships.

### Tuning numbers

| constant | value | source |
|---|---|---|
| `TRAP_LIFETIME_CYCLES` | 100 | RuneLite's client-side `HunterTrap.TRAP_TIME` overlay figure (~1 min); not server truth, a starting value to confirm in-game |
| `TRAP_SPRING_CYCLES` | 2 | ours; live's duration for the `_trapping_`/`_failing_` frames is not answerable offline |
| `TRAP_COLLAPSE_LINGER_CYCLES` | 100 | ours; finite so a wreck cleans itself up |
| `BOX_TRAP_TRIGGER_DISTANCE` | 2 | "Any ferret or chinchompa within a 2-tile radius of the box trap (forming a 5x5 square centred on the trap) can be attracted." (*Box trap → Mechanics*) |
| `SNARE_TRIGGER_DISTANCE` | 1 | unsourced; no page states a radius and no cache record carries one. Adjacency is the conservative reading — do not promote it to the box trap's 2 without a source |
| `BOX_TRAP_ATTEMPT_CYCLES` | 3 | "Once a box trap has been set, it will make an attempt every 3 ticks (1.8 seconds) to lure in an animal that is currently in range." (*Box trap → Mechanics*) |
| `SNARE_ATTEMPT_CYCLES` | 1 | unsourced; the wiki gives the cadence for the box trap only |

### Behaviour rules and their sources

- **A player standing on the trap suppresses the catch.** "A bird snare will
  not catch birds if the user is standing directly on the bird snare." (*Bird
  snare*); "Box traps won't trap prey if players are standing on the trap
  itself." (*Box trap → Mechanics*). Any player, not just the owner — the box
  trap's wording is the plural, general one. Only the roll is blocked; the trap
  still ages toward collapse, otherwise standing on one would hold it open
  indefinitely. The occupancy test requires `isValidTarget()`, not presence
  alone: `PlayerRegistry.findAll` does not filter hidden or mid-logout
  players, and an invisible player parked on the tile would otherwise suppress
  every catch silently. Known, accepted consequence: a second visible player
  can camp someone else's trap and suppress every roll while its lifetime
  decays (every trap loc is blockwalk=no, confirmed in cache). The trap item
  still comes back via the wreck, so this costs time only, and it matches
  live's plural wording — a known griefing vector, not an oversight.
- **A caught creature must not be caught twice.** `NpcRepository.despawn` only
  hides a creature — it stays in the zone map until its respawn cycle — and
  `NpcRegistry.findAll` does not filter hidden npcs, so the trigger scan
  filters on `Npc.isVisible` itself. Without it, two traps in range of one
  creature both catch it on the same cycle, and re-despawns rewrite
  `lifecycleRespawnCycle` so the missed respawn is never retried.
  `isValidTarget()` is deliberately *not* used here: it also requires
  `hitpoints > 0`, which no hunter creature declares in the cache.
- **Attempts are phased per trap.** The cadence counts from the trap's own
  creation cycle, not the raw map clock, so traps laid on different cycles do
  not roll in lockstep.
- **An unattended trap decays.** The tick deliberately never resets an armed
  trap's duration; only a spring does, so the owner has the full window to
  collect. A controller whose duration expires between ticks is deleted by
  `ControllerRepository` silently, which would strand the loc — so the tick
  collapses the trap one cycle early instead.

### The loc-state key

The bird snare's and box trap's loc states are named by a per-creature suffix
(`loc.hunting_ojibway_trap_full_<key>`, `loc.hunting_boxtrap_full_<key>`). The
key is authored data in the creature row, never derived from the npc symbol:
not every creature's npc and loc names share a derivable stem, and Kotlin's
`substringAfter` returns the whole string when its delimiter is absent, so a
derivation would not even fail loudly — it would build a loc name like
`loc.hunting_ojibway_trap_full_npc.multicoloured_bird` and throw at the first
catch of the one affected creature, not at boot.

### Deliberately not modelled

- **The lure walk.** An in-range creature is caught in place; live walks it to
  the trap first. Cosmetic; the radius, cadence and rate are modelled.
- **Eagles' Peak.** Live gates box traps on the quest; no quest system entry
  for it exists in this repo, so the gate is left unenforced rather than
  fabricating a check. The level-27 gate is enforced.
- **`Reset` (op2 on a sprung/collapsed box trap)**, which re-arms in place. A
  scope decision, not a cache gap; `BoxTrapEvents.investigate` guards on the
  armed loc id so the shared op2 registration cannot run Investigate text
  against a Reset click.
- **Letvek** (`npc.hunting_letvek`, level 76 box trap) exists in the cache but
  has zero spawns in `.data/raw-cache/map/npcs/`, so a row for it would be
  unreachable content.
- **Investigate wording is ours.** The text is server-sent, so it is in
  neither the cache nor the wiki; what it reports is the real controller
  state.

## Deadfall

Not a carried trap: the boulder is a permanent *map* loc, armed in place with a
log and a knife. That single fact drives the family's whole shape.

- **A map loc must never be deleted.** `LocRepository` only schedules a respawn
  for a delete with a *finite* duration (`LocRepository.kt:98-131`), so one
  `locRepo.del` on a boulder takes that spot out of the world until the next
  restart — no error, no log line. Every deadfall transition therefore goes
  through one function (`changeDeadfallLoc`), which is a `locRepo.change`, and
  the portable-family teardown (`clearTrapLoc`) carries a hard check that
  throws rather than delete a deadfall id: a loud tick is recoverable by
  restarting, a silent permanent delete is not. A `change` with a finite
  duration reverts to the map loc underneath, which is what makes the setting
  frame's logout safety-net possible at all.
- **Set mechanics.** "Clicking a boulder with a knife or fletching knife and
  any type of log in the inventory (or a banana when hunting maniacal monkeys)
  will set the trap." (*Deadfall*, oldid=15201193). The knife is kept; the log
  is consumed and its obj id recorded in `varcon.hunter_trap_deadfall_log`,
  because dismantling hands that exact log back. Maniacal monkeys are
  quest-gated on Monkey Madness II, not modelled. The log is charged *after*
  the set delay, past the last path that can refuse: two players can start on
  the same boulder (mid-set there is only an op-less `SETTING` loc, no
  controller), and a logout mid-delay reverts the boulder without resuming the
  coroutine — charging up front loses a log for nothing in both cases.
- **That dismantling returns the log is unsourced.** No source says what it
  gives back; the portable families return their trap item and the log is the
  only equivalent. Silently consuming it would cost an item with no notice.
- **A failed catch has no collectible state.** The cache holds no
  `hunting_deadfall_failed`; the boulder unsets and the log is lost. The
  collapse message is ours (server-sent text, recoverable from neither cache
  nor wiki).

### Log eligibility

"Any type of log" is read off the packed firemaking logs table, not retyped as
a list, so a log added to firemaking becomes deadfall fuel automatically. Two
exclusions, one sourced and one protective:

- "Redwood logs and arctic pine logs cannot be used for deadfall traps."
  (*Deadfall*, oldid=15201193). The cache spells `obj.arctic_pine_log` in the
  singular where every other log is plural; the plural resolves to nothing.
- The five Treasure Trails logs (`blue_logs`, `green_logs`, `red_logs`,
  `trail_logs_purple`, `trail_logs_white`) are real firemaking input rows, so
  the table read sweeps them in — and the set path picks the first usable log
  by slot order, so a clue step's log carried above ordinary ones would be
  destroyed to arm a boulder. Whether live accepts them is unsourced; refusing
  is the recoverable half of that uncertainty, and they are excluded outright
  rather than ranked last so a player holding *only* coloured logs is refused
  instead of quietly charged. Which log live picks when several are held is
  also unstated; slot order is ours.

### Rates and rows

No deadfall creature states its `P(L)` formula. Each pair was fit against the
creature's full per-level chart under *Drops → Hunting chance*, reproducing
every charted point exactly — 102 points across the five creatures. The wild
kebbit (42 points) and prickly kebbit (45) fits are mathematically pinned:
exactly one integer pair satisfies every point. Barb-tailed (6 charted levels,
25 pairs fit), sabre-toothed (5 levels, 19 pairs) and pyre fox (4 levels)
cannot be pinned by fitting, so those three ship the wiki template's own
published parameters, read from Parsoid transclusion metadata. Revisions:
wild kebbit oldid=15196478, barb-tailed oldid=15196228, prickly
oldid=15196260, sabre-toothed oldid=15196422, pyre fox oldid=15197087.
Negative lows are the honest fit for the steeper curves and must not be
clamped — these creatures are only catchable from level 23+, so the
sub-requirement end of the interpolation is never evaluated.

Loc-suffix map, read off `config/loc` and cross-checked against each npc's
recolours: claw = wild kebbit, barbed = barb-tailed, sabre = sabre-toothed,
fennec = pyre fox (whose cache symbols all still say "Fennec fox"), spike =
prickly by elimination. Two near-name traps: `obj.huntingbeast_claws` is the
item Kebbit claws while `npc.huntingbeast_claws` is the creature, and
`obj.huntingbeast_sabreteeth` is Kebbit teeth while `_dust` is the dust.
Rewards are the infobox "Always" drops only — Kebbity tuft and Fox fluff are
1/15 *and* conditional on an active Hunter's Rumour, which is not implemented;
bait (+3/256) and smoke (+2/256) are real but out of scope and deliberately
get no column. Prickly and sabre-toothed drop no meat, so their reward lists
are two lines, not three. XP is the creature infobox's, agreeing with the
parent page.

### Tuning

| constant | value | source |
|---|---|---|
| `DEADFALL_TRIGGER_DISTANCE` | 1 | unsourced; adjacency is the conservative reading — the boulder falls on what walks under it |
| `DEADFALL_ATTEMPT_CYCLES` | 3 | unsourced; borrowed from the box trap rather than the snare's every-cycle roll, since rolling 3× as often would quietly triple the slow family's rate |
| `DEADFALL_SET_CYCLES` | 3 | unsourced placeholder; `seq.human_laytrap` outlasts the state change either way |
| `DEADFALL_LEVEL_REQ` | 23 | the wild kebbit's, the lowest deadfall creature |
| `MAX_LAID_DEADFALLS` | 1 | "only one deadfall trap can be set up at once" (*Deadfall*), on top of the shared cap |

### The mirrored trapping loc

Every deadfall creature has a mirrored `_m` variant of its `_trapping_` loc,
plainly encoding an approach side, but what live keys the choice on is not
recoverable offline — the catch is server-side, and the two emulator
references that implement deadfall pick a side without agreeing on the axis
test. West-or-south-picks-the-mirror is our convention, not a source; it only
changes which of two boulder models shows for a couple of cycles.

Deadfall is also the one family the wiki exempts from the standing-on-trap
rule: "Deadfall traps are not prone to failure by standing where they are
set."

## Net trap and magic box

### The net trap's two locs

The net trap is a fixed map loc like the deadfall, but it drives *two* locs:
the young tree the player sets, and a "Net trap" loc that appears on the tile
beside it. The split is the cache's, not ours: `up`, `setting` and `set` are
all `name=Young tree` on the tree's tile; `net_set`, `catching`, `full`,
`failing` and `failed` are all `name=Net trap` beside it. The tree is a
permanent map loc and follows the deadfall's never-delete rule word for word;
the net is an ordinary spawn, and its delete path is checked so only
`name=Net trap` ids can ever pass it. Every op that lands on the net walks
back to the tree the controller is anchored to, and the *only* record of the
pairing is the tree's angle, which the net is spawned carrying — so net-state
changes are `locRepo.change` too, to keep the angle across.

All eight states are table columns, and all forty were resolved individually
against `config/loc` rather than derived from a suffix, because the swamp
lizard's are inconsistent: six `_swamp` states but
`hunting_sapling_{catching,full}_green` for the other two. Its npc is
`salamander_green`, the wiki calls it a Swamp lizard, and its caught obj
`green_salamander` is *named* "Swamp lizard" — three words for one creature.
The tecu mirrors this: npc `salamander_mountain`, obj `mountain_salamander`
named "Tecu salamander".

### The net-tile offset convention

Which tile each tree angle puts its net on is not recoverable offline — no
cs2 script or loc field states it. That the offset comes from the tree loc's
*own angle* (not where the player stood) is structural and matches the one
reference implementation. Reading `LocAngle` names as compass directions is a
convention, corroborated three ways (2026-08-26):

1. Every `hunting_sapling_up_*` carries `forceapproach` blocking exactly the
   side this mapping picks (measured in-game: an East-angle tree walks the
   setter around to its south — the protected side is the east tile the
   mapping names).
2. The map keeps exactly this tile clear: rotating the mapping one step
   counter-clockwise was tried, and the same tree's net tile landed on map
   scenery, refusing every set.
3. The pairing survives either way: `netTrapTreeCoords` is defined as the
   negation of `netTrapCoords`, so tree → net → tree is the identity
   whichever four tiles are picked.

How the models join visually — the bent trunk does not obviously arc over the
net — is unverified against live and may simply be what the trap looks like.

### Net trap behaviour

- **An occupied net tile refuses the set outright** rather than shuffling to
  another neighbour: a fallback tile would need state nothing else keeps, and
  half a trap would consume the rope and net for something that can never
  spring. Refusing costs a walk to the next tree and nothing else.
- **The level gate is the creature's own**, read off the tree clicked — every
  young tree belongs to exactly one salamander, and the family spans level 29
  to 79.
- **Failure drops the materials on the ground**: "If not successful, the tree
  will snap back to its original position, and the small fishing net and rope
  will appear on the ground" (*Net trap*, oldid=15272929) — the one family
  whose failure returns materials as ground objs. They drop on the *trap's*
  tile (the only position that always exists; the owner may be elsewhere or
  logged out), private to the owner first like kill loot. A collapse shares
  the failure path deliberately, so a timed-out trap cannot silently swallow
  the pair; the sprung-and-empty wreck accordingly owes the player nothing.
- The rope and net are charged as a pair after the set delay (same reasoning
  as the deadfall log); losing the net mid-set refunds the rope.

### Salamander rates

The consolidated "Salamander catch chance" chart on the *Net trap* page
publishes all five `(low, high)` pairs outright. They were fit anyway against
the 174 server-rendered chart points, and each of the five is pinned to a
single integer pair. The tecu is `(1, 212)`, *not* the `(0, 212)` its
near-identical black-salamander curve suggests: the two differ at exactly one
charted level, L83, where the chart reads 179/256 and `(0, 212)` yields 178.
The black salamander's 319.2 xp is the first value the ×10 storage is
load-bearing for. Levels and xp are from the page's Creatures table
(oldid=15272929). No bait column, as with the deadfall; the tecu is the only
Hunter creature that accepts no bait and cannot be smoked at all.

### The magic box

One creature: the imp. It gets its own table rather than a sixth box-trap row
because the *family* decides the laid obj and every loc state:
`HunterCreatures` derives the family from which table a row came out of, and
the imp cannot participate in the box trap's naming from either end — its npc
is the bare `npc.imp` with no prefix to strip, its states are the unsuffixed
`hunting_imptrap_*` set, and it is laid from `obj.magic_imp_box`. Filing it
under BOX would resolve to loc names that do not exist and throw at the first
catch. With one creature, every loc state is shared by construction, so the
four states are content-side constants and the table is the shared 0–7 block
alone. The held op is `Activate`, not `Lay`.

**The rate is published.** The *Magic box* page carries no chart, but the
imp's own creature page does: `{{Skilling success chart}}` "Imp catch
chance", `low=0 high=197 req=71`, 29 server-rendered points that fit exactly.
The fit is not unique — `(0, 197)`, `(1, 197)` and `(2, 197)` all reproduce
every charted level — so the published `low` ships. Note 198/256 at level 99:
no level makes an imp catch certain. Level 71 / 45 xp from the imp's infobox
(oldid=15271036), agreeing with the *Magic box* page (oldid=15185581).

The catch yields the 2-charge `obj.magic_imp_box_full` ("Imp-in-a-box(2)");
the 1-charge `_half` is what *using* the box leaves behind, and the banking
mechanic that consumes it is out of scope. The magic box has no mid-failure
frame in the cache (`empty`, `trapping`, `full`, `failed` and nothing between
the last two), so a failed catch shows its wreck immediately and the settle
step is a no-op.

### Tuning

| constant | value | source |
|---|---|---|
| `NET_TRAP_TRIGGER_DISTANCE` | 1 | unsourced; measured from the **net** — the tile the wiki treats as the business end ("not standing on the net") |
| `NET_TRAP_ATTEMPT_CYCLES` | 3 | unsourced; borrowed from the box trap for the deadfall's reason |
| `NET_TRAP_SET_CYCLES` | 3 | unsourced placeholder, as the deadfall's |
| `NET_TRAP_DROP_CYCLES` | 100 | sourced: the dropped pair "will eventually disappear in approximately a minute" (*Net trap*, oldid=15272929) |
| `MAGIC_BOX_TRIGGER_DISTANCE` | 1 | unsourced; deliberately *not* borrowed from the box trap — the only reason to think they agree is that both are boxes |
| `MAGIC_BOX_ATTEMPT_CYCLES` | 3 | unsourced; borrowed from the box trap on the deadfall's reasoning |

`obj.net` is the cache symbol for the Small fishing net; `obj.small_fishing_net`
does not exist.
