package org.valkyrienskies.eureka

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.mojang.logging.LogUtils
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads `config/vs_eureka_armada_loottable.json` into [EurekaLootConfig.LOOT] at mod init --
 * [EurekaConfigLoader]'s three-branch behaviour, verbatim, for the loot file:
 *
 * - File missing: write the defaults.
 * - File present and parses: merge onto the live singleton, then RE-WRITE so newly-added fields
 *   materialize and obsolete ones drop out.
 * - File malformed: warn and run on defaults, but never overwrite the user's broken file.
 *
 * Its own file rather than a block in the main config because the tables are BIG -- four lists a
 * hundred entries long would bury the seventy tuning knobs a server owner actually visits -- and
 * because loot is the one region a modpack curates independently of ship handling.
 */
object EurekaLootLoader {
    private val LOGGER = LogUtils.getLogger()
    private val CONFIG_FILE: Path = Path.of("config", "vs_eureka_armada_loottable.json")

    private val mapper: ObjectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
        // One unrecognized key must not silently revert the whole file to defaults.
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    @JvmStatic
    fun loadOrCreate() {
        try {
            if (!Files.exists(CONFIG_FILE)) {
                writeConfig()
                LOGGER.info("Created default Eureka loot table config at {}", CONFIG_FILE.toAbsolutePath())
                return
            }
            mapper.readerForUpdating(EurekaLootConfig.LOOT).readValue<Any>(CONFIG_FILE.toFile())
            LOGGER.info("Loaded Eureka loot table config from {}", CONFIG_FILE.toAbsolutePath())
            writeConfig()
        } catch (e: Exception) {
            LOGGER.warn(
                "Failed to load Eureka loot table config at {} ({}); using built-in defaults.",
                CONFIG_FILE.toAbsolutePath(), e.message, e
            )
        }
    }

    @JvmStatic
    fun save() {
        try {
            writeConfig()
        } catch (e: Exception) {
            LOGGER.warn(
                "Failed to save Eureka loot table config at {} ({})",
                CONFIG_FILE.toAbsolutePath(), e.message, e
            )
        }
    }

    private fun writeConfig() {
        CONFIG_FILE.parent?.let { Files.createDirectories(it) }
        mapper.writeValue(CONFIG_FILE.toFile(), EurekaLootConfig.LOOT)
    }
}
