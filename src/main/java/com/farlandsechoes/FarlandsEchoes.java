package com.farlandsechoes;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FarlandsEchoes implements ModInitializer {
	public static final String MOD_ID = "farlands_echoes";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playS2C().register(MaskSyncPayload.TYPE, MaskSyncPayload.CODEC);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> EchoCommands.register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(EchoManager::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(EchoManager::stop);
		ServerTickEvents.END_SERVER_TICK.register(EchoManager::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> withManager(
				manager -> manager.playerJoined(handler.getPlayer())));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> withManager(
				manager -> manager.playerDisconnected(handler.getPlayer())));
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				withManager(manager -> manager.markUse(serverPlayer, hand));
			}
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				withManager(manager -> {
					manager.notePlayerBlockInteraction(hit);
					manager.markUse(serverPlayer, hand);
					manager.watchBlockInteraction(serverPlayer, hit);
				});
			}
			return InteractionResult.PASS;
		});
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (EchoEntityState.isMount(entity)) {
				return InteractionResult.FAIL;
			}
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				withManager(manager -> manager.markUse(serverPlayer, hand));
			}
			return InteractionResult.PASS;
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (EchoEntityState.isMount(entity)
					|| (EchoEntityState.isEcho(entity) && !EchoEntityState.hasHitbox(entity))
					|| (EchoEntityState.isFake(entity) && !EchoEntityState.canAttackFake(entity))) {
				return InteractionResult.FAIL;
			}
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				withManager(manager -> manager.markAttack(serverPlayer, hand, entity));
			}
			return InteractionResult.PASS;
		});
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				withManager(manager -> {
					manager.notePlayerBlockTouch(pos);
					manager.markSwing(serverPlayer);
					manager.watchBlockAttack(serverPlayer, pos);
				});
			}
			return InteractionResult.PASS;
		});
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				withManager(manager -> manager.markBlockBroken(serverPlayer, pos, state, blockEntity));
			}
		});
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> withManager(manager -> manager.markProjectile(entity)));
		LOGGER.info("Farlands Echoes initialized");
	}

	private static void withManager(java.util.function.Consumer<EchoManager> action) {
		EchoManager manager = EchoManager.current();
		if (manager != null) {
			action.accept(manager);
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
