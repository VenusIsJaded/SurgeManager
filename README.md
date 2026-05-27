<p align="center">
  <img src="assets/surge-logo.svg" width="128" alt="SurgeCord logo" />
</p>

# Surge Manager

Surge Manager is the Android installer/patcher for **SurgeCord**. It downloads the required patching components, applies the SurgeCord/Xposed integration to Discord, signs the patched APK set, and installs it on-device.

## Repositories

- **Bundle:** [`VenusIsJaded/Surge`](https://github.com/VenusIsJaded/Surge)
- **Xposed module:** [`VenusIsJaded/SurgeXposed`](https://github.com/VenusIsJaded/SurgeXposed)
- **Version metadata:** [`VenusIsJaded/ControlRepo`](https://github.com/VenusIsJaded/ControlRepo)

## Features

- Automatic Discord RNA APK download flow.
- Local APK/APKM support.
- SurgeXposed injection through LSPatch.
- Custom package name, app name, debug flag, and icon options.
- Install logs for troubleshooting.
- Release workflow for APK publishing.

## Release signing

The release workflow supports these repository secrets:

- `SIGNING_KEYSTORE`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

If these are missing, the workflow generates a temporary fallback keystore so CI can still produce an APK. Use real signing secrets before publishing production builds.

## Logo

The SurgeCord logo is a cyan/purple surge bolt on a dark circular background. Source artwork is in `assets/surge-logo.svg`.
