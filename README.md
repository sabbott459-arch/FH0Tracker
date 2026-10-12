# FH0Tracker v0.2

An unofficial, free, offline-first Frosthaven character tracker, initially for Frozen Fist.

## Features
- HP, scenario XP, campaign XP
- Level 1–9 adjustment
- Gold, three materials, six herbs
- Battle goal checks, editable masteries and perk boxes
- IndexedDB autosaves with localStorage fallback, JSON backup export/import
- Multiple local Frozen Fist characters and recovery snapshots (up to 25 per character)
- Automatic import of existing v0.1 localStorage save on first launch
- Installable progressive web app

## Hosting
Repository Settings → Pages → Deploy from branch → main → / (root). GitHub Free Pages generally requires a public repository. The app will appear at https://sabbott459-arch.github.io/FH0Tracker/ once published.

## Important
This is an unofficial fan project. Class-specific perks and mastery descriptions must be verified against the physical character sheet. Save data stays on each device; it is not synchronized. IndexedDB snapshots are stored on the same device, not in the cloud. Export backups periodically. Level 9 is the default and can be changed from settings. The max HP value can be overridden.

## Development
Static files; no build step or paid services. Open index.html locally for basic testing. Service worker/offline install requires localhost or HTTPS.
