package com.farlandsechoes;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

final class EchoAction {
	static final String BLOCK_CHANGE = "block_change";
	static final String ATTACK_ENTITY = "attack_entity";
	static final String USE = "use";
	static final String PROJECTILE = "projectile";

	String type;
	String hand;
	String targetUuid;
	int x;
	int y;
	int z;
	double preciseX;
	double preciseY;
	double preciseZ;
	double velocityX;
	double velocityY;
	double velocityZ;
	float yaw;
	float pitch;
	String entityType;
	JsonElement beforeState;
	JsonElement afterState;
	String beforeBlockEntity;
	String afterBlockEntity;

	static EchoAction use(String hand) {
		EchoAction action = new EchoAction();
		action.type = USE;
		action.hand = hand;
		return action;
	}

	static EchoAction attack(Entity target, String hand) {
		EchoAction action = new EchoAction();
		action.type = ATTACK_ENTITY;
		action.hand = hand;
		action.targetUuid = target.getUUID().toString();
		action.preciseX = target.getX();
		action.preciseY = target.getY();
		action.preciseZ = target.getZ();
		return action;
	}

	static EchoAction blockChange(BlockPos pos, BlockState before, BlockState after, RegistryAccess registries) {
		return blockChange(pos, before, after, null, null, registries);
	}

	static EchoAction blockChange(
			BlockPos pos,
			BlockState before,
			BlockState after,
			String beforeBlockEntity,
			String afterBlockEntity,
			RegistryAccess registries
	) {
		EchoAction action = new EchoAction();
		action.type = BLOCK_CHANGE;
		action.x = pos.getX();
		action.y = pos.getY();
		action.z = pos.getZ();
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
		action.beforeState = BlockState.CODEC.encodeStart(ops, before)
				.getOrThrow(error -> new IllegalStateException("Could not encode old block state: " + error));
		action.afterState = BlockState.CODEC.encodeStart(ops, after)
				.getOrThrow(error -> new IllegalStateException("Could not encode new block state: " + error));
		action.beforeBlockEntity = beforeBlockEntity;
		action.afterBlockEntity = afterBlockEntity;
		return action;
	}

	static EchoAction projectile(Entity projectile, String typeId) {
		EchoAction action = new EchoAction();
		action.type = PROJECTILE;
		action.entityType = typeId;
		action.preciseX = projectile.getX();
		action.preciseY = projectile.getY();
		action.preciseZ = projectile.getZ();
		action.velocityX = projectile.getDeltaMovement().x;
		action.velocityY = projectile.getDeltaMovement().y;
		action.velocityZ = projectile.getDeltaMovement().z;
		action.yaw = projectile.getYRot();
		action.pitch = projectile.getXRot();
		return action;
	}

	BlockPos blockPos() {
		return new BlockPos(x, y, z);
	}

	BlockState before(RegistryAccess registries) {
		return decodeState(beforeState, registries);
	}

	BlockState after(RegistryAccess registries) {
		return decodeState(afterState, registries);
	}

	private static BlockState decodeState(JsonElement json, RegistryAccess registries) {
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
		return BlockState.CODEC.parse(ops, json)
				.getOrThrow(error -> new IllegalStateException("Could not decode block state: " + error));
	}
}
