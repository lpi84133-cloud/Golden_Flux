package com.goldenflux.goldenfluxgame.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.goldenflux.goldenfluxgame.audio.Sfx
import com.goldenflux.goldenfluxgame.audio.SoundManager
import com.goldenflux.goldenfluxgame.data.SaveRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Numbers the HUD shows. Small on purpose: it updates on every tick. */
data class HudState(
    val energy: Double = 0.0,
    val crystals: Double = 0.0,
    val goldSymbols: Long = 0,
    val energyPerSec: Double = 0.0,
    val crystalsPerSec: Double = 0.0,
    val capacity: Double = 0.0,
    val converterLoad: Float = 1f,
    val coreLevel: Int = 1,
)

/** Everything the isometric board draws. Changes only when the layout changes. */
data class BoardState(
    val zone: ZoneId = ZoneId.ANCIENT_HALL,
    val devices: Map<Int, DeviceInstance> = emptyMap(),
    val tileBoost: Map<Int, Double> = emptyMap(),
)

enum class TileAction { MOVED, MERGED, REJECTED }

class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SaveRepository(app)
    val sound = SoundManager(app)

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(FactoryStats())
    val stats: StateFlow<FactoryStats> = _stats.asStateFlow()

    private val _selectedZone = MutableStateFlow(ZoneId.ANCIENT_HALL)
    val selectedZone: StateFlow<ZoneId> = _selectedZone.asStateFlow()

    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    /** HUD slice: only re-emits when a displayed number actually changes. */
    val hud: StateFlow<HudState> = combine(_state, _stats) { s, st ->
        HudState(
            energy = s.resources.energy,
            crystals = s.resources.crystals,
            goldSymbols = s.resources.goldSymbols,
            energyPerSec = st.energyPerSec,
            crystalsPerSec = st.crystalsPerSec,
            capacity = st.capacity,
            converterLoad = st.converterLoad,
            coreLevel = s.coreLevel,
        )
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, HudState())

    /** Board slice: stays identical across ticks, so the grid does not recompose. */
    val board: StateFlow<BoardState> = combine(_state, _stats, _selectedZone) { s, st, zone ->
        BoardState(
            zone = zone,
            devices = s.zones[zone]?.devices ?: emptyMap(),
            tileBoost = st.tileBoost,
        )
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, BoardState())

    val hint: StateFlow<String?> = combine(_state, _stats) { s, st -> Hints.current(s, st) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val claimableQuests: StateFlow<Int> = _state.map { Quests.claimable(it).size }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private var initialized = false
    private var lastTickMs = 0L
    private var dirty = false

    // ---- lifecycle ----------------------------------------------------------

    fun initFrom(loaded: GameState?, offline: OfflineReport?) {
        if (initialized) return
        initialized = true

        val start = (loaded ?: GameState()).copy(lastSavedEpochMs = System.currentTimeMillis())
        _state.value = start
        _stats.value = GameLogic.stats(start, _selectedZone.value)

        // resume in the last zone the player actually owns
        _selectedZone.value = ZoneId.entries.lastOrNull { start.zones[it]?.unlocked == true }
            ?: ZoneId.ANCIENT_HALL

        sound.sfxEnabled = start.settings.sfx
        sound.musicEnabled = start.settings.music
        sound.vibrationEnabled = start.settings.vibration
        sound.applyMusicSetting()

        if (offline != null && offline.hasEarnings) _events.tryEmit(GameEvent.Offline(offline))

        startLoops()
    }

    private fun startLoops() {
        lastTickMs = System.currentTimeMillis()
        viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                val dt = (now - lastTickMs).coerceIn(0, 5_000) / 1000.0
                lastTickMs = now
                val current = _state.value
                val st = GameLogic.stats(current, _selectedZone.value)
                _stats.value = st
                _state.value = GameLogic.step(current, dt, st)
            }
        }
        // saving is decoupled from the tick so the disk is touched rarely
        viewModelScope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                if (dirty) persist()
            }
        }
    }

    fun persist() {
        dirty = false
        val snapshot = _state.value.copy(lastSavedEpochMs = System.currentTimeMillis())
        _state.value = snapshot
        viewModelScope.launch { repo.save(snapshot) }
    }

    /** Applies a structural change, refreshes derived stats and flags a save. */
    private fun commit(next: GameState) {
        _state.value = next
        _stats.value = GameLogic.stats(next, _selectedZone.value)
        dirty = true
    }

    // ---- core interaction ---------------------------------------------------

    /** Manual tap on the core; returns the amount gained for the UI feedback. */
    fun tapCore(): Double {
        val st = _stats.value
        commit(GameLogic.tap(_state.value, st))
        sound.play(Sfx.COLLECT)
        return st.tapValue
    }

    fun selectZone(zone: ZoneId) {
        if (_state.value.zones[zone]?.unlocked == true && _selectedZone.value != zone) {
            _selectedZone.value = zone
            _stats.value = GameLogic.stats(_state.value, zone)
            sound.play(Sfx.MENU_OPEN)
        }
    }

    // ---- building -----------------------------------------------------------

    fun buildCost(type: DeviceType): Cost =
        GameLogic.buildCost(type, _state.value.countOf(type))

    fun build(tile: Int, type: DeviceType): Boolean {
        val s = _state.value
        val zone = _selectedZone.value
        val z = s.zones[zone] ?: return false
        if (z.devices.containsKey(tile)) return false

        val cost = GameLogic.buildCost(type, s.countOf(type))
        if (!GameLogic.canAfford(s, cost)) {
            emit("Not enough resources for a ${type.title}")
            return false
        }
        val spent = GameLogic.spend(s, cost)
        commit(
            spent.copy(
                zones = spent.zones + (zone to z.copy(
                    devices = z.devices + (tile to DeviceInstance(type, 1, tile))
                )),
                stats = spent.stats.copy(devicesBuilt = spent.stats.devicesBuilt + 1),
            )
        )
        sound.play(Sfx.BUILD)
        sound.tick()
        return true
    }

    fun upgradeDevice(tile: Int): Boolean {
        val s = _state.value
        val zone = _selectedZone.value
        val z = s.zones[zone] ?: return false
        val d = z.devices[tile] ?: return false
        if (d.level >= d.type.maxLevel) { emit("Already at maximum tier"); return false }

        val cost = GameLogic.upgradeCost(d.type, d.level)
        if (!GameLogic.canAfford(s, cost)) { emit("Not enough resources to upgrade"); return false }
        val spent = GameLogic.spend(s, cost)
        commit(
            spent.copy(
                zones = spent.zones + (zone to z.copy(
                    devices = z.devices + (tile to d.copy(level = d.level + 1))
                )),
                stats = spent.stats.copy(upgradesDone = spent.stats.upgradesDone + 1),
            )
        )
        sound.play(Sfx.UPGRADE)
        sound.tick()
        return true
    }

    fun sellDevice(tile: Int): Boolean {
        val s = _state.value
        val zone = _selectedZone.value
        val z = s.zones[zone] ?: return false
        val d = z.devices[tile] ?: return false
        val refund = GameLogic.sellValue(d.type, d.level, s.countOf(d.type))
        commit(
            s.copy(
                zones = s.zones + (zone to z.copy(devices = z.devices - tile)),
                resources = s.resources.copy(energy = s.resources.energy + refund),
            )
        )
        sound.play(Sfx.MENU_CLOSE)
        return true
    }

    /** Drag result: merge two identical machines, or relocate one. */
    fun moveOrMerge(from: Int, to: Int): TileAction {
        if (from == to) return TileAction.REJECTED
        val s = _state.value
        val zone = _selectedZone.value
        val z = s.zones[zone] ?: return TileAction.REJECTED
        val source = z.devices[from] ?: return TileAction.REJECTED
        val target = z.devices[to]

        if (target == null) {
            commit(
                s.copy(
                    zones = s.zones + (zone to z.copy(
                        devices = z.devices - from + (to to source.copy(tile = to))
                    ))
                )
            )
            sound.play(Sfx.CLICK)
            return TileAction.MOVED
        }

        val mergeable = target.type == source.type &&
            target.level == source.level &&
            target.level < source.type.maxLevel
        if (!mergeable) {
            emit(
                if (target.type == source.type) "Both machines must be the same tier"
                else "Only identical machines can merge"
            )
            return TileAction.REJECTED
        }

        commit(
            s.copy(
                zones = s.zones + (zone to z.copy(
                    devices = z.devices - from + (to to target.copy(level = target.level + 1))
                )),
                stats = s.stats.copy(merges = s.stats.merges + 1),
            )
        )
        sound.play(Sfx.UPGRADE)
        sound.tick()
        _events.tryEmit(GameEvent.Merged(source.type, target.level + 1))
        return TileAction.MERGED
    }

    // ---- progression --------------------------------------------------------

    fun upgradeCore(): Boolean {
        val s = _state.value
        val cost = GameLogic.coreUpgradeCost(s.coreLevel)
        if (!GameLogic.canAfford(s, cost)) { emit("Not enough resources for the Core"); return false }
        val spent = GameLogic.spend(s, cost)
        commit(spent.copy(coreLevel = spent.coreLevel + 1))
        sound.play(Sfx.CORE)
        sound.tick()
        _events.tryEmit(GameEvent.CoreUpgraded(_state.value.coreLevel))
        persist()
        return true
    }

    fun upgradeTrack(track: UpgradeTrack): Boolean {
        val s = _state.value
        val level = levelOf(s, track)
        val cost = GameLogic.trackUpgradeCost(track, level)
        if (!GameLogic.canAfford(s, cost)) { emit("Not enough crystals"); return false }
        val spent = GameLogic.spend(s, cost)
        val upg = when (track) {
            UpgradeTrack.PRODUCTION -> spent.upgrades.copy(production = level + 1)
            UpgradeTrack.EFFICIENCY -> spent.upgrades.copy(efficiency = level + 1)
            UpgradeTrack.TAP_POWER -> spent.upgrades.copy(tapPower = level + 1)
            UpgradeTrack.OFFLINE_CAP -> spent.upgrades.copy(offlineCap = level + 1)
        }
        commit(spent.copy(upgrades = upg))
        sound.play(Sfx.UPGRADE)
        return true
    }

    fun levelOf(s: GameState, track: UpgradeTrack): Int = when (track) {
        UpgradeTrack.PRODUCTION -> s.upgrades.production
        UpgradeTrack.EFFICIENCY -> s.upgrades.efficiency
        UpgradeTrack.TAP_POWER -> s.upgrades.tapPower
        UpgradeTrack.OFFLINE_CAP -> s.upgrades.offlineCap
    }

    fun unlockZone(zone: ZoneId): Boolean {
        val s = _state.value
        val z = s.zones[zone] ?: return false
        if (z.unlocked) { _selectedZone.value = zone; return true }
        val cost = GameLogic.zoneUnlockCost(zone)
        if (!GameLogic.canAfford(s, cost)) { emit("Not enough resources to unlock"); return false }
        val spent = GameLogic.spend(s, cost)
        commit(spent.copy(zones = spent.zones + (zone to z.copy(unlocked = true))))
        _selectedZone.value = zone
        sound.play(Sfx.ZONE)
        sound.tick()
        _events.tryEmit(GameEvent.ZoneUnlocked(zone))
        persist()
        return true
    }

    fun claimQuest(id: String): Boolean {
        val s = _state.value
        val quest = Quests.byId(id) ?: return false
        if (id in s.claimedQuests || !quest.isComplete(s)) return false
        commit(
            GameLogic.grant(s, quest.reward).copy(claimedQuests = s.claimedQuests + id)
        )
        sound.play(Sfx.COMPLETE)
        _events.tryEmit(GameEvent.QuestDone(quest))
        persist()
        return true
    }

    // ---- rewards & shop -----------------------------------------------------

    fun rewardRemainingMs(): Long =
        (_state.value.lastRewardEpochMs + GameLogic.REWARD_COOLDOWN_MS - System.currentTimeMillis())
            .coerceAtLeast(0)

    fun claimReward(): Boolean {
        if (rewardRemainingMs() > 0) return false
        val s = _state.value
        val st = _stats.value
        val energy = (st.energyPerSec * 900.0).coerceAtLeast(150.0)
        val crystals = (st.crystalsPerSec * 900.0).coerceAtLeast(5.0)
        val gold = if ((0..2).random() == 0) 1L else 0L
        commit(
            GameLogic.grant(s, Grant(energy, crystals, gold)).copy(
                lastRewardEpochMs = System.currentTimeMillis(),
                stats = s.stats.copy(chestsOpened = s.stats.chestsOpened + 1),
            ).let { it.copy(resources = it.resources.copy(energy = minOf(it.resources.energy, st.capacity))) }
        )
        sound.play(Sfx.CHEST)
        _events.tryEmit(GameEvent.Reward(energy, crystals, gold))
        persist()
        return true
    }

    fun buy(item: ShopItem): Boolean {
        val s = _state.value
        if (!GameLogic.canAfford(s, item.cost)) { emit("Not enough resources"); return false }
        val spent = GameLogic.spend(s, item.cost)
        val granted = GameLogic.grant(spent, item.grant)
        commit(
            granted.copy(
                resources = granted.resources.copy(
                    energy = minOf(granted.resources.energy, _stats.value.capacity)
                )
            )
        )
        sound.play(Sfx.COLLECT)
        emit("${item.title} acquired")
        persist()
        return true
    }

    // ---- settings & profile -------------------------------------------------

    fun toggleMusic() {
        val s = _state.value
        commit(s.copy(settings = s.settings.copy(music = !s.settings.music)))
        sound.musicEnabled = _state.value.settings.music
        sound.applyMusicSetting()
        sound.play(Sfx.CLICK)
    }

    fun toggleSfx() {
        val s = _state.value
        commit(s.copy(settings = s.settings.copy(sfx = !s.settings.sfx)))
        sound.sfxEnabled = _state.value.settings.sfx
        sound.play(Sfx.CLICK)
    }

    fun toggleVibration() {
        val s = _state.value
        commit(s.copy(settings = s.settings.copy(vibration = !s.settings.vibration)))
        sound.vibrationEnabled = _state.value.settings.vibration
        sound.tick()
    }

    fun setProfileName(name: String) {
        val clean = name.trim().take(18).ifBlank { "Keeper" }
        val s = _state.value
        commit(s.copy(profile = s.profile.copy(name = clean)))
        persist()
    }

    fun click() = sound.play(Sfx.CLICK)

    private fun emit(msg: String) { _events.tryEmit(GameEvent.Message(msg)) }

    override fun onCleared() {
        persist()
        sound.release()
        super.onCleared()
    }

    private companion object {
        const val TICK_MS = 250L
        const val SAVE_INTERVAL_MS = 15_000L
    }
}

sealed interface GameEvent {
    data class Message(val text: String) : GameEvent
    data class Offline(val report: OfflineReport) : GameEvent
    data class Reward(val energy: Double, val crystals: Double, val gold: Long) : GameEvent
    data class ZoneUnlocked(val zone: ZoneId) : GameEvent
    data class CoreUpgraded(val level: Int) : GameEvent
    data class Merged(val type: DeviceType, val level: Int) : GameEvent
    data class QuestDone(val quest: Quest) : GameEvent
}

data class ShopItem(
    val title: String,
    val description: String,
    val icon: String,
    val cost: Cost,
    val grant: Grant,
) {
    companion object {
        fun catalog(): List<ShopItem> = listOf(
            ShopItem(
                "Energy Surge", "A burst of raw flux energy",
                "game/sprites/crystal_1.png",
                cost = Cost(crystals = 12.0), grant = Grant(energy = 3_000.0),
            ),
            ShopItem(
                "Crystal Cache", "Trade a golden symbol for crystals",
                "game/sprites/upgrade_crystal.png",
                cost = Cost(goldSymbols = 2), grant = Grant(crystals = 90.0),
            ),
            ShopItem(
                "Golden Symbol", "Forge a rare golden symbol",
                "game/sprites/symbol_1.png",
                cost = Cost(crystals = 180.0), grant = Grant(goldSymbols = 1),
            ),
            ShopItem(
                "Star Artifact", "A legendary relic of the Keepers",
                "game/sprites/artifact_1.png",
                cost = Cost(crystals = 250.0, goldSymbols = 4), grant = Grant(starArtifacts = 1),
            ),
        )
    }
}
