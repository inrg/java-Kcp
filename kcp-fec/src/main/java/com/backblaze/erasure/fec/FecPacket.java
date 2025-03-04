package com.backblaze.erasure.fec;

import io.netty.buffer.ByteBuf;
import io.netty.util.Recycler;

/**
 * Created by JinMiao
 * 2018/6/26.
 */
public class FecPacket {
    private int seqid;
    private int flag;
    private ByteBuf data;
    private Recycler.Handle<FecPacket> recyclerHandle;


    private static final Recycler<FecPacket> fecPacketRecycler = new Recycler<FecPacket>() {
        @Override
        protected FecPacket newObject(Handle<FecPacket> handle) {
            return new FecPacket(handle);
        }
    };

    public static FecPacket newFecPacket(ByteBuf byteBuf){
        FecPacket pkt = fecPacketRecycler.get();
        pkt.seqid =byteBuf.readInt();
        pkt.flag = byteBuf.readShort();
        pkt.data = byteBuf.retainedDuplicate();
        return pkt;
    }

    private FecPacket(Recycler.Handle<FecPacket> recyclerHandle) {
        this.recyclerHandle = recyclerHandle;
    }

    public void release(){
        this.seqid = 0;
        this.flag = 0;
        this.data.release();
        this.data = null;
        recyclerHandle.recycle(this);
    }

    public int getSeqid() {
        return seqid;
    }

    public void setSeqid(int seqid) {
        this.seqid = seqid;
    }

    public int getFlag() {
        return flag;
    }

    public void setFlag(int flag) {
        this.flag = flag;
    }

    public ByteBuf getData() {
        return data;
    }

    public void setData(ByteBuf data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "FecPacket{" +
                "seqid=" + seqid +
                ", flag=" + flag +
                '}';
    }
}
