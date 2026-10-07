package com.farlandsechoes.mixin;

import com.farlandsechoes.EchoManager;
import java.util.List;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
	@Inject(method = "updateSignText", at = @At("HEAD"))
	private void farlandsEchoes$watchSignUpdate(
			ServerboundSignUpdatePacket packet, List<FilteredText> lines, CallbackInfo callback) {
		EchoManager manager = EchoManager.current();
		if (manager != null) {
			manager.notePlayerBlockTouch(packet.getPos());
			manager.watchBlockUpdate(((ServerGamePacketListenerImpl) (Object) this).player, packet.getPos());
		}
	}
}
