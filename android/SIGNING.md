# Stable Android signing (required for native APK builds)

FH0Tracker must use the **same certificate** for every Android update. A new debug key generated on a GitHub-hosted runner cannot update an APK signed by another key.

## One-time setup on Windows (PowerShell)

1. Install a JDK with `keytool` (Android Studio's bundled JDK works), and open PowerShell.
2. Create a signing key **once** in a safe folder:

```powershell
mkdir "$HOME\FH0TrackerSigning" -Force
keytool -genkeypair -keystore "$HOME\FH0TrackerSigning\fh0-debug.keystore" -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=FH0Tracker,O=FH0Tracker,C=US" -noprompt
```

3. Encode it into the clipboard (do **not** paste it into chat, GitHub issues, or repository files):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\FH0TrackerSigning\fh0-debug.keystore")) | Set-Clipboard
```

4. In the GitHub repository, open **Settings → Secrets and variables → Actions → New repository secret**. Name the secret exactly `FH0_SIGNING_KEYSTORE_B64` and paste the clipboard value.
5. Back up `$HOME\FH0TrackerSigning\fh0-debug.keystore` securely. If you lose the key, you lose the ability to issue compatible upgrades for APKs signed with it.
6. Trigger a new workflow from **Actions → Build Android APK → Run workflow**.

The workflow restores the keystore from the encrypted GitHub Actions secret into `~/.android/debug.keystore` before Gradle signs the APK. The alias and password are the Android debug defaults (`androiddebugkey` / `android`).

**Important:** The first APK built with this new key cannot update an existing installation signed by a different key. That requires a one-time uninstall/reinstall (or recovery and reuse of the original signing key). Future APKs using this secret can update in place, provided versionCode increases.

Before uninstalling, verify the character data is visible in the web tracker and preserve any unsynced local changes. Uninstalling deletes local app storage, but does not delete Supabase campaign records.

**Production note:** This is a persistent *development/debug* signing key. Before distributing the app more widely, migrate to a properly protected release key and a release-signing pipeline. Do not publish this keystore or its base64 encoding.
