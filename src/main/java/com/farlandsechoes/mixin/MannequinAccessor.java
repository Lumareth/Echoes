package com.farlandsechoes.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Mannequin.class)
public interface MannequinAccessor {
	@Accessor("DATA_PROFILE")
	static EntityDataAccessor<ResolvableProfile> farlandsEchoes$getDataProfile() {
		throw new AssertionError();
	}

	@Invoker("setHideDescription")
	void farlandsEchoes$setHideDescription(boolean hidden);

	@Invoker("setProfile")
	void farlandsEchoes$setProfile(ResolvableProfile profile);
}
