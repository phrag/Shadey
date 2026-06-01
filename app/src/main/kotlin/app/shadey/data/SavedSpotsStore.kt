package app.shadey.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.shadey.core.data.SpotsJson
import app.shadey.core.model.Spot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shadey")

/** Local, on-device storage for user-added spots and the OSM-roaming preference. */
class SavedSpotsStore(private val context: Context) {

    private val userSpotsKey = stringPreferencesKey("user_spots")
    private val roamingKey = booleanPreferencesKey("allow_osm_roaming")

    val userSpots: Flow<List<Spot>> = context.dataStore.data.map { prefs ->
        prefs[userSpotsKey]?.let { SpotsJson.parse(it) } ?: emptyList()
    }

    val allowRoaming: Flow<Boolean> = context.dataStore.data.map { it[roamingKey] ?: false }

    suspend fun addOrUpdate(spot: Spot) {
        context.dataStore.edit { prefs ->
            val current = prefs[userSpotsKey]?.let { SpotsJson.parse(it) } ?: emptyList()
            val next = current.filterNot { it.id == spot.id } + spot
            prefs[userSpotsKey] = SpotsJson.encode(next)
        }
    }

    suspend fun remove(id: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[userSpotsKey]?.let { SpotsJson.parse(it) } ?: emptyList()
            prefs[userSpotsKey] = SpotsJson.encode(current.filterNot { it.id == id })
        }
    }

    suspend fun setAllowRoaming(value: Boolean) {
        context.dataStore.edit { it[roamingKey] = value }
    }
}
