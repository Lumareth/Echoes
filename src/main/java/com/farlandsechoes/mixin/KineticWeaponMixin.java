package com.farlandsechoes.mixin;

import com.farlandsechoes.EchoEntityState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.KineticWeapon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KineticWeapon.class)
public abstract class KineticWeaponMixin {
	@Inject(method = "damageEntities", at = @At("HEAD"), cancellable = true)
	private void farlandsEchoes$skipEchoContactDamage(
			ItemStack stack,
			int useTicks,
			LivingEntity holder,
			EquipmentSlot slot,
			CallbackInfo callback
	) {
		// Echoes only replay a weapon visually. This also protects worlds that
		// already contain an echo holding an unsanitized kinetic weapon.
		if (EchoEntityState.isEcho(holder)) {
			callback.cancel();
		}
	}
}
