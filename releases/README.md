# Releases

Every published version has **one file here**, with the details of what was done in it:

```
releases/
├── README.md     ← this file (the workflow)
├── 1.0.0.md
├── 1.0.1.md
└── ...
```

The **summary** of every version lives in [`CHANGELOG.md`](../CHANGELOG.md) at the project root.

## How to cut a release

1. Pick the number following [SemVer](https://semver.org/):
   - **MAJOR** (2.0.0): breaks configuration, commands or compatibility (for example another Minecraft version).
   - **MINOR** (1.1.0): backwards-compatible new functionality.
   - **PATCH** (1.0.1): a bug fix that does not change the expected behaviour.
2. Create `releases/<version>.md` from the template below.
3. In `CHANGELOG.md`, move the content of `## [Unreleased]` into a new `## [<version>] - YYYY-MM-DD` heading
   (sections *Added*, *Changed*, *Fixed*, *Removed*) with a short summary and a link to the release file.
4. Update `mod_version` in `gradle.properties`.
5. Run `./gradlew build`. The `verifyReleaseNotes` task (part of `check`) **fails** if `releases/<version>.md` or the
   `## [<version>]` heading in `CHANGELOG.md` is missing.
6. The jar is written to `build/libs/villager-trading-reroller-<version>.jar`.
7. Tag the commit: `git tag v<version>`.

Release files are **immutable** once published: fixes go into a new version.

## Template for `<version>.md`

```markdown
# <version> — YYYY-MM-DD

One sentence saying what this version is.

## Highlights
- ...

## Added / Changed / Fixed / Removed
- ...

## Requirements and compatibility
- Minecraft, Fabric Loader, Fabric API, VTP.

## Upgrading from <previous version>
- Configuration, command or behaviour changes that need action from the player.

## Known limitations
- ...

## Verification
- What was tested (automated tests, in-game use) and what was not.
```
