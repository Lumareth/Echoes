package com.farlandsechoes.mixin;

import com.farlandsechoes.EchoEntityState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartMixin {
	@Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$disableCollision(Entity other, CallbackInfoReturnable<Boolean> callback) {
		if (isEchoMount() || EchoEntityState.isMount(other)) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = {"isPushable", "isPickable"}, at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$disablePhysicalSelection(CallbackInfoReturnable<Boolean> callback) {
		if (isEchoMount()) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$disablePush(Entity other, CallbackInfo callback) {
		if (isEchoMount() || EchoEntityState.isMount(other)) {
			callback.cancel();
		}
	}

	private boolean isEchoMount() {
		return EchoEntityState.isMount((AbstractMinecart) (Object) this);
	}
}
