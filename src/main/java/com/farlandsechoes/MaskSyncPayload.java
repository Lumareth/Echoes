package com.farlandsechoes;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.component.ResolvableProfile;

public record MaskSyncPayload(UUID playerUuid, ResolvableProfile profile) implements CustomPacketPayload {
	public static final Type<MaskSyncPayload> TYPE = new Type<>(FarlandsEchoes.id("mask_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MaskSyncPayload> CODEC = CustomPacketPayload.codec(
			(payload, buffer) -> {
				buffer.writeUUID(payload.playerUuid);
				buffer.writeBoolean(payload.profile != null);
				if (payload.profile != null) {
					ResolvableProfile.STREAM_CODEC.encode(buffer, payload.profile);
				}
			},
			buffer -> {
				UUID playerUuid = buffer.readUUID();
				ResolvableProfile profile = buffer.readBoolean()
						? ResolvableProfile.STREAM_CODEC.decode(buffer) : null;
				return new MaskSyncPayload(playerUuid, profile);
			});

	@Override
	public Type<MaskSyncPayload> type() {
		return TYPE;
	}
}
