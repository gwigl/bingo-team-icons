# Bingo Team Icons

A RuneLite plugin that shows a colored, numbered team badge next to player names in chat, based on which bingo team they are on. Works in public chat, friends chat, clan chat (including guest and GIM), and private messages.

## Usage

1. Enable the **Bingo Team Icons** plugin.
2. Open the **Bingo Team Icons** sidebar panel (the small four-color grid icon).
3. Pick the **number of teams** (1–10) — a text box appears for each team.
4. Paste each team's player names into its box, separated by commas or new lines.

Icons update immediately, including on messages already in your chat history. Names are matched case-insensitively, and lowering the team count keeps the hidden teams' rosters saved in case you raise it again.

## Development

Requires JDK 11+ (any modern JDK works; the build targets Java 11).

- **Run a dev client:** `gradlew run` (or run `BingoTeamIconsPluginTest` from IntelliJ). To log in with a Jagex account, follow the [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) wiki page.
- **Build:** `gradlew build` — the plugin jar lands in `build/libs/`.

### Using with the regular RuneLite client

RuneLite only loads sideloaded plugins in developer mode:

1. `gradlew jar`
2. Copy `build/libs/bingo-team-icons.jar` into `~/.runelite/sideloaded-plugins/` (create the folder if needed).
3. In the RuneLite **launcher** settings, add `--developer-mode` to the client arguments.

For normal (non-developer-mode) use, the plugin would need to be submitted to the [Plugin Hub](https://github.com/runelite/plugin-hub).
