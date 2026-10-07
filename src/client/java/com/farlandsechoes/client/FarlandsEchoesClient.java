package com.farlandsechoes.client;

import com.farlandsechoes.FarlandsEchoes;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

public final class FarlandsEchoesClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(FarlandsEchoes.id("echo"));
	private static final KeyMapping RECORD = key("key.farlands_echoes.record");
	private static final KeyMapping STOP = key("key.farlands_echoes.stop");
	private static final KeyMapping PAUSE = key("key.farlands_echoes.pause");

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(com.farlandsechoes.MaskSyncPayload.TYPE,
				(payload, context) -> context.client().execute(() ->
						ClientMaskedProfiles.update(context.client(), payload.playerUuid(), payload.profile())));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientMaskedProfiles.clear());
		KeyBindingHelper.registerKeyBinding(RECORD);
		KeyBindingHelper.registerKeyBinding(STOP);
		KeyBindingHelper.registerKeyBinding(PAUSE);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientMaskedProfiles.applyAll(client);
			runPressed(client, RECORD, "echo record");
			runPressed(client, STOP, "echo stop");
			runPressed(client, PAUSE, "echo pause");
		});
	}

	private static KeyMapping key(String translationKey) {
		return new KeyMapping(translationKey, InputConstants.UNKNOWN.getValue(), CATEGORY);
	}

	private static void runPressed(Minecraft client, KeyMapping mapping, String command) {
		while (mapping.consumeClick()) {
			if (client.getConnection() != null && client.player != null && client.screen == null) {
				client.getConnection().sendCommand(command);
			}
		}
	}
}
