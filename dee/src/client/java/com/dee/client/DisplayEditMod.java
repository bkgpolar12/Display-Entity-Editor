package com.dee.client;

import com.dee.DisplayEntityEditor;
import com.dee.client.DisplayEditPayloads.Apply;
import com.dee.client.DisplayEditPayloads.OpenRequest;
import com.dee.client.DisplayEditPayloads.OpenScreen;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;

import java.util.Set;

/** 서버(공용) 쪽 진입점. net.minecraft.client.* 를 import 하면 안 된다. */
public class DisplayEditMod implements ModInitializer {
    public static final String MOD_ID = "dee";

    /** true 면 서버 쪽 처리 결과를 채팅으로 알려 준다. 문제 해결되면 false 로 */
    private static final boolean DEBUG = false;

    /** 클라이언트가 수정을 요청할 수 있는 text_display / block_display NBT 키 화이트리스트 */
    private static final Set<String> ALLOWED = Set.of(
            // text_display 전용
            "text", "line_width", "background", "text_opacity",
            "alignment", "shadow", "see_through", "default_background",
            // block_display 전용
            "block_state",
            // item_display 전용
            "item", "item_display",
            // 공통 Display
            "Tags", "transformation", "billboard", "brightness", "view_range",
            "shadow_radius", "shadow_strength", "glow_color_override",
            "width", "height",
            "interpolation_duration", "start_interpolation", "teleport_duration"
    );

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(OpenRequest.TYPE, OpenRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Apply.TYPE, Apply.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OpenScreen.TYPE, OpenScreen.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(OpenRequest.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            try {
                Entity entity = resolve(player, payload.entityId());
                if (entity == null) return;

                CompoundTag nbt = EntityNbt.save(entity);
                ServerPlayNetworking.send(player, new OpenScreen(entity.getId(), nbt));
                //debug(player, "편집 데이터 전송 (id " + entity.getId() + ")");
            } catch (Throwable t) {
                fail(player, "편집 요청 처리 중 오류", t);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(Apply.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            try {
                Entity entity = resolve(player, payload.entityId());
                if (entity == null) return;

                CompoundTag merged = EntityNbt.save(entity);

                CompoundTag patch = payload.patch();
                for (String key : patch.keySet()) {
                    if (!ALLOWED.contains(key)) continue;
                    Tag value = patch.get(key);
                    if (value != null) merged.put(key, value.copy());
                }
                for (String key : patch.getCompoundOrEmpty("__remove").keySet()) {
                    if (ALLOWED.contains(key)) merged.remove(key);
                }

                // /data merge entity 와 같은 방식. 트래킹 데이터가 갱신되어 클라이언트로 자동 동기화된다.
                EntityNbt.load(entity, merged);
                //debug(player, "적용 완료 (id " + entity.getId() + ")");
            } catch (Throwable t) {
                fail(player, "적용 중 오류", t);
            }
        });
    }

    /** 권한/거리/타입 검증. 통과하면 text_display 엔티티, 아니면 사유를 알리고 null */
    private static Entity resolve(ServerPlayer player, int entityId) {
        if (!player.isCreative()) return reject(player, "크리에이티브 모드가 아닙니다");
        // 서버용으로 쓸 때는 OP 권한 검사를 추가하세요. (1.21.11+ 는 player.permissions() 의 PermissionSet 으로 검사)
        Entity entity = player.level().getEntity(entityId);
        if (entity == null) return reject(player, "엔티티를 찾지 못했습니다 (id " + entityId + ")");
        if (!(entity instanceof Display.TextDisplay || entity instanceof Display.BlockDisplay
                || entity instanceof Display.ItemDisplay))
            return reject(player, "text_display / block_display / item_display 가 아닙니다");
        if (player.distanceToSqr(entity) > 16 * 16) return reject(player, "너무 멉니다 (16칸 초과)");
        return entity;
    }

    private static Entity reject(ServerPlayer player, String reason) {
        DisplayEntityEditor.LOGGER.warn("편집 요청 거부 ({}): {}", player.getName().getString(), reason);
        if (DEBUG) player.sendSystemMessage(Component.literal("[DEE] 서버가 거부: " + reason));
        return null;
    }

    private static void debug(ServerPlayer player, String message) {
        if (DEBUG) player.sendSystemMessage(Component.literal("[DEE] 서버: " + message));
    }

    private static void fail(ServerPlayer player, String message, Throwable t) {
        DisplayEntityEditor.LOGGER.error(message, t);
        player.sendSystemMessage(Component.literal("[DEE] 서버 오류: " + message + " - " + t));
    }
}