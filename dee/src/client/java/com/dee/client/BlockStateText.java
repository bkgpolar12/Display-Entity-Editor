package com.dee.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Optional;

/**
 * block_display 의 block_state NBT ({Name:"minecraft:oak_stairs",Properties:{facing:"east"}}) 와
 * "oak_stairs[facing=east]" 같은 사람이 쓰는 문자열 사이의 변환 도우미.
 * (client/server 공용, net.minecraft.client.* import 금지)
 */
final class BlockStateText {
    private BlockStateText() {}

    /** "stone" / "minecraft:stone" 처럼 이름만 받아 블록을 찾는다. 없으면 empty. */
    static Optional<Block> findBlock(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return Optional.empty();
        Identifier id = Identifier.tryParse(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return Optional.empty();
        return Optional.of(BuiltInRegistries.BLOCK.getValue(id));
    }

    /** block_state NBT 에서 블록을 꺼낸다. */
    static Optional<Block> blockOf(Tag tag) {
        if (!(tag instanceof CompoundTag state)) return Optional.empty();
        return findBlock(state.getStringOr("Name", ""));
    }

    /**
     * "oak_stairs[facing=east,half=top]" 를 block_state NBT 로 바꾼다.
     * 블록이 없거나, 그 블록에 없는 속성/값이면 empty. (속성을 안 쓰면 블록 기본값이 적용된다)
     */
    static Optional<CompoundTag> parse(String text) {
        String s = text.trim();
        if (s.isEmpty()) return Optional.empty();

        String name = s;
        String props = "";
        int open = s.indexOf('[');
        if (open >= 0) {
            if (!s.endsWith("]")) return Optional.empty();
            name = s.substring(0, open);
            props = s.substring(open + 1, s.length() - 1);
        }

        Optional<Block> found = findBlock(name);
        if (found.isEmpty()) return Optional.empty();
        Block block = found.get();

        String trimmed = name.trim();
        CompoundTag state = new CompoundTag();
        state.putString("Name", trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);

        CompoundTag properties = new CompoundTag();
        if (!props.isBlank()) {
            for (String pair : props.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) return Optional.empty();
                String key = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                Property<?> property = block.getStateDefinition().getProperty(key);
                if (property == null || property.getValue(value).isEmpty()) return Optional.empty();
                properties.putString(key, value);
            }
        }
        if (!properties.isEmpty()) state.put("Properties", properties);
        return Optional.of(state);
    }

    /** block_state NBT 를 입력칸에 보여 줄 문자열로 바꾼다. (minecraft: 는 생략) */
    static String format(Tag tag) {
        if (!(tag instanceof CompoundTag state)) return "";
        String name = state.getStringOr("Name", "");
        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());

        CompoundTag properties = state.getCompoundOrEmpty("Properties");
        if (properties.isEmpty()) return name;

        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (String key : properties.keySet().stream().sorted().toList()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(key).append('=').append(properties.getStringOr(key, ""));
        }
        return sb.append(']').toString();
    }
}