# DuoBoy Advance 0.1.3

- New app icon (also used for home-screen shortcuts).

# DuoBoy Advance 0.1.2 — hotfix

- Fixed: the party ball rows in battle were drawn one pixel too low, which made empty slots look broken. They now match the game's own sprites.

# DuoBoy Advance 0.1.1 — hotfix

- Partner battles (you and an AI partner against two foes, like the one near the start of a new game) now use the bottom screen like double battles. The ball row shows your Pokémon and your partner's.
- Fixed: in battle types DuoBoy hands back to the game, the game's own battle menu was hidden on the top screen. It now shows normally.

# DuoBoy Advance 0.1.0 — first public preview

The first public build. Supports **Pokémon Unbound 2.1.1.1** (patched onto Pokémon FireRed (USA) v1.0) on dual-screen Android handhelds, developed on the AYN Thor.

## Highlights
- 3DS-style battle bottom screen: actions, moves with info, targets, Pokémon and Bag without leaving the battle, Last Ball button, battler info, Yes/No buttons
- Overworld bottom screen: party with Summary / Shift / Item and drag-to-reorder, region map, menu buttons, tap to continue dialogue
- Save compatibility with mGBA (`.sav`) and RetroArch (`.srm`), save folder choice, import / export, daily backups
- Save states, fast forward, RetroAchievements (softcore)
- IPS / UPS / BPS patcher, home shortcuts, frontend launching
- Safe fallbacks: unfamiliar battle types, untested Unbound builds and unexpected errors fall back to the game's own menus

## Not tested yet
These should work or fall back safely, but haven't been played through yet. Reports welcome.
- Learning a new move in battle ("Delete a move?")
- Eggs in the party (display and item rules)
- The ROM patcher and home shortcuts from start to finish on a device
- Battle types other than wild and trainer singles / doubles and in-game partner battles: link and multi battles, Battle Frontier facilities, Safari-style battles, special scripted battles (these use the game's own menus)
- Unbound versions other than 2.1.1.1 (they run in safe mode)
- Dual-screen devices other than the AYN Thor
- A full new game past the first partner battle

## Known limitations
- Only Pokémon Unbound is supported.
- RetroAchievements hardcore unlocks are rejected by the server for unapproved emulators.
- The bottom screen stays blank until you have your first Pokémon.
