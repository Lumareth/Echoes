package com.farlandsechoes.mixin;

import com.farlandsechoes.EchoEntityState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void farlandsEchoes$keepGhostArrowNonPhysical(CallbackInfo callback) {
		AbstractArrow self = (AbstractArrow) (Object) this;
		if (EchoEntityState.isGhostProjectile(self)) {
			self.setNoPhysics(true);
		}
	}

	@Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$ignoreGhostArrowTargets(
			Entity target, CallbackInfoReturnable<Boolean> callback) {
		if (EchoEntityState.isGhostProjectile((AbstractArrow) (Object) this)) {
			callback.setReturnValue(false);
		}
	}
}
