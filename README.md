<p align="center">
  <img width="150" alt="SurgeCord logo" src="assets/surge-logo.svg" />
</p>

<h1 align="center">Surge Manager</h1>

<p align="center">
  Android installer and patcher for SurgeCord.
</p>

<p align="center">
  <a href="https://github.com/VenusIsJaded/SurgeManager/releases/latest">Download</a>
  ·
  <a href="https://github.com/VenusIsJaded/SurgeManager/issues">Issues</a>
</p>

## What it does

Surge Manager builds and installs a SurgeCord-patched Discord client on Android.

It handles:

- downloading supported Discord RNA APKs
- selecting a local APK/APKM file
- patching app name, package name, icons, and manifest values
- downloading and embedding SurgeXposed
- signing patched APKs
- installing patched APKs through Android's PackageInstaller
- keeping install logs for troubleshooting

## Related repositories

- [`Surge`](https://github.com/VenusIsJaded/Surge) — SurgeCord JavaScript bundle
- [`SurgeXposed`](https://github.com/VenusIsJaded/SurgeXposed) — LSPatch/Xposed module
- [`ControlRepo`](https://github.com/VenusIsJaded/ControlRepo) — version metadata

## Release signing

The release workflow supports these repository secrets:

- `SIGNING_KEYSTORE`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

If signing secrets are not configured, CI generates a temporary fallback keystore so test builds can still complete. Use persistent signing secrets for builds you plan to keep installed across updates.

## Development

```bash
./gradlew assembleDebug
```

## Disclaimer

Surge Manager is an independent project and is not affiliated with Discord Inc.
