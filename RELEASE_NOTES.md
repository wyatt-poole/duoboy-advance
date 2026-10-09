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
These should work or fall back safely, but haven't been played through. Reports welcome.
- "Use next Pokémon?" after a faint in a wild battle: Yes / No work; going back from the Pokémon screen to the question isn't supported yet
- Learning a new move in battle ("Delete a move?")
- Eggs in the party (display and item rules)
- The patcher and home shortcuts from start to finish on a device
- Battle types other than normal wild and trainer singles / doubles: partner and multi battles, Battle Frontier facilities, Safari-style battles, special scripted battles (these use the game's own menus)
- Unbound versions other than 2.1.1.1 (they run in safe mode)
- Dual-screen devices other than the AYN Thor
- Starting a brand-new game all the way to the first Pokémon (the intro and naming were checked)

## Known limitations
- Only Pokémon Unbound is supported.
- RetroAchievements hardcore unlocks are rejected by the server for unapproved emulators.
- The bottom screen stays blank until you have your first Pokémon.
