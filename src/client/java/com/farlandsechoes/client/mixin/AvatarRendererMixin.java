package com.farlandsechoes.client.mixin;

import com.farlandsechoes.EchoEntityState;
import com.farlandsechoes.client.ClientMaskedProfiles;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Unique
	private final Map<Avatar, LocalAnimationState> farlandsEchoes$animations = new WeakHashMap<>();

	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void farlandsEchoes$applyRecordedPose(
			Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo callback) {
		String maskedName = ClientMaskedProfiles.name(entity.getUUID());
		if (maskedName != null) {
			state.skin = ClientMaskedProfiles.skin(entity.getUUID());
			if (state.nameTag != null) {
				state.nameTag = net.minecraft.network.chat.Component.literal(maskedName);
			}
		}
		if (!EchoEntityState.isEcho(entity)) {
			return;
		}
		LocalAnimationState animation = farlandsEchoes$animations.computeIfAbsent(entity,
				ignored -> new LocalAnimationState(EchoEntityState.swingSequence(entity)));

		int swingSequence = EchoEntityState.swingSequence(entity);
		if (swingSequence != animation.swingSequence) {
			animation.swingSequence = swingSequence;
			animation.swingStartTick = entity.tickCount;
		}
		float swingAge = animation.swingStartTick < 0 ? 7.0F
				: entity.tickCount - animation.swingStartTick + partialTick;
		if (swingAge >= 0.0F && swingAge <= 6.0F) {
			state.attackTime = Math.min(1.0F, swingAge / 6.0F);
			HumanoidArm mainArm = entity.getMainArm();
			state.attackArm = EchoEntityState.swingsOffHand(entity) ? mainArm.getOpposite() : mainArm;
		} else {
			state.attackTime = 0.0F;
		}
		int deathTime = EchoEntityState.deathTime(entity);
		if (deathTime > 0 && !animation.dying) {
			animation.dying = true;
			animation.deathStartTick = entity.tickCount - Math.min(19, Math.max(0, deathTime - 1));
		} else if (deathTime <= 0) {
			animation.dying = false;
			animation.deathStartTick = -1;
		}
		if (animation.dying) {
			state.deathTime = Math.min(20.0F,
					entity.tickCount - animation.deathStartTick + partialTick);
		}
		if (EchoEntityState.hurtTime(entity) > 0 || animation.dying) {
			state.hasRedOverlay = true;
		}
	}

	@Unique
	private static final class LocalAnimationState {
		int swingSequence;
		int swingStartTick = -1;
		boolean dying;
		int deathStartTick = -1;

		LocalAnimationState(int swingSequence) {
			this.swingSequence = swingSequence;
		}
	}
}
