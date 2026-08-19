# Third-party notices

StageGrid's own source code is licensed under the MIT License. Third-party components remain under their respective licenses; this file records the direct build/runtime/test dependencies pinned by the project.

| Dependency / tool | Pinned version | Purpose | License | Official project / publisher |
|---|---:|---|---|---|
| Android Gradle Plugin | 9.3.1 | Android build system | Apache-2.0 | Android Open Source Project / Google |
| Gradle | 9.5.1 | Build runner / wrapper distribution | Apache-2.0 | Gradle Build Tool |
| Kotlin / Compose compiler plugin | 2.3.21 | Kotlin language + Compose compiler integration | Apache-2.0 | JetBrains |
| Kotlin Symbol Processing (KSP) | 2.3.10 | Room code generation | Apache-2.0 | Google |
| AndroidX Core KTX | 1.17.0 | Android Kotlin extensions | Apache-2.0 | Android Open Source Project |
| AndroidX Activity Compose | 1.13.0 | Activity/Compose integration | Apache-2.0 | Android Open Source Project |
| AndroidX Lifecycle | 2.9.3 | ViewModel/lifecycle runtime | Apache-2.0 | Android Open Source Project |
| Jetpack Compose | BOM 2026.08.00 | UI toolkit / Material 3 / foundation | Apache-2.0 | Android Open Source Project |
| AndroidX Room | 2.8.4 | Structured local database | Apache-2.0 | Android Open Source Project |
| AndroidX DataStore | 1.2.1 | App preferences | Apache-2.0 | Android Open Source Project |
| AndroidX DocumentFile | 1.1.0 | Storage Access Framework tree traversal | Apache-2.0 | Android Open Source Project |
| Kotlin Coroutines | 1.10.2 | Structured concurrency / Flow | Apache-2.0 | JetBrains |
| Oboe | 1.10.0 | Native low-latency audio output abstraction | Apache-2.0 | Google |
| JUnit 4 | 4.13.2 | JVM tests only | EPL-1.0 | JUnit project |
| AndroidX Test JUnit | 1.2.1 | Instrumentation tests | Apache-2.0 | Android Open Source Project |
| Espresso Core | 3.6.1 | Android UI/instrumentation tests | Apache-2.0 | Android Open Source Project |

The Android SDK/NDK/CMake toolchain is not redistributed in this source archive; Android Studio/SDK Manager installs those components separately under their own terms.

No GPL/LGPL DSP, pitch-shifting, or time-stretching library is bundled in this MVP. Pitch/tempo processing remains intentionally disabled until a production-grade implementation with a distribution-compatible license is selected and documented.
