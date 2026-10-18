package com.dee.client;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 플레이어 헤드의 "minecraft:profile" 컴포넌트 <-> 사람이 입력하는 문자열 변환 도우미.
 * (client/server 공용, net.minecraft.client.* import 금지)
 *
 * 입력으로 받는 형식:
 *  - 플레이어 이름            예) Notch                          -> "Notch"
 *  - 텍스처 값(Base64)        예) eyJ0ZXh0dXJlcyI6...             -> {properties:[{name:"textures",value:"..."}]}
 *  - 텍스처 URL               예) http://textures.minecraft.net/texture/<해시>
 *  - 텍스처 해시(64자리 16진수)
 */
final class ProfileText {
    private ProfileText() {}

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern HASH = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/_-]{20,}={0,2}");

    /** 입력 문자열을 profile 컴포넌트 값으로 바꾼다. 형식이 올바르지 않으면 empty. (빈 문자열은 호출하는 쪽에서 "제거"로 처리) */
    static Optional<NbtElement> parse(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return Optional.empty();

        if (NAME.matcher(s).matches()) return Optional.of(NbtString.of(s));

        String value;
        String url = null;
        if (s.startsWith("http://") || s.startsWith("https://")) {
            url = s;
        } else if (HASH.matcher(s).matches()) {
            url = "http://textures.minecraft.net/texture/" + s.toLowerCase();
        }

        if (url != null) {
            if (url.contains("\"") || url.contains("\\")) return Optional.empty();
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
            value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } else {
            if (!BASE64.matcher(s).matches()) return Optional.empty();
            try {
                String json = new String(Base64.getDecoder().decode(s.replace('-', '+').replace('_', '/')), StandardCharsets.UTF_8);
                if (!json.contains("\"textures\"")) return Optional.empty();
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            value = s;
        }

        NbtCompound property = new NbtCompound();
        property.putString("name", "textures");
        property.putString("value", value);
        NbtList properties = new NbtList();
        properties.add(property);
        NbtCompound profile = new NbtCompound();
        profile.put("properties", properties);
        return Optional.of(profile);
    }

    /** profile 컴포넌트 값을 입력칸에 보여 줄 문자열로 바꾼다. (텍스처가 있으면 그 값, 없으면 이름) */
    static String format(NbtElement tag) {
        if (tag == null) return "";
        if (tag instanceof NbtCompound profile) {
            NbtList properties = profile.getListOrEmpty("properties");
            for (int i = 0; i < properties.size(); i++) {
                if (!(properties.get(i) instanceof NbtCompound property)) continue;
                if (property.getString("name", "").equals("textures")) {
                    String value = property.getString("value", "");
                    if (!value.isEmpty()) return value;
                }
            }
            return profile.getString("name", "");
        }
        NbtCompound wrapper = new NbtCompound();
        wrapper.put("v", tag);
        return wrapper.getString("v", "");
    }
}