package com.farlandsechoes.client.mixin;

import com.farlandsechoes.client.ClientMaskedProfiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@Inject(method = "getPlayerInfo", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$useCurrentMaskedProfile(CallbackInfoReturnable<PlayerInfo> callback) {
		Minecraft minecraft = Minecraft.getInstance();
		AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
		if (minecraft.getConnection() != null) {
			PlayerInfo current = minecraft.getConnection().getPlayerInfo(self.getUUID());
			if (current != null) {
				callback.setReturnValue(current);
			}
		}
	}

	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$useCurrentMaskedSkin(CallbackInfoReturnable<PlayerSkin> callback) {
		AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
		PlayerSkin masked = ClientMaskedProfiles.skin(self.getUUID());
		if (masked != null) {
			callback.setReturnValue(masked);
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.getConnection() != null) {
			PlayerInfo current = minecraft.getConnection().getPlayerInfo(self.getUUID());
			if (current != null) {
				callback.setReturnValue(current.getSkin());
			}
		}
	}
}
