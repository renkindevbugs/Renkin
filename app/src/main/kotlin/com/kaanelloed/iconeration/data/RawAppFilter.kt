package com.kaanelloed.iconeration.data

import com.kaanelloed.iconeration.packages.PackageInfoStruct

abstract class RawElement

data class RawItem(
    val component: String,
    val drawableLink: String
): RawElement()

data class RawCalendar(
    val component: String,
    val prefix: String
): RawElement()

data class RawDynamicClock(
    val drawableLink: String,
    val defaultHour: String,
    val defaultMinute: String,
    val hourLayerIndex: String,
    val minuteLayerIndex: String
): RawElement()

fun InstalledApplication.toComponentInfo(): String {
    return "ComponentInfo{${this.packageName}/${this.activityName}}"
}