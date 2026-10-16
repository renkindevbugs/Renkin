# PERF6a: backup and profile export memory

Date: 2026-10-16. Branch: `feature/backup-memory-measurement`, based on `dev` at `585c9f81`.
Measurement checkpoint only: production backup code, formats and limits are unchanged.

The current workload fits this phone, but reading a complete backup creates substantial transient
allocation and retains every icon payload. The export JSON tree shares source strings and is
comparatively small. The next useful boundary is streaming inspection/validation, followed by
row-wise export. Removing the export tree alone would not remove the main payload ownership.

## Source ownership

The codebase-memory graph traced `BackupManager.prepareBackupExport`/`prepareProfileExport` through
repository reads, validation and `exportArchive`; source inspection verified the actual edges.
Graph matches for generic names such as `put`/`map` were not treated as evidence.

1. `RenkinPackRepository.getAll`/`getAllProfilesApplications` construct complete `DbApplication`
   lists under a consistent Room read, including oversized artwork. Sharing makes metadata copies
   and resolves missing pack drawable names; full backup groups rows and validates modifier-input
   files. Original strings remain owned through preparation. Serializing stored artwork does not
   require decoding its full raster pixels.
2. `BackupValidator.prepare` normalizes watch metadata and validates identities/style ownership.
   `BackupCodec.encodeJson` builds containers referencing existing drawable/base strings.
   `writeBackupData` traverses that tree with JsonWriter into a bounded file; production does not
   call the whole-String `BackupCodec.encode()` API.
3. `exportArchive` creates an immutable staged ZIP under the storage gate and manager mutex.
   `writePreparedExport` copies it with an 8 KiB buffer outside those locks, then releases staging
   on success, failure or cancellation.
4. Inspection stages and CRC-checks the archive, builds a complete JSON tree, then DTOs referring
   to its strings. Equal rendered/base values share a reference within each decoded row. The tree
   and DTOs overlap during decoding; DTOs retain payloads through validation/summary creation.
   PreparedImport retains staged files and a summary, not the DTO graph. Actual import reads the
   model again before its existing recovery-protected commit.

DTOs, parser buffers, uncollected garbage, active artwork, native allocation and mapped code are
different contributors. No allocation-stack or retained-heap trace was taken; no leak is identified.

## Device and measurement controls

OnePlus CPH2663, Android 16 / API 36, debug package `dev.renkinProject.renkin.dev` only.
Room/portable-backup/recovery-prefs versions remain 21/4/3. Java heap capacity was 256 MiB.
Original data contained six profiles, 939 saved rows and 46 regular persistent files. The largest
profile had 224 rows and 28,697,500 UTF-8 artwork bytes; all profiles had 49,664,660 bytes. These
sums count both rendered/base columns, without deduplication. The largest combined row had
412,756 characters. No synthetic large profile or deliberate capacity/OOM stress was used.

The original active profile had three saved icons. Another probe temporarily selected the largest
profile through MainViewModel, without Save/Apply; its live list had 220 applications and 211
created icons. Stored rows for unavailable activities still travel in backups. Original data and
the active selection were restored exactly afterwards.

- Original installed debug APK SHA-256:
  `bfa2aae3a32ae3f1a06f96ebc8052249820bc9e0cfe46537df62f4214bf271a1`.
- Measurement production APK SHA-256:
  `919c0e3881291a0b01f9e7e22cedf0ac305705124f0676b278dd4eb40768b1f1`.
- The new probe is androidTest only; no production Kotlin/resource changes were needed.

Ordinary `dumpsys meminfo` calls the app's dump path, which requests GC in
[AOSP ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/core/java/android/app/ActivityThread.java).
The phone's help documents `--local` as collecting locally without calling the process. Ten-second
idle controls observed 20 GC with ordinary meminfo, one with no external sampler, and zero with
`--local`. These single controls are not a statistical equivalence claim.

Primary heap/timing runs therefore used no external meminfo. A separate local PSS run of the
large-active-profile workload had one GC per preparation and two per inspection, matching its
unsampled counterpart. Ordinary-meminfo results remain diagnostic logs, not natural-collection
heap bounds. Earlier installed-icon reports now clarify this effect without changing their
historical numbers or presenting them as reruns.

The test samples process Java used heap/native allocated bytes approximately every 10 ms. ART
allocation/GC deltas include background work and probe overhead; allocated bytes are cumulative,
not resident memory. Maxima are sampled lower bounds and may include dead objects awaiting GC.
The external local sampler pauses 300 ms after each probe. Timings include instrumentation.
The transfer sink counts/hashes bytes instead of writing to SAF/cloud, so its timing is not SAF
throughput. These are debug observations, not optimized-release benchmarks.

## Actual archive workloads

Each workload below ran three times sequentially in a fresh instrumented process without external
meminfo. Inspection is an additional read-only probe, not part of ordinary export UI. Every ZIP
passed production inspection, matched its own transferred SHA-256/byte count and released staging.
No archive was imported. Timestamps/preference projections can change archive bytes between trials.

| Workload | Icons | data.json | ZIP | Median prepare | Median inspect | Median transfer |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Share largest profile, small profile active | 224 | 27.475 MiB | 20.589 MiB | 4,675.9 ms | 862.0 ms | 48.9 ms |
| Full backup, small profile active | 939 | 47.848 MiB | 33.628 MiB | 6,110.1 ms | 1,688.2 ms | 77.4 ms |
| Full backup, largest profile active | 939 | 47.849 MiB | 33.628 MiB | 6,101.4 ms | 1,688.5 ms | 75.3 ms |

| Same runs: maximum sampled process Java used heap | Prepare | Inspect | Median allocated during inspect |
| --- | ---: | ---: | ---: |
| Share largest profile | 122.7 MiB | 137.2 MiB | 113.0 MiB |
| Full backup, small profile active | 138.9 MiB | 152.9 MiB | 202.1 MiB |
| Full backup, largest profile active | 164.9 MiB | 175.5 MiB | 198.5 MiB |

These are process maxima, not exclusive backend allocations or retained sizes. Success on this
dataset does not show that a transport-limit 512 MiB JSON fits a 256 MiB heap or establish a maximum
supported profile size. No heap-fraction compatibility cutoff is proposed.

The separate large-active-profile `--local` run observed prepare/inspect maxima of 164.6/178.0 MiB
Java used heap. Its entire startup/profile-switch/export journey reached 660.2 MiB PSS; that sample
contained 246.3 MiB APK mmap PSS and 174.4 MiB DEX mmap PSS. After the final ready marker, maximum
PSS was 410.6 MiB, with 3.2 MiB APK and 174.4 MiB DEX at that sample. Mapping values here are not
independent maxima. The idle local control itself reached 594.7 MiB PSS during startup/background
work, ending at 272.7 MiB. Different profiles/histories prevent subtracting these absolute peaks
to isolate backup cost. Exact mapped files were not traced.

Three initial uninstrumented cold observations under ordinary meminfo ended at a median 157.9 MiB
PSS, 27.5 MiB native allocation, 9.9 MiB Java allocation and 1.17 MiB malloced bitmaps. They describe
the GC-perturbed setup, not unsampled residency, and cannot be directly compared with instrumented
PSS. Native allocation, PSS, Java used heap, mapped code and bitmap counters must not be added or
assigned wholesale to artwork/JSON.

## Codec ownership probe

This separate largest-profile probe uses raw persisted rows, without sharing's watch/pack
normalization. It deliberately retains the original DTO while decoding another copy, rather than
claiming this overlap occurs in normal preparation. A temporary JSON file avoids a whole String.
No external meminfo ran during the following observations.

| Phase | Time | Process allocation delta | Sampled Java used heap peak |
| --- | ---: | ---: | ---: |
| Read Room rows/model | 1,753.9 ms | 35.2 MiB | 78.4 MiB |
| Create/inspect export JSON tree | 10.4 ms | 0.344 MiB | 78.7 MiB |
| Write JSON file | 1,091.6 ms | 1.114 MiB | 79.8 MiB |
| Decode with original model retained | 812.6 ms | 112.9 MiB | 153.2 MiB |

All 224 rendered and base fields in the encoded tree referenced their original String objects.
All 224 decoded rows preserved exact rendered/base text; five shared equal decoded base/rendered
references. These process deltas include probe/background overhead. They support avoiding an
all-payload inspection model, but do not prove containers account for all parser allocation or
predict the savings of a specific replacement.

## Proposed next checkpoints

**PERF6b: streaming inspection/summary.** Keep ZIP staging, central-directory/CRC checks and
PreparedImport ownership. Validate formats 1–4, identities, style/prefs ownership, known fields and
watch normalization while consuming artwork without retaining all payload strings or a whole-input
tree. Retain the summary, identity metadata and verified file handles. Preserve duplicate-field,
depth, size, path, cancellation and optional-input checks. Accept every currently supported field
order; spool locally where later fields are needed. Unknown fields still need structural validation.
Keep actual import's current commit path. First compare acceptance/rejection and summaries with the
current decoder across legacy, malformed, optional-input and large-artwork fixtures.

**PERF6c: row-wise export, format 4 unchanged.** Under the same profile-session/storage/manager locks,
capture profile/style/watch/preset metadata and visit artwork rows in bounded batches within a
consistent Room read. Preserve oversized-row handling and ICON_ROW_COLUMNS. Write identical JSON
fields to bounded staging, preserving sharing exclusions, modifier files, active compatibility
projection and normalization. Validate/finish the archive before opening a destination; transfer
stays outside the locks. Batches must not mix storage epochs. Cover concurrent writes, cancellation,
failed validation and cleanup. Removing only the tree while retaining all rows is a small step.

**Later: bounded import commit/file artwork.** Whole-import ownership and the largest single
supported field remain separate problems. Streaming commit needs the durable cross-store rollback
and recovery boundary; an archive-format change needs a separate compatibility decision. Neither
belongs silently in inspection/export. Room remains 21; prefsSnapshot retirement still requires
its explicit Room 21→22 migration gate. CI/release work is not started here.

## Verification and protection

1. Build: assembleDebug and assembleDebugAndroidTest passed. The preceding test-only fix had already
   passed 1,382 unit tests in 189 suites with zero failures/errors/skips, plus assembleDebug. That
   suite is not claimed as a new run for this androidTest-only measurement addition.
2. Backend: profile export, full export and full export with largest profile active each completed
   three repetitions under ordinary meminfo and three without external sampling. The local full
   run added three. All passed inspection, transfer equality and export staging cleanup. No live
   import, pack build/install, Apply or Discard ran.
3. Ownership: the codec probe passed with and without ordinary meminfo, including exact artwork
   text; three idle controls passed. These are existing-data measurements, not capacity tests.
4. Restore: the original debug APK and all 46 persistent regular files in databases/files/
   shared_prefs/no_backup were restored with matching hashes, modes, uid/gid and mtimes. Room stayed
   21, with six profiles, 939 icons and quick_check `ok`. The restored home opened without a crash;
   data was restored again to erase launch side effects. The helper test APK was removed.
5. Cleanup: temporary private backup/APK/database/UI copies were removed after verification; only
   aggregate logs/scripts remain in session scratchpad `perf6a/`. Debug ends stopped with its
   original active selection. Release Renkin, installed packs, stashes and foreign worktrees were
   untouched. research_notes/, reports/ and patches/ were not edited or committed.

This measurement checkpoint accompanies the accepted PERF6b implementation. No production memory improvement, release
benchmark, SAF/cloud throughput, restore-commit behavior or maximum-capacity guarantee is claimed.
PERF6b added compatibility fixtures and inspection projection, then repeated device measurements
without GC-requesting probes.

The subsequent implementation and fresh paired comparisons are recorded in
[PERF6b](performance-backup-inspection.md). The numbers above remain the historical PERF6a baseline.
