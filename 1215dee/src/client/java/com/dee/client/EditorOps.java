package com.dee.client;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 되돌리기 기록이 붙는 편집 동작들: 이동/회전/크기 기록, UI 편집 기록, 생성, 삭제, 복사/붙여넣기/복제, 그룹.
 * 서버로 보내는 명령은 전부 GizmoEditor.send (대기열)를 거치므로 보낸 순서가 지켜진다.
 *
 * 되돌리기로 다시 만든 엔티티는 원래 UUID 를 그대로 쓴다. (그룹/기록이 UUID 로 이어지기 때문)
 * 단, 클라이언트가 볼 수 없는 Tags 는 복원되지 않는다.
 */
public final class EditorOps {
    private EditorOps() {}

    // ---- 클립보드 ----
    private static List<EntitySnapshot> clipboard = List.of();
    /** 복사 당시 그룹이었던 묶음. clipboard 의 인덱스 목록 */
    private static List<List<Integer>> clipboardGroups = List.of();
    private static Vec3d clipboardCenter = Vec3d.ZERO;

    private static void say(MinecraftClient client, String key, Object... args) {
        DisplayEditClient.chat(client, Text.translatable(key, args));
    }

    // ------------------------------------------------------------------
    // 되돌리기 / 다시 실행
    // ------------------------------------------------------------------

    public static void undo(MinecraftClient client) {
        Text label = EditorHistory.undo();
        if (label == null) say(client, "dee.chat.nothing_undo");
        else say(client, "dee.chat.undo", label);
    }

    public static void redo(MinecraftClient client) {
        Text label = EditorHistory.redo();
        if (label == null) say(client, "dee.chat.nothing_redo");
        else say(client, "dee.chat.redo", label);
    }

    // ------------------------------------------------------------------
    // 기록 남기기
    // ------------------------------------------------------------------

    /** 기즈모로 이동/회전/크기를 바꾼 것. before/after 는 같은 순서의 엔티티별 모습. */
    public static void recordTransform(Text label, List<EntitySnapshot> before, List<EntitySnapshot> after) {
        List<UUID> ids = new ArrayList<>();
        for (EntitySnapshot s : before) ids.add(s.id);
        EditorHistory.push(label,
                () -> {
                    for (int i = 0; i < before.size(); i++) {
                        for (String cmd : EntitySnapshot.restore(before.get(i), after.get(i))) GizmoEditor.send(cmd);
                    }
                    GizmoEditor.selectIds(ids);
                    GizmoEditor.afterOp();
                },
                () -> {
                    for (int i = 0; i < after.size(); i++) {
                        for (String cmd : EntitySnapshot.restore(after.get(i), before.get(i))) GizmoEditor.send(cmd);
                    }
                    GizmoEditor.selectIds(ids);
                    GizmoEditor.afterOp();
                });
    }

    /**
     * 편집 UI 에서 [적용] 하기 직전에 부른다. (엔티티는 아직 바뀌기 전)
     * 바뀐 뒤의 모습은 처음 되돌릴 때 그 시점의 엔티티에서 읽어 둔다.
     */
    public static void recordEdit(DisplayEntity entity) {
        UUID id = entity.getUuid();
        EntitySnapshot[] box = {EntitySnapshot.of(entity), null}; // [0] 이전, [1] 이후
        EditorHistory.push(Text.translatable("dee.history.edit"),
                () -> {
                    DisplayEntity now = GizmoEditor.find(MinecraftClient.getInstance(), id);
                    if (box[1] == null && now != null) box[1] = EntitySnapshot.of(now);
                    for (String cmd : EntitySnapshot.restore(box[0], box[1])) GizmoEditor.send(cmd);
                    GizmoEditor.afterOp();
                },
                () -> {
                    if (box[1] == null) return;
                    for (String cmd : EntitySnapshot.restore(box[1], box[0])) GizmoEditor.send(cmd);
                    GizmoEditor.afterOp();
                });
    }

    /** 편집 UI 의 [생성] 으로 만든 엔티티. (UUID 를 미리 정해 summon 했다) */
    public static void recordCreate(UUID id) {
        EntitySnapshot[] box = {null};
        EditorHistory.push(Text.translatable("dee.history.create"),
                () -> {
                    DisplayEntity now = GizmoEditor.find(MinecraftClient.getInstance(), id);
                    if (now != null) box[0] = EntitySnapshot.of(now);
                    GizmoEditor.send("kill " + id);
                    GizmoEditor.deselect(List.of(id));
                    GizmoEditor.afterOp();
                },
                () -> {
                    if (box[0] == null) return;
                    GizmoEditor.send(box[0].summonCommand());
                    GizmoEditor.selectIds(List.of(id));
                    GizmoEditor.afterOp();
                });
    }

    // ------------------------------------------------------------------
    // 삭제 / 잘라내기
    // ------------------------------------------------------------------

    /** Delete 키(cut=false) 또는 Ctrl+X(cut=true). */
    public static void deleteSelection(MinecraftClient client, boolean cut) {
        List<DisplayEntity> selection = GizmoEditor.selection();
        Vec3d center = GizmoEditor.selectionPivot();
        if (selection.isEmpty() || center == null) {
            say(client, "dee.chat.no_selection");
            return;
        }
        if (cut) {
            Captured cap = capture(selection);
            clipboard = cap.snaps();
            clipboardGroups = cap.groups();
            clipboardCenter = center;
        }
        delete(selection, Text.translatable(cut ? "dee.history.cut" : "dee.history.delete"));
        say(client, cut ? "dee.chat.cut" : "dee.chat.deleted", selection.size());
    }

    /** 엔티티들을 지우고(그룹에서도 뺀다) 되돌리기 기록을 남긴다. 편집 UI 의 [삭제] 도 이걸 쓴다. */
    public static void delete(List<DisplayEntity> list, Text label) {
        List<EntitySnapshot> snaps = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        Map<UUID, DisplayGroups.GroupData> groupData = new LinkedHashMap<>();
        for (DisplayEntity e : list) {
            snaps.add(EntitySnapshot.of(e));
            ids.add(e.getUuid());
            DisplayGroups.Group g = DisplayGroups.groupOf(e.getUuid());
            if (g != null) groupData.putIfAbsent(g.id, DisplayGroups.dataOf(g));
        }
        List<DisplayGroups.GroupData> groups = new ArrayList<>(groupData.values());

        Runnable remove = () -> {
            for (EntitySnapshot s : snaps) {
                GizmoEditor.send(s.killCommand());
                DisplayGroups.forget(s.id);
            }
            GizmoEditor.deselect(ids);
            GizmoEditor.afterOp();
        };
        remove.run();
        EditorHistory.push(label,
                () -> {
                    for (EntitySnapshot s : snaps) GizmoEditor.send(s.summonCommand());
                    for (DisplayGroups.GroupData d : groups) DisplayGroups.restore(d);
                    GizmoEditor.selectIds(ids);
                    GizmoEditor.afterOp();
                },
                remove);
    }

    // ------------------------------------------------------------------
    // 복사 / 붙여넣기 / 복제
    // ------------------------------------------------------------------

    private record Captured(List<EntitySnapshot> snaps, List<List<Integer>> groups) {}

    private static Captured capture(List<DisplayEntity> list) {
        List<EntitySnapshot> snaps = new ArrayList<>();
        Map<UUID, Integer> index = new HashMap<>();
        for (DisplayEntity e : list) {
            index.put(e.getUuid(), snaps.size());
            snaps.add(EntitySnapshot.of(e));
        }
        List<List<Integer>> groups = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (DisplayEntity e : list) {
            DisplayGroups.Group g = DisplayGroups.groupOf(e.getUuid());
            if (g == null || !seen.add(g.id)) continue;
            List<Integer> idx = new ArrayList<>();
            for (UUID m : g.members) {
                Integer i = index.get(m);
                if (i != null) idx.add(i);
            }
            if (idx.size() >= 2) groups.add(idx);
        }
        return new Captured(snaps, groups);
    }

    public static void copy(MinecraftClient client) {
        List<DisplayEntity> selection = GizmoEditor.selection();
        Vec3d center = GizmoEditor.selectionPivot();
        if (selection.isEmpty() || center == null) {
            say(client, "dee.chat.no_selection");
            return;
        }
        Captured cap = capture(selection);
        clipboard = cap.snaps();
        clipboardGroups = cap.groups();
        clipboardCenter = center;
        say(client, "dee.chat.copied", selection.size());
    }

    /** 클립보드의 중심이 십자선이 가리키는 블록 위치(없으면 앞쪽 4칸)에 오도록 붙여넣는다. */
    public static void paste(MinecraftClient client) {
        if (clipboard.isEmpty()) {
            say(client, "dee.chat.clipboard_empty");
            return;
        }
        place(client, clipboard, clipboardGroups, clipboardCenter, cursorTarget(client),
                Text.translatable("dee.history.paste"));
    }

    /** 선택한 것을 복사해 같은 자리에서 조금(0.5칸) 옆에 붙인다. 클립보드는 건드리지 않는다. */
    public static void duplicate(MinecraftClient client) {
        List<DisplayEntity> selection = GizmoEditor.selection();
        Vec3d center = GizmoEditor.selectionPivot();
        if (selection.isEmpty() || center == null) {
            say(client, "dee.chat.no_selection");
            return;
        }
        Captured cap = capture(selection);
        place(client, cap.snaps(), cap.groups(), center, center.add(0.5, 0.0, 0.5),
                Text.translatable("dee.history.duplicate"));
    }

    private static Vec3d cursorTarget(MinecraftClient client) {
        HitResult hit = client.crosshairTarget;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) return hit.getPos();
        PlayerEntity player = client.player;
        return player.getEyePos().add(player.getRotationVector().multiply(4.0));
    }

    private static void place(MinecraftClient client, List<EntitySnapshot> source, List<List<Integer>> groups,
                              Vec3d center, Vec3d target, Text label) {
        Vec3d delta = target.subtract(center);
        List<EntitySnapshot> created = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (EntitySnapshot s : source) {
            EntitySnapshot c = s.copyAs(UUID.randomUUID(), s.pos.add(delta));
            created.add(c);
            ids.add(c.id);
        }
        // 복사한 것이 그룹이었다면 붙여넣은 것도 (새) 그룹으로 묶는다
        List<DisplayGroups.GroupData> groupData = new ArrayList<>();
        for (List<Integer> idx : groups) {
            List<UUID> members = new ArrayList<>();
            for (int i : idx) members.add(created.get(i).id);
            DisplayGroups.Group g = DisplayGroups.create(members, DisplayGroups.nextName());
            if (g != null) groupData.add(DisplayGroups.dataOf(g));
        }
        for (EntitySnapshot c : created) GizmoEditor.send(c.summonCommand());
        GizmoEditor.selectIds(ids);
        GizmoEditor.afterOp();

        EditorHistory.push(label,
                () -> {
                    for (EntitySnapshot c : created) GizmoEditor.send(c.killCommand());
                    for (DisplayGroups.GroupData d : groupData) DisplayGroups.disband(DisplayGroups.byId(d.id()));
                    GizmoEditor.deselect(ids);
                    GizmoEditor.afterOp();
                },
                () -> {
                    for (EntitySnapshot c : created) GizmoEditor.send(c.summonCommand());
                    for (DisplayGroups.GroupData d : groupData) DisplayGroups.restore(d);
                    GizmoEditor.selectIds(ids);
                    GizmoEditor.afterOp();
                });
        say(client, "dee.chat.pasted", created.size());
    }

    // ------------------------------------------------------------------
    // 그룹
    // ------------------------------------------------------------------

    public static void group(MinecraftClient client, List<UUID> ids) {
        if (ids.size() < 2 || DisplayGroups.exactGroup(ids) != null) return;
        Map<UUID, DisplayGroups.GroupData> olds = new LinkedHashMap<>();
        for (UUID id : ids) {
            DisplayGroups.Group g = DisplayGroups.groupOf(id);
            if (g != null) olds.putIfAbsent(g.id, DisplayGroups.dataOf(g));
        }
        DisplayGroups.Group created = DisplayGroups.create(ids, DisplayGroups.nextName());
        if (created == null) return;
        DisplayGroups.GroupData after = DisplayGroups.dataOf(created);
        List<DisplayGroups.GroupData> before = new ArrayList<>(olds.values());
        EditorHistory.push(Text.translatable("dee.history.group"),
                () -> {
                    DisplayGroups.disband(DisplayGroups.byId(after.id()));
                    for (DisplayGroups.GroupData d : before) DisplayGroups.restore(d);
                },
                () -> DisplayGroups.restore(after));
    }

    public static void ungroup(MinecraftClient client, DisplayGroups.Group group) {
        if (group == null) return;
        DisplayGroups.GroupData data = DisplayGroups.dataOf(group);
        DisplayGroups.disband(group);
        EditorHistory.push(Text.translatable("dee.history.ungroup"),
                () -> DisplayGroups.restore(data),
                () -> DisplayGroups.disband(DisplayGroups.byId(data.id())));
    }
}