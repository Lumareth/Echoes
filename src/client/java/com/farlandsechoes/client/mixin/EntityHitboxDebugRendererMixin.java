package com.farlandsechoes.client.mixin;

import com.farlandsechoes.EchoEntityState;
import net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityHitboxDebugRenderer.class)
public abstract class EntityHitboxDebugRendererMixin {
	@Inject(method = "showHitboxes", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$hideDisabledEchoHitboxes(
			Entity entity, float partialTick, boolean serverEntity, CallbackInfo callback) {
		if (EchoEntityState.isMount(entity)) {
			callback.cancel();
			return;
		}
		if (EchoEntityState.isEcho(entity) && !EchoEntityState.hasHitbox(entity)) {
			callback.cancel();
			return;
		}
		for (Entity passenger : entity.getPassengers()) {
			if (EchoEntityState.isEcho(passenger) && !EchoEntityState.hasHitbox(passenger)) {
				callback.cancel();
				return;
			}
		}
	}
}
