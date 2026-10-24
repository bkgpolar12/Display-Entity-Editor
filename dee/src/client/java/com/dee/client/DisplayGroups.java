package com.dee.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 디스플레이 그룹(묶음) 저장소. 클라이언트 전용이다.
 *
 * 스코어보드 태그(Tags)는 클라이언트 엔티티로 동기화되지 않아서 멀티플레이에서는 읽을 수 없다.
 * 그래서 그룹은 "엔티티 UUID 목록"으로 config/dee_groups.txt 에 저장한다.
 * (UUID 는 월드와 상관없이 유일하므로 월드/서버 구분 없이 한 파일에 모아도 된다.)
 * 같은 엔티티는 한 그룹에만 속하고, 멤버가 2개 미만이 된 그룹은 자동으로 해제된다.
 *
 * 파일 형식: 한 줄에 그룹 하나. [그룹 UUID] TAB [이름] TAB [멤버 UUID,멤버 UUID,...]
 */
final class DisplayGroups {
    private DisplayGroups() {}

    static final class Group {
        final UUID id;
        String name;
        /** 멤버 UUID. 넣은 순서가 파트 목록의 순서가 된다. */
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();

        Group(UUID id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final Map<UUID, Group> BY_ID = new LinkedHashMap<>();
    private static final Map<UUID, Group> BY_MEMBER = new HashMap<>();
    private static boolean loaded;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("dee_groups.txt");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = file();
        if (!Files.exists(path)) return;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 3) continue;
                try {
                    Group g = new Group(UUID.fromString(parts[0].trim()), parts[1]);
                    for (String m : parts[2].split(",")) {
                        if (!m.isBlank()) g.members.add(UUID.fromString(m.trim()));
                    }
                    if (g.members.size() < 2) continue;
                    // 같은 엔티티가 두 그룹에 들어 있으면 먼저 읽은 그룹을 우선한다
                    g.members.removeIf(BY_MEMBER::containsKey);
                    if (g.members.size() < 2) continue;
                    BY_ID.put(g.id, g);
                    for (UUID m : g.members) BY_MEMBER.put(m, g);
                } catch (IllegalArgumentException ignored) {
                    // 깨진 줄은 건너뛴다
                }
            }
        } catch (IOException ignored) {
            // 읽지 못하면 빈 상태로 시작한다
        }
    }

    private static void save() {
        List<String> lines = new ArrayList<>();
        for (Group g : BY_ID.values()) {
            StringBuilder sb = new StringBuilder();
            sb.append(g.id).append('\t').append(g.name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')).append('\t');
            boolean first = true;
            for (UUID m : g.members) {
                if (!first) sb.append(',');
                sb.append(m);
                first = false;
            }
            lines.add(sb.toString());
        }
        try {
            Files.write(file(), lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 저장에 실패해도 이번 접속 동안은 그대로 동작한다
        }
    }

    /** 그룹의 내용만 복사해 둔 것. 되돌리기에서 그룹을 그대로 다시 만들 때 쓴다. */
    record GroupData(UUID id, String name, List<UUID> members) {}

    static GroupData dataOf(Group g) {
        return new GroupData(g.id, g.name, new ArrayList<>(g.members));
    }

    static Group byId(UUID id) {
        ensureLoaded();
        return BY_ID.get(id);
    }

    /** 같은 id / 이름 / 멤버로 그룹을 다시 만든다. 멤버가 다른 그룹에 있으면 거기서 뺀다. */
    static void restore(GroupData d) {
        ensureLoaded();
        Group existing = BY_ID.remove(d.id());
        if (existing != null) {
            for (UUID m : existing.members) BY_MEMBER.remove(m);
        }
        for (UUID m : d.members()) removeFromGroup(m);
        if (d.members().size() < 2) {
            save();
            return;
        }
        Group g = new Group(d.id(), d.name());
        g.members.addAll(d.members());
        BY_ID.put(g.id, g);
        for (UUID m : g.members) BY_MEMBER.put(m, g);
        save();
    }

    /** 이 엔티티가 속한 그룹. 없으면 null. */
    static Group groupOf(UUID member) {
        ensureLoaded();
        return BY_MEMBER.get(member);
    }

    /**
     * ids 가 전부 같은 한 그룹의 멤버이고 2개 이상이면 그 그룹을 돌려준다. (선택이 그룹 하나와 같은 상태)
     * 선택은 항상 그룹 단위로 만들어지므로, 일부 멤버가 사라진(삭제/멀리 있음) 경우에도 같은 그룹으로 본다.
     */
    static Group exactGroup(Collection<UUID> ids) {
        ensureLoaded();
        if (ids.size() < 2) return null;
        Group g = null;
        for (UUID id : ids) {
            Group of = BY_MEMBER.get(id);
            if (of == null) return null;
            if (g == null) g = of;
            else if (g != of) return null;
        }
        return g;
    }

    /** 새 그룹을 만든다. 이미 다른 그룹에 있던 멤버는 거기서 빠진다. */
    static Group create(Collection<UUID> ids, String name) {
        ensureLoaded();
        LinkedHashSet<UUID> unique = new LinkedHashSet<>(ids);
        if (unique.size() < 2) return null;
        for (UUID id : unique) removeFromGroup(id);
        Group g = new Group(UUID.randomUUID(), name);
        g.members.addAll(unique);
        BY_ID.put(g.id, g);
        for (UUID id : unique) BY_MEMBER.put(id, g);
        save();
        return g;
    }

    /** 그룹을 해제한다. (엔티티는 그대로 두고 묶음만 없앤다) */
    static void disband(Group g) {
        ensureLoaded();
        if (g == null) return;
        for (UUID id : g.members) BY_MEMBER.remove(id);
        BY_ID.remove(g.id);
        save();
    }

    /** 엔티티를 삭제했을 때 그룹에서도 뺀다. */
    static void forget(UUID member) {
        ensureLoaded();
        if (BY_MEMBER.containsKey(member)) {
            removeFromGroup(member);
            save();
        }
    }

    private static void removeFromGroup(UUID id) {
        Group g = BY_MEMBER.remove(id);
        if (g == null) return;
        g.members.remove(id);
        if (g.members.size() < 2) {
            for (UUID m : g.members) BY_MEMBER.remove(m);
            BY_ID.remove(g.id);
        }
    }

    /** "그룹 1", "그룹 2" ... 중 아직 안 쓰는 이름. (현재 언어로 만든다) */
    static String nextName() {
        ensureLoaded();
        int n = BY_ID.size() + 1;
        while (true) {
            String name = Component.translatable("dee.group.default_name", n).getString();
            boolean used = false;
            for (Group g : BY_ID.values()) {
                if (g.name.equals(name)) {
                    used = true;
                    break;
                }
            }
            if (!used) return name;
            n++;
        }
    }
}