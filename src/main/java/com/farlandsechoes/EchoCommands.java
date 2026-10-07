package com.farlandsechoes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class EchoCommands {
	private static final List<String> SETTING_NAMES = List.of(
			"AllowFakePlayers",
			"EchoHitboxes",
			"EchoCollisions",
			"MaximumGroups",
			"MaximumPlayersInGroup",
			"MaximumEchoes",
			"EchoNameTags",
			"EchoBlockDrops",
			"AllowDeletingAllEchoes",
			"AttackFakePlayers",
			"EchoIDs");

	private EchoCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("echo")
				.then(Commands.literal("record").executes(context -> runPlayer(context, EchoManager.get()::beginRecording)))
				.then(Commands.literal("pause").executes(context -> runPlayer(context, EchoManager.get()::pauseRecording)))
				.then(Commands.literal("stop").executes(context -> runPlayer(context, EchoManager.get()::finishRecording)))
				.then(Commands.literal("list").executes(EchoCommands::list))
				.then(Commands.literal("rename")
						.then(Commands.argument("echo", StringArgumentType.string())
								.suggests((context, builder) -> suggestEchoes(builder, null))
								.then(Commands.argument("new_name", StringArgumentType.string())
										.executes(context -> send(context, EchoManager.get().renameEcho(
												StringArgumentType.getString(context, "echo"),
												StringArgumentType.getString(context, "new_name")))))))
				.then(Commands.literal("delete")
						.then(Commands.literal("all").executes(context -> send(context, EchoManager.get().deleteAllEchoes())))
						.then(echoArgument(null).executes(context -> send(context, EchoManager.get().deleteEcho(
								StringArgumentType.getString(context, "echo"))))))
				.then(Commands.literal("disable")
						.then(echoArgument(true).executes(context -> send(context, EchoManager.get().setEchoEnabled(
								StringArgumentType.getString(context, "echo"), false)))))
				.then(Commands.literal("enable")
						.then(echoArgument(false).executes(context -> send(context, EchoManager.get().setEchoEnabled(
								StringArgumentType.getString(context, "echo"), true)))))
				.then(Commands.literal("sync")
						.then(Commands.literal("all").executes(context -> send(context, EchoManager.get().syncAllEchoes())))
						.then(echoArgument(null).executes(context -> send(context, EchoManager.get().syncEcho(
								StringArgumentType.getString(context, "echo"))))))
				.then(Commands.literal("teleport")
						.then(echoArgument(null).executes(context -> runPlayer(context, player ->
								EchoManager.get().teleportToEcho(player, StringArgumentType.getString(context, "echo"))))))
				.then(Commands.literal("mask")
						.then(Commands.literal("clear").executes(context -> runPlayer(context, EchoManager.get()::clearMask)))
						.then(Commands.argument("player", StringArgumentType.word())
								.suggests(EchoCommands::suggestOnlinePlayers)
								.executes(context -> runPlayer(context, player -> EchoManager.get().setMask(
										player, StringArgumentType.getString(context, "player"))))))
				.then(Commands.literal("accept").executes(context -> runPlayer(context, EchoManager.get()::acceptInvite)))
				.then(Commands.literal("refuse").executes(context -> runPlayer(context, EchoManager.get()::refuseInvite)))
				.then(groupCommands())
				.then(settingsCommands()));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> groupCommands() {
		return Commands.literal("group")
				.then(Commands.literal("create").requires(EchoCommands::hasNoGroup)
						.executes(context -> runPlayer(context, EchoManager.get()::createGroup)))
				.then(Commands.literal("leave").requires(EchoCommands::hasGroup)
						.executes(context -> runPlayer(context, EchoManager.get()::leaveGroup)))
				.then(Commands.literal("disband").requires(EchoCommands::hasGroup)
						.executes(context -> runPlayer(context, EchoManager.get()::disbandGroup)))
				.then(Commands.literal("list").requires(EchoCommands::hasGroup).executes(context -> {
					ServerPlayer player = context.getSource().getPlayerOrException();
					context.getSource().sendSuccess(() -> EchoManager.get().groupList(player), false);
					return 1;
				}))
				.then(Commands.literal("invite").requires(EchoCommands::hasGroup)
						.then(Commands.argument("player", StringArgumentType.word())
								.suggests(EchoCommands::suggestOnlinePlayers)
								.executes(context -> runPlayer(context, player -> EchoManager.get().inviteToGroup(
										player, StringArgumentType.getString(context, "player"))))))
				.then(Commands.literal("kick").requires(EchoCommands::hasGroup)
						.then(Commands.argument("player", StringArgumentType.string())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(
										groupMemberNames(context), builder))
								.executes(context -> runPlayer(context, player -> EchoManager.get().kickGroupMember(
										player, StringArgumentType.getString(context, "player"))))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> settingsCommands() {
		return Commands.literal("settings")
				.executes(EchoCommands::showSettings)
				.then(Commands.argument("setting", StringArgumentType.word())
						.suggests(EchoCommands::suggestSettings)
						.then(Commands.argument("value", StringArgumentType.word())
								.suggests(EchoCommands::suggestSettingValues)
								.executes(EchoCommands::changeSetting)));
	}

	private static int showSettings(CommandContext<CommandSourceStack> context) {
		EchoSettings settings = EchoManager.get().settings();
		String message = "Echo settings:"
				+ "\nAllowFakePlayers: " + settings.allowFakePlayers
				+ "\nEchoHitboxes: " + settings.echoHitboxes
				+ "\nEchoCollisions: " + settings.echoCollisions
				+ "\nMaximumGroups: " + settings.maximumGroups
				+ "\nMaximumPlayersInGroup: " + settings.maximumPlayersInGroup
				+ "\nMaximumEchoes: " + settings.maximumEchoes
				+ "\nEchoNameTags: " + settings.echoNameTags
				+ "\nEchoBlockDrops: " + settings.echoBlockDrops
				+ "\nAllowDeletingAllEchoes: " + settings.allowDeletingAllEchoes
				+ "\nAttackFakePlayers: " + settings.attackFakePlayers + " (live)"
				+ "\nEchoIDs: " + settings.echoIDs + " (live)";
		context.getSource().sendSuccess(() -> Component.literal(message), false);
		return 1;
	}

	private static int changeSetting(CommandContext<CommandSourceStack> context) {
		String requested = StringArgumentType.getString(context, "setting");
		String name = exactSettingName(requested);
		if (name == null) {
			context.getSource().sendFailure(Component.literal("Unknown setting: " + requested));
			return 0;
		}
		String value = StringArgumentType.getString(context, "value");
		EchoSettings settings = EchoManager.get().settings();
		try {
			switch (name) {
				case "AllowFakePlayers" -> settings.allowFakePlayers = parseBoolean(value);
				case "EchoHitboxes" -> settings.echoHitboxes = parseBoolean(value);
				case "EchoCollisions" -> settings.echoCollisions = parseBoolean(value);
				case "MaximumGroups" -> settings.maximumGroups = parseInteger(value, 0, 750);
				case "MaximumPlayersInGroup" -> settings.maximumPlayersInGroup = parseInteger(value, 1, 600);
				case "MaximumEchoes" -> settings.maximumEchoes = parseInteger(value, 0, 300);
				case "EchoNameTags" -> settings.echoNameTags = parseBoolean(value);
				case "EchoBlockDrops" -> settings.echoBlockDrops = parseBoolean(value);
				case "AllowDeletingAllEchoes" -> settings.allowDeletingAllEchoes = parseBoolean(value);
				case "AttackFakePlayers" -> settings.attackFakePlayers = parseBoolean(value);
				case "EchoIDs" -> settings.echoIDs = parseBoolean(value);
				default -> throw new IllegalArgumentException("Unknown setting");
			}
		} catch (IllegalArgumentException exception) {
			context.getSource().sendFailure(Component.literal(exception.getMessage()));
			return 0;
		}
		EchoManager.get().settingsChanged();
		context.getSource().sendSuccess(() -> Component.literal(name + ": " + value.toLowerCase()), false);
		return 1;
	}

	private static CompletableFuture<Suggestions> suggestSettings(
			CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		String query = normalize(builder.getRemaining());
		for (String name : SETTING_NAMES) {
			if (query.isEmpty() || fuzzyMatch(normalize(name), query)) {
				builder.suggest(name);
			}
		}
		return builder.buildFuture();
	}

	private static CompletableFuture<Suggestions> suggestSettingValues(
			CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		String name = exactSettingName(StringArgumentType.getString(context, "setting"));
		if (name != null && !name.startsWith("Maximum")) {
			builder.suggest("true");
			builder.suggest("false");
		}
		return builder.buildFuture();
	}

	private static String exactSettingName(String requested) {
		for (String name : SETTING_NAMES) {
			if (name.equalsIgnoreCase(requested)) {
				return name;
			}
		}
		return null;
	}

	private static boolean parseBoolean(String value) {
		if ("true".equalsIgnoreCase(value)) {
			return true;
		}
		if ("false".equalsIgnoreCase(value)) {
			return false;
		}
		throw new IllegalArgumentException("Use true or false.");
	}

	private static int parseInteger(String value, int minimum, int maximum) {
		try {
			int parsed = Integer.parseInt(value);
			if (parsed < minimum || parsed > maximum) {
				throw new IllegalArgumentException("Use " + minimum + "-" + maximum + ".");
			}
			return parsed;
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Use " + minimum + "-" + maximum + ".");
		}
	}

	private static String normalize(String value) {
		return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private static boolean fuzzyMatch(String candidate, String query) {
		if (candidate.contains(query)) {
			return true;
		}
		int queryIndex = 0;
		for (int index = 0; index < candidate.length() && queryIndex < query.length(); index++) {
			if (candidate.charAt(index) == query.charAt(queryIndex)) {
				queryIndex++;
			}
		}
		return queryIndex == query.length();
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		context.getSource().sendSuccess(() -> EchoManager.get().echoList(), false);
		return 1;
	}

	private static int runPlayer(CommandContext<CommandSourceStack> context, PlayerOperation operation)
			throws CommandSyntaxException {
		return send(context, operation.run(context.getSource().getPlayerOrException()));
	}

	private static int send(CommandContext<CommandSourceStack> context, EchoManager.OperationResult result) {
		if (result.success()) {
			context.getSource().sendSuccess(() -> Component.literal(result.message()), false);
			return 1;
		}
		context.getSource().sendFailure(Component.literal(result.message()));
		return 0;
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> echoArgument(
			Boolean enabled) {
		return Commands.argument("echo", StringArgumentType.string())
				.suggests((context, builder) -> suggestEchoes(builder, enabled));
	}

	private static CompletableFuture<Suggestions> suggestEchoes(SuggestionsBuilder builder, Boolean enabled) {
		return SharedSuggestionProvider.suggest(EchoManager.get().echoNames(enabled).stream()
				.map(StringArgumentType::escapeIfRequired), builder);
	}

	private static CompletableFuture<Suggestions> suggestOnlinePlayers(
			CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		java.util.UUID sourceUuid = context.getSource().getEntity() instanceof ServerPlayer player
				? player.getUUID() : null;
		List<String> names = context.getSource().getServer().getPlayerList().getPlayers().stream()
				.filter(player -> sourceUuid == null || !player.getUUID().equals(sourceUuid))
				.map(ServerPlayer::getScoreboardName)
				.toList();
		return SharedSuggestionProvider.suggest(names, builder);
	}

	private static List<String> groupMemberNames(CommandContext<CommandSourceStack> context) {
		try {
			return EchoManager.get().groupMemberNames(context.getSource().getPlayerOrException()).stream()
					.map(StringArgumentType::escapeIfRequired)
					.toList();
		} catch (CommandSyntaxException ignored) {
			return List.of();
		}
	}

	private static boolean hasGroup(CommandSourceStack source) {
		EchoManager manager = EchoManager.current();
		return manager != null && source.getEntity() instanceof ServerPlayer player && manager.hasGroup(player);
	}

	private static boolean hasNoGroup(CommandSourceStack source) {
		EchoManager manager = EchoManager.current();
		return manager != null && source.getEntity() instanceof ServerPlayer player && !manager.hasGroup(player);
	}

	@FunctionalInterface
	private interface PlayerOperation {
		EchoManager.OperationResult run(ServerPlayer player);
	}

}
