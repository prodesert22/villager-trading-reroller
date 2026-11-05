# Changelog

All notable changes to this project are summarised here.
The details of each version live in [`releases/<version>.md`](releases/).

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). How to add a version: see [`releases/README.md`](releases/README.md).

## [Unreleased]

Nothing yet.

## [1.0.0] - 2026-11-04

First stable release. A companion mod for *Villager Trading Predictor* (VTP) on Minecraft 26.3 (Fabric).
Full details: [`releases/1.0.0.md`](releases/1.0.0.md).

### Added
- **Closed-loop reroller**: places the workstation, waits for the villager to claim it, opens the trades, compares the
  real offers with the VTP targets and breaks the workstation if it is not what we are looking for. It stops only on a
  really observed offer.
- **Pool of burner villagers in parallel**: several rounds of the sequence consumed per cycle.
- **VTP-driven prediction**: rounds left, ETA, the predicted item and enchantment, drift detection and **automatic
  calibration** on multiplayer (night only). With no target in VTP's window it keeps burning until one appears.
- **Optional integration with the Villager Job Fix data pack** (`/trigger vjf_single|vjf_multiple`), off by default.
- **Container mode** (chest/barrel/hopper, copper chests included): the player stays still and the mod takes the
  lecterns back. Alternative: floor pickup, walking to the items and back to the starting spot.
- **Automatic tool selection** from the hotbar, and `start`/`resume` refuse when the block needs the right tool.
- **Window (K key)** with Control, Blocks and Settings tabs, styled like VTP.
- **In-world highlights** of the registered blocks (green = ok, red = invalid with the reason), with a global switch,
  a per-block switch and a *View* button.
- **HUD with three draggable, resizable panels** (Pool, Prediction, Activity) and an **edit mode**; the settings window is resizable too.
- **`/vprng` commands** matching every option of the window, plus keys **G** (start/pause/stop) and **K** (window).
- **Translations: English (default), Portuguese and Spanish** for the window, HUD, chat messages and highlight reasons.
  The language is chosen for the mod only (`/vprng language`, the *Settings* tab or `language` in the config); `auto` follows
  the game's language.
- **Chat logs are optional** (`chatLogs`, off by default; `/vprng logs`, or a switch in the window). Replies to your
  own commands/keys and the FOUND notice always show; pauses, calibration and warnings are logs and also appear on the HUD.
- Safety guards: damage, other screens, spectator mode, villager work hours, an entity in the cell, lost window focus.
- A build check that requires `releases/<version>.md` and a `CHANGELOG.md` entry for the current version.

### Known limitations
- Minecraft **26.3** only; depends on the internal classes of VTP **1.3.x** (no stable API).
- Prediction covers **librarian** trades only (what VTP supports).
- Several parts depend on the physical build (reach, hoppers, closed cells) and were validated in partial real use only.

[Unreleased]: ./releases/
[1.0.0]: ./releases/1.0.0.md
