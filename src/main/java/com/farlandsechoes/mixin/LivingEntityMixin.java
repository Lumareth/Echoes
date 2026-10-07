package com.farlandsechoes.mixin;

import com.farlandsechoes.EchoEntityState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "updateFallFlying", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$keepRecordedGlideState(CallbackInfo callback) {
		if (EchoEntityState.isEcho((LivingEntity) (Object) this)) {
			callback.cancel();
		}
	}

	@Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$controlPicking(CallbackInfoReturnable<Boolean> callback) {
		LivingEntity self = (LivingEntity) (Object) this;
		if ((EchoEntityState.isEcho(self) && !EchoEntityState.hasHitbox(self))
				|| (EchoEntityState.isFake(self) && !EchoEntityState.canAttackFake(self))
				|| EchoEntityState.isMount(self)) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$controlPushing(CallbackInfoReturnable<Boolean> callback) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (EchoEntityState.isMount(self)
				|| (EchoEntityState.isEcho(self) && !EchoEntityState.hasCollision(self))) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$controlEntityPush(Entity other, CallbackInfo callback) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (blocksCollision(self) || blocksCollision(other)) {
			callback.cancel();
		}
	}

	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$preventDamage(
			ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
		if (EchoEntityState.isProtected((LivingEntity) (Object) this)) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "isInvulnerableTo", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$alwaysInvulnerable(
			ServerLevel level, DamageSource source, CallbackInfoReturnable<Boolean> callback) {
		if (EchoEntityState.isProtected((LivingEntity) (Object) this)) {
			callback.setReturnValue(true);
		}
	}

	private static boolean blocksCollision(Entity entity) {
		return EchoEntityState.isMount(entity)
				|| (EchoEntityState.isEcho(entity) && !EchoEntityState.hasCollision(entity));
	}
}
