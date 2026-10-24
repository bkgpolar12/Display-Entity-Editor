package com.dee.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display.BlockDisplay;
import net.minecraft.world.entity.Display.ItemDisplay;
import net.minecraft.world.entity.Display.TextDisplay;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 클라이언트 전용 진입점. 서버에 모드가 없어도 동작한다. */
public class DisplayEditClient implements ClientModInitializer {
    /** 선택 가능 거리 */
    private static final double REACH = 8.0;
    /** 텍스트 영역 가장자리에 더해 주는 여유 (작은 텍스트도 쉽게 맞추기 위함) */
    private static final double MARGIN = 0.2;
    /** true 면 채팅/액션바에 진단 메시지를 띄운다. 문제 해결되면 false 로 */
    private static final boolean DEBUG = true;
    /** 텍스트 크기 계산 결과를 재사용하는 틱 수 */
    private static final int CACHE_TICKS = 10;

    /** 화면상 텍스트 영역(대략). center 는 월드 좌표, half* 는 여유 포함 절반 크기 */
    private record Visual(Vec3 center, double halfW, double halfH, long time) {}

    private final Map<Integer, Visual> visualCache = new HashMap<>();
    private static final int VISUAL_BUDGET_PER_TICK = 12;
    private int visualBudget;

    private static KeyMapping OPEN_EDITOR;
    /** 에디터 모드에서 누른 채 클릭하면 선택에 추가/제거. 기본값은 왼쪽 Ctrl 이고 조작 설정에서 바꿀 수 있다. */
    static KeyMapping MULTI_SELECT;

    /** "내 위치로" 지정을 기다리는 편집 초안. null 이면 대기 중이 아니다. */
    private static Draft pendingPlacement;

    /**
     * 편집 화면의 위치 지정 버튼이 호출한다.
     * UI 를 닫고 안내를 띄운 뒤, 에디터 열기 키를 누르면 그 자리의 위치가 초안에 들어간다. (ESC 로 취소)
     */
    public static void beginPlacement(Minecraft client, Draft draft) {
        pendingPlacement = draft;
        client.gui.setScreen(null);
        Component key = OPEN_EDITOR.getTranslatedKeyMessage().copy().withStyle(style -> style.withColor(0xFFFF55));
        chat(client, Component.translatable("dee.chat.place_hint", key));
    }

    /**
     * 새로 만들기 화면의 [에디터 모드] 버튼이 호출한다.
     * 초안으로 엔티티를 "지금" summon 하고(UUID 를 미리 정해 둔다), 그 엔티티로 에디터 모드를 시작한다.
     * 이후에는 평범한 기존 엔티티와 같다. (UI 로 고치거나 삭제 가능)
     */
    public static void beginEditorFromDraft(Minecraft client, Draft draft) {
        var handler = client.getConnection();
        if (handler == null) return;
        UUID id = UUID.randomUUID();
        handler.sendCommand(draft.buildSummonCommand(id));
        client.gui.setScreen(null);
        GizmoEditor.beginPending(id);
        chat(client, Component.translatable("dee.chat.gizmo_enter"));
    }

    /** 에디터 모드가 켜진 동안 블록/아이템/엔티티 상호작용을 막을지. (클라이언트 쪽에서만) */
    private static boolean blocked(Level level) {
        return level.isClientSide() && GizmoEditor.isActive();
    }

    @Override
    public void onInitializeClient() {
        // 카테고리 번역 키: key.category.dee.main (lang 파일 참고)
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(DisplayEditMod.MOD_ID, "main"));

        // 26.3: GLFW 대신 SDL. InputConstants.Type.KEYSYM -> KEYBOARD, GLFW.GLFW_KEY_G -> InputConstants.KEY_G
        OPEN_EDITOR = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.dee.open_editor",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_G,
                category
        ));
        MULTI_SELECT = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.dee.multi_select",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_LCONTROL,
                category
        ));

        // 빈손으로 허공을 우클릭하면 UseItemCallback 이 호출되지 않고, 디스플레이 엔티티는
        // 히트박스가 없어 UseEntityCallback 도 호출되지 않는다. 그래서 틱에서 우클릭 키를 직접 확인한다.
        // START_CLIENT_TICK 은 바닐라 입력 처리보다 먼저 실행되므로 여기서 키 입력을 소비하면 기본 동작이 막힌다.
        ClientTickEvents.START_CLIENT_TICK.register(this::onTick);

        // 기즈모는 HUD 에 그린다. (월드 렌더 API 는 버전마다 크게 바뀌어서 HUD API 만 사용)
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(DisplayEditMod.MOD_ID, "gizmo"),
                (graphics, deltaTracker) -> GizmoEditor.render(
                        Minecraft.getInstance(), graphics, deltaTracker.getGameTimeDeltaPartialTick(false)));

        // 에디터 모드에서는 모든 블록 상호작용 차단. (크리에이티브에서 좌클릭을 누르고 있으면 블록이 연속으로
        // 부서지는데, 그것도 attack 콜백이 막는다.) 클릭 키 자체는 GizmoEditor.tick 이 소비한다.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                blocked(level) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                blocked(level) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) ->
                blocked(level) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                blocked(level) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                blocked(level) ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    private void onTick(Minecraft client) {
        GizmoEditor.flush(client); // 여러 엔티티를 놓을 때 쌓아 둔 명령을 조금씩 보낸다
        Player player = client.player;
        if (player == null || client.level == null) {
            visualCache.clear();
            pendingPlacement = null;
            EditorHistory.clear(); // 월드를 나가면 되돌리기 기록도 버린다
            GizmoEditor.exit();
            return;
        }

        // 위치 지정 대기 중에는 다른 입력(Shift+우클릭, 새로 만들기)을 모두 막는다
        if (pendingPlacement != null) {
            handlePlacement(client, player);
            return;
        }

        // 에디터 모드: 입력은 전부 GizmoEditor 가 처리한다 (ESC 종료, 핸들 드래그, 우클릭 UI 등)
        if (GizmoEditor.isActive()) {
            boolean noScreen = client.gui.screen() == null;
            Display hovered = noScreen ? pick(client, player) : null;
            GizmoEditor.tick(client, player, hovered);

            // 에디터 모드 중 G
            //  - 바라보는 디스플레이가 있으면: 그 디스플레이의 편집 UI (선택해 둔 것은 상관없음)
            //  - 없으면: 새로 만들기 UI
            if (GizmoEditor.isActive() && noScreen && client.gui.screen() == null) {
                if (OPEN_EDITOR.consumeClick()) {
                    while (OPEN_EDITOR.consumeClick()) { /* 남은 눌림 소비 */ }
                    if (EditorShortcuts.ctrlDown(client)) {
                        // Ctrl+G 는 그룹 단축키(EditorShortcuts 가 처리)라서 편집 UI 는 열지 않는다
                    } else if (hovered != null) {
                        // 이미 선택된 것이면 선택 전체의 파트 목록과 함께, 아니면 이것(또는 그 그룹)만 선택하고 연다
                        GizmoEditor.openFor(client, hovered);
                    } else {
                        Vec3 p = player.position();
                        client.gui.setScreen(new DisplayEditScreen(Draft.forNew(p.x, p.y, p.z)));
                    }
                }
            }
            return;
        }

        boolean creative = client.gui.screen() == null && player.isCreative();
        boolean ready = creative && player.isShiftKeyDown();
        Display target = ready ? pick(client, player) : null;

        // Shift + 우클릭: 에디터 모드를 거치지 않고 바로 편집 UI (기존 방식 유지)
        if (target != null) {
            if (client.options.keyUse.consumeClick()) {
                // 같은 틱에 바닐라가 우클릭을 한 번 더 처리하지 않도록 눌림 상태 해제 (블록 설치 등 차단)
                client.options.keyUse.setDown(false);
                openEditorScreen(client, target);
                return;
            }
        } else if (DEBUG && ready && client.options.keyUse.isDown() && player.tickCount % 20 == 0) {
            player.sendOverlayMessage(prefixed(Component.translatable("dee.chat.not_found")));
        }

        // 키바인드 (웅크리지 않아도 됨)
        //  - 바라보는 디스플레이가 있으면: UI 없이 바로 에디터 모드
        //  - 없으면: 새로 만들기 UI (거기서 [에디터 모드] 버튼으로 진입)
        // 눌림은 항상 소비한다.
        while (OPEN_EDITOR.consumeClick()) {
            if (!creative) {
                chat(client, Component.translatable("dee.chat.creative_only"));
                continue;
            }
            Display t = target != null ? target : pick(client, player);
            if (t != null) {
                GizmoEditor.begin(t);
                chat(client, Component.translatable("dee.chat.gizmo_enter"));
                return;
            }
            Vec3 p = player.position();
            client.gui.setScreen(new DisplayEditScreen(Draft.forNew(p.x, p.y, p.z)));
            return;
        }
    }

    /**
     * 위치 지정 대기 처리.
     * - ESC: 바닐라가 일시정지 메뉴를 먼저 열어 버리므로, 그 메뉴를 감지해 편집기로 되돌린다.
     * - 에디터 열기 키: 지금 서 있는 위치를 초안에 넣고 편집기로 돌아간다.
     */
    private void handlePlacement(Minecraft client, Player player) {
        Draft draft = pendingPlacement;

        if (client.gui.screen() instanceof PauseScreen) {
            pendingPlacement = null;
            client.gui.setScreen(new DisplayEditScreen(draft));
            chat(client, Component.translatable("dee.chat.place_cancel"));
            return;
        }

        // 인벤토리 등 다른 화면이 열려 있으면 그대로 대기
        if (client.gui.screen() != null) return;

        if (OPEN_EDITOR.consumeClick()) {
            while (OPEN_EDITOR.consumeClick()) { /* 남은 눌림 소비 */ }
            Vec3 p = player.position();
            draft.setPos(p.x, p.y, p.z);
            pendingPlacement = null;
            client.gui.setScreen(new DisplayEditScreen(draft));
            chat(client, Component.translatable("dee.chat.place_done",
                    String.format(Locale.ROOT, "%.2f", p.x), String.format(Locale.ROOT, "%.2f", p.y), String.format(Locale.ROOT, "%.2f", p.z)));
        }
    }

    /** (공통) 서버 없이 클라이언트 엔티티의 현재 NBT 를 전부 읽어 Draft 로 만들고 편집 화면을 연다. */
    static void openEditorScreen(Minecraft client, Display target) {
        CompoundTag nbt = EntityNbt.save(target);
        mergeServerTags(client, target, nbt);
        Draft.Type type = target instanceof BlockDisplay ? Draft.Type.BLOCK
                : target instanceof ItemDisplay ? Draft.Type.ITEM : Draft.Type.TEXT;
        client.gui.setScreen(new DisplayEditScreen(new Draft(target.getStringUUID(), nbt, type)));
    }

    /**
     * 스코어보드 태그(Tags)는 서버에만 있고 클라이언트 엔티티로 동기화되지 않는다.
     * 싱글플레이(통합 서버)에서는 서버 쪽 같은 엔티티에서 읽어 와서 nbt 에 넣는다.
     * 멀티플레이에서는 읽을 수 없으므로 그대로 둔다. (적용은 /tag add|remove 라서 기존 태그는 지워지지 않는다)
     */
    private static void mergeServerTags(Minecraft client, Display target, CompoundTag nbt) {
        var server = client.getSingleplayerServer();
        if (server == null) return;
        try {
            for (ServerLevel level : server.getAllLevels()) {
                Entity serverEntity = level.getEntity(target.getUUID());
                if (serverEntity == null) continue;
                Tag tags = EntityNbt.save(serverEntity).get("Tags");
                if (tags != null) nbt.put("Tags", tags);
                return;
            }
        } catch (RuntimeException ignored) {
            // 서버 스레드와 겹쳐 읽다 실패해도 편집은 계속할 수 있다
        }
    }

    private static Component prefixed(Component message) {
        return message;
    }

    static void chat(Minecraft client, Component message) {
        if (client.player != null) client.player.sendSystemMessage(prefixed(message));
    }

    // ------------------------------------------------------------------
    // 타겟 선택
    // ------------------------------------------------------------------

    /**
     * 디스플레이 엔티티는 히트박스가 없어 일반 크로스헤어 타겟팅이 안 된다.
     * 그래서 각 text_display / block_display 의 화면상 텍스트 영역을 계산해서, 시선이 그 영역에 가장 가깝게 지나가는 것을 고른다.
     */
    private Display pick(Minecraft client, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getViewVector(1.0f);

        // 시선 앞에 블록이 있으면 그 뒤의 엔티티는 무시
        HitResult blockHit = player.pick(REACH, 1.0f, false);
        double blockDist = blockHit.getType() == HitResult.Type.MISS
                ? Double.MAX_VALUE : blockHit.getLocation().distanceTo(eye);

        AABB area = player.getBoundingBox().inflate(REACH + 4.0);
        visualBudget = VISUAL_BUDGET_PER_TICK;
        long now = client.level.getGameTime();

        Display best = null;
        double bestScore = Double.MAX_VALUE;
        for (Display e : client.level.getEntitiesOfClass(Display.class, area, x -> x instanceof TextDisplay || x instanceof BlockDisplay || x instanceof ItemDisplay)) {
            Visual v = visualOf(client, e, now);
            if (v == null) continue;

            Vec3 rel = v.center().subtract(eye);
            double along = rel.dot(dir);
            if (along < 0 || along > REACH + v.halfW() || along > blockDist + 0.5) continue;

            // 시선 직선에서 영역 중심까지의 어긋남 (세로/가로를 영역 크기로 나눠 비교)
            Vec3 off = rel.subtract(dir.scale(along));
            double score = Math.max(Math.abs(off.y) / v.halfH(), Math.hypot(off.x, off.z) / v.halfW());
            if (score <= 1.0 && score < bestScore) {
                best = e;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * 선택 영역 계산. (회전은 무시한 근사)
     * - text_display: 엔티티 위치에서 위로 자라고(바닥이 위치), 글자 1픽셀 = 0.025 블록 × transformation.scale
     * - block_display: 엔티티 위치(모서리)에서 +x/+y/+z 방향으로 scale 만큼 자란다
     */
    private Visual visualOf(Minecraft client, Display e, long now) {
        Visual cached = visualCache.get(e.getId());
        // 엔티티마다 만료 시각을 어긋나게 해서(지터) 한 틱에 전부 다시 계산하는 일을 막는다
        if (cached != null && now - cached.time() < CACHE_TICKS + (e.getId() & 7)) return cached;
        // 틱마다 새로 읽는 수를 제한한다. 넘으면 이전 값(없으면 null)을 쓰고 다음 틱에 이어서 한다
        if (visualBudget <= 0) return cached;
        visualBudget--;
        if (visualCache.size() > 4096) visualCache.clear();

        CompoundTag nbt = EntityNbt.save(e);
        CompoundTag transformation = nbt.getCompoundOrEmpty("transformation");
        ListTag scale = transformation.getListOrEmpty("scale");
        ListTag translation = transformation.getListOrEmpty("translation");
        double tx = component(translation, 0, 0f);
        double ty = component(translation, 1, 0f);
        double tz = component(translation, 2, 0f);

        Visual visual;
        if (e instanceof BlockDisplay) {
            double sx = component(scale, 0, 1f);
            double sy = component(scale, 1, 1f);
            double sz = component(scale, 2, 1f);
            // 음수 scale 도 부호 그대로 더하면 중심이 맞는다
            Vec3 center = e.position().add(tx + sx / 2, ty + sy / 2, tz + sz / 2);
            visual = new Visual(center,
                    Math.max(Math.abs(sx), Math.abs(sz)) / 2 + MARGIN, Math.abs(sy) / 2 + MARGIN, now);
        } else if (e instanceof ItemDisplay) {
            // item_display: 아이템 모델이 엔티티 위치를 중심으로 scale 만큼 퍼진다
            double sx = Math.abs(component(scale, 0, 1f));
            double sy = Math.abs(component(scale, 1, 1f));
            double sz = Math.abs(component(scale, 2, 1f));
            Vec3 center = e.position().add(tx, ty, tz);
            visual = new Visual(center, Math.max(sx, sz) / 2 + MARGIN, sy / 2 + MARGIN, now);
        } else {
            Tag textElement = nbt.get("text");
            Component text = textElement == null
                    ? Component.empty()
                    : ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, textElement).result().orElse(Component.empty());

            List<FormattedCharSequence> lines = client.font.split(text, Math.max(1, nbt.getIntOr("line_width", 200)));
            int widthPx = 0;
            for (FormattedCharSequence line : lines) widthPx = Math.max(widthPx, client.font.width(line));
            int heightPx = Math.max(1, lines.size()) * (client.font.lineHeight + 1);

            double sx = Math.abs(component(scale, 0, 1f));
            double sy = Math.abs(component(scale, 1, 1f));
            double w = widthPx * 0.025 * sx;
            double h = heightPx * 0.025 * sy;

            Vec3 center = e.position().add(tx, ty, tz).add(0, h / 2, 0);
            visual = new Visual(center, w / 2 + MARGIN, h / 2 + MARGIN, now);
        }
        visualCache.put(e.getId(), visual);
        return visual;
    }

    private static float component(ListTag list, int index, float fallback) {
        return index < list.size() && list.get(index) instanceof NumericTag n ? n.floatValue() : fallback;
    }
}