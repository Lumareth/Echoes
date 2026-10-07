package com.farlandsechoes.mixin;

import com.farlandsechoes.EchoEntityState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Projectile.class)
public abstract class ProjectileMixin {
	@Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$ignoreGhostTargets(Entity target, CallbackInfoReturnable<Boolean> callback) {
		if (isGhost()) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "mayInteract", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$preventGhostInteraction(
			ServerLevel level, net.minecraft.core.BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
		if (isGhost()) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "mayBreak", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$preventGhostBreaking(
			ServerLevel level, CallbackInfoReturnable<Boolean> callback) {
		if (isGhost()) {
			callback.setReturnValue(false);
		}
	}

	private boolean isGhost() {
		return EchoEntityState.isGhostProjectile((Projectile) (Object) this);
	}
}
