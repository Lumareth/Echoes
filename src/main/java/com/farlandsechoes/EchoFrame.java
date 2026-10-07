package com.farlandsechoes;

import com.farlandsechoes.mixin.EntityAccessor;
import com.farlandsechoes.mixin.ServerPlayerGameModeAccessor;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.Vec3;

final class EchoFrame {
	double x;
	double y;
	double z;
	float yaw;
	float pitch;
	float bodyYaw;
	float headYaw;
	String pose;
	boolean sprinting;
	boolean swing;
	String swingHand;
	boolean usingItem;
	String useHand;
	boolean fallFlying;
	int hurtTime;
	int hurtDuration;
	int deathTime;
	boolean breakingBlock;
	int breakX;
	int breakY;
	int breakZ;
	int breakStage;
	String vehicleUuid;
	String vehicleType;
	double vehicleX;
	double vehicleY;
	double vehicleZ;
	float vehicleYaw;
	float vehiclePitch;
	Map<String, JsonElement> equipment = new HashMap<>();
	List<EchoAction> actions = new ArrayList<>();

	private transient EnumMap<EquipmentSlot, ItemStack> decodedEquipment;

	static EchoFrame capture(
			LivingEntity source,
			RegistryAccess registries,
			boolean swingStarted,
			String recordedSwingHand,
			int recordedDeathTicks,
			List<EchoAction> actions
	) {
		EchoFrame frame = new EchoFrame();
		frame.x = source.getX();
		frame.y = source.getY();
		frame.z = source.getZ();
		frame.yaw = source.getYRot();
		frame.pitch = source.getXRot();
		frame.bodyYaw = source.yBodyRot;
		frame.headYaw = source.getYHeadRot();
		frame.pose = source.getPose().name();
		frame.sprinting = source.isSprinting();
		frame.swing = swingStarted;
		frame.swingHand = recordedSwingHand != null ? recordedSwingHand
				: source.swingingArm == null ? InteractionHand.MAIN_HAND.name() : source.swingingArm.name();
		frame.usingItem = source.isUsingItem();
		frame.useHand = source.getUsedItemHand().name();
		frame.fallFlying = source.isFallFlying();
		frame.hurtTime = source.hurtTime;
		frame.hurtDuration = source.hurtDuration;
		frame.deathTime = Math.max(source.deathTime, recordedDeathTicks);
		frame.actions = new ArrayList<>(actions);
		if (source instanceof ServerPlayer player) {
			ServerPlayerGameModeAccessor gameMode = (ServerPlayerGameModeAccessor) player.gameMode;
			frame.breakingBlock = gameMode.farlandsEchoes$isDestroyingBlock();
			BlockPos destroyPos = gameMode.farlandsEchoes$getDestroyPos();
			if (frame.breakingBlock && destroyPos != null) {
				frame.breakX = destroyPos.getX();
				frame.breakY = destroyPos.getY();
				frame.breakZ = destroyPos.getZ();
				frame.breakStage = Math.max(0, Math.min(9, gameMode.farlandsEchoes$getLastSentState()));
			}
		}
		Entity vehicle = source.getVehicle();
		if (vehicle != null) {
			frame.vehicleUuid = vehicle.getUUID().toString();
			frame.vehicleType = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString();
			frame.vehicleX = vehicle.getX();
			frame.vehicleY = vehicle.getY();
			frame.vehicleZ = vehicle.getZ();
			frame.vehicleYaw = vehicle.getYRot();
			frame.vehiclePitch = vehicle.getXRot();
		}

		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
		for (EquipmentSlot slot : trackedSlots()) {
			JsonElement encoded = ItemStack.OPTIONAL_CODEC.encodeStart(ops, source.getItemBySlot(slot))
					.getOrThrow(error -> new IllegalStateException("Could not encode " + slot + ": " + error));
			frame.equipment.put(slot.getSerializedName(), encoded);
		}
		return frame;
	}

	void applyVisuals(Mannequin echo, RegistryAccess registries) {
		echo.setPos(x, y, z);
		echo.setYRot(yaw);
		echo.setXRot(pitch);
		echo.setYBodyRot(bodyYaw);
		echo.setYHeadRot(headYaw);
		echo.setPose(parsePose());
		((EntityAccessor) echo).farlandsEchoes$setSharedFlag(7, fallFlying);
		echo.setSprinting(sprinting);
		echo.setDeltaMovement(Vec3.ZERO);
		echo.deathTime = deathTime;
		echo.hurtDuration = Math.max(hurtDuration, hurtTime);
		echo.hurtTime = deathTime > 0 && hurtTime == 0 ? 10 : hurtTime;

		for (Map.Entry<EquipmentSlot, ItemStack> entry : decodedEquipment(registries).entrySet()) {
			EquipmentSlot slot = entry.getKey();
			ItemStack desired = entry.getValue();
			if (!ItemStack.matches(echo.getItemBySlot(slot), desired)) {
				echo.setItemSlot(slot, desired.copy());
			}
		}

		if (usingItem && !echo.isUsingItem()) {
			echo.startUsingItem(parseHand(useHand));
		} else if (!usingItem && echo.isUsingItem()) {
			echo.stopUsingItem();
		}
	}

	static InteractionHand parseHand(String value) {
		try {
			return InteractionHand.valueOf(value);
		} catch (IllegalArgumentException | NullPointerException exception) {
			return InteractionHand.MAIN_HAND;
		}
	}

	private EnumMap<EquipmentSlot, ItemStack> decodedEquipment(RegistryAccess registries) {
		if (decodedEquipment != null) {
			return decodedEquipment;
		}

		decodedEquipment = new EnumMap<>(EquipmentSlot.class);
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
		for (EquipmentSlot slot : trackedSlots()) {
			JsonElement encoded = equipment.get(slot.getSerializedName());
			if (encoded == null) {
				decodedEquipment.put(slot, ItemStack.EMPTY);
				continue;
			}
			ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(ops, encoded)
					.getOrThrow(error -> new IllegalStateException("Could not decode " + slot + ": " + error));
			if (!stack.isEmpty()) {
				// Echo equipment is visual only. Mannequins do not expose every player
				// attribute (notably ATTACK_DAMAGE), so allowing a sword's equipment
				// modifiers to be applied makes LivingEntity's equipment tick crash. The
				// 1.21.11 kinetic-weapon component also reads ATTACK_DAMAGE every tick,
				// so it must not run on the visual copy either.
				stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
				stack.remove(DataComponents.KINETIC_WEAPON);
			}
			decodedEquipment.put(slot, stack);
		}
		return decodedEquipment;
	}

	private Pose parsePose() {
		try {
			Pose parsed = Pose.valueOf(pose);
			// Mannequins only serialize player movement poses. Their death animation is
			// replayed through deathTime, so assigning DYING here just corrupts the
			// mannequin pose and floods chunk serialization with errors.
			return parsed == Pose.DYING ? Pose.STANDING : parsed;
		} catch (IllegalArgumentException | NullPointerException exception) {
			return Pose.STANDING;
		}
	}

	private static EquipmentSlot[] trackedSlots() {
		return new EquipmentSlot[] {
				EquipmentSlot.MAINHAND,
				EquipmentSlot.OFFHAND,
				EquipmentSlot.HEAD,
				EquipmentSlot.CHEST,
				EquipmentSlot.LEGS,
				EquipmentSlot.FEET
		};
	}
}
