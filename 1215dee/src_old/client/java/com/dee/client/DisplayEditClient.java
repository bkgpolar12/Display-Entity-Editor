package com.dee.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private record Visual(Vec3d center, double halfW, double halfH, long time) {}

    private final Map<Integer, Visual> visualCache = new HashMap<>();

    private static KeyBinding OPEN_EDITOR;

    /** "내 위치로" 지정을 기다리는 편집 초안. null 이면 대기 중이 아니다. */
    private static Draft pendingPlacement;

    /**
     * 편집 화면의 위치 지정 버튼이 호출한다.
     * UI 를 닫고 안내를 띄운 뒤, 에디터 열기 키를 누르면 그 자리의 위치가 초안에 들어간다. (ESC 로 취소)
     */
    public static void beginPlacement(MinecraftClient client, Draft draft) {
        pendingPlacement = draft;
        client.setScreen(null);
        Text key = OPEN_EDITOR.getBoundKeyLocalizedText().copy().formatted(Formatting.YELLOW);
        chat(client, Text.translatable("dee.chat.place_hint", key));
    }

    @Override
    public void onInitializeClient() {
        OPEN_EDITOR = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.dee.open_editor",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.dee"
        ));

        // 빈손으로 허공을 우클릭하면 UseItemCallback 이 호출되지 않고, 디스플레이 엔티티는
        // 히트박스가 없어 UseEntityCallback 도 호출되지 않는다. 그래서 틱에서 우클릭 키를 직접 확인한다.
        // START_CLIENT_TICK 은 바닐라 입력 처리보다 먼저 실행되므로 여기서 키 입력을 소비하면 기본 동작이 막힌다.
        ClientTickEvents.START_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        PlayerEntity player = client.player;
        if (player == null || client.world == null) {
            visualCache.clear();
            pendingPlacement = null;
            return;
        }

        // 위치 지정 대기 중에는 다른 입력(Shift+우클릭, 새로 만들기)을 모두 막는다
        if (pendingPlacement != null) {
            handlePlacement(client, player);
            return;
        }

        boolean creative = client.currentScreen == null && player.isCreative();
        boolean ready = creative && player.isSneaking();
        TextDisplayEntity target = ready ? pick(client, player) : null;

        // Shift + 우클릭
        if (target != null) {
            if (client.options.useKey.wasPressed()) {
                // 같은 틱에 바닐라가 우클릭을 한 번 더 처리하지 않도록 눌림 상태 해제 (블록 설치 등 차단)
                client.options.useKey.setPressed(false);
                openEditor(client, target);
                return;
            }
        } else if (DEBUG && ready && client.options.useKey.isPressed() && player.age % 20 == 0) {
            player.sendMessage(prefixed(Text.translatable("dee.chat.not_found")), true);
        }

        // 키바인드 (웅크리지 않아도 됨): 바라보는 text_display 가 있으면 수정, 없으면 새로 만들기.
        // 눌림은 항상 소비한다.
        while (OPEN_EDITOR.wasPressed()) {
            if (!creative) {
                chat(client, Text.translatable("dee.chat.creative_only"));
                continue;
            }
            TextDisplayEntity t = target != null ? target : pick(client, player);
            if (t != null) {
                openEditor(client, t);
                return;
            }
            Vec3d p = player.getPos();
            client.setScreen(new TextDisplayScreen(Draft.forNew(p.x, p.y, p.z)));
            return;
        }
    }

    /**
     * 위치 지정 대기 처리.
     * - ESC: 바닐라가 일시정지 메뉴를 먼저 열어 버리므로, 그 메뉴를 감지해 편집기로 되돌린다.
     * - 에디터 열기 키: 지금 서 있는 위치를 초안에 넣고 편집기로 돌아간다.
     */
    private void handlePlacement(MinecraftClient client, PlayerEntity player) {
        Draft draft = pendingPlacement;

        if (client.currentScreen instanceof GameMenuScreen) {
            pendingPlacement = null;
            client.setScreen(new TextDisplayScreen(draft));
            chat(client, Text.translatable("dee.chat.place_cancel"));
            return;
        }

        // 인벤토리 등 다른 화면이 열려 있으면 그대로 대기
        if (client.currentScreen != null) return;

        if (OPEN_EDITOR.wasPressed()) {
            while (OPEN_EDITOR.wasPressed()) { /* 남은 눌림 소비 */ }
            Vec3d p = player.getPos();
            draft.setPos(p.x, p.y, p.z);
            pendingPlacement = null;
            client.setScreen(new TextDisplayScreen(draft));
            chat(client, Text.translatable("dee.chat.place_done",
                    String.format(Locale.ROOT, "%.2f", p.x), String.format(Locale.ROOT, "%.2f", p.y), String.format(Locale.ROOT, "%.2f", p.z)));
        }
    }

    /** 서버 없이 클라이언트 엔티티의 현재 NBT 를 전부 읽어 Draft 로 만들고 화면을 연다. */
    private void openEditor(MinecraftClient client, TextDisplayEntity target) {
        NbtCompound nbt = new NbtCompound();
        target.writeNbt(nbt);
        client.setScreen(new TextDisplayScreen(new Draft(target.getUuidAsString(), nbt)));
        // if (DEBUG) chat(client, Text.translatable("dee.chat.opened", target.getUuidAsString()));
    }

    private static Text prefixed(Text message) {
        return Text.literal("[DEE] ").append(message);
    }

    private static void chat(MinecraftClient client, Text message) {
        if (client.player != null) client.player.sendMessage(prefixed(message), false);
    }

    // ------------------------------------------------------------------
    // 타겟 선택
    // ------------------------------------------------------------------

    /**
     * 디스플레이 엔티티는 히트박스가 없어 일반 크로스헤어 타겟팅이 안 된다.
     * 그래서 각 text_display 의 화면상 텍스트 영역을 계산해서, 시선이 그 영역에 가장 가깝게 지나가는 것을 고른다.
     */
    private TextDisplayEntity pick(MinecraftClient client, PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d dir = player.getRotationVec(1.0f);

        // 시선 앞에 블록이 있으면 그 뒤의 엔티티는 무시
        HitResult blockHit = player.raycast(REACH, 1.0f, false);
        double blockDist = blockHit.getType() == HitResult.Type.MISS
                ? Double.MAX_VALUE : blockHit.getPos().distanceTo(eye);

        Box area = player.getBoundingBox().expand(REACH + 4.0);
        long now = client.world.getTime();

        TextDisplayEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (TextDisplayEntity e : client.world.getEntitiesByClass(TextDisplayEntity.class, area, x -> true)) {
            Visual v = visualOf(client, e, now);

            Vec3d rel = v.center().subtract(eye);
            double along = rel.dotProduct(dir);
            if (along < 0 || along > REACH + v.halfW() || along > blockDist + 0.5) continue;

            // 시선 직선에서 영역 중심까지의 어긋남 (세로/가로를 영역 크기로 나눠 비교)
            Vec3d off = rel.subtract(dir.multiply(along));
            double score = Math.max(Math.abs(off.y) / v.halfH(), Math.hypot(off.x, off.z) / v.halfW());
            if (score <= 1.0 && score < bestScore) {
                best = e;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * 텍스트 영역 계산. text_display 는 엔티티 위치에서 위로 자라고(바닥이 위치),
     * 글자 1픽셀 = 0.025 블록 × transformation.scale 이다. (회전은 무시한 근사)
     */
    private Visual visualOf(MinecraftClient client, TextDisplayEntity e, long now) {
        Visual cached = visualCache.get(e.getId());
        if (cached != null && now - cached.time() < CACHE_TICKS) return cached;
        if (visualCache.size() > 512) visualCache.clear();

        NbtCompound nbt = new NbtCompound();
        e.writeNbt(nbt);

        NbtElement textElement = nbt.get("text");
        Text text = textElement == null
                ? Text.empty()
                : TextCodecs.CODEC.parse(NbtOps.INSTANCE, textElement).result().orElse(Text.empty());

        List<OrderedText> lines = client.textRenderer.wrapLines(text, Math.max(1, nbt.getInt("line_width", 200)));
        int widthPx = 0;
        for (OrderedText line : lines) widthPx = Math.max(widthPx, client.textRenderer.getWidth(line));
        int heightPx = Math.max(1, lines.size()) * (client.textRenderer.fontHeight + 1);

        NbtCompound transformation = nbt.getCompoundOrEmpty("transformation");
        NbtList scale = transformation.getListOrEmpty("scale");
        NbtList translation = transformation.getListOrEmpty("translation");
        double sx = Math.abs(component(scale, 0, 1f));
        double sy = Math.abs(component(scale, 1, 1f));

        double w = widthPx * 0.025 * sx;
        double h = heightPx * 0.025 * sy;

        Vec3d center = e.getPos()
                .add(component(translation, 0, 0f), component(translation, 1, 0f), component(translation, 2, 0f))
                .add(0, h / 2, 0);

        Visual visual = new Visual(center, w / 2 + MARGIN, h / 2 + MARGIN, now);
        visualCache.put(e.getId(), visual);
        return visual;
    }

    private static float component(NbtList list, int index, float fallback) {
        return index < list.size() && list.get(index) instanceof AbstractNbtNumber n ? n.floatValue() : fallback;
    }
}