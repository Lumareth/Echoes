package com.farlandsechoes.client;

import com.farlandsechoes.mixin.PlayerAccessor;
import com.mojang.authlib.GameProfile;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.ResolvableProfile;

public final class ClientMaskedProfiles {
	private static final Map<UUID, MaskedClientProfile> MASKS = new ConcurrentHashMap<>();
	private static final Map<UUID, GameProfile> ORIGINALS = new ConcurrentHashMap<>();

	private ClientMaskedProfiles() {
	}

	public static void update(Minecraft client, UUID uuid, ResolvableProfile syncedProfile) {
		if (syncedProfile == null) {
			MASKS.remove(uuid);
			restore(client, uuid);
		} else {
			GameProfile profile = syncedProfile.partialProfile();
			Supplier<PlayerSkin> skin = client.getSkinManager().createLookup(profile, true);
			MASKS.put(uuid, new MaskedClientProfile(profile, skin));
			apply(client, uuid, profile);
		}
	}

	public static void applyAll(Minecraft client) {
		MASKS.forEach((uuid, masked) -> apply(client, uuid, masked.profile));
	}

	public static String name(UUID uuid) {
		MaskedClientProfile masked = MASKS.get(uuid);
		return masked == null ? null : masked.profile.name();
	}

	public static PlayerSkin skin(UUID uuid) {
		MaskedClientProfile masked = MASKS.get(uuid);
		return masked == null ? null : masked.skin.get();
	}

	public static void clear() {
		MASKS.clear();
		ORIGINALS.clear();
	}

	private static void apply(Minecraft client, UUID uuid, GameProfile masked) {
		if (client.level == null) {
			return;
		}
		Player player = client.level.getPlayerByUUID(uuid);
		if (player == null) {
			return;
		}
		ORIGINALS.putIfAbsent(uuid, player.getGameProfile());
		GameProfile current = player.getGameProfile();
		if (!current.equals(masked)) {
			((PlayerAccessor) player).farlandsEchoes$setGameProfile(masked);
		}
	}

	private static void restore(Minecraft client, UUID uuid) {
		GameProfile original = ORIGINALS.remove(uuid);
		if (original == null || client.level == null) {
			return;
		}
		Player player = client.level.getPlayerByUUID(uuid);
		if (player != null) {
			((PlayerAccessor) player).farlandsEchoes$setGameProfile(original);
		}
	}

	private record MaskedClientProfile(GameProfile profile, Supplier<PlayerSkin> skin) {
	}
}
