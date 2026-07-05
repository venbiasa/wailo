# Contributing

Thanks for your interest in Waylay.

## Development setup

- JDK 21 (the Android Studio JBR 21 works). Ensure the Android SDK is installed (compileSdk 36).
- Use the Gradle wrapper: `./gradlew`.
- Copy your Android SDK path into `local.properties` (`sdk.dir=/path/to/Android/sdk`) if it is not
  picked up from `ANDROID_HOME`.

## Building

```bash
./gradlew projects
./gradlew build
./gradlew :sample-android:assembleDebug
```

## Guidelines

- Read [AGENTS.md](AGENTS.md) for the module dependency rules and hard invariants, and
  [ARCHITECTURE.md](ARCHITECTURE.md) for the why.
- Keep all versions in `gradle/libs.versions.toml`.
- The interceptor SDK ships inside third-party apps: keep it small and never make it depend on the
  engine, shared, or desktop modules.
- Significant design changes should be recorded as an ADR in [DECISIONS.md](DECISIONS.md).

## Commits & PRs

- Small, focused PRs aligned to the milestones in the README roadmap.
- Make sure `./gradlew build` passes before opening a PR.
