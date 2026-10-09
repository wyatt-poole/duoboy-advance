# DuoBoy Advance

A dual-screen Game Boy Advance experience for Android handhelds like the **AYN Thor**. The game runs on the top screen; the bottom screen becomes a touch companion styled after the game itself, inspired by the 3DS Pokémon games.

**Version 0.1.0 — first public preview.** Supports **Pokémon Unbound 2.1.1.1** only. More games are planned.

> DuoBoy Advance does not include any game. You need your own legally obtained ROM.

## What it does

**In battle (bottom screen)**
- Action screen in the 3DS style: Fight, Pokémon, Bag, Run, with the field and both sides' Pokémon
- Move list with type, category, power, accuracy, PP and move info
- Target selection for double battles
- Pokémon and Bag screens on the bottom, while the top keeps showing the battle
- Last Ball button (follows the game's "Last Used Ball" option)
- Tap a Pokémon on the field for its types, status, HP, stat changes and ability
- Yes/No prompts (nickname, learn move, switch) as buttons

**In the overworld (bottom screen)**
- Your party, laid out like the game's party menu: tap for Summary / Shift / Item, or drag one card onto another to reorder
- Region map with your position (needs the Town Map)
- Menu buttons that open the game's own menus
- Tap anywhere to continue dialogue that's waiting for A

**System**
- Built on the mGBA emulator core
- Saves stay compatible: `.sav` (mGBA) or `.srm` (RetroArch), in a folder you choose, with import and export
- Save states (hold Start + Select), fast forward (R2)
- RetroAchievements (softcore)
- ROM hack patcher: IPS, UPS and BPS, from a `.gba` or `.zip` base ROM
- Home-screen shortcuts, and launching from frontends such as ES-DE or Daijisho
- Daily automatic save backups

Everything on the bottom screen is drawn from the game's own graphics and palettes, read from your ROM at runtime. DuoBoy Advance ships no game assets.

## Requirements

- An Android device with two screens. Developed on the **AYN Thor**; other dual-screen devices are untested.
- Android 10 or newer, 64-bit ARM
- **Pokémon Unbound 2.1.1.1**, patched onto a clean **Pokémon FireRed (USA) v1.0** ROM (the 1636 "Squirrels" dump). FireRed **Rev 1** won't work with the official patch; DuoBoy's patcher checks this and tells you. Other Unbound versions run in a limited safe mode.

## Install

1. Download `DuoBoyAdvance-0.1.0.apk` from [Releases](../../releases) and install it.
2. Open it and allow file access (needed so your saves can live next to your ROM).
3. Open Settings (the cog on the title screen) → ROMs → Add ROM, and pick your Unbound `.gba`.
   - Have the FireRed ROM and the Unbound patch instead? Use **Patch a ROM…**.

See the **[Guide](GUIDE.md)** for everything else.

## Status

This is an early preview. Things that haven't been tested yet are listed in the [release notes](RELEASE_NOTES.md). If something goes wrong, DuoBoy Advance falls back to the game's own menus rather than crashing. Please [open an issue](../../issues) with what happened.

## Roadmap and contributing

See [ROADMAP.md](ROADMAP.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

## Credits

- **DuoBoy Advance** by Wyatt Poole. Built with help from AI tools.
- [mGBA](https://mgba.io) by endrift and contributors (MPL 2.0)
- [rcheevos](https://github.com/RetroAchievements/rcheevos) by RetroAchievements (MIT)
- **Pokémon Unbound** by Skeli and the Unbound team
- The [pret](https://github.com/pret) decompilation projects, used as a reference for how the game works

## License

GPL-3.0, with an attribution requirement: see [LICENSE](LICENSE) and [NOTICE](NOTICE). Forks and derivatives must stay open source and keep the credit to the original project.

## Disclaimer

DuoBoy Advance is a fan project. It is not affiliated with or endorsed by Nintendo, Game Freak, Creatures Inc., The Pokémon Company, or the Pokémon Unbound team. Pokémon and related names are trademarks of their respective owners. No ROMs, patches or game assets are distributed with this project.
