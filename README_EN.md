# 16KB Checker

`16KB Checker` is a Kotlin-based toolchain for analyzing Android build artifacts and detecting native library risks related to 16 KB page-size compatibility.

## Features

- Scan APK and AAB artifacts directly
- Check ELF load-segment alignment for native libraries
- Detect ZIP alignment and compressed `.so` packaging issues
- Generate final compatibility judgement for the artifact
- Show per-ABI, per-library issue details
- Trace `.so` files back to dependency origins
- Export reports in JSON, Markdown, and HTML
- Run from CLI, Gradle, or Android Studio

## Capabilities

### Artifact Analysis

- Inspect built APK/AAB files instead of source code
- Detect blocking ELF issues that can break loading on 16 KB devices
- Surface non-blocking risks such as compressed native libraries or non-ideal alignment

### Final Judgement

- Classify artifacts into compatibility outcomes such as:
  - native 16 KB compatible
  - backcompat/risk path
  - compressed fallback
  - incompatible

### Dependency Attribution

- Match scanned native libraries to their origins
- Help identify which SDK, AAR, or local library introduced the issue

### Reporting

- Produce machine-readable JSON for CI
- Generate Markdown and HTML reports for review and sharing

## Usage

### Gradle Plugin

Apply the plugin in your Android project:

```kotlin
plugins {
    id("com.check16k.plugin")
}

check16k {
    pageSize.set(16_384)
    strict.set(true)
    checkZipAlignment.set(true)
    checkCompressed.set(true)
    compressedAsError.set(false)
    inferOrigin.set(true)
    artifactType.set(ArtifactTypePreference.AUTO)
}
```

Run:

```bash
./gradlew check16kDebug
./gradlew check16kRelease
```

### CLI

Run against an APK or AAB:

```bash
./gradlew :cli:run --args "app-release.apk --output report.json"
```

Common options:

- `--variant <name>`: add variant name to the report
- `--origins <file>`: provide hash-to-origin mapping JSON
- `--abi-filter <list>`: limit scanning to selected ABIs
- `--page-size <int>`: override page size, default is `16384`
- `--no-zip-align`: skip ZIP alignment checks
- `--allow-deflate`: skip compressed `.so` checks
- `--compressed-error`: treat compressed `.so` as error
- `--no-origin`: disable origin inference
- `--no-strict`: do not exit non-zero on incompatible results

Example:

```bash
./gradlew :cli:run --args "app-release.apk --abi-filter arm64-v8a --output report.json"
```

### Android Studio Plugin

- Open `Tools` -> `16kb Check`
- Select the APK or AAB to analyze
- Review the final judgement and generated report
