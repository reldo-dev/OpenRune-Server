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

## Late additions: tropical wagtail, ferret, embertailed jerboa

Three creatures joined tables that had already shipped, which is why their
dbrow ids (56360–56362) sit above every id in use anywhere in the hunter
block, not merely above their own table's: `HunterCreatures.all` sorts all
trap tables into one list by dbrow id, and an id filed next to the other
birds would sort ahead of the chinchompas and shift every trap index already
written into a save.

### Tropical wagtail

The one bird a `hunting_bird_*` symbol search does not find — that prefix
holds exactly four npcs, which reads as proof that nothing stands behind the
otherwise-unused `hunting_ojibway_trap_full_coloured` state. The wagtail is
`npc.multicoloured_bird` (`name=Tropical wagtail`), whose `model1` is the
very model that trap state uses, otherwise byte-for-byte the shape of the
other four birds, with 30 spawns in `.data`. Its loc key is `coloured`, not
`multicoloured_bird`.

Level 19 / 95.2 xp and a 43-point chart from its Hunter info box
(oldid=15259195). The chart alone does not pin the pair — both `(74, 371)`
and `(75, 370)` reproduce all 43 points. The page's prose decides it: "The
catch rate is 29% at lvl 1 and 144% at lvl 99", and the engine evaluates
`low+1` at L1 and `high+1` at L99 — `(75, 370)` gives 29.6% and 144.9%,
truncating to the stated 29 and 144, while `(74, 371)` gives 145.3% → 145.
`(75, 370)` also continues the snare family's own descending sequence
((100,420), (92,400), (85,390), (82,380) as the requirement climbs).
Tailfeathers is omitted from rewards: 1/20 *and* Hunter's-Rumour-conditional,
like the deadfall's Kebbity tuft.

### Ferret and embertailed jerboa

The two genuinely rate-blocked creatures: a content search of the offline
wiki snapshot for the `skillingSuccess` chart marker returns nothing on
`Ferret`, `Ferret (Hunter)`, `Embertailed jerboa`, or the *Box trap* page,
and neither page states endpoints in prose. Both pairs are therefore
**derived guesses**: the regular chinchompa's `(6, 268)` — the only published
box-trap curve that is not one of the two identical high-level ones — gives
146/256 at its own requirement (53) and reaches certainty 41 levels later.
Each pair solves the engine formula for those two anchors translated to the
creature's own requirement (ferret 27 → `(75, 338)`, jerboa 39 → `(43, 306)`),
reproducing the chinchompa's shape rather than inventing a new one; both
anchors are reproduced to the integer. There was no prior guess to re-derive
from — void ships no ferret or jerboa pair at all. A later in-game
measurement should be checked against this derivation.

Symbol traps: `obj.hunting_ferret` is the item and `npc.hunting_ferret` the
creature (the claws pattern again); the jerboa is
`npc.varlamore_hunterjerboa01` (`name=Embertailed jerboa`, `category_374`
like every box-trap creature) — *not* `npc.varlamore_jerboa`, which is
ambient scenery named plain "Jerboa". Large jerboa tail is 1/50 and
rumour-conditional, so omitted.

## Falconry

Not a trap, and it deliberately shares no code with `HunterTrap` — it borrows
the controller-anchored-at-a-coord idea and a varcon as the only persisted
state, and nothing else: falconry has no trap item, no cap and no loc. Its
creatures are a separate record (`FalconryCreature`), not `HunterCreature`
rows: widening that record would have meant either a sixth `TrapFamily` entry
(corrupting the trap cap and the controller-per-trap model) or a nullable
family every existing `when` grows a branch for. It also gets its own
controller type — `onAiConTimer` dispatches on the type, and a falcon
arriving at the trap tick would read as a trap with a corrupt family — and
its own varcon ids, since sharing names across controller types would let a
rename in one feature silently retarget the other.

### Rental and the glove

"For a fee of 500 coins, hunters can rent a gyr falcon and a falconer's glove
from the falcon expert Matthias" (*Falconry*, oldid=14840978), wired to his
real `op3=Quick-falcon`. The Talk-to dialogue tree, and the 500,000-coin
permanent unlock behind it, are out of scope, so every rental is charged. The
fee is charged *before* the space check: paying with an exact coin stack
frees the slot the glove takes, so checking first would refuse a player who
can afford it; if there is still no room the fee is refunded.

The glove is *equipment* — both states carry `iop2=Wear` with
`wearpos=righthand`, and the engine equips them with no code in this module —
so every glove check reads the worn slot as well as the backpack, and the
bird leaves and returns on the hand it was on (the swap transforms the obj in
its slot). Leaving the enclosure strips both glove states, walking or
teleporting alike (`PlayerAreaProcessor` recomputes areas from coords alone)
— but **not on logout**: that processor deliberately fires area-exits on the
logout cycle, so the handler guards on `pendingLogout || forceDisconnect`, and
the activity re-arms at next login instead.

### One falcon npc per kebbit

OSRS ships three distinct falcon-with-prey npcs, one per kebbit (all
`name=Gyr Falcon`, all `op1=Retrieve`), where thinner reference dumps use one
generic falcon plus a side-channel prey attribute. Encoding the prey in the
npc means a retrieve recovers the whole reward from the thing it clicked, and
the controller only remembers *who* owns the catch. The npc id order is not
the level order: `onspeedy2` (1343) is the dashing kebbit's, `onsilent`
(1344) the dark's.

The falcon–controller pairing is by **identity** (`IdentityHashMap`), never
by tile: falcon npcs take wander defaults, and a tile-keyed lookup voids the
catch the moment the bird steps off it (tick deletes the controller, retrieve
finds nothing, an `Int.MAX_VALUE` npc stays in the world). The three npcs are
additionally pinned in `.data/raw-cache/server/npcs.toml`
(`moveRestrict=NoMove`, `wanderRange=0`) so the bird also *looks* right;
the identity link removes the defect, the pinning removes the trigger.

### Rates

All three pairs are pinned to a single integer solution — the strongest fit
of any hunter table — with two independent cross-checks each: a
`{{Skilling success chart}}` whose every y value is an exact 256th, plus
prose endpoints. Spotted (oldid=15225548, 38 points): `(26, 310)`, prose "10%
at lvl 1 and 121% at lvl 99" (Mod Ash). Dark (oldid=15288973, 43 points):
`(0, 253)`, prose "0% … 99%". Dashing (oldid=15225549, 31 points):
`(0, 205)`, prose "0% … 80%". The spotted kebbit's `high=310` exceeds 256 on
purpose and must not be clamped: certainty from L80 is what the chart shows,
and clamping would move it to L99. Extraction note: the offline wiki sqlite's
chunks truncate at ~1KB and silently yield 2 of the spotted kebbit's 38
points; the 112-point extract came through the `osrs-cache` MCP
`get_wiki_section`, whole.

Rewards are infobox "Always" drops (Kebbity tuft omitted: 1/10 and
rumour-conditional); only the dashing kebbit has a third line (its meat).

### Behaviour and tuning

- **No proximity rate term.** "Although the success rate is supposedly not
  affected by proximity, running up to the target before catching it may
  improve success rate" (*Falconry*) — which the page itself explains as a
  timing artefact of flight time. Distance costs time here and nothing else:
  the flight delay is Chebyshev distance × `FALCON_CYCLES_PER_TILE` (1,
  unsourced — walking speed as the conservative reading), floored at 1 cycle
  so a point-blank catch cannot resolve on the input tick.
- **Timeout**: `FALCON_TIMEOUT_CYCLES` = 100 (~1 minute). The behaviour and
  the message are sourced ("Your falcon has left its prey…", and "no
  experience is given for the lost prey"); the duration is not. The timeout
  runs with the owner logged out — unlike a trap, a falcon has already
  rolled, so it has no live-level dependency, and one that only expired while
  watched would sit on the map until restart.
- **A miss leaves the kebbit in place** — despawning it would be an
  invention, and the cost of guessing wrong is a creature that vanishes on
  every miss. The empty-handed "kebbit will escape" line is likewise not
  modelled: despawning on a gloveless click would let a player clear the
  enclosure for free.
- **XP is awarded at retrieve, not at catch**, which is what the timeout
  depends on: a falcon that paid on landing would pay for prey never
  collected.

The enclosure area (`area.piscatoris_falconry` = 59, the next free id above
fishing's `fishing_guild = 58`) needs both the gameval id *and* the polygon
in `.data/raw-cache/map/area/` — an area name without the polygon resolves
and then matches no tile.

## Butterfly netting

Structurally the simplest technique: click the creature, roll once, done. No
loc, no controller, no varcon, no timeout, no cap — so no `TrapFamily` entry,
and its rows are read into `ButterflyCreatures.all`, never
`HunterCreatures.all`. There is no delay and no lock (a net swing lands where
the player stands, and no source describes a wait), so nothing re-checks the
target afterwards the way falconry must.

The mechanic, from *Butterfly (Hunter)* (oldid=15242004): catchable wielding
a butterfly net or magic butterfly net, or barehanded at +10 levels. Read the
jar sentence carefully — the *reward* does not depend on the net. It depends
on whether an empty jar is carried; barehanded is a level gate and nothing
else. (void's 80/85/90/95 barehanded levels are the RS3 rule; the wiki's flat
+10 ships.) Jarring never needs a free slot: both jars are non-stackable, so
the swap frees exactly the slot it takes, and a full inventory can still jar
a catch.

### The shared pair, the guesses, and the moonlight moth bound

Only two of the five shipped creatures carry a published chart: black warlock
(oldid=15288148, 41 points) and sunlight moth (oldid=15197088, 21 points).
The two curves are **pointwise identical over the levels they share**, and
both fit the same single integer pair, `(20, 296)`. The load-bearing
observation: the sunlight moth (req 65) starts at 201/256 — exactly what the
warlock's curve reads at 65, not a fresh curve anchored at its own
requirement. For these two, the requirement only decides where you join a
shared curve.

That is *not* the same as catch chance being species-independent. The
moonlight moth is a third published member (oldid=15208105) and does **not**
sit on the shared curve: it fits `(0, 276)` plain and `(20, 286)` magic, each
a unique exact fit, reading 209/256 at its requirement of 75 where the shared
curve reads 229. It is not shipped — zero spawns in `.data`, like Letvek —
but it is the counterexample that bounds the claim, and
`HunterRateTablesTest` asserts the disagreement so the stronger claim cannot
creep back.

The three unpublished rows (ruby harvest, sapphire glacialis, snowy knight)
therefore ship `(20, 296)` as a **guess — an extrapolation, not an
interpolation**: all three sit below the lower of the two agreeing
requirements, so no charted point brackets them. void independently fits
`[20, 296]` for its black warlock (confirming the engine formula maps onto
the wiki template) but guesses differently for the same three creatures —
guesses that predate the sunlight moth (Varlamore content), so they could not
be checked against the second curve and are not adopted.

### The magic net's +20

Both published charts carry a second series fitting `(40, 316)` — exactly
`+20` on each coefficient — and void reaches the same `+20` independently.
It is applied content-side as one constant (`HunterButterfly.NET_BONUS`)
rather than a second column pair, so the three guessed rows do not each need
a second guess. Barehanded rides the faster curve on two of three sources:
the warlock chart labels its series "Butterfly net" / "Barehanded or Magic
butterfly net", and void applies its `+20` to "Barehanded or magic net"; the
sunlight moth chart's labels disagree, but its points are identical, so the
uncorroborated label is read as stale. "Wielding" means *worn*: both nets are
`wearpos=righthand` `iop2=Wield`, so a net in the backpack is barehanded.

### Not modelled

The stat boost of a jarless catch (and the `Release` op on the six filled
jars). Each creature grants a different boost, and shipping six half-wired
stat effects — or refusing a jarless catch outright — were both worse than a
stated gap: a jarless catch awards the xp, removes the creature, and says so.
Chart extraction went through the `osrs-cache` MCP `get_wiki_section` (the
sqlite route truncates); note the page and cache spell "Sunlight Moth" with a
capital M, so a case-sensitive search for "Sunlight moth" wrongly reports no
chart.

## Crab trapping

Structurally unlike every other technique: **a crab trap is not an object in
the world**. The map places a `crab_trap_<site>_<n>` loc carrying no ops and a
`multivar`; the client draws whichever `multiloc` child the *viewing player's*
varbit selects. The server's whole job is a varbit write, so:

- `locRepo` is never touched — the never-delete invariant the deadfall and net
  trap enforce with hard checks is satisfied structurally here.
- Traps are private per player; two players baiting one hole never contend, so
  there is no owner, no controller, no tile key.
- **There is no roll.** "Unlike other methods, players cannot fail to catch a
  crab" (*Crab trapping*, oldid=15264574), restated in its Strategy section as
  the reason the guild hunter outfit and anti-odour salt have no effect. No
  `(low, high)` pair exists, and the only random draw picks the rainbow crab's
  colourway. This is also why the table has no npc column: the three crab npcs
  carry no ops, are never touched, and `.data` holds one red spawn, two blue
  and zero rainbow while the technique works identically for all three.

Filing it as a `TrapFamily` would have meant placeholder answers to that
enum's questions (laid obj, radius, cadence, failure state) and dead branches
in six `when`s.

### Sites, derived not retyped

The twenty sites (five holes each on The Pandemonium, two Great Conch shores,
The Crown Jewel) are the only hand-written list; everything else comes off
the packed cache: the varbit is the loc's own `multiVarBit` (so the server
writes exactly the var the client renders from), the state ordinals are
positions in the `multiloc` child list looked up by id (hard-coded 0/1/2/3
would silently show the wrong model after a cache reorder), and the site's
creature is matched by which full-trap locs appear among its children — never
by parsing `pandemonium` out of a symbol. Child ids are masked to 16 bits the
way `LocInteractions.multiLoc` masks them; padding slots read 65535. The op
dispatch reads the *varbit*, not the resolved child loc, since that is the
value the client rendered from and the server is about to overwrite.

### Levels, xp, bait, delay — all sourced twice where possible

Levels 21/48/77 appear both in the wiki's Overview table and in the cache's
own `skill_feature_hunter_*_crab` rows (`data=skill,23,<level>,9`); the same
rows carry `data=skill,22,10,-1` — the 10 Construction build gate, which
lives as one constant since all three rows agree. XP 64/136/216, stored ×10.
Bait is mandatory and per-site: red/blue take Fish offcuts
(`obj.brut_fish_cuts` — the plausible `sailing_fish_offcuts` does not exist),
rainbow takes Fine fish offcuts; the cache itself renders two different
baited locs, which is why bait is a lifecycle step here and not an unmodelled
`+%`. The fill delay is sourced exactly ("Red and blue crabs: 15 ticks;
Rainbow crabs: 25 ticks"), hence a column, not a guess.

The rainbow crab's three colourways are **one creature seen three ways**: the
three objs share name/description/cost/params and differ only in a 13-pair
recolour table that is byte-identical to the loc and npc of the same letter —
that identity, not the alphabet, pins the a/b/c triples. The row's reward and
full-loc columns are parallel lists; entry *i* of each is the same colourway.

### Item-symbol traps met along the way

`obj.woodplank` is the Plank (no `obj.plank`); `obj.bucket_empty` is the
Bucket (plain `obj.bucket` is a milk bucket); `obj.nails` is real steel nails
while `obj.any_nails` — literally `name=Steel nails` — is a display-only
duplicate no player can hold. The crystal and Amy's saws are accepted
alongside the plain saw: unsourced for this technique specifically, but
refusing them would make this the one build in the game they do not work
for, and accepting is the recoverable direction. Nail choice is
first-by-slot-order (ours), but requires the stack to cover both nails — one
leftover bronze nail must not refuse a build the steel stack below could pay
for.

### Behaviour

- **Build** is permanent ("Built traps remain there permanently"), level-free
  in Hunter, and unwinds its charges on a partial failure.
- **Bait** is where the Hunter level and cap are checked. The cap is its own
  ladder — 2/3/4/5 at 21/40/60/80 ("active (baited or full)") — written out
  rather than delegated to `TrapLadder`: it is a different table from a
  different source that happens to agree everywhere crab trapping is
  reachable, and delegating would grant a below-20 rung its source lacks.
- **The wait is a soft queue on the player**, not a controller: nothing of a
  crab trap lives in the world, and the arrival must not interrupt whatever
  its owner is doing. The queue's body re-checks the trap is still baited — a
  matured catch landing on an emptied trap would mint a crab from nothing.
- **Login re-arms pending catches**: the varbit persists, the queue does not.
  Re-queuing the full delay is the honest reading of "after a set period of
  time"; maturing on login would invent a rule that offline time counts.
- **Emptying a baited trap returns the bait** — unsourced, the recoverable
  half of that uncertainty, and what keeps a trap baited across logout from
  being stuck.

### Not modelled

The automatic re-bait ("after 3 ticks... reduced to just one tick by
immediately clicking again") — an ergonomic accelerator whose second half has
no state to hang off yet; and the crab visibly walking to the trap (the trap
simply fills).
