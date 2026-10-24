package com.dee.client;

import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtElement;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.math.MathHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 인게임 기즈모 에디터 모드. (블렌더의 이동/회전/크기 기즈모와 같은 방식)
 *
 * - 마우스는 잡힌 채로 그대로이고, 십자선(화면 정중앙)이 핸들에 닿으면 강조된다. 좌클릭을 누른 채 시선을 움직이면 드래그.
 * - 기즈모는 월드 렌더가 아니라 HUD 에 3D -> 2D 투영으로 그린다. (월드 렌더 API 는 버전마다 크게 바뀌어서 HUD API 만 쓴다)
 * - 드래그 중에는 클라이언트 엔티티에만 미리보기로 적용하고, 마우스를 놓을 때 명령 1~2개만 서버로 보낸다.
 *   (드래그 도중 명령을 계속 보내면 채팅 스팸 제한에 걸려 강퇴당한다)
 *
 * 조작: 1/2/3 = 이동/회전/크기, 좌클릭 드래그 = 조작, 드래그 중 우클릭 = 취소,
 *       엔티티를 바라보고 좌클릭 = 선택, 다중 선택 키(기본 Ctrl, 조작 설정에서 변경) + 좌클릭 = 선택에 추가/제거 (여러 개 선택),
 *       우클릭 / G(에디터 열기 키) = 편집 UI + 파트 목록 (G 는 바라보는 게 없으면 새로 만들기), ESC = 종료
 *
 * 여러 개를 선택하면(또는 그룹을 선택하면) 선택된 것들의 중심에 기즈모가 하나 놓이고, 하나인 것처럼 이동/회전/크기가 적용된다.
 * 그룹은 DisplayGroups 에 저장되고, 그룹의 엔티티를 하나만 클릭해도 그룹 전체가 선택된다.
 */
public final class GizmoEditor {
    private GizmoEditor() {}

    public enum Mode { MOVE, ROTATE, SCALE }

    /** X, Y, Z 축 핸들과 가운데 핸들(이동: 화면 평면 이동 / 크기: 균등 크기) */
    private enum Part { X, Y, Z, CENTER }

    /** 화살표 길이 = 카메라까지 거리 × 이 값. (화면에서 항상 비슷한 크기로 보이게) */
    private static final double ARM = 0.2;
    private static final double NEAR = 0.05;
    private static final float DEG = (float) (Math.PI / 180.0);

    private static final int[] AXIS_COLOR = {0xFFE5484D, 0xFF46A758, 0xFF3E63DD};
    private static final int COLOR_HOT = 0xFFFFE066;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_GROUP = 0xFF55FFFF;

    private static boolean active;
    private static Mode mode = Mode.MOVE;

    private static UUID selectedId;
    private static DisplayEntity selected;
    private static Pose pose;
    private static Drag drag;

    /** 선택된 엔티티 전체의 UUID (선택한 순서). selectedId 는 그중 "대표" 하나. */
    private static final Set<UUID> selIds = new LinkedHashSet<>();
    /** selIds 중 지금 클라이언트에 있는 엔티티. 2개 이상이면 다중 선택 모드. */
    private static final List<DisplayEntity> members = new ArrayList<>();
    /** 다중 드래그 시작 시점의 각 멤버 포즈 / 마지막으로 미리보기에 반영한 포즈 (members 와 같은 순서) */
    private static Pose[] memberStart;
    private static Pose[] memberPreviewed;
    /** 드래그 시작 시점의 엔티티 모습(되돌리기 기록용). 단일이면 1개, 다중이면 members 와 같은 순서 */
    private static List<EntitySnapshot> dragBefore;
    /** 서버로 보낼 명령 대기열. 여러 엔티티를 한꺼번에 놓을 때 명령을 틱마다 나눠 보낸다. */
    private static final Deque<String> outbox = new ArrayDeque<>();
    private static final int OUTBOX_PER_TICK = 6;
    /** true 면 드래그 중 회전/크기(transformation)도 클라이언트 엔티티에 실시간으로 반영한다. 문제가 생기면 false 로 */
    private static final boolean LIVE_TRANSFORM_PREVIEW = true;
    /** 드래그 중 클라이언트 엔티티에 마지막으로 반영한 포즈. null 이면 드래그 중이 아니다. */
    private static Pose previewed;

    private static Part hoverPart;
    private static DisplayEntity hoverEntity;
    private static int syncTick;
    /** 마우스를 놓은 직후, 서버 반영이 오기 전에 옛 값을 다시 읽지 않도록 쉬는 틱 수 */
    private static int holdRefresh;
    /** 지난 틱 이후 render() 가 실행됐는지. (HUD 가 숨겨지면 render 가 호출되지 않는다) */
    private static boolean renderedSinceTick;
    /** H 로 켜고 끄는 상세 도움말 표시 여부 */
    private static boolean showHelp;
    private static int missingTicks;

    // ------------------------------------------------------------------
    // 진입 / 종료
    // ------------------------------------------------------------------

    public static boolean isActive() {
        return active;
    }

    public static DisplayEntity selected() {
        return selected;
    }

    /** 이미 있는 디스플레이로 에디터 모드를 시작한다. (그룹에 속해 있으면 그룹 전체가 선택된다) */
    public static void begin(DisplayEntity target) {
        active = true;
        drag = null;
        missingTicks = 0;
        selIds.clear();
        members.clear();
        selectOnly(target);
    }

    /** 방금 summon 한 엔티티(UUID 만 알고 있음)로 시작한다. 클라이언트에 나타날 때까지 기다린다. */
    public static void beginPending(UUID id) {
        active = true;
        drag = null;
        selected = null;
        pose = null;
        members.clear();
        selIds.clear();
        selIds.add(id);
        selectedId = id;
        missingTicks = 0;
    }

    public static void exit() {
        if (drag != null && pose != null) {
            // 드래그 도중에 끝나면 미리보기를 원래대로 되돌린다
            if (isMulti() && memberStart != null) {
                restoreMembers();
            } else if (selected != null) {
                pose = drag.start;
                applyPreview(pose);
            }
        }
        previewed = null;
        memberStart = null;
        memberPreviewed = null;
        dragBefore = null;
        EditorShortcuts.releaseMovement(); // 다중 선택 키로 막아 둔 이동 키를 되돌린다
        showHelp = false;
        active = false;
        drag = null;
        selected = null;
        selectedId = null;
        selIds.clear();
        members.clear();
        pose = null;
        hoverPart = null;
        hoverEntity = null;
    }

    // ---- 선택 ----

    private static boolean isMulti() {
        return members.size() >= 2;
    }

    /** 이 엔티티의 선택 단위: 그룹에 속해 있으면 그룹 전체, 아니면 자기 자신. */
    private static Set<UUID> unitOf(UUID id) {
        Set<UUID> unit = new LinkedHashSet<>();
        DisplayGroups.Group g = DisplayGroups.groupOf(id);
        if (g != null) unit.addAll(g.members);
        unit.add(id);
        return unit;
    }

    /** 이 엔티티(또는 그 그룹)만 선택한다. */
    private static void selectOnly(DisplayEntity target) {
        UUID id = target.getUuid();
        Set<UUID> next = unitOf(id);
        if (!members.isEmpty() && next.equals(selIds)) {
            selectedId = id;          // 이미 같은 선택이면 대표만 바꾼다
            reselectPrimary();
            return;
        }
        selIds.clear();
        selIds.addAll(next);
        selectedId = id;
        members.clear();
        resolveNow();
    }

    /** Ctrl + 클릭: 이 엔티티(그룹이면 그룹 전체)를 선택에 넣거나 뺀다. */
    private static void toggle(DisplayEntity target) {
        UUID id = target.getUuid();
        Set<UUID> unit = unitOf(id);
        if (selIds.contains(id)) {
            selIds.removeAll(unit);
            if (selectedId == null || !selIds.contains(selectedId)) {
                selectedId = selIds.isEmpty() ? null : selIds.iterator().next();
            }
        } else {
            selIds.addAll(unit);
            selectedId = id;
        }
        members.clear();
        resolveNow();
    }

    private static void reselectPrimary() {
        for (DisplayEntity m : members) {
            if (m.getUuid().equals(selectedId)) {
                selected = m;
                return;
            }
        }
    }

    private static void resolveNow() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.world != null && c.player != null) resolveSelection(c, c.player);
    }

    /**
     * 편집 UI 를 연다. (G / 우클릭 공통)
     * 이미 선택된 것을 가리키면 선택은 그대로 두고(파트 목록에 선택 전체가 나온다), 아니면 이것(또는 그 그룹)만 선택한다.
     */
    public static void openFor(MinecraftClient client, DisplayEntity target) {
        if (selIds.contains(target.getUuid())) {
            selectedId = target.getUuid();
            reselectPrimary();
        } else {
            selectOnly(target);
        }
        DisplayEditClient.openEditorScreen(client, target);
    }

    // ---- 편집 화면(파트 목록)이 쓰는 것들 ----

    /** 지금 선택에 포함된(클라이언트에 있는) 엔티티의 UUID. 파트 목록용. */
    public static List<UUID> partIds() {
        List<UUID> out = new ArrayList<>(members.size());
        for (DisplayEntity m : members) out.add(m.getUuid());
        return out;
    }

    /** 지금 선택이 정확히 하나의 그룹이면 그 그룹. */
    public static DisplayGroups.Group selectionGroup() {
        return DisplayGroups.exactGroup(selIds);
    }

    public static void groupSelection() {
        EditorOps.group(MinecraftClient.getInstance(), new ArrayList<>(selIds));
    }

    public static void ungroupSelection() {
        EditorOps.ungroup(MinecraftClient.getInstance(), selectionGroup());
    }

    // ---- 편집 동작(EditorOps)이 쓰는 것들 ----

    /** 서버로 보낼 명령을 대기열에 넣는다. (순서가 지켜진다) */
    public static void send(String command) {
        outbox.add(command);
    }

    /** 편집 동작 직후: 서버가 바꾼 값이 클라이언트에 반영될 때까지 기즈모가 값을 다시 읽지 않게 잠시 둔다. */
    public static void afterOp() {
        holdRefresh = Math.max(holdRefresh, 10 + outbox.size() / OUTBOX_PER_TICK);
        syncTick = 0;
    }

    /** 지금 선택에 포함된(클라이언트에 있는) 엔티티들. */
    public static List<DisplayEntity> selection() {
        return new ArrayList<>(members);
    }

    /** 선택의 기즈모 중심. 선택이 없으면 null. */
    public static Vec3d selectionPivot() {
        return pose == null ? null : pivot(pose);
    }

    /** 선택을 이 UUID 들로 바꾼다. 아직 클라이언트에 나타나지 않은 것은 나타날 때까지 기다린다. */
    public static void selectIds(Collection<UUID> ids) {
        drag = null;
        previewed = null;
        memberStart = null;
        memberPreviewed = null;
        dragBefore = null;
        selIds.clear();
        selIds.addAll(ids);
        selectedId = selIds.isEmpty() ? null : selIds.iterator().next();
        members.clear();
        missingTicks = 0;
        resolveNow();
    }

    /** 선택에서 이 UUID 들을 뺀다. */
    public static void deselect(Collection<UUID> ids) {
        selIds.removeAll(ids);
        if (selectedId == null || !selIds.contains(selectedId)) {
            selectedId = selIds.isEmpty() ? null : selIds.iterator().next();
        }
        members.clear();
        resolveNow();
    }

    /** Ctrl+A 로 고르는 거리(칸). 화면에 보여도 이보다 멀면 제외한다. */
    private static final double SELECT_ALL_RANGE = 32.0;

    /**
     * Ctrl+A: 카메라 화면 안에 보이는 가까운(32칸) 디스플레이를 선택한다. 그룹이면 그룹 전체가 들어간다.
     * "보인다"는 디스플레이의 위치가 화면 영역 안에 있다는 뜻이다. (블록 뒤에 가려졌는지는 보지 않는다)
     */
    public static void selectAllNearby(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        View v = View.of(client, client.player, 1.0f);
        Vec3d eye = v.eye;
        List<DisplayEntity> all = client.world.getEntitiesByClass(
                DisplayEntity.class, client.player.getBoundingBox().expand(SELECT_ALL_RANGE), d -> {
                    if (d.getPos().squaredDistanceTo(eye) > SELECT_ALL_RANGE * SELECT_ALL_RANGE) return false;
                    double[] sc = v.project(d.getPos());
                    return sc != null && sc[0] >= 0 && sc[0] <= v.w && sc[1] >= 0 && sc[1] <= v.h;
                });
        if (all.isEmpty()) return;
        Set<UUID> next = new LinkedHashSet<>();
        for (DisplayEntity d : all) next.addAll(unitOf(d.getUuid()));
        selectIds(next);
    }

    /** 가까운 곳에서 UUID 로 디스플레이를 찾는다. 없으면 null. */
    public static DisplayEntity find(MinecraftClient client, UUID id) {
        if (client.world == null || client.player == null) return null;
        List<DisplayEntity> found = client.world.getEntitiesByClass(
                DisplayEntity.class, client.player.getBoundingBox().expand(64.0), d -> d.getUuid().equals(id));
        return found.isEmpty() ? null : found.get(0);
    }

    /** 대기 중인 명령을 틱마다 조금씩 보낸다. (매 클라이언트 틱에 호출) */
    public static void flush(MinecraftClient client) {
        if (outbox.isEmpty()) return;
        var handler = client.getNetworkHandler();
        if (handler == null) {
            outbox.clear();
            return;
        }
        for (int i = 0; i < OUTBOX_PER_TICK && !outbox.isEmpty(); i++) {
            handler.sendChatCommand(outbox.poll());
        }
    }

    /** 엔티티의 현재 상태를 다시 읽는다. (UI 에서 적용한 값, 서버가 보정한 값을 반영) */
    private static void refresh() {
        rebuildFrame();
    }

    /** members 가 바뀌었거나 값을 새로 읽어야 할 때: 대표 엔티티와 기즈모 기준 포즈를 다시 만든다. */
    private static void rebuildFrame() {
        if (members.isEmpty()) {
            selected = null;
            pose = null;
            return;
        }
        DisplayEntity primary = members.get(0);
        for (DisplayEntity m : members) {
            if (m.getUuid().equals(selectedId)) {
                primary = m;
                break;
            }
        }
        selected = primary;
        selectedId = primary.getUuid();
        pose = members.size() == 1 ? readPose(EntityNbt.save(primary)) : framePose();
    }

    /** 다중 선택의 기즈모 기준: 멤버들의 피벗(블록은 중심) 평균에 놓고, 회전/크기는 항등. */
    private static Pose framePose() {
        Vec3d sum = Vec3d.ZERO;
        for (DisplayEntity m : members) {
            sum = sum.add(pivotOf(m instanceof DisplayEntity.BlockDisplayEntity, readPose(EntityNbt.save(m))));
        }
        Pose p = new Pose();
        p.pos = sum.multiply(1.0 / members.size());
        return p;
    }

    // ------------------------------------------------------------------
    // 틱: 입력 처리 (DisplayEditClient 의 START_CLIENT_TICK 에서 호출)
    // ------------------------------------------------------------------

    public static void tick(MinecraftClient client, PlayerEntity player, DisplayEntity hovered) {
        if (!active) return;

        // ESC: 바닐라가 일시정지 메뉴를 먼저 열어 버리므로, 그걸 감지해서 에디터 모드를 끝낸다
        if (client.currentScreen instanceof GameMenuScreen) {
            client.setScreen(null);
            exit();
            DisplayEditClient.chat(client, Text.translatable("dee.chat.gizmo_exit"));
            return;
        }
        if (!player.isCreative()) {
            exit();
            return;
        }
        // UI / 인벤토리 등이 열려 있으면 입력은 그쪽 것. 닫히면 자동으로 에디터 모드로 돌아온다
        if (client.currentScreen != null) {
            hoverEntity = null;
            EditorShortcuts.releaseMovement();
            EditorShortcuts.sync(client);
            return;
        }

        resolveSelection(client, player);
        if (!active) return;

        // H: 상세 도움말 토글
        if (EditorShortcuts.helpPressed(client)) showHelp = !showHelp;

        // 에디터 모드에서는 클릭이 바닐라 동작(공격/설치/아이템 사용)으로 가지 않게 전부 소비한다.
        // (블록 상호작용 자체도 DisplayEditClient 의 Fabric 콜백이 따로 막는다)
        boolean attackPressed = false;
        boolean usePressed = false;
        while (client.options.attackKey.wasPressed()) attackPressed = true;
        while (client.options.useKey.wasPressed()) usePressed = true;
        while (client.options.pickItemKey.wasPressed()) { /* 가운데 클릭 무시 */ }
        if (DisplayEditClient.MULTI_SELECT != null) {
            while (DisplayEditClient.MULTI_SELECT.wasPressed()) { /* 눌림 횟수만 비운다. 상태는 isDown 으로 본다 */ }
        }

        KeyBinding[] slots = client.options.hotbarKeys;
        for (int i = 0; i < slots.length; i++) {
            while (slots[i].wasPressed()) {
                if (i < Mode.values().length && drag == null) mode = Mode.values()[i];
            }
        }
        boolean attackHeld = client.options.attackKey.isPressed();

        hoverEntity = hovered;

        // 편집 단축키 (Ctrl+Z/Y/X/C/V/D/A/G, Delete). 드래그 중에는 처리하지 않고 눌림 상태만 맞춘다
        if (drag == null) EditorShortcuts.poll(client);
        else EditorShortcuts.sync(client);

        // 다중 선택 키(기본 왼쪽 Ctrl): 누른 채 클릭하면 선택에 추가/제거. 물리 Ctrl 도 항상 된다
        // (키 설정이 같은 키를 쓰는 다른 동작과 겹쳐 isDown 이 안 올 수 있어서)
        boolean ctrl = EditorShortcuts.ctrlDown(client);
        EditorShortcuts.suppressMovement(client, ctrl); // 다중 선택 키를 누르는 동안 WASD/점프/웅크리기/달리기 무시
        if (pose == null) {
            // 선택된 엔티티가 없거나 아직 클라이언트에 나타나지 않음. 바라보는 디스플레이는 고르거나 열 수 있다
            if (hovered != null && attackPressed) {
                if (ctrl) toggle(hovered);
                else selectOnly(hovered);
            } else if (hovered != null && usePressed) {
                openFor(client, hovered);
            }
            return;
        }

        View v = View.of(client, player, 1.0f);

        if (drag != null) {
            if (usePressed) {
                cancelDrag();                 // 드래그 중 우클릭 = 취소
            } else if (!attackHeld) {
                finishDrag(client);           // 버튼을 놓으면 확정
            } else if (!renderedSinceTick) {
                updateDrag(client, v);        // F1 로 HUD 를 숨기는 등 render 가 안 불린 경우 틱에서 갱신
            }
            renderedSinceTick = false;
            return;
        }

        if (holdRefresh > 0) {
            holdRefresh--;
        } else if (++syncTick >= 4) {
            syncTick = 0;
            refresh();
        }

        hoverPart = pickPart(v);
        if (attackPressed && attackHeld) {
            if (ctrl && hovered != null) {
                toggle(hovered);                  // Ctrl + 클릭: 선택에 추가/제거
            } else if (hoverPart != null) {
                beginDrag(client, v);
            } else if (hovered != null) {
                selectOnly(hovered);              // 같은 선택이면 아무 일도 없다
            }
        } else if (usePressed) {
            // 우클릭 -> 편집 UI. (G 키와 같은 용도)
            //  - 디스플레이를 바라보고 있으면 그것(이미 선택된 것이면 선택 전체)의 편집 UI + 파트 목록
            //  - 선택한 디스플레이의 핸들에 십자선이 걸려 있어도(엔티티를 바라보면 보통 핸들과 겹친다) 대표 디스플레이를 연다
            if (hovered != null) {
                openFor(client, hovered);
            } else if (hoverPart != null && selected != null) {
                DisplayEditClient.openEditorScreen(client, selected);
            }
        }
    }

    /**
     * 선택된 엔티티들을 UUID 로 찾아 members 를 맞춘다.
     * 없어졌거나 아직 안 나타난 것은 잠시(3초) 다시 찾아보고, 끝내 못 찾으면(삭제/kill 등) 선택에서만 뺀다.
     * 에디터 모드는 유지한다. 선택이 없는 동안에는 다른 디스플레이를 클릭해서 고르거나 G 로 새로 만들 수 있다.
     */
    private static void resolveSelection(MinecraftClient client, PlayerEntity player) {
        if (selIds.isEmpty()) {
            if (!members.isEmpty() || selected != null || pose != null) clearResolved();
            return;
        }
        boolean valid = members.size() == selIds.size();
        if (valid) {
            for (DisplayEntity m : members) {
                if (m.isRemoved()) {
                    valid = false;
                    break;
                }
            }
        }
        if (valid) {
            missingTicks = 0;
            return;
        }

        final Set<UUID> ids = new HashSet<>(selIds);
        List<DisplayEntity> found = client.world.getEntitiesByClass(
                DisplayEntity.class, player.getBoundingBox().expand(64.0), d -> ids.contains(d.getUuid()));
        List<DisplayEntity> ordered = new ArrayList<>();
        for (UUID id : selIds) {
            for (DisplayEntity d : found) {
                if (d.getUuid().equals(id)) {
                    ordered.add(d);
                    break;
                }
            }
        }

        boolean changed = ordered.size() != members.size();
        if (!changed) {
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i) != members.get(i)) {
                    changed = true;
                    break;
                }
            }
        }
        if (ordered.size() == selIds.size()) {
            missingTicks = 0;
        } else if (++missingTicks > 60) {
            Set<UUID> have = new HashSet<>();
            for (DisplayEntity d : ordered) have.add(d.getUuid());
            selIds.retainAll(have);
            if (selectedId == null || !selIds.contains(selectedId)) {
                selectedId = selIds.isEmpty() ? null : selIds.iterator().next();
            }
            missingTicks = 0;
            changed = true;
        }

        if (changed || selected == null) {
            drag = null;
            previewed = null;
            memberStart = null;
            memberPreviewed = null;
            dragBefore = null;
            hoverPart = null;
            members.clear();
            members.addAll(ordered);
            rebuildFrame();
        }
    }

    private static void clearResolved() {
        members.clear();
        selected = null;
        pose = null;
        drag = null;
        previewed = null;
        memberStart = null;
        memberPreviewed = null;
        dragBefore = null;
        hoverPart = null;
    }

    // ------------------------------------------------------------------
    // 포즈 (위치 / 방향 / transformation)
    // ------------------------------------------------------------------

    private static final class Pose {
        Vec3d pos = Vec3d.ZERO;
        float yaw;
        float pitch;
        final Vector3f translation = new Vector3f();
        final Vector3f scale = new Vector3f(1f, 1f, 1f);
        final Quaternionf left = new Quaternionf();
        final Quaternionf right = new Quaternionf();

        Pose copy() {
            Pose c = new Pose();
            c.pos = pos;
            c.yaw = yaw;
            c.pitch = pitch;
            c.translation.set(translation);
            c.scale.set(scale);
            c.left.set(left);
            c.right.set(right);
            return c;
        }

        /** 엔티티 자체의 방향. (바닐라 fixed 빌보드와 같은 식. 보통 yaw/pitch 가 0 이라 항등) */
        Quaternionf orientation() {
            return new Quaternionf().rotationYXZ(-yaw * DEG, pitch * DEG, 0f);
        }

        boolean sameAs(Pose o) {
            return pos.equals(o.pos) && translation.equals(o.translation) && scale.equals(o.scale)
                    && left.equals(o.left) && right.equals(o.right);
        }
    }

    private static double num(NbtList list, int i, double fallback) {
        return i < list.size() && list.get(i) instanceof AbstractNbtNumber n ? n.doubleValue() : fallback;
    }

    private static Pose readPose(NbtCompound nbt) {
        Pose p = new Pose();
        NbtList pos = nbt.getListOrEmpty("Pos");
        p.pos = new Vec3d(num(pos, 0, 0), num(pos, 1, 0), num(pos, 2, 0));
        NbtList rot = nbt.getListOrEmpty("Rotation");
        p.yaw = (float) num(rot, 0, 0);
        p.pitch = (float) num(rot, 1, 0);

        NbtCompound t = nbt.getCompoundOrEmpty("transformation");
        NbtList tr = t.getListOrEmpty("translation");
        p.translation.set((float) num(tr, 0, 0), (float) num(tr, 1, 0), (float) num(tr, 2, 0));
        NbtList sc = t.getListOrEmpty("scale");
        p.scale.set((float) num(sc, 0, 1), (float) num(sc, 1, 1), (float) num(sc, 2, 1));
        readQuat(t.getListOrEmpty("left_rotation"), p.left);
        readQuat(t.getListOrEmpty("right_rotation"), p.right);
        return p;
    }

    private static void readQuat(NbtList list, Quaternionf out) {
        if (list.size() == 4) {
            out.set((float) num(list, 0, 0), (float) num(list, 1, 0), (float) num(list, 2, 0), (float) num(list, 3, 1));
            if (out.lengthSquared() > 1e-8f) out.normalize();
            else out.identity();
        } else {
            out.identity(); // {axis, angle} 같은 입력용 형식은 저장된 NBT 에 나오지 않는다
        }
    }

    private static NbtList floats(float... values) {
        NbtList list = new NbtList();
        for (float v : values) list.add(NbtFloat.of(v));
        return list;
    }

    private static NbtCompound transformationTag(Pose p) {
        NbtCompound t = new NbtCompound();
        t.put("left_rotation", floats(p.left.x, p.left.y, p.left.z, p.left.w));
        t.put("right_rotation", floats(p.right.x, p.right.y, p.right.z, p.right.w));
        t.put("translation", floats(p.translation.x, p.translation.y, p.translation.z));
        t.put("scale", floats(p.scale.x, p.scale.y, p.scale.z));
        return t;
    }

    private static boolean isBlock() {
        return !isMulti() && selected instanceof DisplayEntity.BlockDisplayEntity;
    }

    /**
     * 기즈모가 놓이는 곳(월드 좌표).
     * block_display 는 블록의 중심(엔티티 위치가 모서리라서), 그 외에는 엔티티 위치(+translation).
     */
    private static Vec3d pivot(Pose p) {
        return pivotOf(isBlock(), p);
    }

    private static Vec3d pivotOf(boolean block, Pose p) {
        Vector3f local = new Vector3f(p.translation);
        if (block) {
            local.add(p.left.transform(new Vector3f(p.scale).mul(0.5f), new Vector3f()));
        }
        Vector3f w = p.orientation().transform(local, new Vector3f());
        return p.pos.add(w.x, w.y, w.z);
    }

    /** 회전/크기 뒤에도 block_display 의 중심이 제자리에 있도록 translation 을 보정한다. */
    private static void keepPivot(Pose next, Pose start) {
        if (!isBlock()) return;
        Vector3f p0 = start.left.transform(new Vector3f(start.scale).mul(0.5f), new Vector3f());
        Vector3f p1 = next.left.transform(new Vector3f(next.scale).mul(0.5f), new Vector3f());
        next.translation.set(start.translation).add(p0).sub(p1);
    }

    /** 모드별 축 방향(월드, 단위벡터). 이동: 월드 축 / 회전: 엔티티 축 / 크기: 엔티티 × left_rotation 축 */
    private static Vec3d axisWorld(Mode m, int i, Pose p) {
        Vector3f e = new Vector3f(i == 0 ? 1f : 0f, i == 1 ? 1f : 0f, i == 2 ? 1f : 0f);
        Vector3f out = new Vector3f(e);
        switch (m) {
            case ROTATE -> p.orientation().transform(e, out);
            case SCALE -> new Quaternionf(p.orientation()).mul(p.left).transform(e, out);
            default -> { }
        }
        return new Vec3d(out.x, out.y, out.z).normalize();
    }

    // ------------------------------------------------------------------
    // 시선 / 투영
    // ------------------------------------------------------------------

    private record View(Vec3d eye, Vec3d dir, Vec3d right, Vec3d up, double tanHalf, int w, int h) {
        static View of(MinecraftClient client, PlayerEntity player, float partial) {
            Vec3d eye = player.getCameraPosVec(partial);
            Vec3d dir = player.getRotationVec(partial).normalize();
            double yaw = Math.toRadians(player.getYaw(partial));
            Vec3d right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
            Vec3d up = right.crossProduct(dir);
            // 설정의 FOV(세로). 달리기/비행으로 바뀌는 FOV 효과는 반영하지 않는다.
            double fov = client.options.getFov().getValue();
            int w = client.getWindow().getScaledWidth();
            int h = client.getWindow().getScaledHeight();
            return new View(eye, dir, right, up, Math.tan(Math.toRadians(fov) / 2.0), w, h);
        }

        /** 화면 좌표 {x, y}. 카메라 뒤면 null. */
        double[] project(Vec3d p) {
            Vec3d rel = p.subtract(eye);
            double z = rel.dotProduct(dir);
            if (z < NEAR) return null;
            double k = (h / 2.0) / (z * tanHalf);
            return new double[]{w / 2.0 + rel.dotProduct(right) * k, h / 2.0 - rel.dotProduct(up) * k};
        }

        double depth(Vec3d p) {
            return p.subtract(eye).dotProduct(dir);
        }
    }

    /** 시선(직선)과 직선 O + t·a 가 가장 가까운 지점의 t. 거의 평행이면 NaN. */
    private static double lineParam(View v, Vec3d o, Vec3d a) {
        Vec3d w0 = o.subtract(v.eye);
        double b = a.dotProduct(v.dir);
        double c = a.dotProduct(w0);
        double e = v.dir.dotProduct(w0);
        double den = 1.0 - b * b;
        if (den < 1e-4) return Double.NaN;
        return (b * e - c) / den;
    }

    /** 시선과 평면(point, normal)의 교점. 평행이거나 뒤쪽이면 null. */
    private static Vec3d rayPlane(View v, Vec3d point, Vec3d normal) {
        double denom = normal.dotProduct(v.dir);
        if (Math.abs(denom) < 1e-4) return null;
        double s = point.subtract(v.eye).dotProduct(normal) / denom;
        if (s <= 0) return null;
        return v.eye.add(v.dir.multiply(s));
    }

    /** 점과 시선 사이 거리. 카메라 뒤면 무한대. */
    private static double rayPointDistance(View v, Vec3d p) {
        Vec3d rel = p.subtract(v.eye);
        double s = rel.dotProduct(v.dir);
        if (s < 0) return Double.MAX_VALUE;
        return rel.subtract(v.dir.multiply(s)).length();
    }

    private static Vec3d[] basis(Vec3d axis) {
        Vec3d h = Math.abs(axis.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d u = axis.crossProduct(h).normalize();
        Vec3d w = axis.crossProduct(u).normalize();
        return new Vec3d[]{u, w};
    }

    private static double angleOf(Vec3d rel, Vec3d axis) {
        Vec3d[] b = basis(axis);
        return Math.atan2(rel.dotProduct(b[1]), rel.dotProduct(b[0]));
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a <= -Math.PI) a += 2 * Math.PI;
        return a;
    }

    // ------------------------------------------------------------------
    // 핸들 선택
    // ------------------------------------------------------------------

    private static Part pickPart(View v) {
        if (pose == null) return null;
        Vec3d o = pivot(pose);
        double dist = Math.max(0.5, v.eye.distanceTo(o));
        double len = dist * ARM;

        double best = Double.MAX_VALUE;
        Part bestPart = null;

        if (mode != Mode.ROTATE) {
            double dc = rayPointDistance(v, o);
            if (dc < dist * 0.035) {
                best = 0; // 가운데 핸들 우선
                bestPart = Part.CENTER;
            }
        }
        if (bestPart != null) return bestPart;

        for (int i = 0; i < 3; i++) {
            Vec3d a = axisWorld(mode, i, pose);
            if (mode == Mode.ROTATE) {
                Vec3d hit = rayPlane(v, o, a);
                if (hit == null) continue;
                double err = Math.abs(hit.subtract(o).length() - len * 0.9);
                if (err < dist * 0.035 && err / dist < best) {
                    best = err / dist;
                    bestPart = Part.values()[i];
                }
            } else {
                double t = lineParam(v, o, a);
                if (Double.isNaN(t)) t = 0; // 평행이면 시작점 기준
                t = MathHelper.clamp(t, len * 0.15, len);
                double d = rayPointDistance(v, o.add(a.multiply(t)));
                if (d < dist * 0.025 && d / dist < best) {
                    best = d / dist;
                    bestPart = Part.values()[i];
                }
            }
        }
        return bestPart;
    }

    // ------------------------------------------------------------------
    // 드래그
    // ------------------------------------------------------------------

    private static final class Drag {
        Part part;
        Mode mode;
        Pose start;
        Vec3d pivot;
        Vec3d axis = Vec3d.ZERO;
        Vector3f localAxis = new Vector3f();
        Vec3d normal = Vec3d.ZERO;
        Vec3d grab = Vec3d.ZERO;
        double t0;
        double lastAngle;
        double total;
        float pitch0;
        double arm;
        String readout = "";
    }

    private static void beginDrag(MinecraftClient client, View v) {
        Part part = hoverPart;
        if (part == null || pose == null) return;

        Drag d = new Drag();
        d.part = part;
        d.mode = mode;
        d.start = pose.copy();
        d.pivot = pivot(pose);
        d.arm = Math.max(0.5, v.eye.distanceTo(d.pivot)) * ARM;
        if (part != Part.CENTER) {
            int i = part.ordinal();
            d.axis = axisWorld(mode, i, pose);
            d.localAxis = new Vector3f(i == 0 ? 1f : 0f, i == 1 ? 1f : 0f, i == 2 ? 1f : 0f);
        }

        switch (mode) {
            case MOVE -> {
                if (part == Part.CENTER) {
                    d.normal = v.dir;
                    Vec3d hit = rayPlane(v, d.pivot, d.normal);
                    if (hit == null) return;
                    d.grab = hit;
                } else {
                    double t = lineParam(v, d.pivot, d.axis);
                    if (Double.isNaN(t)) return;
                    d.t0 = t;
                }
            }
            case ROTATE -> {
                Vec3d hit = rayPlane(v, d.pivot, d.axis);
                if (hit == null) return;
                d.lastAngle = angleOf(hit.subtract(d.pivot), d.axis);
            }
            case SCALE -> {
                if (part == Part.CENTER) {
                    d.pitch0 = client.player.getPitch();
                } else {
                    double t = lineParam(v, d.pivot, d.axis);
                    if (Double.isNaN(t)) return;
                    double min = d.arm * 0.3; // 피벗 근처를 잡아도 배율이 폭주하지 않게
                    d.t0 = Math.abs(t) < min ? (t < 0 ? -min : min) : t;
                }
            }
        }
        drag = d;
        previewed = d.start.copy();
        if (isMulti()) {
            // 다중 선택: 각 멤버의 시작 포즈를 기억해 둔다
            memberStart = new Pose[members.size()];
            memberPreviewed = new Pose[members.size()];
            for (int i = 0; i < members.size(); i++) {
                memberStart[i] = readPose(EntityNbt.save(members.get(i)));
                memberPreviewed[i] = memberStart[i].copy();
            }
        }
        // 되돌리기 기록용: 드래그 시작 시점의 모습
        dragBefore = new ArrayList<>();
        if (isMulti()) {
            for (DisplayEntity m : members) dragBefore.add(EntitySnapshot.of(m));
        } else {
            dragBefore.add(EntitySnapshot.of(selected));
        }
    }

    /** 시선에 맞춰 새 포즈를 계산하고 미리보기에 적용한다. (프레임마다 불린다) */
    private static void updateDrag(MinecraftClient client, View v) {
        Drag d = drag;
        if (d == null || selected == null || pose == null) return;
        boolean multi = isMulti();
        Pose next = d.start.copy();

        switch (d.mode) {
            case MOVE -> {
                Vec3d delta;
                if (d.part == Part.CENTER) {
                    Vec3d hit = rayPlane(v, d.pivot, d.normal);
                    if (hit == null) return;
                    delta = hit.subtract(d.grab);
                } else {
                    double t = lineParam(v, d.pivot, d.axis);
                    if (Double.isNaN(t)) return;
                    delta = d.axis.multiply(MathHelper.clamp(t - d.t0, -256.0, 256.0));
                }
                Vec3d np = d.start.pos.add(delta);
                next.pos = np;
                Vec3d moved = np.subtract(d.start.pos);
                d.readout = String.format(Locale.ROOT, "Δ %.2f  %.2f  %.2f", moved.x, moved.y, moved.z);
            }
            case ROTATE -> {
                Vec3d hit = rayPlane(v, d.pivot, d.axis);
                if (hit == null) return;
                double ang = angleOf(hit.subtract(d.pivot), d.axis);
                d.total += wrap(ang - d.lastAngle);
                d.lastAngle = ang;
                double a = d.total;
                Quaternionf delta = new Quaternionf().rotationAxis((float) a, d.localAxis.x, d.localAxis.y, d.localAxis.z);
                delta.mul(d.start.left, next.left).normalize();
                keepPivot(next, d.start);
                d.readout = String.format(Locale.ROOT, "%.1f°", Math.toDegrees(a));
            }
            case SCALE -> {
                double f;
                if (d.part == Part.CENTER) {
                    // 시선을 위로 올리면 커지고 내리면 작아진다
                    f = Math.exp(-(client.player.getPitch() - d.pitch0) * 0.03);
                } else {
                    double t = lineParam(v, d.pivot, d.axis);
                    if (Double.isNaN(t)) return;
                    f = t / d.t0;
                }
                f = MathHelper.clamp(f, 0.01, 100.0);
                for (int i = 0; i < 3; i++) {
                    // 다중 선택의 크기 조절은 어느 핸들이든 균등 배율로 적용한다
                    if (!multi && d.part != Part.CENTER && d.part.ordinal() != i) continue;
                    float s = d.start.scale.get(i);
                    double value = Math.abs(s) * f;
                    next.scale.setComponent(i, (float) (Math.signum(s == 0f ? 1f : s) * value));
                }
                keepPivot(next, d.start);
                d.readout = String.format(Locale.ROOT, "×%.2f", f);
            }
        }

        if (!next.sameAs(pose)) {
            pose = next;
            applyPreview(next);
        }
    }

    private static void cancelDrag() {
        if (drag == null) return;
        if (isMulti() && memberStart != null) {
            restoreMembers();
            drag = null;
            previewed = null;
            memberStart = null;
            memberPreviewed = null;
            dragBefore = null;
            rebuildFrame();
            return;
        }
        pose = drag.start;
        applyPreview(pose);
        previewed = null;
        dragBefore = null;
        drag = null;
    }

    private static void finishDrag(MinecraftClient client) {
        Drag d = drag;
        List<EntitySnapshot> before = dragBefore;
        drag = null;
        previewed = null;
        dragBefore = null;
        if (d == null || selected == null || pose == null) {
            memberStart = null;
            memberPreviewed = null;
            return;
        }
        Text label = Text.translatable("dee.gizmo.mode." + d.mode.name().toLowerCase(Locale.ROOT));

        if (isMulti() && memberStart != null) {
            List<EntitySnapshot> after = new ArrayList<>();
            boolean changed = false;
            for (int i = 0; i < members.size() && i < memberStart.length; i++) {
                Pose np = transformMember(memberStart[i], d.start, pose);
                List<String> commands = commandsFor(members.get(i).getUuidAsString(), memberStart[i], np);
                outbox.addAll(commands);
                if (!commands.isEmpty()) changed = true;
                if (before != null && i < before.size()) after.add(before.get(i).with(np.pos, transformationTag(np)));
            }
            if (changed && before != null && after.size() == before.size()) {
                EditorOps.recordTransform(label, before, after);
            }
            memberStart = null;
            memberPreviewed = null;
            rebuildFrame();   // 기즈모를 새 중심으로 다시 놓는다 (회전/크기 값은 항등으로 초기화)
            holdRefresh = 10 + outbox.size() / OUTBOX_PER_TICK;
            return;
        }

        List<String> commands = commandsFor(selected.getUuidAsString(), d.start, pose);
        outbox.addAll(commands);
        if (!commands.isEmpty() && before != null && before.size() == 1) {
            EditorOps.recordTransform(label, before, List.of(before.get(0).with(pose.pos, transformationTag(pose))));
        }
        holdRefresh = 10 + outbox.size() / OUTBOX_PER_TICK;
    }

    private static boolean sameTransform(Pose a, Pose b) {
        return a.translation.equals(b.translation) && a.scale.equals(b.scale)
                && a.left.equals(b.left) && a.right.equals(b.right);
    }

    /** DisplayEntity 의 private setTransformation(AffineTransformation). 못 찾으면 null 이고 실시간 미리보기는 꺼진다. */
    private static Method setTransformation;
    private static boolean transformationLookedUp;

    /**
     * (text_display 전용) transformation 만 클라이언트 엔티티의 동기화 데이터에 직접 넣는다. (NBT 를 save/load 하지 않는다)
     * NBT 를 통째로 다시 load 하면 text 값이 비어 버리기 때문이다.
     * 메서드 이름 대신 "파라미터가 AffineTransformation 하나인 DisplayEntity 의 메서드"로 찾는다.
     */
    private static boolean applyTransformation(DisplayEntity e, Pose p) {
        if (!transformationLookedUp) {
            transformationLookedUp = true;
            for (Method m : DisplayEntity.class.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0] == AffineTransformation.class && m.getReturnType() == void.class) {
                    try {
                        m.setAccessible(true);
                        setTransformation = m;
                    } catch (RuntimeException ignored) {
                        // 접근할 수 없으면 미리보기 없이 동작한다
                    }
                    break;
                }
            }
        }
        if (setTransformation == null) return false;
        try {
            setTransformation.invoke(e, new AffineTransformation(
                    new Vector3f(p.translation), new Quaternionf(p.left),
                    new Vector3f(p.scale), new Quaternionf(p.right)));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            setTransformation = null; // 한 번 실패하면 더는 시도하지 않는다
            return false;
        }
    }

    /**
     * 한 엔티티에 미리보기를 적용한다. (클라이언트 엔티티에만 적용되고, 서버에는 마우스를 놓을 때 명령으로 간다)
     * - 위치: setPos
     * - 회전/크기 (transformation 이 바뀐 경우에만):
     *   · text_display: transformation 만 직접 설정. (NBT 를 다시 load 하면 text 가 비어 버린다)
     *   · block_display / item_display: 현재 NBT 를 읽어 transformation 만 바꿔서 다시 load
     *     (fast 이면 NBT 를 거치지 않고 text_display 와 같은 직접 설정을 먼저 시도한다. 여러 개를 한꺼번에 움직일 때 쓴다)
     * last 는 마지막으로 반영한 포즈(null 이면 transformation 은 건너뜀). 반영한 포즈를 돌려준다.
     */
    private static Pose previewEntity(DisplayEntity e, Pose p, Pose last, boolean fast) {
        Pose now = last;
        if (LIVE_TRANSFORM_PREVIEW && last != null && !sameTransform(p, last)) {
            boolean text = e instanceof DisplayEntity.TextDisplayEntity;
            boolean done = false;
            if (text || fast) done = applyTransformation(e, p);
            if (!done && !text) {
                try {
                    NbtCompound nbt = EntityNbt.save(e);
                    nbt.put("transformation", transformationTag(p));
                    EntityNbt.load(e, nbt);
                    done = true;
                } catch (RuntimeException ignored) {
                    // 미리보기가 실패해도 기즈모와 수치 표시, 확정 시 적용은 그대로 동작한다
                }
            }
            if (done) now = p.copy();
        }
        e.setPosition(p.pos.x, p.pos.y, p.pos.z);
        return now;
    }

    private static void applyPreview(Pose p) {
        if (selected == null) return;
        if (isMulti()) {
            previewMulti(p);
            return;
        }
        previewed = previewEntity(selected, p, previewed, false);
    }

    /** 다중 선택: 기즈모(기준 포즈)의 변화를 모든 멤버에 같은 식으로 적용해 미리보기. */
    private static void previewMulti(Pose next) {
        if (drag == null || memberStart == null || memberPreviewed == null) return;
        for (int i = 0; i < members.size() && i < memberStart.length; i++) {
            Pose np = transformMember(memberStart[i], drag.start, next);
            memberPreviewed[i] = previewEntity(members.get(i), np, memberPreviewed[i], true);
        }
    }

    private static void restoreMembers() {
        if (memberStart == null || memberPreviewed == null) return;
        for (int i = 0; i < members.size() && i < memberStart.length; i++) {
            memberPreviewed[i] = previewEntity(members.get(i), memberStart[i], memberPreviewed[i], true);
        }
    }

    /**
     * 기준 포즈가 f0 -> f1 으로 바뀔 때(이동 + 회전 q + 균등 배율 f, 회전/배율의 중심은 f0.pos) 멤버 s 의 새 포즈.
     * 멤버의 월드 변환 M = T(pos)·O·T(translation)·L·S·R 에 그룹 변환을 곱해 같은 형태로 다시 쓴 것이다.
     *   pos' = 중심' + f·q·(pos - 중심),  translation' = f·(O⁻¹qO)·translation,  left' = (O⁻¹qO)·left,  scale' = f·scale
     * (O = 엔티티 자체의 방향. 회전 q 는 월드 기준)
     */
    private static Pose transformMember(Pose s, Pose f0, Pose f1) {
        Pose n = s.copy();
        Quaternionf q = new Quaternionf(f1.left).mul(new Quaternionf(f0.left).conjugate());
        boolean rotated = !(q.x == 0f && q.y == 0f && q.z == 0f && q.w == 1f);
        float f = f0.scale.x == 0f ? 1f : f1.scale.x / f0.scale.x;
        Vec3d move = f1.pos.subtract(f0.pos);

        if (!rotated && f == 1f) {
            n.pos = s.pos.add(move);   // 순수 이동: 값이 미세하게 달라지지 않도록 그대로 더한다
            return n;
        }

        Vec3d rel = s.pos.subtract(f0.pos);
        Vector3f r = q.transform(new Vector3f((float) rel.x, (float) rel.y, (float) rel.z), new Vector3f());
        n.pos = f0.pos.add(move).add(r.x * f, r.y * f, r.z * f);

        Vector3f t = new Vector3f(s.translation);
        if (rotated) {
            Quaternionf o = s.orientation();
            Quaternionf qp = new Quaternionf(o).invert().mul(q).mul(o);
            t = qp.transform(new Vector3f(s.translation), new Vector3f());
            n.left.set(new Quaternionf(qp).mul(s.left).normalize());
        }
        n.translation.set(t).mul(f);
        n.scale.set(s.scale).mul(f);
        return n;
    }

    /** 바뀐 것만 명령으로 보낸다: 위치 -> tp, 회전/크기 -> data merge entity (transformation). */
    private static List<String> commandsFor(String id, Pose from, Pose to) {
        List<String> out = new ArrayList<>(2);
        if (!to.pos.equals(from.pos)) {
            out.add(String.format(Locale.ROOT, "tp %s %.5f %.5f %.5f", id, to.pos.x, to.pos.y, to.pos.z));
        }
        boolean transformChanged = !to.translation.equals(from.translation) || !to.scale.equals(from.scale)
                || !to.left.equals(from.left) || !to.right.equals(from.right);
        if (transformChanged) {
            out.add("data merge entity " + id + " {transformation:" + transformationTag(to) + "}");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // HUD 렌더링 (DisplayEditClient 가 HudElementRegistry 로 연결)
    // ------------------------------------------------------------------

    public static void render(MinecraftClient client, DrawContext g, float partial) {
        if (!active || client.player == null || client.world == null) return;
        if (client.currentScreen != null) return;
        renderedSinceTick = true;

        View v = View.of(client, client.player, partial);
        if (drag != null) {
            updateDrag(client, v);
        } else if (pose != null) {
            hoverPart = pickPart(v);
        }

        if (pose != null) drawGizmo(g, v);

        // 다중 선택: 선택된 멤버마다 작은 사각형 표시
        if (isMulti()) {
            for (DisplayEntity m : members) {
                double[] s = v.project(m.getPos());
                if (s != null) box(g, (int) s[0], (int) s[1], 3, COLOR_WHITE);
            }
        }

        // 마우스를 올려 둔 다른 디스플레이 표시
        if (drag == null && hoverPart == null && hoverEntity != null && hoverEntity != selected) {
            double[] s = v.project(hoverEntity.getPos());
            if (s != null) box(g, (int) s[0], (int) s[1], 5, COLOR_HOT);
            g.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("dee.gizmo.hover", keyName("key.attack"), keyName("key.dee.open_editor"), keyName("key.use")), v.w / 2, v.h / 2 + 14, 0xFFFFE066);
        }

        // 바라보는 디스플레이가 그룹에 속해 있으면 그 그룹 전체의 경계를 보여 준다
        if (drag == null && hoverEntity != null) {
            DisplayGroups.Group hoverGroup = DisplayGroups.groupOf(hoverEntity.getUuid());
            if (hoverGroup != null) drawGroupBorder(client, g, v, hoverGroup);
        }

        // 상단 안내
        Text modeName = Text.translatable("dee.gizmo.mode." + mode.name().toLowerCase(Locale.ROOT));
        g.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("dee.gizmo.title", modeName), v.w / 2, 6, 0xFFFFFFFF);
        g.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("dee.gizmo.help",
                keyName("key.hotbar.1"), keyName("key.hotbar.2"), keyName("key.hotbar.3"),
                keyName("key.attack"), keyName("key.use")), v.w / 2, 18, 0xFFA0A0A0);
        g.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("dee.gizmo.help_hint"), v.w / 2, 30, 0xFF808080);
        int infoY = 42;
        if (showHelp) {
            g.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("dee.gizmo.help2",
                    keyName("key.attack"), keyName("key.dee.multi_select"), keyName("key.use"),
                    keyName("key.dee.open_editor")), v.w / 2, 42, 0xFFA0A0A0);
            g.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("dee.gizmo.help3"), v.w / 2, 54, 0xFFA0A0A0);
            infoY = 66;
        }
        if (isMulti()) {
            DisplayGroups.Group group = selectionGroup();
            Text info = group != null
                    ? Text.translatable("dee.gizmo.group", group.name, members.size())
                    : Text.translatable("dee.gizmo.multi", members.size());
            g.drawCenteredTextWithShadow(client.textRenderer, info, v.w / 2, infoY, 0xFFFFE066);
        }
        if (drag != null) {
            g.drawCenteredTextWithShadow(client.textRenderer, Text.literal(drag.readout), v.w / 2, v.h / 2 + 14, 0xFFFFE066);
        }
    }

    /** 안내 문구에 넣을 키 이름. 사용자가 바꾼 키 설정이 그대로 나온다. (언어팩에 키를 직접 쓰지 않기 위함) */
    private static Text keyName(String keyMappingName) {
        return Text.keybind(keyMappingName).copy().styled(style -> style.withColor(0xFFFFFF));
    }

    private static void drawGizmo(DrawContext g, View v) {
        Vec3d o = pivot(pose);
        double dist = Math.max(0.5, v.eye.distanceTo(o));
        double len = dist * ARM;
        Part hot = drag != null ? drag.part : hoverPart;

        if (mode == Mode.ROTATE) {
            for (int i = 0; i < 3; i++) {
                Vec3d a = axisWorld(mode, i, pose);
                Vec3d[] b = basis(a);
                int color = hot == Part.values()[i] ? COLOR_HOT : AXIS_COLOR[i];
                final int n = 48;
                Vec3d prev = null;
                for (int k = 0; k <= n; k++) {
                    double ang = 2 * Math.PI * k / n;
                    Vec3d p = o.add(b[0].multiply(Math.cos(ang) * len * 0.9)).add(b[1].multiply(Math.sin(ang) * len * 0.9));
                    if (prev != null) worldLine(g, v, prev, p, color, 2);
                    prev = p;
                }
            }
        } else {
            for (int i = 0; i < 3; i++) {
                Vec3d a = axisWorld(mode, i, pose);
                Vec3d tip = o.add(a.multiply(len));
                int color = hot == Part.values()[i] ? COLOR_HOT : AXIS_COLOR[i];
                worldLine(g, v, o, tip, color, 2);
                double[] s = v.project(tip);
                if (s != null) fillBox(g, (int) s[0], (int) s[1], mode == Mode.SCALE ? 4 : 3, color);
            }
            double[] c = v.project(o);
            if (c != null) fillBox(g, (int) c[0], (int) c[1], 4, hot == Part.CENTER ? COLOR_HOT : COLOR_WHITE);
        }
        if (mode == Mode.ROTATE) {
            double[] c = v.project(o);
            if (c != null) fillBox(g, (int) c[0], (int) c[1], 2, COLOR_WHITE);
        }
    }

    /** 월드 좌표 선분을 화면에 그린다. 카메라 앞쪽(near)에서 잘라낸다. */
    private static void worldLine(DrawContext g, View v, Vec3d p0, Vec3d p1, int color, int thickness) {
        double z0 = v.depth(p0);
        double z1 = v.depth(p1);
        if (z0 < NEAR && z1 < NEAR) return;
        if (z0 < NEAR) p0 = p0.add(p1.subtract(p0).multiply((NEAR - z0) / (z1 - z0)));
        if (z1 < NEAR) p1 = p1.add(p0.subtract(p1).multiply((NEAR - z1) / (z0 - z1)));
        double[] a = v.project(p0);
        double[] b = v.project(p1);
        if (a == null || b == null) return;

        // 화면 밖은 그리지 않는다. (가까이 가면 선이 화면보다 훨씬 길어져 fill 호출이 폭증한다)
        double[] clipped = clipToScreen(a, b, v.w, v.h);
        if (clipped == null) return;
        double ax = clipped[0], ay = clipped[1], bx = clipped[2], by = clipped[3];
        double dx = bx - ax;
        double dy = by - ay;

        // 픽셀마다 fill 하지 않고, 같은 줄(또는 열)에 놓이는 연속 픽셀은 한 번의 fill 로 묶는다.
        // 가로/세로선은 fill 한 번, 대각선도 (화면 크기 이하의) 줄 수만큼만 호출한다.
        if (Math.abs(dx) >= Math.abs(dy)) {
            int x0 = (int) Math.round(ax), x1 = (int) Math.round(bx);
            int stepX = x1 >= x0 ? 1 : -1;
            int runStart = x0;
            int runY = Integer.MIN_VALUE;
            for (int x = x0; ; x += stepX) {
                int y = dx == 0 ? (int) Math.round(ay) : (int) Math.round(ay + dy * (x - ax) / dx);
                if (runY == Integer.MIN_VALUE) runY = y;
                if (y != runY) {
                    int prev = x - stepX;
                    g.fill(Math.min(runStart, prev), runY, Math.max(runStart, prev) + thickness, runY + thickness, color);
                    runStart = x;
                    runY = y;
                }
                if (x == x1) break;
            }
            g.fill(Math.min(runStart, x1), runY, Math.max(runStart, x1) + thickness, runY + thickness, color);
        } else {
            int y0 = (int) Math.round(ay), y1 = (int) Math.round(by);
            int stepY = y1 >= y0 ? 1 : -1;
            int runStart = y0;
            int runX = Integer.MIN_VALUE;
            for (int y = y0; ; y += stepY) {
                int x = (int) Math.round(ax + dx * (y - ay) / dy);
                if (runX == Integer.MIN_VALUE) runX = x;
                if (x != runX) {
                    int prev = y - stepY;
                    g.fill(runX, Math.min(runStart, prev), runX + thickness, Math.max(runStart, prev) + thickness, color);
                    runStart = y;
                    runX = x;
                }
                if (y == y1) break;
            }
            g.fill(runX, Math.min(runStart, y1), runX + thickness, Math.max(runStart, y1) + thickness, color);
        }
    }

    /** 선분 a-b 를 화면 사각형 [0,w]x[0,h] 로 자른다 (Liang-Barsky). 완전히 밖이면 null. {x0, y0, x1, y1} */
    private static double[] clipToScreen(double[] a, double[] b, int w, int h) {
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double t0 = 0.0, t1 = 1.0;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {a[0], w - a[0], a[1], h - a[1]};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return null;
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) {
                    if (t > t1) return null;
                    if (t > t0) t0 = t;
                } else {
                    if (t < t0) return null;
                    if (t < t1) t1 = t;
                }
            }
        }
        return new double[]{a[0] + t0 * dx, a[1] + t0 * dy, a[0] + t1 * dx, a[1] + t1 * dy};
    }

    // ------------------------------------------------------------------
    // 그룹 경계 (바라보는 디스플레이가 속한 그룹을 감싸는 상자)
    // ------------------------------------------------------------------

    /** 경계 계산은 비싸서(멤버마다 NBT 를 읽는다) 멤버별로 캐시하고, 한 번에 새로 읽는 수를 제한한다. */
    private static UUID boundsGroup;
    private static long boundsTime;
    private static final double[] BOUNDS = new double[6]; // minX, minY, minZ, maxX, maxY, maxZ
    private static int boundsCount;

    private static void drawGroupBorder(MinecraftClient client, DrawContext g, View v, DisplayGroups.Group group) {
        long now = System.currentTimeMillis();
        if (!group.id.equals(boundsGroup) || now - boundsTime > 100) {
            computeGroupBounds(client, group);
            boundsGroup = group.id;
            boundsTime = now;
        }
        if (boundsCount == 0) return;

        double x0 = BOUNDS[0], y0 = BOUNDS[1], z0 = BOUNDS[2], x1 = BOUNDS[3], y1 = BOUNDS[4], z1 = BOUNDS[5];
        Vec3d[] c = {
                new Vec3d(x0, y0, z0), new Vec3d(x1, y0, z0), new Vec3d(x1, y0, z1), new Vec3d(x0, y0, z1),
                new Vec3d(x0, y1, z0), new Vec3d(x1, y1, z0), new Vec3d(x1, y1, z1), new Vec3d(x0, y1, z1)
        };
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            worldLine(g, v, c[i], c[j], COLOR_GROUP, 1);         // 아래 면
            worldLine(g, v, c[4 + i], c[4 + j], COLOR_GROUP, 1); // 위 면
            worldLine(g, v, c[i], c[4 + i], COLOR_GROUP, 1);     // 기둥
        }
        g.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("dee.gizmo.group", group.name, boundsCount),
                v.w / 2, v.h / 2 + 26, COLOR_GROUP);
    }

    /** 멤버별 경계 캐시. 멤버마다 만료 시각이 달라야(아래 지터) 한 프레임에 전부 다시 계산하는 일이 없다. */
    private static final Map<UUID, double[]> MEMBER_BOUNDS = new HashMap<>();
    private static final Map<UUID, Long> MEMBER_BOUNDS_TIME = new HashMap<>();
    private static final long MEMBER_TTL_MS = 1500;
    /** 한 번 갱신할 때 NBT 를 새로 읽는 멤버 수의 상한. 나머지는 이전 값을 쓰고 다음 갱신 때 이어서 한다. */
    private static final int MEMBER_BUDGET = 8;

    private static void computeGroupBounds(MinecraftClient client, DisplayGroups.Group group) {
        boundsCount = 0;
        if (client.world == null || client.player == null) return;
        List<DisplayEntity> found = client.world.getEntitiesByClass(
                DisplayEntity.class, client.player.getBoundingBox().expand(64.0), d -> group.members.contains(d.getUuid()));
        BOUNDS[0] = BOUNDS[1] = BOUNDS[2] = Double.POSITIVE_INFINITY;
        BOUNDS[3] = BOUNDS[4] = BOUNDS[5] = Double.NEGATIVE_INFINITY;
        long now = System.currentTimeMillis();
        int budget = MEMBER_BUDGET;
        if (MEMBER_BOUNDS.size() > 4096) {
            MEMBER_BOUNDS.clear();
            MEMBER_BOUNDS_TIME.clear();
        }
        for (DisplayEntity e : found) {
            UUID id = e.getUuid();
            double[] box = MEMBER_BOUNDS.get(id);
            Long t = MEMBER_BOUNDS_TIME.get(id);
            long ttl = MEMBER_TTL_MS + (e.getId() & 15) * 50L; // 멤버마다 만료 시각을 어긋나게 한다
            boolean stale = box == null || t == null || now - t > ttl;
            if (stale && budget > 0) {
                budget--;
                box = new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
                addMemberBounds(client, e, box);
                MEMBER_BOUNDS.put(id, box);
                MEMBER_BOUNDS_TIME.put(id, now);
            }
            if (box == null) continue; // 아직 계산 못 한 멤버는 다음 갱신 때 들어온다
            for (int i = 0; i < 3; i++) BOUNDS[i] = Math.min(BOUNDS[i], box[i]);
            for (int i = 3; i < 6; i++) BOUNDS[i] = Math.max(BOUNDS[i], box[i]);
            boundsCount++;
        }
    }

    /**
     * 멤버 하나의 화면에 보이는 범위(대략)를 월드 좌표 상자로 BOUNDS 에 합친다.
     * 로컬 범위: 블록은 0~1 정육면체, 아이템은 중심 기준 1x1 의 얇은 판, 텍스트는 글자 폭/줄 수로 추정한 상자.
     * 거기에 translation/회전/크기 변환을 적용한다. (빌보드는 반영하지 않는다)
     */
    private static void addMemberBounds(MinecraftClient client, DisplayEntity e, double[] out) {
        NbtCompound nbt = EntityNbt.save(e);
        Pose p = readPose(nbt);

        float lx0, lx1, ly0, ly1, lz0, lz1;
        if (e instanceof DisplayEntity.BlockDisplayEntity) {
            lx0 = ly0 = lz0 = 0f;
            lx1 = ly1 = lz1 = 1f;
        } else if (e instanceof DisplayEntity.ItemDisplayEntity) {
            lx0 = ly0 = -0.5f;
            lx1 = ly1 = 0.5f;
            lz0 = -0.03f;
            lz1 = 0.03f;
        } else {
            // 텍스트: 1 글자 픽셀 = 1/40 블록. 가로는 가운데 정렬, 세로는 엔티티 위치에서 위로 자란다고 본다.
            int lineWidth = nbt.getInt("line_width", 200);
            int maxW = 8;
            int lines = 1;
            NbtElement element = nbt.get("text");
            if (element != null) {
                Text c = TextCodecs.CODEC.parse(NbtOps.INSTANCE, element).result().orElse(null);
                if (c != null) {
                    maxW = 0;
                    lines = 0;
                    for (String line : c.getString().split("\n", -1)) {
                        int w = client.textRenderer.getWidth(line);
                        lines += Math.max(1, (int) Math.ceil(w / (double) Math.max(1, lineWidth)));
                        maxW = Math.max(maxW, Math.min(w, lineWidth));
                    }
                }
            }
            float halfW = (maxW + 2) * 0.5f * 0.025f;
            float height = (lines * 9 + 1) * 0.025f;
            lx0 = -halfW;
            lx1 = halfW;
            ly0 = 0f;
            ly1 = height;
            lz0 = -0.01f;
            lz1 = 0.01f;
        }

        // 월드 변환: 엔티티 방향 · translation · left · scale · right  (엔티티 위치에서 시작)
        Matrix4f m = new Matrix4f().rotation(p.orientation()).translate(p.translation)
                .rotate(p.left).scale(p.scale).rotate(p.right);
        float[] xs = {lx0, lx1};
        float[] ys = {ly0, ly1};
        float[] zs = {lz0, lz1};
        for (float x : xs) {
            for (float y : ys) {
                for (float z : zs) {
                    Vector3f w = m.transformPosition(new Vector3f(x, y, z));
                    double wx = p.pos.x + w.x, wy = p.pos.y + w.y, wz = p.pos.z + w.z;
                    out[0] = Math.min(out[0], wx);
                    out[1] = Math.min(out[1], wy);
                    out[2] = Math.min(out[2], wz);
                    out[3] = Math.max(out[3], wx);
                    out[4] = Math.max(out[4], wy);
                    out[5] = Math.max(out[5], wz);
                }
            }
        }
    }

    private static void fillBox(DrawContext g, int cx, int cy, int r, int color) {
        g.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, color);
    }

    private static void box(DrawContext g, int cx, int cy, int r, int color) {
        g.fill(cx - r, cy - r, cx + r + 1, cy - r + 1, color);
        g.fill(cx - r, cy + r, cx + r + 1, cy + r + 1, color);
        g.fill(cx - r, cy - r, cx - r + 1, cy + r + 1, color);
        g.fill(cx + r, cy - r, cx + r + 1, cy + r + 1, color);
    }
}