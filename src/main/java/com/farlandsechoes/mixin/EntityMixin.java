package com.farlandsechoes.mixin;

import com.farlandsechoes.EchoEntityState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "isPickable", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$disablePicking(CallbackInfoReturnable<Boolean> callback) {
		Entity self = (Entity) (Object) this;
		if ((EchoEntityState.isFake(self) && !EchoEntityState.canAttackFake(self)) || EchoEntityState.isMount(self)
				|| (EchoEntityState.isEcho(self) && !EchoEntityState.hasHitbox(self))) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "isAttackable", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$controlAttackability(CallbackInfoReturnable<Boolean> callback) {
		Entity self = (Entity) (Object) this;
		if (EchoEntityState.isMount(self)
				|| (EchoEntityState.isEcho(self) && !EchoEntityState.hasHitbox(self))
				|| (EchoEntityState.isFake(self) && !EchoEntityState.canAttackFake(self))) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "skipAttackInteraction", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$skipBlockedAttacks(Entity attacker, CallbackInfoReturnable<Boolean> callback) {
		Entity self = (Entity) (Object) this;
		if (EchoEntityState.isMount(self)
				|| (EchoEntityState.isEcho(self) && !EchoEntityState.hasHitbox(self))
				|| (EchoEntityState.isFake(self) && !EchoEntityState.canAttackFake(self))) {
			callback.setReturnValue(true);
		}
	}

	@Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$disablePushing(CallbackInfoReturnable<Boolean> callback) {
		Entity self = (Entity) (Object) this;
		if (EchoEntityState.isMount(self)
				|| (EchoEntityState.isEcho(self) && !EchoEntityState.hasCollision(self))) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "canCollideWith", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$disableCollision(Entity other, CallbackInfoReturnable<Boolean> callback) {
		Entity self = (Entity) (Object) this;
		if (blocksCollision(self) || blocksCollision(other)) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "canBeCollidedWith", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$disableIncomingCollision(Entity other, CallbackInfoReturnable<Boolean> callback) {
		Entity self = (Entity) (Object) this;
		if (blocksCollision(self) || blocksCollision(other)) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "kill", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$preventKill(ServerLevel level, CallbackInfo callback) {
		if (EchoEntityState.isProtected((Entity) (Object) this)) {
			callback.cancel();
		}
	}

	private static boolean blocksCollision(Entity entity) {
		return EchoEntityState.isMount(entity)
				|| (EchoEntityState.isEcho(entity) && !EchoEntityState.hasCollision(entity));
	}
}
