package org.valkyrienskies.eureka

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.mojang.logging.LogUtils
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads `config/vs_eureka_armada.json` and copies its values into [EurekaConfig.SERVER] /
 * [EurekaConfig.CLIENT] at mod init. VS 2.5 vs-core removed `registerConfig`, so this
 * is Eureka's replacement until VS2's ModConfigSpec framework is wired up.
 *
 * The file is deliberately NOT `vs_eureka.json`: that name belongs to Eureka Ships, and Armada is a
 * different mod with a different set of knobs. Sharing it would mean each mod silently rewriting the
 * other's file -- the re-serialize below drops keys it does not recognise -- so an install that had both,
 * or a player moving between them, would lose a tuned config without ever being told. Armada keeps its
 * own file and leaves Eureka Ships' alone; nothing is read from or written to the old name.
 *
 * The path resolves against MC's working directory (always the instance root), which
 * keeps the loader platform-agnostic — no FabricLoader / Architectury plumbing needed.
 *
 * Behavior:
 * - File missing: write defaults from the current [EurekaConfig.SERVER]/[EurekaConfig.CLIENT] singletons.
 * - File present and parses: merge values onto the live singletons via Jackson's
 *   `readerForUpdating`, then re-serialize so newly-added fields show up on next launch.
 * - File present but malformed: log a warning and fall back to defaults; do NOT overwrite
 *   the user's broken file (so they can fix it).
 */
object EurekaConfigLoader {
    private val LOGGER = LogUtils.getLogger()
    private val CONFIG_FILE: Path = Path.of("config", "vs_eureka_armada.json")

    private val mapper: ObjectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
        // A file written by a NEWER build may carry fields this build has dropped. Without this, one such
        // key fails the whole parse and the entire config silently reverts to defaults -- far worse than
        // ignoring the key. The re-serialize after load drops obsolete keys from the file anyway.
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    @JvmStatic
    fun loadOrCreate() {
        try {
            if (!Files.exists(CONFIG_FILE)) {
                writeConfig()
                LOGGER.info("Created default Eureka config at {}", CONFIG_FILE.toAbsolutePath())
                return
            }

            val tree = mapper.readTree(CONFIG_FILE.toFile())
            // "server" is the base block: assembly-time knobs, buoyancy/lift, and everything shared.
            tree.get("server")?.takeIf { !it.isMissingNode && !it.isNull }?.let {
                mapper.readerForUpdating(EurekaConfig.SERVER).readValue<Any>(it)
            }
            // The three ship categories, SEEDED then OVERLAID. Seeding matters exactly once, on the first
            // load after this feature lands: a file written by an older build has no category blocks at all,
            // and if they simply kept their baked defaults every hand-edit in "server" would be silently
            // dropped for anything a ship actually reads. So each category starts as a copy of the "server"
            // block as just loaded, takes its own handling deltas on top, and only then reads its own key
            // from the file. writeConfig() emits all three in full, so from the second load onward the
            // file's own block governs completely and the seed is inert. Idempotent either way.
            seedCategory(EurekaConfig.BOAT, tree.get("serverBoat"), EurekaConfig::boatDefaults)
            seedCategory(EurekaConfig.AIRSHIP, tree.get("serverAirship"), EurekaConfig::airshipDefaults)
            seedCategory(EurekaConfig.SUBMARINE, tree.get("serverSubmarine"), EurekaConfig::submarineDefaults)
            tree.get("client")?.takeIf { !it.isMissingNode && !it.isNull }?.let {
                mapper.readerForUpdating(EurekaConfig.CLIENT).readValue<Any>(it)
            }
            // Updated IN PLACE like every block above it, which matters more here than elsewhere:
            // PathMessages.Topic holds a lambda per constant reading these fields, so a block that was
            // replaced rather than updated would leave every one of those lambdas pointing at the
            // pre-load object for the rest of the session.
            tree.get("clientMessages")?.takeIf { !it.isMissingNode && !it.isNull }?.let {
                mapper.readerForUpdating(EurekaConfig.MESSAGES).readValue<Any>(it)
            }
            LOGGER.info("Loaded Eureka config from {}", CONFIG_FILE.toAbsolutePath())

            // Re-write so newly-added fields flow into the file and obsolete fields drop out.
            // Safe to do here because we only reach this point if the file parsed cleanly.
            writeConfig()
        } catch (e: Exception) {
            LOGGER.warn(
                "Failed to load Eureka config at {} ({}); using built-in defaults.",
                CONFIG_FILE.toAbsolutePath(), e.message, e
            )
        }
    }

    // Persist the live singletons now (e.g. after a client toggles a CLIENT option in a GUI). Safe to call
    // any time; swallows IO errors so a failed save never breaks the UI action that triggered it.
    @JvmStatic
    fun save() {
        try {
            writeConfig()
        } catch (e: Exception) {
            LOGGER.warn("Failed to save Eureka config at {} ({})", CONFIG_FILE.toAbsolutePath(), e.message, e)
        }
    }

    /**
     * Bring one ship-category preset up to date: copy the freshly-loaded [EurekaConfig.SERVER] over it,
     * apply that category's handling [deltas], then overlay whatever the config file holds for it.
     *
     * The copy goes through the mapper rather than a hand-written field-by-field clone so it can never fall
     * behind the handling field set -- adding a config key needs no change here. It stays correct now that a
     * preset is a [EurekaConfig.ShipHandling] rather than a full [EurekaConfig.Server]: the serialized SERVER
     * carries both halves, and the reader simply drops the global half it has nowhere to put (the mapper has
     * FAIL_ON_UNKNOWN_PROPERTIES disabled). That is also what quietly cleans up an OLD config file, whose
     * category blocks are full of global keys this build no longer wants there.
     */
    private fun seedCategory(
        preset: EurekaConfig.ShipHandling,
        node: JsonNode?,
        deltas: (EurekaConfig.ShipHandling) -> Unit
    ) {
        mapper.readerForUpdating(preset).readValue<Any>(mapper.valueToTree<JsonNode>(EurekaConfig.SERVER))
        deltas(preset)
        node?.takeIf { !it.isMissingNode && !it.isNull }?.let {
            mapper.readerForUpdating(preset).readValue<Any>(it)
        }
    }

    private fun writeConfig() {
        CONFIG_FILE.parent?.let { Files.createDirectories(it) }
        val wrapper = linkedMapOf(
            "client" to EurekaConfig.CLIENT,
            "clientMessages" to EurekaConfig.MESSAGES,
            "server" to EurekaConfig.SERVER,
            "serverBoat" to EurekaConfig.BOAT,
            "serverAirship" to EurekaConfig.AIRSHIP,
            "serverSubmarine" to EurekaConfig.SUBMARINE
        )
        mapper.writeValue(CONFIG_FILE.toFile(), wrapper)
    }
}
