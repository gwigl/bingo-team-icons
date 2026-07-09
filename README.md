# Bingo Team Icons

A RuneLite plugin that shows a colored, numbered team badge next to player names in chat, based on which bingo team they are on. Works in public chat, friends chat, clan chat (including guest and GIM), and private messages.

## Usage

1. Enable the **Bingo Team Icons** plugin.
2. Open the **Bingo Team Icons** sidebar panel (the small four-color grid icon).
3. Pick the **number of teams** (1–10) — a text box appears for each team.
4. Paste each team's player names into its box, separated by commas or new lines.

Icons update immediately, including on messages already in your chat history. Names are matched case-insensitively, and lowering the team count keeps the hidden teams' rosters saved in case you raise it again.

Team icons also appear on **collection log and drop broadcasts** in clan chat (own clan, guest clan, and group ironman broadcasts), next to the player's name inside the announcement. This can be turned off with the "Broadcast icons" toggle in the plugin's settings (gear icon).

To preview how icons will look, type `::bingotest` in chat (optionally `::bingotest Some Player`) — it locally injects a sample chat message and sample drop/collection log broadcasts for a rostered player. Nothing is sent to the server.

## Development

Requires JDK 11+ (any modern JDK works; the build targets Java 11).

- **Run a dev client:** `gradlew run` (or run `BingoTeamIconsPluginTest` from IntelliJ). To log in with a Jagex account, follow the [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) wiki page.
- **Build:** `gradlew build` — the plugin jar lands in `build/libs/`.

### Playing with the plugin (outside IntelliJ)

The official launcher-installed client cannot load local plugins (developer mode is disabled when the client is started by the launcher), so use the bundled standalone client instead:

1. `gradlew shadowJar` — builds a self-contained RuneLite client with this plugin included.
2. Double-click `run-bingo-client.bat` to launch it.

To log in with a **Jagex account**, export your session once per the [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) wiki page: add `--insecure-write-credentials` to Client arguments in "RuneLite (configure)", log in through the Jagex Launcher once, then remove the argument. The standalone/dev client then logs in automatically using `~/.runelite/credentials.properties`.

For the plugin to work in the normal launcher-installed client, it must be submitted to the [Plugin Hub](https://github.com/runelite/plugin-hub).
