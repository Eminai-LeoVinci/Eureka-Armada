@file:Suppress("TooManyFunctions")

package org.valkyrienskies.eureka.util.nbt

import com.mojang.serialization.Codec
import net.minecraft.core.HolderLookup
import java.util.Optional
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.RegistryOps

/**
 * The 1.21.5+ CompoundTag surface, re-created on 1.21.1 as extensions.
 *
 * The Armada codebase is written against the modern CompoundTag, whose getters return Optionals
 * (`getList(key)`), carry defaults (`getIntOr`), and speak codecs directly (`read`/`store`). 1.21.1's
 * CompoundTag predates all of that. Re-creating the surface here -- same names, same semantics --
 * means the ported files keep their bodies verbatim and only add one star-import; the alternative was
 * rewriting several hundred call sites into contains()/get() pairs, each a chance to drop a default.
 *
 * CODEC NOTE: [read]/[store] decode with plain [NbtOps], which is correct for Armada's own codecs
 * (CrewRoster and friends -- pure data, no registry holders). A REGISTRY-sensitive codec (ItemStack,
 * Component) cannot go through these on 1.21.1: it needs RegistryOps, which needs the provider the
 * call site already holds -- those sites are hand-ported to the provider-taking 1.21.1 idioms
 * instead (ItemStack.parseOptional/save, ComponentSerialization with registry ops).
 */

fun CompoundTag.getIntOr(key: String, default: Int): Int =
    if (contains(key, 99)) getInt(key) else default

fun CompoundTag.getLongOr(key: String, default: Long): Long =
    if (contains(key, 99)) getLong(key) else default

fun CompoundTag.getDoubleOr(key: String, default: Double): Double =
    if (contains(key, 99)) getDouble(key) else default

fun CompoundTag.getFloatOr(key: String, default: Float): Float =
    if (contains(key, 99)) getFloat(key) else default

fun CompoundTag.getBooleanOr(key: String, default: Boolean): Boolean =
    if (contains(key, 99)) getBoolean(key) else default

fun CompoundTag.getStringOr(key: String, default: String): String =
    if (contains(key, 8)) getString(key) else default

/**
 * The single-argument list getter. Untyped on purpose where vanilla 1.21.1's two-argument form is
 * typed: the modern API returns whatever list is stored and lets the reader cope, and the ported
 * readers do (they iterate and cast per element).
 */
fun CompoundTag.getListOpt(key: String): Optional<ListTag> =
    Optional.ofNullable(get(key) as? ListTag)

/** Decode [codec] from the value under [key]. Empty when absent or malformed (malformed = skipped, as upstream). */
fun <T : Any> CompoundTag.read(key: String, codec: Codec<T>): Optional<T> {
    val element = get(key) ?: return Optional.empty<T>()
    return codec.parse(NbtOps.INSTANCE, element).result()
}

/** Encode [value] with [codec] under [key]. A value the codec refuses is simply not written, as upstream. */
fun <T> CompoundTag.store(key: String, codec: Codec<T>, value: T) {
    codec.encodeStart(NbtOps.INSTANCE, value).result().ifPresent { put(key, it) }
}

/** [store], tolerating null by writing nothing. */
fun <T : Any> CompoundTag.storeNullable(key: String, codec: Codec<T>, value: T?) {
    if (value != null) store(key, codec, value)
}

/** The modern name for removing a key. */
fun CompoundTag.discard(key: String) = remove(key)

// ---- provider-aware forms, for REGISTRY-sensitive codecs (ItemStack, Component, ...) ----
// The modern ValueInput carries its registry context internally; on 1.21.1 the context is the
// HolderLookup.Provider the save/load signature already hands us, so these take it explicitly.
// Registry ops decode registry-free codecs too, so when a provider is in scope, prefer these.

fun <T : Any> CompoundTag.read(key: String, codec: Codec<T>, provider: HolderLookup.Provider): Optional<T> {
    val element = get(key) ?: return Optional.empty<T>()
    return codec.parse(RegistryOps.create(NbtOps.INSTANCE, provider), element).result()
}

fun <T> CompoundTag.store(key: String, codec: Codec<T>, provider: HolderLookup.Provider, value: T) {
    codec.encodeStart(RegistryOps.create(NbtOps.INSTANCE, provider), value).result()
        .ifPresent { put(key, it) }
}

fun <T : Any> CompoundTag.storeNullable(key: String, codec: Codec<T>, provider: HolderLookup.Provider, value: T?) {
    if (value != null) store(key, codec, provider, value)
}

// ---- Optional-returning getters ----
// The modern CompoundTag's plain getters return Optionals; 1.21.1's members of the same NAMES return
// bare values (and members always beat extensions), so the ported call sites that chain Optional
// operators are renamed to these Opt forms instead -- same semantics, one suffix of difference.

fun CompoundTag.getStringOpt(key: String): Optional<String> =
    if (contains(key, 8)) Optional.of(getString(key)) else Optional.empty()

fun CompoundTag.getIntOpt(key: String): Optional<Int> =
    if (contains(key, 99)) Optional.of(getInt(key)) else Optional.empty()

fun CompoundTag.getLongOpt(key: String): Optional<Long> =
    if (contains(key, 99)) Optional.of(getLong(key)) else Optional.empty()

fun CompoundTag.getDoubleOpt(key: String): Optional<Double> =
    if (contains(key, 99)) Optional.of(getDouble(key)) else Optional.empty()

fun CompoundTag.getFloatOpt(key: String): Optional<Float> =
    if (contains(key, 99)) Optional.of(getFloat(key)) else Optional.empty()

fun CompoundTag.getBooleanOpt(key: String): Optional<Boolean> =
    if (contains(key, 99)) Optional.of(getBoolean(key)) else Optional.empty()

fun CompoundTag.getCompoundOpt(key: String): Optional<CompoundTag> =
    Optional.ofNullable(get(key) as? CompoundTag)

fun CompoundTag.getUUIDOpt(key: String): Optional<java.util.UUID> =
    if (hasUUID(key)) Optional.of(getUUID(key)) else Optional.empty()

// ---- ListTag index getters (the modern branch returns Optionals here too) ----

fun ListTag.getCompoundOpt(index: Int): Optional<CompoundTag> =
    Optional.ofNullable(if (index in 0 until size) get(index) as? CompoundTag else null)

fun ListTag.getStringOpt(index: Int): Optional<String> =
    Optional.ofNullable(if (index in 0 until size) (get(index) as? net.minecraft.nbt.StringTag)?.asString else null)

/** The modern Optional-returning int-array getter, 1.21.1's member returns the bare array. */
fun CompoundTag.getIntArrayOpt(key: String): Optional<IntArray> =
    Optional.ofNullable(get(key)?.let { (it as? net.minecraft.nbt.IntArrayTag)?.asIntArray })

/** The modern Optional-returning long-array getter; 1.21.1's member returns the bare array. */
fun CompoundTag.getLongArrayOpt(key: String): Optional<LongArray> =
    Optional.ofNullable(get(key)?.let { (it as? net.minecraft.nbt.LongArrayTag)?.asLongArray })
