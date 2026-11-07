import config.RunConfiguration
import config.Tuning
import data.RootData
import data.loadRootData
import data.readResourceOrFile

/** recipes.json parsed once for the whole test run. */
object TestData {
    val data: RootData by lazy { loadRootData() }

    fun job(name: String): RunConfiguration = RunConfiguration.load("jobs/$name.json")

    fun text(name: String): String = readResourceOrFile(name)
}

/**
 * Tuning for tests whose machine counts are worked out by hand (and fixtures placed for them): machines may run
 * at full speed, no headroom (planner.max_utilization 1).
 */
val handTuning: Tuning = Tuning().let { it.copy(planner = it.planner.copy(maxUtilization = 1.0)) }
