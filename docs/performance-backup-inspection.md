# PERF6b: inspection without retaining artwork payloads

Date: 2026-10-16. Branch: `feature/backup-streaming-inspection`, based on `dev` `585c9f81`.
Implementation and preceding PERF6a measurement files accepted by the developer on 2026-10-16.

Backup inspection now consumes rendered/base artwork one field at a time and retains metadata
instead of every icon's payload. Actual import still decodes the complete, verified staged file.
The fixed-archive device comparison observed Java used-heap maxima of 172.9 → 139.7 MiB across
three prepareImport calls per build. This changes ownership, not the archive format or the parser's
large-string allocation behavior; it is not a complete solution for large imports.

## Boundary and compatibility

`readBackupData` keeps the full-data mode of the shared strict reader. The new
`readBackupInspectionData` selects a schema projection for root/profile/icon records:

- `drawable` is consumed and receives the existing required-field/string-coercion check, then its
  reference is replaced with an empty inspection marker. `baseDrawable` is consumed and dropped
  with its existing optional-field policy. Missing required fields still fail in BackupCodec.
- Other consumed fields remain available to the same BackupCodec and BackupValidator. Optional
  modifier documents normalize at each icon boundary, preserving canonical settings and valid
  external-artwork references without retaining rejected optional documents across all rows.
- Unknown root/profile/icon fields are consumed without building retained value containers.
  Duplicate names, numbers, nesting and cancellation are still checked recursively, including
  discarded values and object-shaped artwork. This does not use an unchecked skipValue shortcut.
- Arbitrary supported field order remains valid: metadata can wait for later profile identities
  or preferences without buffering all artwork or introducing an ordering restriction.

The projection returns the existing DTO shape with empty artwork markers. `inspectFile` and
`prepareImport` use it, then perform the existing style/prefs/identity/watch/file validation and
preview construction. ArchiveContents records inspection-only ownership; applyArchive rejects
that mode. `importPrepared` rereads full content from its verified staged bytes, and direct import
also keeps full decoding. No inspection marker is passed to the current import/export paths.
PreparedImport continues to own only staging and presentation, with existing cancellation/close
cleanup, source-disappearance behavior, fingerprint checks and storage/recovery gates.

Projection field sets must follow future additions to BackupCodec's consumed metadata and its parity
fixtures. Nested preference values whose key happens to be `drawable` are not icon artwork and are
preserved. The policy retains metadata for all icons; it is not constant-memory processing of every
possible field. JsonReader can still allocate one large string or value before it is discarded.
There is no new heap-fraction cutoff, payload validation rule or supported-format restriction.

Room/portable-backup/recovery-prefs versions remain 21/4/3. Export, actual restore commit, durable
recovery, icon generation/editing and installer policy are unchanged. PERF6c row-wise export and
bounded full import remain separate work; prefsSnapshot retirement is not included.

## Verification

Codebase-memory traced the existing archive/reader/validation owners; the actual source determined
the projection contract. Compose/installer ownership was not refactored.

- Final targeted run: all 120 selected backup/style/modifier tests passed.
- Fresh full suite: 1,392 tests, 190 suites, zero failures/errors/skips; assembleDebug passed.
- compileDebugKotlin and assembleDebugAndroidTest passed as part of candidate/helper builds.
- Nine new reader tests cover formats 1–4, all consumed metadata, watch normalization, reversed
  field order, 320 required/default/coercion combinations, invalid identities/style/prefs,
  duplicate/deep/nonfinite/trailing JSON including ignored fields/artwork objects, optional
  modifier documents, zero model-owned artwork and cancellation with Reader closure.
- A new BackupManager integration test prepares inspection, deletes the original source, commits
  the staged import and compares complete rendered/base strings and row metadata. Existing
  modifier-input, archive-hardening and recovery tests also passed. An initial test-fixture format
  error was corrected before the final runs; production code was unchanged after the passing suite.

Lint and optimized-release benchmarks were not run. Synthetic/in-memory import coverage does not
claim that a full restore was performed on the developer's real device data.

## Device comparison

OnePlus CPH2663, Android 16/API 36, Java heap capacity 256 MiB. Fresh original data: six profiles,
939 stored icons, 47 persistent regular files, largest profile 224 rows/28,697,500 UTF-8 artwork
bytes. Its live list had 220 apps and 211 created icons. Original active profile 25 had three icons.
All baseline/candidate groups began from the same restored data; larger-profile groups switched
through MainViewModel without Save/Apply. There was no actual import into live device storage.

APK SHA-256 identities:

- Baseline used in these new runs: `919c0e3881291a0b01f9e7e22cedf0ac305705124f0676b278dd4eb40768b1f1`.
- Candidate: `6cf94dec504cd160b4a31a283709f70536346744f287e69c5c42f2b8bf77376a`.
- Developer's original installed APK at backup time:
  `83c22c3e6d9c50856d2f4ae1eea9c56b025c9d59ab5d31747d3a9496cf6e3f2f`.

Both comparison builds used the same measurement helper within each experiment. Runtime heap/native
sampling was approximately every 10 ms, with no external meminfo for the primary runs. ART allocated
bytes are cumulative process deltas, including probes/background work, not retained size. Maxima
include dead objects awaiting GC and are lower bounds. See PERF6a for ordinary meminfo's GC effect.

### Isolated prepareImport: identical ZIP

One private 35,261,452-byte ZIP was generated once, then reused byte-for-byte across builds. Its
SHA-256 matched every prepared import. Each build ran three calls in a fresh process with the
largest profile active, without generating an export first in that measured process. All summaries
reported six profiles/939 icons and every staged import was discarded and cleaned.

| Metric | Baseline | Candidate |
| --- | ---: | ---: |
| Three sampled Java used-heap maxima | 152.2 / 172.9 / 166.8 MiB | 135.4 / 139.7 / 138.8 MiB |
| Maximum across the three calls | 172.9 MiB | 139.7 MiB |
| Median call duration | 1,686.0 ms | 1,617.8 ms |
| Median process allocation delta | 202.1 MiB | 204.4 MiB |
| GC counts per call | 2 / 2 / 3 | 2 / 3 / 2 |

The observed peak reduction is about 33.2 MiB for this group. Three calls on one debug device do
not establish a statistically significant speedup or a universal memory bound. Allocation did not
fall: the parser still constructs large transient values and projection adds metadata bookkeeping.

### Export → inspectFile → prepareImport → transfer

The main integration workload ran three sequential full backups per build for both active-profile
sizes. Each archive passed direct inspection, matching prepared inspection and byte/hash-exact
transfer; both staging owners were released. prepareImport follows inspectFile here to compare the
two APIs on the same archive, whereas normal UI preparation calls prepareImport directly.

| No external sampler: maximum process Java used heap | Baseline | Candidate |
| --- | ---: | ---: |
| Small active profile: direct inspectFile | 143.0 MiB | 159.3 MiB |
| Small active profile: prepareImport | 137.2 MiB | 72.8 MiB |
| Largest active profile: direct inspectFile | 175.9 MiB | 166.0 MiB |
| Largest active profile: prepareImport | 174.3 MiB | 137.3 MiB |

Largest-profile median prepareImport time was 2,137.2 → 2,136.8 ms and allocation 198.5 → 200.8 MiB.
The small-profile direct API peak increased, despite dropping payload ownership; preceding export
allocations and GC timing matter. Do not describe every heap peak or throughput metric as improved.
Export still owns all source rows and is the next independent memory boundary.

### Separate local PSS probes

Three additional full-backup repetitions per build used meminfo --local. Maximum PSS over startup,
profile switch and the workload was 462.2 → 566.5 MiB, with APK mmap PSS 126.1 → 184.1 MiB and DEX
mmap PSS about 174.4 MiB at those respective maxima. After the final ready marker, maximum sampled
PSS was 409.1 → 379.2 MiB; the last samples were 329.5 → 282.0 MiB. Mapping values at the maximum
PSS samples are not independently sampled maxima. Exact mapped files/native allocation stacks
were not traced. The higher candidate whole-journey peak is not evidence of a new bitmap/JSON leak.
PSS, native allocation, Java heap and mapped-code values must not be added or conflated.

## Data protection and developer checkpoint

Only debug Renkin and its helper were installed/operated. The original 47 persistent files in
databases/files/shared_prefs/no_backup were restored with matching hashes, modes, uid/gid and mtimes.
Room stayed 21, with six profiles, 939 icons and quick_check `ok`. The candidate opened without a
crash, then data was restored again to remove launch side effects. The temporary private cache ZIP
and helper APK were removed; local private backup/database/APK copies were removed after verification.
The final candidate remains installed, stopped, with the original active selection and persisted
draft state. Aggregate logs/scripts remain in session scratchpad `perf6b/`.

No pack build/install, Apply, Discard or real-device restore commit ran. Release Renkin, installed
packs, old stashes and foreign worktrees were untouched; research_notes/, reports/ and patches/ were
not edited or committed. No push or release is included in this checkpoint.

1. In debug Renkin, select an existing full backup. The profile/icon/style/watch/upload summary
   should match that selected backup; choosing Cancel should leave the current profile untouched.
2. Select the full backup again, rotate while its preview is open, then cancel. The preview and
   cancellation should keep their existing behavior without a stuck preparation indicator.
3. If an older full backup or known invalid fixture is available, inspect it without confirming
   Restore. Supported legacy data should show its usual preview; invalid data should fail without
   changing live storage. Shared-profile selection imports immediately, so it is not a cancel-only
   manual check.

The developer approved committing and merging this checkpoint on 2026-10-16. PERF6c follows this
accepted inspection boundary; actual import and Room migration remain separate work.
