package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Reads server limits or requests an operator-only update. */
public final class ServerSettingsRequestPacket implements IMessage {

    private boolean update;
    private int queueLimit;
    private int durationMinutes;

    public ServerSettingsRequestPacket() {}

    public ServerSettingsRequestPacket(boolean update, int queueLimit, int durationMinutes) {
        this.update = update;
        this.queueLimit = queueLimit;
        this.durationMinutes = durationMinutes;
    }

    public boolean isUpdate() {
        return update;
    }

    public int getQueueLimit() {
        return queueLimit;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(update);
        buf.writeInt(queueLimit);
        buf.writeInt(durationMinutes);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        update = buf.readBoolean();
        queueLimit = buf.readInt();
        durationMinutes = buf.readInt();
    }
}
