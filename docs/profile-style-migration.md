# Profile style migration — phase 7A

## Current boundary: 7A4

Room is version 21. `Profile.prefsSnapshot` is an unversioned JSON object containing
the registered `ProfilePrefKeys`. `Profile.profileStyle` becomes authoritative after the shared
bootstrap; DataStore holds application settings plus a derived active-profile UI projection.
Phase 7A1 isolated the existing wire format
in `LegacyProfilePreferences`, without changing its fields, defaults, locking or consumers.
`DataPreferences` retains the typed key registry and the storage/preference write boundaries.

Phase 7A2 adds an immutable `ProfileStyle`, `ProfileStylePreferences` and a version-1
`ProfileStyleCodec`. Snapshot creation now captures stored preferences into that model and
projects them back through the legacy encoder. No new document is persisted: profile switches,
builds and exports still write the same unversioned legacy JSON. Replacement and inactive-profile
baseline patches retain the 7A1 adapter. The 7A2 checkpoint kept Room at 20 and portable backups at 3.

Phase 7A3 appends `Profile.profileStyle` as TEXT NOT NULL DEFAULT ''. Migration 20 → 21
does no backfill. At that checkpoint existing profiles and creation/import paths left the column
empty, and DataStore/`prefsSnapshot` retained their previous ownership. Portable export stays
at format 3. The shared bootstrap and routing of every style writer now form the 7A4 checkpoint.

`ProfileStyleStore` is the singleton owner, composed by Hilt from the shared repository and
settings DataStore. The early `ProfileStyleAccess` adapter routes existing DataPreferences
setters to it for that settings instance; standalone/staged DataStores retain their own behavior.
All typed/legacy pairs are written atomically through `ProfileDao.updateStyle` in one Room
transaction. Metadata-only repository updates preserve that pair from the current row.

After pending recovery, bootstrap captures one active id/preference snapshot, validates all
candidate documents, then commits only changed rows. An empty active row uses live DataStore
values; empty inactive rows use legacy snapshots. Non-empty documents remain authoritative,
including on restart. The active UI projection is derived from the winning Room document.
Absent values, disabled settings and unrecognized legacy ordinals retain their stored form.
Conversion validates the actual complete encoded size before any style row is written; oversized
or invalid input blocks initialization and preserves the original data for a reported failure.
The confirmed keep-current action is the explicit escape for a damaged non-empty style document:
validate its legacy snapshot strictly (known unique keys, correct primitive types, integer and size
limits, Unicode and complete JSON), encode every replacement, then preserve the exact current
Profile rows in abandoned recovery storage before writing repairs in one transaction. The diagnostic
`profiles.json` uses the raw column/value writer but omits icon tables; full-snapshot compatibility
and restore continue to require every table. The keep-current confirmation also explains that moving
the recovery directory makes Previous state unavailable. Valid typed
documents are untouched. If the legacy copy is also invalid, repair fails without clearing values.
Normal startup, recovery retry and imports never automatically fall back to a legacy copy.

Normal preference edits, source/colour bundles, Global Save, new profiles, switches and captured
built-primary updates use this owner. Codecs and repository calls run off Main under the existing
storage boundary; accepted Room/projection commits finish non-cancellably. A failed projection
after a normal style write marks recovery required so stale values cannot remain available.
Application-only edits and profile selections that did not write Room propagate DataStore failures
without blocking the application. The initialization cache is invalidated after a committed style's
projection failure so retry really re-projects the authoritative Room state.
Bootstrap inside import/recovery lets the existing rollback/retry boundary handle its failure.

Application, UI/provider snapshots, WatchWorker and AutoBackupWorker share the early recovery
barrier. UI preference flows pause whenever storage is unavailable. Full/shared import, rollback,
Previous state and recovery abandonment force initialization after restoring rows/preferences,
before releasing the gate. Storage replacement changes the epoch and pauses observers until
the outer operation finishes; initialization is never assumed to be startup-only.
The observer-pause signal is independent of the recovery-screen state. A normal import keeps
the current composition, dialogs, scroll and phased restore progress; only actual startup/recovery
uses the recovery screen. The outer gate resumes observers after successful commit or rollback.

Room remains at 21, and format-3 full/shared exports use the derived legacy representation.
Only existing representable fields are introduced in this checkpoint. Format 4 and removal of
the compatibility projection remain later work. A 7A3 build can open Room 21 but cannot keep
typed documents current when editing settings; switching back to 7A4 trusts those documents.
Use a forward fix or an explicit legacy-backup import to carry such older-build edits across.

After device confirmation, `ProfileStyleStoreTest` installs the bridge with real Room storage and
injectable preference writes. It covers active live values versus stale snapshots, every registered
setter, all-or-nothing validation/Room failure, restarts, cancellation before/after acceptance,
projection failure/retry, metadata copies, new profiles and concurrent worker/Application first access.
It verifies application-only changes never update Room, failed application settings/selection do not
require recovery, and repair preserves originals and unknown ordinals while rejecting invalid legacy
copies or failed preservation. `StorageOperationGateTest` covers observer-only pauses and resumption
after successful operations/rollback, plus blocking after rollback failure. `BackupRecoveryTest`
installs the production bridge for full/shared imports, cold worker-first v20 recovery, Previous
state → bootstrap → restart → redo, typed-style rollback and Profile-only retained diagnostics/deletion.
Existing adapter tests retain regression coverage for the standalone-store path.

Internal recovery composes explicit per-table additions for 18 → 19, 19 → 20 and 20 → 21.
Restoring a v18/v19/v20 snapshot into v21 inserts the restored Profile rows with an empty
`profileStyle`, even if the replaced rows previously carried a value. Same-version v21 recovery
preserves the column exactly. Checks still require exact table/column order and audited
TEXT/NOT NULL/empty-string defaults; unknown version transitions and unrelated additions fail.
The unchanged Watch database uses the same checker without receiving the Profile exception.

Room's exported 21.json accompanies the migration. `RenkinPackMigrationTest` covers v20 and
earlier upgrades, preserving all profile values and defaulting the new column without backfill.
`BackupDatabaseSnapshotTest` covers v18/v19/v20 → v21, exact v21 restore, and rejection of
wrong/default/extra columns or unaudited transitions. `BackupRecoveryTest` covers v20 Previous
state, v21 redo and pending v20 recovery before the first writer through the shared store.

`ProfileStyleSchema` assigns all 64 registered keys to typed fields and wire paths. The same
descriptors drive legacy projection and versioned encoding/decoding. Complete, unique key and
path coverage is a test-time obligation, without assertions in the production object initializer.
`ProfileStylePreferencesTest` checks classification and a `Preferences -> capture -> toPreferences`
round-trip for every registered key with distinct integer/string values and isolated true/false
Boolean cases. Each key's presence is checked independently as well as in a populated profile,
so swapped or omitted getters cannot hide behind matching key sets or identical test values.
Coverage includes missing values, disabled configuration, capture immutability, byte-identical
legacy encoding and every known enum value plus unknown ordinals. Future registry changes must
pass these tests before shipping. `ProfileStyleCodecTest` covers strict parsing, Unicode,
canonical enum forms, deep foreign trees and exact character/UTF-8 size boundaries.

The version-1 codec requires the five root groups and an integer version. Absent scalar fields
remain absent; explicit null, wrong types, duplicate names, dotted-name aliases, unknown fields,
unknown enum names, trailing content and unsupported versions fail. Known enum values use names;
unrecognized legacy ordinals use a tagged `legacyOrdinal` object. The parser follows only known
schema paths, limiting nesting before arbitrary JSON trees can be allocated, and caps fields
at 256. It requires valid Unicode and limits both input/output to 262,144 UTF-16 code units and
262,144 UTF-8 bytes. Encoding checks cumulative string size before creating the complete JSON.
The limits coincide for ASCII; UTF-8 bytes are stricter for multibyte text, while the character
check rejects oversized strings before encoding allocations.

A schema-based populated size estimate with all 64 fields, ten-stop gradients, a 512-character
font filename and 255-character pack strings is 3,861 ASCII bytes. The 256 KiB ceiling provides
headroom while bounding foreign documents. Existing legacy snapshots remain unbounded by this
codec in 7A2; inspect real snapshot sizes before storage cutover. An oversized legacy profile
must remain available with a reported conversion failure, rather than being truncated or dropped.

Snapshot encoding includes only present registered profile keys. Replacement clears missing,
malformed or wrongly typed profile values and leaves application preferences untouched. Boolean
and String values require their actual JSON types; integer values accept Int or an in-range Long.
Unknown keys are ignored on replacement. Updating an inactive profile's built primary selection
patches only its two baseline fields and preserves every other JSON field, including unknown ones.
This distinction is part of the existing compatibility contract.

The sections below describe the storage rollout beyond this model/codec boundary.

## Stored model

Use a versioned profile document whose style has separate source, modifier and export groups.
Keep operation policy and the last built primary selection as distinct groups alongside the
style. They must not be inferred from rendered artwork or collapsed into modifier state.

| Group | Existing settings to retain |
| --- | --- |
| Source | Primary and secondary source/pack/text type, fallback source, configured font path |
| Generation modifiers | Primary and secondary image effect, icon colour and gradient configuration |
| Global modifiers | Shape/crop/scale and plate style, icon scale, colourizer and its flags, outline and its style |
| Export | Vector, monochrome/Material You and themed choices, background colour and gradient configuration |
| Operation policy | Override-existing, calendar retrieval, Global Apply Generated/Existing/Custom/Empty targets |
| Built primary selection | Presence and values of `BuiltPrimarySourceKey` and `BuiltPrimaryIconPackKey` |

Every current `ProfilePrefKeys` entry must map exactly once. Application settings remain outside
the document: theme, installer choice/confirmation, sort/filter, onboarding/release-note/share
preferences, watch schedule/check timestamp, automatic-backup settings/timestamp and active
profile selection. Preset libraries remain device-wide Room tables. Profile name/description,
pack label, unbuilt-change flag and missing-pack warning remain profile row metadata.

The stored model describes configured values, including disabled settings. It is separate from
`GenerationOptions`, `globalModifierOptions` and the local editor's `ModifierStack` recipe:
those are execution projections and may resolve defaults, theme colours, font availability,
path relevance or an inactive shape fill. Persisting those projections would freeze dynamic
defaults or lose settings when a toggle is disabled. Generation effects and the explicit final
global layer must remain separate to preserve Apply and refresh behaviour.

Represent absence explicitly. Missing colours remain theme-dependent rather than becoming
resolved ARGB values. Preserve configured font paths even when the file is currently missing.
Preserve disabled gradient, shape and export settings. Store new enum values with stable names;
the legacy adapter must retain unrecognized old ordinals explicitly until replaced, while the
execution projection uses the existing enum fallback. Retain stored numeric values and apply
the existing render/UI normalization at projection time. Do not silently normalize a profile
merely by opening or switching it.

The document has an explicit version and bounded character/UTF-8 size. Confirm the codec ceiling
against real existing snapshots before storage rollout. Unsupported versions and oversized or invalid
documents must report a storage/import failure and preserve the original data; they must never
silently become an empty profile. Local malformed legacy snapshots retain today's clear-to-default
behaviour; backup validation remains stricter and rejects wrongly typed profile fields.

## Persistence rollout

1. Add the immutable model, bounded versioned codec and adapters between the existing registered
   preferences and the new model. First verify presence and disabled-value round-trips without
   changing persisted documents or the active settings owner. At this step's test checkpoint,
   add a classification test comparing the actual `ProfilePrefKeys` registry with the model's
   group mappings: every registered key must occur exactly once, no application key may occur,
   and no mapped key may be absent from the registry. Adding a registered key without classifying
   it, or classifying one twice, must fail this test.
2. Append a `profileStyle` TEXT column with an empty-string default in Room 21. Commit its Room
   schema and migration; retain `prefsSnapshot` during the transition. SQL only adds the column:
   it cannot reconstruct the active profile from DataStore, and must not backfill stale data.
3. After recovery, bootstrap under the existing storage and preference boundaries. Read the
   active id and a single DataStore snapshot. For an unconverted active profile, use those live
   profile preferences, not its potentially stale Room snapshot. Convert unconverted inactive
   profiles from their Room snapshots. Validate all candidates before a single Room transaction
   writes both each typed document and its legacy `prefsSnapshot` projection. A non-empty style
   document becomes authoritative for its row; a restart must never overwrite it from the
   obsolete DataStore projection. Failure before commit leaves all original data available.
   All entry points use the shared bootstrap barrier described below.
4. Route profile style writes and captured operation snapshots through Room under the existing
   provider ownership checks. Switch selects the target style; it no longer needs to save a
   leaving DataStore style. A temporary preference projection may support existing UI readers,
   but it must be derived from Room and cannot accept independent writes. Retire that projection
   after the UI and workers use the same typed owner. DataStore then owns application settings.

Steps 3 and 4 form one authority-cutover checkpoint: do not ship authoritative typed documents
while legacy UI or worker setters can still write independently to DataStore. The model/codec
and additive schema steps can be reviewed and tested separately before that cutover.

During the transition, every accepted style or built-baseline change updates `profileStyle` and
its derived `prefsSnapshot` in the same Room transaction, including inactive profiles, bootstrap
and imports. The typed document is the owner; the legacy snapshot is an output projection and
cannot be edited independently. Until format 4 is enabled, format-3 export must use a current
projection for every profile and the matching active-profile preference snapshot. Only settings
that round-trip through the legacy representation may be introduced while exporting format 3;
new unrepresentable settings require format 4 first.

Stop writing the legacy projection in a separate cleanup change after format 4 is the sole
export format, every production reader/writer and worker uses the typed owner, and legacy import
plus recovery adapters have passed their compatibility tests. That change also removes the
obsolete Room column with an explicit schema migration and updates recovery compatibility.
Keep legacy decoders for supported imports/recovery. Removing the DataStore UI projection alone
does not satisfy this retirement gate. No destructive database downgrade is provided: a Room-20
build cannot open the upgraded Room-21 database even while `prefsSnapshot` remains current.

Each independently shippable step has its own device checkpoint. New profile creation,
duplication, shared-profile import and full restore must initialize or convert the style through
the same boundary. No startup-only migration may be assumed to cover imports in a running process.

## Startup and background entry points

Use one application-scoped, idempotent storage/bootstrap barrier for `RenkinApplication`,
`WatchWorker`, `AutoBackupWorker`, UI/profile readers and manual backup operations. Register it
in `RenkinApplication.attachBaseContext` before ContentProviders can start WorkManager, extending
the current early recovery barrier. It must finish pending recovery first, then profile bootstrap,
before publishing storage as ready or allowing style reads/writes. `Application.onCreate` may
trigger it eagerly; each worker/backup entry point must also await the same barrier and cannot
assume that asynchronous Application startup has completed. It must not depend on MainViewModel
or construct ApplicationProvider to initialize storage.

Concurrent first callers share the initialization and serialize through the storage boundary.
Bootstrap failure keeps storage unavailable, preserves original data and follows the recovery/
retry path; it cannot mark initialization complete or let a worker export stale settings. A
storage replacement invalidates initialization with its epoch and requires conversion of newly
restored/imported legacy rows before consumers resume. Test cold worker-first startup without
an Activity, concurrent Application/worker startup, initialization failure/retry and restore.
The current workers run in the main application process without UI; if a separate Android
process is introduced later, a process-local singleton/mutex alone is insufficient and requires
an explicit cross-process initialization protocol before that process accesses profile storage.

## Backup, recovery and operation ownership

The current portable backup format is **3**, including watch state. Enable **format 4** with
typed-style export for both full backups and shared profiles. Format 4 carries the versioned
typed document in a dedicated `profileStyle` field; retain the current legacy `prefsSnapshot`
projection only until the retirement gate above. Readers supporting formats 1–3 must reject the entire
format-4 archive through the manifest version check, as the current reader already does for
newer formats. The retained legacy field does not promise partial import into an older app.
No implicit format downgrade or best-effort partial import is provided.

Format-4 readers continue accepting legacy formats 1–3 through their existing validators and
the legacy-to-typed adapter. In a format-4 archive the typed document is required, validated and
authoritative; invalid/unsupported typed data cannot fall back to the legacy projection. While
both fields are emitted, validation must reject a mismatching projection. Do not place a new
document in the old `prefsSnapshot` field where an older reader would silently ignore it.
Full backup must capture the active and inactive authoritative styles; shared profiles still
exclude library presets and pre-modifier source files. Dynamic values remain unresolved in both
kinds of export.

Recovery snapshots describe the actual Room columns. The 18 → 19 → 20 → 21 compatibility
adapter permits only audited additions to their specific tables, including `Profile.profileStyle`.
Restoring a supported v20 snapshot into v21 must clear the new column
for restored rows and then bootstrap from the restored DB plus restored preferences. Extend
the audited 18/19 chains deliberately. A v21 snapshot restores its typed documents exactly.
Previous state, pending rollback and abandoned-recovery handling must use the same rules.
Do not generalize compatibility to arbitrary ADD COLUMN migrations.

At the authority-cutover checkpoint, explicitly test Previous state → bootstrap in a running
process: start with converted profiles, restore a retained v20 snapshot and its preferences,
then require the shared bootstrap to convert the restored rows before readers/writers resume.
The active profile must use restored DataStore preferences, inactive profiles their restored
legacy snapshots, and no typed style from before the restore may survive. Restart must preserve
the converted restored state without repeating conversion from an obsolete preference projection.

Keep storage → provider/profile lock ordering, preference serialization, cancellation and the
restore preparation/commit boundary. Long render/build/watch jobs capture an immutable typed
style together with the existing profile/session token and storage epoch. Commit compares the
owning style/revision and rejects stale work. Installer completion updates the captured profile's
built baseline, even when another profile is active; it cannot overwrite newer style edits.
The build's rendered icon snapshot and exact editor/watch Apply contract remain unchanged.

Verification must cover every registered key and presence state, unresolved theme defaults,
disabled settings, legacy enum fallback, profile-switch write races, active bootstrap versus
stale Room data, interrupted bootstrap, new/shared/full profile import, explicit supported
recovery chains, previous state and stale render/build/watch commits. Add those tests only at
the corresponding device-confirmation checkpoints.

## Document lifetime

Commit this file with 7A1 as the persistent migration design, linked from `ARCHITECTURE.md`.
Update it as decisions are implemented; it is not a temporary device checklist. At completion
of 7A, consolidate the final storage, startup and compatibility contracts into `ARCHITECTURE.md`
and remove this rollout document in the same documentation change, updating its links.
