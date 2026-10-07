package com.farlandsechoes.mixin;

import com.farlandsechoes.EchoManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerIdentityMixin {
	@Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$maskedTabName(CallbackInfoReturnable<Component> callback) {
		EchoManager manager = EchoManager.current();
		if (manager == null) {
			return;
		}
		String masked = manager.maskedName(((ServerPlayer) (Object) this).getUUID());
		if (masked != null) {
			callback.setReturnValue(Component.literal(masked));
		}
	}
}
