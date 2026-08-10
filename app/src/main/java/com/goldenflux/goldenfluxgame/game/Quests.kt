package com.goldenflux.goldenfluxgame.game

data class Quest(
    val id: String,
    val title: String,
    val detail: String,
    val target: Double,
    val icon: String,
    val reward: Grant,
    val progressOf: (GameState) -> Double,
) {
    fun progress(s: GameState): Float =
        (progressOf(s) / target).coerceIn(0.0, 1.0).toFloat()

    fun isComplete(s: GameState): Boolean = progressOf(s) >= target
}

/**
 * A short ordered campaign that teaches every mechanic and always gives the
 * player a concrete next goal.
 */
object Quests {

    val all: List<Quest> = listOf(
        Quest(
            "tap10", "Awaken the Core", "Tap the Golden Flux Core 10 times",
            10.0, "game/sprites/core.png", Grant(energy = 30.0),
        ) { it.stats.taps.toDouble() },
        Quest(
            "gen1", "First Spark", "Build your first Generator",
            1.0, "game/sprites/generator_1.png", Grant(energy = 60.0),
        ) { it.countOf(DeviceType.GENERATOR).toDouble() },
        Quest(
            "gen3", "Power Grid", "Run 3 Generators at once",
            3.0, "game/sprites/generator_2.png", Grant(energy = 250.0),
        ) { it.countOf(DeviceType.GENERATOR).toDouble() },
        Quest(
            "merge1", "Fusion", "Merge two identical machines",
            1.0, "game/sprites/upgrade_crystal.png", Grant(energy = 400.0),
        ) { it.stats.merges.toDouble() },
        Quest(
            "conv1", "Refinery", "Build a Converter to make crystals",
            1.0, "game/sprites/converter_1.png", Grant(crystals = 15.0),
        ) { it.countOf(DeviceType.CONVERTER).toDouble() },
        Quest(
            "crystal25", "Crystal Harvest", "Produce 25 crystals",
            25.0, "game/sprites/crystal_1.png", Grant(energy = 1_500.0, goldSymbols = 1),
        ) { it.stats.totalCrystalsProduced },
        Quest(
            "core2", "Rising Power", "Upgrade the Core to level 2",
            2.0, "game/sprites/core.png", Grant(crystals = 25.0),
        ) { it.coreLevel.toDouble() },
        Quest(
            "amp1", "Resonance", "Place an Amplifier next to a Generator",
            1.0, "game/sprites/amplifier_1.png", Grant(crystals = 40.0),
        ) { it.countOf(DeviceType.AMPLIFIER).toDouble() },
        Quest(
            "zone2", "Expansion", "Unlock the Golden Archive",
            2.0, "game/sprites/portal.png", Grant(crystals = 60.0, goldSymbols = 2),
        ) { s -> s.zones.values.count { it.unlocked }.toDouble() },
        Quest(
            "energy100k", "Flux Torrent", "Produce 100K energy in total",
            100_000.0, "game/sprites/crystal_2.png", Grant(crystals = 150.0),
        ) { it.stats.totalEnergyProduced },
        Quest(
            "merge10", "Master Engineer", "Merge machines 10 times",
            10.0, "game/sprites/mechanism_1.png", Grant(crystals = 200.0, goldSymbols = 3),
        ) { it.stats.merges.toDouble() },
        Quest(
            "core5", "Golden Age", "Upgrade the Core to level 5",
            5.0, "game/sprites/final_reactor.png", Grant(crystals = 400.0, starArtifacts = 1),
        ) { it.coreLevel.toDouble() },
    )

    /** Quests that are done but not collected yet. */
    fun claimable(s: GameState): List<Quest> =
        all.filter { it.id !in s.claimedQuests && it.isComplete(s) }

    /** The next few goals worth showing, newest objectives first. */
    fun active(s: GameState, limit: Int = 3): List<Quest> =
        all.filter { it.id !in s.claimedQuests }.take(limit)

    fun byId(id: String): Quest? = all.firstOrNull { it.id == id }
}

/** Short, context aware instruction shown above the board. */
object Hints {
    fun current(s: GameState, st: FactoryStats): String? {
        val generators = s.countOf(DeviceType.GENERATOR)
        val genCost = GameLogic.buildCost(DeviceType.GENERATOR, generators)
        return when {
            generators == 0 && s.resources.energy < genCost.energy ->
                "Tap the Golden Core to gather energy"
            generators == 0 ->
                "Tap an empty platform to build your first Generator"
            generators in 1..2 && s.stats.merges == 0 && hasMergePair(s) ->
                "Drag a machine onto an identical one to merge them"
            generators in 1..2 ->
                "Add more Generators — energy income grows with every one"
            s.countOf(DeviceType.CONVERTER) == 0 && s.resources.energy > 150 ->
                "Build a Converter to turn energy into crystals"
            s.countOf(DeviceType.AMPLIFIER) == 0 && s.resources.crystals > 12 ->
                "Amplifiers boost the machines standing next to them"
            s.coreLevel == 1 && s.resources.crystals >= GameLogic.coreUpgradeCost(1).crystals ->
                "Upgrade the Golden Flux Core to multiply everything"
            st.converterLoad < 0.6f ->
                "Your Converters are starving — add Generators"
            else -> null
        }
    }

    private fun hasMergePair(s: GameState): Boolean = s.zones.values.any { zone ->
        zone.devices.values
            .groupBy { it.type to it.level }
            .any { (key, list) -> list.size >= 2 && key.second < key.first.maxLevel }
    }
}
