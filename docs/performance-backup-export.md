# PERF6c: row-wise portable export

Date: 2026-10-16. Branch: `feature/backup-streaming-export`, based on `dev` `9e3d7e2b`.
Implemented and verified checkpoint. The developer delegated the remaining UI checks and approved
commit/merge after their successful completion on 2026-10-16.

## Source trace and implementation boundary

Codebase-memory traced prepareBackupExport/prepareProfileExport to repository reads, validation,
the codec and staging. Source inspection confirmed that getAll/getAllProfilesApplications reconstructed
every artwork string before export started. The JSON tree shared those strings; removing containers
alone would leave the main ownership unchanged. PERF6a and PERF6b measurements are historical
baselines, not fresh measurements of this branch.

Export now keeps profile/preferences/watch/preset metadata and component keys, loading and serializing
one full icon at a time. readExportIcons captures keys and oversized lengths, and uses ICON_ROW_COLUMNS
and existing chunk readers within a single Room transaction. Keys explicitly follow rowid order,
matching the previous table-scan row order. The scoped reader expires on success/error/cancellation;
it never returns an all-artwork list. Shared exports also capture keys/lengths for other profiles,
but reconstruct artwork only for the requested profile. Metadata ownership still scales with rows.
The outer storage gate continues to freeze legitimate writers across Room, DataStore, watch storage,
files and signing. Metadata is collected under that gate; the Room transaction covers icon emission
and ends before ZIP compression. Writers bypassing the gate are outside the supported storage API.

BackupCodec.encodeIcon is shared by the existing model codec and row writer; metadata uses encodeJson
with empty icon arrays. writeBackupExport substitutes those arrays with the scoped visitor. Existing
validation checks profile/style/preferences/watch/preset metadata before staging; shared validateIcon
checks component identity/ownership during emission, with a per-profile uniqueness set. Row failures
delete staging before an external destination can be opened. The export metadata model rejects
artwork rows explicitly. The shared full-data writer/decoder remain available for their existing uses.

Full backups retain only verified referenced modifier-input files; sharing removes hidden modifier
input and keeps the existing appfilter fallback policy. Both paths retain exact rendered/base text,
calendar/adaptive/custom/fallback/source metadata and optional-document normalization. ZIP limits,
space checks, cancellation, immutable PreparedExport and transfer outside locks remain unchanged.

No schema, portable format, actual import, recovery, generation or installer behavior changes.
This removes all-row artwork ownership, not the largest single-field allocation or all metadata.
The existing full decoder remains necessary for actual restore.

## Verification

The merged PERF6b tree, including newer accepted unsaved-journal changes on dev, passed a fresh
1,424-test/193-suite run and assembleDebug before PERF6c code changes. PERF6c's targeted run passed
203 tests in 17 suites with zero failures/errors/skips; assembleDebug/assembleDebugAndroidTest passed.
Two initial fixture errors (comparing different JSON escaping and an empty legacy style document)
were corrected before that final targeted run. Production behavior did not change for those fixes.

New tests compare the complete streamed output with the existing streaming model writer and decode
exact escaped artwork for formats 1–4, multiple/empty profiles and all icon fields. They reject
invalid/duplicate/wrong-owner rows and all-artwork metadata, and cover cancellation. Repository tests
compare oversized rows exactly, suspend a reader while another writer waits, then verify one stored
state; cancellation expires the reader and releases the gate/transaction. A manager test rejects a
late invalid row, keeps the destination unopened, cleans staging and succeeds on a subsequent export.
Existing archive/style/watch/modifier/recovery tests provide the integration coverage.

The final full run passed 1,432 tests in 194 suites with zero failures/errors/skips; assembleDebug
passed. compileDebugKotlin and assembleDebugAndroidTest passed in the targeted candidate build.
Paired device measurements completed below. No optimized-release or lint result is claimed.

## Device comparison

OnePlus CPH2663, Android 16/API 36, debug only, Java heap capacity 256 MiB. A fresh backup contained
47 persistent regular files, six profiles and 939 icons; Room 21 and quick_check `ok`. The largest
profile had 224 stored rows/28,697,500 UTF-8 artwork bytes; its live list had 220 apps/211 created
icons. Original active profile 25 had three icons. Every experiment began from the same restored
data; large-active runs switched through MainViewModel without Save/Apply.

APK SHA-256 identities:

- Baseline, including the accepted current dev journal changes:
  `9343712d1e5f6884448de58a2d61d9850de4644f65b600ed898abb515ec7fa26`.
- Candidate: `73b07977635b787897a28a54905a2975cb8e389d9bf763b6dd94bc135c267daf`.
- Original installed debug APK before these experiments:
  `6cf94dec504cd160b4a31a283709f70536346744f287e69c5c42f2b8bf77376a`.

Both builds used the same helper. Primary groups ran without external meminfo; Java used heap/native
allocation were sampled approximately every 10 ms. ART allocation is a cumulative process delta,
including probes/background work, not retained size. Maxima are sampled lower bounds and include
dead objects awaiting GC. The baseline ran first; groups were not randomized. Three calls on one
debug phone do not establish a statistically significant speedup or universal memory bound.
Separate PSS observations used meminfo --local, avoiding ordinary meminfo's GC-requesting app dump.

### Preparation: three calls per group without external sampling

| Workload | Baseline maximum Java used heap | Candidate maximum Java used heap | Median prepare time, baseline → candidate |
| --- | ---: | ---: | ---: |
| Share largest profile, small profile active | 106.1 MiB | 85.9 MiB | 5,141.7 → 4,880.0 ms |
| Full backup, small profile active | 126.4 MiB | 106.1 MiB | 6,753.7 → 5,444.4 ms |
| Full backup, largest profile active | 135.2 MiB | 148.3 MiB | 6,761.1 → 5,506.0 ms |

| Same groups: preparation | Median allocated, baseline → candidate | GC counts, baseline → candidate |
| --- | ---: | --- |
| Share largest profile | 41.4 → 47.0 MiB | 1/1/1 → 0/1/1 |
| Full backup, small active profile | 56.7 → 60.2 MiB | 1/1/2 → 1/1/2 |
| Full backup, largest active profile | 56.6 → 60.2 MiB | 0/0/1 → 1/1/0 |

The largest-active-profile process peak increased despite removing all-row artwork ownership.
Its first preparation began with 59.0 MiB used heap on baseline versus 119.4 MiB on candidate.
Subsequent inspection/GC histories also differ. These are process observations, not exclusive
backend allocations; no retained-heap/allocation-stack trace attributes the difference. Cumulative
allocation increased with per-row queries/bookkeeping. Do not claim every peak or total allocation
improved. The change bounds the number of source artwork rows owned by export, not total process
heap across UI/profile loading, parser work and uncollected objects.

Direct inspection maxima for the three groups were 135.3 → 125.9, 154.8 → 138.6 and
155.2 → 159.9 MiB; prepared-import maxima were 117.3 → 112.8, 127.5 → 110.0 and
140.7 → 140.4 MiB. Inspection follows export in this experiment; its implementation was unchanged
from PERF6b. These values do not isolate inspection cost or replace PERF6b's fixed-archive comparison.

### Archive equality and oversized values

Each build produced nine primary and three local-PSS archives. All 24 passed production inspection,
matching prepared inspection, byte/hash-exact immutable transfer and export staging cleanup. For
every paired trial, data.json SHA-256 was identical across builds, covering all exported artwork,
metadata and field ordering. The shared profile had two ZIP entries; full backups had 15. ZIP
manifest timestamps may differ, so equality is checked on data.json across builds and separately
on each archive's own transferred bytes. No import into live storage was committed.

A separate synthetic in-memory profile export passed on both builds on the physical device:
1,540,000 rendered characters plus 1,080,000 base characters, with escaped text and adaptive/source/
fallback flags. Full decoding exactly matched the original row and export staging was removed.
This exercises real CursorWindow/chunk handling beyond inline limits without adding a synthetic
profile to the developer's database. It is not a maximum-capacity or heap-exhaustion test.

### Separate local PSS observations

Three full backups per build used the largest active profile and meminfo --local. Whole-journey
maximum PSS, including startup/profile switch, was 480.6 → 593.8 MiB. APK mmap PSS at those samples
was 54.8 → 132.6 MiB and DEX mmap PSS was 174.7 MiB for both. After the final ready marker, maximum
PSS was 389.5 → 397.2 MiB; final samples were 286.3 → 286.5 MiB. Mapping values at a PSS maximum
are not independent mapping maxima. Exact mapped files/native stacks were not traced.

The candidate has a higher whole-journey PSS peak and similar final PSS in this group. PSS, Java
used heap, native allocation, mapped APK/DEX and bitmap counters are distinct accounting domains;
they must not be added or assigned wholesale to artwork. These runs do not identify a leak or
demonstrate a universal decrease in total process memory.

## Data protection

All 47 persistent regular files in databases/files/shared_prefs/no_backup were restored with matching
hashes, modes, uid/gid and mtimes. Room stayed 21, with six profiles/939 icons and quick_check `ok`.
The candidate opened without a current-process crash; data was restored again to erase launch side
effects. The helper APK was uninstalled, export staging was empty, and local private backup/APK/
database/manifest copies were removed after verification. Aggregate logs/scripts remain in session
scratchpad perf6c/. The candidate remains installed, stopped, with original persistent data and
selection for developer UI checks. No release app, installed pack, old stash or foreign worktree
was operated; no real-device restore commit, Apply/Discard or pack build ran. research_notes/,
reports/ and patches/ were not edited or committed.

## Delegated UI checkpoint

These are new physical UI checks of the same candidate APK, separate from the preceding backend
measurements. The user explicitly authorized the system document picker as part of these checks.
Only uniquely named test files were selected/created; no unrelated file or cloud account was opened.

1. **Full export → preview → Dismiss:** actual Settings buttons and CreateDocument/OpenDocument
   produced/read a CRC-valid 35,261,452-byte backup. Preview matched its six profiles, 939 icons,
   11 watch rules (one active/ten completed), eight styles (two colour/six modifier presets), six
   images and the four visible per-profile icon counts. Dismiss released staged import files.
   Consistent stopped-process DB captures before/after showed identical complete Profile (6),
   DbApplication (939), BuiltIcon (1,213), ColorPreset (2) and ModifierPreset (6) rows, including
   all artwork and metadata. Restore was never confirmed.
2. **Share largest saved profile:** actual Profiles → Share profile → warning → SAF Save completed.
   The 21,588,953-byte archive had only manifest/data entries, one profile/224 icons and empty
   device preferences/preset libraries. Every rendered/base string and adaptive/XML flag matched
   stored data exactly; hidden modifier input was absent. The next operation could open normally.
3. **Cancel destination → retry:** Back cancelled the actual CreateDocument picker. Export backup
   reopened the picker after its asynchronous preflight and completed a new CRC-valid
   35,261,452-byte backup with six profiles/939 icons. Staging was empty, the retry kept the same
   process and no current-process crash was recorded. Picker discovery waits for actual controls;
   early harness assertions were adjusted for asynchronous search/preflight and reused resource IDs.

A fresh backup preceded these UI operations. All 47 original persistent files were restored with
matching contents and metadata; the candidate opened without a crash, then data was restored again.
The three external test archives and local private copies were removed after verification. Debug
ends stopped with original data; no helper remains installed. Aggregate evidence lives in perf6c-ui/.

The developer's instruction to perform these checks and then commit/merge is the approval for
PERF6c. No push, release or dev-to-main merge is included. UI preflight for Icon Pack Studio warnings
still reads full stored artwork via profileUsesIconPackStudio/otherProfilesUseIconPackStudio;
removing that independent ownership boundary is the next small checkpoint. Bounded actual import
and its recovery-protected commit remain separate work.
