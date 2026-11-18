# Development notes

## Responsibilities

| File | Responsibility |
| --- | --- |
| `MainActivity.java` | Bookshelf, text pagination, navigation, preferences and background work coordination |
| `BookRepository.java` | File-picker imports, private book copies, atomic bookshelf index and text caches |
| `BookTextParser.java` | Text extraction and heading offsets; SAX for FB2, EPUB spine handling, TXT/HTML decoding |
| `ReadingPosition.java` | Preserve reading anchors when legacy whitespace is normalized |
| `PdfPageView.java` | Native PDF page display, zoom and pan |
| `app/src/debug/.../ReaderDataSelfTest.java` | Device-only data checks with disposable files |

The application uses native activities, views and XML resources. It has no third-party application runtime dependency. Books and the bookshelf index live in app-private storage; removing a shelf entry does not delete the original selected document.

## FB2 memory work

FB2 parsing reads SAX events instead of building a DOM and duplicating embedded binary content. The parser skips image/binary payloads and retains printed text, metadata, heading offsets and a bounded element stack. Output text remains resident, so this is not a constant-memory reader.

The parser currently limits an FB2 document to 32 MiB, extracted text to 12 Mi characters and XML nesting to 256 levels. Import-copy and cache limits are separate from these parser limits. The synthetic regressions cover encoding, whitespace across SAX callbacks, repeated headings, multiple bodies, CDATA, rejected entities, malformed XML and the resource limits.

Private full-book investigation helped identify the earlier memory problem. That book and its text are excluded from the repository; the public CI makes no full-book memory claim. Reproduce memory behavior with an appropriately licensed local file and `BookMemoryProbe` if needed.

## Validation boundaries

The host tests compile the real parser and position-migration code with minimal support classes. Those classes are test substitutes, not an Android emulator; HTML conversion and Android persistence/rendering need device checks. A passing parser suite does not validate EPUB/HTML rendering on Android, PDF gestures, saved preferences or lifecycle behavior.

Before calling a build device-verified, install it on a clean test profile and check:

1. Import representative permitted files in each supported format, including malformed and oversized input.
2. Reopen books after process death and confirm text/PDF positions and preferences.
3. Check repeated headings, chapter navigation, verse spacing and old-cache migration.
4. Check pagination after type-size changes, screen rotation, themes and PDF gestures.
5. Run the debug data self-test described in [tests/README.md](../tests/README.md).

Record the APK version, device/Android version and observed result. These steps describe remaining validation, not a claim that they have passed for this snapshot.
