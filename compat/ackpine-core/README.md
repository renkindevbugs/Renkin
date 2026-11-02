# Ackpine confirmation compatibility build

This module replaces only `ru.solrudev.ackpine:ackpine-core:0.25.4` with a source build.
The existing API, runtime, KTX and Shizuku artifacts keep their pinned upstream version.
Renkin's app architecture, Room schema and installer state ownership do not change.

Upstream: https://github.com/solrudev/Ackpine
Tag: `0.25.4`
Commit: `6fe77aa6814a95a907ec948e58ccc0287e4479b5`
Source checkout: `third_party/ackpine-source` (Git submodule).
License: Apache-2.0; see `third_party/ackpine-source/LICENSE` and the retained source headers.

Initialize sources with `git submodule update --init --recursive`. CI already checks out
submodules recursively. Source archives must include this pinned submodule as well as the
existing font submodule. This build does not download, publish or rewrite an AAR.

The upstream build uses a newer toolchain, so this adapter uses Renkin's existing AGP,
Kotlin, KSP and Room compiler. Upstream resources, manifest and consumer ProGuard rules
are consumed directly. A generated Kotlin source directory combines the original core
sources with the one patched Activity. The original file's normalized SHA-256 and the
catalog version are checked before replacement; an upstream update requires review.
Source preparation also adds explicit Boolean return types to three private session helpers
for Kotlin 2.3 and selects Room's explicit `dropAllTables = false` overload, preserving the
legacy migration behavior. The pinned upstream checkout is unchanged.
The Kotlin module name stays `ackpine-core` to preserve internal JVM member names used
by published artifacts. Plugin references are checked against the rebuilt classes.

The debug variant also includes a small verification Activity and an upstream fake session
fixture. It exercises the real confirmation Activity with a deliberately missing target in
the debug app's own package. Neither verification class is included in release builds.

## Local source change, 2026-10-17

`patched/kotlin/.../SessionBasedInstallConfirmationActivity.kt` is the upstream file with
one added `ActivityNotFoundException` import and a catch around the actual confirmation
launch. It completes the owning session exceptionally and finishes its Activity instead
of crashing the app. Cancellation, successful confirmation and restored-Activity behavior
remain upstream code. Review with:

```
git diff --no-index third_party/ackpine-source/ackpine-core/src/main/kotlin/ru/solrudev/ackpine/impl/installer/activity/SessionBasedInstallConfirmationActivity.kt compat/ackpine-core/patched/kotlin/ru/solrudev/ackpine/impl/installer/activity/SessionBasedInstallConfirmationActivity.kt
```

The app depends directly on this adapter and excludes the published `ackpine-core` module from
its configurations, including transitive references from installer plugins. This avoids the
early resolution caused by global project-substitution rules. Verify with
`:app:dependencyInsight --dependency ackpine-core --configuration debugRuntimeClasspath`.
Remove the adapter, exclusion and submodule when a reviewed upstream release includes
the guard. No upstream fork or pull request is published by this change.
