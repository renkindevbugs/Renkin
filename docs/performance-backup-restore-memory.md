# PERF6e: restore preparation memory and streaming-import design

Date: 2026-10-16. Branch: `feature/backup-restore-memory`, based on `dev` `4dfa758b`.
Accepted analysis/measurement checkpoint; production import is unchanged.

## Verified ownership and durable boundary

Codebase-memory traced importPrepared → readContents → applyArchive/restoreBackup and the recovery
owners. Actual source confirms that prepared inspection does not own artwork, but importPrepared
rereads complete staged data into a JSON tree and BackupData. The full DTO remains reachable while
captureCurrentState writes the old pack/watch databases, preferences, gallery, referenced modifier
inputs and signing files. Database recovery writes are row-wise; incoming artwork stays resident.
File preparation moves verified staged files into a journal-owned batch on the same filesystem.

commitWithRecovery calls editorDraftInvalidation.beforeStorageReplacement before invoking the
provider/session commit callback. Only inside that callback are pending recovery activated, the
storage epoch replaced, original files applied and stores replaced. A callback that simply aborts
against the real manager would already invalidate the real draft/journal, so it is not a safe
read-only measurement seam. Actual full restore then flattens the icon lists for one repository
replacement transaction, restores watch/presets/preferences, resets local verdicts, bootstraps typed
styles, flushes WAL/directories and publishes Previous state. Shared-profile import regenerates ids,
names and flags; replay/recovery/installer ownership remains outside this proposed change.

## Safe measurement design

The debug probe clones both Room databases through their existing exact recovery format into
disk-backed databases under one private cache directory. It copies gallery/signing/modifier
files into that scope and uses separate editor-draft/journal stores. Context paths and opened DB
paths must resolve inside that owned scope. The process DataStore delegate remains shared; its
identity is asserted and only read before the callback, which never invokes the provided commit.
This is not a fully isolated, executed cross-store restore.

Create a private full archive from the clones, then measure prepared inspection and full decoding +
recovery capture + file preparation up to the callback boundary. Abort with a diagnostic sentinel,
verify staged/snapshot/batch cleanup, unchanged clone data, unchanged real persisted files/draft,
unchanged storage epoch and immutable archive identity. No live restore, provider invalidation,
notification cancellation, preferences replacement or recovery activation should occur.

Use the device-test skill with a fresh backup of debug data including no_backup. Restore original
file contents/metadata exactly after measurements and remove helper/private copies. Primary heap/
allocation/timing groups omit external meminfo; separate PSS probes use --local. Cloning, export and
post-call integrity checks are outside the measured interval but affect process history. The probe
does not measure actual SQL replacement, startup recovery or crash/rollback after activation.

## Fresh device measurements

OnePlus CPH2663, Android 16/API 36, debug Java heap cap 256 MiB. Original data: Room 21, six
profiles and 939 stored icons; largest combined rendered/base row 412,756 characters. The app was
ready with 220 installed entries and either three created icons (original active profile) or 211
(largest profile active). Each group contains three sequential trials in one fresh instrumented
process. There is no before/after optimization comparison or release benchmark here.

The original-active fixture is 35,261,452 ZIP bytes / 50,171,933 data.json bytes; the largest-active
fixture is 35,261,654 / 50,173,482 bytes. The difference includes the active preference projection.
Both describe six profiles/939 icons. The immutable archive digest is checked on every trial.

Primary runs omit external meminfo. Existing internal sampling reads used Java heap/native
allocation about every 10 ms, so peaks are sampled lower bounds. ART allocation counters are
cumulative process allocation during the call, not retained memory. Timings include stage reporting,
the boundary diagnostic, exception propagation and staging cleanup.

| Active profile / measured operation | Time range; median | Java heap before (MiB) | Java heap peaks (MiB), trials 1–3 | Allocated (MiB) | GC count |
| --- | --- | --- | --- | --- | --- |
| Original / inspection | 2.083–2.274 s; 2.144 s | 19.64, 131.04, 120.53 | 47.54, 155.51, 154.01 | 204.47–204.82 | 4–6 |
| Original / full decode → boundary | 5.774–5.996 s; 5.800 s | 24.93, 20.58, 43.30 | 116.19, 106.95, 128.47 | 257.11–257.57 | 4–5 |
| Largest / inspection | 2.083–2.238 s; 2.115 s | 105.29, 115.55, 125.29 | 136.92, 137.54, 137.03 | 200.92–201.04 | 2–3 |
| Largest / full decode → boundary | 5.689–5.959 s; 5.925 s | 116.16, 127.50, 42.42 | 160.73, 159.72, 158.91 | 253.42–253.66 | 2–3 |

Full preparation's sampled native allocation peaks are 31.84–32.77 MiB (original active) and
33.89–34.76 MiB (largest active). Boundary recovery data occupies 51,230,276 / 51,231,825 disk bytes,
including 50,080,670 bytes of pack snapshot; the prepared replacement file batch is 1,125,435 bytes.
These disk sizes are not retained-heap measurements.

Observed stage transitions put full decoding/validation before capture at about 1.4–1.5 s and
the pack snapshot at about 4.0 s. StateFlow observers can conflate short transitions; these are
approximate observed intervals, not individually instrumented stage timers. Source ownership shows
the incoming full DTO remains reachable across capture; no heap-dominator or native-stack attribution
was collected. Streaming import should bound that owner, but this baseline does not predict savings.

Cloning, fixture export and whole-store fingerprints happen outside measured intervals. Repeated
integrity checks allocate temporary objects and leave garbage for later trials. Inspection peaks
in later original-active trials therefore cannot be interpreted as retained inspection artwork or
compared with a clean inspection-only process. No forced-GC retained-size measurement was run.

### Separate local PSS group

Three additional largest-active trials passed with meminfo --local: preparation 5.764–5.933 s,
Java heap peaks 158.33–161.33 MiB. The host collected 174 samples, 173 with valid PSS, including 39
whose host timestamps lie in preparation intervals. Each request took 78–703 ms (mean 157 ms),
with a 300 ms pause between requests. Cross-process timing windows are approximate.

Whole-journey maximum PSS was 447.41 MiB before ready (APK mmap 1.31 MiB, DEX mmap 174.85 MiB at that
sample). Maximum after ready was 415.61 MiB; maximum during preparation was 404.83 MiB, both with
APK mmap 1.07 MiB and DEX mmap 174.85 MiB at those samples. Final sampled PSS was 288.77 MiB, after
integrity checks and cleanup rather than an equal-duration settled baseline. APK mmap independently
peaked at 138.39 MiB earlier in startup, when PSS was 439.16 MiB. Do not add independent maxima.

Local meminfo's app allocation/bitmap detail is unavailable; zero parsed allocation fields do not
mean no allocation. The internal Java/native counters above are the allocation evidence. APK/DEX
mappings, PSS, Java used heap and native allocation are different accounting domains. Exact mapped
files, bitmap ownership and native allocation stacks were not traced. No leak or PSS improvement is
claimed; this checkpoint changes no production ownership.

## Proposed implementation checkpoints after measurement

1. **Validated staging model:** preserve strict parsing for formats 1–4, arbitrary field order,
   duplicate/depth/number/path/size checks, typed style/legacy projections and watch normalization.
   Spool full icon records into owned local row files or a staging database while retaining profile/
   prefs/watch/preset/identity metadata. Icons may precede their profile id and root metadata may
   follow profiles; validation must complete before live mutation. Normalize optional modifier
   documents and verify referenced file hashes with the existing policy. No heap-fraction cutoff.
2. **Transactional row consumer:** once staging is fully validated, create the existing complete
   recovery snapshot and prepare the file batch. Replace saved icons in bounded batches inside
   the same single Room replacement transaction, preserving oversized text and ICON_ROW_COLUMNS.
   Keep built-record cleanup and every other store's existing normalization/replacement semantics.
   The pending marker and original batch must own rollback before any live mutation.
3. **Recovery proof:** retain non-cancellable durable commit, cancellation before the boundary,
   provider-session invalidation timing, draft/journal invalidation, storage epoch and startup gate.
   Test malformed final rows, reordered fields, transaction failure, file failures, rollback failure,
   restart recovery and Previous-state/redo, then compare exact generated/replayed data. A true
   isolated full device commit requires an explicit preferences/store boundary first.

These are separate changes after this measurement checkpoint. Room/portable-backup/recovery-prefs
remain 21/4/3. prefsSnapshot retirement still requires its own explicit Room 21→22 migration gate.
No bitmap flattening, icon-source resolution, installer, CI/release or dev-to-main work is included.

## Verification and data protection

1. Fresh builds: assembleDebug and assembleDebugAndroidTest passed; the final helper compiles
   without new type warnings. Production debug APK SHA-256 remained
   d996c68f19ea2b072fb93e1a8a41022c63cf36a5cc480d3abfd758b53420d699, identical to installed PERF6d.
   PERF6d's 1,433 unit tests in 194 suites passed previously; that is not a fresh suite run here.
2. Original-active, largest-active and largest-active local-PSS groups each passed all three
   trials. Expected: no commit, matching summary/archive, unchanged clone/live data and released
   staging. Observed: six profiles/939 icons each time, exact raw pack/watch snapshot hashes,
   unchanged original files/no_backup/preferences, unchanged storage epoch and READY recovery gate.
   Incoming staging, captured snapshot and prepared batch were removed; owned cache scopes closed.
3. An early helper run stopped before measurement because the path check rejected Android's
   parent-directory alias. The corrected check accepts canonical app storage while rejecting actual
   child symlinks/escapes. Original data was restored before the successful groups. An initial
   invariant-array compile error and nullable-path warnings were corrected before those groups.
4. All 47 original persistent files in databases/files/shared_prefs/no_backup were restored with
   identical contents, modes, uid/gid and mtimes after every successful group. Room remained 21,
   six profiles/939 icons, quick_check ok. Final startup reached the debug foreground without a
   current-process crash; no home icon count was asserted. Data was
   restored again to erase launch side effects. The original production-identical debug APK stays
   installed and stopped. The helper APK was uninstalled, staging is empty and probe scopes are gone.

The callback deliberately throws without invoking the supplied operation, after isolated
draft/journal invalidation. This is preparation-boundary evidence, not a real restore, rollback,
process-kill recovery, Previous-state or maximum-capacity test. Production import optimization
requires the separate validated-staging/transaction/recovery checkpoints above.

Private host backup/manifest/APK/database copies were removed after final proof. Aggregate logs,
memory samples and scripts remain in the session scratchpad perf6e/. Release Renkin, installed
packs, old stashes and foreign worktrees were untouched. research_notes/, reports/ and patches/
were not edited or committed. PERF6d was approved and committed as a818fd2f, merged to dev through
4dfa758b. The developer approved committing and merging PERF6e after reviewing the device results.
No push, release or dev-to-main merge is included.
