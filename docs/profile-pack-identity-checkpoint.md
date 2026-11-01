# Profile pack identity (issue #26)

Profiles can customize the application icon and applicationId of their generated APK.
The existing `packLabel` remains the Android-visible application label. Default and named
profiles can edit these settings; the default profile remains undeletable.

## Ownership

`ProfileDetailsViewModel` owns the create/edit form and its separate `EditorSession`.
The shared `OptionsDialog` uses `EditorPurpose.PROFILE_ICON` to browse arbitrary pack icons
without app-component matching or calendar behavior. Apply stages a detached result in the
form; Save writes the profile through MainViewModel, ApplicationProvider and ProfileManager.
Cancel does not change the stored profile. Native artwork never enters a Bundle.

`ProfilePackIconCodec` stores the rendered artwork, attribution and optional modifier input
as one self-contained document capped at 768 KiB. It reuses drawable serialization and
modifier-input codecs, preserving adaptive layers. No gallery path, source-pack resource or
external modifier-input file is required when reopening or transferring the profile.

Room 23 adds `Profile.customPackageName` and `Profile.packIcon`, both defaulting to empty.
Migration 22 to 23 and audited recovery omissions preserve existing profiles. Full backups
and shared profiles carry the optional fields in the existing portable format. A shared
profile whose custom applicationId conflicts falls back to the new profile's automatic ID.
Full restore validates IDs and cross-profile collisions before replacing storage.

## APK identity

`automaticPackPackageName` is the shared default policy. Save and build validate overrides
against other profiles, reserved automatic IDs and installed foreign applications.
Changing applicationId creates another installable package; the previous package is retained.
Install and Replace recheck foreign custom targets and cannot uninstall an unrelated app.

Generated APKs carry a bounded format marker. Discovery uses that marker for custom IDs;
format recognition does not grant source-pack verdict exemptions. A local signing ledger and
the installed certificate identify locally built packages. The restored pack keystore also
supports ownership checks after a full backup restore. Historical ledger entries are retained.
The smali activity FQN stays `dev.renkinProject.renkinpack.MainActivity`.

The builder captures the selected icon with the immutable build input, exports adaptive
layers with a raster fallback or a legacy bitmap, and sets both manifest icon attributes.
Its application-icon source is included in the existing provenance map. Profile-icon origins
participate in backup source metadata and verdict verification. Newer profile metadata bumps
the provider's saved revision so an earlier installer completion cannot mark it built.
Both default and custom icons write the roundIcon reference explicitly because ARSCLib 1.3.5's
round-icon setter incorrectly reuses the icon attribute name. Resource regression tests check
the actual manifest references and versioned adaptive resources.

## Verification checkpoint

The developer confirmed device behavior on 2026-10-30 and authorized regression tests,
commit and merge to dev. Room schema 23 is included. Verification covers the retained profile
form, automatic package-name prefill/reset, codec artwork and modifier inputs, migration and
recovery, backup/share transfer, package-name collisions, signing ownership, provenance and
legacy/adaptive APK application-icon resources.

Device scenarios:

1. Upgrade existing data: profiles and saved artwork remain intact and automatic IDs stay the same.
2. Edit the default and a named profile: select a pack icon, an adaptive icon and an upload;
   Apply returns to the form and Save retains the result after reopening and restarting.
3. Cancel the icon editor, then cancel the profile form: the previous saved icon remains.
   Recreate the activity while editing: the form, artwork and open editor remain coherent.
4. Build and install: Android's App info and launcher show the chosen label and application icon.
   Repeat with adaptive and non-adaptive artwork, and Reset to default.
5. Change Advanced package name: a separate APK installs, the old package remains, and rebuilding
   with the same new ID updates that package. Reset to automatic targets the original ID again.
6. Invalid IDs, another profile's ID and an installed foreign application's ID are rejected;
   the form retains its inputs and no unrelated uninstall is offered.
7. Create a profile with unsaved current icons: Save/Don't save and cancel in the existing switch
   prompt retain the submitted icon and produce at most one new profile.
8. Share/import and full backup/restore retain the icon after removing its original upload.
   Duplicate imports do not overwrite another profile's installed pack. Imported source-pack
   restrictions apply to the profile icon as they do to ordinary artwork.
9. Select a generated pack's own application icon elsewhere: its original pack attribution is
   retained. Custom-ID generated packs do not appear as new foreign packs or Watch candidates.
