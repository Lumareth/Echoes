package com.farlandsechoes.mixin;

import com.farlandsechoes.EchoManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerIdentityMixin {
	@Inject(method = "getName", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$maskedName(CallbackInfoReturnable<Component> callback) {
		String name = maskedName();
		if (name != null) {
			callback.setReturnValue(Component.literal(name));
		}
	}

	@Inject(method = "getPlainTextName", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$maskedPlainName(CallbackInfoReturnable<String> callback) {
		String name = maskedName();
		if (name != null) {
			callback.setReturnValue(name);
		}
	}

	@Inject(method = "getScoreboardName", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$maskedScoreboardName(CallbackInfoReturnable<String> callback) {
		String name = maskedName();
		if (name != null) {
			callback.setReturnValue(name);
		}
	}

	private String maskedName() {
		EchoManager manager = EchoManager.current();
		return manager == null ? null : manager.maskedName(((Player) (Object) this).getUUID());
	}
}
