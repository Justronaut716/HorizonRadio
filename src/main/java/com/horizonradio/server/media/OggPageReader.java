package com.horizonradio.server.media;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Queue;

/** Bounded, single-stream Ogg parser that validates CRC and page sequencing. */
public final class OggPageReader {

    private static final int FIXED_HEADER_LENGTH = 27;
    private static final int DEFAULT_MAX_PAGE_BODY_BYTES = 65025;
    private static final int DEFAULT_MAX_PACKET_BYTES = 1024 * 1024;
    private final InputStream input;
    private final int maxPageBodyBytes;
    private final int maxPacketBytes;
    private final boolean allowNoEos;
    private final Queue<Packet> completePackets = new ArrayDeque<Packet>();
    private ByteArrayOutputStream packet = new ByteArrayOutputStream();
    private boolean packetContinues;
    private boolean eof;
    private boolean endOfStream;
    private long endOfStreamGranulePosition = -1L;
    private boolean streamKnown;
    private int serialNumber;
    private int expectedSequence;

    public OggPageReader(InputStream input) { this(input, DEFAULT_MAX_PAGE_BODY_BYTES, DEFAULT_MAX_PACKET_BYTES, false); }
    public OggPageReader(InputStream input, int maxPageBodyBytes, int maxPacketBytes) { this(input, maxPageBodyBytes, maxPacketBytes, false); }

    /**
     * Opt-in policy for Task 6-style endless radio streams. Finite media
     * decoders intentionally use the default constructor and require EOS.
     */
    public static OggPageReader allowNoEos(InputStream input) {
        return new OggPageReader(input, DEFAULT_MAX_PAGE_BODY_BYTES, DEFAULT_MAX_PACKET_BYTES, true);
    }

    private OggPageReader(InputStream input, int maxPageBodyBytes, int maxPacketBytes, boolean allowNoEos) {
        if (input == null) throw new IllegalArgumentException("Ogg input is required");
        if (maxPageBodyBytes <= 0 || maxPageBodyBytes > DEFAULT_MAX_PAGE_BODY_BYTES) throw new IllegalArgumentException("Invalid Ogg page body limit");
        if (maxPacketBytes <= 0) throw new IllegalArgumentException("Invalid Ogg packet limit");
        this.input=input; this.maxPageBodyBytes=maxPageBodyBytes; this.maxPacketBytes=maxPacketBytes; this.allowNoEos=allowNoEos;
    }
    public byte[] nextPacket() throws IOException { Packet next=nextPacketInfo(); return next==null?null:next.getData(); }
    public Packet nextPacketInfo() throws IOException { while(completePackets.isEmpty()){if(eof)return null;readPage();}return completePackets.remove(); }
    public boolean hasEndOfStream() { return endOfStream; }
    public long getEndOfStreamGranulePosition() {
        if (!endOfStream) throw new IllegalStateException("Ogg end-of-stream page has not been read");
        return endOfStreamGranulePosition;
    }

    private void readPage() throws IOException {
        byte[] header=new byte[FIXED_HEADER_LENGTH]; int first=input.read();
        if(first<0){eof=true;if(packetContinues)throw new MediaException("Truncated Ogg packet");if(!allowNoEos&&!endOfStream)throw new MediaException("Finite Ogg stream ended without EOS");return;}
        header[0]=(byte)first; readFully(header,1,26,"Ogg page header");
        if(header[0]!='O'||header[1]!='g'||header[2]!='g'||header[3]!='S')throw new MediaException("Invalid Ogg capture pattern");
        if(header[4]!=0)throw new MediaException("Unsupported Ogg version");
        int flags=header[5]&255; if((flags&~7)!=0)throw new MediaException("Invalid Ogg page flags");
        int segments=header[26]&255; byte[] lacing=new byte[segments]; readFully(lacing,0,segments,"Ogg lacing table");
        int bodyLength=0;for(int i=0;i<segments;i++){bodyLength+=lacing[i]&255;if(bodyLength>maxPageBodyBytes)throw new MediaException("Ogg page body exceeds limit");}
        byte[] body=new byte[bodyLength];readFully(body,0,body.length,"Ogg page body");
        if(readInt(header,22)!=crc(header,lacing,body))throw new MediaException("Invalid Ogg CRC");
        validateStream(header,flags);
        if(((flags&1)!=0)!=packetContinues)throw new MediaException("Invalid Ogg packet continuation");
        long granule=readLong(header,6);int bodyOffset=0;
        for(int i=0;i<segments;i++){
            int length=lacing[i]&255;if(packet.size()>maxPacketBytes-length)throw new MediaException("Ogg packet exceeds limit");
            packet.write(body,bodyOffset,length);bodyOffset+=length;
            if(length<255){boolean last=i==segments-1;completePackets.add(new Packet(packet.toByteArray(),last?granule:-1L,last&&(flags&4)!=0));packet.reset();packetContinues=false;}else packetContinues=true;
        }
        if ((flags & 4) != 0) {
            if (packetContinues) throw new MediaException("Truncated Ogg packet at EOS");
            endOfStream = true;
            endOfStreamGranulePosition = granule;
            eof = true;
        }
    }
    private void validateStream(byte[] header,int flags)throws IOException{
        int serial=readInt(header,14), sequence=readInt(header,18);
        if(!streamKnown){if(sequence!=0)throw new MediaException("Invalid initial Ogg sequence");if((flags&2)==0)throw new MediaException("Missing Ogg beginning-of-stream page");streamKnown=true;serialNumber=serial;expectedSequence=1;}
        else {if(serial!=serialNumber)throw new MediaException("Multiple Ogg stream serial numbers are unsupported");if(sequence!=expectedSequence)throw new MediaException("Invalid Ogg sequence gap");expectedSequence++;}
    }
    private static int crc(byte[] header,byte[] lacing,byte[] body){int crc=0;crc=update(crc,header,0,22);crc=update(crc,new byte[4],0,4);crc=update(crc,header,26,1);crc=update(crc,lacing,0,lacing.length);return update(crc,body,0,body.length);}
    private static int update(int crc,byte[] bytes,int offset,int length){for(int i=0;i<length;i++){crc^=(bytes[offset+i]&255)<<24;for(int bit=0;bit<8;bit++)crc=(crc<<1)^((crc&0x80000000)==0?0:0x04c11db7);}return crc;}
    private static int readInt(byte[] b,int o){return (b[o]&255)|((b[o+1]&255)<<8)|((b[o+2]&255)<<16)|(b[o+3]<<24);}
    private static long readLong(byte[] b,int o){long value=0;for(int i=0;i<8;i++)value|=((long)b[o+i]&255L)<<(8*i);return value;}
    private void readFully(byte[] target,int offset,int length,String part)throws IOException{int total=0;while(total<length){int count=input.read(target,offset+total,length-total);if(count<0)throw new MediaException("Truncated "+part);if(count>0)total+=count;}}
    public static final class Packet {private final byte[] data;private final long granulePosition;private final boolean endOfStream;private Packet(byte[] data,long granulePosition,boolean endOfStream){this.data=data;this.granulePosition=granulePosition;this.endOfStream=endOfStream;}public byte[] getData(){return data;}public long getGranulePosition(){return granulePosition;}public boolean isEndOfStream(){return endOfStream;}}
}
