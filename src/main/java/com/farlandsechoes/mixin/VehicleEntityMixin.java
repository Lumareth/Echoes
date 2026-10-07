package com.farlandsechoes.mixin;

import com.farlandsechoes.EchoEntityState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VehicleEntity.class)
public abstract class VehicleEntityMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$preventMountDamage(
			ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
		if (EchoEntityState.isMount((VehicleEntity) (Object) this)) {
			callback.setReturnValue(false);
		}
	}
}
