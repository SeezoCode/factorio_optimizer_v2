package ui

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import config.RunConfiguration
import config.Tuning
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The browser UI (`./optimize --ui`, then http://localhost:8765): edit jobs with a form built from the job and tuning
 * types themselves (every setting, its type, default and documentation), start and stop runs, watch them live and
 * look at their layouts, blocks and plans, drawn with the game's own icons when Factorio is installed.
 *
 * It only reads and writes files and starts `./optimize` as a separate process, so the solver knows nothing of it:
 * the UI works with any run folder, also runs started from a terminal. Files are served from the repository folder
 * only (jobs, run folders); the page itself is src/main/resources/ui (read from disk when present, so it can be edited
 * while the server runs).
 */
class Server(private val root: File, private val port: Int) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val compact = Json { encodeDefaults = true }
    private val runs = ConcurrentHashMap<String, Process>()

    fun start() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        println("UI at http://localhost:$port (serving ${root.canonicalPath}); Ctrl+C stops it")
    }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            val q = query(ex)
            when {
                path == "/" || path == "/index.html" -> static(ex, "index.html")
                path.startsWith("/static/") -> static(ex, path.removePrefix("/static/"))
                path == "/api/schema" -> send(ex, schema())
                path == "/api/game" -> send(ex, game(q["data"]))
                path == "/api/jobs" -> send(ex, jobs())
                path == "/api/file" && ex.requestMethod == "GET" -> file(ex, q.getValue("path"))
                path == "/api/file" && ex.requestMethod == "POST" -> saveFile(ex, q.getValue("path"))
                path == "/api/validate" -> validate(ex)
                path == "/api/runs" -> send(ex, runList())
                path == "/api/run" && ex.requestMethod == "GET" -> send(ex, run(q.getValue("dir")))
                path == "/api/run" && ex.requestMethod == "POST" -> startRun(ex)
                path == "/api/stop" -> stopRun(ex, q.getValue("dir"))
                path == "/api/verify" -> startVerify(ex, q.getValue("dir"))
                path == "/api/starve" -> starve(ex, q.getValue("dir"))
                path.startsWith("/api/sprite/") -> sprite(ex, path.removePrefix("/api/sprite/"))
                else -> error(ex, 404, "no such page: $path")
            }
        } catch (e: Exception) {
            runCatching { error(ex, 500, e.message ?: e.toString()) }
        }
    }

    // --- files -------------------------------------------------------------------------------------------------

    /** [p] inside the repository (no escaping it with .. or a symlink), or null. */
    private fun inside(p: String): File? {
        val f = (if (File(p).isAbsolute) File(p) else File(root, p)).canonicalFile
        val r = root.canonicalFile
        val tmp = File(System.getProperty("java.io.tmpdir")).canonicalFile
        // Run folders may also live in the temp folder (scratch runs).
        return f.takeIf { it.path == r.path || it.path.startsWith(r.path + File.separator) || it.path.startsWith(tmp.path + File.separator) || it.path.startsWith("/private/tmp/") }
    }

    private fun file(ex: HttpExchange, p: String) {
        val f = inside(p) ?: return error(ex, 403, "outside the project: $p")
        if (!f.isFile) return error(ex, 404, "no file $p")
        sendBytes(ex, f.readBytes(), contentType(f.name))
    }

    private fun saveFile(ex: HttpExchange, p: String) {
        val f = inside(p) ?: return error(ex, 403, "outside the project: $p")
        if (!f.name.endsWith(".json")) return error(ex, 403, "only .json files can be saved")
        val text = ex.requestBody.readBytes().decodeToString()
        Json.parseToJsonElement(text)   // well-formed at least
        f.parentFile.mkdirs()
        f.writeText(text)
        send(ex, buildJsonObject { put("saved", f.relativeTo(root).path) })
    }

    private fun jobs(): JsonElement = buildJsonArray {
        val dirs = listOf(".", "jobs", "docs/examples", "src/test/resources", "src/test/resources/jobs")
        for (d in dirs) {
            val dir = File(root, d)
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { f ->
                val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
                if (!text.contains("\"targets\"")) return@forEach
                add(JsonPrimitive(f.relativeTo(root).path))
            }
        }
    }

    private fun validate(ex: HttpExchange) {
        val text = ex.requestBody.readBytes().decodeToString()
        val r = runCatching { RunConfiguration.parse(text) }
        send(ex, buildJsonObject {
            put("ok", r.isSuccess)
            r.exceptionOrNull()?.let { put("error", it.message ?: it.toString()) }
        })
    }

    // --- runs --------------------------------------------------------------------------------------------------

    private fun runList(): JsonElement {
        val dirs = ArrayList<File>()
        for (d in listOf("solves", "out", "src/main/resources/solves", "docs/examples")) {
            File(root, d).listFiles { f -> f.isDirectory }?.forEach { dirs += it }
        }
        runs.keys.map { File(it) }.filter { it !in dirs }.forEach { dirs += it }
        return buildJsonArray {
            dirs.filter { File(it, "plan.json").isFile || File(it, "best_blueprint.json").isFile || File(it, "status.json").isFile || runs.containsKey(it.canonicalPath) }
                .sortedByDescending { it.lastModified() }
                .forEach { d ->
                    add(buildJsonObject {
                        put("dir", d.relativeOrAbsolute())
                        put("modified", d.lastModified())
                        put("running", runs[d.canonicalPath]?.isAlive == true)
                        put("factory", File(d, "status.json").isFile)
                    })
                }
        }
    }

    private fun File.relativeOrAbsolute(): String = runCatching { canonicalFile.relativeTo(root.canonicalFile).path.takeIf { !it.startsWith("..") } }.getOrNull() ?: canonicalPath

    private fun run(dir: String): JsonElement {
        val d = inside(dir) ?: error("outside the project: $dir")
        fun read(name: String) = File(d, name).takeIf { it.isFile }?.let { f -> runCatching { Json.parseToJsonElement(f.readText()) }.getOrNull() }
        val log = listOf("ui_run.log", "log.txt", "factory.txt").map { File(d, it) }.firstOrNull { it.isFile }
        return buildJsonObject {
            put("dir", d.relativeOrAbsolute())
            put("running", runs[d.canonicalPath]?.isAlive == true)
            put("verifying", runs[d.canonicalPath + "#verify"]?.isAlive == true)
            File(d, "verify.log").takeIf { it.isFile }?.let { put("verifyLog", tail(it, 60)) }
            read("status.json")?.let { put("status", it) }
            read("ports.json")?.let { put("ports", it) }
            read("verify_report.json")?.let { r -> put("verify", buildJsonObject {
                (r as? JsonObject)?.get("outputs")?.let { put("outputs", it) }
                (r as? JsonObject)?.get("machines")?.let { ms -> put("machines", JsonPrimitive((ms as JsonArray).size)) }
            }) }
            put("log", log?.let { JsonPrimitive(tail(it, 200)) } ?: JsonNull)
            putJsonArray("files") { d.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { add(JsonPrimitive(it.name)) } }
            putJsonArray("blocks") {
                d.listFiles { f -> f.isDirectory && f.name.startsWith("block_") }?.sortedBy { it.name.removePrefix("block_").toIntOrNull() ?: 0 }?.forEach { b ->
                    add(buildJsonObject {
                        put("name", b.name)
                        put("blueprint", File(b, "best_blueprint.json").isFile)
                        put("image", File(b, "best.png").isFile)
                    })
                }
            }
        }
    }

    private fun tail(f: File, lines: Int): String = f.readLines().takeLast(lines).joinToString("\n")

    private fun startRun(ex: HttpExchange) {
        val body = Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject
        val config = body["config"]?.jsonPrimitive?.content ?: return error(ex, 400, "no config")
        val out = body["out"]?.jsonPrimitive?.content ?: return error(ex, 400, "no out folder")
        val cf = inside(config) ?: return error(ex, 403, "config outside the project")
        val od = inside(out) ?: return error(ex, 403, "out folder outside the project")
        if (runs[od.canonicalPath]?.isAlive == true) return error(ex, 409, "already running in $out")
        od.mkdirs()
        val cmd = mutableListOf(File(root, "optimize").path, "--config=${cf.path}", "--out=${od.path}", "--plain")
        body["ram"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { cmd += "--ram=$it" }
        if (body["resume"]?.jsonPrimitive?.content == "true") cmd += "--resume"
        val p = ProcessBuilder(cmd).directory(root).redirectErrorStream(true).redirectOutput(File(od, "ui_run.log")).start()
        runs[od.canonicalPath] = p
        send(ex, buildJsonObject { put("started", od.relativeOrAbsolute()); put("command", cmd.joinToString(" ")) })
    }

    private fun stopRun(ex: HttpExchange, dir: String) {
        val d = inside(dir) ?: return error(ex, 403, "outside the project")
        // A factory of blocks stops cleanly through its control file (it finishes the best factory so far).
        File(d, "control").writeText("stop\n")
        val p = runs[d.canonicalPath]
        if (p != null && !File(d, "status.json").isFile) p.destroy()
        send(ex, buildJsonObject { put("stopping", d.relativeOrAbsolute()) })
    }

    /** Builds the run's test copy in the real game (tools/factorio-verify/verify.py, with belt dumps for starve.py). */
    private fun startVerify(ex: HttpExchange, dir: String) {
        val d = inside(dir) ?: return error(ex, 403, "outside the project")
        val key = d.canonicalPath + "#verify"
        if (runs[key]?.isAlive == true) return error(ex, 409, "already verifying")
        if (!File(d, "test_output_string.txt").isFile) return error(ex, 400, "the run has no test copy yet")
        val cmd = listOf("python3", File(root, "tools/factorio-verify/verify.py").path, d.path, "--dump")
        runs[key] = ProcessBuilder(cmd).directory(root).redirectErrorStream(true).redirectOutput(File(d, "verify.log")).start()
        send(ex, buildJsonObject { put("verifying", d.relativeOrAbsolute()) })
    }

    /** Why machines starved in game (tools/factorio-verify/starve.py on the run's verify_report.json). */
    private fun starve(ex: HttpExchange, dir: String) {
        val d = inside(dir) ?: return error(ex, 403, "outside the project")
        val p = ProcessBuilder("python3", File(root, "tools/factorio-verify/starve.py").path, d.path).directory(root).redirectErrorStream(true).start()
        val text = p.inputStream.readBytes().decodeToString()
        p.waitFor()
        send(ex, buildJsonObject { put("text", text) })
    }

    // --- game data and sprites ---------------------------------------------------------------------------------

    private val gameCache = ConcurrentHashMap<String, JsonElement>()

    /** Entity sizes, recipes and items of the game data (built-in recipes.json, or a job's "data" file). */
    private fun game(data: String?): JsonElement = gameCache.getOrPut(data ?: "") {
        val text = data?.let { inside(it)?.takeIf { f -> f.isFile }?.readText() }
            ?: javaClass.classLoader.getResource("recipes.json")?.readText()
            ?: File(root, "src/main/resources/recipes.json").readText()
        val d = Json.parseToJsonElement(text).jsonObject
        buildJsonObject {
            putJsonObject("entities") {
                d["entities"]?.jsonObject?.forEach { (name, e) ->
                    val o = e as? JsonObject ?: return@forEach
                    val w = (o["width"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: (o["tile_width"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 1
                    val h = (o["height"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: (o["tile_height"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 1
                    putJsonObject(name) { put("w", w); put("h", h); o["type"]?.let { put("type", it) } }
                }
                // Belts, inserters, poles and the like (data.logistics): 1 x 1 unless the game says otherwise.
                (d["logistics"] as? JsonObject)?.forEach { (_, group) ->
                    (group as? JsonArray)?.forEach { e -> (e as? JsonObject)?.get("name")?.jsonPrimitive?.content?.let { n ->
                        if (d["entities"]?.jsonObject?.containsKey(n) != true) putJsonObject(n) {
                            val w = (e["width"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: if (n.endsWith("splitter")) 2 else 1
                            val h = (e["height"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 1
                            put("w", w); put("h", h)
                        }
                    } }
                }
            }
            putJsonArray("recipes") { d["recipes"]?.jsonObject?.keys?.sorted()?.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("items") { d["items"]?.jsonObject?.keys?.sorted()?.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("fluids") { d["fluids"]?.jsonObject?.keys?.sorted()?.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("machines") {
                d["entities"]?.jsonObject?.forEach { (name, e) ->
                    val o = e as? JsonObject ?: return@forEach
                    if (o["crafting_categories"] != null) add(JsonPrimitive(name))
                }
            }
        }
    }

    /** Where the game keeps its graphics (the Steam install on a Mac; FACTORIO_DATA overrides). */
    private val gameData: File? = listOfNotNull(
        System.getenv("FACTORIO_DATA"),
        System.getProperty("user.home") + "/Library/Application Support/Steam/steamapps/common/Factorio/factorio.app/Contents/data",
        System.getProperty("user.home") + "/.steam/steam/steamapps/common/Factorio/data",
        "C:/Program Files (x86)/Steam/steamapps/common/Factorio/data",
    ).map(::File).firstOrNull { File(it, "base/graphics/icons").isDirectory }

    /** An item, fluid or entity icon from the game (the largest mipmap), or 404 without the game. */
    private fun sprite(ex: HttpExchange, file: String) {
        val name = URLDecoder.decode(file, Charsets.UTF_8).removeSuffix(".png")
        if (!name.matches(Regex("[a-z0-9_-]+"))) return error(ex, 400, "bad sprite name")
        val g = gameData ?: return error(ex, 404, "Factorio not found (set FACTORIO_DATA to its data folder)")
        val mods = listOf("base", "space-age", "quality", "elevated-rails")
        val f = mods.flatMap { m -> listOf("$m/graphics/icons/$name.png", "$m/graphics/icons/fluid/$name.png") }.map { File(g, it) }.firstOrNull { it.isFile }
            ?: return error(ex, 404, "no icon for $name")
        ex.responseHeaders.add("Cache-Control", "max-age=86400")
        sendBytes(ex, f.readBytes(), "image/png")
    }

    // --- the settings' schema, from the types themselves --------------------------------------------------------

    /**
     * The job and tuning settings as a tree: each field's JSON name, kind, whether it may be left out, its default
     * (from the type's own defaults) and its documentation (the KDoc above it in the source, when the source is here).
     */
    private fun schema(): JsonElement {
        val docs = docComments()
        val jobDefaults = runCatching { compact.encodeToJsonElement(RunConfiguration.serializer(), RunConfiguration.parse("""{"targets": []}""")) }.getOrNull()
        val tuningDefaults = compact.encodeToJsonElement(Tuning.serializer(), Tuning.defaults())
        return buildJsonObject {
            put("job", describe(RunConfiguration.serializer().descriptor, jobDefaults, docs, HashSet()))
            put("tuning", describe(Tuning.serializer().descriptor, tuningDefaults, docs, HashSet()))
            put("gameIcons", gameData != null)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun describe(d: SerialDescriptor, default: JsonElement?, docs: Map<String, String>, seen: HashSet<String>): JsonElement = buildJsonObject {
        put("kind", kindName(d.kind))
        put("type", d.serialName.substringAfterLast('.').removeSuffix("?"))
        if (d.isNullable) put("nullable", true)
        default?.let { put("default", it) }
        when (d.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val type = d.serialName.removeSuffix("?")
                if (!seen.add(type)) return@buildJsonObject
                putJsonArray("fields") {
                    for (i in 0 until d.elementsCount) {
                        val name = d.getElementName(i)
                        val ed = d.getElementDescriptor(i)
                        val def = (default as? JsonObject)?.get(name)
                        add(buildJsonObject {
                            put("name", name)
                            put("optional", d.isElementOptional(i))
                            docs["${type.substringAfterLast('.')}.$name"]?.let { put("doc", it) }
                            put("schema", describe(ed, def, docs, HashSet(seen)))
                        })
                    }
                }
            }
            StructureKind.LIST -> put("items", describe(d.getElementDescriptor(0), (default as? JsonArray)?.firstOrNull(), docs, HashSet(seen)))
            StructureKind.MAP -> {
                put("keys", describe(d.getElementDescriptor(0), null, docs, HashSet(seen)))
                put("values", describe(d.getElementDescriptor(1), null, docs, HashSet(seen)))
            }
            else -> {}
        }
    }

    private fun kindName(k: SerialKind): String = when (k) {
        PrimitiveKind.BOOLEAN -> "boolean"
        PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> "integer"
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> "number"
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
        StructureKind.LIST -> "list"
        StructureKind.MAP -> "map"
        StructureKind.CLASS, StructureKind.OBJECT -> "object"
        is PolymorphicKind -> "json"
        else -> if (k.toString().contains("ENUM")) "string" else "json"
    }

    /**
     * KDoc of each property in config/ (Type.json_name -> text): the comment right above `val x` or
     * `@SerialName("x") val x` inside `data class Type(`.
     */
    private fun docComments(): Map<String, String> {
        val out = HashMap<String, String>()
        val dir = File(root, "src/main/kotlin/config")
        val files = dir.listFiles { f -> f.name.endsWith(".kt") } ?: return out
        val classRe = Regex("""^\s*(?:data\s+)?class\s+(\w+)\s*\(""")
        val propRe = Regex("""^\s*(?:@SerialName\("([^"]+)"\)\s*)?(?:override\s+)?val\s+(\w+)\s*:""")
        for (f in files) {
            var cls: String? = null
            var pending: String? = null
            val doc = StringBuilder()
            var inDoc = false
            for (line in f.readLines()) {
                val t = line.trim()
                classRe.find(line)?.let { cls = it.groupValues[1]; pending = null }
                if (t.startsWith("/**")) { inDoc = true; doc.clear() }
                if (inDoc) {
                    doc.append(t.removePrefix("/**").removeSuffix("*/").removePrefix("*").trim()).append(' ')
                    if (t.endsWith("*/")) { inDoc = false; pending = doc.toString().trim().replace(Regex("\\s+"), " ") }
                    continue
                }
                val m = propRe.find(line)
                if (m != null && cls != null) {
                    val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
                    pending?.let { out["$cls.$name"] = it }
                    pending = null
                } else if (t.isNotEmpty() && !t.startsWith("@") && !t.startsWith("//")) pending = null
            }
        }
        return out
    }

    // --- plumbing ----------------------------------------------------------------------------------------------

    private fun static(ex: HttpExchange, name: String) {
        if (name.contains("..")) return error(ex, 400, "bad path")
        val disk = File(root, "src/main/resources/ui/$name")
        val bytes = if (disk.isFile) disk.readBytes() else javaClass.classLoader.getResource("ui/$name")?.readBytes() ?: return error(ex, 404, "no $name")
        sendBytes(ex, bytes, contentType(name))
    }

    private fun contentType(name: String) = when (name.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"; "js" -> "text/javascript; charset=utf-8"; "css" -> "text/css; charset=utf-8"
        "json" -> "application/json"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "svg" -> "image/svg+xml"
        else -> "text/plain; charset=utf-8"
    }

    private fun query(ex: HttpExchange): Map<String, String> = (ex.requestURI.rawQuery ?: "").split('&').filter { it.contains('=') }
        .associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }

    private fun send(ex: HttpExchange, body: JsonElement) = sendBytes(ex, json.encodeToString(JsonElement.serializer(), body).toByteArray(), "application/json")

    private fun sendBytes(ex: HttpExchange, bytes: ByteArray, type: String, code: Int = 200) {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun error(ex: HttpExchange, code: Int, msg: String) =
        sendBytes(ex, json.encodeToString(JsonElement.serializer(), buildJsonObject { put("error", msg) }).toByteArray(), "application/json", code)
}
