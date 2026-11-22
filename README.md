# RunSense Native Android

RunSense Android is a **native Kotlin + Jetpack Compose application**. It does not load the former web app through WebView; the bundled HTML has been removed.

## Implemented

- Native Compose navigation and RunSense dark visual system: Beranda, Latihan, Progress, Coach, Jam, and Setelan.
- Native local activity, strength, wellness, and athlete-profile entry with persistent local storage.
- The original RunSense Kotlin domain engine is ported into `:domain`: capacity/stage estimation, LTHR/LT pace, readiness, autonomic checks, session classification, daily Coach design, workout steps, stream analysis, and review logic.
- Coach UI consumes `Snapshot.processData()`, including stage, threshold estimates, 5K estimate, and today's recommendation.
- The native data module (`:data`) includes RunSense-compatible JSON codecs, Intervals.icu ingestion, Huawei watch import, stream summaries, and repository persistence.
- Native Intervals.icu API-key entry and sync adapter. Imported activities and wellness are merged into the local history used by Coach and Progress.
- Native Huawei pairing / connection / sync screen. Synced Huawei workouts and wellness are imported into the same local history; watch stream summaries are passed into the original Kotlin domain engine. Structured workout delivery to the Huawei watch has also been validated on the user's Watch Fit 2, including warmup, main intervals, active recovery, and cooldown.
- Progress includes native session review, on-demand Intervals.icu stream retrieval, and collapsible **5-second cadence segments**. Raw cadence below 120 is normalized by doubling; windows above 135 spm are classified as running, and the running-cadence average excludes walking windows.
- CI runs original domain golden tests, focused JSON/Huawei watch-ingestion tests, and app unit tests before packaging the APK.
- Huawei transport source is linked from RunNowNative as a Git submodule under `vendor/RunNowNative`; CI checks out submodules.

## Still to integrate / validate

- Full parity for the original strength progression menus (daily menu selection, full per-exercise levels, skip/recovery rules, and form check) and pre-run subjective check-in remains incomplete. Native strength logs, set/repetition/load capture, conservative per-exercise progression suggestions, post-run RPE/pain check-in, weekly target, and Coach load integration are present.
- HR-zone distribution is now rendered in native Progress when stream data is available. Pace-zone charts and remaining analytics parity with the web version are still incomplete.
- GPS/background run recording remains to be integrated and validated.
- Full-history Huawei sync, Intervals.icu sync, and stream review still require repeatable end-to-end regression checks on the actual phone and Watch Fit 2. CI compilation is not a substitute for device validation.
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
