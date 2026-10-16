# PERF6d: export warning preflight without artwork

Date: 2026-10-16. Branch: `feature/backup-warning-metadata`, based on `dev` `81763586`.
Implemented and verified checkpoint accepted by the developer for commit/merge on 2026-10-16.

The real PERF6c UI checks exposed a remaining ownership boundary before a destination is selected:
Settings and profile sharing call Icon Pack Studio warning checks. Codebase-memory traced
MainViewModel.backupUsesIconPackStudio to BackupManager.otherProfilesUseIconPackStudio; source
verification confirmed it read every stored artwork row and then excluded the active profile.
The saved-profile counterpart read all artwork of that profile. Both need only sourcePackName.

MainViewModel's live active-profile test remains unchanged so unsaved IPS selections participate.
For persisted profiles, exportSourcePacks now accepts an optional excluded profile, selecting
distinct nonempty names under StorageOperationGate. Filtering happens in the single SQL statement,
before values reach Kotlin; a pack present in both profiles still appears for the included one.
BackupManager applies the existing Kotlin isIconPackStudioExport classifier, including its
case-insensitive substring policy. The previous artwork-list adapters are removed. Default null
filters preserve the existing full/profile export metadata projection.

This does not change paid-pack/IPS warnings, warning preferences, export contents, UI ownership,
installer behavior, actual import/recovery or Room/backup/recovery versions 21/4/3. Names/metadata
remain proportional to distinct sources; the purpose is to remove artwork ownership in these checks.
Bounded actual import is a separate, larger follow-up with a durable recovery/rollback boundary.

## Verification

- Targeted suite: 41 tests in four suites, zero failures/errors/skips; assembleDebug and
  assembleDebugAndroidTest passed. compileDebugKotlin passed in that candidate build.
- Fresh full suite: 1,433 tests in 194 suites, zero failures/errors/skips; assembleDebug passed.
- The new query-callback regression fixture uses oversized rendered/base strings, mixed case,
  substring matches, nonmatching/empty sources, unknown profiles, shared sources and simultaneous
  include/exclude filters. Nine actual DbApplication queries select sourcePackName only, with no
  drawable/chunk/SELECT-star reads. Existing profile/exclusion, archive/style/modifier and backup
  coordinator tests also passed. MainViewModel and its live/unsaved branch were not modified.

These are new PERF6d runs. PERF6c's preceding results remain historical. No lint, optimized-release
benchmark or actual restore commit is claimed.

## Device measurement

OnePlus CPH2663, Android 16/API 36, debug only, 256 MiB Java heap capacity. A fresh backup contained
47 persistent regular files, six profiles/939 icons, Room 21 and quick_check `ok`. Original active
profile 25 had three created icons in a 220-app live list. The largest stored profile had 224 rows;
full artwork text was not needed to determine these counts. Each build/group started from the
same restored original data, with no profile switch or actual export/import in these probes.

SHA-256 identities:

- Baseline: `73b07977635b787897a28a54905a2975cb8e389d9bf763b6dd94bc135c267daf`.
- Candidate: `d996c68f19ea2b072fb93e1a8a41022c63cf36a5cc480d3abfd758b53420d699`.
- Identical helper: `5af4c95209cecbc9623a53e78560c33551a8a524d631b007f8ee692df042d4de`.

Each primary group called profileUsesIconPackStudio(largest) then
otherProfilesUseIconPackStudio(active) three times, without external meminfo. Each result matched
an independent source-name projection of persisted data. All real-dataset results were false;
positive IPS/case/substring/exclusion behavior is covered by unit fixtures, not an installed IPS
pack's real warning dialog. A separate group per build repeated the calls with meminfo --local.

| Primary group, three calls | Baseline | Candidate |
| --- | ---: | ---: |
| Saved largest-profile median time | 654.67 ms | 39.13 ms |
| Other-profiles median time | 1,072.70 ms | 33.88 ms |
| Saved-profile median process allocation delta | 28.3789 MiB | 0 MiB reported |
| Other-profiles median process allocation delta | 51.4962 MiB | 0.0312 MiB |
| Saved-profile maximum sampled Java used heap | 126.51 MiB | 47.70 MiB |
| Other-profiles maximum sampled Java used heap | 142.87 MiB | 47.70 MiB |
| GC counts, saved profile | 0/0/0 | 0/0/0 |
| GC counts, other profiles | 1/1/1 | 0/0/0 |

Candidate allocation deltas per call were 3.168/0/0 MiB for the saved profile and
0.3125/0.0312/0 MiB for other profiles. A zero process-counter delta does not prove zero allocations:
query/result/collection objects still exist. These short operations can finish without moving the
counter. The measurements include startup/background and probe work; no allocation-stack trace
attributes the first-call delta to a particular owner. ART allocation is a
cumulative process delta, not retained memory. Heap/native probes ran approximately every 10 ms;
short candidate calls have few samples. Used-heap peaks include garbage awaiting GC and background
UI work, and are sampled lower bounds. No explicit GC command or ordinary meminfo ran.

This small ordered debug comparison demonstrates the observed reduction and confirms the source
ownership change; it is not a statistically established universal speedup or total-memory bound.

### Separate local PSS observations

Whole-journey maximum PSS was 471.41 → 521.56 MiB, with APK mmap PSS 31.81 → 158.47 MiB and DEX
mmap PSS 174.74 MiB at those respective samples. After ready, maximum PSS was 471.41 → 446.98 MiB;
final samples were 259.82 → 278.12 MiB. There were 25/15 valid PSS samples. Mapping values at a PSS
maximum are not independent maxima; exact mapped files and native allocation stacks were not traced.

Candidate calls finish much sooner, so final samples have different process/startup ages; no
equal-duration settled-memory comparison was run. Do not claim whole-journey or final PSS improved.
PSS, Java used heap, native allocation and APK/DEX mappings are distinct accounting domains, not
components to add or assign wholesale to bitmap/artwork ownership. These runs do not identify a leak.

## UI and data protection

1. Actual Settings → Export backup opened CreateDocument; Back cancelled it. Export reopened the
   picker normally and a second Back returned to debug settings. No stuck preflight indicator.
2. The largest inactive saved profile's Share action showed its existing general "Before you share"
   warning. Share opened CreateDocument; Back returned to debug home. The warning preference was
   not toggled. This is not a real IPS-specific warning test, since this dataset contains no IPS
   source. No external file was created and no actual import/pack build/Apply/Discard ran.
3. All 47 original files in databases/files/shared_prefs/no_backup were restored with identical
   contents, modes, uid/gid and mtimes. Room remained 21, six profiles/939 icons, quick_check `ok`.
   Candidate startup had no current-process crash; data was restored again to erase launch side
   effects. Helper APK and private local backup/APK/database/UI/manifest copies were removed;
   staging was empty. Debug remains stopped with original data and the verified candidate installed.

Aggregate logs/scripts remain in session scratchpad perf6d/. Release Renkin, installed packs,
old stashes and foreign worktrees were untouched; research_notes/, reports/ and patches/ were not
edited or committed. Automatic approval review temporarily failed with a usage limit before UI
smoke could start; the developer asked to continue, the same approval path succeeded, and all
checks/restoration then completed. No approval check was bypassed and nothing remains blocked.

The user-approved full UI/SAF checks and commit/merge of PERF6c are recorded in its own report.
The developer approved committing and merging PERF6d after reviewing these results. No push,
release or dev-to-main merge is included. Actual import memory remains the next separate checkpoint.
