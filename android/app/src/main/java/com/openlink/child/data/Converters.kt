package com.openlink.child.data

import androidx.room.TypeConverter

/**
 * Stores a package-name list in a single TEXT column, comma-separated.
 *
 * A join table would be the textbook answer, but `PUT /schedule` replaces the entire set of
 * windows on every save, so rows are never edited in place and there is nothing for foreign keys
 * to protect. A delimiter is unambiguous here for a reason specific to this data: an Android
 * package name is `[A-Za-z0-9_.]` separated by dots, so it can never contain a comma. This would
 * be a bad idea for arbitrary user text.
 */
class PackageListConverter {

    @TypeConverter
    fun fromPackageList(packages: List<String>): String = packages.joinToString(SEPARATOR)

    @TypeConverter
    fun toPackageList(stored: String): List<String> =
        if (stored.isEmpty()) emptyList()
        else stored.split(SEPARATOR).filter { it.isNotBlank() }

    private companion object {
        const val SEPARATOR = ","
    }
}
