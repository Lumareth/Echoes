package com.farlandsechoes;

public final class EchoSettings {
	public boolean allowFakePlayers = true;
	public boolean echoHitboxes = false;
	public boolean echoCollisions = false;
	public int maximumGroups = 100;
	public int maximumPlayersInGroup = 40;
	public int maximumEchoes = 100;
	public boolean echoNameTags = true;
	public boolean echoBlockDrops = false;
	public boolean allowDeletingAllEchoes = false;
	public boolean attackFakePlayers = true;
	public boolean echoIDs = false;

	public EchoSettings copy() {
		EchoSettings copy = new EchoSettings();
		copy.allowFakePlayers = allowFakePlayers;
		copy.echoHitboxes = echoHitboxes;
		copy.echoCollisions = echoCollisions;
		copy.maximumGroups = maximumGroups;
		copy.maximumPlayersInGroup = maximumPlayersInGroup;
		copy.maximumEchoes = maximumEchoes;
		copy.echoNameTags = echoNameTags;
		copy.echoBlockDrops = echoBlockDrops;
		copy.allowDeletingAllEchoes = allowDeletingAllEchoes;
		copy.attackFakePlayers = attackFakePlayers;
		copy.echoIDs = echoIDs;
		return copy;
	}

	public void sanitize() {
		maximumGroups = clamp(maximumGroups, 0, 750);
		maximumPlayersInGroup = clamp(maximumPlayersInGroup, 1, 600);
		maximumEchoes = clamp(maximumEchoes, 0, 300);
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
