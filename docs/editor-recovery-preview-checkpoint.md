# Editor recovery preview checkpoint — 2026-10-14

Branch: `feature/editor-recovery-preview`, based on `dev` at `acc638ee`.

## Status

The developer confirmed that the device scenarios they tried work, including the revised recovery
behavior. This does not claim that every scenario below was exercised. The developer subsequently
explicitly authorized verification and merge into dev. Preview and behavior changes are recorded
as separate commits, with a follow-up regression checkpoint for watch notifications. The automatic
blocked-state recheck has regression coverage; no additional device run by the agent is claimed.
Installation priorities
1–2 are already verified and merged separately: change `f3e23d69`, merge `acc638ee`.
Their results do not verify this new recovery UI.

## Presentation

The valid recovery offer uses the existing Material 3 dialog, with the shared CardShape and
IconShape tokens. It shows `Resume icon edit`, an application identity row, the captured profile
name, a large saved icon preview and `Resume edit`, `Not now`, `Discard` actions. The content
scrolls and the secondary actions wrap when space is limited. Confirming discard names the
application. Cancelling that confirmation returns to the same offer.

The small identification icon comes from the validated activity-specific baseline. The large
preview comes only from `bound.capture.snapshot.rendered` and its already bound saved artwork.
It never selects a fresh source or applies current preferences. If no completed render was saved,
the offer explicitly says so; the original app icon does not masquerade as the saved render.
If preparing the optional preview fails, the offer instead says the preview is unavailable and
editing can still resume. No save time is displayed. The filled Resume button is the selected
design's primary action; the secondary actions remain text buttons.
The `Saved preview` caption appears only when there is an actual saved-render image.

## Ownership

Preview preparation runs on Default while the unpublished prepared draft is still owned by the
recovery action. It makes independent immutable bitmap copies: application icon at 128 px and
saved render bounded to 512 px. Compose receives these copies, without a reference to the
preparation's native artwork or a lazy image loader that could access it after disposal.

UI copies are never manually recycled. Their lifetime follows the state/composition/render
consumer references, including an older frame retained while a new offer arrives. Releasing or
transferring the original preparation cannot recycle pixels still used by the dialog.
Dropping a preparation does not hide the current immutable UI snapshot during refresh; the
replacement state is published by the existing action owner. This avoids closing/reopening the
dialog merely to dispose native artwork. Copies that never publish are left to normal GC.

Preview preparation is optional. It catches ordinary exceptions and OutOfMemoryError, logs the
failure and returns null. A valid Ready preparation still publishes Offer and retains its native
ownership for acceptance; the cosmetic failure does not call autosave.unavailable or become a
recovery failure. CancellationException propagates through the existing cancellation/disposal
path. Failure to prepare or validate the actual draft retains its existing error behavior.

Checkpoint, acceptance lease, editor generation, profile/session and storage checks remain in
their existing owners. NeedsProfile never switches profiles silently. Obsolete, Changed, Failed,
cleanup Retry and unreadable-catalog discard guards retain their behavior. No Room, draft codec,
portable backup format or saved artwork changes are included.

Not now/Back/outside dismissal postpones the observed checkpoint for the current profile visit.
Switching onto another profile does not open a foreign draft prompt. Returning to the draft's
owning profile offers it again. Same-profile editor/session refreshes do not count as a new visit.
A fresh app launch with a new owner may also offer it; nothing is written to disk to suppress it
permanently. A new checkpoint remains eligible, and explicit review/Retry resets postponement.
An unreadable catalog stays postponed for the owner lifetime because its profile is unknown.

Blocked autosave cannot be repaired by repeating a save: a foreign checkpoint must be preserved.
The notice therefore offers Review saved edit instead of Try again. Confirmation warns that the
current editor's unapplied changes will be lost. After checking the exact displayed autosave
state and editor lifetime, the owner fences captures, closes the editor and requests recovery
without deleting the foreign checkpoint. For a different profile, this explicit review shows the
existing named-profile explanation; it never switches profiles automatically. Cancelling review
keeps the current editor open. Failed autosave still offers Try again, and confirmed Discard still
keeps the current editor open and enables saving. Stale confirmations cannot close another editor.
The Review callback is mandatory. On runtime/storage observations and subsequent save/flush
requests, a blocked live editor re-inspects the checkpoint once storage and the provider are ready.
An empty checkpoint admits the current editor and resumes saving; an existing foreign checkpoint
remains protected. No polling job or ineffective blocked Retry button is added.

## Manual device checks

Use a disposable test profile and edit. Preserve existing personal drafts.

1. Change a test icon and background the app long enough for autosave, then stop/reopen Renkin.
   Expect the application name, its small original icon, the correct profile and a large preview
   matching the saved edited icon.
2. Choose `Resume edit`. Expect the same saved result and controls in the editor, with no default
   source briefly replacing the saved appearance. Closing the restored editor retains the
   existing owned-checkpoint cleanup behavior.
3. Create another test draft, reopen and choose `Not now` or dismiss the offer with Back/outside.
   Stay on that profile and expect no repeated offer. Switch between unrelated profiles and
   expect no foreign recovery dialog. Return to the owning profile and expect the same draft
   offer again without deletion. Repeat, then restart on the owning profile and expect the offer.
4. Choose `Discard`. Expect the confirmation to name the application. Cancel and expect the
   same offer; confirm on a disposable draft and expect it to disappear without changing saved
   profile icons.
5. For a draft belonging to a different profile, open Renkin on another test profile. Expect no
   automatic recovery prompt or profile switch. Switch to the owning profile and expect the offer.
6. Exercise light/dark mode and larger font size. Expect readable application/profile text,
   accessible actions, scrollable content and faithful saved artwork, without regeneration.
7. Repeatedly postpone/reopen/resume test drafts and use Retry if an existing error occurs.
   Expect no recycled-bitmap crash, no previous draft's image in a new offer, and no stale discard
   confirmation acting on a replaced checkpoint. Do not corrupt private data to force an error.
8. Change an icon setting and immediately background the app before rendering settles, then
   reopen after process termination. Compare the saved preview with the resumed controls/result.
   The preview represents the last saved completed render, not a newly generated interpretation
   of unfinished inputs. Record whether additional explanation is needed.
9. Postpone a disposable draft, then open a new icon editor and change a setting. Expect the
   blocked notice to offer Review saved edit and Discard, without an ineffective Try again.
   Cancel review confirmation: the current editor and its changes remain. Confirm review:
   the current editor closes and the preserved draft offer opens. Resume and verify its saved
   contents. Repeat from a different profile: review names the owning profile and does not switch.
10. On a disposable blocked draft, confirm Discard instead. Expect the current editor and its
    changes to remain, with recovery saves enabled. Real save failures retain Try again.
11. If a supported storage recovery removes the foreign checkpoint while an editor remains open,
    expect the next valid observation/edit/background flush to recheck and resume saving without
    requiring Review or discarding the current edit. If that operation expires the profile/storage
    session, the existing expired-editor handling applies. Do not delete private files manually.
12. Open a New icon watch notification while a draft exists. Dismissing the suggestion must keep
    the checkpoint. Confirming the suggestion intentionally changes the profile icon, but does not
    replace the draft. Restore alone must not apply the draft over that icon. After saving a changed
    target baseline, expect the existing Obsolete protection rather than automatic restoration.

Sixteen initial regression scenarios cover detached immutable preview pixels after preparation
disposal and transfer/close; preview exceptions/OOM without recovery/autosave failure; cancellation
propagation; named discard and missing-preview captions; profile-visit postponement and unreadable
catalog postponement; blocked review confirmation/cancellation and stale state/lifetime rejection;
automatic recheck after an external clear and preservation of surviving foreign checkpoints.
The previous owner, recovery, codec and Failed retry scenarios also remain green. The automatic
foreign-profile test now checks silence until explicit review, then the named-profile explanation.

Four follow-up watch cases verify that applying a reviewed suggestion leaves the draft checkpoint
unchanged, restoring an offered draft does not itself replace the newly selected profile icon,
persisting a change to the draft's application expires its original baseline without deleting it,
and a different application remains independent when the draft baseline was already persisted.
The first profile save may create a previously absent target row even after a watch change to a
different application; that conservatively makes the draft Obsolete, also without deleting it.

Verification on 2026-10-14:

- `compileDebugKotlin`: passed.
- Initial targeted owner/dialog/notice tests: 59 passed.
- Follow-up owner/watch-apply integrity tests: 73 passed.
- Initial `testDebugUnitTest`: 1,326 tests across 180 suites, zero failures/errors/skips.
- Follow-up `testDebugUnitTest`: 1,330 tests across 180 suites, zero failures/errors/skips.
- `assembleDebug`: passed. No installation was performed.
- `git diff --check`: passed.

Tests use the existing local ignored `build/verify-jvm.gradle` JVM workaround. Logs are ignored
local files `build/editor-recovery-targeted.log`, `build/editor-recovery-verification.log`,
`build/editor-watch-targeted.log` and `build/editor-watch-verification.log`.
Existing BuildInstallCoordinator NonCancellable-launch deprecation warnings remain outside scope.
Merge into dev is explicitly authorized after the follow-up checks. No push or main release is
authorized by that merge instruction.

## Release note draft

Recovery offers show the saved icon preview and application/profile identity. Dismissing an offer
keeps it quiet until returning to its owning profile; other profiles do not open unsolicited recovery
dialogs. Blocked editors can review the saved edit or explicitly discard it to enable autosave.
At release time, add only verified behavior to the versioned fastlane changelog. No root changelog
or speculative release entry is created for this checkpoint.

## Remaining work

After device confirmation, regression checks and merge of priority 3, continue with separate
checkpoints in this order:

1. Original application-icon memory: measure source acquisition and reload overlap, then separate
   small UI previews from on-demand full sources. Preserve activity/user context, badges,
   fallback, adaptive/monochrome layers and exact Apply/Undo/replay/recovery. PERF4's measured
   130.4 MiB catalog inventory is a baseline, not an optimization already delivered.
2. Backup/export memory: collect safe representative measurements before choosing streaming or
   file-backed payload changes. Preserve portable backup formats 1–4. Private-profile export
   remains unmeasured; prior approval restrictions must not be bypassed.
3. CI/release completion: inspect current GitHub/Linux runs and artifacts before claiming they
   are absent or complete; resolve Kotlin/R8 metadata warnings and the reproducible R8 memory
   budget, then validate the signed optimized runtime on-device.
4. Final `prefsSnapshot` retirement: only after its retirement gate, an authorized backup and an
   explicit Room 22 migration. Keep legacy import/recovery adapters and typed-style authority.

Architecture residual dark-mode recipe validation, visual cache keys and presentation/visibility
seams remain in [ARCHITECTURE.md](ARCHITECTURE.md); they are separate from these UX changes.

The completed 7C/7D, PERF4 and Macrobenchmark/Compose audit are not reopened. The complete
installation verification and backlog context remain in
[install-confirmation-checkpoint.md](install-confirmation-checkpoint.md).
