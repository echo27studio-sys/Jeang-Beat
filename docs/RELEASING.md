# Releasing a signed APK

Android only installs updates signed with the **same key** as the installed app.
If the release key is lost, users must uninstall and reinstall to update.
**Keep a backup of the keystore and its passwords somewhere safe (not in git).**

## 1. Create the release key (once)

```bash
keytool -genkeypair -v -keystore jeangbeat-release.jks \
  -alias jeangbeat -keyalg RSA -keysize 4096 -validity 10000
```

## 2. Add GitHub secrets (once)

Repository → Settings → Secrets and variables → Actions → *New repository secret*:

| Secret | Value |
|---|---|
| `JB_KEYSTORE_BASE64` | output of `base64 -w0 jeangbeat-release.jks` |
| `JB_KEYSTORE_PASSWORD` | keystore password |
| `JB_KEY_ALIAS` | `jeangbeat` |
| `JB_KEY_PASSWORD` | key password |

## 3. Release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`.
3. The *Release APK* workflow builds `JeangBeat-v0.1.0.apk` and publishes it on the Releases page.

## Building a signed APK locally

```bash
export JB_KEYSTORE_PATH=/path/to/jeangbeat-release.jks
export JB_KEYSTORE_PASSWORD=... JB_KEY_ALIAS=jeangbeat JB_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```
