package com.dee.client;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 디스플레이 엔티티 하나의 "그 순간의 모습". 되돌리기/복사/붙여넣기가 쓴다.
 * 클라이언트 엔티티에서 읽기 때문에 서버 전용인 Tags 는 들어 있지 않다.
 * (되돌리기로 다시 만들거나 붙여넣은 엔티티에는 태그가 없고, UI 로 바꾼 태그는 되돌리기에 포함되지 않는다)
 */
final class EntitySnapshot {
    /** 보존/복원하는 NBT 키. Pos / Rotation 은 따로 가진다. */
    static final String[] KEYS = {
            "text", "line_width", "background", "text_opacity", "alignment", "shadow", "see_through",
            "default_background", "block_state", "item", "item_display", "transformation", "billboard",
            "brightness", "view_range", "shadow_radius", "shadow_strength", "glow_color_override",
            "width", "height", "interpolation_duration", "teleport_duration"
    };

    final UUID id;
    /** summon 에 쓰는 엔티티 ID: text_display / block_display / item_display */
    final String type;
    final Vec3 pos;
    final float yaw;
    final float pitch;
    final CompoundTag data;

    private EntitySnapshot(UUID id, String type, Vec3 pos, float yaw, float pitch, CompoundTag data) {
        this.id = id;
        this.type = type;
        this.pos = pos;
        this.yaw = yaw;
        this.pitch = pitch;
        this.data = data;
    }

    static EntitySnapshot of(Display e) {
        CompoundTag full = EntityNbt.save(e);
        CompoundTag data = new CompoundTag();
        for (String key : KEYS) {
            Tag t = full.get(key);
            if (t != null) data.put(key, t.copy());
        }
        String type = e instanceof Display.BlockDisplay ? "block_display"
                : e instanceof Display.ItemDisplay ? "item_display" : "text_display";
        return new EntitySnapshot(e.getUUID(), type, e.position(), e.getYRot(), e.getXRot(), data);
    }

    /** 위치와 transformation 만 바꾼 복사본. */
    EntitySnapshot with(Vec3 newPos, Tag transformation) {
        CompoundTag d = data.copy();
        if (transformation != null) d.put("transformation", transformation);
        return new EntitySnapshot(id, type, newPos, yaw, pitch, d);
    }

    /** 다른 UUID / 위치로 붙여넣기 위한 복사본. */
    EntitySnapshot copyAs(UUID newId, Vec3 newPos) {
        return new EntitySnapshot(newId, type, newPos, yaw, pitch, data.copy());
    }

    /** 이 모습 그대로 엔티티를 (같은 UUID 로) 만드는 명령. */
    String summonCommand() {
        CompoundTag nbt = data.copy();
        nbt.put("UUID", new IntArrayTag(UUIDUtil.uuidToIntArray(id)));
        ListTag rot = new ListTag();
        rot.add(FloatTag.valueOf(yaw));
        rot.add(FloatTag.valueOf(pitch));
        nbt.put("Rotation", rot);
        return String.format(Locale.ROOT, "summon %s %.5f %.5f %.5f %s", type, pos.x, pos.y, pos.z, nbt);
    }

    String killCommand() {
        return "kill " + id;
    }

    /**
     * 살아 있는 엔티티를 target 의 모습으로 되돌리는 명령들.
     * other 는 "지금 엔티티의 모습"을 알 때만 넘긴다. 알면 달라진 것만 보내고, 모르면(null) 전부 보낸다.
     * 값은 data modify ... set 으로 통째로 바꾼다. (merge 는 block_state 의 속성처럼 옛 값이 남는다)
     */
    static List<String> restore(EntitySnapshot target, EntitySnapshot other) {
        List<String> out = new ArrayList<>();
        String id = target.id.toString();
        if (other == null || !target.pos.equals(other.pos) || target.yaw != other.yaw || target.pitch != other.pitch) {
            out.add(String.format(Locale.ROOT, "tp %s %.5f %.5f %.5f %.3f %.3f",
                    id, target.pos.x, target.pos.y, target.pos.z, target.yaw, target.pitch));
        }
        for (String key : KEYS) {
            Tag t = target.data.get(key);
            Tag o = other == null ? null : other.data.get(key);
            if (t != null) {
                if (o == null || !t.equals(o)) out.add("data modify entity " + id + " " + key + " set value " + t);
            } else if (o != null) {
                out.add("data remove entity " + id + " " + key);
            }
        }
        return out;
    }
}