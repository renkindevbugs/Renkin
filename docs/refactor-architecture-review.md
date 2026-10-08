# Refactor architecture assessment

Reviewed after 7B4 (`f031a40`, 2026-10-08). This is a review of ownership, dependencies,
state publication and representative editor/build/profile/transfer/watch paths across the
project. It is not a line-by-line correctness, security or performance audit. Structural graph
results were checked against source; graph edges alone do not establish runtime call paths.

This is a historical assessment of that commit, not a live status document. Maintain current
contracts and roadmap progress in `ARCHITECTURE.md`. At the end of 7D, reconcile its remaining recommendations with the current code,
retain applicable decisions in `ARCHITECTURE.md` and retire this report.

## Assessment

The refactor materially improves the architecture. Continue it in small checkpoints. The
strongest changes define who owns data and when work may commit; they do more than relocate
methods. The architecture remains transitional, particularly around provider state, memory
usage and durable drafts. Passing tests establish the covered contracts, not smooth scrolling,
bounded heap usage on every device or release-build correctness.

| Boundary | Evidence in the current implementation | Architectural benefit |
| --- | --- | --- |
| Profile configuration | `ProfileStyleStore`, typed model/schema/codec, Room document, application-only DataStore | One persistent owner; explicit bootstrap, compatibility projections and recovery policy |
| Local icon editing | `EditorSessionViewModel`, `EditorSession`, saved modifier input, replay verification, completed render ownership | Apply persists the reviewed result; reopening does not infer a source from baked artwork |
| Long operations | `BuildInstallCoordinator`, `BackupUiCoordinator` | Jobs, prompts, artifacts/staging and cleanup have explicit owners and lifetimes |
| Preset library | `PresetLibraryViewModel` and singleton repository | Shared persisted library without unrelated provider pass-through methods |
| Live applications | `ApplicationListState`, persistent list, profile-gate completion publication | Applications, lock keys, readiness and session describe the same completed operation |
| Delayed work | Session tokens, render tickets, mutation revisions, storage epochs | Old editor/refresh/build results cannot silently become writes to a newer owner |
| Composition root | `di/AppModule.kt` | Core services and repositories are assembled explicitly; tests can substitute dependencies |

Confirmed 7B4 regression coverage
includes real Room/provider mutations, retained snapshots, partial failure, session rejection,
actual diff-flow subscription/dispatcher behavior and Compose lifecycle collection. Test counts
and individual build-run diagnostics belong to verification records rather than this assessment.

## Remaining work, in order

1. **Finish domain state ownership in 7B.** `ApplicationProvider` still uses Compose state for
   startup, profile transitions, session revision, long-operation ownership and unsaved keys.
   `ProfileManager.activeProfileId` also remains Compose state; WatchViewModel bridges it with
   `snapshotFlow`. Move observable domain state to explicit read-only flows while preserving
   coherent publication for related fields. Publishing several independently collected flows
   does not guarantee a single revision. 7B5 starts with Undo and carries it in the application
   aggregate. Compose state local to a ViewModel/UI is not inherently wrong; avoid mechanically
   converting every flag or baseline just to remove an import.

2. **Implement durable editor drafts separately in 7C.** A live editor ViewModel survives
   configuration changes, not process death. A saved modifier recipe/input belongs to a confirmed
   icon; it is not an unsaved draft. Recovery needs a versioned draft record, file ownership,
   cleanup, and explicit validation against the current profile/session/storage. Keep artwork
   out of Bundle and `rememberSaveable`. This phase requires a product decision about which
   draft is offered after restart, rather than reviving an old in-memory session token.

3. **Measure existing artwork and backup memory.** `DbApplication.drawable` and `baseDrawable`
   still store payload strings in Room, and `BackupCodec` builds JSON trees containing them.
   Modifier input files solve only that newer payload boundary. Large-row CursorWindow and
   whole-backup heap risks remain. Measure realistic large profiles, heap and refresh latency
   before choosing a file/streaming migration; preserve backup/recovery ownership throughout.

4. **Close the verification gap in a separate CI/toolchain change.** `.github/workflows/ci.yml`
   compiles Kotlin and runs unit tests. It does not run `lintDebug`, assemble an APK or validate
   R8/release behavior. Add suitable checks without exposing signing secrets. Investigate the
   D8 metadata warnings and JVM stability independently of the state migration.

5. **Move feature packages incrementally in 7D.** `ApplicationProvider` remains a broad
   orchestrator and MainViewModel coordinates many screen operations. Further extraction is
   useful where policy, resource lifetime or work can have an independent owner. Splitting every
   method into a forwarding class would increase navigation without clarifying ownership.
   `DataPreferences.kt` still mixes preference/adaptation and Compose helpers; UI thumbnail
   loading in `UploadWidgets.kt` and construction of some watch collaborators also leave seams
   to review. Package moves should expose the intended dependencies, with compilation/tests
   after each confirmed checkpoint. Separate Gradle modules need a measured reason.

## Constraints to retain

- The storage gate deliberately serializes recovery with normal access; lock order remains
  storage then profile. The reviewed paths do not establish a new deadlock, but this is not a
  proof for every future caller. Never wait for a UI/shared-flow emission while holding the
  gate that emission needs. Keep rendering, signing and SAF copying outside critical sections
  wherever their existing contracts permit it; measure contention rather than removing gates.
- Legacy `prefsSnapshot` is a compatibility/repair projection, not a second authoritative
  model. Keep its paired writes until a separate retirement design covers corrupt-document
  repair, old portable formats, unconverted rows and non-additive recovery migration. The
  process-wide `ProfileStyleAccess` resolver is a bridge worth revisiting after those callers
  migrate; replacing it blindly would bypass initialization/recovery ownership.
- Preserve cancellation at suspend boundaries. Representative editor/coordinator paths now
  propagate it, but this review does not certify every catch in the project. Cleanup/rollback
  under `NonCancellable` is intentional and should not be mechanically removed.
- Keep exact Apply, replay from an immutable input, bounded batch refresh, stale-result rejection
  and artifact cleanup as architectural contracts. Adding an abstraction is worthwhile only
  when it preserves these contracts and removes real duplication or policy coupling.

The remaining main roadmap areas are completion of 7B, 7C and 7D. Legacy-column retirement,
payload/performance work and CI/toolchain hardening are separate follow-ups, not silently included
in the next small checkpoint.
