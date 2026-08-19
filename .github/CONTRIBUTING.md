# Contributing to OpenRune Server

Thanks for your interest in helping build OpenRune Server! Contributions from the community keep the project healthy and moving forward. This guide outlines the process, expectations, and best practices so we can collaborate effectively.

---

## 👩‍💻 Behavior & Expectations

- We follow the [Contributor Covenant](https://www.contributor-covenant.org/version/2/1/code_of_conduct/); be respectful, constructive, and inclusive.
- Use the public [Discord](https://discord.gg/v2qcXzBCwf) for quick questions, coordination, and to find mentors for first contributions.
- Trello write access and contributor status are reserved for active maintainers. Reach out to a maintainer on Discord with a short summary of your work if you need access.

---

## 🧭 Finding Something to Work On

If you do not already have something in mind, in rough order of how self-contained they are:

- **Add game content** — a skill, boss, quest, area or interface. This is the best first
  contribution: content lives in its own module under `content/` and is loaded by scanning, so it
  touches nothing else in the tree. See [Adding game content](#-adding-game-content).
- **Improve documentation** (README, `docs/`, in-code comments, this file). Small doc PRs are
  welcome without prior approval. If something here was wrong or missing when you set up, fixing
  it is a genuinely useful first PR.
- **Fix a bug** from the [issue tracker](https://github.com/OpenRune/OpenRune-Server/issues).
- **Report bugs** via [GitHub Issues](https://github.com/OpenRune/OpenRune-Server/issues/new). Provide reproduction steps, logs (if any), and the game revision you are targeting.
- **Propose features** by opening an issue or discussing ideas first in Discord. The public roadmap lives on [Trello](https://trello.com/b/A0LefFDs/later).

Not sure whether something is wanted, already in progress, or bigger than it looks? Ask in Discord
before you start. That is the cheapest possible step and it avoids the two things that waste the
most contributor time: building something that gets rejected on scope, and duplicating work
someone else already has half-finished.

---

## 🛠️ Local Development Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/OpenRune/OpenRune-Server.git
   cd OpenRune-Server
   ```
2. **Install prerequisites**
    - Java 21 (Temurin or an equivalent distribution). The `tools:osrs-mcp` module targets Java 17, but Gradle's JVM toolchain handles that automatically.
    - Gradle (wrapper is included).
3. **Run the installation task** (downloads the cache and generates required data under `.data/`)
   ```bash
   ./gradlew install
   ```
4. **Start the game server**
   ```bash
   ./gradlew run
   ```
   A successful boot logs `Server ready in ...` and listens on port `43594`.

> [!IMPORTANT]
> Run `./gradlew install` before anything else. A fresh clone does not compile until it has:
> part of `api/` is generated from the game cache, so `assemble`, `test` and `run` all fail with
> unresolved references until the cache has been downloaded and the generators have run. If a
> checkout that used to build starts failing this way after a `git pull`, run `install` again —
> a merge that adds new gamevals leaves your generated sources stale.

If setup fails for a reason this guide does not cover, ask in Discord and then send a PR
improving this file — setup bugs affect every future contributor.

See the [README](../README.md) for IntelliJ-specific setup and client (RSProx) configuration.

---

## 🗺️ How the Codebase Fits Together

Four layers, each depending only on the ones above it:

| Layer | What lives there |
|---|---|
| `engine/` | Framework: entities, map and coordinates, pathfinding, events, the plugin loader |
| `api/` | Game systems: combat, inventory, stats, npcs, players, registries |
| `content/` | Actual gameplay: skills, bosses, quests, areas, interfaces |
| `server/` | Application entrypoint, install tasks, services, logging |

`settings.gradle.kts` walks those four directories and includes **any** folder containing a
`build.gradle.kts`. Creating a module means creating the directory and build file — there is no
central list to register it in.

Design notes for systems that are easy to get subtly wrong live in [`docs/`](../docs): instances,
doors, gates, drop tables, boss HP bars, ironman rules, and a general quirks file. Read the
relevant one before touching those areas.

---

## 🎮 Adding Game Content

Content is discovered by classpath scanning, not registration. Nothing central needs editing to
add a feature. A content module is a directory under `content/` containing:

- a **`PluginScript`** subclass, whose only method is `ScriptContext.startup()`, where you register
  your handlers (`onArea`, `onOpLoc`, `onOpNpc`, and so on);
- optionally a **`PluginModule`** subclass for any Guice bindings the feature needs.

At boot, `PluginModuleLoader` and `PluginScriptLoader` scan the configured plugin packages, install
every module, and run every script's `startup()`.

A minimal script looks like this:

```kotlin
class MyFeatureScript : PluginScript() {
    override fun ScriptContext.startup() {
        onOpLoc1("loc.my_object") { mes("You poke the object.") }
    }
}
```

Handler bodies run with a `ProtectedAccess` receiver, which is where `mes`, `player`, inventory
helpers and the rest of the player API hang off — so it is `mes("…")`, not `player.mes("…")`.

[`content/areas/godwars`](../content/areas/godwars) is a compact real example of both halves — a
script that opens an interface on area entry, and a module that binds a kill-counting hook.

Two things that catch people out:

- **Game data is referenced by string key, not by id.** Objects, npcs, interfaces and varbits are
  resolved through RSCM — `"npc.godwars_bandos_avatar".asRSCM(RSCMType.NPC)`. An unmapped key
  throws at boot rather than at the call site, so a typo shows up as a server that will not start.
- **`.data/gamevals/*.rscm` is rewritten by the build.** The gameval merge step mutates those
  tracked files as a packaging byproduct. Check `git status .data/gamevals` before staging, and do
  not commit that churn unless changing the mappings is the actual point of your PR.

---

## 🧪 Testing & Quality Checks

- Check that everything still compiles:
  ```bash
  ./gradlew assemble
  ```
- Run the unit tests:
  ```bash
  ./gradlew test
  ```
  Coverage is thin, so a green run is a weak signal. Booting the server is often the more
  meaningful check, since startup resolves every gameval and loads every plugin — most content
  mistakes surface there rather than in a test.
- The project uses Spotless with ktfmt (Kotlin style, 100-column limit), but formatting is not
  currently enforced across the tree and much of the existing code predates it. **Format only the
  modules you touched** — a repo-wide `./gradlew spotlessApply` rewrites a large fraction of the
  codebase and will bury your change:
  ```bash
  ./gradlew :content:areas:example:spotlessApply
  ```
- Keep pull requests focused. Large changes should be split into multiple PRs.
- If you modify game data (gamevals, spawn definitions, configs), include verification steps in the PR description.

---

## 🧱 Coding Guidelines

### Kotlin

- Aim for idiomatic Kotlin. Prefer `val` over `var`, extension functions where appropriate, and data classes for simple models.
- Follow existing naming conventions and module boundaries (`engine`, `api`, `content`, `server`, `tools`).
- New gameplay features belong in standalone plugin modules under `content/`.
- `engine` and most of `api` opt into Kotlin's explicit API mode, so public declarations there need explicit visibility modifiers and return types. Most of `content` does not — match the modules next to the one you are adding.
- Avoid long monolithic functions; refactor into smaller composable functions.
- Document non-trivial business logic with KDoc comments.

### Game data (gamevals, spawn definitions, configs)

- Maintain sorted keys in data files to minimize merge conflicts.
- Include notes about data sources when adding new cache or spawn definitions.

---

## 📦 Branches & Workflow

1. **Fork the repository** (if you do not have write access).
2. **Create a feature branch** from `main`:
   ```bash
   git checkout -b feat/my-feature
   ```
3. **Commit using clear messages**. We recommend the `type: summary` format (e.g., `fix: correct prayer drain calculation`).
4. **Push your branch** and open a pull request early if you would like feedback.
5. **Link to related issues** in the PR description using `Fixes #123` or `Closes #123`.

If you are porting a body of content from another project, open an issue or post in Discord first
describing what you plan to bring over. Splitting it into reviewable pieces up front is much less
painful than untangling one enormous PR later, and it avoids two people porting the same thing.

---

## ✅ Pull Request Checklist

- [ ] The change compiles (`./gradlew assemble`).
- [ ] The server still boots (`./gradlew run`).
- [ ] Tests related to the change pass (or are added).
- [ ] Formatting run on the modules you touched, and nothing else.
- [ ] Documentation/examples updated where relevant.
- [ ] No unrelated formatting or dependency changes — in particular, `.data/gamevals/*.rscm` is
      rewritten as a build byproduct and should not be committed unless the mapping change is the
      point of your PR.
- [ ] PR description says how you verified the change, with screenshots/logs if useful.

This is a small team, so review happens around other commitments. Expect comments focused on
stability, readability, and maintainability, and expect to be asked to rebase or squash before
merging. If a PR goes quiet for a while, a polite nudge in Discord is welcome rather than rude —
it is far more likely to have been lost than ignored.

---

## 🙌 Getting Help

- Join the [Discord server](https://discord.gg/v2qcXzBCwf) for questions, architecture discussions, or review blockers.
- Check the [Trello roadmap](https://trello.com/b/A0LefFDs/later) to see what is planned or in progress.

Thank you again for contributing to OpenRune Server! Your ideas, fixes, and feedback help shape the project for the entire community.
