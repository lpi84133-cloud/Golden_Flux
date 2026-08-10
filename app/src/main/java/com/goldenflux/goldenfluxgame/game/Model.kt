package com.goldenflux.goldenfluxgame.game

/** The four buildable device families placed on the factory grid. */
enum class DeviceType(
    val title: String,
    val spriteBase: String,   // sprite file base in assets/game/sprites
    val maxLevel: Int,
) {
    GENERATOR("Generator", "generator", 4),
    AMPLIFIER("Amplifier", "amplifier", 4),
    CONVERTER("Converter", "converter", 4),
    STORAGE("Battery", "storage", 4);

    fun spriteFor(level: Int): String {
        val idx = level.coerceIn(1, maxLevel)
        return "game/sprites/${spriteBase}_$idx.png"
    }
}

/**
 * Factory zones. Each zone is a [cols] x [rows] isometric board, so the only
 * limit on how much the player can build is physical space they can see.
 */
enum class ZoneId(
    val title: String,
    val subtitle: String,
    val background: String,
    val cols: Int,
    val rows: Int,
    val unlockEnergy: Double,
    val unlockCrystals: Double,
) {
    ANCIENT_HALL(
        "Ancient Hall", "Where the Flux first awoke",
        "game/bg/bg_ancient_hall.webp", 3, 3, 0.0, 0.0,
    ),
    GOLDEN_ARCHIVE(
        "Golden Archive", "Vault of forgotten technology",
        "game/bg/bg_golden_archive.webp", 3, 4, 4_000.0, 30.0,
    ),
    CRYSTAL_REACTOR(
        "Crystal Reactor", "Raw power, barely contained",
        "game/bg/bg_crystal_reactor.webp", 4, 4, 80_000.0, 400.0,
    ),
    MAIN_FLUX(
        "Main Flux Engine", "The heart of the Golden Flux",
        "game/bg/bg_crystal_reactor.webp", 4, 5, 1_200_000.0, 3_000.0,
    );

    val tiles: Int get() = cols * rows

    /** Orthogonal neighbours of [tile] inside this board. */
    fun neighbours(tile: Int): List<Int> {
        val c = tile % cols
        val r = tile / cols
        val out = ArrayList<Int>(4)
        if (c > 0) out.add(tile - 1)
        if (c < cols - 1) out.add(tile + 1)
        if (r > 0) out.add(tile - cols)
        if (r < rows - 1) out.add(tile + cols)
        return out
    }
}

/** A single device placed on a tile. */
data class DeviceInstance(
    val type: DeviceType,
    val level: Int,
    val tile: Int,
)

data class ZoneState(
    val id: ZoneId,
    val unlocked: Boolean,
    val devices: Map<Int, DeviceInstance> = emptyMap(),
)

data class Resources(
    val energy: Double = 0.0,
    val crystals: Double = 0.0,
    val goldSymbols: Long = 0,
    val starArtifacts: Long = 0,
)

data class Settings(
    val music: Boolean = true,
    val sfx: Boolean = true,
    val vibration: Boolean = true,
)

data class Profile(
    val name: String = "Keeper",
    val avatarPath: String? = null,
)

data class Stats(
    val totalEnergyProduced: Double = 0.0,
    val totalCrystalsProduced: Double = 0.0,
    val devicesBuilt: Int = 0,
    val upgradesDone: Int = 0,
    val merges: Int = 0,
    val taps: Long = 0,
    val chestsOpened: Int = 0,
    val playTimeSeconds: Long = 0,
)

/** Permanent upgrade tracks bought with crystals. */
data class Upgrades(
    val production: Int = 0,   // +% global energy output
    val efficiency: Int = 0,   // +% converter crystal yield
    val offlineCap: Int = 0,   // extends the offline earning window
    val tapPower: Int = 0,     // stronger manual core taps
)

data class GameState(
    val resources: Resources = Resources(energy = 4.0),
    val coreLevel: Int = 1,
    val zones: Map<ZoneId, ZoneState> = defaultZones(),
    val upgrades: Upgrades = Upgrades(),
    val settings: Settings = Settings(),
    val profile: Profile = Profile(),
    val stats: Stats = Stats(),
    val claimedQuests: Set<String> = emptySet(),
    val lastRewardEpochMs: Long = 0L,
    val lastSavedEpochMs: Long = 0L,
) {
    val deviceCount: Int get() = zones.values.sumOf { it.devices.size }

    fun countOf(type: DeviceType): Int =
        zones.values.sumOf { z -> z.devices.values.count { it.type == type } }

    companion object {
        fun defaultZones(): Map<ZoneId, ZoneState> =
            ZoneId.entries.associateWith { z ->
                ZoneState(id = z, unlocked = z == ZoneId.ANCIENT_HALL)
            }
    }
}

/**
 * Everything the HUD needs, computed once per tick instead of being recomputed
 * by every composable that wants to show a number.
 */
data class FactoryStats(
    val energyPerSec: Double = 0.0,
    val crystalsPerSec: Double = 0.0,
    val drainPerSec: Double = 0.0,
    val capacity: Double = 0.0,
    val globalMultiplier: Double = 1.0,
    val tapValue: Double = 1.0,
    val converterLoad: Float = 1f,
    /** Per-tile output multiplier coming from neighbouring amplifiers. */
    val tileBoost: Map<Int, Double> = emptyMap(),
)
