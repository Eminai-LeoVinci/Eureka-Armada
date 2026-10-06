package org.valkyrienskies.eureka.fabric.mixin.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.SharedSuggestionProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes SERVER-side {@code /vs} subcommands (e.g. {@code /vs teleport}, {@code /vs get-ship},
 * {@code /vs set-keep-active}) actually reach the server.
 *
 * <p>The problem: Armada registers a CLIENT-side {@code /vs} root ({@code /vs cannonball-render-distance}),
 * so Fabric's client command dispatcher OWNS {@code /vs}. When the player types {@code /vs teleport ...},
 * Fabric's {@link ClientCommandInternals#executeCommand(String)} runs its client dispatcher, matches the
 * {@code vs} literal, then fails on the unknown {@code teleport} child with a {@code dispatcherUnknownArgument}
 * exception. That exception type is NOT in Fabric's ignore-list ({@code isIgnoredException} only forwards
 * {@code dispatcherUnknownCommand}/{@code dispatcherParseException}), so Fabric shows the client-side
 * "incorrect argument ... position 3" error and cancels the send -- the command never reaches the server.
 *
 * <p>Hooking {@code ClientPacketListener#sendCommand} to forward manually does not work either: Fabric hooks
 * BOTH {@code sendCommand} and {@code sendUnsignedCommand}, so any forward attempt is re-intercepted and
 * swallowed the same way.
 *
 * <p>The fix operates at Fabric's decision point instead. At {@code executeCommand} HEAD we test whether the
 * typed command is a fully-valid command in the client's copy of the SERVER command tree that the client
 * dispatcher does NOT own; if so we {@code setReturnValue(false)}. Fabric interprets {@code false} as "not a
 * client command", so it does NOT cancel and the ordinary vanilla signed {@code sendCommand} path delivers the
 * command to the server normally (correctly signed, correct last-seen-messages -- no packet fiddling). Client
 * {@code /vs} subcommands (cannonball-render-distance) are still owned by the client dispatcher, so {@code clientOwns}
 * returns true for them and we leave Fabric to run them exactly as before.
 *
 * <p>The sibling {@link MixinClientPacketListenerCommandMerge} grafts EXECUTABLE copies of the client {@code /vs}
 * subcommands into the server-synced dispatcher for tab-completion, so a server-tree parse of a client
 * subcommand also looks valid; walking the matched leading literals through the client tree ({@code clientOwns})
 * is what disambiguates {@code cannonball-render-distance} (client) from {@code teleport} (server).
 *
 * <p>Our VS2 build used to carry this; it lives in Armada now, because Armada is what adds the client {@code /vs}.
 */
// remap = false: the target class and executeCommand are Fabric API's own (stable names), not Minecraft, so
// they must NOT be run through the MC obfuscation mappings. Minecraft calls in the body below are remapped
// separately by Loom's jar remapper and are unaffected.
@Mixin(value = ClientCommandInternals.class, remap = false)
public abstract class MixinClientCommandInternals {

    @Inject(method = "executeCommand", at = @At("HEAD"), cancellable = true, remap = false)
    private static void vs_eureka$forwardServerOwnedCommand(final String command,
        final CallbackInfoReturnable<Boolean> cir) {
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientPacketListener connection = minecraft.getConnection();
        if (connection == null) {
            return;
        }
        final CommandDispatcher<SharedSuggestionProvider> serverDispatcher = connection.getCommands();
        if (serverDispatcher == null) {
            return;
        }
        final ParseResults<SharedSuggestionProvider> parse =
            serverDispatcher.parse(command, connection.getSuggestionsProvider());
        // Hand back to the server ONLY a command it can fully run that the Fabric client dispatcher does NOT own.
        if (!vs_eureka$isFullyValid(parse) || vs_eureka$clientOwns(parse)) {
            return;
        }
        // false => Fabric does not treat this as a handled client command, so it does not cancel the send and
        // the vanilla signed sendCommand path forwards it to the server.
        cir.setReturnValue(false);
    }

    /** Mirror of vanilla command validity: fully consumed + no exceptions + reaches an executable node. */
    private static boolean vs_eureka$isFullyValid(final ParseResults<?> parse) {
        return !parse.getReader().canRead()
            && parse.getExceptions().isEmpty()
            && parse.getContext().getLastChild().getCommand() != null;
    }

    /**
     * True if the Fabric client dispatcher owns this command's leading subcommand literals (so it MUST run
     * client-side). Walks the matched server-side literal nodes through the client tree; any divergence (a
     * literal the client tree lacks, e.g. {@code teleport}) means the client does not own it.
     */
    private static boolean vs_eureka$clientOwns(final ParseResults<?> parse) {
        final CommandDispatcher<FabricClientCommandSource> client = ClientCommandInternals.getActiveDispatcher();
        if (client == null) {
            return false;
        }
        CommandNode<FabricClientCommandSource> node = client.getRoot();
        boolean walkedLiteral = false;
        for (final ParsedCommandNode<?> parsed : parse.getContext().getNodes()) {
            if (!(parsed.getNode() instanceof LiteralCommandNode)) {
                break; // stop at the first argument -- only subcommand literals identify ownership
            }
            final CommandNode<FabricClientCommandSource> child = node.getChild(parsed.getNode().getName());
            if (child == null) {
                return false; // client tree diverges -> client does not own this command
            }
            node = child;
            walkedLiteral = true;
        }
        return walkedLiteral;
    }
}
