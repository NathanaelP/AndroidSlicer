// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import java.io.InputStream

/** A flat list of CuraEngine settings, applied in order. */
data class Profile(val name: String, val settings: List<Pair<String, String>>) {

    operator fun get(key: String): String? = settings.lastOrNull { it.first == key }?.second

    fun float(key: String): Float? = get(key)?.toFloatOrNull()

    companion object {
        /** Parses key=value lines; '#' starts a comment line and "\n" in a value is a line break. */
        fun parse(name: String, input: InputStream): Profile {
            val settings = input.bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                .map { line ->
                    val key = line.substringBefore('=').trim()
                    val value = line.substringAfter('=').trim().replace("\\n", "\n")
                    key to value
                }
            return Profile(name, settings)
        }
    }
}
