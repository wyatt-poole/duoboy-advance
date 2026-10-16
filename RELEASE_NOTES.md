# DuoBoy Advance 0.2.6

- **Yes/No prompts are now linked, not hidden.** The game's own box and question stay on the top screen in battle and in the overworld; the bottom buttons follow its cursor and answer it. Nothing is hidden, cleared or frame-skipped on top any more, so the flashes and the stuck or false prompts that came with that are gone. (Hiding the box properly is back on the roadmap.)
- Overworld prompts are only detected in the field, so the Options screen can no longer trigger a bottom Yes/No.
- A tap outside the Yes/No buttons does nothing while a prompt is up (it no longer counts as A).
- Picking a move or target keeps the top covered until the game's menu has scrolled off, checked from the game's state rather than a timer.

# DuoBoy Advance 0.2.5

- **Battle prompts show the right question.** The text buffer read for "Will you switch?", "Give a nickname?" and the learn-move prompts was a stale copy; the overlay now reads the game's displayed string (gDisplayedStringBattle) and shows the last page of a multi-page message with its line break.
- **Yes / No answers on the first tap:** the answer is re-pressed until the prompt is gone, so a tap made while the question is still printing no longer just moves the cursor.
- **Item sounds from the bottom bag** match the party menu: the recovery sound for a Medicine that worked, the select beep for "It won't have any effect."
- Dev builds: KEY hook extras `classic 0|1` and `pause 1`; screenshots named by view.

# DuoBoy Advance 0.2.4

- **Party cards** (overworld and battle) now match the game's party menu pixel for pixel: same grid, same small font, Poké Ball and icon in the corner, Lv / HP rows where the game puts them.
- **Classic mode** (Start + Select → Classic mode): the game runs like on a GBA with no bottom screen, and the second screen is released for other apps. The pause menu then shows on the top screen. Remembered between launches.
- **No more flashes** of the game's own boxes on the top screen: battle Yes/No prompts (including "switch Pokémon?" after a KO and "Delete a move?") are detected from the box itself, hidden, and the question is shown in DuoBoy's text box; overworld Yes/No boxes stay hidden while the game is still printing; picking a move or target no longer flashes the game's menu.
- **Bag / Pokémon** from the battle screen open without the black frame; the held-item icon is drawn whole.
- **Potion from the bottom bag** plays the item sound, rolls the HP bar up and shows "HP was restored".
- Overworld party menu says **Switch** (battle keeps Shift). Menu icons are no longer all greyed after saving.
- Screenshots are named with where they were taken (party, map, dexnav, battle-menu, …).

# DuoBoy Advance 0.2.3

- DexNav view: the list scrolls cleanly down to the bar (no fixed strip or stray text at the edge); no extra band at the bottom.
- Docs: every battle type uses the bottom screen (Safari labels are ready for vanilla FireRed later; Unbound has no Safari battles).
- README: icon and screenshots.

# DuoBoy Advance 0.2.2

- **All Yes/No prompts** — dialogue, the save prompt, overworld move-learning — now show as buttons on the bottom screen with the game's box hidden (battle prompts already did).
- **Install Unbound…** (Settings → ROMs): downloads the official patch, patches a FireRed (USA) v1.0 ROM found on the device and adds the game; the download is deleted afterwards. The patch is hosted with the author's permission.

# DuoBoy Advance 0.2.1

- **Every battle type** gets the bottom screen (tag battles with a partner, Frontier-style, Unbound's extra kinds). Safari battles show Ball / Bait / Rock / Run.
- **Battle Yes/No prompts** — including "Delete a move for…?" and "Stop learning…?" on level-up — now show as buttons, with the game's box hidden and the question in our text box.
- **DexNav view:** Pokémon are grouped by when they appear (all day first, then "Night only (now)" and so on); scrolling slides under the header and footer bands.
- **Take held item** asks Yes / No first.
- **Pokédex page after a catch** advances on tap; Run / Quick Run no longer flash the game's menu.
- **Pause menu:** Fast forward On/Off row.
- **ROMs:** "Get Unbound patch" opens the official page; after patching, offers to delete the patch file.

# DuoBoy Advance 0.2.0

**Setup**
- First run: supported games are listed first and marked; `.zip` ROMs are unpacked for you; "Browse…" and "Rescan" if nothing is found. Picking a file that isn't Unbound now explains why instead of crashing on every launch.
- If a save for the game already exists (next to the ROM, or in RetroArch's `saves/mGBA`), DuoBoy offers to use it on the first boot.
- Save states and daily backups now live in `DuoBoy Advance/` on shared storage, so they survive an uninstall. Existing ones are moved over automatically.
- Settings → Top screen: **Fill** (default) or **Pixel-perfect** (whole-number scaling, small black borders).
- Settings messages use the game-style popup on the bottom screen.

**Pause menu (Start + Select)**
- **Screenshot**: both screens as one picture, 4×, into `Pictures/DuoBoy Advance`.
- **Load state** asks once more; hold it to pick any slot (with dates).

**Battles**
- Partner battles name the Pokémon that's choosing in the top text box; no flash of the game's menu on Run / Quick Run or the Last Ball button.
- Tapping advances the level-up stats box like other text.

**Overworld**
- Signs and other text you can walk away from are no longer treated as cutscenes: no dimming, no tap-for-A.
- Stronger dimming during real cutscenes and while the game is busy.

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
- Battle Frontier-style facilities and Safari battles (supported, lightly tested)
- Unbound versions other than 2.1.1.1 (they run in safe mode)
- Dual-screen devices other than the AYN Thor
- A full new game past the first partner battle

## Known limitations
- Only Pokémon Unbound is supported.
- RetroAchievements hardcore unlocks are rejected by the server for unapproved emulators.
- The bottom screen stays blank until you have your first Pokémon.
