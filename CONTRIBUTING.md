# Contributing

Thanks for helping. Bug reports, testing on other devices, and code are all welcome.

## Reporting bugs
Open an issue with: what you were doing, what happened, what you expected, your device, and a screenshot of both screens if you can. Never attach ROMs or patched games.

## Building
1. `git clone --recursive` (or `git submodule update --init` for `third_party/mgba` and `third_party/rcheevos`).
2. Install Android Studio with NDK 28.2 and CMake 3.31.6, and a JDK 17 or newer.
3. `./gradlew assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`.

Build types:
- **debug**: everything on, including experimental features and the adb test tools below.
- **release**: what's published. Experimental features and test tools are compiled out. Signed with the maintainer's key when it's available, otherwise the debug key.

## How it's put together
| File | What it does |
|---|---|
| `MainActivity.java` | App shell: emulator thread, top screen, settings actions, saves, ROM list, patcher, shortcuts |
| `Core.java` / `cpp/bridge.c` | JNI wrapper around mGBA: frames, input, memory access, save states |
| `Game.java` | Reads the game's state from memory each frame and drives the game (battle menus, party, bag) through its own functions |
| `Bottom.java` | Draws the bottom screen and handles touch |
| `Skin.java` / `Rom.java` | Load graphics, palettes, fonts and text from the ROM at runtime |
| `Patcher.java` | IPS / UPS / BPS patching |
| `Ra.java` / `cpp/ra.c` | RetroAchievements |

Key ideas:
- **The game stays in charge.** The bottom screen drives the game's own code paths (its cursors, functions and scripts) instead of re-implementing rules, so behaviour matches the game. When something is unfamiliar, it falls back to the game's own menus.
- **Saves stay compatible.** Nothing is written to the save that the game itself wouldn't write.
- **Addresses** for Unbound are documented next to where they're used, usually with the FireRed decompilation name. The pret `pokefirered` symbols are a good starting point; Unbound moves many things, so check by disassembling (`tools/thumbdis.py`).

## Design rules for the bottom screen
- Use only the game's own graphics and colours. No invented colours, gradients or system fonts.
- Never show a prompt on the bottom that the top already shows.
- Avoid errors by design: grey out or block what can't work right now instead of showing an error.
- While the game is playing out (battle text, cutscenes), nothing on the bottom is tappable.
- A touch that scrolled or dragged never clicks the button it ends on.
- Copy the game's behaviour unless there's a good reason not to.

## Test tools (debug builds only)
With the device connected over adb:
```
adb shell am broadcast -a dev.gbads.KEY --ei k <button bits> --ei f <frames>
adb shell am broadcast -a dev.gbads.STATE --es op save|load --ei slot <n>
adb shell am broadcast -a dev.gbads.WILD --ei sp <species> --ei lv <level> [--ei sp2 <species> for a double]
adb shell am broadcast -a dev.gbads.SCRIPT --es hex <script bytes>
adb shell am broadcast -a dev.gbads.POKE --ei a <address> --ei v <byte>
adb shell am broadcast -a dev.gbads.DUMP --es tag <name>   # RAM dump to Download/gbads/<name>.bin
```
`tools/thor.sh` wraps these for the AYN Thor (keys, taps, screenshots of both screens, states). Other tools: `thumbdis.py` (Thumb disassembler for ROM addresses), `gbagfx.py` / `fontdump.py` (graphics and font extraction), `romprobe.py`, `savparty.py`.

Use save states and test copies for experiments. Never test on a save you care about, and never commit ROMs, saves or states.

## Pull requests
- One feature or fix per PR, with a short description and screenshots for anything visual.
- Say what you tested and on which device.
- By contributing you agree your code is released under the project's license (GPL-3.0 with the attribution term in NOTICE).
