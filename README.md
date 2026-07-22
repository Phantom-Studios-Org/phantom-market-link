# PhantomMarket Link

A client-side [Fabric](https://fabricmc.net/) addon for [Litematica](https://github.com/maruohon/litematica)
that connects Minecraft to your [PhantomMarket](https://market.phantom-node.com) account.

Log in once from Litematica's main menu, and schematics you send from the PhantomMarket
website drop straight into your game: they are downloaded, saved to Litematica's schematics
folder, and (when you're in a world) loaded as a placement automatically.

## What it does

- Adds a button to the bottom-right of **Litematica's main menu**:
  - Logged out → **"Login with Phantom Market"** — starts the OAuth device flow and opens your browser.
  - Logged in → **"Connected to `<username>`"** — a live WebSocket link to PhantomMarket.
    Clicking it asks you to confirm, then logs out.
- Holds a persistent WebSocket to the PhantomMarket gateway (auto-reconnects with backoff).
- When you press **Send to game** on the website, the mod downloads that schematic over HTTPS,
  saves it as `<slug>.<ext>` into Litematica's schematics directory, shows an in-game message,
  and — if you're in a world and it's a `.litematic` — loads it as a placement.
- Reports which world/server you're in so the website can show where a schematic will land.

## Supported versions

Fabric only. Built with [Stonecutter](https://stonecutter.kikugie.dev/); active/pinned version is **1.21.1**.

| Minecraft | Java | Litematica | MaLiLib          | Fabric API      | Yarn            |
|-----------|------|------------|------------------|-----------------|-----------------|
| 1.20.1    | 17   | 0.15.4     | 0.16.3           | 0.92.2+1.20.1   | 1.20.1+build.10 |
| 1.20.4    | 17   | 0.17.4     | 0.18.4-alpha.1   | 0.97.0+1.20.4   | 1.20.4+build.3  |
| 1.21.1    | 21   | 0.19.61    | 0.21.10          | 0.107.0+1.21.1  | 1.21.1+build.3  |
| 1.21.4    | 21   | 0.21.7     | 0.23.5           | 0.119.4+1.21.4  | 1.21.4+build.8  |

Fabric Loader `0.16.10` for all versions. Litematica and MaLiLib are pulled from the
[Modrinth Maven](https://docs.modrinth.com/docs/tutorials/maven/) and mapped with Yarn
(the mapping masa mods are consumed with in dev).

## Building

Requires a JDK 21 (used to run Gradle; per-version compilation uses Java toolchains 17/21).

```bash
# Build every supported version (jars land in versions/<mc>-fabric/build/libs/)
./gradlew chiseledBuild

# Build (or just compile) only the active version
./gradlew build
./gradlew compileJava

# Switch the active version, then build
./gradlew "Set active project to 1.20.1-fabric"
./gradlew build
```

## Configuration

On first launch the mod writes `config/phantom-market-link.json`:

```json
{
  "baseUrl": "https://market.phantom-node.com",
  "gatewayUrl": "wss://market.phantom-node.com/link/connect",
  "sessionToken": null,
  "phantomUsername": null
}
```

- `baseUrl` — PhantomMarket web origin (HTTP API + device flow).
- `gatewayUrl` — WebSocket URL (`/link/connect` on the same marketplace worker).
- `sessionToken` — your BetterAuth session token, written after you log in. **Keep it secret** (it is never logged).
- `phantomUsername` — cached display name for the button.

### Developing against the dev marketplace

Point the mod at the dev environment before logging in:

```json
{
  "baseUrl": "https://marketplace-dev.phantom-node.com",
  "gatewayUrl": "wss://marketplace-dev.phantom-node.com/link/connect"
}
```

## License

All Rights Reserved.
