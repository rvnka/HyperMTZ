# HyperMTZ

Patches MIUI theme authorization checks using an Accessibility Service and
[Shizuku](https://github.com/RikkaApps/Shizuku) for privileged file operations.

## Requirements

- Android 9 (API 28) or later
- MIUI / HyperOS device with ThemeManager
- [Shizuku](https://shizuku.rikka.app) v11+ installed and running
  (via ADB or wireless debugging — root not required)

## Setup

1. Install and start Shizuku (see [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/))
2. Open HyperMTZ → grant Shizuku permission when prompted
3. In the setup dialog: tap **Accessibility Settings** → enable HyperMTZ
4. Tap **Battery Settings** → set battery optimization to **Unrestricted**
5. Lock HyperMTZ in the Recents screen (prevents MIUI from killing it)

## Build

### Debug APK (recommended for testing)

```bash
chmod +x gradlew
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Or via the included Makefile:

```bash
make          # assembleDebug
make install  # install to connected device via adb
make clean    # clean build outputs
```

### Release APK (signed)

Set the following environment variables, then build:

```bash
export KEYSTORE_PATH=/path/to/keystore.jks
export KEYSTORE_PASSWORD=your_keystore_password
export KEY_ALIAS=your_key_alias
export KEY_PASSWORD=your_key_password

./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

## CI / GitHub Actions

| Trigger | Job | Output |
|---|---|---|
| Push to `main` / `master` / `dev` | Debug build + lint | Debug APK artifact (14 days) |
| Pull request to `main` / `master` | Debug build + lint | — |
| Push tag `v*` | Release build (signed) | Release APK artifact + GitHub Release |
| `workflow_dispatch` | Release build (signed) | Release APK artifact |

### Required repository secrets (for release builds)

| Secret | Description |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded `.jks` keystore file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias within the keystore |
| `KEY_PASSWORD` | Key password |

## Permissions

| Permission | Reason |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Browse and copy `.mtz` files from external storage (Android 11+) |
| `READ/WRITE_EXTERNAL_STORAGE` | Same — legacy path for Android < 11 |
| `moe.shizuku.manager.permission.API_V23` | Communicate with Shizuku manager |

## License

This project is provided as-is for personal use on MIUI/HyperOS devices.
