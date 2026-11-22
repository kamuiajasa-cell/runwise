# RunSense Native Android

RunSense Android is a **native Kotlin + Jetpack Compose application**. It does not load the former web app through WebView; the bundled HTML has been removed.

## Implemented

- Native Compose UI matched to `runsense.html`: navy/green palette, floating pill bottom navigation with four icon tabs (Hari Ini, Riwayat, Progres, Saya), editorial page heroes, filter docks, stat tiles, and grouped settings cards. Coach and Jam open from Saya with a back bar.
- Native local activity, strength, wellness, and athlete-profile entry with persistent local storage, including age for the domain athlete profile.
- Strength logging is exercise-specific: timed/isometric movements (e.g. plank, side plank, wall sit) record duration only; dynamic movements record sets × repetitions only. Coach load and progression use the selected dose type without double-counting the other measure; legacy logs remain readable.
- The original RunSense Kotlin domain engine is ported into `:domain`: capacity/stage estimation, LTHR/LT pace, readiness, autonomic checks, session classification, daily Coach design, workout steps, stream analysis, and review logic.
- Coach UI consumes `Snapshot.processData()`, including stage, threshold estimates, 5K estimate, and today's recommendation.
- The native data module (`:data`) includes RunSense-compatible JSON codecs, Intervals.icu ingestion, Huawei watch import, stream summaries, and repository persistence.
- Native Intervals.icu API-key entry and sync adapter. Imported activities and wellness are merged into the local history used by Coach and Progress.
- Native Huawei pairing / connection / sync screen. Synced Huawei workouts and wellness are imported into the same local history; watch stream summaries are passed into the original Kotlin domain engine.
- Progress includes native session review, on-demand Intervals.icu stream retrieval, and collapsible **5-second cadence segments**. Raw cadence below 120 is normalized by doubling; windows above 135 spm are classified as running, and the running-cadence average excludes walking windows.
- CI runs original domain golden tests, focused JSON/Huawei watch-ingestion tests, and app unit tests before packaging the APK.
- Huawei transport source is linked from RunNowNative as a Git submodule under `vendor/RunNowNative`; CI checks out submodules.

## Still to integrate / validate

- Further parity work for the full original Coach state inputs and richer strength progression rules (the current native app now has conservative per-exercise progression based on completed logs).
- Complete native pace/HR zone charts and broader analytics parity with the web version.
- GPS/background run recording and native workout delivery to the watch.
- Verify Huawei pairing, full-history sync, Intervals.icu sync, and stream review on the actual phone and Watch Fit 2. CI compilation is not a substitute for device validation.
- Migrate existing browser-local records; new syncs can restore data from connected Intervals.icu / Huawei sources, but local browser storage is not copied automatically.

The Huawei protocol module is AGPL-3.0-or-later and derived in part from Gadgetbridge. Its license and attribution notices are retained in `vendor/RunNowNative/huawei/LICENSE` and `vendor/RunNowNative/huawei/NOTICE`.

The migration is incremental. A feature is only marked complete after its Kotlin path and regression/device checks are in place.

## Build

```bash
gradle :domain:test
gradle :data:test --tests com.runnow.data.JsonTest --tests com.runnow.data.WatchImportCodecTest
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Uploading to GitHub

1. Extract this ZIP and upload the **contents** (not the wrapper folder) to the repository root.
2. `vendor/RunNowNative` is a Git submodule and cannot be carried inside a ZIP. The workflow now clones it automatically when it is empty. If `kamuiajasa-cell/RunNowNative` is private, either make it accessible or add the submodule locally with `git submodule add https://github.com/kamuiajasa-cell/RunNowNative.git vendor/RunNowNative`.
3. Push to `main`; the **Build RunSense APK** workflow produces `app-debug.apk` as an artifact.
