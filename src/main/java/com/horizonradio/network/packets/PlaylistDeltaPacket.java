package com.horizonradio.network.packets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.horizonradio.core.model.MediaSourceType;
import com.horizonradio.network.PacketBufferUtil;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** A single revisioned playlist mutation with only operation-relevant wire fields. */
public class PlaylistDeltaPacket implements IMessage {

    private long queueRevision;
    private Operation operation;
    private Entry entry;
    private int index;
    private int targetIndex;
    private List<Entry> entries;

    public PlaylistDeltaPacket() {
        entries = Collections.emptyList();
        index = -1;
        targetIndex = -1;
    }

    private PlaylistDeltaPacket(long queueRevision, Operation operation, Entry entry, int index, int targetIndex,
        List<Entry> entries) {
        this.queueRevision = queueRevision;
        this.operation = operation;
        this.entry = entry;
        this.index = index;
        this.targetIndex = targetIndex;
        this.entries = entries == null ? Collections.<Entry>emptyList() : new ArrayList<Entry>(entries);
        validate();
    }

    public static PlaylistDeltaPacket add(long queueRevision, Entry entry, int index) {
        return new PlaylistDeltaPacket(queueRevision, Operation.ADD, entry, index, -1, null);
    }

    public static PlaylistDeltaPacket remove(long queueRevision, int index) {
        return new PlaylistDeltaPacket(queueRevision, Operation.REMOVE, null, index, -1, null);
    }

    public static PlaylistDeltaPacket move(long queueRevision, int index, int targetIndex) {
        return new PlaylistDeltaPacket(queueRevision, Operation.MOVE, null, index, targetIndex, null);
    }

    public static PlaylistDeltaPacket clear(long queueRevision) {
        return new PlaylistDeltaPacket(queueRevision, Operation.CLEAR, null, -1, -1, null);
    }

    public static PlaylistDeltaPacket replace(long queueRevision, List<Entry> entries) {
        return new PlaylistDeltaPacket(queueRevision, Operation.REPLACE, null, -1, -1, entries);
    }

    public long getQueueRevision() {
        return queueRevision;
    }

    public Operation getOperation() {
        return operation;
    }

    public Entry getEntry() {
        return entry;
    }

    public int getIndex() {
        return index;
    }

    public int getTargetIndex() {
        return targetIndex;
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        validate();
        buf.writeLong(queueRevision);
        buf.writeByte(operation.getWireValue());
        switch (operation) {
            case ADD:
                PlaylistSyncPacket.writeEntry(buf, entry.toSnapshotEntry());
                buf.writeInt(index);
                break;
            case REMOVE:
                buf.writeInt(index);
                break;
            case MOVE:
                buf.writeInt(index);
                buf.writeInt(targetIndex);
                break;
            case REPLACE:
                PacketBufferUtil.writeCount(buf, entries.size());
                for (Entry replacement : entries) {
                    PlaylistSyncPacket.writeEntry(buf, replacement.toSnapshotEntry());
                }
                break;
            case CLEAR:
                break;
            default:
                throw new IllegalStateException("unsupported playlist operation");
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        long decodedRevision = buf.readLong();
        Operation decodedOperation = Operation.fromWireValue(buf.readByte());
        Entry decodedEntry = null;
        int decodedIndex = -1;
        int decodedTargetIndex = -1;
        List<Entry> decodedEntries = Collections.emptyList();
        switch (decodedOperation) {
            case ADD:
                decodedEntry = Entry.fromSnapshotEntry(PlaylistSyncPacket.readEntry(buf));
                decodedIndex = buf.readInt();
                break;
            case REMOVE:
                decodedIndex = buf.readInt();
                break;
            case MOVE:
                decodedIndex = buf.readInt();
                decodedTargetIndex = buf.readInt();
                break;
            case REPLACE:
                int count = PlaylistSyncPacket.readPlaylistCount(buf);
                decodedEntries = new ArrayList<Entry>(count);
                for (int i = 0; i < count; i++) {
                    decodedEntries.add(Entry.fromSnapshotEntry(PlaylistSyncPacket.readEntry(buf)));
                }
                break;
            case CLEAR:
                break;
            default:
                throw new IllegalArgumentException("unsupported playlist operation");
        }
        queueRevision = decodedRevision;
        operation = decodedOperation;
        entry = decodedEntry;
        index = decodedIndex;
        targetIndex = decodedTargetIndex;
        entries = decodedEntries;
        validate();
    }

    private void validate() {
        if (queueRevision < 0L || operation == null) {
            throw new IllegalArgumentException("invalid playlist delta");
        }
        switch (operation) {
            case ADD:
                if (entry == null || index < 0 || !entries.isEmpty()) {
                    throw new IllegalArgumentException("invalid add playlist delta");
                }
                break;
            case REMOVE:
                if (index < 0 || entry != null || !entries.isEmpty()) {
                    throw new IllegalArgumentException("invalid remove playlist delta");
                }
                break;
            case MOVE:
                if (index < 0 || targetIndex < 0 || entry != null || !entries.isEmpty()) {
                    throw new IllegalArgumentException("invalid move playlist delta");
                }
                break;
            case CLEAR:
                if (entry != null || !entries.isEmpty()) {
                    throw new IllegalArgumentException("invalid clear playlist delta");
                }
                break;
            case REPLACE:
                if (entry != null || entries.size() > PlaylistSyncPacket.MAX_ENTRIES) {
                    throw new IllegalArgumentException("invalid replace playlist delta");
                }
                for (Entry replacement : entries) {
                    if (replacement == null) {
                        throw new IllegalArgumentException("playlist replacement entry must not be null");
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("unsupported playlist operation");
        }
    }

    public enum Operation {

        ADD((byte) 1),
        REMOVE((byte) 2),
        MOVE((byte) 3),
        CLEAR((byte) 4),
        REPLACE((byte) 5);

        private final byte wireValue;

        Operation(byte wireValue) {
            this.wireValue = wireValue;
        }

        public byte getWireValue() {
            return wireValue;
        }

        static Operation fromWireValue(byte wireValue) {
            for (Operation candidate : values()) {
                if (candidate.wireValue == wireValue) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown playlist delta operation: " + wireValue);
        }
    }

    public static final class Entry {

        private final MediaSourceType sourceType;
        private final String sourceId;
        private final String addedBy;

        public Entry(MediaSourceType sourceType, String sourceId, String addedBy) {
            PlaylistSyncPacket.Entry snapshotEntry = new PlaylistSyncPacket.Entry(sourceType, sourceId, addedBy);
            this.sourceType = snapshotEntry.getSourceType();
            this.sourceId = snapshotEntry.getSourceId();
            this.addedBy = snapshotEntry.getAddedBy();
        }

        private static Entry fromSnapshotEntry(PlaylistSyncPacket.Entry snapshotEntry) {
            return new Entry(snapshotEntry.getSourceType(), snapshotEntry.getSourceId(), snapshotEntry.getAddedBy());
        }

        private PlaylistSyncPacket.Entry toSnapshotEntry() {
            return new PlaylistSyncPacket.Entry(sourceType, sourceId, addedBy);
        }

        public MediaSourceType getSourceType() {
            return sourceType;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getAddedBy() {
            return addedBy;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Entry)) {
                return false;
            }
            Entry that = (Entry) other;
            return sourceType == that.sourceType && Objects.equals(sourceId, that.sourceId)
                && Objects.equals(addedBy, that.addedBy);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceType, sourceId, addedBy);
        }
    }
}
