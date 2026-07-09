# Contributing

## Prerequisites

- JDK 21 (Homebrew: `brew install openjdk@21`)
- Set `JAVA_HOME` in your shell profile:
  ```sh
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  ```

## Running locally

```sh
# Run tests
./gradlew test

# Launch a sandboxed IntelliJ instance with the plugin loaded and sample files open
./gradlew runIde

# Build the distributable ZIP
./gradlew buildPlugin
```

---

## Publishing to the JetBrains Marketplace

### Release process

1. Bump the version in `build.gradle.kts`
2. Run tests and build: `./gradlew clean test buildPlugin`
3. Publish to the marketplace: `PUBLISH_TOKEN=<token> ./gradlew publishPlugin`
4. Verify on the marketplace within a few minutes

### Channels

JetBrains Marketplace uses channels for pre-releases rather than a flag:
- No channel (or `stable`) — GA release, visible to all users
- `eap` or a custom channel — pre-release, visible only to subscribers

### Required secrets and accounts

- **`PUBLISH_TOKEN`** — token from `plugins.jetbrains.com`.
- **JetBrains Marketplace publisher account and plugin ID** — must be created before first publish.

### Gradle publish tasks

```sh
./gradlew verifyPlugin   # validate plugin structure before publishing
./gradlew publishPlugin  # publish to JetBrains Marketplace
```

`publishPlugin` reads the token and channel from the `intellijPlatform` block in `build.gradle.kts`.

---

## Codebase orientation

### Feature-to-source mapping

| Concern | File |
|---|---|
| Tool window UI, binary download, credential prompt | `FCSToolWindowFactory.kt` |
| FCS binary detection, validation, version | `FCSBinaryService.kt` |
| Persistent settings, command building, path validation | `FCSConfigurationService.kt` |
| JSON parsing, file path matching, result caching | `FCSResultsService.kt` |
| File save events, IaC detection, auto-scan triggering | `FCSFileScanTriggerService.kt` |
| Inline editor annotations, severity highlighting, tooltips | `FCSExternalAnnotator.kt` |
| Problems view integration | `FCSInspection.kt` |
| `.tf` / `.tfvars` / `.tfstate` file type registration | `FCSFileTypes.kt` + `plugin.xml` |
| FCS CLI download script | `src/main/resources/scripts/fcs_download.sh` |

### Non-obvious architectural details

**Scan result storage — two locations:**
Individual file scans (triggered on save) write results to `java.io.tmpdir/fcs-scan-results/`. Project-wide scans write to the configured output path. `FCSResultsService.getResultsForFile()` checks the temp directory first and falls back to the project output path, so per-file results always take precedence.

**Binary discovery priority:**
`FCSBinaryService` checks in this order: `~/.local/bin/fcs` (the download target) → `which fcs` → common paths (`/usr/local/bin`, `/usr/bin`, `/opt/crowdstrike/fcs/bin`, `~/bin`) → PATH scan. Binaries found inside any open project workspace are rejected outright to prevent workspace-shadowing attacks.

**Duplicate scan cooldown:**
`FCSFileScanTriggerService` enforces a 5-second per-file cooldown. Saving the same file twice in quick succession only triggers one scan.

**Annotation position tracking:**
When a file is edited after a scan, annotations would drift without re-scanning. Instead, `FCSDocumentListener` listens for document changes and updates the line/column of each annotation in memory via `FCSResultsService.updateResultPosition()`, backed by IntelliJ's `RangeMarker` system. Positions stay accurate until the next scan replaces them.

**IaC file detection:**
Both `FCSFileScanTriggerService` and `FCSExternalAnnotator` delegate to `FilePatternMatcher.matches()` using patterns from `FCSConfigurationService.getFilePatterns()`. Default patterns cover extensions `tf`, `tfvars`, `tfstate`, `json`, `yaml`, `yml`, `xml`, `hcl`, `rego`, `dockerfile`, `jinja`, `bicep`, and the bare filename `Dockerfile`. To add a new file type, add a pattern to `DEFAULT_FILE_PATTERNS` in `FCSConfigurationService`.

**Startup initialisation:**
`FCSFileScanTriggerStartupActivity` implements `ProjectActivity` (not the deprecated `StartupActivity`) and runs on project open to register the file listener and clean up old scan results.
