package com.farlandsechoes.client.mixin;

import com.farlandsechoes.EchoEntityState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
			at = @At("TAIL"))
	private void farlandsEchoes$hideMountMarkerName(
			Entity entity, EntityRenderState state, float partialTick, CallbackInfo callback) {
		if (EchoEntityState.isMount(entity)) {
			state.nameTag = null;
			state.nameTagAttachment = null;
		}
	}
}
