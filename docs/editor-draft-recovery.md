# Individual editor draft recovery

The developer chose an explicit **Restore / Discard** offer for the last unfinished individual
editor after restart. Global options and other unsaved profile edits are outside this scope.
Never restore an old `ProfileSessionToken`; successful recovery obtains a new live editor owner.

## Checkpoints

- **7C1 — storage foundation (confirmed and verified).** Versioned envelope and one local
  draft store. No editor capture, recovery prompt or storage-replacement hooks are enabled yet.
- **7C2a — controls snapshot/codec (confirmed and verified).** Lossless bounded control
  values and a Main-thread capture adapter. It is not wired to autosave or recovery.
- **7C2b — artwork and complete capture.** Add draft-owned artwork/uploads/fonts, vector paths
  and seed, original input, replay bookkeeping and canonical baseline encodings. Capture a
  coherent completed frame without I/O in composition or a long-held profile gate. Include all
  source-specific state and reject partial capture before checkpointing.
- **7C3 — recovery and lifecycle.** Offer the validated draft after startup, bind it to a fresh
  editor session, clear it on Apply/explicit dismissal, and integrate import/recovery/deletion
  invalidation. Validate races and cancellation before enabling recovery offers.

Each checkpoint follows CLAUDE.md's device-confirmation/test/commit sequence. The envelope is
not evidence that arbitrary controls or artwork can already be restored. End-to-end recovery
must remain disabled until both semantic codecs and domain validation are integrated.

## Controls checkpoint (7C2a)

`EditorControlsSnapshot` represents Create selection/text/Material You controls, Upload zoom and
adaptive choice, vector control flags/viewport/attribution, all adjustment values, ordered
segment layers, both brush lists, pack restyling and calendar/online attribution. Disabled
styles, flags and slider values are retained. It carries no Drawable, Bitmap, Compose state,
session token, selected upload path or vector path object. The two UI Color values retain their
packed 64-bit representation rather than losing color-space information in an ARGB conversion;
the existing `ColorizerStyle` groups retain their ARGB integers and unnormalized flags.

`EditorControlsCodec` version 1 is a fixed-order binary document: big-endian `RKED` magic and
version integers, followed by selected tab, Create, Upload, Vector, Adjustments, pack controls,
calendar and optional online URL. Integers are signed 32/64-bit values, floats use IEEE-754
32-bit storage, and booleans must be exactly 0 or 1. Enum values use length-prefixed UTF-8
names, never ordinals. Nullable values have an explicit presence boolean; strings/lists have
32-bit counts. Empty and null strings and positive/negative zero remain distinct. No preset
or recipe decoder normalizes/clamps values. Field order or enum-name/packed-color encoding
changes require a controls version bump and an explicit adapter; unknown versions/names fail.

The codec rejects truncated/trailing data, negative/oversized counts, invalid UTF-8, non-finite
floats, invalid tab selection and negative app Material You scheme indices. It bounds the whole
document/output buffer to the store's 2 MiB controls limit, each string to 64 KiB of UTF-8, enum
names to 128 bytes, each gradient/target list to 256 values, segments to 128, each brush list to
2048 strokes and points across both brush lists to 100,000. Reads check counts against remaining
bytes before allocation. Oversized values fail instead of being truncated or defaulted.

`ui/EditorControlCapture.kt` copies values on the editor-owning Main thread, including nested
gradient/target arrays, segment styles and brush point geometry. It checks collection/text
length budgets before copying, so a rejected large live draft is not copied without bounds.
Encoding must run off Main after capture; the codec performs no I/O and retains no editor.
Capture cannot be called on a worker against live Compose state. No caller or autosave job is
installed in 7C2a, so existing editing/Apply/configuration retention remains unchanged.

The 7C2b/7C3 autosave owner must handle `IllegalArgumentException` from both Main-thread capture
and background encoding. A budget or value rejection must leave the last valid checkpoint
untouched, preserve the live editor and report/log the autosave failure without crashing Main.
Do not truncate strokes, substitute defaults or silently disable future saves; a later valid
capture can retry. Cancellation must still propagate. This boundary must cover capture itself,
not only the suspend store call.

A controls snapshot is deliberately insufficient to recover an icon. In 7C2b the owner must
capture raw/rendered artwork, selected resources, owned file data, vector entries and original
seed, completed-render identity and saved modifier-replay bookkeeping as one coherent draft.
Do not restore `vector.initialized` without its verified paths/seed.
Neither restored controls nor the active tab can substitute for the draft's actual origin.

Before binding decoded controls in 7C3, validate them against the supported UI/render domains,
including upload zoom, outline width, shadow opacity, packed Color/color-space values and
geometry. Finite numeric storage and artifact checksums do not provide semantic validation.
Reject incompatible snapshots before mutating live state, or use an explicit versioned adapter;
do not silently clamp through a recipe decoder and change the saved draft. Preserve valid
disabled values. Scheme validation must respect current semantics: pack `selectedScheme = -1`
means inherited, while app/pack indices at or above the current palette count mean Custom,
not an invalid palette lookup. Verify these meanings with the captured source during recovery.

`fontReference` is a selection identifier, never permission to open an arbitrary path. The
7C2b/7C3 resolver must use a verified draft-owned font artifact or a canonical regular file
from the allowed font catalog (currently `/system/fonts`, plus the bundled default represented
by an empty reference). Reject path escapes and unavailable fonts instead of silently selecting
a different font. Owned artifacts use the store's length/digest checks; externally resolved
fonts also need a captured content identity if recovery relies on unchanged font bytes. Perform
file checks/copying off Main; a successfully decoded controls string alone is insufficient.

7C2a device regressions were confirmed. Eleven new tests cover all-field capture/codec round
trips with distinct/non-default values, disabled groups, null/empty text, signed-zero/packed
colors, mutations of original nested lists after capture, every truncated prefix, invalid
framing/enums/booleans/UTF-8/counts/floats, cumulative brush limits and boundary sizes. All 974
tests across 152 suites and `assembleDebug` pass with the existing local test-JVM C2 workaround.
Before enabling autosave/recovery, also cover capture/encode rejection preserving the last
checkpoint, semantic rejection before binding, inherited/Custom scheme selections and font
resolution outside the catalog or with missing/changed content.

## Envelope and local files

`data/editor/EditorDraftRecord.kt` contains primitive metadata, without Drawable, Compose state
or a live session token. The owner records a profile id, component, SHA-256 profile baseline and
SHA-256 original-icon baseline. A separate persistent UUID identifies the local storage
generation; it must change before full import, Previous state, rollback and confirmed
keep-current repair can replace storage. These hooks belong to 7C3 and are not installed yet.

`EditorDraftCodec` version 1 describes the storage generation and an optional last draft. Each
draft has a UUID, timestamp, owner and named artifact descriptors. `controls` is required;
its semantic schema/version is the responsibility of 7C2. The envelope accepts only its exact
fields and types, rejects duplicates, malformed Unicode, fractional/overflowing integers,
unsupported versions and trailing content. It bounds metadata to 32 KiB of UTF-8, artifact
count to 12, controls to 2 MiB, each other artifact to 32 MiB and the total to 64 MiB. The known
fixed object paths also bound nesting. These limits reject an oversized draft rather than
silently dropping fields. Every artifact has an exact byte length and SHA-256 digest.

`EditorDraftStore` is an application singleton with its own coroutine mutex and IO dispatcher.
Files live in `noBackupFilesDir/editor-draft`, independently of portable backup format 4,
Room 21, recovery prefs format 3, uploaded-image GC and confirmed modifier-input GC. Payload
filenames are derived from validated UUIDs and artifact slot names, never caller-supplied
paths. Canonical-path checks prevent following links outside the owned root.

`checkpoint()` and `candidate()` are not pure reads: the first call creates and fsyncs an empty
catalog with a persistent generation, and candidate inspection can prune orphan files. All
public operations enter the IO dispatcher before file access, including lazy path resolution.
The future startup owner must call the suspend API without blocking Main or performing work
in composition. `@Singleton`/`@Inject` only supplies a binding; 7C1 has no consumer, so Hilt
does not instantiate the store and these operations do not run on-device in this checkpoint.

Saving writes a new set of UUID-prefixed blobs, checks cancellation between chunks and fsyncs
each file. The bounded catalog is published through `AtomicFile` only after the blobs are
complete; directory entries are synced before/after publication and the published bytes are
read back. Publication finishes in a short non-cancellable section. The previous catalog stays
in place until this commit point. A final I/O/cancellation error can occur after publication;
callers must re-read the checkpoint before retrying instead of assuming the old one still owns
the store. Failed-write blobs remain available for safe later pruning, including an ambiguous
final fsync failure that followed a successful rename.

The checkpoint compares both storage generation and current draft UUID. Saving against a
stale checkpoint fails; discarding it returns false. A successful discard also rotates the
generation, so an old writer that captured an empty store cannot resurrect its draft after
an intervening save/discard cycle. Storage invalidation atomically replaces
the catalog with a new generation and no draft, preventing already queued pre-import saves
from republishing old data. Cleanup removes only recognized, unreferenced local blob files;
it does not recursively delete directories. Orphans are pruned before/after successful saves
and after successful candidate inspection. An invalid catalog or checksum is reported and
preserved for an explicit decision, never interpreted as an empty/default editor. The explicit
invalidation API can clear corrupt metadata without decoding it.

Candidate inspection streams checksums without decoding/retaining artwork. Reading a chosen
artifact rechecks checkpoint, length and digest. A matching envelope/checksum is only an I/O
guarantee: callers must still use bounded PNG/XML/vector/control decoders before restoring.
Draft failures must be handled by the editor/recovery-offer owner; they must not crash a flow
collector or put otherwise valid profile storage into global backup recovery.

A corrupt catalog also blocks `checkpoint()` and `save()`. In 7C3 the owner must visibly report
that draft autosaving is unavailable and offer Retry or an explicit confirmed discard of the
unreadable draft. Retry must preserve existing files. Confirmed discard must serialize with
opening/autosaving editors, recheck the failure so an old error dialog cannot clear a newer
valid draft, then explicitly rotate the generation through `invalidateStorage()`. After a
successful reset, obtain a fresh checkpoint and revalidate the live editor before resuming
autosaves. A failed reset keeps the error visible; do not silently disable autosaving or
pretend an empty draft was recovered. Do not enable capture/recovery without this error path.

## Required integration contracts

Capture editor state on its owning thread and transfer immutable bytes to the store. Serialize
autosaves in the editor owner, debounce routine gestures, flush when leaving for the background,
and reject render results from older editor generations. Do not serialize while a render is
publishing mismatched controls/artwork, or replace the last valid checkpoint with a partial
capture. An explicit Apply must continue storing exactly the confirmed result.

Define canonical baseline encodings in 7C2. The original-icon fingerprint must cover the full
persisted editor input (base/rendered artwork, recipe/input descriptors, calendar and source
metadata), not merely the build-export fingerprint. Capture the original live artwork as
draft-owned payload when it differs from persisted state; saving an editor draft must not
commit unrelated live profile edits. The profile baseline must identify its persisted style.
Verify profile/component existence and both baselines under the provider gate when offering
and accepting recovery. Repeat validation after decoding and before attaching a fresh owner.

Large encoding/hashing/file writes run outside the profile gate. Import/recovery takes the
storage gate before invalidating the draft store. The store never calls back into a provider
or acquires that gate, keeping the lock direction consistent. Draft invalidation failures
must stop a storage replacement before it begins. Explicit discard and old ViewModel cleanup
must compare the offered checkpoint so they cannot delete a newer editor's saved state.

The recovery prompt appears only after storage recovery and application startup have completed.
A stale/deleted profile, replaced icon, unsupported/invalid payload or mismatched generation
cannot be applied. Recovering a draft must not silently switch profiles or resurrect old
process ownership. The precise UI for an owning profile other than the active one belongs to
7C3; it must respect the existing Save/Discard profile-switch flow.

## Verification at the storage checkpoint

7C1 adds no production caller and changes no visible behavior. The developer confirmed normal
cold start, live editor retention on configuration recreation and profile switching. Those
regression checks do not exercise the new store. Automated coverage now includes 25 codec/store
tests: round trips and strict rejection, exact artifact bytes, first-use IO, competing/stale
writes, generation invalidation, checksum/size corruption, corrupt-catalog blocking/reset,
byte/UTF-8 limits, path rejection before file creation, flat orphan cleanup and publication
cancellation/failures before and after rename. All 963 tests across 151 suites and
`assembleDebug` pass, using the existing local test-JVM C2 workaround. Directory fsync is
simulated by the existing Windows-compatible Os shadow; payload files use real file sync.
Symlink rejection remains source-reviewed and needs platform integration coverage.
Process-death Restore/Discard and the visible error path are mandatory once 7C3 enables the offer.
