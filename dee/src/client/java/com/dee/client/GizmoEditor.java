package com.dee.client;

import com.mojang.math.Transformation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 인게임 기즈모 에디터 모드. (블렌더의 이동/회전/크기 기즈모와 같은 방식)
 *
 * - 마우스는 잡힌 채로 그대로이고, 십자선(화면 정중앙)이 핸들에 닿으면 강조된다. 좌클릭을 누른 채 시선을 움직이면 드래그.
 * - 기즈모는 월드 렌더가 아니라 HUD 에 3D -> 2D 투영으로 그린다. (월드 렌더 API 는 버전마다 크게 바뀌어서 HUD API 만 쓴다)
 * - 드래그 중에는 클라이언트 엔티티에만 미리보기로 적용하고, 마우스를 놓을 때 명령 1~2개만 서버로 보낸다.
 *   (드래그 도중 명령을 계속 보내면 채팅 스팸 제한에 걸려 강퇴당한다)
 *
 * 조작: 1/2/3 = 이동/회전/크기, 좌클릭 드래그 = 조작, 드래그 중 우클릭 = 취소, Ctrl(달리기 키) = 스냅,
 *       엔티티를 바라보고 좌클릭 = 선택, 우클릭 / G(에디터 열기 키) = 편집 UI (G 는 바라보는 게 없으면 새로 만들기), ESC = 종료
 */
final class GizmoEditor {
    private GizmoEditor() {}

    enum Mode { MOVE, ROTATE, SCALE }

    /** X, Y, Z 축 핸들과 가운데 핸들(이동: 화면 평면 이동 / 크기: 균등 크기) */
    private enum Part { X, Y, Z, CENTER }

    /** 화살표 길이 = 카메라까지 거리 × 이 값. (화면에서 항상 비슷한 크기로 보이게) */
    private static final double ARM = 0.2;
    private static final double NEAR = 0.05;
    private static final float DEG = (float) (Math.PI / 180.0);

    private static final int[] AXIS_COLOR = {0xFFE5484D, 0xFF46A758, 0xFF3E63DD};
    private static final int COLOR_HOT = 0xFFFFE066;
    private static final int COLOR_WHITE = 0xFFFFFFFF;

    private static boolean active;
    private static Mode mode = Mode.MOVE;

    private static UUID selectedId;
    private static Display selected;
    private static Pose pose;
    private static Drag drag;
    /** true 면 드래그 중 회전/크기(transformation)도 클라이언트 엔티티에 실시간으로 반영한다. 문제가 생기면 false 로 */
    private static final boolean LIVE_TRANSFORM_PREVIEW = true;
    /** 드래그 중 클라이언트 엔티티에 마지막으로 반영한 포즈. null 이면 드래그 중이 아니다. */
    private static Pose previewed;

    private static Part hoverPart;
    private static Display hoverEntity;
    private static int syncTick;
    /** 마우스를 놓은 직후, 서버 반영이 오기 전에 옛 값을 다시 읽지 않도록 쉬는 틱 수 */
    private static int holdRefresh;
    /** 지난 틱 이후 render() 가 실행됐는지. (HUD 가 숨겨지면 render 가 호출되지 않는다) */
    private static boolean renderedSinceTick;
    private static int missingTicks;

    // ------------------------------------------------------------------
    // 진입 / 종료
    // ------------------------------------------------------------------

    static boolean isActive() {
        return active;
    }

    static Display selected() {
        return selected;
    }

    /** 이미 있는 디스플레이로 에디터 모드를 시작한다. */
    static void begin(Display target) {
        active = true;
        drag = null;
        missingTicks = 0;
        select(target);
    }

    /** 방금 summon 한 엔티티(UUID 만 알고 있음)로 시작한다. 클라이언트에 나타날 때까지 기다린다. */
    static void beginPending(UUID id) {
        active = true;
        drag = null;
        selected = null;
        pose = null;
        selectedId = id;
        missingTicks = 0;
    }

    static void exit() {
        if (drag != null && selected != null && pose != null) {
            // 드래그 도중에 끝나면 미리보기를 원래대로 되돌린다
            pose = drag.start;
            applyPreview(pose);
        }
        previewed = null;
        active = false;
        drag = null;
        selected = null;
        selectedId = null;
        pose = null;
        hoverPart = null;
        hoverEntity = null;
    }

    private static void select(Display target) {
        selected = target;
        selectedId = target.getUUID();
        refresh();
    }

    /** 엔티티의 현재 상태를 다시 읽는다. (UI 에서 적용한 값, 서버가 보정한 값을 반영) */
    private static void refresh() {
        if (selected == null) return;
        pose = readPose(EntityNbt.save(selected));
    }

    // ------------------------------------------------------------------
    // 틱: 입력 처리 (DisplayEditClient 의 START_CLIENT_TICK 에서 호출)
    // ------------------------------------------------------------------

    static void tick(Minecraft client, Player player, Display hovered) {
        if (!active) return;

        // ESC: 바닐라가 일시정지 메뉴를 먼저 열어 버리므로, 그걸 감지해서 에디터 모드를 끝낸다
        if (client.gui.screen() instanceof PauseScreen) {
            client.gui.setScreen(null);
            exit();
            DisplayEditClient.chat(client, Component.translatable("dee.chat.gizmo_exit"));
            return;
        }
        if (!player.isCreative()) {
            exit();
            return;
        }
        // UI / 인벤토리 등이 열려 있으면 입력은 그쪽 것. 닫히면 자동으로 에디터 모드로 돌아온다
        if (client.gui.screen() != null) {
            hoverEntity = null;
            return;
        }

        resolveSelected(client, player);
        if (!active) return;

        // 에디터 모드에서는 클릭이 바닐라 동작(공격/설치/아이템 사용)으로 가지 않게 전부 소비한다.
        // (블록 상호작용 자체도 DisplayEditClient 의 Fabric 콜백이 따로 막는다)
        boolean attackPressed = false;
        boolean usePressed = false;
        while (client.options.keyAttack.consumeClick()) attackPressed = true;
        while (client.options.keyUse.consumeClick()) usePressed = true;
        while (client.options.keyPickItem.consumeClick()) { /* 가운데 클릭 무시 */ }

        KeyMapping[] slots = client.options.keyHotbarSlots;
        for (int i = 0; i < slots.length; i++) {
            while (slots[i].consumeClick()) {
                if (i < Mode.values().length && drag == null) mode = Mode.values()[i];
            }
        }
        boolean attackHeld = client.options.keyAttack.isDown();

        hoverEntity = hovered;
        if (pose == null) {
            // 선택된 엔티티가 없거나 아직 클라이언트에 나타나지 않음. 바라보는 디스플레이는 고르거나 열 수 있다
            if (hovered != null && attackPressed) {
                select(hovered);
            } else if (hovered != null && usePressed) {
                select(hovered);
                DisplayEditClient.openEditorScreen(client, hovered);
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
            if (hoverPart != null) {
                beginDrag(client, v);
            } else if (hovered != null && hovered != selected) {
                select(hovered);
            }
        } else if (usePressed) {
            // 우클릭 -> 편집 UI. (G 키와 같은 용도)
            //  - 다른 디스플레이를 바라보고 있으면 그것을 선택하고 연다
            //  - 선택한 디스플레이의 핸들에 십자선이 걸려 있어도(엔티티를 바라보면 보통 핸들과 겹친다) 그 디스플레이를 연다
            if (hovered != null) {
                select(hovered);
                DisplayEditClient.openEditorScreen(client, hovered);
            } else if (hoverPart != null && selected != null) {
                DisplayEditClient.openEditorScreen(client, selected);
            }
        }
    }

    /**
     * 선택된 엔티티가 없어졌거나 아직 안 나타났으면 UUID 로 잠시(3초) 다시 찾는다.
     * 끝내 못 찾아도(삭제/kill 등) 에디터 모드는 유지하고, 선택만 비운다.
     * 선택이 없는 동안에는 다른 디스플레이를 클릭해서 고르거나 G 로 새로 만들 수 있다.
     */
    private static void resolveSelected(Minecraft client, Player player) {
        if (selected != null && !selected.isRemoved()) {
            missingTicks = 0;
            return;
        }
        selected = null;
        pose = null;
        drag = null;
        previewed = null;
        hoverPart = null;
        if (selectedId == null) return;
        final UUID id = selectedId;
        List<Display> found = client.level.getEntitiesOfClass(
                Display.class, player.getBoundingBox().inflate(64.0), d -> d.getUUID().equals(id));
        if (!found.isEmpty()) {
            missingTicks = 0;
            select(found.get(0));
        } else if (++missingTicks > 60) {
            selectedId = null;
            missingTicks = 0;
        }
    }

    // ------------------------------------------------------------------
    // 포즈 (위치 / 방향 / transformation)
    // ------------------------------------------------------------------

    private static final class Pose {
        Vec3 pos = Vec3.ZERO;
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

    private static double num(ListTag list, int i, double fallback) {
        return i < list.size() && list.get(i) instanceof NumericTag n ? n.doubleValue() : fallback;
    }

    private static Pose readPose(CompoundTag nbt) {
        Pose p = new Pose();
        ListTag pos = nbt.getListOrEmpty("Pos");
        p.pos = new Vec3(num(pos, 0, 0), num(pos, 1, 0), num(pos, 2, 0));
        ListTag rot = nbt.getListOrEmpty("Rotation");
        p.yaw = (float) num(rot, 0, 0);
        p.pitch = (float) num(rot, 1, 0);

        CompoundTag t = nbt.getCompoundOrEmpty("transformation");
        ListTag tr = t.getListOrEmpty("translation");
        p.translation.set((float) num(tr, 0, 0), (float) num(tr, 1, 0), (float) num(tr, 2, 0));
        ListTag sc = t.getListOrEmpty("scale");
        p.scale.set((float) num(sc, 0, 1), (float) num(sc, 1, 1), (float) num(sc, 2, 1));
        readQuat(t.getListOrEmpty("left_rotation"), p.left);
        readQuat(t.getListOrEmpty("right_rotation"), p.right);
        return p;
    }

    private static void readQuat(ListTag list, Quaternionf out) {
        if (list.size() == 4) {
            out.set((float) num(list, 0, 0), (float) num(list, 1, 0), (float) num(list, 2, 0), (float) num(list, 3, 1));
            if (out.lengthSquared() > 1e-8f) out.normalize();
            else out.identity();
        } else {
            out.identity(); // {axis, angle} 같은 입력용 형식은 저장된 NBT 에 나오지 않는다
        }
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float v : values) list.add(FloatTag.valueOf(v));
        return list;
    }

    private static CompoundTag transformationTag(Pose p) {
        CompoundTag t = new CompoundTag();
        t.put("left_rotation", floats(p.left.x, p.left.y, p.left.z, p.left.w));
        t.put("right_rotation", floats(p.right.x, p.right.y, p.right.z, p.right.w));
        t.put("translation", floats(p.translation.x, p.translation.y, p.translation.z));
        t.put("scale", floats(p.scale.x, p.scale.y, p.scale.z));
        return t;
    }

    private static boolean isBlock() {
        return selected instanceof Display.BlockDisplay;
    }

    /**
     * 기즈모가 놓이는 곳(월드 좌표).
     * block_display 는 블록의 중심(엔티티 위치가 모서리라서), 그 외에는 엔티티 위치(+translation).
     */
    private static Vec3 pivot(Pose p) {
        Vector3f local = new Vector3f(p.translation);
        if (isBlock()) {
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
    private static Vec3 axisWorld(Mode m, int i, Pose p) {
        Vector3f e = new Vector3f(i == 0 ? 1f : 0f, i == 1 ? 1f : 0f, i == 2 ? 1f : 0f);
        Vector3f out = new Vector3f(e);
        switch (m) {
            case ROTATE -> p.orientation().transform(e, out);
            case SCALE -> new Quaternionf(p.orientation()).mul(p.left).transform(e, out);
            default -> { }
        }
        return new Vec3(out.x, out.y, out.z).normalize();
    }

    // ------------------------------------------------------------------
    // 시선 / 투영
    // ------------------------------------------------------------------

    private record View(Vec3 eye, Vec3 dir, Vec3 right, Vec3 up, double tanHalf, int w, int h) {
        static View of(Minecraft client, Player player, float partial) {
            Vec3 eye = player.getEyePosition(partial);
            Vec3 dir = player.getViewVector(partial).normalize();
            double yaw = Math.toRadians(player.getViewYRot(partial));
            Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
            Vec3 up = right.cross(dir);
            // 설정의 FOV(세로). 달리기/비행으로 바뀌는 FOV 효과는 반영하지 않는다.
            double fov = client.options.fov().get();
            int w = client.getWindow().getGuiScaledWidth();
            int h = client.getWindow().getGuiScaledHeight();
            return new View(eye, dir, right, up, Math.tan(Math.toRadians(fov) / 2.0), w, h);
        }

        /** 화면 좌표 {x, y}. 카메라 뒤면 null. */
        double[] project(Vec3 p) {
            Vec3 rel = p.subtract(eye);
            double z = rel.dot(dir);
            if (z < NEAR) return null;
            double k = (h / 2.0) / (z * tanHalf);
            return new double[]{w / 2.0 + rel.dot(right) * k, h / 2.0 - rel.dot(up) * k};
        }

        double depth(Vec3 p) {
            return p.subtract(eye).dot(dir);
        }
    }

    /** 시선(직선)과 직선 O + t·a 가 가장 가까운 지점의 t. 거의 평행이면 NaN. */
    private static double lineParam(View v, Vec3 o, Vec3 a) {
        Vec3 w0 = o.subtract(v.eye);
        double b = a.dot(v.dir);
        double c = a.dot(w0);
        double e = v.dir.dot(w0);
        double den = 1.0 - b * b;
        if (den < 1e-4) return Double.NaN;
        return (b * e - c) / den;
    }

    /** 시선과 평면(point, normal)의 교점. 평행이거나 뒤쪽이면 null. */
    private static Vec3 rayPlane(View v, Vec3 point, Vec3 normal) {
        double denom = normal.dot(v.dir);
        if (Math.abs(denom) < 1e-4) return null;
        double s = point.subtract(v.eye).dot(normal) / denom;
        if (s <= 0) return null;
        return v.eye.add(v.dir.scale(s));
    }

    /** 점과 시선 사이 거리. 카메라 뒤면 무한대. */
    private static double rayPointDistance(View v, Vec3 p) {
        Vec3 rel = p.subtract(v.eye);
        double s = rel.dot(v.dir);
        if (s < 0) return Double.MAX_VALUE;
        return rel.subtract(v.dir.scale(s)).length();
    }

    private static Vec3[] basis(Vec3 axis) {
        Vec3 h = Math.abs(axis.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = axis.cross(h).normalize();
        Vec3 w = axis.cross(u).normalize();
        return new Vec3[]{u, w};
    }

    private static double angleOf(Vec3 rel, Vec3 axis) {
        Vec3[] b = basis(axis);
        return Math.atan2(rel.dot(b[1]), rel.dot(b[0]));
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
        Vec3 o = pivot(pose);
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
            Vec3 a = axisWorld(mode, i, pose);
            if (mode == Mode.ROTATE) {
                Vec3 hit = rayPlane(v, o, a);
                if (hit == null) continue;
                double err = Math.abs(hit.subtract(o).length() - len * 0.9);
                if (err < dist * 0.035 && err / dist < best) {
                    best = err / dist;
                    bestPart = Part.values()[i];
                }
            } else {
                double t = lineParam(v, o, a);
                if (Double.isNaN(t)) t = 0; // 평행이면 시작점 기준
                t = Mth.clamp(t, len * 0.15, len);
                double d = rayPointDistance(v, o.add(a.scale(t)));
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
        Vec3 pivot;
        Vec3 axis = Vec3.ZERO;
        Vector3f localAxis = new Vector3f();
        Vec3 normal = Vec3.ZERO;
        Vec3 grab = Vec3.ZERO;
        double t0;
        double lastAngle;
        double total;
        float pitch0;
        double arm;
        String readout = "";
    }

    private static void beginDrag(Minecraft client, View v) {
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
                    Vec3 hit = rayPlane(v, d.pivot, d.normal);
                    if (hit == null) return;
                    d.grab = hit;
                } else {
                    double t = lineParam(v, d.pivot, d.axis);
                    if (Double.isNaN(t)) return;
                    d.t0 = t;
                }
            }
            case ROTATE -> {
                Vec3 hit = rayPlane(v, d.pivot, d.axis);
                if (hit == null) return;
                d.lastAngle = angleOf(hit.subtract(d.pivot), d.axis);
            }
            case SCALE -> {
                if (part == Part.CENTER) {
                    d.pitch0 = client.player.getXRot();
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
    }

    private static double snapTo(double value, double step) {
        return Math.round(value / step) * step;
    }

    /** 시선에 맞춰 새 포즈를 계산하고 미리보기에 적용한다. (프레임마다 불린다) */
    private static void updateDrag(Minecraft client, View v) {
        Drag d = drag;
        if (d == null || selected == null || pose == null) return;
        boolean snap = client.options.keySprint.isDown(); // Ctrl
        Pose next = d.start.copy();

        switch (d.mode) {
            case MOVE -> {
                Vec3 delta;
                if (d.part == Part.CENTER) {
                    Vec3 hit = rayPlane(v, d.pivot, d.normal);
                    if (hit == null) return;
                    delta = hit.subtract(d.grab);
                } else {
                    double t = lineParam(v, d.pivot, d.axis);
                    if (Double.isNaN(t)) return;
                    delta = d.axis.scale(Mth.clamp(t - d.t0, -256.0, 256.0));
                }
                Vec3 np = d.start.pos.add(delta);
                if (snap) {
                    boolean all = d.part == Part.CENTER;
                    np = new Vec3(
                            all || Math.abs(d.axis.x) > 0.5 ? snapTo(np.x, 0.25) : np.x,
                            all || Math.abs(d.axis.y) > 0.5 ? snapTo(np.y, 0.25) : np.y,
                            all || Math.abs(d.axis.z) > 0.5 ? snapTo(np.z, 0.25) : np.z);
                }
                next.pos = np;
                Vec3 moved = np.subtract(d.start.pos);
                d.readout = String.format(Locale.ROOT, "Δ %.2f  %.2f  %.2f", moved.x, moved.y, moved.z);
            }
            case ROTATE -> {
                Vec3 hit = rayPlane(v, d.pivot, d.axis);
                if (hit == null) return;
                double ang = angleOf(hit.subtract(d.pivot), d.axis);
                d.total += wrap(ang - d.lastAngle);
                d.lastAngle = ang;
                double a = d.total;
                if (snap) a = snapTo(a, Math.toRadians(15.0));
                Quaternionf delta = new Quaternionf().rotationAxis((float) a, d.localAxis.x, d.localAxis.y, d.localAxis.z);
                delta.mul(d.start.left, next.left).normalize();
                keepPivot(next, d.start);
                d.readout = String.format(Locale.ROOT, "%.1f°", Math.toDegrees(a));
            }
            case SCALE -> {
                double f;
                if (d.part == Part.CENTER) {
                    // 시선을 위로 올리면 커지고 내리면 작아진다
                    f = Math.exp(-(client.player.getXRot() - d.pitch0) * 0.03);
                } else {
                    double t = lineParam(v, d.pivot, d.axis);
                    if (Double.isNaN(t)) return;
                    f = t / d.t0;
                }
                f = Mth.clamp(f, 0.01, 100.0);
                for (int i = 0; i < 3; i++) {
                    if (d.part != Part.CENTER && d.part.ordinal() != i) continue;
                    float s = d.start.scale.get(i);
                    double value = Math.abs(s) * f;
                    if (snap) value = Math.max(0.0625, snapTo(value, 0.125));
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
        pose = drag.start;
        applyPreview(pose);
        previewed = null;
        drag = null;
    }

    private static void finishDrag(Minecraft client) {
        Drag d = drag;
        drag = null;
        previewed = null;
        if (d == null || selected == null || pose == null) return;
        commit(client, d.start, pose);
        holdRefresh = 10;
    }

    private static boolean sameTransform(Pose a, Pose b) {
        return a.translation.equals(b.translation) && a.scale.equals(b.scale)
                && a.left.equals(b.left) && a.right.equals(b.right);
    }

    /** Display 의 private setTransformation(Transformation). 못 찾으면 null 이고 실시간 미리보기는 꺼진다. */
    private static Method setTransformation;
    private static boolean transformationLookedUp;

    /**
     * (text_display 전용) transformation 만 클라이언트 엔티티의 동기화 데이터에 직접 넣는다. (NBT 를 save/load 하지 않는다)
     * NBT 를 통째로 다시 load 하면 text 값이 비어 버리기 때문이다.
     * 메서드 이름 대신 "파라미터가 Transformation 하나인 Display 의 메서드"로 찾는다.
     */
    private static boolean applyTransformation(Display e, Pose p) {
        if (!transformationLookedUp) {
            transformationLookedUp = true;
            for (Method m : Display.class.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0] == Transformation.class && m.getReturnType() == void.class) {
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
            setTransformation.invoke(e, new Transformation(
                    new Vector3f(p.translation), new Quaternionf(p.left),
                    new Vector3f(p.scale), new Quaternionf(p.right)));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            setTransformation = null; // 한 번 실패하면 더는 시도하지 않는다
            return false;
        }
    }

    /**
     * 드래그 중 미리보기. (클라이언트 엔티티에만 적용되고, 서버에는 마우스를 놓을 때 명령으로 간다)
     * - 위치: setPos
     * - 회전/크기 (transformation 이 바뀐 프레임에만 실행):
     *   · text_display: transformation 만 직접 설정. (NBT 를 다시 load 하면 text 가 비어 버린다)
     *   · block_display / item_display: 현재 NBT 를 읽어 transformation 만 바꿔서 다시 load
     */
    private static void applyPreview(Pose p) {
        if (selected == null) return;
        if (LIVE_TRANSFORM_PREVIEW && previewed != null && !sameTransform(p, previewed)) {
            if (selected instanceof Display.TextDisplay) {
                if (applyTransformation(selected, p)) previewed = p.copy();
            } else {
                try {
                    CompoundTag nbt = EntityNbt.save(selected);
                    nbt.put("transformation", transformationTag(p));
                    EntityNbt.load(selected, nbt);
                    previewed = p.copy();
                } catch (RuntimeException ignored) {
                    // 미리보기가 실패해도 기즈모와 수치 표시, 확정 시 적용은 그대로 동작한다
                }
            }
        }
        selected.setPos(p.pos.x, p.pos.y, p.pos.z);
    }

    /** 바뀐 것만 명령으로 보낸다: 위치 -> tp, 회전/크기 -> data merge entity (transformation). */
    private static void commit(Minecraft client, Pose from, Pose to) {
        var handler = client.getConnection();
        if (handler == null || selected == null) return;
        String id = selected.getStringUUID();

        if (!to.pos.equals(from.pos)) {
            handler.sendCommand(String.format(Locale.ROOT, "tp %s %.5f %.5f %.5f", id, to.pos.x, to.pos.y, to.pos.z));
        }
        boolean transformChanged = !to.translation.equals(from.translation) || !to.scale.equals(from.scale)
                || !to.left.equals(from.left) || !to.right.equals(from.right);
        if (transformChanged) {
            handler.sendCommand("data merge entity " + id + " {transformation:" + transformationTag(to) + "}");
        }
    }

    // ------------------------------------------------------------------
    // HUD 렌더링 (DisplayEditClient 가 HudElementRegistry 로 연결)
    // ------------------------------------------------------------------

    static void render(Minecraft client, GuiGraphicsExtractor g, float partial) {
        if (!active || client.player == null || client.level == null) return;
        if (client.gui.screen() != null) return;
        renderedSinceTick = true;

        View v = View.of(client, client.player, partial);
        if (drag != null) {
            updateDrag(client, v);
        } else if (pose != null) {
            hoverPart = pickPart(v);
        }

        if (pose != null) drawGizmo(g, v);

        // 마우스를 올려 둔 다른 디스플레이 표시
        if (drag == null && hoverPart == null && hoverEntity != null && hoverEntity != selected) {
            double[] s = v.project(hoverEntity.position());
            if (s != null) box(g, (int) s[0], (int) s[1], 5, COLOR_HOT);
            g.centeredText(client.font, Component.translatable("dee.gizmo.hover", keyName("key.attack"), keyName("key.dee.open_editor"), keyName("key.use")), v.w / 2, v.h / 2 + 14, 0xFFFFE066);
        }

        // 상단 안내
        Component modeName = Component.translatable("dee.gizmo.mode." + mode.name().toLowerCase(Locale.ROOT));
        g.centeredText(client.font, Component.translatable("dee.gizmo.title", modeName), v.w / 2, 6, 0xFFFFFFFF);
        g.centeredText(client.font, Component.translatable("dee.gizmo.help",
                keyName("key.hotbar.1"), keyName("key.hotbar.2"), keyName("key.hotbar.3"),
                keyName("key.attack"), keyName("key.use"), keyName("key.sprint"),
                keyName("key.dee.open_editor")), v.w / 2, 18, 0xFFA0A0A0);
        if (drag != null) {
            g.centeredText(client.font, Component.literal(drag.readout), v.w / 2, v.h / 2 + 14, 0xFFFFE066);
        }
    }

    /** 안내 문구에 넣을 키 이름. 사용자가 바꾼 키 설정이 그대로 나온다. (언어팩에 키를 직접 쓰지 않기 위함) */
    private static Component keyName(String keyMappingName) {
        return Component.keybind(keyMappingName).copy().withStyle(style -> style.withColor(0xFFFFFF));
    }

    private static void drawGizmo(GuiGraphicsExtractor g, View v) {
        Vec3 o = pivot(pose);
        double dist = Math.max(0.5, v.eye.distanceTo(o));
        double len = dist * ARM;
        Part hot = drag != null ? drag.part : hoverPart;

        if (mode == Mode.ROTATE) {
            for (int i = 0; i < 3; i++) {
                Vec3 a = axisWorld(mode, i, pose);
                Vec3[] b = basis(a);
                int color = hot == Part.values()[i] ? COLOR_HOT : AXIS_COLOR[i];
                final int n = 48;
                Vec3 prev = null;
                for (int k = 0; k <= n; k++) {
                    double ang = 2 * Math.PI * k / n;
                    Vec3 p = o.add(b[0].scale(Math.cos(ang) * len * 0.9)).add(b[1].scale(Math.sin(ang) * len * 0.9));
                    if (prev != null) worldLine(g, v, prev, p, color, 2);
                    prev = p;
                }
            }
        } else {
            for (int i = 0; i < 3; i++) {
                Vec3 a = axisWorld(mode, i, pose);
                Vec3 tip = o.add(a.scale(len));
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
    private static void worldLine(GuiGraphicsExtractor g, View v, Vec3 p0, Vec3 p1, int color, int thickness) {
        double z0 = v.depth(p0);
        double z1 = v.depth(p1);
        if (z0 < NEAR && z1 < NEAR) return;
        if (z0 < NEAR) p0 = p0.add(p1.subtract(p0).scale((NEAR - z0) / (z1 - z0)));
        if (z1 < NEAR) p1 = p1.add(p0.subtract(p1).scale((NEAR - z1) / (z0 - z1)));
        double[] a = v.project(p0);
        double[] b = v.project(p1);
        if (a == null || b == null) return;

        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        int steps = (int) Math.min(Math.max(Math.abs(dx), Math.abs(dy)), 1500);
        if (steps <= 0) {
            g.fill((int) a[0], (int) a[1], (int) a[0] + thickness, (int) a[1] + thickness, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = (int) Math.round(a[0] + dx * i / steps);
            int y = (int) Math.round(a[1] + dy * i / steps);
            g.fill(x, y, x + thickness, y + thickness, color);
        }
    }

    private static void fillBox(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        g.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, color);
    }

    private static void box(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        g.fill(cx - r, cy - r, cx + r + 1, cy - r + 1, color);
        g.fill(cx - r, cy + r, cx + r + 1, cy + r + 1, color);
        g.fill(cx - r, cy - r, cx - r + 1, cy + r + 1, color);
        g.fill(cx + r, cy - r, cx + r + 1, cy + r + 1, color);
    }
}