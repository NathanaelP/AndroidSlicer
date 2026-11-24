// SPDX-License-Identifier: AGPL-3.0-or-later
package org.mobileslicer.engine

import org.json.JSONObject

/**
 * A complete, flat set of CuraEngine settings for one printer, material and
 * quality, built offline from Cura's definitions by profiles/build_profiles.py.
 */
data class Profile(
    val id: String,
    /** Short label, e.g. "Normal · 0.15 mm". */
    val name: String,
    /** Cura's quality name: "Fine", "Normal" or "Fast". */
    val quality: String,
    val machine: String,
    val material: String,
    /** Filament density in g/cm³, for weight estimates. */
    val materialDensity: Double,
    val settings: Map<String, String>,
) {
    operator fun get(key: String): String? = settings[key]

    fun float(key: String): Float? = settings[key]?.toFloatOrNull()

    companion object {
        fun parseJson(text: String): Profile {
            val o = JSONObject(text)
            val s = o.getJSONObject("settings")
            val settings = LinkedHashMap<String, String>()
            for (key in s.keys()) settings[key] = s.getString(key)
            return Profile(
                id = o.getString("id"),
                name = o.getString("name"),
                quality = o.optString("quality", o.getString("name")),
                machine = o.optString("machine", settings["machine_name"] ?: ""),
                material = o.optString("material", ""),
                materialDensity = o.optDouble("material_density", 1.24),
                settings = settings,
            )
        }
    }
}
