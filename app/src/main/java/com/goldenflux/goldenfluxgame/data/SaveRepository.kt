package com.goldenflux.goldenfluxgame.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.goldenflux.goldenfluxgame.game.DeviceInstance
import com.goldenflux.goldenfluxgame.game.DeviceType
import com.goldenflux.goldenfluxgame.game.GameState
import com.goldenflux.goldenfluxgame.game.Profile
import com.goldenflux.goldenfluxgame.game.Resources
import com.goldenflux.goldenfluxgame.game.Settings
import com.goldenflux.goldenfluxgame.game.Stats
import com.goldenflux.goldenfluxgame.game.Upgrades
import com.goldenflux.goldenfluxgame.game.ZoneId
import com.goldenflux.goldenfluxgame.game.ZoneState
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "golden_flux_save")

/** Persists [GameState] as a compact JSON blob in DataStore. */
class SaveRepository(private val context: Context) {

    private val key = stringPreferencesKey("state_json")

    suspend fun load(): GameState? {
        val json = context.dataStore.data.first()[key] ?: return null
        return runCatching { fromJson(JSONObject(json)) }.getOrNull()
    }

    suspend fun save(state: GameState) {
        val json = toJson(state).toString()
        context.dataStore.edit { it[key] = json }
    }

    private fun toJson(s: GameState): JSONObject = JSONObject().apply {
        put("coreLevel", s.coreLevel)
        put("lastRewardEpochMs", s.lastRewardEpochMs)
        put("lastSavedEpochMs", s.lastSavedEpochMs)
        put("claimedQuests", JSONArray(s.claimedQuests.toList()))
        put("resources", JSONObject().apply {
            put("energy", s.resources.energy)
            put("crystals", s.resources.crystals)
            put("goldSymbols", s.resources.goldSymbols)
            put("starArtifacts", s.resources.starArtifacts)
        })
        put("upgrades", JSONObject().apply {
            put("production", s.upgrades.production)
            put("efficiency", s.upgrades.efficiency)
            put("offlineCap", s.upgrades.offlineCap)
            put("tapPower", s.upgrades.tapPower)
        })
        put("settings", JSONObject().apply {
            put("music", s.settings.music)
            put("sfx", s.settings.sfx)
            put("vibration", s.settings.vibration)
        })
        put("profile", JSONObject().apply {
            put("name", s.profile.name)
            put("avatarPath", s.profile.avatarPath ?: JSONObject.NULL)
        })
        put("stats", JSONObject().apply {
            put("totalEnergyProduced", s.stats.totalEnergyProduced)
            put("totalCrystalsProduced", s.stats.totalCrystalsProduced)
            put("devicesBuilt", s.stats.devicesBuilt)
            put("upgradesDone", s.stats.upgradesDone)
            put("merges", s.stats.merges)
            put("taps", s.stats.taps)
            put("chestsOpened", s.stats.chestsOpened)
            put("playTimeSeconds", s.stats.playTimeSeconds)
        })
        val zonesArr = JSONArray()
        s.zones.values.forEach { z ->
            zonesArr.put(JSONObject().apply {
                put("id", z.id.name)
                put("unlocked", z.unlocked)
                val dev = JSONArray()
                z.devices.values.forEach { d ->
                    dev.put(JSONObject().apply {
                        put("type", d.type.name)
                        put("level", d.level)
                        put("tile", d.tile)
                    })
                }
                put("devices", dev)
            })
        }
        put("zones", zonesArr)
    }

    private fun fromJson(o: JSONObject): GameState {
        val r = o.optJSONObject("resources") ?: JSONObject()
        val u = o.optJSONObject("upgrades") ?: JSONObject()
        val st = o.optJSONObject("settings") ?: JSONObject()
        val pr = o.optJSONObject("profile") ?: JSONObject()
        val stat = o.optJSONObject("stats") ?: JSONObject()

        val zones = HashMap<ZoneId, ZoneState>()
        val zonesArr = o.optJSONArray("zones") ?: JSONArray()
        for (i in 0 until zonesArr.length()) {
            val zo = zonesArr.getJSONObject(i)
            val id = runCatching { ZoneId.valueOf(zo.getString("id")) }.getOrNull() ?: continue
            val devices = HashMap<Int, DeviceInstance>()
            val da = zo.optJSONArray("devices") ?: JSONArray()
            for (j in 0 until da.length()) {
                val d = da.getJSONObject(j)
                val type = runCatching { DeviceType.valueOf(d.getString("type")) }.getOrNull() ?: continue
                val tile = d.getInt("tile")
                // discard tiles that no longer exist if a board was resized
                if (tile !in 0 until id.tiles) continue
                devices[tile] = DeviceInstance(type, d.getInt("level").coerceIn(1, type.maxLevel), tile)
            }
            zones[id] = ZoneState(id, zo.optBoolean("unlocked", id == ZoneId.ANCIENT_HALL), devices)
        }
        ZoneId.entries.forEach { z ->
            if (!zones.containsKey(z)) zones[z] = ZoneState(z, z == ZoneId.ANCIENT_HALL)
        }

        val claimed = HashSet<String>()
        o.optJSONArray("claimedQuests")?.let { arr ->
            for (i in 0 until arr.length()) claimed.add(arr.getString(i))
        }

        return GameState(
            resources = Resources(
                energy = r.optDouble("energy", 4.0),
                crystals = r.optDouble("crystals", 0.0),
                goldSymbols = r.optLong("goldSymbols", 0L),
                starArtifacts = r.optLong("starArtifacts", 0L),
            ),
            coreLevel = o.optInt("coreLevel", 1).coerceAtLeast(1),
            zones = zones,
            upgrades = Upgrades(
                production = u.optInt("production", 0),
                efficiency = u.optInt("efficiency", 0),
                offlineCap = u.optInt("offlineCap", 0),
                tapPower = u.optInt("tapPower", 0),
            ),
            settings = Settings(
                music = st.optBoolean("music", true),
                sfx = st.optBoolean("sfx", true),
                vibration = st.optBoolean("vibration", true),
            ),
            profile = Profile(
                name = pr.optString("name", "Keeper"),
                avatarPath = if (pr.isNull("avatarPath")) null else pr.optString("avatarPath", null),
            ),
            stats = Stats(
                totalEnergyProduced = stat.optDouble("totalEnergyProduced", 0.0),
                totalCrystalsProduced = stat.optDouble("totalCrystalsProduced", 0.0),
                devicesBuilt = stat.optInt("devicesBuilt", 0),
                upgradesDone = stat.optInt("upgradesDone", 0),
                merges = stat.optInt("merges", 0),
                taps = stat.optLong("taps", 0L),
                chestsOpened = stat.optInt("chestsOpened", 0),
                playTimeSeconds = stat.optLong("playTimeSeconds", 0L),
            ),
            claimedQuests = claimed,
            lastRewardEpochMs = o.optLong("lastRewardEpochMs", 0L),
            lastSavedEpochMs = o.optLong("lastSavedEpochMs", 0L),
        )
    }
}
