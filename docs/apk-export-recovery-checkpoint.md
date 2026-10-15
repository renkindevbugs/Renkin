# APK export recovery checkpoint — 2026-10-15

Branch: `fix/apk-export-recovery`, based on `dev` at `93a6b562`.

## Changes

- Reserve the failed build's artifact before opening SAF, rather than when its result returns.
  Independent reservation tokens protect overlapping pickers without reclaiming installer ownership.
- Save only the source URI and reservation token in the MainViewModel's SavedStateHandle. A disk
  marker keeps that reservation across process death and another build's pruning. Abandoned
  markers expire after the existing 24-hour orphan grace period.
- Cancellation, picker-launch failure, ViewModel clearing and completed copies release the
  reservation. Copying retains an additional in-memory pin until all bytes have been written.
- An orphaned picker result or missing source removes its empty document and reports failure.
- A generic BLOCKED result gets neutral restriction guidance. Samsung Auto Blocker advice requires
  an explicit Auto Blocker detail as well as a blocked result and Samsung manufacturer. Guidance
  reads the final attempt's detail, rather than arbitrary labels or earlier attempts in the report.
- Installation confirmation, profile persistence, signing, Room and draft storage are unchanged.

## Validation

Targeted regression tests cover parcelled handoff restoration, prune before copying, process-level
artifact-manager recreation, overlapping picker reservations, cancellation/failure cleanup, expired
orphans and malformed tokens. Existing coordinator and installation suites remain applicable.

`ApkExportDeviceTest` runs only against the debug package. Its picker tests use synthetic failed-build
fixtures, create their own SAF documents and remove them after checking the bytes. No installer is
started and no generated icon pack is installed. The restoration test reconstructs the saved state
and artifact manager; it does not itself kill Android's app process.

Verification: `compileDebugKotlin`, all 1,371 unit tests across 187 suites (zero failures, errors or
skips), `assembleDebug` and `assembleDebugAndroidTest` passed. On a OnePlus CPH2663 / Android 16,
all six device tests passed in 12.28 seconds:

1. SAF saved exactly the fixture bytes while another build pruned completed artifacts.
2. The actual Save APK button opened SAF; cancelling returned to the usable failure report and
   released the reservation without deleting its source.
3. A parcelled handoff and reconstructed artifact manager retained and exported the same source.
4. Missing source cleanup removed the new empty document and released its reservation.
5. Missing handoff cleanup removed the new empty document.
6. Samsung BLOCKED policy input selected neutral help; explicit Auto Blocker input selected its
   specific advice. This verifies the classifier, not a real Samsung failure.

SAF automation uses resource IDs because the device's document picker is Slovak, and Back can
navigate through folders before cancelling. ExternalStorageProvider revokes the URI grant after
deletion, which the cleanup assertions handle as well as FileNotFoundException. No commit or
merge is approved by this checkpoint alone.

The debug app's original databases, files and shared preferences were restored after testing.
All 35 regular files matched the backup byte-for-byte, including mode/owner/timestamp metadata;
Room remained at version 21 with 6 profiles and 938 icon rows. The helper test APK was removed.
The release app and installed icon packs were not touched.

## Manual device checks

1. From an installation failure, choose Save APK and cancel the document picker. The report remains
   usable; no success toast appears and its reservation is released.
2. Save the failed APK to Downloads. The file matches the generated APK and the success toast appears.
3. Keep the picker open while another build prunes completed artifacts. The pending source survives.
4. With the picker open, let Android terminate Renkin's background process, then save. The restored
   activity consumes the saved URI/token and exports the same source without restoring the build.
5. Return a picker result without a valid handoff/source. The empty document is removed and the
   failed-save toast appears.
6. A generic blocked Samsung install shows neutral restriction advice. Only an explicit Auto Blocker
   detail produces Auto Blocker advice.
