package com.farlandsechoes;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

public final class EchoEntityState {
	private static final String PREFIX = "farlands_echoes:";

	private EchoEntityState() {
	}

	public static String echoMarker(
			boolean hitbox,
			boolean collision,
			int swingSequence,
			boolean offHand,
			int deathTime,
			int hurtTime
	) {
		return PREFIX + "echo:h" + (hitbox ? "1" : "0")
				+ ":c" + (collision ? "1" : "0")
				+ ":s" + swingSequence
				+ ":a" + (offHand ? "1" : "0")
				+ ":d" + Math.max(0, deathTime)
				+ ":u" + Math.max(0, hurtTime);
	}

	public static String fakeMarker(boolean attackable) {
		return PREFIX + "fake:a" + (attackable ? "1" : "0");
	}

	public static String mountMarker() {
		return PREFIX + "mount";
	}

	public static boolean isEcho(Entity entity) {
		return entity.getTags().contains(EchoManager.ECHO_TAG) || marker(entity).startsWith(PREFIX + "echo:");
	}

	public static boolean isFake(Entity entity) {
		return entity.getTags().contains(EchoManager.FAKE_PLAYER_TAG) || marker(entity).startsWith(PREFIX + "fake");
	}

	public static boolean isMount(Entity entity) {
		return entity.getTags().contains(EchoManager.ECHO_MOUNT_TAG)
				|| marker(entity).startsWith(PREFIX + "mount");
	}

	public static boolean isProtected(Entity entity) {
		return isEcho(entity) || isMount(entity) || (isFake(entity) && !canAttackFake(entity));
	}

	public static boolean canAttackFake(Entity entity) {
		return isFake(entity) && (entity.getTags().contains(EchoManager.FAKE_ATTACKABLE_TAG)
				|| marker(entity).equals(PREFIX + "fake:a1"));
	}

	public static boolean hasHitbox(Entity entity) {
		if (!isEcho(entity)) {
			return false;
		}
		return entity.getTags().contains(EchoManager.ECHO_HITBOX_TAG) || marker(entity).contains(":h1:");
	}

	public static boolean hasCollision(Entity entity) {
		if (!isEcho(entity)) {
			return false;
		}
		String marker = marker(entity);
		return entity.getTags().contains(EchoManager.ECHO_COLLISION_TAG)
				|| marker.contains(":c1:") || marker.endsWith(":c1");
	}

	public static int swingSequence(Entity entity) {
		return markerInt(marker(entity), ":s");
	}

	public static String projectileMarker() {
		return PREFIX + "projectile";
	}

	public static boolean isGhostProjectile(Entity entity) {
		return entity.getTags().contains(EchoManager.ECHO_PROJECTILE_TAG)
				|| marker(entity).equals(projectileMarker());
	}

	public static boolean swingsOffHand(Entity entity) {
		return marker(entity).contains(":a1:");
	}

	public static int deathTime(Entity entity) {
		return markerInt(marker(entity), ":d");
	}

	public static int hurtTime(Entity entity) {
		return markerInt(marker(entity), ":u");
	}

	private static String marker(Entity entity) {
		Component name = entity.getCustomName();
		String insertion = name == null ? null : name.getStyle().getInsertion();
		return insertion == null ? "" : insertion;
	}

	private static int markerInt(String marker, String key) {
		int start = marker.indexOf(key);
		if (start < 0) {
			return 0;
		}
		start += key.length();
		int end = marker.indexOf(':', start);
		if (end < 0) {
			end = marker.length();
		}
		try {
			return Integer.parseInt(marker.substring(start, end));
		} catch (NumberFormatException exception) {
			return 0;
		}
	}
}
