package com.dee.client;

import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

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
        if (id == null || !Registries.BLOCK.containsId(id)) return Optional.empty();
        return Optional.of(Registries.BLOCK.get(id));
    }

    /** block_state NBT 에서 블록을 꺼낸다. */
    static Optional<Block> blockOf(NbtElement tag) {
        if (!(tag instanceof NbtCompound state)) return Optional.empty();
        return findBlock(state.getString("Name", ""));
    }

    /**
     * "oak_stairs[facing=east,half=top]" 를 block_state NBT 로 바꾼다.
     * 블록이 없거나, 그 블록에 없는 속성/값이면 empty. (속성을 안 쓰면 블록 기본값이 적용된다)
     */
    static Optional<NbtCompound> parse(String text) {
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
        NbtCompound state = new NbtCompound();
        state.putString("Name", trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);

        NbtCompound properties = new NbtCompound();
        if (!props.isBlank()) {
            for (String pair : props.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) return Optional.empty();
                String key = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                Property<?> property = block.getStateManager().getProperty(key);
                if (property == null || property.parse(value).isEmpty()) return Optional.empty();
                properties.putString(key, value);
            }
        }
        if (!properties.isEmpty()) state.put("Properties", properties);
        return Optional.of(state);
    }

    /** block_state NBT 를 입력칸에 보여 줄 문자열로 바꾼다. (minecraft: 는 생략) */
    static String format(NbtElement tag) {
        if (!(tag instanceof NbtCompound state)) return "";
        String name = state.getString("Name", "");
        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());

        NbtCompound properties = state.getCompoundOrEmpty("Properties");
        if (properties.isEmpty()) return name;

        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (String key : properties.getKeys().stream().sorted().toList()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(key).append('=').append(properties.getString(key, ""));
        }
        return sb.append(']').toString();
    }
}