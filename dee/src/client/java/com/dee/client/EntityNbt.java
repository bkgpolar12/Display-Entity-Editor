package com.dee.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * 엔티티 <-> CompoundTag 변환 도우미. (client/server 공용, net.minecraft.client.* import 금지)
 *
 * 1.21.6 부터 엔티티는 CompoundTag 를 직접 받지 않고 ValueOutput / ValueInput 으로만 저장·로드한다.
 * (1.21.5 의 entity.writeNbt(tag) / entity.readNbt(tag) 에 해당)
 */
final class EntityNbt {
    private EntityNbt() {}

    /** 엔티티의 현재 상태를 NBT 로 읽는다. (/data get entity 와 같은 내용) */
    static CompoundTag save(Entity entity) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.level().registryAccess());
        entity.saveWithoutId(out);
        return out.buildResult();
    }

    /** NBT 를 엔티티에 적용한다. (/data merge entity 와 같은 방식) */
    static void load(Entity entity, CompoundTag tag) {
        entity.load(TagValueInput.create(ProblemReporter.DISCARDING, entity.level().registryAccess(), tag));
    }
}
