package org.valkyrienskies.eureka.fabric.mixin.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes Fabric client commands ({@code ClientCommandManager}) that share a root literal with a SERVER
 * command appear in {@code /<root> } tab-completion.
 *
 * <p>Fabric merges client commands into the vanilla suggestion dispatcher in
 * {@code ClientCommandInternals.copyChildren}. For a NON-colliding root that works. For a COLLIDING root it
 * builds the client root node via {@code CommandNode.createBuilder()} (which drops the node's children),
 * {@code addChild}s that now-empty node onto the existing server root (a no-op name merge), and then
 * recurses into the freshly-built-but-discarded node -- so the client subcommands get attached to an orphan
 * that never reaches the suggestion tree. Armada registers a client-side {@code /vs}
 * ({@code cannonball-render-distance}) alongside VS2's server-side {@code /vs}, so that client subcommand never
 * showed up in {@code /vs } tab-completion.
 *
 * <p>This injects right after the command tree is (re)built and copies the subcommands of any colliding
 * client root into the real server root node as completion-only copies. It is order-independent vs Fabric's
 * own merge (both at {@code handleCommands} RETURN) and idempotent (skips names already present). It is
 * suggestion-only: the real command still EXECUTES through Fabric's client dispatcher, which intercepts the
 * input before it is sent to the server.
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListenerCommandMerge {

    @Shadow
    private CommandDispatcher<SharedSuggestionProvider> commands;

    @Inject(method = "handleCommands", at = @At("RETURN"), require = 1)
    private void vs_eureka$mergeCollidingClientSubcommands(final ClientboundCommandsPacket packet, final CallbackInfo ci) {
        final CommandDispatcher<FabricClientCommandSource> clientDispatcher = ClientCommandInternals.getActiveDispatcher();
        if (clientDispatcher == null) {
            return;
        }
        final CommandNode<SharedSuggestionProvider> serverRoot = commands.getRoot();
        for (final CommandNode<FabricClientCommandSource> clientRoot : clientDispatcher.getRoot().getChildren()) {
            final CommandNode<SharedSuggestionProvider> serverNode = serverRoot.getChild(clientRoot.getName());
            // Only a name COLLISION is broken by Fabric; a unique client root is already merged correctly.
            if (serverNode == null) {
                continue;
            }
            for (final CommandNode<FabricClientCommandSource> sub : clientRoot.getChildren()) {
                if (serverNode.getChild(sub.getName()) == null) {
                    serverNode.addChild(vs_eureka$completionCopy(sub));
                }
            }
        }
    }

    /**
     * Deep-copies a client command node into a completion-only node for the vanilla suggestion dispatcher:
     * permission always passes, the executor is a harmless no-op (it is never actually run here -- Fabric's
     * client dispatcher handles execution), and the original node's children are copied recursively. The
     * source-type mismatch (client vs shared) is erased at runtime and safe because the requirement ignores
     * its argument.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CommandNode<SharedSuggestionProvider> vs_eureka$completionCopy(final CommandNode<FabricClientCommandSource> node) {
        final ArgumentBuilder builder = node.createBuilder();
        builder.requires(s -> true);
        if (builder.getCommand() != null) {
            builder.executes(ctx -> 0);
        }
        final CommandNode result = builder.build();
        for (final CommandNode child : node.getChildren()) {
            result.addChild(vs_eureka$completionCopy((CommandNode<FabricClientCommandSource>) child));
        }
        return result;
    }
}
