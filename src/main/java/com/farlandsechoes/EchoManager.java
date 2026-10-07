package com.farlandsechoes;

import com.farlandsechoes.mixin.MannequinAccessor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.util.ProblemReporter;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class EchoManager {
	public static final String ECHO_TAG = "farlands_echoes.echo";
	public static final String ECHO_HITBOX_TAG = "farlands_echoes.hitbox";
	public static final String ECHO_COLLISION_TAG = "farlands_echoes.collision";
	public static final String FAKE_PLAYER_TAG = "farlands_echoes.fake_player";
	public static final String FAKE_ATTACKABLE_TAG = "farlands_echoes.fake_attackable";
	public static final String ECHO_PROJECTILE_TAG = "farlands_echoes.projectile";
	public static final String ECHO_MOUNT_TAG = "farlands_echoes.mount";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int INVITE_LIFETIME_TICKS = 20 * 60;
	private static final String NO_COLLISION_TEAM = "fe_no_collision";
	private static EchoManager instance;

	private final MinecraftServer server;
	private final Path saveFile;
	private final List<SavedEcho> echoes = new ArrayList<>();
	private final List<EchoGroup> groups = new ArrayList<>();
	private final Map<String, MaskProfile> masks = new HashMap<>();
	private final Set<RecordingSession> recordingSessions = new LinkedHashSet<>();
	private final Map<UUID, RecordingSession> sessionsByPlayer = new HashMap<>();
	private final Map<UUID, PendingInvite> pendingInvites = new HashMap<>();
	private final Map<String, UUID> playbackVehicles = new HashMap<>();

	private EchoSettings settings = new EchoSettings();

	private EchoManager(MinecraftServer server) {
		this.server = server;
		this.saveFile = server.getWorldPath(LevelResource.ROOT)
				.resolve("farlands_echoes")
				.resolve("echoes.json");
		load();
	}

	public static EchoManager get() {
		if (instance == null) {
			throw new IllegalStateException("Echo manager is not running");
		}
		return instance;
	}

	public static EchoManager current() {
		return instance;
	}

	public static void start(MinecraftServer server) {
		instance = new EchoManager(server);
	}

	public static void stop(MinecraftServer server) {
		if (instance != null) {
			instance.prepareForShutdown();
			instance.save();
			instance = null;
		}
	}

	public static void tick(MinecraftServer server) {
		if (instance != null) {
			instance.maintainFakeMembers();
			instance.captureTick();
			instance.playbackTick();
			instance.expireInvites();
		}
	}

	public EchoSettings settings() {
		return settings;
	}

	public void settingsChanged() {
		settings.sanitize();
		save();
	}

	public OperationResult beginRecording(ServerPlayer player) {
		RecordingSession existing = sessionsByPlayer.get(player.getUUID());
		if (existing != null) {
			if (!existing.controllerUuid.equals(player.getUUID())) {
				return failure("Only " + existing.controllerName + " can control this recording.");
			}
			if (!existing.paused) {
				return failure("Already recording.");
			}
			existing.paused = false;
			return success("Recording resumed.");
		}
		if (settings.maximumEchoes == 0) {
			return failure("Echoes are disabled.");
		}

		EchoGroup group = findGroup(player.getUUID());
		List<RecordingTrack> tracks = recordingTracksFor(player);
		if (tracks.isEmpty()) {
			return failure("No players to record.");
		}
		if (echoes.size() + tracks.size() > settings.maximumEchoes) {
			return failure("Too many echoes!");
		}

		RecordingSession session = new RecordingSession(player, tracks, group == null ? null : group.id);
		recordingSessions.add(session);
		for (RecordingTrack track : tracks) {
			if (!track.fake) {
				sessionsByPlayer.put(track.sourceUuid, session);
				ServerPlayer participant = server.getPlayerList().getPlayer(track.sourceUuid);
				if (group != null && participant != null) {
					participant.sendSystemMessage(Component.literal(player.getScoreboardName()
							+ " started an echo recording. You are part of this recording.")
							.withStyle(ChatFormatting.AQUA));
				}
			}
		}
		return success("Recording started (" + tracks.size() + ").");
	}

	public OperationResult pauseRecording(ServerPlayer player) {
		RecordingSession session = sessionsByPlayer.get(player.getUUID());
		if (session == null) {
			return failure("Not recording.");
		}
		if (!session.controllerUuid.equals(player.getUUID())) {
			return failure("Only " + session.controllerName + " can pause it.");
		}
		if (session.paused) {
			return failure("Already paused.");
		}
		session.paused = true;
		return success("Recording paused.");
	}

	public OperationResult finishRecording(ServerPlayer player) {
		RecordingSession session = sessionsByPlayer.get(player.getUUID());
		if (session == null) {
			return failure("Not recording.");
		}
		if (!canStopSession(session, player)) {
			return failure("Only " + session.controllerName + " can stop it.");
		}
		for (RecordingTrack track : session.tracks) {
			if (track.frames.size() < 2) {
			return failure("Recording too short.");
			}
		}
		if (echoes.size() + session.tracks.size() > settings.maximumEchoes) {
			return failure("Too many echoes!");
		}

		removeSession(session);
		int created = 0;
		for (RecordingTrack track : session.tracks) {
			if (createPlaybackEcho(track)) {
				created++;
			}
		}
		save();
		return created == 0
				? failure("Could not create echoes.")
				: success("Saved " + created + " echo(es).");
	}

	public List<String> echoNames(Boolean enabled) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (SavedEcho echo : echoes) {
			if (enabled == null || echo.isEnabled() == enabled) {
				names.add(Integer.toString(echo.numericId));
				names.add(echo.name);
			}
		}
		return new ArrayList<>(names);
	}

	public Component echoList() {
		if (echoes.isEmpty()) {
			return Component.literal("No echoes have been created.");
		}
		Component result = Component.literal("Echoes (oldest first):");
		for (int index = 0; index < echoes.size(); index++) {
			SavedEcho echo = echoes.get(index);
			String alias = echo.name.equals(Integer.toString(echo.numericId)) ? "" : " — " + echo.name;
			String source = echo.sourceName == null ? "" : " [" + echo.sourceName + "]";
			String suffix = echo.isEnabled() ? "" : " (disabled)";
			result = result.copy().append(Component.literal("\n" + (index + 1) + ". #" + echo.numericId + alias + source + suffix)
					.withStyle(echo.isEnabled() ? ChatFormatting.AQUA : ChatFormatting.GRAY));
		}
		return result;
	}

	public OperationResult renameEcho(String oldName, String newName) {
		SavedEcho echo = findEcho(oldName);
		if (echo == null) {
			return failure("Unknown echo: " + oldName);
		}
		String cleaned = newName.trim();
		if (cleaned.isEmpty() || cleaned.length() > 64 || cleaned.equalsIgnoreCase("all")) {
			return failure("Echo aliases must contain 1-64 characters and cannot be 'all'.");
		}
		try {
			int numeric = Integer.parseInt(cleaned);
			if (numeric != echo.numericId && findEchoById(numeric) != null) {
				return failure("That number is already another echo's ID.");
			}
		} catch (NumberFormatException ignored) {
		}
		SavedEcho collision = findEcho(cleaned);
		if (collision != null && collision != echo) {
			return failure("An echo named '" + cleaned + "' already exists.");
		}
		echo.name = cleaned;
		Mannequin entity = resolveEchoEntity(echo);
		if (entity != null) {
			applyEntitySettings(echo, entity);
		}
		save();
		return success("Renamed: " + cleaned);
	}

	public OperationResult deleteEcho(String name) {
		SavedEcho echo = findEcho(name);
		if (echo == null) {
			return failure("Unknown echo: " + name);
		}
		resetEchoWorld(echo);
		cleanupProjectiles(echo);
		Mannequin entity = resolveEchoEntity(echo);
		if (entity != null) {
			clearBreakProgress(echo, entity);
			detachEchoVehicle(entity);
			clearCollisionTeam(entity);
			entity.discard();
		}
		echoes.remove(echo);
		save();
		return success("Deleted: " + echo.name);
	}

	public OperationResult deleteAllEchoes() {
		if (!settings.allowDeletingAllEchoes) {
			return failure("Deleting all echoes is disabled.");
		}
		int count = echoes.size();
		for (SavedEcho echo : new ArrayList<>(echoes)) {
			resetEchoWorld(echo);
			cleanupProjectiles(echo);
			Mannequin entity = resolveEchoEntity(echo);
			if (entity != null) {
				clearBreakProgress(echo, entity);
				detachEchoVehicle(entity);
				clearCollisionTeam(entity);
				entity.discard();
			}
		}
		for (String recordingId : echoes.stream().map(echo -> echo.recordingId)
				.filter(java.util.Objects::nonNull).distinct().toList()) {
			cleanupVehicles(recordingId);
		}
		echoes.clear();
		save();
		return success("Deleted " + count + " echo(es).");
	}

	public OperationResult setEchoEnabled(String name, boolean enabled) {
		SavedEcho echo = findEcho(name);
		if (echo == null) {
			return failure("Unknown echo: " + name);
		}
		if (echo.isEnabled() == enabled) {
			return failure("Echo '" + echo.name + "' is already " + (enabled ? "enabled" : "disabled") + ".");
		}
		int startFrame = 0;
		boolean resetOnNextTick = enabled;
		if (!enabled) {
			resetEchoWorld(echo);
			cleanupProjectiles(echo);
			Mannequin entity = resolveEchoEntity(echo);
			if (entity != null) {
				clearBreakProgress(echo, entity);
				detachEchoVehicle(entity);
				clearCollisionTeam(entity);
				entity.discard();
			}
			echo.entityUuid = null;
			echo.enabled = false;
		} else {
			SavedEcho sibling = activeRecordingSibling(echo);
			if (sibling != null) {
				startFrame = sibling.frameIndex;
				resetOnNextTick = false;
			}
			echo.enabled = true;
			echo.frameIndex = startFrame;
			if (!spawnSavedEcho(echo)) {
				echo.enabled = false;
				return failure("Minecraft could not recreate echo '" + echo.name + "'.");
			}
		}
		echo.frameIndex = startFrame;
		echo.needsReset = resetOnNextTick;
		save();
		return success((enabled ? "Enabled: " : "Disabled: ") + echo.name);
	}

	private SavedEcho activeRecordingSibling(SavedEcho echo) {
		if (echo.recordingId == null) {
			return null;
		}
		for (SavedEcho candidate : echoes) {
			if (candidate != echo && candidate.isEnabled()
					&& echo.recordingId.equals(candidate.recordingId)
					&& candidate.frames != null && !candidate.frames.isEmpty()) {
				return candidate;
			}
		}
		return null;
	}

	public OperationResult syncEcho(String name) {
		SavedEcho echo = findEcho(name);
		if (echo == null) {
			return failure("Unknown echo: " + name);
		}
		echo.settings = settings.copy();
		applyEntitySettings(echo, resolveEchoEntity(echo));
		save();
		return success("Synced: " + echo.name);
	}

	public OperationResult syncAllEchoes() {
		for (SavedEcho echo : echoes) {
			echo.settings = settings.copy();
			applyEntitySettings(echo, resolveEchoEntity(echo));
		}
		save();
		return success("Synced " + echoes.size() + " echo(es).");
	}

	public OperationResult teleportToEcho(ServerPlayer player, String name) {
		SavedEcho echo = findEcho(name);
		if (echo == null) {
			return failure("Unknown echo: " + name);
		}
		ServerLevel level = level(echo.dimension);
		if (level == null || echo.frames.isEmpty()) {
			return failure("The echo's dimension is unavailable.");
		}
		Mannequin entity = resolveEchoEntity(echo);
		EchoFrame frame = echo.frames.get(Math.min(echo.frameIndex, echo.frames.size() - 1));
		double x = entity == null ? frame.x : entity.getX();
		double y = entity == null ? frame.y : entity.getY();
		double z = entity == null ? frame.z : entity.getZ();
		player.teleportTo(level, x, y, z, Set.of(), frame.yaw, frame.pitch, false);
		return success("Teleported: " + echo.name);
	}

	public OperationResult setMask(ServerPlayer player, String profileName) {
		String cleaned = profileName.trim();
		if (!cleaned.matches("[A-Za-z0-9_]{1,16}")) {
			return failure("Invalid player name.");
		}
		GameProfile targetProfile = fetchProfile(cleaned);
		if (targetProfile == null) {
			return failure("Profile not found: " + cleaned);
		}
		MaskProfile previous = masks.get(player.getUUID().toString());
		MaskProfile mask = new MaskProfile();
		mask.name = targetProfile.name();
		masks.put(player.getUUID().toString(), mask);
		try {
			refreshMaskDisplay(player);
			updateGroupMemberName(player.getUUID(), targetProfile.name());
			broadcastMask(player.getUUID(), targetProfile);
			save();
			return success("Mask: " + targetProfile.name());
		} catch (RuntimeException exception) {
			if (previous == null) {
				masks.remove(player.getUUID().toString());
			} else {
				masks.put(player.getUUID().toString(), previous);
			}
			FarlandsEchoes.LOGGER.error("Could not apply mask {} to {}", cleaned, player.getUUID(), exception);
			return failure("Mask failed. Check the server log.");
		}
	}

	public OperationResult clearMask(ServerPlayer player) {
		MaskProfile removed = masks.remove(player.getUUID().toString());
		if (removed == null) {
			return failure("No mask set.");
		}
		try {
			refreshMaskDisplay(player);
			updateGroupMemberName(player.getUUID(), player.getGameProfile().name());
			broadcastMask(player.getUUID(), null);
			save();
			return success("Mask cleared.");
		} catch (RuntimeException exception) {
			masks.put(player.getUUID().toString(), removed);
			FarlandsEchoes.LOGGER.error("Could not clear mask for {}", player.getUUID(), exception);
			return failure("Could not clear mask.");
		}
	}

	public OperationResult createGroup(ServerPlayer leader) {
		if (findGroup(leader.getUUID()) != null) {
			return failure("You are already in an echo group.");
		}
		if (settings.maximumGroups == 0) {
			return failure("Groups are disabled!");
		}
		if (groups.size() >= settings.maximumGroups) {
			return failure("Too many groups!");
		}
		EchoGroup group = new EchoGroup();
		group.id = UUID.randomUUID().toString();
		group.leaderUuid = leader.getUUID().toString();
		group.leaderName = leader.getScoreboardName();
		group.members.add(GroupMember.real(leader));
		groups.add(group);
		refreshCommands(leader);
		save();
		return success("Group created.");
	}

	public OperationResult leaveGroup(ServerPlayer player) {
		EchoGroup group = findGroup(player.getUUID());
		if (group == null) {
			return failure("You are not in an echo group.");
		}
		if (group.isLeader(player.getUUID())) {
			return failure("Group leaders must use /echo group disband.");
		}
		if (sessionsByPlayer.containsKey(player.getUUID())) {
			return failure("You cannot leave while the group is recording.");
		}
		group.members.removeIf(member -> member.matches(player.getUUID()));
		refreshCommands(player);
		save();
		return success("Left group.");
	}

	public OperationResult disbandGroup(ServerPlayer player) {
		EchoGroup group = findGroup(player.getUUID());
		if (group == null) {
			return failure("You have no echo group to disband.");
		}
		if (!group.isLeader(player.getUUID())) {
			return failure("Only an echo group leader can disband the group.");
		}
		if (groupHasRecording(group)) {
			return failure("Stop the active group recording before disbanding.");
		}
		for (GroupMember member : group.members) {
			removeFakeMemberEntity(member);
		}
		groups.remove(group);
		pendingInvites.values().removeIf(invite -> invite.groupId.equals(group.id));
		for (GroupMember member : group.members) {
			if (!member.fake) {
				try {
					ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(member.uuid));
					if (online != null) {
						refreshCommands(online);
					}
				} catch (IllegalArgumentException ignored) {
				}
			}
		}
		save();
		return success("Group disbanded.");
	}

	public OperationResult kickGroupMember(ServerPlayer leader, String memberName) {
		EchoGroup group = findGroup(leader.getUUID());
		if (group == null || !group.isLeader(leader.getUUID())) {
			return failure("Only an echo group leader can kick members.");
		}
		if (groupHasRecording(group)) {
			return failure("Stop the active group recording before kicking members.");
		}
		GroupMember member = group.findMember(memberName);
		if (member == null) {
			return failure("That player is not in your echo group.");
		}
		if (member.uuid.equals(group.leaderUuid)) {
			return failure("The group leader cannot be kicked.");
		}
		removeFakeMemberEntity(member);
		group.members.remove(member);
		if (!member.fake) {
			try {
				ServerPlayer kicked = server.getPlayerList().getPlayer(UUID.fromString(member.uuid));
				if (kicked != null) {
					refreshCommands(kicked);
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
		save();
		return success("Removed: " + member.name);
	}

	public Component groupList(ServerPlayer player) {
		EchoGroup group = findGroup(player.getUUID());
		if (group == null) {
			return Component.literal("You are not in an echo group.");
		}
		Component result = Component.literal("Echo group led by " + group.leaderName + ":");
		for (GroupMember member : group.members) {
			String suffix = member.fake ? " (fake player)" : member.uuid.equals(group.leaderUuid) ? " (leader)" : "";
			result = result.copy().append(Component.literal("\n- " + member.name + suffix));
		}
		return result;
	}

	public List<String> groupMemberNames(ServerPlayer player) {
		EchoGroup group = findGroup(player.getUUID());
		if (group == null) {
			return List.of();
		}
		return group.members.stream().map(member -> member.name).toList();
	}

	public OperationResult inviteToGroup(ServerPlayer leader, String targetName) {
		EchoGroup group = findGroup(leader.getUUID());
		if (group == null || !group.isLeader(leader.getUUID())) {
			return failure("Only an echo group leader can invite players.");
		}
		if (group.members.size() >= settings.maximumPlayersInGroup) {
			return failure("Too many players in this group!");
		}
		if (group.findMember(targetName) != null) {
			return failure(targetName + " is already in your echo group.");
		}

		ServerPlayer target = server.getPlayerList().getPlayerByName(targetName);
		if (target != null) {
			if (findGroup(target.getUUID()) != null) {
				return failure(target.getScoreboardName() + " is already in another echo group.");
			}
			PendingInvite invite = new PendingInvite();
			invite.groupId = group.id;
			invite.leaderName = leader.getScoreboardName();
			invite.expiresAtTick = server.getTickCount() + INVITE_LIFETIME_TICKS;
			pendingInvites.put(target.getUUID(), invite);
			target.sendSystemMessage(Component.literal(leader.getScoreboardName()
					+ " invited you to an echo group. Use /echo accept or /echo refuse.")
					.withStyle(ChatFormatting.AQUA));
			return success("Invited: " + target.getScoreboardName());
		}

		if (!settings.allowFakePlayers) {
			return failure("That player is offline and AllowFakePlayers is false.");
		}
		return createFakeGroupMember(group, leader, targetName);
	}

	public OperationResult acceptInvite(ServerPlayer player) {
		PendingInvite invite = pendingInvites.remove(player.getUUID());
		if (invite == null || invite.expiresAtTick < server.getTickCount()) {
			return failure("You do not have a pending echo group invitation.");
		}
		EchoGroup group = findGroupById(invite.groupId);
		if (group == null) {
			return failure("That echo group no longer exists.");
		}
		if (findGroup(player.getUUID()) != null) {
			return failure("You are already in an echo group.");
		}
		if (group.members.size() >= settings.maximumPlayersInGroup) {
			return failure("Too many players in that group!");
		}
		group.members.add(GroupMember.real(player));
		refreshCommands(player);
		save();
		return success("Joined " + invite.leaderName + "'s group.");
	}

	public OperationResult refuseInvite(ServerPlayer player) {
		PendingInvite invite = pendingInvites.remove(player.getUUID());
		return invite == null
				? failure("You do not have a pending echo group invitation.")
				: success("You refused the echo group invitation.");
	}

	public void markUse(ServerPlayer player, InteractionHand hand) {
		RecordingTrack track = trackFor(player.getUUID());
		if (track != null) {
			queueSwing(track, hand);
		}
	}

	public void markSwing(ServerPlayer player) {
		RecordingTrack track = trackFor(player.getUUID());
		if (track != null) {
			queueSwing(track, InteractionHand.MAIN_HAND);
		}
	}

	public void markAttack(ServerPlayer player, InteractionHand hand, Entity target) {
		RecordingTrack track = trackFor(player.getUUID());
		if (track != null) {
			queueSwing(track, hand);
			track.pendingActions.add(EchoAction.attack(target, hand.name()));
		}
	}

	private void queueSwing(RecordingTrack track, InteractionHand hand) {
		if (!track.pendingSwing && track.swingCooldown <= 0) {
			track.pendingSwing = true;
			track.pendingSwingHand = hand.name();
			track.swingCooldown = 6;
		}
	}

	public void watchBlockInteraction(ServerPlayer player, BlockHitResult hit) {
		RecordingTrack track = trackFor(player.getUUID());
		if (track == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}
		BlockPos center = hit.getBlockPos();
		watchBlock(track, level, center);
		watchBlock(track, level, center.relative(hit.getDirection()));
		for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
			watchBlock(track, level, center.relative(direction));
		}
	}

	public void notePlayerBlockInteraction(BlockHitResult hit) {
		BlockPos center = hit.getBlockPos();
		notePlayerBlockTouch(center);
		notePlayerBlockTouch(center.relative(hit.getDirection()));
		for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
			notePlayerBlockTouch(center.relative(direction));
		}
	}

	public void notePlayerBlockTouch(BlockPos pos) {
		String key = blockKey(pos);
		for (SavedEcho echo : echoes) {
			if (echo.originalBlocks.containsKey(key)) {
				echo.externallyTouchedBlocks.add(key);
			}
		}
	}

	public void watchBlockAttack(ServerPlayer player, BlockPos pos) {
		RecordingTrack track = trackFor(player.getUUID());
		if (track != null && player.level() instanceof ServerLevel level) {
			watchBlock(track, level, pos);
		}
	}

	public void markBlockBroken(ServerPlayer player, BlockPos pos, BlockState before, BlockEntity blockEntity) {
		RecordingTrack track = trackFor(player.getUUID());
		if (track == null || !(player.level() instanceof ServerLevel level)) {
			return;
		}
		WatchedBlock watched = track.watchedBlocks.remove(pos);
		String beforeData = blockEntitySnapshot(blockEntity);
		if (beforeData == null && watched != null) {
			beforeData = watched.blockEntityData;
		}
		track.pendingActions.add(EchoAction.blockChange(
				pos, before, level.getBlockState(pos), beforeData,
				blockEntitySnapshot(level.getBlockEntity(pos)), server.registryAccess()));
	}

	public void watchBlockUpdate(ServerPlayer player, BlockPos pos) {
		RecordingTrack track = trackFor(player.getUUID());
		if (track != null && player.level() instanceof ServerLevel level) {
			watchBlock(track, level, pos);
		}
	}

	public void markProjectile(Entity entity) {
		if (!(entity instanceof Projectile projectile) || !(projectile.getOwner() instanceof ServerPlayer owner)) {
			return;
		}
		RecordingTrack track = trackFor(owner.getUUID());
		if (track == null) {
			return;
		}
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		if (id != null) {
			track.pendingActions.add(EchoAction.projectile(entity, id.toString()));
		}
	}

	private void captureTick() {
		Iterator<RecordingSession> iterator = recordingSessions.iterator();
		while (iterator.hasNext()) {
			RecordingSession session = iterator.next();
			if (server.getPlayerList().getPlayer(session.controllerUuid) == null) {
				removeSessionMappings(session);
				iterator.remove();
				continue;
			}
			if (session.paused) {
				continue;
			}
			for (RecordingTrack track : session.tracks) {
				LivingEntity source = resolveTrackEntity(track);
				if (source == null) {
					if (!track.frames.isEmpty()) {
						track.frames.add(track.frames.getLast());
					}
					continue;
				}
				collectBlockChanges(track, source.level());
				boolean swingStarted = track.pendingSwing
						|| (track.swingCooldown <= 0 && source.swinging && !track.wasSwinging);
				if (swingStarted) {
					track.swingCooldown = 6;
				}
				if (source.isDeadOrDying()) {
					track.recordedDeathTicks = Math.min(20, track.recordedDeathTicks + 1);
				} else {
					track.recordedDeathTicks = 0;
				}
				track.frames.add(EchoFrame.capture(source, server.registryAccess(), swingStarted,
						track.pendingSwingHand, track.recordedDeathTicks, track.pendingActions));
				snapshotVehicle(track, source.getVehicle());
				track.pendingActions.clear();
				track.pendingSwing = false;
				track.pendingSwingHand = null;
				if (track.swingCooldown > 0) {
					track.swingCooldown--;
				}
				track.wasSwinging = source.swinging;
			}
		}
	}

	private void playbackTick() {
		updatePlaybackVehicleVisibility();
		for (SavedEcho saved : echoes) {
			if (!saved.isEnabled()) {
				Mannequin leftover = resolveEchoEntity(saved);
				if (leftover != null) {
					clearCollisionTeam(leftover);
					leftover.discard();
					saved.entityUuid = null;
				}
				continue;
			}
			if (saved.frames == null || saved.frames.isEmpty()) {
				continue;
			}
			ServerLevel level = level(saved.dimension);
			Mannequin echo = resolveEchoEntity(saved);
			if (level == null || echo == null) {
				continue;
			}
			applyEntitySettings(saved, echo);
			if (saved.needsReset) {
				resetEchoWorld(saved);
				cleanupProjectiles(saved);
				saved.needsReset = false;
			}
			try {
				if (saved.swingTicks > 0) {
					saved.swingTicks--;
				}
				EchoFrame frame = saved.frames.get(saved.frameIndex);
				applyVehicleFrame(saved, echo, level, frame);
				frame.applyVisuals(echo, server.registryAccess());
				if (frame.swing) {
					startSwing(saved, EchoFrame.parseHand(frame.swingHand));
				}
				applyBreakProgress(saved, echo, level, frame);
				for (EchoAction action : frame.actions) {
					if (frame.swing && EchoAction.USE.equals(action.type)) {
						continue;
					}
					playAction(saved, echo, level, action);
				}
				applyEntitySettings(saved, echo);
			} catch (RuntimeException exception) {
				saved.enabled = false;
				applyEntitySettings(saved, echo);
				FarlandsEchoes.LOGGER.error("Disabled malformed echo '{}' instead of crashing playback", saved.name, exception);
				continue;
			}
			saved.frameIndex++;
			if (saved.frameIndex >= saved.frames.size()) {
				saved.frameIndex = 0;
				saved.needsReset = true;
			}
		}
	}

	private void playAction(SavedEcho saved, Mannequin echo, ServerLevel level, EchoAction action) {
		if (EchoAction.USE.equals(action.type)) {
			startSwing(saved, EchoFrame.parseHand(action.hand));
			return;
		}
		if (EchoAction.ATTACK_ENTITY.equals(action.type)) {
			try {
				Entity target = findPlaybackTarget(saved, action.targetUuid, level);
				if (target != null && target.distanceToSqr(echo) <= 100.0) {
					level.broadcastEntityEvent(target, (byte) 2);
				}
			} catch (IllegalArgumentException ignored) {
				// Old or malformed recordings simply skip the target reaction.
			}
			return;
		}
		if (EchoAction.BLOCK_CHANGE.equals(action.type)) {
			playBlockChange(saved, echo, level, action);
			return;
		}
		if (EchoAction.PROJECTILE.equals(action.type)) {
			spawnProjectile(saved, echo, level, action);
		}
	}

	private Entity findPlaybackTarget(SavedEcho attacker, String targetUuid, ServerLevel level) {
		for (SavedEcho candidate : echoes) {
			if (candidate.isEnabled()
					&& attacker.recordingId != null
					&& attacker.recordingId.equals(candidate.recordingId)
					&& targetUuid != null
					&& targetUuid.equals(candidate.sourceUuid)) {
				Mannequin echoTarget = resolveEchoEntity(candidate);
				if (echoTarget != null) {
					return echoTarget;
				}
			}
		}
		if (attacker.recordingParticipants != null && attacker.recordingParticipants.contains(targetUuid)) {
			return null;
		}
		try {
			return targetUuid == null ? null : level.getEntityInAnyDimension(UUID.fromString(targetUuid));
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private void applyBreakProgress(SavedEcho saved, Mannequin echo, ServerLevel level, EchoFrame frame) {
		BlockPos current = frame.breakingBlock ? new BlockPos(frame.breakX, frame.breakY, frame.breakZ) : null;
		if (saved.lastBreakPos != null && !saved.lastBreakPos.equals(current)) {
			level.destroyBlockProgress(echo.getId(), saved.lastBreakPos, -1);
		}
		if (current != null) {
			level.destroyBlockProgress(echo.getId(), current, frame.breakStage);
			if (saved.swingTicks == 0 && saved.frameIndex % 6 == 0) {
				startSwing(saved, InteractionHand.MAIN_HAND);
			}
		}
		saved.lastBreakPos = current;
	}

	private void startSwing(SavedEcho saved, InteractionHand hand) {
		saved.swingSequence++;
		saved.swingTicks = 6;
		saved.swingOffHand = hand == InteractionHand.OFF_HAND;
	}

	private void applyVehicleFrame(SavedEcho saved, Mannequin echo, ServerLevel level, EchoFrame frame) {
		if (frame.vehicleUuid == null || frame.vehicleType == null) {
			if (echo.getVehicle() != null && EchoEntityState.isMount(echo.getVehicle())) {
				echo.stopRiding();
			}
			return;
		}
		String key = vehicleKey(saved.recordingId, frame.vehicleUuid);
		level.getChunkAt(BlockPos.containing(frame.vehicleX, frame.vehicleY, frame.vehicleZ));
		Entity vehicle = resolvePlaybackVehicle(key, level);
		if (vehicle == null) {
			Identifier typeId = Identifier.tryParse(frame.vehicleType);
			EntityType<?> type = typeId == null ? null : BuiltInRegistries.ENTITY_TYPE.getValue(typeId);
			if (type == null) {
				return;
			}
			vehicle = type.create(level, EntitySpawnReason.COMMAND);
			if (vehicle == null) {
				return;
			}
			loadVehicleSnapshot(vehicle, saved.vehicleData.get(frame.vehicleUuid));
			vehicle.addTag(ECHO_MOUNT_TAG);
			vehicle.addTag(mountKeyTag(key));
			vehicle.setCustomName(Component.empty().withStyle(style -> style
					.withInsertion(EchoEntityState.mountMarker())));
			vehicle.setCustomNameVisible(false);
			vehicle.setInvulnerable(true);
			vehicle.setNoGravity(true);
			vehicle.noPhysics = true;
			vehicle.setSilent(true);
			if (!level.addFreshEntity(vehicle)) {
				return;
			}
			playbackVehicles.put(key, vehicle.getUUID());
		}
		vehicle.setInvisible(false);
		vehicle.setCustomName(Component.empty().withStyle(style -> style
				.withInsertion(EchoEntityState.mountMarker())));
		vehicle.setCustomNameVisible(false);
		vehicle.setPos(frame.vehicleX, frame.vehicleY, frame.vehicleZ);
		vehicle.setYRot(frame.vehicleYaw);
		vehicle.setXRot(frame.vehiclePitch);
		vehicle.setDeltaMovement(Vec3.ZERO);
		if (echo.getVehicle() != vehicle) {
			echo.stopRiding();
			echo.startRiding(vehicle, true, true);
		}
	}

	private void updatePlaybackVehicleVisibility() {
		Set<String> activeKeys = new HashSet<>();
		for (SavedEcho saved : echoes) {
			if (!saved.isEnabled() || saved.frames == null || saved.frames.isEmpty() || saved.recordingId == null) {
				continue;
			}
			EchoFrame frame = saved.frames.get(Math.min(saved.frameIndex, saved.frames.size() - 1));
			if (frame.vehicleUuid != null) {
				activeKeys.add(vehicleKey(saved.recordingId, frame.vehicleUuid));
			}
		}
		for (Map.Entry<String, UUID> entry : playbackVehicles.entrySet()) {
			for (ServerLevel level : server.getAllLevels()) {
				Entity vehicle = level.getEntityInAnyDimension(entry.getValue());
				if (vehicle != null && EchoEntityState.isMount(vehicle)) {
					vehicle.setInvisible(!activeKeys.contains(entry.getKey()));
					break;
				}
			}
		}
	}

	private void playBlockChange(SavedEcho saved, Mannequin echo, ServerLevel level, EchoAction action) {
		BlockPos pos = action.blockPos();
		BlockState before = action.before(server.registryAccess());
		BlockState after = action.after(server.registryAccess());
		if (!level.getBlockState(pos).equals(before)) {
			return;
		}
		if (action.beforeBlockEntity != null
				&& !java.util.Objects.equals(action.beforeBlockEntity, blockEntitySnapshot(level.getBlockEntity(pos)))) {
			return;
		}
		if (after.isAir() && saved.settings.echoBlockDrops) {
			level.destroyBlock(pos, true, echo);
		} else {
			level.setBlock(pos, after, 3);
		}
			saved.expectedBlocks.put(blockKey(pos), action.afterState);
		applyBlockEntitySnapshot(level, pos, action.afterBlockEntity);
		if (action.afterBlockEntity == null) {
			saved.expectedBlockEntities.remove(blockKey(pos));
		} else {
			saved.expectedBlockEntities.put(blockKey(pos), action.afterBlockEntity);
		}
	}

	private void spawnProjectile(SavedEcho saved, Mannequin echo, ServerLevel level, EchoAction action) {
		Identifier id = Identifier.tryParse(action.entityType);
		if (id == null) {
			return;
		}
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
		if (type == null) {
			return;
		}
		Entity projectileEntity = type.create(level, EntitySpawnReason.TRIGGERED);
		if (!(projectileEntity instanceof Projectile projectile)) {
			return;
		}
		projectile.setOwner(echo);
		projectile.setPos(action.preciseX, action.preciseY, action.preciseZ);
		projectile.setYRot(action.yaw);
		projectile.setXRot(action.pitch);
		projectile.setDeltaMovement(action.velocityX, action.velocityY, action.velocityZ);
		projectile.noPhysics = true;
		if (projectile instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow) {
			arrow.setNoPhysics(true);
		}
		projectile.setInvulnerable(true);
		projectile.addTag(ECHO_PROJECTILE_TAG);
		projectile.setCustomName(Component.empty().withStyle(style -> style
				.withInsertion(EchoEntityState.projectileMarker())));
		projectile.setCustomNameVisible(false);
		if (level.addFreshEntity(projectile)) {
			saved.spawnedProjectiles.add(projectile.getUUID());
		}
	}

	private boolean createPlaybackEcho(RecordingTrack track) {
		ServerLevel level = level(track.dimension);
		if (level == null) {
			return false;
		}
		Mannequin entity = EntityType.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
		if (entity == null) {
			return false;
		}

		SavedEcho saved = new SavedEcho();
		saved.id = UUID.randomUUID().toString();
		saved.numericId = allocateEchoId();
		saved.name = Integer.toString(saved.numericId);
		saved.recordingId = track.recordingId;
		saved.sourceUuid = track.sourceUuid.toString();
		saved.recordingParticipants = new ArrayList<>(track.recordingParticipants);
		saved.entityUuid = entity.getUUID().toString();
		saved.dimension = track.dimension;
		saved.sourceName = track.sourceName;
		saved.mainArm = track.mainArm.name();
		saved.frames = track.frames;
		saved.vehicleData = new HashMap<>(track.vehicleData);
		saved.settings = settings.copy();
		saved.enabled = true;
		saved.initializeRuntime();

		entity.addTag(ECHO_TAG);
		entity.addTag("farlands_echoes.id." + saved.id);
		entity.setInvulnerable(true);
		entity.setNoGravity(true);
		entity.setSilent(true);
		entity.setMainArm(track.mainArm);
		ResolvableProfile profile = track.resolvedProfile != null
				? ResolvableProfile.createResolved(track.resolvedProfile)
				: ResolvableProfile.createUnresolved(track.profileName);
		((MannequinAccessor) entity).farlandsEchoes$setProfile(profile);
		track.frames.getFirst().applyVisuals(entity, server.registryAccess());
		echoes.add(saved);
		applyEntitySettings(saved, entity);
		level.addFreshEntity(entity);
		return true;
	}

	private boolean spawnSavedEcho(SavedEcho saved) {
		ServerLevel level = level(saved.dimension);
		if (level == null || saved.frames == null || saved.frames.isEmpty()) {
			return false;
		}
		Mannequin entity = EntityType.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
		if (entity == null) {
			return false;
		}
		entity.addTag(ECHO_TAG);
		entity.addTag("farlands_echoes.id." + saved.id);
		entity.setInvulnerable(true);
		entity.setNoGravity(true);
		entity.setSilent(true);
		try {
			entity.setMainArm(net.minecraft.world.entity.HumanoidArm.valueOf(saved.mainArm));
		} catch (IllegalArgumentException | NullPointerException ignored) {
			entity.setMainArm(net.minecraft.world.entity.HumanoidArm.RIGHT);
		}
		((MannequinAccessor) entity).farlandsEchoes$setProfile(resolveProfile(saved.sourceName, null));
		saved.frames.get(Math.min(saved.frameIndex, saved.frames.size() - 1))
				.applyVisuals(entity, server.registryAccess());
		saved.entityUuid = entity.getUUID().toString();
		applyEntitySettings(saved, entity);
		return level.addFreshEntity(entity);
	}

	private void applyEntitySettings(SavedEcho saved, Mannequin entity) {
		if (entity == null) {
			return;
		}
		entity.addTag(ECHO_TAG);
		boolean hitbox = saved.isEnabled() && saved.settings.echoHitboxes;
		boolean collision = saved.isEnabled() && saved.settings.echoCollisions;
		setTag(entity, ECHO_HITBOX_TAG, hitbox);
		setTag(entity, ECHO_COLLISION_TAG, collision);
		updateCollisionTeam(entity, collision);
		String visibleName = saved.sourceName == null || saved.sourceName.isBlank() ? saved.name : saved.sourceName;
		if (settings.echoIDs) {
			visibleName += " [#" + saved.numericId + "]";
		}
		entity.setCustomName(Component.literal(visibleName).withStyle(style -> style
				.withInsertion(EchoEntityState.echoMarker(
						hitbox,
						collision,
						saved.swingSequence,
						saved.swingOffHand,
						entity.deathTime,
						entity.hurtTime))));
		entity.setCustomNameVisible(saved.isEnabled() && saved.settings.echoNameTags);
		entity.setInvisible(!saved.isEnabled());
		((MannequinAccessor) entity).farlandsEchoes$setHideDescription(true);
	}

	private void updateCollisionTeam(Entity entity, boolean collides) {
		Scoreboard scoreboard = server.getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(NO_COLLISION_TEAM);
		if (team == null) {
			team = scoreboard.addPlayerTeam(NO_COLLISION_TEAM);
			team.setCollisionRule(Team.CollisionRule.NEVER);
		}
		String entry = entity.getScoreboardName();
		if (collides) {
			if (scoreboard.getPlayersTeam(entry) == team) {
				scoreboard.removePlayerFromTeam(entry, team);
			}
		} else if (scoreboard.getPlayersTeam(entry) != team) {
			scoreboard.addPlayerToTeam(entry, team);
		}
	}

	private void clearCollisionTeam(Entity entity) {
		Scoreboard scoreboard = server.getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(NO_COLLISION_TEAM);
		if (team != null && scoreboard.getPlayersTeam(entity.getScoreboardName()) == team) {
			scoreboard.removePlayerFromTeam(entity.getScoreboardName(), team);
		}
	}

	private void resetEchoWorld(SavedEcho saved) {
		ServerLevel level = level(saved.dimension);
		if (level == null) {
			return;
		}
		for (Map.Entry<String, JsonElement> entry : saved.originalBlocks.entrySet()) {
			BlockPos pos = parseBlockKey(entry.getKey());
			if (pos == null) {
				continue;
			}
			try {
				EchoAction decoder = new EchoAction();
				decoder.afterState = entry.getValue();
				BlockState original = decoder.after(server.registryAccess());
				if (!saved.externallyTouchedBlocks.contains(entry.getKey())) {
					level.setBlock(pos, original, 3);
					applyBlockEntitySnapshot(level, pos, saved.originalBlockEntities.get(entry.getKey()));
				}
			} catch (RuntimeException exception) {
				FarlandsEchoes.LOGGER.warn("Could not reset block {} for echo {}", pos, saved.name, exception);
			}
		}
		saved.expectedBlocks.clear();
		saved.expectedBlockEntities.clear();
		saved.externallyTouchedBlocks.clear();
	}

	private String blockEntitySnapshot(BlockEntity blockEntity) {
		if (!(blockEntity instanceof SignBlockEntity)) {
			return null;
		}
		try {
			return blockEntity.saveWithFullMetadata(server.registryAccess()).toString();
		} catch (RuntimeException exception) {
			FarlandsEchoes.LOGGER.warn("Could not snapshot sign at {}", blockEntity.getBlockPos(), exception);
			return null;
		}
	}

	private void applyBlockEntitySnapshot(ServerLevel level, BlockPos pos, String snbt) {
		if (snbt == null) {
			return;
		}
		try {
			CompoundTag tag = TagParser.parseCompoundFully(snbt);
			BlockEntity restored = BlockEntity.loadStatic(pos, level.getBlockState(pos), tag, server.registryAccess());
			if (restored != null) {
				level.setBlockEntity(restored);
				restored.setChanged();
				level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
			}
		} catch (Exception exception) {
			FarlandsEchoes.LOGGER.warn("Could not replay block-entity data at {}", pos, exception);
		}
	}

	private void cleanupProjectiles(SavedEcho saved) {
		for (UUID uuid : saved.spawnedProjectiles) {
			for (ServerLevel level : server.getAllLevels()) {
				Entity entity = level.getEntityInAnyDimension(uuid);
				if (entity != null && entity.getTags().contains(ECHO_PROJECTILE_TAG)) {
					entity.discard();
					break;
				}
			}
		}
		saved.spawnedProjectiles.clear();
	}

	private void snapshotVehicle(RecordingTrack track, Entity vehicle) {
		if (vehicle == null) {
			return;
		}
		String uuid = vehicle.getUUID().toString();
		if (track.vehicleData.containsKey(uuid)) {
			return;
		}
		try {
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
			vehicle.saveWithoutId(output);
			CompoundTag tag = output.buildResult();
			for (String field : List.of("UUID", "Pos", "Motion", "Rotation", "Passengers", "Leash")) {
				tag.remove(field);
			}
			track.vehicleData.put(uuid, tag.toString());
		} catch (RuntimeException exception) {
			FarlandsEchoes.LOGGER.warn("Could not snapshot ridden entity {}", vehicle.getType(), exception);
		}
	}

	private void loadVehicleSnapshot(Entity vehicle, String snbt) {
		if (snbt == null || snbt.isBlank()) {
			return;
		}
		try {
			CompoundTag tag = TagParser.parseCompoundFully(snbt);
			vehicle.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), tag));
		} catch (Exception exception) {
			FarlandsEchoes.LOGGER.warn("Could not restore a recorded ridden entity; using its default appearance", exception);
		}
	}

	private Entity resolvePlaybackVehicle(String key, ServerLevel expectedLevel) {
		UUID uuid = playbackVehicles.get(key);
		if (uuid != null) {
			Entity entity = expectedLevel.getEntityInAnyDimension(uuid);
			if (entity != null && EchoEntityState.isMount(entity)) {
				return entity;
			}
		}
		String keyTag = mountKeyTag(key);
		for (Entity entity : expectedLevel.getAllEntities()) {
			if (entity.getTags().contains(keyTag) && EchoEntityState.isMount(entity)) {
				playbackVehicles.put(key, entity.getUUID());
				return entity;
			}
		}
		playbackVehicles.remove(key);
		return null;
	}

	private static String mountKeyTag(String key) {
		return "farlands_echoes.mount_key." + key;
	}

	private void cleanupVehicles(String recordingId) {
		String prefix = recordingId + "|";
		Iterator<Map.Entry<String, UUID>> iterator = playbackVehicles.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, UUID> entry = iterator.next();
			if (!entry.getKey().startsWith(prefix)) {
				continue;
			}
			for (ServerLevel level : server.getAllLevels()) {
				Entity entity = level.getEntityInAnyDimension(entry.getValue());
				if (entity != null && EchoEntityState.isMount(entity)) {
					entity.discard();
					break;
				}
			}
			iterator.remove();
		}
	}

	private void clearBreakProgress(SavedEcho saved, Mannequin echo) {
		ServerLevel level = level(saved.dimension);
		if (level != null && saved.lastBreakPos != null) {
			level.destroyBlockProgress(echo.getId(), saved.lastBreakPos, -1);
		}
		saved.lastBreakPos = null;
	}

	private void detachEchoVehicle(Mannequin echo) {
		Entity vehicle = echo.getVehicle();
		if (vehicle == null || !EchoEntityState.isMount(vehicle)) {
			return;
		}
		echo.stopRiding();
		if (vehicle.getPassengers().isEmpty()) {
			UUID uuid = vehicle.getUUID();
			vehicle.discard();
			playbackVehicles.values().removeIf(uuid::equals);
		}
	}

	private static String vehicleKey(String recordingId, String sourceVehicleUuid) {
		return recordingId + "|" + sourceVehicleUuid;
	}

	private void prepareForShutdown() {
		for (String recordingId : echoes.stream().map(echo -> echo.recordingId).filter(java.util.Objects::nonNull).toList()) {
			cleanupVehicles(recordingId);
		}
		for (SavedEcho echo : echoes) {
			resetEchoWorld(echo);
			cleanupProjectiles(echo);
			echo.frameIndex = 0;
			echo.needsReset = true;
		}
	}

	private List<RecordingTrack> recordingTracksFor(ServerPlayer initiator) {
		EchoGroup group = findGroup(initiator.getUUID());
		if (group == null) {
			return List.of(RecordingTrack.real(initiator, maskFor(initiator.getUUID())));
		}
		List<RecordingTrack> tracks = new ArrayList<>();
		for (GroupMember member : group.members) {
			if (member.fake) {
				LivingEntity fake = resolveGroupMemberEntity(member);
				if (fake != null) {
					tracks.add(RecordingTrack.fake(member, fake));
				}
				continue;
			}
			ServerPlayer online = server.getPlayerList().getPlayer(UUID.fromString(member.uuid));
			if (online != null) {
				tracks.add(RecordingTrack.real(online, maskFor(online.getUUID())));
			}
		}
		return tracks;
	}

	private OperationResult createFakeGroupMember(EchoGroup group, ServerPlayer leader, String targetName) {
		if (!targetName.matches("[A-Za-z0-9_]{1,16}")) {
			return failure("Fake player names must be valid Minecraft profile names.");
		}
		Mannequin fake = EntityType.MANNEQUIN.create(leader.level(), EntitySpawnReason.COMMAND);
		if (fake == null) {
			return failure("Minecraft could not create the fake player entity.");
		}
		fake.addTag(FAKE_PLAYER_TAG);
		setTag(fake, FAKE_ATTACKABLE_TAG, settings.attackFakePlayers);
		fake.setInvulnerable(!settings.attackFakePlayers);
		fake.setNoGravity(false);
		fake.setSilent(true);
		fake.setPos(leader.position());
		fake.setYRot(leader.getYRot());
		fake.setXRot(leader.getXRot());
		fake.setCustomName(Component.literal(targetName).withStyle(style -> style
				.withInsertion(EchoEntityState.fakeMarker(settings.attackFakePlayers))));
		fake.setCustomNameVisible(true);
		((MannequinAccessor) fake).farlandsEchoes$setProfile(resolveProfile(targetName, null));
		((MannequinAccessor) fake).farlandsEchoes$setHideDescription(true);
		leader.level().addFreshEntity(fake);
		GroupMember member = GroupMember.fake(targetName, fake);
		group.members.add(member);
		save();
		return success("Added fake player " + targetName + ".");
	}

	private void watchBlock(RecordingTrack track, ServerLevel level, BlockPos pos) {
		track.watchedBlocks.putIfAbsent(pos.immutable(), new WatchedBlock(
				level.getBlockState(pos), blockEntitySnapshot(level.getBlockEntity(pos))));
	}

	private void collectBlockChanges(RecordingTrack track, Level level) {
		if (!(level instanceof ServerLevel serverLevel)) {
			track.watchedBlocks.clear();
			return;
		}
		for (Map.Entry<BlockPos, WatchedBlock> entry : track.watchedBlocks.entrySet()) {
			BlockState after = serverLevel.getBlockState(entry.getKey());
			String afterData = blockEntitySnapshot(serverLevel.getBlockEntity(entry.getKey()));
			if (!after.equals(entry.getValue().state) || !java.util.Objects.equals(afterData, entry.getValue().blockEntityData)) {
				track.pendingActions.add(EchoAction.blockChange(
						entry.getKey(), entry.getValue().state, after,
						entry.getValue().blockEntityData, afterData, server.registryAccess()));
			}
		}
		track.watchedBlocks.clear();
	}

	private void load() {
		if (!Files.exists(saveFile)) {
			return;
		}
		try {
			SaveData data = GSON.fromJson(Files.readString(saveFile, StandardCharsets.UTF_8), SaveData.class);
			if (data == null) {
				return;
			}
			settings = data.settings == null ? new EchoSettings() : data.settings;
			settings.sanitize();
			if (data.echoes != null) {
				echoes.addAll(data.echoes);
			}
			if (data.groups != null) {
				groups.addAll(data.groups);
			}
			if (data.masks != null) {
				masks.putAll(data.masks);
			}
			Set<Integer> claimedIds = new HashSet<>();
			for (SavedEcho echo : echoes) {
				if (echo.numericId <= 0) {
					try {
						int legacyId = Integer.parseInt(echo.name);
						if (legacyId > 0 && !claimedIds.contains(legacyId)) {
							echo.numericId = legacyId;
						}
					} catch (NumberFormatException | NullPointerException ignored) {
					}
				}
				if (echo.numericId <= 0 || claimedIds.contains(echo.numericId)) {
					echo.numericId = firstFreeId(claimedIds);
				}
				claimedIds.add(echo.numericId);
				if (echo.name == null || echo.name.isBlank()) {
					echo.name = Integer.toString(echo.numericId);
				}
				if (echo.sourceName == null || echo.sourceName.isBlank()) {
					echo.sourceName = echo.ownerName == null || echo.ownerName.isBlank() ? echo.name : echo.ownerName;
				}
				if (echo.recordingId == null || echo.recordingId.isBlank()) {
					echo.recordingId = echo.id;
				}
				if (echo.vehicleData == null) {
					echo.vehicleData = new HashMap<>();
				}
				if (echo.settings == null) {
					echo.settings = settings.copy();
				}
				if (echo.enabled == null) {
					echo.enabled = true;
				}
				echo.initializeRuntime();
			}
			FarlandsEchoes.LOGGER.info("Loaded {} saved echoes and {} groups", echoes.size(), groups.size());
		} catch (Exception exception) {
			FarlandsEchoes.LOGGER.error("Could not load echoes from {}", saveFile, exception);
		}
	}

	private void save() {
		try {
			Files.createDirectories(saveFile.getParent());
			Path temporary = saveFile.resolveSibling(saveFile.getFileName() + ".tmp");
			Files.writeString(temporary, GSON.toJson(new SaveData(this)), StandardCharsets.UTF_8);
			try {
				Files.move(temporary, saveFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException unsupportedAtomicMove) {
				Files.move(temporary, saveFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			FarlandsEchoes.LOGGER.error("Could not save echoes to {}", saveFile, exception);
		}
	}

	private void removeSession(RecordingSession session) {
		recordingSessions.remove(session);
		removeSessionMappings(session);
	}

	private void removeSessionMappings(RecordingSession session) {
		sessionsByPlayer.values().removeIf(value -> value == session);
	}

	private boolean canStopSession(RecordingSession session, ServerPlayer player) {
		if (session.controllerUuid.equals(player.getUUID())) {
			return true;
		}
		EchoGroup group = session.groupId == null ? null : findGroupById(session.groupId);
		return group != null && group.isLeader(player.getUUID());
	}

	private RecordingTrack trackFor(UUID playerUuid) {
		RecordingSession session = sessionsByPlayer.get(playerUuid);
		return session == null || session.paused ? null : session.track(playerUuid);
	}

	private LivingEntity resolveTrackEntity(RecordingTrack track) {
		if (!track.fake) {
			return server.getPlayerList().getPlayer(track.sourceUuid);
		}
		ServerLevel level = level(track.dimension);
		if (level == null) {
			return null;
		}
		Entity entity = level.getEntityInAnyDimension(track.sourceUuid);
		return entity instanceof LivingEntity living ? living : null;
	}

	private Mannequin resolveEchoEntity(SavedEcho echo) {
		ServerLevel level = level(echo.dimension);
		if (level == null || echo.entityUuid == null) {
			return null;
		}
		try {
			UUID uuid = UUID.fromString(echo.entityUuid);
			Entity entity = level.getEntityInAnyDimension(uuid);
			if (entity == null && echo.frames != null && !echo.frames.isEmpty()) {
				EchoFrame frame = echo.frames.get(Math.min(echo.frameIndex, echo.frames.size() - 1));
				level.getChunkAt(BlockPos.containing(frame.x, frame.y, frame.z));
				entity = level.getEntityInAnyDimension(uuid);
			}
			return entity instanceof Mannequin mannequin ? mannequin : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private LivingEntity resolveGroupMemberEntity(GroupMember member) {
		if (!member.fake || member.entityUuid == null || member.dimension == null) {
			return null;
		}
		ServerLevel level = level(member.dimension);
		if (level == null) {
			return null;
		}
		try {
			Entity entity = level.getEntityInAnyDimension(UUID.fromString(member.entityUuid));
			return entity instanceof LivingEntity living ? living : null;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private void removeFakeMemberEntity(GroupMember member) {
		LivingEntity entity = resolveGroupMemberEntity(member);
		if (entity != null) {
			entity.discard();
		}
	}

	private SavedEcho findEcho(String name) {
		try {
			SavedEcho byId = findEchoById(Integer.parseInt(name));
			if (byId != null) {
				return byId;
			}
		} catch (NumberFormatException | NullPointerException ignored) {
		}
		for (SavedEcho echo : echoes) {
			if (echo.name != null && echo.name.equalsIgnoreCase(name)) {
				return echo;
			}
		}
		return null;
	}

	private SavedEcho findEchoById(int id) {
		for (SavedEcho echo : echoes) {
			if (echo.numericId == id) {
				return echo;
			}
		}
		return null;
	}

	private EchoGroup findGroup(UUID memberUuid) {
		for (EchoGroup group : groups) {
			if (group.hasMember(memberUuid)) {
				return group;
			}
		}
		return null;
	}

	private EchoGroup findGroupById(String id) {
		for (EchoGroup group : groups) {
			if (group.id.equals(id)) {
				return group;
			}
		}
		return null;
	}

	private boolean groupHasRecording(EchoGroup group) {
		for (GroupMember member : group.members) {
			try {
				if (!member.fake && sessionsByPlayer.containsKey(UUID.fromString(member.uuid))) {
					return true;
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
		return false;
	}

	private MaskProfile maskFor(UUID uuid) {
		return masks.get(uuid.toString());
	}

	public boolean hasGroup(ServerPlayer player) {
		return findGroup(player.getUUID()) != null;
	}

	public boolean isGroupLeader(ServerPlayer player) {
		EchoGroup group = findGroup(player.getUUID());
		return group != null && group.isLeader(player.getUUID());
	}

	public void playerJoined(ServerPlayer player) {
		syncMasksTo(player);
		MaskProfile mask = maskFor(player.getUUID());
		if (mask == null) {
			return;
		}
		GameProfile target = fetchProfile(mask.name);
		if (target == null) {
			FarlandsEchoes.LOGGER.warn("Could not restore mask {} for {}", mask.name, player.getScoreboardName());
			return;
		}
		updateGroupMemberName(player.getUUID(), target.name());
		try {
			refreshMaskDisplay(player);
			broadcastMask(player.getUUID(), target);
		} catch (RuntimeException exception) {
			FarlandsEchoes.LOGGER.error("Could not restore mask {} for {}", mask.name, player.getUUID(), exception);
		}
	}

	public void playerDisconnected(ServerPlayer player) {
		// Masks persist by UUID and are restored when the player rejoins.
	}

	public String maskedName(UUID uuid) {
		MaskProfile mask = maskFor(uuid);
		return mask == null ? null : mask.name;
	}

	private int allocateEchoId() {
		for (int candidate = 1; candidate <= settings.maximumEchoes + echoes.size() + 1; candidate++) {
			if (findEchoById(candidate) == null) {
				return candidate;
			}
		}
		return echoes.size() + 1;
	}

	private static int firstFreeId(Set<Integer> claimed) {
		int candidate = 1;
		while (claimed.contains(candidate)) {
			candidate++;
		}
		return candidate;
	}

	private ResolvableProfile resolveProfile(String name, GameProfile fallback) {
		GameProfile resolved = fetchProfile(name);
		if (resolved == null) {
			resolved = fallback;
		}
		return resolved == null
				? ResolvableProfile.createUnresolved(name)
				: ResolvableProfile.createResolved(resolved);
	}

	private GameProfile fetchProfile(String name) {
		try {
			GameProfile profile = server.services().profileResolver().fetchByName(name).orElse(null);
			if (profile == null) {
				return null;
			}
			com.mojang.authlib.yggdrasil.ProfileResult full =
					server.services().sessionService().fetchProfile(profile.id(), true);
			return full == null || full.profile() == null ? profile : full.profile();
		} catch (RuntimeException exception) {
			FarlandsEchoes.LOGGER.warn("Could not resolve Minecraft profile {}", name, exception);
			return null;
		}
	}

	private void refreshMaskDisplay(ServerPlayer changed) {
		server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
				ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, changed));
	}

	private void syncMasksTo(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, MaskSyncPayload.TYPE)) {
			return;
		}
		for (Map.Entry<String, MaskProfile> entry : masks.entrySet()) {
			try {
				GameProfile profile = fetchProfile(entry.getValue().name);
				if (profile != null) {
					ServerPlayNetworking.send(player, new MaskSyncPayload(
							UUID.fromString(entry.getKey()), ResolvableProfile.createResolved(profile)));
				}
			} catch (IllegalArgumentException ignored) {
				// Ignore malformed legacy UUID entries.
			}
		}
	}

	private void broadcastMask(UUID playerUuid, GameProfile profile) {
		MaskSyncPayload payload = new MaskSyncPayload(playerUuid,
				profile == null ? null : ResolvableProfile.createResolved(profile));
		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(online, MaskSyncPayload.TYPE)) {
				ServerPlayNetworking.send(online, payload);
			}
		}
	}

	private void refreshCommands(ServerPlayer player) {
		server.getCommands().sendCommands(player);
	}

	private void updateGroupMemberName(UUID playerUuid, String name) {
		EchoGroup group = findGroup(playerUuid);
		if (group == null) {
			return;
		}
		for (GroupMember member : group.members) {
			if (member.matches(playerUuid)) {
				member.name = name;
			}
		}
		if (group.isLeader(playerUuid)) {
			group.leaderName = name;
		}
	}

	private void maintainFakeMembers() {
		boolean changed = false;
		for (EchoGroup group : groups) {
			Iterator<GroupMember> iterator = group.members.iterator();
			while (iterator.hasNext()) {
				GroupMember member = iterator.next();
				LivingEntity fake = resolveGroupMemberEntity(member);
				if (!member.fake) {
					continue;
				}
				if (fake == null) {
					ServerLevel memberLevel = level(member.dimension);
					if (memberLevel != null && member.anchorSet) {
						memberLevel.getChunkAt(BlockPos.containing(member.anchorX, 0.0, member.anchorZ));
						fake = resolveGroupMemberEntity(member);
						if (fake == null) {
							fake = respawnFakeMember(member, memberLevel);
							changed |= fake != null;
						}
					}
					if (fake == null) {
						// Keep the saved member. Its dimension or chunk may become available later.
						continue;
					}
				}
				if (fake.isDeadOrDying() || fake.isRemoved()) {
					iterator.remove();
					changed = true;
					continue;
				}
				fake.addTag(FAKE_PLAYER_TAG);
				fake.setNoGravity(false);
				setTag(fake, FAKE_ATTACKABLE_TAG, settings.attackFakePlayers);
				fake.setInvulnerable(!settings.attackFakePlayers);
				fake.setCustomName(Component.literal(member.name).withStyle(style -> style
						.withInsertion(EchoEntityState.fakeMarker(settings.attackFakePlayers))));
				member.anchorX = fake.getX();
				member.anchorY = fake.getY();
				member.anchorZ = fake.getZ();
				member.anchorYSet = true;
				member.yaw = fake.getYRot();
				member.pitch = fake.getXRot();
			}
		}
		if (changed) {
			save();
		}
	}

	private Mannequin respawnFakeMember(GroupMember member, ServerLevel level) {
		Mannequin fake = EntityType.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
		if (fake == null) {
			return null;
		}
		double y = member.anchorYSet ? member.anchorY
				: level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						(int) Math.floor(member.anchorX), (int) Math.floor(member.anchorZ)) + 1.0;
		fake.addTag(FAKE_PLAYER_TAG);
		setTag(fake, FAKE_ATTACKABLE_TAG, settings.attackFakePlayers);
		fake.setInvulnerable(!settings.attackFakePlayers);
		fake.setNoGravity(false);
		fake.setSilent(true);
		fake.setPos(member.anchorX, y, member.anchorZ);
		fake.setYRot(member.yaw);
		fake.setXRot(member.pitch);
		fake.setCustomName(Component.literal(member.name).withStyle(style -> style
				.withInsertion(EchoEntityState.fakeMarker(settings.attackFakePlayers))));
		fake.setCustomNameVisible(true);
		((MannequinAccessor) fake).farlandsEchoes$setProfile(resolveProfile(member.name, null));
		((MannequinAccessor) fake).farlandsEchoes$setHideDescription(true);
		if (!level.addFreshEntity(fake)) {
			return null;
		}
		member.entityUuid = fake.getUUID().toString();
		member.dimension = level.dimension().identifier().toString();
		member.anchorY = y;
		member.anchorYSet = true;
		return fake;
	}

	private ServerLevel level(String dimension) {
		Identifier id = Identifier.tryParse(dimension);
		return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	private void expireInvites() {
		int tick = server.getTickCount();
		pendingInvites.values().removeIf(invite -> invite.expiresAtTick < tick);
	}

	private static void setTag(Entity entity, String tag, boolean enabled) {
		if (enabled) {
			entity.addTag(tag);
		} else {
			entity.removeTag(tag);
		}
	}

	private static String blockKey(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static BlockPos parseBlockKey(String key) {
		try {
			String[] parts = key.split(",", 3);
			return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static OperationResult success(String message) {
		return new OperationResult(true, message);
	}

	private static OperationResult failure(String message) {
		return new OperationResult(false, message);
	}

	public record OperationResult(boolean success, String message) {
	}

	private static final class RecordingSession {
		final String recordingId = UUID.randomUUID().toString();
		final UUID controllerUuid;
		final String controllerName;
		final List<RecordingTrack> tracks;
		final String groupId;
		boolean paused;

		RecordingSession(ServerPlayer controller, List<RecordingTrack> tracks, String groupId) {
			this.controllerUuid = controller.getUUID();
			this.controllerName = controller.getScoreboardName();
			this.tracks = tracks;
			this.groupId = groupId;
			List<String> participants = tracks.stream().map(track -> track.sourceUuid.toString()).toList();
			for (RecordingTrack track : tracks) {
				track.recordingId = recordingId;
				track.recordingParticipants = participants;
			}
		}

		RecordingTrack track(UUID uuid) {
			for (RecordingTrack track : tracks) {
				if (!track.fake && track.sourceUuid.equals(uuid)) {
					return track;
				}
			}
			return null;
		}
	}

	private static final class RecordingTrack {
		String recordingId;
		List<String> recordingParticipants = List.of();
		UUID sourceUuid;
		String sourceName;
		String profileName;
		String dimension;
		boolean fake;
		net.minecraft.world.entity.HumanoidArm mainArm;
		com.mojang.authlib.GameProfile resolvedProfile;
		List<EchoFrame> frames = new ArrayList<>();
		List<EchoAction> pendingActions = new ArrayList<>();
		Map<BlockPos, WatchedBlock> watchedBlocks = new LinkedHashMap<>();
		Map<String, String> vehicleData = new HashMap<>();
		boolean wasSwinging;
		boolean pendingSwing;
		String pendingSwingHand;
		int swingCooldown;
		int recordedDeathTicks;

		static RecordingTrack real(ServerPlayer player, MaskProfile mask) {
			RecordingTrack track = new RecordingTrack();
			track.sourceUuid = player.getUUID();
			track.sourceName = mask == null ? player.getScoreboardName() : mask.name;
			track.profileName = track.sourceName;
			track.dimension = player.level().dimension().identifier().toString();
			track.mainArm = player.getMainArm();
			track.resolvedProfile = mask == null ? player.getGameProfile() : null;
			track.wasSwinging = player.swinging;
			return track;
		}

		static RecordingTrack fake(GroupMember member, LivingEntity entity) {
			RecordingTrack track = new RecordingTrack();
			track.sourceUuid = entity.getUUID();
			track.sourceName = member.name;
			track.profileName = member.name;
			track.dimension = entity.level().dimension().identifier().toString();
			track.fake = true;
			track.mainArm = entity.getMainArm();
			track.wasSwinging = entity.swinging;
			return track;
		}
	}

	private record WatchedBlock(BlockState state, String blockEntityData) {
	}

	private static final class SavedEcho {
		String id;
		int numericId;
		String name;
		String recordingId;
		String sourceUuid;
		List<String> recordingParticipants = new ArrayList<>();
		String entityUuid;
		String dimension;
		String sourceName;
		String mainArm;
		String ownerName;
		List<EchoFrame> frames = new ArrayList<>();
		Map<String, String> vehicleData = new HashMap<>();
		EchoSettings settings;
		Boolean enabled;

		transient int frameIndex;
		transient boolean needsReset;
		transient Map<String, JsonElement> originalBlocks = new LinkedHashMap<>();
		transient Map<String, JsonElement> recordedFinalBlocks = new LinkedHashMap<>();
		transient Map<String, JsonElement> expectedBlocks = new HashMap<>();
		transient Map<String, String> originalBlockEntities = new HashMap<>();
		transient Map<String, String> recordedFinalBlockEntities = new HashMap<>();
		transient Map<String, String> expectedBlockEntities = new HashMap<>();
		transient Set<UUID> spawnedProjectiles = new HashSet<>();
		transient Set<String> externallyTouchedBlocks = new HashSet<>();
		transient BlockPos lastBreakPos;
		transient int swingSequence;
		transient int swingTicks;
		transient boolean swingOffHand;

		boolean isEnabled() {
			return enabled == null || enabled;
		}

		void initializeRuntime() {
			frameIndex = 0;
			needsReset = true;
			originalBlocks = new LinkedHashMap<>();
			recordedFinalBlocks = new LinkedHashMap<>();
			expectedBlocks = new HashMap<>();
			originalBlockEntities = new HashMap<>();
			recordedFinalBlockEntities = new HashMap<>();
			expectedBlockEntities = new HashMap<>();
			spawnedProjectiles = new HashSet<>();
			externallyTouchedBlocks = new HashSet<>();
			lastBreakPos = null;
			swingSequence = 0;
			swingTicks = 0;
			swingOffHand = false;
			if (frames == null) {
				frames = new ArrayList<>();
			}
			for (EchoFrame frame : frames) {
				if (frame.actions == null) {
					frame.actions = new ArrayList<>();
				}
				for (EchoAction action : frame.actions) {
					if (EchoAction.BLOCK_CHANGE.equals(action.type)) {
						String key = blockKey(action.blockPos());
						originalBlocks.putIfAbsent(key, action.beforeState);
						recordedFinalBlocks.put(key, action.afterState);
						if (action.beforeBlockEntity != null) {
							originalBlockEntities.putIfAbsent(key, action.beforeBlockEntity);
						}
						if (action.afterBlockEntity == null) {
							recordedFinalBlockEntities.remove(key);
						} else {
							recordedFinalBlockEntities.put(key, action.afterBlockEntity);
						}
					}
				}
			}
			expectedBlocks.putAll(recordedFinalBlocks);
			expectedBlockEntities.putAll(recordedFinalBlockEntities);
		}
	}

	private static final class EchoGroup {
		String id;
		String leaderUuid;
		String leaderName;
		List<GroupMember> members = new ArrayList<>();

		boolean hasMember(UUID uuid) {
			return members.stream().anyMatch(member -> member.matches(uuid));
		}

		boolean isLeader(UUID uuid) {
			return leaderUuid.equals(uuid.toString());
		}

		GroupMember findMember(String name) {
			return members.stream().filter(member -> member.name.equalsIgnoreCase(name)).findFirst().orElse(null);
		}
	}

	private static final class GroupMember {
		String uuid;
		String name;
		boolean fake;
		String entityUuid;
		String dimension;
		boolean anchorSet;
		double anchorX;
		double anchorY;
		double anchorZ;
		boolean anchorYSet;
		float yaw;
		float pitch;

		static GroupMember real(ServerPlayer player) {
			GroupMember member = new GroupMember();
			member.uuid = player.getUUID().toString();
			member.name = player.getScoreboardName();
			return member;
		}

		static GroupMember fake(String name, Mannequin entity) {
			GroupMember member = new GroupMember();
			member.uuid = UUID.randomUUID().toString();
			member.name = name;
			member.fake = true;
			member.entityUuid = entity.getUUID().toString();
			member.dimension = entity.level().dimension().identifier().toString();
			member.anchorSet = true;
			member.anchorX = entity.getX();
			member.anchorY = entity.getY();
			member.anchorZ = entity.getZ();
			member.anchorYSet = true;
			member.yaw = entity.getYRot();
			member.pitch = entity.getXRot();
			return member;
		}

		boolean matches(UUID playerUuid) {
			return !fake && uuid.equals(playerUuid.toString());
		}
	}

	private static final class MaskProfile {
		String name;
	}

	private static final class PendingInvite {
		String groupId;
		String leaderName;
		int expiresAtTick;
	}

	private static final class SaveData {
		int format = 3;
		EchoSettings settings;
		List<SavedEcho> echoes;
		List<EchoGroup> groups;
		Map<String, MaskProfile> masks;

		SaveData() {
		}

		SaveData(EchoManager manager) {
			settings = manager.settings;
			echoes = manager.echoes;
			groups = manager.groups;
			masks = manager.masks;
		}
	}
}
