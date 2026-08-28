# Trialbound

<img src="trialman.png" alt="Trialbound" width="96" align="right"/>

**Trialbound** is a RuneLite plugin for a collection-log-locked OSRS game mode: every
collection log item is **locked** — you cannot buy it on the Grand Exchange or equip
it — until it is **unlocked** for you (or your group) in one of two ways:

1. **Earn it** — receive the item yourself as a drop, or
2. **Buy it** — spend **Grit** points on the unlock.

Everything that is *not* in the collection log is completely unrestricted. Play solo,
or as a group with shared unlocks and pooled Grit.

## Grit and trials

Grit is earned **only** from bosses that are currently *on trial*. While a boss is on
trial, every collection log item it drops for you awards Grit — **duplicates count**.
Off-trial clog drops still unlock their slot, but award nothing, so trials are the only
Grit faucet while any bossing still progresses your unlocks.

Five trials are always active, chosen deterministically from the UTC date — every
member of your group sees the same trials with no coordination:

| Trial | Pool | Multiplier | Rolls over |
|---|---|---|---|
| Daily | any boss or raid (wildcard) | **3x** | midnight UTC |
| Weekly (Easy) | easy-tier bosses | **2x** | Monday UTC |
| Weekly (Medium) | medium-tier bosses | **2x** | Monday UTC |
| Weekly (Hard) | hard-tier bosses | **2x** | Monday UTC |
| Monthly | raids | **1.5x** | 1st of month UTC |

Grit per drop = tier base × multiplier. These values are **fixed in the plugin**
(not settings) so every group member plays by identical rules — changing them
requires a new build that the whole group shares:

| Tier | Base Grit | Unlock price |
|---|---|---|
| Easy | 10 | 100 |
| Medium | 25 | 250 |
| Hard | 50 | 500 |
| Raid | 50 | 1000 |
| Non-boss (clues, skilling, minigames) | — | 250 |

An item's price comes from its hardest source. Boss tiers, drop-attribution aliases,
and per-page overrides live in
[`src/main/resources/clog_boss_tiers.json`](src/main/resources/clog_boss_tiers.json) —
edit it to re-tier bosses; page names are validated against the game cache at startup.

## The collection log is the store

Open your collection log: every item is marked **green** (unlocked, tooltip shows who)
or **red** (locked, tooltip shows the price). **Right-click a locked item → "Unlock
(N Grit)"** to buy it from the pooled balance. The side panel also has a full browser
(Trials / Grit / Unlocks tabs) with search, filters, and the same buy/re-lock actions.

## Enforcement

- **Grand Exchange — hard block**: locked items are greyed out of search, and buy
  offers for them are blocked outright.
- **Equipping — hard block**: Wield/Wear/Equip is removed on locked items.
- **Inventory/bank/equipment**: locked items you're holding are greyed out.
- **Player trades — warning only**: an overlay lists any locked items in the trade
  window and accepting prints a warning. Trades are honor-system.

Each layer can be toggled in the config.

## Group play

Sync is **peer-to-peer over the RuneLite party system** — no server or database to
host. Each client keeps the full event history on disk
(`~/.runelite/trialbound/`); when group members are online together, changes stream
live, and members returning from offline catch up automatically via reconciliation
(state also spreads transitively through whoever is online).

Setup, for every member:

1. Agree on a **party passphrase** (any string — treat it like a password) and a
   **group password**.
2. In the plugin config, set the **party passphrase** and the **group password**.
   The plugin joins the party automatically on login.

The group password authenticates sync messages (HMAC-SHA256), so someone who only
knows the party passphrase cannot inject unlocks or Grit.

## Getting started

1. Log in — the first character you log in with claims the mode automatically
   (change the character name in settings if it picked the wrong account; the mode
   only runs for that character).
2. **Open your collection log once** — the plugin auto-runs its search to read which
   items you have already obtained (re-open it whenever the plugin asks to re-sync).
3. Enable the in-game setting **"Collection log - New addition notification"**
   (chat or popup) for the most robust drop detection.
4. Keep the **Loot Tracker** plugin enabled — a few sources (Wintertodt, Tempoross,
   the Gauntlet) are only attributable through it. Raids and reward chests are handled
   natively.

### Chat commands

| Command | Effect |
|---|---|
| `!grit` | your Grit and the pooled balance |
| `!tbunlocks` | group unlock count (and how many are yours) |
| `!tbrecent` | five most recent unlocks |
| `!tbclog` | collection log data status (pages/items loaded) |

## Running it

Requires a **JDK 11 or newer** — grab one from [adoptium.net](https://adoptium.net)
and enable *"Set JAVA_HOME variable"* in the installer (reopen your terminal
afterwards). Then, from the repo folder:

```
./gradlew runPlugin  # launch a RuneLite client with the plugin loaded
./gradlew build      # compile + tests
```

## Credits

Trialbound started as a wholesale fork of
[mvdicarlo/osrs-crabman-mode](https://github.com/mvdicarlo/osrs-crabman-mode)
(Group Bronzeman Mode), itself descended from
[CodePanter/another-bronzeman-mode](https://github.com/CodePanter/another-bronzeman-mode).
The chat-icon and GE-filtering foundations remain from that lineage; see
[LICENSE](LICENSE) for the original BSD terms.
