package com.dee.client;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;

/**
 * 엔티티 <-> NbtCompound 변환 도우미. (client/server 공용, net.minecraft.client.* import 금지)
 *
 * 1.21.5 는 엔티티가 NbtCompound 를 직접 받는다. (1.21.6 부터 ReadView / WriteView 로 바뀐다)
 */
final class EntityNbt {
    private EntityNbt() {}

    /** 엔티티의 현재 상태를 NBT 로 읽는다. (/data get entity 와 같은 내용, id 는 포함되지 않는다) */
    static NbtCompound save(Entity entity) {
        return entity.writeNbt(new NbtCompound());
    }

    /** NBT 를 엔티티에 적용한다. (/data merge entity 와 같은 방식) */
    static void load(Entity entity, NbtCompound tag) {
        entity.readNbt(tag);
    }
}
