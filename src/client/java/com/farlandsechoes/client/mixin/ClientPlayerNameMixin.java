package com.farlandsechoes.client.mixin;

import com.farlandsechoes.client.ClientMaskedProfiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class ClientPlayerNameMixin {
	@Inject(method = "getName", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$maskedName(CallbackInfoReturnable<Component> callback) {
		String name = currentProfileName();
		if (name != null) {
			callback.setReturnValue(Component.literal(name));
		}
	}

	@Inject(method = "getPlainTextName", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$maskedPlainName(CallbackInfoReturnable<String> callback) {
		String name = currentProfileName();
		if (name != null) {
			callback.setReturnValue(name);
		}
	}

	@Inject(method = "getScoreboardName", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$maskedScoreboardName(CallbackInfoReturnable<String> callback) {
		String name = currentProfileName();
		if (name != null) {
			callback.setReturnValue(name);
		}
	}

	private String currentProfileName() {
		Minecraft minecraft = Minecraft.getInstance();
		Player self = (Player) (Object) this;
		String synced = ClientMaskedProfiles.name(self.getUUID());
		if (synced != null) {
			return synced;
		}
		if (minecraft.getConnection() == null) {
			return null;
		}
		PlayerInfo info = minecraft.getConnection().getPlayerInfo(self.getUUID());
		return info == null ? null : info.getProfile().name();
	}
}
