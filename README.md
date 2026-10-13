# Wrist Usage

A Wear OS tile, app and watch-face complication that shows your AI subscription usage limits (Claude and Codex) on your wrist.

Check how much of your 5-hour and weekly limits are left without picking up your phone or opening a laptop. Built for a Samsung Galaxy Watch 6.

<p>
  <img src="docs/images/tile-normal.png" width="220" alt="Tile with usage bars for Claude and Codex">
  <img src="docs/images/tile-warning.png" width="220" alt="Tile with high usage shown in amber and red">
  <img src="docs/images/app-top.png" width="220" alt="App with the full list of limits">
</p>

The screenshots use demo data.

---

## How to install

You need two parts: the collector on your Mac, and the app on your watch. Do the Mac first, because the watch app needs the URL that the Mac setup creates.

### Requirements

- A Mac with Python 3.9 or newer (the built-in `/usr/bin/python3` works)
- GitHub CLI (`gh`), signed in with `gh auth login` (it needs the gist scope)
- Claude Code signed in with a Claude subscription, and/or the Codex CLI signed in with ChatGPT (`codex login`)
- To build and install the watch app: JDK 17 or newer, the Android SDK (Android Studio installs it) and `adb`
- A Wear OS 3 or newer watch (minSdk 30)

### macOS

Get the code:

```sh
git clone https://github.com/Whyater/Wrist-Usage.git
cd Wrist-Usage
```

Check what the collector sees. This prints the snapshot and publishes nothing:

```sh
collector/wrist-usage collect
```

Run setup:

```sh
collector/wrist-usage setup
```

Setup creates a secret GitHub gist with your first snapshot, installs a background job (launchd) that refreshes it every 5 minutes, and writes `snapshotUrl=` into `watch/local.properties`. Add `--dry-run` to see what it would do first.

If you move the folder later, run setup again.

### Watch (Galaxy Watch 6 / Wear OS)

The snapshot URL is baked into the app when you build it, so you build and install it yourself. Your watch and Mac need to be on the same Wi-Fi.

1. On the watch, open Settings > About watch > Software information. Tap "Software version" 5 times to turn on Developer options.
2. Go to Settings > Developer options. Turn on ADB debugging and Wireless debugging. Tap "Pair new device" to see a pairing code and an IP:port.
3. On the Mac, pair and connect. Use the pairing port for the first command, and the port shown on the Wireless debugging screen for the second:

   ```sh
   adb pair <ip>:<pairing-port>
   adb connect <ip>:<port>
   ```

4. Build and install:

   ```sh
   cd watch && ./gradlew :app:installDebug
   ```

   If the build can't find the Android SDK, set `sdk.dir` in `watch/local.properties` or set `ANDROID_HOME`.

5. On the watch, swipe from the watch face to the tiles and scroll to the end. Tap "+" (Add tile) and pick "Wrist Usage". To add the complication, long-press the watch face, choose Customize, then Complications. This only works if your watch face supports complications.

---

## Current features

- A tile that shows every limit for each provider: label, percent, a thin bar and the time until reset. Bars turn amber at 75% and red at 90%.
- "Updated Xm ago" in the footer, based on when the Mac collected the data. It turns amber when the data is over 30 minutes old or the last fetch failed. Tap the footer to refresh. Tap the tile to open the app.
- If there are too many limits to fit, the tile shows the highest ones and "+N more".
- An app with the full list, exact local reset times and a refresh button.
- A complication that shows your highest usage percent.
- Per-provider states for signed out ("Sign in again on the Mac") and errors. It keeps showing the last good data when the watch is offline.
- Claude: 5-hour and weekly limits, per-model weekly limits (like "Fable weekly"), and extra usage when it is enabled.
- Codex: 5-hour and/or weekly limits, and the code review limit when present.

Tested on the Wear OS 6 emulator in large and small round sizes.

---

## Planned features

These are not built yet.

- Set the snapshot URL on the watch, so a prebuilt APK from Releases works without building from source.
- More providers (for example Gemini CLI).
- Collector support for Linux.

---

## Known bugs / limitations

- Not yet tested on a physical Galaxy Watch 6. Only tested on the Wear OS 6 emulator (454 px and 384 px round).
- Data can lag by up to about 10 minutes, sometimes more. The Mac publishes every 5 minutes, GitHub caches the gist file for 5 minutes, and the watch checks every 15 minutes (and when you open the tile).
- The Mac has to be on, awake and online for the numbers to update. Usage from your phone or the web still counts, but it only shows up once the Mac publishes again.
- If you haven't used Claude Code for a while, its sign-in token expires and the tile shows "Sign in again on the Mac". Run Claude Code once to fix it.
- It uses the same usage endpoints the official apps use. They are not public APIs and can change without notice.
- A secret gist is unlisted, not private. Anyone with the link can read it. It only holds percentages, plan names and reset times.
- The collector only runs on macOS.

---

## How it works and privacy

- A small Python script on your Mac (`collector/`) reads the credentials that Claude Code and the Codex CLI already store on the Mac (Claude: a macOS keychain item, Codex: `~/.codex/auth.json`). It asks each provider for your current usage.
- It writes a tiny JSON file with only percentages, plan names and reset times. The format is in [docs/snapshot-format.md](docs/snapshot-format.md).
- `setup` puts that file in a secret GitHub gist and installs a background job that refreshes it every 5 minutes.
- The watch downloads the gist directly over Wi-Fi or LTE, so it works when your phone is not around.
- Your tokens stay on the Mac and only go to Anthropic and OpenAI, the same way the official tools use them. The gist and the watch never see them.
- The collector never refreshes tokens itself, because that could sign Claude Code or Codex out.

Logs are written to `~/Library/Logs/wrist-usage.log`.

---

## Collector commands

Run these from the repo as `collector/wrist-usage <command>`.

| Command | What it does |
|---|---|
| `collect` | Prints the snapshot. |
| `serve [--host] [--port 8765]` | Starts a local test server at `/usage.json`. Defaults to 127.0.0.1. |
| `setup [--dry-run] [--no-launchd]` | Creates the gist, installs the background job and writes `snapshotUrl=` into `watch/local.properties`. |
| `publish` | Collects and publishes a snapshot now. |
| `uninstall [--delete-gist]` | Removes the background job and the local config. With `--delete-gist` it also deletes the gist. |

---

## Run from source / testing on an emulator

You can try the watch app without a gist. Start the local server on the Mac:

```sh
collector/wrist-usage serve
```

Then set this in `watch/local.properties` and build as usual:

```properties
snapshotUrl=http://10.0.2.2:8765/usage.json
```

`10.0.2.2` is how the Android emulator reaches your Mac. Debug builds allow plain HTTP only to that emulator host.

---

## Uninstall

```sh
collector/wrist-usage uninstall --delete-gist
```

Then uninstall the app from the watch the usual way, or with `adb uninstall io.github.whyater.wristusage`.

---

## License

MIT. See [LICENSE](LICENSE).
