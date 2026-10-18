package com.dee.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 클라이언트/서버가 함께 쓰는 패킷 정의.
 * net.minecraft.client.* 는 절대 import 하지 않는다.
 */
public final class DisplayEditPayloads {
    private DisplayEditPayloads() {}

    /** C2S: 이 엔티티를 편집하고 싶다 */
    public record OpenRequest(int entityId) implements CustomPacketPayload {
        public static final Type<OpenRequest> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(DisplayEditMod.MOD_ID, "open_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRequest> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, OpenRequest::entityId, OpenRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: 서버가 읽은 현재 NBT를 보내 UI를 열게 한다 */
    public record OpenScreen(int entityId, CompoundTag nbt) implements CustomPacketPayload {
        public static final Type<OpenScreen> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(DisplayEditMod.MOD_ID, "open_screen"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreen> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, OpenScreen::entityId,
                        ByteBufCodecs.COMPOUND_TAG, OpenScreen::nbt,
                        OpenScreen::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: 바뀐 키만 담은 패치. "__remove" 컴파운드에 있는 키는 삭제한다 */
    public record Apply(int entityId, CompoundTag patch) implements CustomPacketPayload {
        public static final Type<Apply> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(DisplayEditMod.MOD_ID, "apply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Apply> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Apply::entityId,
                        ByteBufCodecs.COMPOUND_TAG, Apply::patch,
                        Apply::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
