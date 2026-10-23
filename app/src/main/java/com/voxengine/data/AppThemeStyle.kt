package com.voxengine.data

enum class AppThemeStyle(val storageValue: String) {
    LIQUID_GLASS("liquid_glass"),
    CLASSIC("classic");

    companion object {
        fun fromStorage(value: String?): AppThemeStyle =
            entries.firstOrNull { it.storageValue == value } ?: LIQUID_GLASS
    }
}
