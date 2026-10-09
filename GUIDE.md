# DuoBoy Advance — Guide

The top screen is always the game. The bottom screen changes with what you're doing. Physical buttons always work exactly as in the game; touch is an extra.

## First setup

1. **File access.** On first launch, allow "All files access". Saves are kept as normal files next to your ROM so other emulators (and sync tools like Syncthing) can use the same save.
2. **Add your game.** Title screen → cog (bottom right) → **ROMs**:
   - **ROM folder:** pick the folder with your ROMs; supported games in it are listed.
   - **Add ROM…:** pick a single `.gba` (or a `.zip` containing one).
   - **Patch a ROM…:** pick a patch (`.ips`, `.ups`, `.bps`), then your base ROM. The patched game is saved next to it and added to the list. Patches check the base ROM's checksum, so a wrong base ROM is refused instead of producing a broken game.
   - **Home shortcut:** adds a home-screen icon that boots straight into the current game.
   - Tap a game to play it; tap the ✕ on the right to remove it from the list (the file isn't deleted).
3. **Saves.** Settings → **Save options**: choose the save folder, the save type (`.sav` for mGBA, `.srm` for RetroArch), or import / export a save. Moving to a folder that already has a save asks which one to keep; the other is kept as a backup.

## Overworld

**Top bar:** Trainer Card (left), the in-game time, the view button, and the game's options (right).

**View button:** switches the bottom screen between your **party** and the **region map**. Its icon shows the view you're on. The map needs the Town Map in your bag.

**Party**
- Tap a Pokémon for **Summary / Shift / Item / Cancel**.
  - *Summary* opens the game's summary; B returns straight to the game.
  - *Shift* then tap another Pokémon to swap them.
  - *Item* to give an item from your Items or Berries pocket, or take the held one.
- **Drag** a card onto another to swap them.
- The party is greyed out while a menu, cutscene or script is running, because it can't be changed then.

**Region map:** shows where you are, including inside buildings and caves. Tap a spot to see its name. The party column on the right works like the party view (tap or drag).

**Bottom bar:** Pokédex, Pokémon, Bag (Cube), Save and Mission Log. These open the game's own menus.

**Dialogue:** when text is waiting for A, the bottom screen dims slightly and tapping anywhere continues.

## Battles

**Action screen:** Fight, Pokémon, Bag and Run, with both sides' Pokémon in the middle and party balls for each side. The d-pad and A work too; the highlighted button is the game's own cursor.

- **Tap a Pokémon on the field** for its types, status, HP, stat changes, and (yours only, like the games) its ability.
- **Last Ball** (top left): throws the ball you last used, or the best one in your bag, depending on the game's *Last Used Ball* option. L does the same. It only appears when a throw is allowed (wild battles; in doubles, once one foe is left, from your first Pokémon).
- **Run** shows R when the game's Quick Run is set to R.

**Fight:** your moves with type, category, power, accuracy and PP. Tap ⓘ for a move's description. In doubles you then pick a target.

**Pokémon:** the party as cards. Tap one, then **Shift** or **Summary**. The game's own rules apply (fainted Pokémon, trapping moves, and so on); its messages show here.

**Bag:** your party on the left, pockets on the right. Pick an item, then the Pokémon to use it on. Ethers ask for the move. Tap the description box to read all of it. Balls follow the game's rules (for example, you can't aim while two wild Pokémon are out).

**Yes/No questions** (nickname, learning a move, switching) appear as two buttons; the d-pad works too.

If a battle type isn't supported yet (link battles, Battle Frontier facilities, Safari-style battles and similar), the game's own menus are used on the top screen and the bottom just shows the field.

## Save states and speed

- **Start + Select** (held together): quick save state, quick load state, reset (asks first), or cancel.
- **R2** (hold): fast forward.
- Save states are separate from your in-game save. Your `.sav`/`.srm` only changes when you save in the game.

## RetroAchievements

Settings → **RetroAchievements** to log in (only a login token is stored, never your password). Unlocks pop up on the bottom screen. Hardcore mode is available but RetroAchievements currently rejects hardcore unlocks from emulators it hasn't approved, so softcore is the default.

## Backups

Once a day, before the game starts, your save is copied to DuoBoy Advance's own `save-backups` folder (the last 3 days are kept).

## Popups

The first tap on the bottom screen while a popup is showing only dismisses it, so you never press something behind it by accident.

## If something goes wrong

- **"Untested Unbound version":** your ROM isn't Unbound 2.1.1.1 patched onto FireRed (USA) v1.0, the version DuoBoy Advance was tested with. The game plays normally; the bottom-screen extras are limited to be safe.
- **"DuoBoy hit a problem":** something unexpected happened, so the game's own menus are used for the rest of the session. Restarting the app turns the extras back on. Please report it.
- Report bugs at the project's GitHub Issues page with what you were doing, a screenshot if possible, and your device.
