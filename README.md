# Villager Trading Reroller

A client-side **Fabric** mod for **Minecraft 26.3**. Companion to
[Villager Trading Predictor](https://www.curseforge.com/minecraft/mc-mods/villager-trading-predictor) (VTP).

It rerolls librarians in a **closed loop** — place the workstation, wait for the villager to claim it, open the trades,
compare the **real** offers with your VTP targets, and break the workstation if it is not what you are looking for —
and uses VTP's prediction to know how many rounds are left. With a **pool of burner villagers** it consumes several
rounds per cycle.

> Current version: **1.0.0** · [Changelog](CHANGELOG.md) · [Per-version release notes](releases/)

## Contents

1. [Requirements](#requirements)
2. [Installation](#installation)
3. [Quick start](#quick-start)
4. [How it works](#how-it-works)
5. [Physical setup](#physical-setup)
6. [Window, highlights and HUD](#window-highlights-and-hud)
7. [Commands and keys](#commands-and-keys)
8. [Villager Job Fix (optional)](#villager-job-fix-optional)
9. [Languages](#languages)
10. [Configuration](#configuration)
11. [Limitations](#limitations)
12. [Building](#building)
13. [License](#license)

## Requirements

- Minecraft **26.3**, Java 25
- Fabric Loader ≥ 0.19.5 and **Fabric API**
- **Villager Trading Predictor 1.3.x** (the `VTP-2.0.0-mc26-universal` jar works)
- On a server: the **world seed** set in VTP (`/vtp seed <seed>`) so there is a prediction. Without it the reroller
  still works, verifying every open.
- Optional: the [**Villager Job Fix**](https://www.curseforge.com/minecraft/customization/villager-job-fix) data pack on
  the server (see [below](#villager-job-fix-optional)).
- Do not run it together with other reroller mods (for example *Villager Trade Reroller*).

Client-side only: nothing to install on the server.

## Installation

1. Put `villager-trading-reroller-1.0.0.jar` in `mods/`, next to VTP and Fabric API.
2. Join a world. Default keys: **G** starts/pauses/stops, **K** opens the window (rebindable in *Controls*).

> The mod is available in **English** (default), **Portuguese** and **Spanish** — see [Languages](#languages).

## Quick start

1. Set your target in VTP: `/vtp target <enchantment> <level>` (or VTP's screen, key `V`).
2. Build the pool ([see below](#physical-setup)) and register the lecterns: look at one and run `/vprng pool add`, or
   stand in the middle and run `/vprng pool scan` (or use the **Blocks** tab of the window).
3. Put a **tool** (an axe, or whatever the block needs) anywhere in the **hotbar** — the mod picks and switches by
   itself — and a stack of **lecterns** in the off hand or hotbar.
4. Prefer **night**: during the day villagers generate their own trades and the sequence drifts.
5. `/vprng start` or the **G** key. ESC (or any screen) pauses; `/vprng resume` continues; `/vprng stop` ends.

When a real offer matches a target the mod stops at **FOUND**: nothing is broken, the trade screen stays open and a
sound plays. **Move that villager out of reach before the next run**, otherwise the mod opens it again and (correctly)
stops on it immediately.

## How it works

A villager's trades are generated **on demand** on the server, the first time someone asks for them (opening the trade
screen, or the villager working at its workstation during the day). They come from a **world sequence per trade set**,
derived from the seed, and every generation consumes **one round**. Any villager takes the next round that has not been
used yet: if one villager got "Mending", the next one to generate (that same villager after a reroll, or another) gets
the following round.

Consequences:

- Every round costs **one GUI open**: prediction **cannot skip rounds**. The gain comes from event-driven waits and from
  burning **N rounds per cycle** with N villagers.
- The prediction tells you **how many rounds are left** and **what comes** (item, enchantment, price), but the **stop
  condition is always a really observed offer**; a wrong seed or another player consuming the sequence costs time, not
  correctness.
- With no target inside VTP's search window (`/vtp maxrerolls`) the mod **keeps burning** until a target comes into range.
- On multiplayer the mod does **automatic calibration** (night only) using VTP's own search.

## Physical setup

**Basic layout.** Stand still in the middle. Each lectern about 1 block from you and each villager in a closed 1×1 cell
behind its lectern, within about 3 blocks of you. You do not need to pair villagers with workstations. Have at least
`cells + 1` lecterns. The lectern is the "wall" of the villager's cell: if the space behind it is open, the villager can
walk into it when the workstation is broken (placement waits up to 10 s and then pauses) — close that space.

**Container mode (player stays still).** Put a **hopper directly under every lectern** (a broken lectern drops where it
stood, and a hopper collects from the block above), connect the hoppers to a **chest (any kind, copper included), barrel
or hopper within your reach**, look at it and run `/vprng container set`. The mod opens it, takes the lecterns out and
closes it without walking. Because the support is now a hopper, the mod **sneaks while placing**. Keep the off hand
empty or a hotbar slot free.

**Floor pickup.** Without a container the mod walks to the dropped lecterns and back to the starting spot (`autoCollect`).

## Window, highlights and HUD

- **Window (K or `/vprng gui`)**, styled like VTP (it uses the accent colour you picked there):
  - **Control**: Start/Pause/Resume/Stop, state cards, prediction, sync, and the **predicted round's offers**.
  - **Blocks**: every lectern with icon, coordinates, an **OK**/**INVALID** chip with the reason, a per-block
    **Highlight** switch, **View** and **Delete**; also `+ Looked at`, `Scan`, `Clear all` and the container card.
  - **Settings**: every switch, Villager Job Fix and the HUD editor.
- **In-world highlights**: outlines through walls — **green** usable, **red** invalid (with the reason floating above),
  **cyan** the container. *View* makes one block pulse with a beam and a label for 5 s.
- **HUD** with three panels (Pool, Prediction, Activity), draggable and **resizable** in **edit mode** (`/vprng hud edit`): drag a panel to
  move it, drag its right/bottom edge or corner to change width and height (Ctrl + arrow keys also resize; Shift = 8 px),
  hide with ×. The settings window can be resized the same way by dragging its right/bottom edge or corner. The Prediction panel shows the predicted item and enchantment with the
  price as a number plus an emerald icon.

Opening the window **pauses** a running run (a screen is open); *Start*/*Resume* close it before acting.

## Commands and keys

| Command | What it does |
| --- | --- |
| `/vprng` · `status` | State, VTP, seed, targets, prediction |
| `/vprng start` · `stop` · `pause` · `resume` | Control the run |
| `/vprng gui` | Open the window |
| `/vprng pool add` · `scan` · `list` · `clear` | Manage the cells |
| `/vprng container` · `set` · `clear` | Lectern recovery container |
| `/vprng highlight [on\|off]` | In-world outlines |
| `/vprng logs [on\|off]` | Progress/diagnostic messages in chat (off by default) |
| `/vprng language [auto\|en\|pt\|es]` | Language of this mod only (no argument cycles) |
| `/vprng hud <x> <y>` · `hud toggle` · `hud edit` | HUD origin, visibility and edit mode |
| `/vprng vjf` · `check` · `on` · `off` · `single` · `multiple` | Villager Job Fix data pack |
| `/vprng reload` | Re-read the configuration file |

Keys: **G** (start/pause/resume/stop) and **K** (window).

## Villager Job Fix (optional)

If the server runs the [**Villager Job Fix**](https://www.curseforge.com/minecraft/customization/villager-job-fix)
data pack, the mod can make villagers take the freshly placed workstations immediately instead of walking to them, using
the pack's player triggers `/trigger vjf_single` and `/trigger vjf_multiple`. This is usually the slowest part of a cycle.

- **Off by default.** Enable it with `/vprng vjf on` (or the switch in the window, or `useVillagerJobFix` in the config).
- `/vprng vjf` asks the server whether the triggers are available to you. The mod asks for the tab-completions of
  `/trigger vjf_`; the server only suggests triggers enabled for your player, so "not detected" means *missing, not
  loaded, or not enabled for you*.
- `vjfMode`: `AUTO` (single for a one-cell pool, multiple otherwise), `SINGLE` or `MULTIPLE`. `vjfRetryTicks` and
  `vjfMaxTriggersPerCycle` limit how often the command is re-sent while waiting for claims.
- Manual test: `/vprng vjf single` / `/vprng vjf multiple` send one trigger.

From the pack's page: `vjf_single` acts on the **closest** villager within 20 blocks (not necessarily the one under
your crosshair), `vjf_multiple` on the **16 closest** within 20 blocks, and both also change the profession of
*trade-unlocked* villagers in that radius — keep valuable villagers out of it. The page lists versions up to 1.20.1
only, so whether it works on 26.3 depends on the pack and the server; the mod can only tell you that the triggers
exist, not that they do the job.

## Languages

The window, the HUD, the chat messages, the in-world highlight reasons and the Villager Job Fix status are translated to
**English** (default), **Portuguese** and **Spanish**. The language belongs to the mod only — it never changes the game's
own language:

- `/vprng language auto|en|pt|es` (no argument cycles through them), or the **Mod language** dropdown at the top of the
  *Settings* tab, or `language` in the config.
- `auto` follows the game's language (any Portuguese variant → Portuguese, any Spanish variant → Spanish, anything else →
  English). The default is `en`.
- Key-binding names in *Controls* follow the game's language (they are translated by the game itself).

Texts live in `src/main/resources/assets/villager-trading-reroller/i18n/<code>.json`. To add a language, copy `en.json`,
translate the values (keep every `%s`/`%d`/`%.1f` in the same order) and add the code to `I18n.Lang`; the unit tests fail
if a language misses a key or changes a placeholder.

## Configuration

`config/villager-trading-reroller.json`, reloadable with `/vprng reload`; the window covers the main options. The full
table (options, defaults and effect) is in [`releases/1.0.0.md`](releases/1.0.0.md#configuration). Pool and container
data live in `villager-trading-reroller-pools.json` and `villager-trading-reroller-containers.json`.

## Limitations

- Minecraft **26.3** and VTP **1.3.x** only; VTP has no stable API (the `VtpBridge` class only switches off the
  prediction if something changes, and the reroller keeps verifying every open).
- Prediction covers **librarian** trades only, with **base** prices.
- Use it only where automation is allowed: the mod does not try to hide anything from a server; it aims before acting and
  waits for the server's confirmations.

## Building

You need **Java 25** (Gradle downloads the toolchain) and, in the project root, your own
`VTP-2.0.0-mc26-universal.jar` — it is used **only at compile time** and in the development client; none of VTP's code is
copied or redistributed, and the jar stays out of git.

```
./gradlew build        # compiles against 26.3, runs the tests and checks the release notes
./gradlew runClient    # development client with VTP loaded
```

The jar is written to `build/libs/villager-trading-reroller-<version>.jar`.

### Versions and changelog

Every version has a file in [`releases/`](releases/) (`1.0.0.md`, `1.0.1.md`, ...) with the details, and the summary lives
in [`CHANGELOG.md`](CHANGELOG.md). The build **fails** if the version in `gradle.properties` has neither. The step-by-step
is in [`releases/README.md`](releases/README.md).

### Layout of the code

```
src/main/java/dev/pedro/vprng/
├── bridge/   VtpBridge — the only place that touches VTP's classes
├── plan/     RoundPlanner, SyncTracker, ObservationWindow (pure logic, unit-tested)
├── roll/     PoolController and the rest of the reroller (actions, cells, container)
├── vjf/      Villager Job Fix detection and triggers
├── config/   Configuration and pool/container persistence
├── mixin/    The offers packet and the block-breaking fix
└── ui/       Window, HUD, in-world highlights, commands and keys
```

## License

The code in this repository is under the [MIT](LICENSE) license. **Villager Trading Predictor** is a separate mod with its
own license (its repository says *All Rights Reserved*; the CurseForge page says MIT) and is **neither included nor
redistributed** here: this mod calls it as a separate mod, without modifying it.
