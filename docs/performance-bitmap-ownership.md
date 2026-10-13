# Stored raster ownership (PERF2)

`RenkinPackStore` previously kept decoded full-resolution rendered and base bitmaps for
every saved raster row. Identical payloads were decoded once and copied to isolate editable
pixels. Measurements identified these native buffers as a worthwhile memory target.

## Ownership contract

- Saved PNGs retain validated immutable Base64 text and dimensions in `StoredBitmapPixels`.
  Independent `BitmapIconDrawable` wrappers may share that encoded source, never mutable pixels.
- Loading still fully validates each unique representation, with at most two row decoders
  running concurrently. Validation pixels are recycled before the factory returns. Invalid
  rows retain the existing fallback and preservation policy; non-PNG legacy rows remain eager.
- Target-sized previews use sampled decoding and the existing 8 MiB preview cache. Compose
  painters decode off Main and preserve the aspect ratio before and after loading. Adaptive
  preview zoom remains separate from raw browser/export geometry.
- Full export/edit reads decode detached full-resolution pixels. `toDbString()` returns the
  original immutable PNG text without decoding or re-encoding. Explicit access to the public
  mutable `drawable` wrapper materializes resident pixels for that instance only.
- Live generated/editor/recovered artwork keeps its previous eager ownership. Visible Canvas
  and cached bitmaps are never recycled by this change. Recovery disposal still owns only
  the eager bitmaps it constructed.

The drawable class, raster/XML flags, persistence formats and fingerprint inputs are preserved.
Room remains 21, portable backup 4 and recovery preferences 3. This is not a file-storage or
database migration. Full pixel capture can now decode a stored source; its Main-thread cost
must be monitored as well as memory. Sampled decoding follows the Android
[bitmap-loading guidance](https://developer.android.com/topic/performance/graphics/load-bitmap).

## Device comparison, 2026-10-13

Debug builds were compared on the same OnePlus Nord 4, Android 16 / API 36, with six existing
profiles and 938 saved rows. The largest profile has 224 raster rows. The baseline was dev
`46490383`; the candidate is the uncommitted `feature/bitmap-memory-ownership` change based on
that commit. The installed APK SHA-256 matched the locally built candidate:
`281f35a81cc3ea3332d6ace3a338b945a3ebc0520e559432f73ab2c23af85b92`.

| Scenario / metric | Baseline | Candidate |
| --- | ---: | ---: |
| Cold activity launch median, three `am start -W` runs | 1,355 ms | 1,240 ms |
| Cold final PSS median, approximately 12 s after launch | 457.7 MiB | 304.9 MiB |
| Largest-profile switch, sampled maximum PSS | 928.9 MiB | 441.4 MiB |
| Same switch, sampled maximum native allocation | 748.1 MiB | 223.7 MiB |
| Same switch, sampled maximum Java allocation | 48.2 MiB | 56.7 MiB |
| Same switch, final PSS at approximately 12 s | 618.9 MiB | 335.8 MiB |
| Profile read/decode timing | 1,317 ms | 1,378 ms |
| Load-target timing, including nested work | 1,408 ms | 1,548 ms |
| First eight list swipes, deadline misses | 65/406 (16.01%) | 65/406 (16.01%) |
| First swipes, p95 / p99 | 31 / 38 ms | 32 / 38 ms |
| Warm eight list swipes, deadline misses | 28/482 (5.81%) | 19/476 (3.99%) |
| Warm swipes, p95 / p99 | 18 / 23 ms | 18 / 21 ms |

Memory was sampled roughly once per second using `dumpsys meminfo`, with a 0.75 s pause after
each probe. Maxima are lower bounds and can miss short peaks. PSS, native allocation and Java
allocation are different metrics, not quantities to add. Launch time measures the first
activity frame rather than completion of asynchronous profile loading. Scroll runs had no
concurrent memory sampler. Small timing differences do not establish a statistically significant
speedup or regression. This is a debug-device comparison, not an optimized-release benchmark.

On the candidate, Scale changes and combined Colorize / Scale 84% / Outline 6 px / three
erasure strokes captured drafts with Main copy times of approximately 0.9–9.7 ms; total capture
times were approximately 29–95 ms. The combined payload was approximately 740 kB. Force-stop,
restart and Restore returned those controls and all three strokes. The screenshot region
containing the New preview matched exactly before and after Restore. This is a display-region
comparison, not a claim about Apply/export pixel equality. The temporary edit was dismissed
without Apply or Clear, its checkpoint was removed, and the original active profile was restored.

## Verification and remaining work

Compilation, `assembleDebug`, all 1,275 unit tests (174 suites, zero failures/errors/skips),
both existing Roborazzi comparisons and `lintDebug` passed. Five new native graphics tests cover
exact full pixels and independent reads, sampled rectangular previews, adaptive geometry,
editor capture metadata/pixels and Canvas bounds/alpha. Lint reports zero errors and 177
warnings; the three additional warnings are KTX convenience suggestions, without suppressions.
Unsigned `assembleRelease` with R8 and resource shrinking also passed (5 min 20 s).
The existing 11 Kotlin-metadata compatibility warnings remain; no toolchain changes are included.

Raw samples, logs, UI captures and scripts remain local and gitignored under
`build/performance/2026-10-13-perf2/`. No private icon payloads belong in this document or Git.

Installed-application drawables, newly generated bulk artwork, editor intermediates and backup
JSON remain separate memory owners. A refresh can still retain eager generated bitmaps until
the next load. Full bitmap reads trade decoding work for lower retained memory, so future
changes should measure export/build and repeated editing on large sources. No leak attribution,
backup-memory fix, schema migration or release-device performance claim is made here.

## Optional final device checks before merge approval

1. In a disposable profile, open a saved raster icon, change Scale and Outline, then Apply.
   The saved icon should match the completed New preview, including its optical size.
2. Undo that change. The original icon should return, with no effect on other profiles.