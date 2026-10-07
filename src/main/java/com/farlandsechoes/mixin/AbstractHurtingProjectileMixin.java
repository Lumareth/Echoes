package com.farlandsechoes.mixin;

import com.farlandsechoes.EchoEntityState;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(AbstractHurtingProjectile.class)
public abstract class AbstractHurtingProjectileMixin {
	@Redirect(
			method = "tick",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getHitResultOnMoveVector(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;Lnet/minecraft/world/level/ClipContext$Block;)Lnet/minecraft/world/phys/HitResult;"))
	private HitResult farlandsEchoes$ghostsMissEverything(
			Entity entity, Predicate<Entity> predicate, ClipContext.Block clipType) {
		if (!EchoEntityState.isGhostProjectile(entity)) {
			return ProjectileUtil.getHitResultOnMoveVector(entity, predicate, clipType);
		}
		Vec3 destination = entity.position().add(entity.getDeltaMovement());
		return BlockHitResult.miss(destination, Direction.UP, BlockPos.containing(destination));
	}
}
