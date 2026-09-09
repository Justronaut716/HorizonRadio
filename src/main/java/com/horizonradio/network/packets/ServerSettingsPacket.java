package com.horizonradio.network.packets;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Server-confirmed limits and permission for the receiving player. */
public final class ServerSettingsPacket implements IMessage {

    public static final int SYNC = 0, SAVED = 1, DENIED = 2, INVALID = 3, SAVE_FAILED = 4;
    private boolean canEdit;
    private int queueLimit;
    private int durationMinutes;
    private int status;

    public ServerSettingsPacket() {}

    public ServerSettingsPacket(boolean canEdit, int queueLimit, int durationMinutes, int status) {
        this.canEdit = canEdit;
        this.queueLimit = queueLimit;
        this.durationMinutes = durationMinutes;
        this.status = status;
    }

    public boolean canEdit() {
        return canEdit;
    }

    public int getQueueLimit() {
        return queueLimit;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public int getStatus() {
        return status;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(canEdit);
        buf.writeInt(queueLimit);
        buf.writeInt(durationMinutes);
        buf.writeInt(status);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        canEdit = buf.readBoolean();
        queueLimit = buf.readInt();
        durationMinutes = buf.readInt();
        status = buf.readInt();
    }
}
