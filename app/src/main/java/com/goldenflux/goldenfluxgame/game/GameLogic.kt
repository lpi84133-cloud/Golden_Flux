package com.goldenflux.goldenfluxgame.game

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * All Golden Flux rules live here as pure functions, so the live tick, the
 * offline catch-up and every cost label in the UI share the same math.
 *
 * Design intent:
 *  - The player can always act: tapping the Core produces energy from second one.
 *  - Placement matters: Amplifiers boost only the machines next to them.
 *  - Merging identical machines is the cheapest way to grow, so the board stays
 *    interesting instead of just getting more crowded.
 */
object GameLogic {

    const val REWARD_COOLDOWN_MS = 10 * 60 * 1000L
    private const val OFFLINE_HOURS_BASE = 4.0

    // ---- per-device output --------------------------------------------------

    fun generatorOutput(level: Int) = 0.8 * 2.35.pow(level - 1)
    fun amplifierBoost(level: Int) = 0.30 * level            // to each neighbour
    fun converterInput(level: Int) = 3.0 * 1.8.pow(level - 1)
    fun converterOutput(level: Int) = 0.20 * 2.2.pow(level - 1)
    fun storageCapacity(level: Int) = 600.0 * 2.4.pow(level - 1)

    fun coreMultiplier(coreLevel: Int) = 1.0 + 0.25 * (coreLevel - 1)

    /** Bonus a tile receives from amplifiers standing right next to it. */
    fun boostAt(zone: ZoneState, tile: Int): Double {
        var bonus = 0.0
        zone.id.neighbours(tile).forEach { n ->
            val d = zone.devices[n]
            if (d != null && d.type == DeviceType.AMPLIFIER) bonus += amplifierBoost(d.level)
        }
        return bonus
    }

    /** Aggregates the whole factory once; [focus] fills in per-tile display data. */
    fun stats(s: GameState, focus: ZoneId): FactoryStats {
        val globalMult = coreMultiplier(s.coreLevel) * (1.0 + 0.06 * s.upgrades.production)
        val efficiency = 1.0 + 0.10 * s.upgrades.efficiency

        var eps = 0.0
        var drain = 0.0
        var cps = 0.0
        var cap = 400.0 + 250.0 * s.coreLevel

        s.zones.values.forEach { zone ->
            if (!zone.unlocked) return@forEach
            zone.devices.values.forEach { d ->
                when (d.type) {
                    DeviceType.GENERATOR ->
                        eps += generatorOutput(d.level) * (1.0 + boostAt(zone, d.tile))
                    DeviceType.CONVERTER -> {
                        drain += converterInput(d.level)
                        cps += converterOutput(d.level) * (1.0 + boostAt(zone, d.tile))
                    }
                    DeviceType.STORAGE -> cap += storageCapacity(d.level)
                    DeviceType.AMPLIFIER -> Unit
                }
            }
        }
        eps *= globalMult
        cps *= efficiency

        val load = if (drain <= 0.0) 1f
        else min(1.0, (eps + s.resources.energy) / drain).toFloat()

        val tileBoost = HashMap<Int, Double>()
        s.zones[focus]?.let { zone ->
            zone.devices.values.forEach { d ->
                if (d.type != DeviceType.AMPLIFIER) {
                    val b = boostAt(zone, d.tile)
                    if (b > 0.0) tileBoost[d.tile] = b
                }
            }
        }

        return FactoryStats(
            energyPerSec = eps,
            crystalsPerSec = cps,
            drainPerSec = drain,
            capacity = cap,
            globalMultiplier = globalMult,
            tapValue = tapValue(s, eps),
            converterLoad = load,
            tileBoost = tileBoost,
        )
    }

    fun tapValue(s: GameState, energyPerSec: Double): Double =
        1.5 + 1.2 * (s.coreLevel - 1) + 2.5 * s.upgrades.tapPower + energyPerSec * 0.4

    // ---- advancing time -----------------------------------------------------

    /** Advances the simulation by [dt] seconds using pre-computed [st]. */
    fun step(s: GameState, dt: Double, st: FactoryStats): GameState {
        if (dt <= 0.0) return s

        val produced = st.energyPerSec * dt
        val want = st.drainPerSec * dt
        val available = s.resources.energy + produced
        val used = min(want, max(0.0, available))
        val ratio = if (want > 0.0) used / want else 1.0
        val crystals = st.crystalsPerSec * ratio * dt
        val energy = (available - used).coerceIn(0.0, st.capacity)

        return s.copy(
            resources = s.resources.copy(
                energy = energy,
                crystals = s.resources.crystals + crystals,
            ),
            stats = s.stats.copy(
                totalEnergyProduced = s.stats.totalEnergyProduced + produced,
                totalCrystalsProduced = s.stats.totalCrystalsProduced + crystals,
                playTimeSeconds = s.stats.playTimeSeconds + dt.toLong(),
            ),
        )
    }

    /** Manual core tap: always available, always useful. */
    fun tap(s: GameState, st: FactoryStats): GameState = s.copy(
        resources = s.resources.copy(
            energy = min(st.capacity, s.resources.energy + st.tapValue),
        ),
        stats = s.stats.copy(
            taps = s.stats.taps + 1,
            totalEnergyProduced = s.stats.totalEnergyProduced + st.tapValue,
        ),
    )

    fun offlineWindowMs(s: GameState): Long =
        ((OFFLINE_HOURS_BASE + s.upgrades.offlineCap) * 3_600_000.0).toLong()

    fun applyOffline(s: GameState, nowMs: Long): Pair<GameState, OfflineReport> {
        if (s.lastSavedEpochMs <= 0L) return s to OfflineReport(0.0, 0.0, 0L)
        val raw = nowMs - s.lastSavedEpochMs
        if (raw < 30_000L) return s to OfflineReport(0.0, 0.0, 0L)
        val capped = raw.coerceAtMost(offlineWindowMs(s))
        val st = stats(s, ZoneId.ANCIENT_HALL)
        val before = s.resources
        val after = step(s, capped / 1000.0, st)
        return after to OfflineReport(
            energy = after.resources.energy - before.energy,
            crystals = after.resources.crystals - before.crystals,
            durationMs = capped,
        )
    }

    // ---- costs --------------------------------------------------------------

    fun buildCost(type: DeviceType, owned: Int): Cost = when (type) {
        DeviceType.GENERATOR -> Cost(energy = 12.0 * 1.62.pow(owned))
        DeviceType.STORAGE -> Cost(energy = 90.0 * 1.70.pow(owned))
        DeviceType.CONVERTER -> Cost(energy = 240.0 * 1.75.pow(owned))
        DeviceType.AMPLIFIER -> Cost(
            energy = 550.0 * 1.75.pow(owned),
            crystals = 10.0 * 1.50.pow(owned),
        )
    }

    fun upgradeCost(type: DeviceType, level: Int): Cost {
        val k = 2.6.pow(level - 1)
        return when (type) {
            DeviceType.GENERATOR -> Cost(energy = 90.0 * k)
            DeviceType.STORAGE -> Cost(energy = 160.0 * k)
            DeviceType.CONVERTER -> Cost(energy = 420.0 * k, crystals = 12.0 * 2.2.pow(level - 1))
            DeviceType.AMPLIFIER -> Cost(energy = 750.0 * k, crystals = 25.0 * 2.2.pow(level - 1))
        }
    }

    /** Selling returns part of what a device of that tier is worth. */
    fun sellValue(type: DeviceType, level: Int, owned: Int): Double {
        val base = buildCost(type, max(0, owned - 1)).energy
        return base * 0.5 * level
    }

    fun coreUpgradeCost(coreLevel: Int) = Cost(
        energy = 700.0 * 1.95.pow(coreLevel - 1),
        crystals = 12.0 * 1.80.pow(coreLevel - 1),
    )

    fun trackUpgradeCost(track: UpgradeTrack, level: Int): Cost = when (track) {
        UpgradeTrack.PRODUCTION -> Cost(crystals = 25.0 * 1.55.pow(level))
        UpgradeTrack.EFFICIENCY -> Cost(crystals = 35.0 * 1.55.pow(level))
        UpgradeTrack.TAP_POWER -> Cost(crystals = 20.0 * 1.70.pow(level))
        UpgradeTrack.OFFLINE_CAP -> Cost(crystals = 60.0 * 1.80.pow(level))
    }

    fun zoneUnlockCost(zone: ZoneId) = Cost(zone.unlockEnergy, zone.unlockCrystals)

    fun canAfford(s: GameState, cost: Cost): Boolean =
        s.resources.energy >= cost.energy &&
            s.resources.crystals >= cost.crystals &&
            s.resources.goldSymbols >= cost.goldSymbols

    fun spend(s: GameState, cost: Cost): GameState = s.copy(
        resources = s.resources.copy(
            energy = s.resources.energy - cost.energy,
            crystals = s.resources.crystals - cost.crystals,
            goldSymbols = s.resources.goldSymbols - cost.goldSymbols,
        )
    )

    fun grant(s: GameState, g: Grant): GameState = s.copy(
        resources = s.resources.copy(
            energy = s.resources.energy + g.energy,
            crystals = s.resources.crystals + g.crystals,
            goldSymbols = s.resources.goldSymbols + g.goldSymbols,
            starArtifacts = s.resources.starArtifacts + g.starArtifacts,
        )
    )

    // ---- readable descriptions used across the UI ---------------------------

    fun deviceRole(type: DeviceType): String = when (type) {
        DeviceType.GENERATOR -> "Produces energy every second"
        DeviceType.AMPLIFIER -> "Boosts the 4 machines next to it"
        DeviceType.CONVERTER -> "Burns energy to make crystals"
        DeviceType.STORAGE -> "Raises your energy capacity"
    }

    fun deviceEffect(type: DeviceType, level: Int): String = when (type) {
        DeviceType.GENERATOR -> "+${fmt(generatorOutput(level))} energy/s"
        DeviceType.AMPLIFIER -> "+${(amplifierBoost(level) * 100).toInt()}% to each neighbour"
        DeviceType.CONVERTER -> "+${fmt(converterOutput(level))} crystals/s  ·  -${fmt(converterInput(level))} energy/s"
        DeviceType.STORAGE -> "+${fmt(storageCapacity(level))} capacity"
    }

    private fun fmt(v: Double): String =
        if (v >= 100) v.toInt().toString() else String.format("%.2f", v).trimEnd('0').trimEnd('.')
}

enum class UpgradeTrack(val title: String, val description: String, val icon: String) {
    PRODUCTION("Flux Power", "+6% energy from every machine", "game/sprites/crystal_1.png"),
    EFFICIENCY("Refinement", "+10% crystal yield", "game/sprites/upgrade_crystal.png"),
    TAP_POWER("Keeper's Touch", "+2.5 energy per core tap", "game/sprites/symbol_1.png"),
    OFFLINE_CAP("Flux Reserve", "+1 hour of offline production", "game/sprites/artifact_1.png"),
}

data class Cost(
    val energy: Double = 0.0,
    val crystals: Double = 0.0,
    val goldSymbols: Long = 0,
)

data class Grant(
    val energy: Double = 0.0,
    val crystals: Double = 0.0,
    val goldSymbols: Long = 0,
    val starArtifacts: Long = 0,
)

data class OfflineReport(
    val energy: Double,
    val crystals: Double,
    val durationMs: Long,
) {
    val hasEarnings get() = energy > 1.0 || crystals > 0.1
}
