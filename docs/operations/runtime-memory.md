# Backend memory and PDF exports

Verified on 13 September 2026. These are JVM defaults in `Dockerfile`; a Railway
`JAVA_TOOL_OPTIONS` override takes precedence and must be checked separately.

## Runtime policy

- Java 25 and G1 are unchanged. Maximum heap remains **3 GiB** for image-heavy exports.
- Minimum heap is **128 MiB**, previously 256 MiB. This permits up to 128 MiB less
  committed heap at the floor; it does not guarantee that a running application
  can reach that floor or save that amount of process RAM.
- Keep the existing periodic **full** G1 collection, with a 120-second interval.
  This is an idle eligibility interval, not a deadline after each PDF: the next
  check may find that a more recent collection occurred and defer collection.
  Observe several idle minutes before comparing memory.
- A full collection briefly pauses Java execution. No `System.gc()` is added to
  request handling. A concurrent-G1 trial needed further mixed collection phases
  and did not establish faster reclamation, so that policy was not adopted.
- Remove `SoftMaxHeapSize=1g`: it does not constrain G1. Metaspace, stack settings,
  the maximum heap, and exit-on-out-of-memory behavior remain unchanged.

## PDF findings and fix

`PdfImageEncoder.inspect` now reads image dimensions through an `ImageReader`
without decoding pixels. The reader is disposed and its input stream is closed.
Actual rendition encoding, resolution, image quality, cropping and layout are unchanged.

Catalog source/rendition caches live for one render. The application-wide editorial
cache holds the bundled logo and editorial picture, rather than customer exports.
OpenHTMLtoPDF 1.1.28 `PdfRendererBuilder.run()` closes its renderer internally;
our PDF font helpers use that method and close their output streams.

## Verification and limits

Local OpenJDK 25 on macOS, a synthetic 6000 × 4000 JPEG, five dimension inspections:

| Dimension inspection | Before | After |
| --- | ---: | ---: |
| Allocated bytes per image | 144,506,436 | 22,478 |
| Elapsed time per image | 54.23 ms | 0.06 ms |

This measures temporary allocation, not retained heap or production RAM savings.
Six repeated seven-page catalog exports completed with both implementations;
all seven rasterized before/after pages were pixel-identical. The fixture contains
one product family and is not a maximum-size production catalog benchmark.

With the final Docker flags, the existing periodic collection ran at approximately
240 seconds: committed heap returned to **256 MiB before / 128 MiB after**, with
about 59 MiB live heap in both. The smaller floor caused more short collections during the
fixture (165 ms total pause time versus 53 ms before the idle collection), so this
is not a general throughput claim. The idle full-collection pause was 36 ms before / 28 ms after.

Focused tests: 30 passed; two existing opt-in artifact exports were skipped.
`PdfImageEncoderTest` checks header-only dimensions, the real WebP reader,
invalid input, bounded output, crop behavior and contained product images.

To reproduce the broader existing PDF fixture with Java 25:

```sh
mvn -Dtest=PdfImageEncoderTest,PdfCatalogRendererTest,PdfCatalogStandaloneQaTest test
mvn -Dtest=PdfCatalogStandaloneQaTest -Dcatalog.qa.output=/tmp/catalog-memory-qa test
```

The second command writes 16 synthetic catalog PDFs without starting an HTTP
listener or reading production data. Render these with `pdftoppm` for visual review.

## Interpreting Railway memory

Heap **used**, heap **committed**, and process/container RAM are different metrics.
The application retains its live objects, framework/classes, thread stacks, native
buffers and runtime libraries after an export. Returning unused heap does not make
the backend's total RAM equal to 128 MiB. TST has its own full backend JVM.

Compare current short-interval samples before export, during export and after
several idle minutes in each environment. A multi-day aggregated peak is not the
current reading and does not establish that a PDF caused it. Local macOS RSS is
also affected by OS reclamation/compression and does not predict Linux/Railway RSS.
There is **no guaranteed production RSS reduction** from this local benchmark.

References: [G1 heap sizing and periodic collection](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-g1-garbage-collector1.html),
[G1 tuning options](https://docs.oracle.com/en/java/javase/25/gctuning/garbage-first-garbage-collector-tuning.html),
[SoftMaxHeapSize belongs to ZGC](https://docs.oracle.com/en/java/javase/24/gctuning/z-garbage-collector.html).
