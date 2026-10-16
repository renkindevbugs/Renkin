# PERF6f: validated disk staging for incoming icon rows

Date: 2026-10-16. Branch: feature/backup-import-staging, based on dev c629b4e7.
Accepted implementation/compatibility checkpoint; live import integration is deferred.

## Scope and ownership

PERF6e showed the complete incoming BackupData owner surviving across recovery capture. This first
implementation step adds StagedBackupData: validated metadata without icon rows, one private row
file per nonempty profile, row counts and verified optional modifier-file references. It is not yet
wired into BackupManager's live import commit; production import still uses the complete DTO.
No production memory reduction or complete restore behavior is claimed by this checkpoint.

Codebase-memory traced readBackupData/decodeJson and prepareModifierInputs. The index missed the
newer inspection projection; actual BackupStreamJson/BackupInspectionProjection/BackupValidator/
BackupCodec source was authoritative. The existing strict reader now accepts an internal row sink.
With no sink, full import and inspection keep their existing behavior. Staging consumes complete
known icon fields one row at a time; unknown fields still receive structural validation and are
discarded. Duplicate fields (including escaped duplicates), depth 64, finite numbers, cancellation,
required fields and end-of-document checks remain shared.

BackupCodec.decodeIcon is extracted unchanged from decodeJson and reused for rows. Required JSON
coercions, legacy classification/base defaults, rendered/base strings, adaptive/XML flags,
calendar/source/fallback fields and optional recipe/input normalization remain the same. Staging
does not resolve source packs, decode bitmaps, flatten adaptive layers or render artwork.

The sink uses profile array positions while parsing, so icons can precede id, profiles can precede
preferences and profile ids need not be contiguous. Identity-only icon stubs enter the existing
whole-document validator. All profile/style/active-projection/pref/preset checks and watch
normalization finish before a handle is published; then those stubs are dropped from metadata.
Rows get their final owning profile id when visited. Metadata and uniqueness sets still grow with
the number of entities; the largest individual field/row must still fit the heap.

BackupModifierInputPreparation is extracted unchanged from BackupManager and shared with staging.
Referenced optional files are hash/size-checked once per name, unavailable/corrupt inputs are
dropped without losing artwork, and profile sharing excludes input references. The archive owner
still validates ZIP central-directory/CRC/transport/path limits, known file entries and kind-specific
file rules, and retains ownership of those files. A validated data.json handle alone does not
certify the surrounding archive. Staging copies row data, not those referenced source files.

## Lifetime, failures and disk cost

Scopes use BackupStaging under the caller's private root with backup-import-rows-* names. Only one
profile writer is open at once. Row arrays stream through JsonWriter with bounded buffers; each
profile is flushed/fsynced before its file is exposed. Existing 32 MiB space reserve checks run at
creation and at roughly 1 MiB intervals while writing. There is no new heap-fraction or row-count
compatibility cutoff. The archive's current 512 MiB data.json transport limit still applies.
Canonical staging can add defaults/escaping, so its disk bytes need not equal incoming JSON bytes.

Cancellation, malformed final rows, late metadata validation and write/space failures close the
writer and delete the entire unpublished scope. Close releases a published scope idempotently;
startup collection removes abandoned scopes while retaining active ones. Source data and optional
files remain owned by the caller. Row replay checks syntax, component ownership and count and
propagates consumer errors. Rows are ephemeral app-private inputs, not durable recovery snapshots.

## Verification

Fresh compileDebugKotlin, assembleDebug and assembleDebugAndroidTest passed. The first targeted
run required the usual Robolectric ShadowStatFs setup; after adding it, 54 tests in five suites
passed. Subsequent coverage adds watch normalization, optional recipe canonicalization, invalid
preset metadata and low-space cleanup. The fresh full suite passed 1,442 tests in 195 suites with
zero failures/errors/skips (build/verify-perf6f-full.log, 5m 35s; aggregate summary alongside it).

The staging tests compare acceptance and every decoded portable row against the existing decoder
across formats 1–4, reversed field order, multiple/noncontiguous/empty profiles, missing required
fields, legacy defaults, JSON coercions and oversized artwork. They cover duplicate identities,
late malformed metadata, unknown-field structural errors, optional-file verification/sharing,
cancellation, consumer failure, expired handles, truncated row files and startup cleanup.

## Fresh device comparison

OnePlus CPH2663, Android 16/API 36, debug heap cap 256 MiB. The largest active profile had 211
created icons in 220 installed entries. Fresh external backup: 47 persistent files, Room 21, six
profiles/939 stored icons, largest combined artwork row 412,756 characters, quick_check ok.

The helper exported a private full archive, then staged its ZIP with the existing archive owner.
Export may update the active prefsSnapshot; unchanged-store assertions begin after fixture creation.
The fixture contained 35,261,654 ZIP bytes / 50,173,482 data.json bytes. Each three-trial group runs
in one fresh process without external meminfo or forced GC, using the existing ~10 ms internal
heap/native sampler. These are debug diagnostics and sampled peaks, not release performance claims.

The first group interleaves full decoding/validation/optional-file preparation with disk staging
and row replay. It compares exact metadata, row counts and hashes of every portable field per
profile. All three trials matched six profiles/939 icons. Published metadata had no icon rows;
the six row files occupied 50,137,425 bytes. Staging cleanup, immutable input hashes, unchanged raw
pack/watch snapshots, original files/no_backup/preferences, storage epoch and READY gate all passed.

| Interleaved operation | Time, trials 1–3 (ms) | Java heap before (MiB) | Sampled Java heap peaks (MiB) | Allocated (MiB) |
| --- | --- | --- | --- | --- |
| Full decoder | 1609.40, 1484.29, 1467.96 | 86.85, 102.76, 111.06 | 138.69, 144.74, 153.63 | 198.03–198.13 |
| Staging preparation | 3705.90, 3583.53, 3623.71 | 142.22, 140.29, 157.16 | 176.52, 177.26, 177.19 | 203.96–203.97 |
| Replay plus field hashing | 3367.89, 3307.93, 3301.50 | 81.46, 88.68, 101.50 | 178.39, 177.82, 177.22 | 200.33–200.36 |

The full baseline function returns only hashes/metadata before staging, but its temporary DTO and
the out-of-interval hash verification leave garbage for later operations. The higher staging peaks
therefore do not measure the staged owner's retained size. Allocated bytes are cumulative process
allocation during a call. Replay includes JSON re-encoding for hash verification and is not SQL
commit timing. Disk preparation adds CPU/I/O; no latency improvement is claimed.

A second fresh-process group omits the full decoder. Expected metadata comes from the existing
inspection projection and expected field hashes from the repository's transactional row-wise
export reader, after fixture creation. This setup is outside the measured calls. All three trials
again matched six profiles/939 rows and passed the same unchanged-store/input/cleanup checks.

| Row-wise reference group | Time, trials 1–3 (ms) | Java heap before (MiB) | Sampled Java heap peaks (MiB) | Allocated (MiB) |
| --- | --- | --- | --- | --- |
| Staging preparation | 3706.63, 3665.35, 3630.34 | 109.02, 92.15, 72.71 | 137.37, 136.54, 137.17 | 203.78–203.86 |
| Replay plus field hashing | 3386.36, 3359.05, 3297.85 | 123.40, 104.89, 83.65 | 136.60, 136.99, 136.91 | 200.29–200.33 |

Preparation's sampled native peaks were 35.82–36.39 MiB; each measured call had two GCs. The
different peak ranges demonstrate sensitivity to setup/history, not a paired production-memory
improvement. No forced-GC/dominator retained-size measurement or new PSS/APK/DEX attribution was
collected here. PERF6e's mapping observations remain historical; do not assign process memory to
artwork or infer a leak from these used-heap values. Integration must measure complete preparation
and transaction/recovery overlap again.

## Next transaction checkpoint

Wire a fully validated staged model into the existing recovery-protected commit only after this
checkpoint. Add a single Room replacement transaction consuming bounded batches, preserve built
record cleanup and oversized-text handling, and retain all watch/preset/prefs/file semantics.
The complete original recovery snapshot and journal-owned file batch must precede live mutation.
Keep provider/draft/journal invalidation timing, cancellation boundary, storage epoch, startup
recovery, Previous-state/redo and rollback behavior. Actual commit, rollback failures and process
recovery require separate integration tests and device verification.

Room/portable-backup/recovery-prefs remain 21/4/3. prefsSnapshot retirement still requires the
explicit Room 21→22 gate. Installer/editor/generation/source ownership and CI/release are unchanged.

## Final data protection and approval state

Both device groups ended with exact restoration of all 47 original persistent files in databases,
files, shared_prefs and no_backup, including hashes, modes, uid/gid and mtimes. Room stayed 21,
six profiles/939 icons, quick_check ok. Final candidate startup reached the debug foreground with
no current-process crash; no home icon count was asserted. Data was restored again after startup.
The helper APK was uninstalled, import/export staging is empty and owned probe cache scopes are gone.
The tested candidate remains installed and stopped, with original data; debug APK SHA-256:
634d61d4178d76b5ac79943803acfd2f71909e2954134fb75c3c0264b6ee1d5c.

Private local backup/manifest/APK/database copies were removed after proof. Aggregate evidence and
scripts remain in session scratchpad perf6f/. No live restore commit, pack build/install, Apply,
Discard, rollback or process-kill recovery ran. Release Renkin, installed packs, old stashes and
foreign worktrees were untouched; research_notes/, reports/ and patches/ were not edited/committed.

The approved PERF6e checkpoint was committed as 7788477e and merged to dev through c629b4e7.
The developer approved committing and merging PERF6f after reviewing its verification. Further
performance measurements and live import integration are deferred in favor of higher-priority
correctness and CI/release work. No push, release or dev-to-main merge is included.
