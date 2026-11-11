package io.github.checkselfy.bakemechanisms.stasis;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/**
 * Target adapter for pinned Paper 26.3. The public setter misses ATTACK_TARGET on brain-backed mobs.
 * The fallback accesses only public native members, never private fields or setAccessible.
 */
public final class PaperMobTargets {
    private PaperMobTargets() {}

    public static void set(Mob mob, LivingEntity target) {
        mob.setTarget(target);
        if (matches(mob, target)) return;
        try {
            var handle = mob.getClass().getMethod("getHandle").invoke(mob);
            var brain = handle.getClass().getMethod("getBrain").invoke(handle);
            var moduleType = Class.forName("net.minecraft.world.entity.ai.memory.MemoryModuleType");
            var module = moduleType.getField("ATTACK_TARGET").get(null);
            var statusType = Class.forName("net.minecraft.world.entity.ai.memory.MemoryStatus");
            var registered = statusType.getField("REGISTERED").get(null);
            boolean supported = (boolean) brain.getClass().getMethod("checkMemory", moduleType, statusType)
                    .invoke(brain, module, registered);
            if (!supported) throw new IllegalStateException("У моба нет памяти ATTACK_TARGET: " + mob.getType());
            var nativeTarget = target == null ? null : target.getClass().getMethod("getHandle").invoke(target);
            brain.getClass().getMethod("setMemory", moduleType, Object.class).invoke(brain, module, nativeTarget);
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("Адаптер целей мобов несовместим с этой сборкой Paper. Требуется Paper 26.3 build 246.", error);
        }
        if (!matches(mob, target)) throw new IllegalStateException("Не удалось восстановить цель моба " + mob.getType());
    }

    private static boolean matches(Mob mob, LivingEntity target) {
        var actual = mob.getTarget();
        return target == null ? actual == null : actual != null && actual.getUniqueId().equals(target.getUniqueId());
    }
}
