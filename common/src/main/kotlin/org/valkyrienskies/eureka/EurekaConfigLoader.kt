package org.valkyrienskies.eureka

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.mojang.logging.LogUtils
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads `config/vs_eureka.json` and copies its values into [EurekaConfig.SERVER] /
 * [EurekaConfig.CLIENT] at mod init. VS 2.5 vs-core removed `registerConfig`, so this
 * is Eureka's replacement until VS2's ModConfigSpec framework is wired up.
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
    private val CONFIG_FILE: Path = Path.of("config", "vs_eureka.json")

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
            // "server" updates the ADVANCED preset (EurekaConfig.SERVER === ADVANCED).
            tree.get("server")?.takeIf { !it.isMissingNode && !it.isNull }?.let {
                mapper.readerForUpdating(EurekaConfig.SERVER).readValue<Any>(it)
            }
            // "serverVanilla" updates the VANILLA preset. Legacy migration: if absent (pre-feature file),
            // leave VANILLA at its baked 833d445 defaults -- writeConfig() below re-emits the key populated.
            tree.get("serverVanilla")?.takeIf { !it.isMissingNode && !it.isNull }?.let {
                mapper.readerForUpdating(EurekaConfig.VANILLA).readValue<Any>(it)
            }
            tree.get("client")?.takeIf { !it.isMissingNode && !it.isNull }?.let {
                mapper.readerForUpdating(EurekaConfig.CLIENT).readValue<Any>(it)
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

    private fun writeConfig() {
        CONFIG_FILE.parent?.let { Files.createDirectories(it) }
        val wrapper = linkedMapOf(
            "client" to EurekaConfig.CLIENT,
            "server" to EurekaConfig.ADVANCED,
            "serverVanilla" to EurekaConfig.VANILLA
        )
        mapper.writeValue(CONFIG_FILE.toFile(), wrapper)
    }
}
