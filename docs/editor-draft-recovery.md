# Individual editor draft recovery

The developer chose an explicit **Restore / Discard** offer for the last unfinished individual
editor after restart. Global options and other unsaved profile edits are outside this scope.
Never restore an old `ProfileSessionToken`; successful recovery obtains a new live editor owner.

## Checkpoints

- **7C1 — storage foundation (confirmed and verified).** Versioned envelope and one local
  draft store. No editor capture, recovery prompt or storage-replacement hooks are enabled yet.
- **7C2 — capture and state codec.** Define the complete controls/artwork representation, capture
  immutable editor inputs and checkpoint them without I/O in composition or a long-held profile
  gate. Include disabled values and all source-specific state needed to reproduce the draft.
- **7C3 — recovery and lifecycle.** Offer the validated draft after startup, bind it to a fresh
  editor session, clear it on Apply/explicit dismissal, and integrate import/recovery/deletion
  invalidation. Validate races and cancellation before enabling recovery offers.

Each checkpoint follows CLAUDE.md's device-confirmation/test/commit sequence. The envelope is
not evidence that arbitrary controls or artwork can already be restored. End-to-end recovery
must remain disabled until both semantic codecs and domain validation are integrated.

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
