# Off-Season

A winter FTP-building app for triathletes. Android, Kotlin + Jetpack Compose.

## What's where

| Folder | What it is |
|---|---|
| `engine/` | **The plan engine.** All the training logic: blocks, weekly templates, workouts, scheduling around swim/run, load maths, .zwo export. Pure Kotlin with no Android code, so it's tested on your PC. |
| `engine/src/test/` | 42 automated tests that prove the plan follows the rules (max 2 hard days, recovery weeks, progression and so on). |
| `app/` | The Android app: screens, local storage and sharing files. |
| `release/` | The APK file to send to testers. |

Key files to read or tweak:
- `engine/.../WorkoutLibrary.kt`: every workout, and the session length for each hours bucket.
- `engine/.../Scheduler.kt`: the rules for placing sessions on days, with penalty weights at the top.
- `engine/.../Load.kt`: Fitness/Fatigue maths, the traffic-light messages, and `MAX_WEEKLY_FITNESS_RISE`.

---

## Beginner guide (for Matt)

### What's already installed on this PC
- **Android Studio**: the program you use to open, edit and run the app.
- **Android SDK**: in `C:\Users\mdavies\AppData\Local\Android\Sdk`.
- **A virtual phone** called *OffSeasonTest* (Pixel 7), for trying the app without a real phone.
- **Git**: tracks every version of the code, so mistakes can always be undone.

### Opening the project for the first time
1. Open **Android Studio** from the Start menu.
2. The first time, it runs a setup wizard. Choose **Standard** and accept the licences. If it asks where the SDK is, it should find the one above.
3. Click **Open**, pick `C:\Users\mdavies\Projects\OffSeason`, and click **Trust Project**.
4. Wait for the progress bar at the bottom ("Gradle sync") to finish. The first time can take a few minutes.

### Running it on the virtual phone
1. In the toolbar at the top, the device dropdown should say **OffSeasonTest**.
2. Press the green **▶ Run** button. The virtual phone opens and the app starts.

### Running it on your own Android phone
1. On the phone: **Settings › About phone**, then tap **Build number** 7 times. This unlocks "Developer options".
2. **Settings › System › Developer options**: turn on **USB debugging**.
3. Plug the phone into the PC with a USB cable, and tap **Allow** on the phone when it asks.
4. Your phone appears in Android Studio's device dropdown. Pick it and press **▶ Run**.

### Sending it to testers (sideloading)
1. Send `release\OffSeason-0.1.0.apk` to the WhatsApp group, or put it on Google Drive and share the link.
2. Testers tap the file. Android asks them to **allow installs from this source** (WhatsApp, Drive or Files). They allow it once, then tap **Install**.
3. Play Protect may warn that it's from an unknown developer. Tap **More details › Install anyway**. That's expected for an app not in the Play Store.
4. To update: send a new APK. Installing over the top keeps their data.

> To build a new APK after changes: in Android Studio, **Build › Generate App Bundles or APKs › Generate APKs**, or ask Claude to run `gradlew :app:assembleRelease`.
> Remember to bump `versionCode` and `versionName` in `app/build.gradle.kts` for each release.

### Running the tests
In Android Studio, right-click the `engine` folder and choose **Run 'Tests in engine'**. Green = good.

### Getting tester feedback
Testers go to **Settings › Your data › Export** and send you the `.json` file. It contains their FTP history, the sessions they completed and how each one felt.

---

## Status: Milestone 1

Done:
- All 8 screens: onboarding (with disclaimer and readiness questions), FTP test, plan overview, this week, session detail, retest, progress and settings.
- Real plan engine, 42 unit tests.
- .zwo export for single sessions and a zip of the whole week.
- Local-only storage, plus export/import.

Not yet (Milestone 2+):
- Missed-session rescheduling and "reduce targets / repeat week" suggestions from RPE.
- .erg/.mrc and .fit export, and Intervals.icu sync.
- The release APK is signed with a development key. Before the Play Store, create a proper signing key.
