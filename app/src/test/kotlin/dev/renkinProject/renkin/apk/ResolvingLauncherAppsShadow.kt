package dev.renkinProject.renkin.apk

import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.UserHandle
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps

// Robolectric leaves resolveActivity to the absent system service; installed icons resolve their
// component through it, so tests answer from the activities registered with addActivity.
@Implements(LauncherApps::class)
class ResolvingLauncherAppsShadow : ShadowLauncherApps() {
    @Implementation
    protected fun resolveActivity(intent: Intent, user: UserHandle): LauncherActivityInfo? {
        val component = intent.component ?: return null
        return getActivityList(component.packageName, user).firstOrNull { it.componentName == component }
    }
}
