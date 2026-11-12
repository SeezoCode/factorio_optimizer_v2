import com.google.ortools.Loader
import com.google.ortools.linearsolver.MPSolver
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test

/**
 * Tool, skipped unless LPFILE=<in.json>[,<in2.json>...] is set: solves linear programs written by tools/plan-sim/margin.py with
 * the planner's own solver and writes <in>.sol.json beside each. In: {"vars": [{"lb", "ub" (null = none), "obj"}],
 * "cons": [{"lb", "ub" (null = none), "terms": [[var, coefficient], ...]}], "maximize": false}.
 * Use `LPFILE=... ./gradlew test --tests LpFileTest --rerun`.
 */
class LpFileTest {
    @Test
    fun solve() {
        val files = System.getenv("LPFILE") ?: return
        Loader.loadNativeLibraries()
        for (path in files.split(",")) {
            val lp = Json.parseToJsonElement(File(path).readText()).jsonObject
            val solver = MPSolver.createSolver("GLOP") ?: error("GLOP solver unavailable")
            val inf = Double.POSITIVE_INFINITY
            fun num(e: JsonElement?, d: Double) = if (e == null || e is JsonNull) d else e.jsonPrimitive.double
            val vars = lp.getValue("vars").jsonArray.mapIndexed { i, v -> solver.makeNumVar(num(v.jsonObject["lb"], 0.0), num(v.jsonObject["ub"], inf), "v$i") }
            val obj = solver.objective()
            lp.getValue("vars").jsonArray.forEachIndexed { i, v -> num(v.jsonObject["obj"], 0.0).let { if (it != 0.0) obj.setCoefficient(vars[i], it) } }
            lp.getValue("cons").jsonArray.forEachIndexed { k, c ->
                val row = solver.makeConstraint(num(c.jsonObject["lb"], -inf), num(c.jsonObject["ub"], inf), "c$k")
                c.jsonObject.getValue("terms").jsonArray.forEach { t -> row.setCoefficient(vars[t.jsonArray[0].jsonPrimitive.int], t.jsonArray[1].jsonPrimitive.double) }
            }
            if (lp["maximize"]?.jsonPrimitive?.boolean == true) obj.setMaximization() else obj.setMinimization()
            solver.setTimeLimit(300_000)
            val status = solver.solve()
            val ok = status == MPSolver.ResultStatus.OPTIMAL || status == MPSolver.ResultStatus.FEASIBLE
            File("$path.sol.json").writeText(buildJsonObject {
                put("status", status.toString())
                put("objective", if (ok) obj.value() else 0.0)
                put("x", buildJsonArray { vars.forEach { add(if (ok) it.solutionValue() else 0.0) } })
            }.toString())
            println("LPFILE $path: $status objective ${if (ok) obj.value() else Double.NaN}")
        }
    }
}
