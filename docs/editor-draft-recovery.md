# Individual editor draft recovery

The developer chose an explicit **Restore / Discard** offer for the last unfinished individual
editor after restart. Global options and other unsaved profile edits are outside this scope.
Never restore an old `ProfileSessionToken`; successful recovery obtains a new live editor owner.

## Checkpoints

- **7C1 — storage foundation (confirmed and verified).** Versioned envelope and one local
  draft store. No editor capture, recovery prompt or storage-replacement hooks are enabled yet.
- **7C2a — controls snapshot/codec (confirmed and verified).** Lossless bounded control
  values and a Main-thread capture adapter. It is not wired to autosave or recovery.
- **7C2b1 — persisted baselines and owned resources (confirmed and verified).** Canonical
  SHA-256 persisted baseline encodings and bounded copies of selected gallery images/fonts.
  No production caller or recovery offer is installed.
- **7C2b2 — artwork and vector-state codecs (confirmed and verified).** Detached artwork,
  exact vector paths/root properties, editable entries and their original seed. No production
  capture/autosave/recovery caller is installed.
- **7C2b3 — complete capture (confirmed and verified).** Combine artwork, controls, original input, selected resources
  and replay bookkeeping with the persisted baseline encodings from 7C2b1. Capture a
  coherent completed frame without I/O in composition or a long-held profile gate. Include all
  source-specific state, use the verified resources from 7C2b1 and reject partial capture before
  checkpointing. Existing DB vector XML omits properties such as path trim, miter, clipping and
  some brush/vector metadata; it is insufficient as the exact draft artwork/seed codec.
- **7C3 — recovery and lifecycle.** Offer the validated draft after startup, bind it to a fresh
  editor session, clear it on Apply/explicit dismissal, and integrate import/recovery/deletion
  invalidation. Validate races and cancellation before enabling recovery offers.
  **7C3a (confirmed and verified)** prepares validated native values and a
  current persisted-baseline lease without attaching an editor.
  **7C3b1 (confirmed and verified)** reconstructs an unpublished session from
  that lease, including completed frame, controls, original/live identity and owned resources.
  **7C3b2 (confirmed and verified)** adds validated acceptance/native ownership transfer.
  **7C3b3 (confirmed and verified)** integrates first-composition rendering.
  **7C3c1 (confirmed and verified)** installs storage/profile invalidation and
  conditional unreadable-catalog discard. **7C3c2a (verified)** adds the visible offer/error owner;
  **7C3c2b** adds debounced lifecycle persistence using those boundaries.

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

## Baseline and resource checkpoint (7C2b1)

`EditorDraftFingerprints` hashes detached persisted values on Default without acquiring either
gate or reading Room. Version-1 `RKEF` encodings use a separate profile/icon domain, big-endian
primitive fields and length-prefixed strict UTF-8. Hashes are SHA-256 lowercase hex. Strings are
streamed into the digest rather than allocating another full copy of encoded drawable payloads;
both Unicode counting and writing check cancellation between chunks.

The profile baseline includes its id and canonical typed style (decode then encode), excluding
the legacy projection and presentation/bookkeeping fields. The icon baseline includes profile
id and component, explicit absent/present row identity, and every other `DbApplication` field:
rendered/base artwork and flags, calendar, source/reference metadata, custom/legacy/fallback
classification and the exact persisted modifier recipe/input documents. A present empty row
differs from an absent row. It is deliberately broader than the build-export SHA-1 fingerprint.
Drawable strings are bounded at 32 MiB each, recipes at 1 MiB, other row text at 64 KiB and the
typed style at its own codec limit. Invalid Unicode or oversized values fail capture explicitly.
Changing this canonical field order/meaning requires a fingerprint version change and explicit
handling of previously saved drafts, never treating them as equivalent under the new encoding.

7C2b3/7C3 must capture these immutable rows/styles under the owning provider/storage gate, hash
outside it, then revalidate session, storage generation and persisted revision before publishing
or accepting recovery. Profile/component deletion, row changes and storage replacement reject
the result. These hashes do not replace verification of referenced modifier-input artifact
bytes. They do not describe unsaved live artwork: that original live input still needs its own
owned payload in 7C2b3, without committing unrelated profile edits.

`EditorDraftResources` reads selected gallery PNGs only from the direct owned gallery directory,
and fonts only from the current system-font catalog or the bundled default. Canonical resolution
allows the Android filesDir alias but rejects links below that owned root and path escapes.
Opening regular files uses `O_NOFOLLOW`; bounded reads check truncation/growth and descriptor/
path identity before returning. Capturing a gallery selection does not create its directory.
The app's gallery files are immutable after atomic publication; import/generation validation in
the future owner must reject captures raced by a replacement. These checks are not a guarantee
against arbitrary same-size in-place writes by another privileged process.

All reads, bounds checks and resource hashing run on IO. PNG signatures and bounds are checked
without decoding pixels (both dimensions at most 4096); font data has a bounded sfnt header check.
Full PNG/Typeface validation remains the responsibility of the later bounded artwork/binding
codecs. Every resource is at most 32 MiB and retains independent private bytes, a SHA-256 digest,
kind and selection attribution; callers obtain copies for draft-store artifacts. Even the
bundled default font is captured as bytes so an app/system update cannot silently replace it.
References never become restore-time filesystem authority. This checkpoint does not write files
or catalogs, install a Hilt consumer, change editing behavior or enable autosave/recovery.

7C2b3 must enforce the 12-artifact/64-MiB aggregate budget while capturing all resources and
artwork, preserve their slot/selection/content correspondence and fail the complete capture if
any required resource is missing, incompatible or over budget. Recovery must resolve the owned
artifact, not reopen the old gallery/system path. It must preserve the original font selection
for display while rendering from verified owned font bytes. A missing gallery path from an
online import does not imply missing artwork; retain its captured raw icon instead.

The 7C2b3 autosave boundary must handle resource-read/validation failures as well as control
capture/encoding failures. In particular, gallery files restored from backup can exceed the
4096-pixel capture limit; backup import does not resize them. The current share-intent path
already uses `getBitmapFromURI` with bounds-based downsampling, but callers must not assume all
gallery entries passed through that path or the editor's 500-pixel resizing. Do not resize a
required source silently or publish a draft with that source omitted. Reject the complete
capture, retain the last valid checkpoint and live editor, and report/log autosave unavailability
without crashing a collector/Main. Cancellation still propagates; a later valid capture may retry.

A selected system font can disappear or leave the allowed catalog after a system/OEM update.
Handle that failure at capture with the same checkpoint-preserving policy, without substituting
the bundled font. Recovery of an already captured draft must use its verified owned font bytes;
it must not call `font(oldReference)` or require the former system path to exist. Apply still
uses the explicitly confirmed live result; a failed draft autosave must not commit or discard it.

Call `profile()` only after shared style bootstrap/storage readiness. An empty or invalid
`profileStyle` is a validation failure, never an empty/default fingerprint or permission to fall
back to `prefsSnapshot`. The 7C3 offer/accept boundary must catch it, log/report that this draft
cannot currently be offered/restored and leave its checkpoint intact. Do not attach a fresh
editor or let the failure escape into a UI flow. Existing profile-storage recovery remains
responsible for storage faults; the draft owner must not initiate it merely to handle a draft
validation failure. Retry must repeat readiness and ownership checks.

7C2b1 device regressions are confirmed. Seventeen new tests cover every row field with a
schema-coverage assertion, absent-row framing, canonical typed style and excluded metadata,
verbatim documents, owner mismatch, streamed Unicode against standard UTF-8, bounds and
cancellation. Resource tests cover independent bytes/hash, the bundled font, missing/out-of-
catalog fonts, path rejection, PNG bounds, oversized files, short/truncated/growing reads,
changed path identity and descriptor cleanup on failure/cancellation. A test-only Os shadow
simulates descriptors/stat changes over real JVM file reads; real Android system-font capture
and symlink/no-follow behavior remain platform integration coverage. All 991 tests across 154
suites and `assembleDebug` pass with the existing local test-JVM C2 workaround.
Before enabling 7C2b3/7C3, verify over-limit backup uploads, a font disappearing before capture,
owned-font recovery after its system path disappears, and empty/invalid style during offer or
acceptance. Each rejected capture/offer must preserve the prior checkpoint without crashing or
silently publishing partial/default state.

## Artwork and vector-state checkpoint (7C2b2)

`EditorArtworkSnapshot` contains detached values for bitmap, vector, adaptive and inset
artwork. `ui/EditorArtworkCapture.kt` copies live values on the owning Main thread; it neither
reads files nor acquires a gate. Byte arrays are privately copied; vector command/child lists
and nested pack-style lists are detached. PathNode values themselves are immutable. The
working-vector snapshot contains both current entries and the original seed, with fill flags,
base stroke widths, thickness, auto-centering, viewport, attribution and baked-edits flags.
An initialized vector without its seed is rejected instead of treating its current paths as
an unchanged seed. This checkpoint exposes a read-only seed accessor without changing editing.

`EditorArtworkCodec` version 1 uses separate big-endian `RKEA` artwork and `RKEV` working-state
headers. It preserves finite Float bits, strict UTF-8, nullable values and exact 0/1 booleans,
rejecting unknown kinds/commands, versions, trailing/truncated content and invalid counts.
All 19 PathNode kinds have explicit tags. Vectors retain names, root/group transforms and clip
paths, path fill type, colors/reference brushes, alpha, stroke cap/join/miter and trim, tint,
viewport/default size, mirroring and stroke-scaling policy. Ordinary DB vector XML is not
used because its conversion loses editable properties. SolidColor and ReferenceBrush with a
SolidColorShader fallback are supported; other shaders/gradients fail capture rather than
being flattened or substituted. A reference is preserved metadata, never permission to load
an external resource during binding.

Bitmaps preserve raw ARGB_8888 sRGB pixels, row layout, density, alpha/premultiplication,
adaptive flag and preview scale. Dimensions are limited to 4096; other configs/color spaces
are rejected. Native binding creates a fresh bitmap and rejects a runtime with an incompatible
row layout or flags. Raw storage avoids PNG conversion changing premultiplied pixel values.
Adaptive snapshots retain exact foreground/background/optional monochrome PNGs, original
layers and optional pack controls. The nested `RKEP` version-1 pack document reuses the controls
codec's exact style encoding. Inset snapshots retain their child plus absolute/fractional
margins; a child outside the supported IconPackDrawable types is rejected.

Draft artwork cannot currently be captured/recovered for these inputs:

- Bitmaps outside sRGB, including Display P3 uploads or shared images, or configurations
  other than ARGB_8888.
- Bitmaps with either dimension above 4096, or raw pixel bytes exceeding the 32 MiB aggregate
  budget. A 3000-by-3000 ARGB image already needs 36,000,000 bytes and exceeds that budget;
  the dimension ceiling alone does not establish support. Encoded framing also consumes space.
- Vectors containing brushes other than SolidColor or ReferenceBrush with a SolidColorShader
  fallback, including gradient brushes if an importer produces them.
- Unsupported inset children, over-budget trees/commands/layers, or values rejected by the
  codec/native binding checks described below.

These are draft-recovery limits, not a request to disable existing live editing or Apply.
The complete-capture/autosave owner in 7C2b3/7C3 must catch capture and encode validation failures,
including failures thrown directly on Main before a suspend call. Keep the live editor and
last valid checkpoint, report/log that the latest state could not be saved for recovery, and
allow a later supported capture to retry. Never resize, convert the color space, flatten a
brush or omit required artwork silently to fit the draft. Cancellation must propagate.

Main-thread bitmap capture copies the entire raw pixel buffer; 1024-by-1024 ARGB pixels alone
are 4 MiB, and the private byte wrapper makes another detached copy. Debounce routine gestures
before calling capture, not merely before background encoding/store writes. Do not copy the
bitmap on every brush point or slider event. Background flushes still need the owning-thread
capture and completed-render checks, with timing/heap coverage before enabling autosave.

Limits apply across the entire artwork tree, or across both current and seed vector state:
depth 64, 10,000 nodes, 100,000 commands, and 32 MiB of copied pixel/layer bytes. The complete
encoded artifact also has a 32 MiB ceiling. Text is limited to 64 KiB UTF-8. Capture checks
collection/byte budgets before copying, and reader counts are checked against remaining bytes
before allocation. Background encoding/decoding checks coroutine cancellation, including
command lists containing only Close nodes. These per-artifact limits do not replace the store's
12-artifact/64 MiB total; complete capture must enforce the latter before publication.

`restoreEditorArtwork` validates the detached tree/budgets before allocating native drawable
objects on Default, checks packed colors and named stroke/tint modes, and validates PNG
signature, 750-pixel layer bounds and full decoding. It never opens captured paths or resolves
references. Binding currently re-encodes for whole-tree validation, and raw byte copies can
temporarily coexist with encoded/native buffers; 32 MiB is an artifact limit, not a peak-heap
limit. Future recovery must validate all source/control domains and accept ownership before
publishing any bound object. Codec-level finite-number checks alone do not establish that a
working vector or disabled UI value is semantically supported by the current editor.

Adaptive binding currently fully decodes each PNG for validation and recycles that temporary
bitmap. AdaptiveIconPackDrawable retains the compressed bytes and decodes them again when a
layer is requested for preview/rendering. This repeated work is a known off-Main recovery cost;
the constructor itself does not decode. Measure it at recovery integration before introducing
validated-layer reuse, which must preserve bounded ownership and avoid retaining large decoded
buffers for every captured icon. Do not remove full-decode validation merely to save this work.

No live caller uses these APIs yet. Complete capture in 7C2b3 must additionally preserve
custom source selections, original/raw and completed artwork, modifier-replay ownership and
baseline, and IconDraftState origin/private bookkeeping. It must identify a coherent completed
render generation together with its controls; the selected tab is not evidence of origin or
render completion. Rebinding editable paths must preserve the captured seed and source flags,
not call captureSeed on already edited paths or publish a restored vector as a new edit.

Device regressions are confirmed. Nineteen new Robolectric/native-graphics tests cover every
command and vector field, direct root properties, all supported stroke/tint modes, brush
references and packed colors, raw pixel/flag/density/preview round trips, adaptive originals
and controls, nested insets, detached mutable capture, current/seed differences and baked
flags. They reject every truncated prefix, trailing content, invalid framing/counts/UTF-8,
non-finite values, unsupported brushes/configs/color spaces, over-budget dimensions/pixels/
trees/current-plus-seed commands, missing seeds and incompatible native layouts/modes/PNGs.
Close-only command lists and cancelled coroutine owners exercise cancellation. All 1,010 tests
across 155 suites pass without failures/errors/skips, and `assembleDebug` passes with the
existing local test-JVM C2 workaround. No production recovery caller is installed; Android
process-death recovery, integration timing/heap and semantic live-state publication remain
future checkpoint coverage, not established by these codec/binding regressions.

## Complete capture checkpoint (7C2b3)

`EditorSessionViewModel.captureCompleteDraft` is an explicit, serialized preparation API. It
has no production caller in this checkpoint and never calls `save`, `discard`, or storage
invalidation. It returns Ready, Superseded (including pending renders/source publications), or
Failed. Failure is logged and returned to the future lifecycle owner; cancellation propagates.
An unsupported Main capture, missing resource/input file, encoding rejection, invalid persisted
style or allocation failure cannot replace the last valid checkpoint or mutate the live editor.
7C3 must display Failed, preserve Retry, and handle corrupt-catalog explicit discard before
enabling this API from lifecycle/autosave code. A morphing startup LoadingIndicator is unchanged.

### Completed render ownership

The existing `IconDraftState` owns one completed frame alongside the winning artwork, recipe
and optional portable input. A narrow `captureState` contract exposes its initialization and
restore availability, actual origin, raw-stage pipeline/baked flag, previous verified input,
active replay, requested/effective options and frame serial. Generation counters, active-job
counts, source invalidation revisions and live session tokens are deliberately not durable
state. Invalidating the active source clears the completed frame; clearing an inactive
upload/vector draft preserves it. Pending/failed renders and a witness mismatch defer capture
instead of pairing current controls with an old completed result.
An empty/null preview also defers preparation; it cannot replace a valid checkpoint with an
incomplete source/result pair.

`EditorRenderWitness` contains detached controls, the selected resource identity, raw source
publication identity, upload selection (path/adaptive/zoom), vector inputs, online attribution
and replay owner. The dialog freezes it and the source selection in the same composition as
its generation options. Render effects check their options and source identity before starting
and before publishing. Their keys contain render inputs and explicit source invalidation;
browsing/tab state and controls outside generation options do not cancel an in-flight render.
A separate idle effect acknowledges the current
witness only when the completed frame still matches the exact render options and source.
Equal completed Create inputs, or equal upload/vector options and raw source, reuse the result;
they do not apply another modifier stage.
Apply still reads the completed artwork/recipe/input pair, never capture output or pending
options. An obsolete render cannot publish solely because its coroutine has not yet been cancelled.
Apply availability and its tap-time guard also check the acknowledged frame and published source,
covering the interval before a newly keyed render effect starts. Unsupported recovery controls
can still use live Apply once their normal source/render is complete; a missing witness alone
does not disable supported existing editing behavior.

The untouched initial saved-icon frame explicitly has no effective rendered-options document.
Its neutral current controls do not claim to have produced the old baked artwork. The old
recipe/input remain attached to that artwork, with their own source options and owned input,
until a real new render completes. Recovery must preserve this distinction and the completed
Create request; it must not render the old recipe over the already baked initial icon.

The witness copies bounded control/brush values when its Compose dependencies change and at
explicit capture/revalidation. Render entry/completion checks compare source identities and
options without copying controls. It copies no pixels, fonts or gallery files. The future owner
must debounce before invoking complete preparation, not just before encoding or store writes.
This control-copy overhead and Main pixel-copy heap/timing still require device measurement;
no performance improvement is claimed by this checkpoint.

Upload callbacks verify the composition's path, adaptive choice and zoom before publishing raw
artwork; the session records the accepted selection. Vector callbacks already verify the full
current input snapshot; the session additionally records that publication. Complete capture
rejects a source whose current tools have not published their artwork yet, even when the shared
modifier renderer is idle. Selected tab and the generating flag alone never establish origin.

`IconGenerationResult.draftInput`/`draftPipeline` retain the actual source-stage input for
individual previews independently of optional portable modifier metadata. This includes brush
edits and pack restyling that cannot fit the portable recipe/input contract. It introduces no
second source render or bitmap copy. Bulk calls with input retention disabled keep their old
result/lifetime behavior. The raw baked-modifier flag also travels when no portable input exists.

### Detached values and wire format

`EditorCompleteMainCapture` copies native artwork on Main and exposes only detached value trees
and immutable stored-input descriptors to background preparation. It preserves:

- The original live application's base and rendered artwork, labels, local recipe/input,
  calendar, source/online attribution and fallback/custom/legacy/refresh classification. Both
  artwork values are retained even when they differ from the persisted row; preparation never
  saves that row or other unsaved profile changes.
- The completed raw stage and result, requested and effective generation options, optional
  portable recipe/input, actual origin and private initialization/restore/baked bookkeeping.
- The session's retained raw source and its baked flag, the previous verified input after source
  transitions, and active replay's original input/options, UI-projection baseline and plate style.
- All controls, current and original-seed vector values, ordered brush geometry and exact native
  erase/background masks. Masks retain separate bitmap references and operation order inside
  one owned artifact; erase/restore groups are never flattened into a different operation.
- `customIconList` selection identity (resource id and pack), online/gallery attribution, owned
  selected upload PNG bytes and owned selected font bytes. Font lookup uses only the captured
  controls selection through the catalog resolver; neither replay options nor a decoded path
  grant filesystem authority. Recovery must render from the owned font bytes.

The current picker publishes zero or one custom resource. Complete capture explicitly rejects
a larger list. Its owned selected artwork is the actual canonical source-stage input, including
source parsing, rather than a serialization of an arbitrary Android ResourceDrawable. Resource
id/pack/path/URL remain attribution. 7C3 must bind that frozen stage and restore the completed
request bookkeeping; it must not fetch/reparse the pack resource or reapply source-stage pack
restyling to reproduce the captured frame. A user explicitly choosing a new source may use the
normal live source pipeline afterward.

`EditorCompleteCodec` uses separate `controls` (RKED v1), `vector` (RKEV v1), `session` (RKEC v1),
numbered RKEA artwork slots, optional `masks` (RKEM v1), `font`, and optional `upload` artifacts.
The masks document contains unique numbered `mask-N` entries with exact RKEA bitmap documents;
generation metadata retains their ordered references and erase/restore actions. RKEC is fixed-order,
big-endian and includes the SHA-256 of its exact controls artifact. It writes frame flags,
original input metadata, completed request/result metadata, replay bookkeeping, selections and
owned-resource references. Strings use strict UTF-8 with bounded length, nullable values have
strict presence booleans, enums use names, and finite Float bits are preserved. Recipes allow
1 MiB; ordinary text retains the existing 64 KiB limit. Unknown versions/enums, invalid counts,
truncation, trailing content and non-finite values fail. Changing field order/meaning needs an
explicit version change/adapter.

`EditorGenerationWire` encodes all source/export/modifier fields directly, reusing the exact
control-style field encoding. It preserves nullable groups, inactive values, high/low edge
thresholds, inherited versus explicit fills, resolved dynamic colors and export flags. It does
not pass complete options through ModifierRecipeCodec or its normalization/clamping. Native
masks are removed from the detached options object only after exact owned bitmap references are
captured; decoding retains those references for later binding rather than inventing masks.

The codec checks referenced artwork, bitmap mask types, vector/control agreement, selected
pack/stage correspondence, completed input/pipeline/baked correspondence and owned font/upload
selection/digest correspondence. Extra, duplicate or missing resources/artwork reject the whole
capture. Artwork is deduplicated by live icon identity; masks are deduplicated by bitmap identity
within a single capture. No cross-editor cache is introduced. Encoded totals include controls,
metadata, vector state,
all artwork and resources. The store's 12-artifact/64-MiB and per-artifact limits apply before a
Ready result. All masks together consume one artifact slot, bounded to 10,000 entries and
32 MiB including their encoded headers; alternating erase/restore groups therefore do not
consume the store's artwork slots. Oversized masks fail capture without dropping operations.
Known resource bytes plus cumulative raw artwork bytes are checked
before pixel/layer copies, with exact encoded-size checks afterward. Temporary encoding/native
buffers can coexist; these limits still do not bound peak heap usage.

The original persisted modifier input can have only a content-addressed file descriptor. Capture
copies that descriptor and its exact options, then reads/verifies the file on IO and decodes on
Default using the existing input store. The resulting unpublished native value is captured on
Main. New live artwork is never serialized through DB XML. Unavailable/unsupported original
input fails the complete capture instead of quietly dropping re-editing metadata.

`VectorEditState.restoreDraftSeed` is a narrow future binding contract: 7C3 must assign validated
current values/initialization first, then attach the preserved original seed. It records the
captured current publication without recapturing the edited paths as their own seed. Reattaching
that recovered state does not publish the same current vector as a new source edit. The original
seed comparison remains intact, and unchanged replay seeds keep replay. This accessor is not a
recovery caller and does not attach an old session owner.

### Baselines, races and remaining verification

The provider captures the exact component's persisted DbApplication row, authoritative Profile
row, original live application identity, saved-row revision and both storage epochs under its
existing storage/profile gates. A single-component DAO query avoids reading all profile artwork.
Fingerprinting, owned-resource reads, stored-input decoding and complete encoding happen after
releasing those gates. Before Ready, preparation repeats provider session/component/live-original
identity, persisted row/style, revision/epoch checks, compares the persistent draft checkpoint and
rechecks the Main editor witness/frame serial/accepted source publications.

Ready is a process-local preparation lease, not permission to publish later without validation.
`PreparedEditorDraft` carries the captured baseline/checkpoint and private encoded payloads;
its live token is never encoded. 7C3 must recheck the editor and baseline at publication and pass
the exact expected checkpoint to the store. Storage replacement must invalidate the generation
before writes, using storage gate → draft store; the store never calls back into the provider.
It must also handle an ambiguous store publication failure by rereading the checkpoint. This
checkpoint installs no replacement hooks, lifecycle flushes, clear-on-Apply or recovery prompt.

Device regression confirmation is complete. After confirmation, 23 new complete-capture/codec
tests and four provider-baseline tests passed. Coverage includes inactive-source clearing,
idle/tab acknowledgment without rerendering, option mismatches, pending/cancelled/obsolete
renders, source revision invalidation, exact original unsaved artwork and stage input without
portable metadata, all-option wire round trips, 24 alternating erase/restore masks in one
artifact, corrupt mask documents, multi-digit artwork slots, required-resource/digest/vector
correspondence, unpublished sources, seed acknowledgment, replay/baked transitions and Main
budget rejection. Provider tests cover unchanged-state publication, value-based persisted-row
comparison, changed rows, replaced live identity, storage replacement and profile switching.
All 1,037 tests across 156 suites and `assembleDebug` pass using the local test-JVM C2 workaround;
logs are in `build/verify-7c2b3-targeted.log` and `build/verify-7c2b3-full.log`.
Native binding/semantic UI-domain validation, orchestration failures preserving the last stored
checkpoint and end-to-end process-death recovery remain 7C3 coverage. The preparation API has
no production caller and cannot establish lifecycle/recovery behavior by itself.

## Recovery preparation checkpoint (7C3a)

`EditorSessionViewModel.prepareDraftRecovery` is an explicit serialized API with no production
caller. It shares the editor's capture mutex, requires no open editor and carries a monotonic
editor generation so opening and closing another editor during preparation expires the result.
It returns Ready, Empty, Superseded, Obsolete, NeedsProfile or Failed; it never calls save, discard,
invalidateStorage or getOrCreate, and never attaches decoded controls to live Compose state.
Cancellation propagates. Corruption, unsupported values, native binding/resource errors and
allocation failures remain visible Failed results for the future offer owner, preserving the
catalog and last valid stored checkpoint.

Candidate inspection and artifact reads use the store's checkpoint, lengths and checksums. The
provider captures the currently active profile's component, authoritative style, persisted icon
row, original live identity, saved revision and both storage epochs under its existing gates.
An unavailable component or a durable fingerprint mismatch returns Obsolete without error logging;
the future offer owner can explain that the draft no longer applies and offer checkpoint-aware
discard without presenting a corruption error. Preparation never discards it automatically.
The provider uses the exact session token inspected before entering its gate, rather than capturing
a newer token there. A profile switch during that interval returns Superseded, including a switch
away and back. Before Obsolete, preparation rechecks the available baseline, checkpoint and editor/
provider ownership so a detected concurrent change takes precedence as Superseded.
Both canonical durable fingerprints must match before decoding. After decode and after native
binding, preparation rechecks the baseline and checkpoint; before Ready it also rechecks editor
generation and the current ready provider token on Main. Hashing, decoding, resource work and
native binding run outside those gates. A different active profile returns NeedsProfile without
switching profiles or granting acceptance; 7C3b must resolve profile existence and the user's
normal Save/Discard switch flow, then prepare again for that profile before showing acceptance.

`EditorRecoveryValidation` applies semantic domains after the strict wire codec and before
native binding. It validates packed Color/color-space values, gradient stop counts/positions,
upload zoom, vector thickness/viewport/path paint, controls and generation transforms, outline,
edge, background-removal and shadow parameters. It retains valid disabled values, signed zeros,
nullable modifier groups and style flags. Positionless gradients retain their meaning; explicit
positions must match their colors, remain ordered and lie in 0..1. Unsupported values reject
the draft without normalization, truncation or substitution. Pack scheme -1 still means inherited;
non-negative scheme indices beyond the current palette remain Custom, with no palette-count cap.
Explicit high edge thresholds are preserved rather than replaced with three times the low value.
Replay/current-source/original-input references must agree before any native object is built.

`bindEditorRecovery` creates unpublished artwork, mask references, original/current input values,
exact requested/effective options and editable current/seed vector paths on Default. All references
to one mask slot share the restored bitmap and ordered erase/restore operations remain separate.
It does not select/fetch a pack drawable or run the source generation pipeline. A previous verified
input is verified again against the captured original base using the existing modifier-stage
verifier; it must not verify against the draft's later edited result or bake another modifier into
that result. Replay bookkeeping remains available for the later live binding step. Failed,
cancelled or superseded unattached preparations recycle their owned bitmaps, including cancellation
while dispatching a completed native preparation back to its caller.

`EditorRecoveryResources` admits only the captured owned bytes. Font loading uses a randomly named
private temporary cache file on IO and removes it in finally; decoded font identifiers never
become filesystem paths. API 26+ uses Typeface.Builder so invalid font data cannot silently fall
back through createFromFile; API 23–25 uses the legacy loader. AOSP Android 6.0.1 and 7.1.2 throw
when that loader rejects a font, rather than silently returning DEFAULT; device coverage remains
necessary. A process death before finally can leave a temporary
font in cache; startup cleanup of this fixed-prefix cache namespace remains an integration task.
Upload PNGs receive signature/bounds checks and full ARGB_8888 decoding on Default, without
resizing. Their expanded pixels are bounded
to 32 MiB and must also fit the remaining 64 MiB budget after artwork bitmap pixels, including inset
children. These bounds do not limit total peak heap or adaptive-layer decoder scratch space.
The resulting Typeface/upload bitmap are for later session binding; 7C3b must explicitly pass them
to text/upload rendering instead of reopening old selection paths or fetching a replacement font.

Ready carries `PreparedEditorRecovery`, a process-local lease containing the exact checkpoint,
current baseline, editor generation and native values. It contains no restored old token. The
future offer owner owns this lease and must dispose unattached values if the offer expires or is
discarded, only after removing every preview consumer that could still draw its bitmaps. Expiring
an offer does not by itself make recycling safe while a dismissal animation still displays it.
Acceptance must repeat baseline/checkpoint/editor-generation validation before creating
a fresh editor and transferring native ownership. This checkpoint deliberately provides no
acceptance or UI entry point. Session construction is the separate 7C3b1 checkpoint below.

The device regression checkpoint is confirmed. Twenty-nine new tests cover valid/invalid control
domains (including disabled fields, inherited/Custom schemes, packed colors, positionless gradients
and explicit high thresholds), native pixels, mask aliasing/order, independent current/seed paths,
owned-byte resource binding without resolving selection paths, font preconditions, upload bounds/
expanded pixel budgets, temporary-file cleanup and original-stage replay verification. Preparation
tests use real temporary checkpoint files and an in-memory provider: they cover Ready without live
attachment, Empty, stale checkpoints, Obsolete for changed durable fingerprints or a removed
component, NeedsProfile without switching, new editor generations, profile/storage/checkpoint
replacement during binding, cancellation, semantic/corrupt artifact rejection and I/O/OOM failures
preserving stored data. Provider tests reject the inspected token after switching away and back.
All 1,066 tests across 158 suites, compileDebugKotlin and assembleDebug pass with the existing local
test-JVM C2 workaround. Logs are in build/verify-7c3a-compile.log, build/verify-7c3a-targeted.log and
build/verify-7c3a-full.log. Native font parser behavior on API 23–25 and current devices, cancellation
at completed dispatcher handoff and allocation timing/peak heap still need platform coverage.
There is no production autosave/recovery caller; on-device checks establish startup/editor regressions.

Before enabling recovery, implement a visible corrupt-catalog Retry/confirmed-discard path, fresh
session construction with exact initial/completed-frame/source/replay/vector-seed ownership,
debounce before Main pixel copies, background flush, checkpoint-aware Apply/dismiss cleanup,
and invalidation before storage replacement/deletion. Keep the startup morphing LoadingIndicator.

## Session reconstruction checkpoint (7C3b1)

`PreparedEditorRecovery.sessionForAcceptance()` constructs and caches one unpublished EditorSession
on Main. It uses the current provider token from the preparation lease, never a serialized old
token. Neither this method nor the factory attaches the session to EditorSessionViewModel, changes
the app list, switches profiles, writes storage or accepts recovery. They have no production caller.
The 7C3b2 owner must revalidate baseline/checkpoint/editor generation before publishing this session;
constructing it does not extend or renew the lease.

`EditorControlRestore` directly restores the already validated control values, including disabled
styles/groups, packed colors, nullable attribution, selected tab, Create/browser selections, Upload
zoom/adaptive values, all adjustments, ordered brush geometry and pack styling. It neither clamps
values nor reconstructs them from a portable recipe. Vector current/seed inputs are installed
independently, with initialized, viewport, thickness, filled/base-stroke metadata, attribution and
baked flags intact. The original seed and current publication are acknowledged through the existing
restoreDraftSeed contract. Upload publication is acknowledged only for the active Upload origin.

The reconstructed application contains the original captured base/rendered artwork, input, recipe,
calendar/source metadata and original flags. Its platform icon/component are obtained from the
currently verified live application. `EditorSession.baselineApplication` retains that provider-owned
live identity separately, so later capture does not ask the provider to find the reconstructed
original object in its app list. Normal sessions default this reference to their existing application.
Recovery does not persist the captured original or overwrite unrelated live profile edits.

`IconDraftState.restoreDraft` installs the actual origin's raw/result/recipe/input pair, exact
requested/effective generation options, initialization/restore availability, restored original
input and replay. It reconstructs the completed Create request against the restored original and
selected source, then captures a new witness after origin/source installation. The frame receives
a new runtime serial; old generation counters, active jobs and source revisions are not restored.
An immediate capture or confirmation can refer to this coherent frame without calling a builder
or applying modifiers to the saved result. This is not yet a guarantee about the first mounted
OptionsDialog: the UI's derived options/masks and tab effects use the separate 7C3b3 projection below.

`EditorModifierReplay.fromDraft` restores the captured baseline modifier stack and plate directly;
it deliberately does not call restoreControls, which would overwrite the current draft with the
original recipe's controls. Its verified original input remains separate from the current edited
render. Source switching retains the existing restored-source baked behavior.

Selected pack artwork is restored as a `RetainedModifierStage` containing the captured raw input,
pipeline and baked flag. The reconstructed ResourceDrawable is attribution only. Both Create
regeneration and modifier-sheet previews render the frozen stage directly, using the same renderer
as verified modifier replay; neither path reopens pack XML, normalizes adaptive geometry again or
reapplies source-stage pack restyling. A failed retained render preserves the completed trio and
blocks Apply. Explicit source selection or modifier restoration releases the frozen-stage route.
Text/application sources keep their normal source-generation semantics; text uses the owned font.

`EditorSessionResources` retains immutable owned font/upload bytes and their native values keyed
by the captured selection reference. Complete recapture reuses these exact bytes while the
reference and retained selection match, rather than reopening missing/changed old paths. Text
generation and its sheet previews pass the owned Typeface through the VM/provider/generation
service into LetterGenerator, without putting native values in GenerationOptions or its codec.
UploadColumn uses the owned bitmap before any gallery decode, and suppresses publication of an
already acknowledged unchanged recovered upload. Zoom/adaptive edits still publish normally.
Explicit font/gallery replacement invalidates the retained selection even at the same path; capture
then uses the normal bounded resolver. Upload callbacks also verify owned-image identity, so a
callback from a replaced recovered image cannot publish merely because the path/zoom still match.
An application reference replaced in the provider now classifies capture as Superseded without an
error log, for both normal and recovered sessions.

The preparation continues owning all native values while construction is unpublished. Its
disposeUnattached clears the cached session and recycles native artwork once, and reconstruction
after disposal is rejected. The caller must first detach every preview consumer. The 7C3b2
acceptance API below guards live ownership transfer, so offer cleanup can never recycle artwork
transferred to a live editor.

Device regressions are confirmed. Nineteen new tests cover exact reconstructed controls and
completed frames, distinct original/live artwork, frozen canonical/adaptive pack rendering without
source lookup, source replacement, replay bookkeeping, vector current/seed independence, brush
mask aliases, owned font/upload reuse and replacement, construction/disposal, and expired live
identity capture. Compose regressions cover missing-file Upload entry and zoom publication; they
also caught and fixed the empty-gallery branch hiding a recovered owned image. A test-only
Roborazzi reference covers the actual gradient/shadow modifier pipeline, with an intentional
color mutation proving ordinary verification fails. All 1,085 tests across 161 suites,
compileDebugKotlin and assembleDebug pass with the local test-JVM C2 workaround. Roborazzi is
absent from debugRuntimeClasspath; see [rendering tests](rendering-tests.md).

Before enabling an offer, verify the 7C3b3 first-composition projection, modifier-sheet previews and
Clear/Restore against captured originals, including unsaved originals absent from the live app
list. 7C3c must implement explicit obsolete/corrupt Retry/discard behavior,
debounced autosave/background flush, checkpoint-aware Apply/dismiss cleanup and storage invalidation.
Startup/process-death recovery remains disabled in this checkpoint; keep the morphing LoadingIndicator.

## Validated acceptance checkpoint (7C3b2)

`EditorSessionViewModel.acceptDraftRecovery` is a dormant explicit operation returning Accepted,
Superseded or Failed. Preparation records the creating VM's process-local identity as well as its
generation. The VM identity is a required non-null constructor argument. A newly created VM cannot
accept an old preparation merely because its counter starts
at the same number. Opening/releasing another editor, clearing the VM, disposing or transferring
the preparation rejects acceptance. A cheap Main ownership check and successful checkpoint lease
precede cached session construction; ownership is checked again after the lease. Session construction
and result allocation happen before final validation/publication; no render, resource lookup or
storage write occurs there.

`EditorDraftStore.acceptanceLease` checks the persisted checkpoint under its own mutex on IO,
then releases the mutex before the provider gate is acquired. A short process-local synchronized
fence ties that check to final publication. Every admitted save, matching discard and storage
invalidation expires earlier leases before mutation begins, including mutations that later fail
or are cancelled. An already stale save/discard does not expire the lease. This fence is scoped
to the singleton store and arbitrates same-process writes; it is not a persisted replacement
generation or a cross-process file lock. The persistent checkpoint/generation contracts remain
unchanged. Only a synchronous in-memory publication callback may run under the fence; it must
never acquire a gate or store mutex or perform I/O.

The provider acquires the profile/storage gate on Default, so any recovery callback triggered by
storage gate acquisition also starts off Main. It repeats the complete baseline check there (token,
epochs, saved revision, live application identity, profile style and stored icon row), then dispatches
only publication to Main while retaining both gates. This suspends the holder rather than blocking
a thread; Main callers likewise acquire gates through suspension. There are no blocking gate waits
on Main and no wait for UI emissions. Repository queries dispatch off Main.
The VM takes the store fence, checks cancellation, VM identity/generation, editor absence and
native ownership again, then transfers and installs the cached session without suspension. The
lock order is VM capture mutex, store mutex (released), storage/profile gate on Default, Main,
synchronous store fence. Writers hold the fence only to advance a counter, never during disk work. There is
no gate acquisition while holding the store mutex and no store I/O while the provider gate is held.

`PreparedEditorRecovery` has unattached, transferred and disposed ownership states. Transfer can
occur only once for its exact cached session. `disposeUnattached` after transfer is a no-op;
session release/replacement/VM cleanup rely on normal GC rather than manually recycling artwork
that a closing Compose preview might still draw. Superseded/failed acceptance does not dispose
the preparation: the offer owner must detach preview consumers first and explicitly dispose it.

Successful publication also sets `recoveryAttachment`, a runtime-only StateFlow containing the
session id, component and current token, without native values or saved-state serialization.
It lets the future UI retrieve the exact attached session through getOrCreate with allowCreate=false
even if cancellation propagates after publication and loses the return value. Normal replacement,
matching release and VM cleanup clear this attachment. Acceptance does not switch profiles,
overwrite live icons, discard the stored checkpoint or enable startup recovery/autosave.

StorageRecoveryUnavailableException during preparation/acceptance returns Superseded without a
draft-error log. The storage recovery owner remains responsible for its existing error/Retry UI.
The future offer owner must refresh preparation after Superseded while its editor/offer ownership
still permits recovery, rather than show a draft error or repeatedly reuse the stale preparation.
Keep a stable preparing presentation during refresh; a new result can then classify the latest
checkpoint as Ready, Obsolete, NeedsProfile or Empty. Avoid an immediate unbounded retry loop while
storage recovery is unavailable; resume when its state permits preparation.

The device regression checkpoint is confirmed. Twenty-five new tests cover same-VM/different-VM
ownership, editor open/release/clear, profile/icon/style/storage/revision changes, save/discard/
invalidation during Main handoff, admitted-write failure/cancellation and lease expiry, off-Main
gate-triggered recovery, unavailable-storage classification, cancellation before/after publication,
duplicate acceptance, disposal during handoff and after transfer, observable attachment and
checkpoint preservation. The existing reconstruction fixture now supplies its explicit owner.
All 1,110 tests across 161 suites, compileDebugKotlin and assembleDebug pass with the local test-JVM
C2 workaround, including ordinary Roborazzi verification. End-to-end Restore/Discard and
first-composition rendering remain subsequent checkpoint coverage.

## First-composition rendering checkpoint (7C3b3)

`EditorSession.restoreDraft` installs `EditorRecoveredRendering` beside the already completed frame.
It retains the captured requested options, exact control/source witness and restored brush geometry.
`generationOptionsFor` first obtains the ordinary UI/replay proposal, then patches only values whose
controls differ from that captured witness. It does not restore controls again, render, perform I/O
or derive the request from a portable recipe. Normal sessions use their existing UI/replay options.
OptionsDialog applies this projection to the main render request and the inputs of its modifier
previews. Draft styles inside those previews remain free to substitute their own temporary values.

OptionsDialog memoizes the projected request by session, UI options, composed witness, recovery
projection and modifier replay. Unrelated recompositions reuse the result instead of traversing
captured styles and brush points again. Actual input changes still recompute the projection; key
comparison and witness capture costs remain, so this is not a measured guarantee for large drafts.
The replay key also invalidates the result when a valid witness is temporarily unavailable.

Untouched controls preserve the captured request even when composition projects different defaults,
wallpaper colors or available pack metadata. This includes secondary/fallback source options,
themed/export background/foreground values, override, nullable shape/outline/shadow groups, explicit
shape fills, independent low/high edge thresholds, inactive segment/background values and source
restyling metadata. A scale edit patches scale; a smoothing edit does not derive a new high threshold.
A low-threshold edit deliberately uses the UI's derived high threshold. Existing optional groups
are patched per field; a previously absent group is introduced when its related controls change.

Recovered outline/background mask suppliers return the bound native values for unchanged geometry,
including null/empty masks and shared bitmap identities; remounting does not rasterize them again.
The normal builders run after relevant brush/activation edits. The projection likewise changes only
the mask whose geometry/activation changed, leaving unrelated native masks intact. Returning to
the captured geometry/control values reuses the captured request values rather than accumulated
projections. This state stays process-local and follows the preparation/session native ownership
contract from 7C3b2.

The source comparison uses origin, actual Create selection/replay/source identity and Upload path.
Upload zoom/adaptive changes and edits to an already active Vector do not count as a replacement
source and retain unrelated captured options. A new source adopts the current UI source/export
presentation and source-specific pack styling; shared modifiers retain their captured values until
their controls change. Consequently `selectNewSource` retains the projection and its native mask
references: they still supply untouched shared modifiers and edit reversals. That method also runs
for Upload zoom and Vector edits. Dropping it there would lose exact captured values; the references
follow session lifetime unless explicit saved-modifier restoration replaces the projection.
Explicit saved-modifier restoration drops this recovery projection and uses
the existing verified replay contract. The frozen selected-pack stage from 7C3b1 still owns its
source pixels and is never reparsed merely by mounting or editing a modifier.

`enterSelectedTab` compares against session-owned tab history, seeded to the restored tab. Initial
mount and configuration remount preserve imageEdit. An actual switch to a non-Modifier tab keeps
the existing reset policy, except during verified replay. An already initialized recovered vector
keeps its working/seed inputs and suppresses unchanged publication. If the captured vector tab was
not initialized yet, its first ordinary source seeding acknowledges the initialized inputs before
publication; this prepares the working preview without replacing the completed Create/Upload frame.
This intentionally differs from a live session with a vector source, where first visiting Vector
publishes the initial vector, switches origin to VECTOR and ends replay. A recovered session keeps
its captured origin/replay on that visit; an actual vector edit publishes normally. The recovery
offer integration in 7C3c must preserve this distinction.
Native mask suppliers and projections are not serialized or reintroduced into saved state.

This checkpoint does not add a recovery-offer caller, startup restoration or autosave. The developer
confirmed device regressions. Twenty-five new tests cover exact projected requests for all origins,
changed UI defaults, individual modifier edits and reversals, absent groups, independent edge
thresholds, explicit fills, native mask identity and lazy rebuilding, source replacement versus
Upload zoom/Vector geometry, decoded session tab mounting and replay/explicit restoration.
Compose tests exercise the production Vector tab wrapper: recovered initialization/remount does
not publish, real edits and reversals do, normal initialization still selects Vector, and a
recovered bitmap acknowledges empty paths without publishing null. Existing owned-Upload and
session reconstruction regressions remain in the targeted suite. A second Roborazzi test captures,
encodes, decodes and binds a selected canonical bitmap stage, then checks its projected rendering
against the unchanged gradient/shadow reference despite different current UI colors.
Full offer/dialog lifecycle, modifier-sheet interaction and Clear/Apply/discard races remain
integration coverage for 7C3c.
All 1,135 tests across 163 suites, compileDebugKotlin and assembleDebug pass with the existing
local test-JVM C2 workaround, including ordinary Roborazzi verification. Targeted and full logs
are `build/verify-7c3b3-targeted.log` and `build/verify-7c3b3-full.log`.

## Storage invalidation checkpoint (7C3c1)

`EditorDraftInvalidation` is a Hilt singleton sharing the same singleton `EditorDraftStore` as
future editor owners. BackupManager and ApplicationProvider require this dependency, including
standalone/test construction; there are no silent no-op hooks. AppModule supplies the shared
instance, so production uses no second context-created store or independent acceptance fence.

BackupManager invalidates the persistent generation before invoking the committed import/Previous
state callback, so failure cannot invalidate the provider's live session, activate a recovery
snapshot or mutate profile data. Archive inspection, cancelled preparation and export do not
invalidate drafts. The shared commit boundary also covers an imported additional profile, which
already changes the storage epoch. Pending rollback, failed-import rollback and Previous-state
snapshot application attempt invalidation before their own storage writes. Confirmed keep-current
repair attempts it before preserving/repairing abandoned recovery state. These emergency paths
must continue if draft I/O fails: they log the failure and try to remove the fixed private
`editor-draft` namespace, without traversing symlink targets. Cleanup failure is also logged and
does not block recovery. Acceptance leases expire before attempting the rotation. A process-local
pending-invalidation flag prevents subsequent draft access from admitting old checkpoints until
an empty generation can be durably published, even if cleanup failed. If all writes and cleanup
fail, this flag does not survive process death; normal owner/baseline validation remains required
after restart. Repeated rotations within one restore are intentional; the inner emergency
rotation never restores blocking behavior after the strict pre-commit boundary.

Profile deletion invalidates under the existing storage/profile gates before switching away or
deleting any row. It always attempts to rotate the generation, even with no draft or with another
profile's draft, to reject already prepared writes for the deleted profile. On successful rotation,
a different profile's record and artifacts remain intact under the new generation. A matching record is removed. The default
profile's existing early return remains. An unreadable catalog is logged and replaced with an
empty generation, allowing deletion without preserving an unusable draft. Failed rotation uses
the same cache-removal and pending-invalidation fallback as storage recovery, so a full draft
disk does not prevent profile deletion. This exceptional fallback may also discard another
profile's draft. Failed cleanup is logged and deletion continues while stale draft access stays
blocked within the process. If profile deletion itself later fails, its matching draft may already
be discarded. Lock order is storage/profile gate → store mutex → synchronous acceptance fence;
the store never enters a provider or acquires a domain gate.

`inspectCheckpoint` distinguishes a readable catalog from an unreadable one without reading or
decoding artwork. A readable catalog with damaged artifacts uses its ordinary checkpoint-aware
discard path. For unreadable metadata, inspection returns the original exception and, only when
the owned metadata files can be safely observed, a process-local reset lease. The lease identifies
this store, its admitted-write version and the SHA-256 of exact primary/backup/new metadata file
presence, lengths and bytes. Hashing streams on IO, including oversized corrupt metadata, without
allocating a whole invalid document. Ordinary inspection and Retry do not rotate the generation
or discard files, although AtomicFile may perform its normal backup recovery. When fallback
invalidation is pending, every catalog access first retries the required empty-generation rotation.

Only an explicitly confirmed error action may call `discardUnreadable`. Under the same
store mutex it checks store identity, write version and metadata fingerprint, retries catalog
reading, and refuses reset if it is now readable. It checks the fingerprint again after a failed
retry because AtomicFile may have promoted a backup. A mismatch returns null (Superseded); an
unchanged unreadable catalog rotates to an empty generation and returns its new checkpoint.
Filesystem/reset failures and cancellation propagate. A failed admitted reset expires its lease;
the owner must inspect again and keep an error visible instead of assuming discard succeeded.
As with other store commits, an ambiguous final fsync/cancellation outcome requires rereading
the checkpoint. These APIs are not cross-process filesystem locks.

This checkpoint activates replacement/deletion hooks only. No production caller yet inspects or
resets a corrupt catalog, captures an editor draft, shows a recovery offer or runs autosave.
7C3c2a must add the visible Retry/confirmed-discard path before enabling writes; 7C3c2b must serialize opening,
cleanup and reset in the editor owner, adopt fresh checkpoints after invalidation, revalidate live
ownership, debounce Main pixel copies and flush completed frames on background transitions.

The developer confirmed profile deletion and normal behavior on-device. Thirty new regressions
cover same/other/empty-profile invalidation, unreadable-catalog profile deletion, queued saves and
acceptance leases, failed rotation, blocked directory creation, failed cleanup and later repair.
Reset coverage includes unchanged errors, valid repair, different corruption, foreign/expired
leases, primary/backup/new metadata observations, oversized metadata and failed admitted resets.
Backup integration verifies strict import/Previous-state failure before the session callback,
successful invalidation before that callback, startup recovery, Abandon and rollback with broken
draft storage. Existing cancellation/publication tests remain green. Symlink target protection is
source-reviewed and still needs platform integration coverage. All 1,165 tests across 164 suites,
compileDebugKotlin and assembleDebug pass with the existing local test-JVM C2 workaround; both
ordinary Roborazzi scenarios verify the unchanged reference. Logs are
`build/verify-7c3c1-targeted.log` and `build/verify-7c3c1-full.log`.

## Recovery offer checkpoint (7C3c2a)

`EditorDraftRecoveryViewModel` is the activity-scoped Hilt offer owner, connected to the same
`EditorSessionViewModel` used by normal individual editors. It requires the production provider
and singleton store. It observes domain readiness/session identity, storage recovery and
the editor's generation/id. A serial request queue owns inspection, preparation, Restore,
confirmed Discard and restored-editor cleanup. Observation requests coalesce while an action is
running. Normal editor creation remains synchronous; its published lifetime and the existing VM
generation checks supersede preparation/acceptance rather than making Main wait for an owner lock.
Application-list revisions are deliberately excluded: bulk-refresh publications must not reread
the catalog or repeatedly decode a hidden offer. Acceptance still revalidates baseline/checkpoint
ownership and refreshes a superseded preparation before transfer.

Inspection starts after storage/domain startup is ready and no individual editor is attached.
Metadata failures produce a visible Retry/confirmed-discard offer; there is no empty-draft fallback.
An unavailable reset lease disables Discard. Retry never resets unreadable metadata. An owner
from another existing profile gets a named-profile message without switching; a deleted profile
or obsolete baseline gets an informational discard option. Not now retains the files and
suppresses that checkpoint in the current profile for this run; switching profiles checks again.
The valid Restore offer also has Not now; Back/outside dismissal postpones it without deleting
files. Postponement is memory-only and intentionally expires after process restart.
The prompt waits behind onboarding, release notes, pending watch suggestions and the main
backup/profile progress dialogs. It uses the shared alert/confirmation components and English
strings. No artwork preview consumes the prepared native resources.

Decode/preparation and acceptance run on Default, retaining the existing explicit Main publication
boundary. A Ready result lost during return dispatch is still disposed on Main. Unattached native
values are dropped before a new preparation, postponement or confirmed discard; transferred values
are never recycled by the offer. Each UI action must match the currently shown prompt by identity,
then the store/VM revalidate its checkpoint and live generation. Superseded preparation retries
automatically up to three times before showing an informational Try again option; superseded
acceptance immediately refreshes. The VM's recovery attachment is authoritative even if acceptance
publishes before a cancelled/failed return dispatch.

The host opens the accepted session id through the existing `OpenAppOptions`, preserving its
captured themed flag instead of reading today's preference. Apply, Clear and explicit dismissal
keep their existing icon-operation behavior; closing the restored dialog additionally releases
its live session and attempts checkpoint-aware persistent cleanup. A failed cleanup stays visible
with Retry. Unexpected session expiration/loss instead releases/detaches the editor and preserves
its checkpoint for another offer; it never uses the explicit-close cleanup path. Metadata failure
still requires confirmed Discard when unreadable. Ambiguous publication is inspected
again, and a changed checkpoint is never erased by an old cleanup. This checkpoint adds no automatic
capture or save, so ordinary edits do not yet create a durable recovery offer. Debounce, background
flush, normal-editor dismissal/Apply cleanup and capture-error handling remain 7C3c2b. Do not infer
autosave availability from a hidden/postponed recovery prompt.

The developer confirmed on-device behavior before automated verification. Fourteen owner tests
cover transfer/close, native disposal, memory-only postponement and a fresh owner, reconnect,
bulk publications without preparation, session-loss preservation, stale UI actions/checkpoints,
automatic re-preparation after superseded acceptance, obsolete/other-profile drafts, unreadable
metadata Retry/reset, repaired metadata and cleanup Retry. Seven Compose tests cover Restore,
Not now, confirmed/cancelled Discard, disabled unsafe Discard, Retry and named-profile messages.
All 1,186 tests across 166 suites pass with no failures, errors or skips, including both Roborazzi
scenarios; compileDebugKotlin and assembleDebug also pass. The existing preparation/acceptance suites retain cancellation, ownership and generation
race coverage. Verification logs: `build/verify-7c3c2a-targeted.log` and
`build/verify-7c3c2a-full.log`. Process-death autosave and large Main-copy/memory measurements
require 7C3c2b's actual capture scheduling.

## Envelope and local files

`data/editor/EditorDraftRecord.kt` contains primitive metadata, without Drawable, Compose state
or a live session token. The owner records a profile id, component, SHA-256 profile baseline and
SHA-256 original-icon baseline. A separate persistent UUID identifies the local storage
generation; committed imports and Previous state rotate it before mutation. Rollback and confirmed
keep-current repair attempt rotation with the emergency fallback described above.

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
it does not recursively delete directories. Storage recovery and profile-deletion fallback may
remove the entire owned draft namespace after a failed rotation. Orphans are pruned before/after successful saves
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
valid draft, then explicitly rotate through the inspected `discardUnreadable` lease. After a
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
must stop elective imports/Previous-state commits before they begin. Emergency rollback,
startup recovery and keep-current repair use best-effort cleanup and block stale draft access
within the process instead of trapping the application in recovery. Explicit discard and old ViewModel cleanup
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
