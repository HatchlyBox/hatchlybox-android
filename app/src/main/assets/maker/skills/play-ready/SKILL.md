---
name: play-ready
description: Make the app ready for Google Play's review - permissions only when needed, a correct data-safety answer, no policy problems in the listing, target audience, accessibility and stability. Use before the app's first upload to Play, when adding a permission, and when the user asks to publish.
---

# Ready for Google Play

Hatchlybox builds the store file, signs it and uploads it. What Google reviews is the app itself and
what it declares. Check this list before the first upload and after adding any permission.

## Permissions

- Only what a feature needs, asked at the moment it's used, with a reason on screen first.
- Avoid the ones that need a Play declaration form unless the app is about them: exact alarms,
  `READ_SMS`/`CALL_LOG`, `MANAGE_EXTERNAL_STORAGE`, background location, `QUERY_ALL_PACKAGES`,
  accessibility services, foreground-service types.
- Photos: the system photo picker (`ActivityResultContracts.PickVisualMedia`) needs no permission.

## Data safety (Play Console › App content)

- `play/listing.json` has `"dataCollected"`: `true` only if the app sends the user's data off the
  phone (accounts, analytics, uploads). Fetching public data doesn't count.
- No ads SDKs, analytics or crash reporters unless the user asked: each one changes the data-safety
  answers.

## Content

- The store listing describes only what the app really does; no "best", "#1", emoji or prices in the
  title (see the listing rules Hatchlybox gives you).
- Apps for children have extra rules: say "13 and over" as the target audience unless the app is
  meant for kids.
- No copyrighted names, logos, music or images you don't own.

## Quality Google checks

- No crash on start, in portrait and landscape (see the see-it-run skill).
- Works offline or says clearly why it can't.
- Back button works everywhere; nothing is cut off by the system bars (edge to edge).
- Text readable at the largest font size; buttons at least 48 dp.
