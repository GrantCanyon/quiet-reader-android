# Parser and device checks

## Host JVM regressions

Use JDK 17 or newer (the project and CI use JDK 21):

```sh
bash tests/run-parser-checks.sh
```

```powershell
.\tests\Run-ParserChecks.ps1 -DevJdkRoot $env:JAVA_HOME
```

The scripts compile the production `BookTextParser.java` and `ReadingPosition.java`, then run `ParserRegression` and `StreamingFb2Regression`. Fixtures are generated in temporary files. No book download, Android SDK or device is needed. The current synthetic run passes 315 parser/position assertions and 40 streaming assertions. The output reports the number actually executed; these are host JVM assertions, not Android instrumentation tests.

- `ParserRegression`: paragraph/poem spacing, nested and repeated heading offsets, position migration and blocked external entities.
- `StreamingFb2Regression`: UTF encodings and Windows-1251, metadata, SAX callback boundaries, CDATA, binary skipping, malformed documents and resource limits.
- `support/`: minimal Android/repository substitutes used only during host compilation. The HTML substitute deliberately does not implement Android HTML conversion.
- `BookMemoryProbe`: an optional local-file diagnostic that prints text/TOC hashes and memory/timing metadata instead of the book text. It is not part of the default regression run.

Optional file arguments are for local investigation only. Do not commit a copyrighted book, personal reading data or its extracted text. Public CI uses synthetic inputs only.

## Android data self-test

Build and install the debug APK on a test device or emulator, then run:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w -r ru.grant.reader/ru.grant.reader.ReaderDataSelfTest
```

This custom instrumentation is included in the debug APK. It checks real Android data and conversion paths using an isolated disposable cache directory. Inspect its `success`, `assertions` and error output; it is not a UI test and is not run by the current CI workflow.

On 2026-11-18 the owner reported that both applications had been checked on a phone and worked. This is recorded as owner-reported manual validation. An APK-specific protocol, device details and per-scenario results were not supplied; the commands above remain available for reproducible device checks.
