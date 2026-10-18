# FH0Tracker Android — Native rebuild

This Android project now launches **NativeActivity**, a Kotlin/Jetpack Compose implementation. The original WebView MainActivity remains in the repository for reference but is not the launcher.

## First milestone

- Sign in with the same campaign suffix and player PIN used by the website.
- Load the same Supabase character and existing JSON data.
- Native rotary HP and scenario XP dials with Android haptics.
- Native Supplies, Masteries, Perks and Settings tabs.
- Serial cloud writes with revision reads to avoid losing rapid wheel adjustments.
- Combat notification support is being migrated; it is not yet validated on Android 14.

## Not yet migrated

Campaign creation, joining as a new player, manager controls, character retirement/history, backup/import, offline Room storage, stable release signing, robust lock-screen actions and native APK updater.

**Do not uninstall your working Android version to test a new build until the GitHub Actions build succeeds and a safe migration/upgrade path is confirmed.** Cloud records are not deleted by the native rebuild, but local app data may be.

The web tracker remains operational and is not replaced.
