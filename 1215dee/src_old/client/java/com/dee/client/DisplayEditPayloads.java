package com.dee.client;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 클라이언트/서버가 함께 쓰는 패킷 정의. net.minecraft.client.* 는 절대 import 하지 않는다.
 * 1.21.5: CustomPacketPayload/StreamCodec(Mojang) -> CustomPayload/PacketCodec(Yarn)
 */
public final class DisplayEditPayloads {
    private DisplayEditPayloads() {}

    /** C2S: 이 엔티티를 편집하고 싶다 */
    public record OpenRequest(int entityId) implements CustomPayload {
        public static final CustomPayload.Id<OpenRequest> TYPE =
                new CustomPayload.Id<>(Identifier.of(DisplayEditMod.MOD_ID, "open_request"));
        public static final PacketCodec<RegistryByteBuf, OpenRequest> CODEC =
                PacketCodec.tuple(PacketCodecs.VAR_INT, OpenRequest::entityId, OpenRequest::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() { return TYPE; }
    }

    /** S2C: 서버가 읽은 현재 NBT를 보내 UI를 열게 한다 */
    public record OpenScreen(int entityId, NbtCompound nbt) implements CustomPayload {
        public static final CustomPayload.Id<OpenScreen> TYPE =
                new CustomPayload.Id<>(Identifier.of(DisplayEditMod.MOD_ID, "open_screen"));
        public static final PacketCodec<RegistryByteBuf, OpenScreen> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.VAR_INT, OpenScreen::entityId,
                        PacketCodecs.NBT_COMPOUND, OpenScreen::nbt,
                        OpenScreen::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() { return TYPE; }
    }

    /** C2S: 바뀐 키만 담은 패치. "__remove" 컴파운드에 있는 키는 삭제한다 */
    public record Apply(int entityId, NbtCompound patch) implements CustomPayload {
        public static final CustomPayload.Id<Apply> TYPE =
                new CustomPayload.Id<>(Identifier.of(DisplayEditMod.MOD_ID, "apply"));
        public static final PacketCodec<RegistryByteBuf, Apply> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.VAR_INT, Apply::entityId,
                        PacketCodecs.NBT_COMPOUND, Apply::patch,
                        Apply::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() { return TYPE; }
    }
}
