package com.backblaze.erasure.fec;

import com.backblaze.erasure.ReedSolomon;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.ArrayList;
import java.util.List;

import static com.backblaze.erasure.fec.Fec.typeData;

/**
 *
 * 融进kcp要考虑fec导致的rtt计算不准的问题
 * 参考 https://github.com/xtaci/kcp-go/issues/63
 * Created by JinMiao
 * 2018/6/8.
 * TODO go版本使用的uint为序列id 在发送24亿条消息之后可能会出现兼容问题，以后版本修复
 */
public class FecDecode {
    // queue size limit
    private int rxlimit;
    private int dataShards;
    private int parityShards;
    /** dataShards+parityShards **/
    private int shardSize;
    // ordered receive queue
    private ArrayList<FecPacket> rx;

    private ByteBuf[] decodeCache;
    /**标记是否已经缓存了**/
    private boolean[] flagCache;
    private ByteBuf zeros;
    private ReedSolomon codec;


    public FecDecode(int rxlimit, ReedSolomon codec) {
        this.rxlimit = rxlimit;
        this.dataShards = codec.getDataShardCount();
        this.parityShards = codec.getParityShardCount();
        this.shardSize = dataShards + parityShards;

        if (dataShards <= 0 || parityShards <= 0) {
            throw new FecException("dataShards and parityShards can not less than 0");
        }
        if (rxlimit < dataShards+parityShards) {
            throw new FecException("");
        }
        this.codec =codec;
        this.decodeCache = new ByteBuf[this.shardSize];
        this.flagCache = new boolean[this.shardSize];
        this.rx = new ArrayList<>(rxlimit);

        zeros = ByteBufAllocator.DEFAULT.buffer(Fec.mtuLimit);
        zeros.writeBytes(new byte[Fec.mtuLimit]);
        zeros.duplicate();
    }


    /**
     * 1，已经收到的丢弃掉
     * 2，找到应该插入rx的位置并插入
     * 3，从rx中找到当前包已收到的属于当前包组的消息集合
     * 4，检验数据包是否已经全部收到了 ，则清理rx收到的包
     * 5，如果收到的一组包数量大于等于数据包数量(dataShards)，进行消息补全，再进行数据恢复
     * 6, 恢复后清空rx收到的包
     * 注意: pkt在传入后不要做释放操作 pkt的data不要做释放操作
     *  返回的对象是被丢掉的数据 需要手动 release
     * @param
     * @return
     */
    public List<ByteBuf> decode(FecPacket pkt){
        if(pkt.getFlag()==Fec.typeParity){
            Snmp.snmp.FECParityShards.incrementAndGet();
        }else{
            Snmp.snmp.FECDataShards.incrementAndGet();
        }
        int n = 0;
        if(rx!=null)
            n = rx.size()-1;
        int insertIdx = 0;
        for (int i = n; i >= 0; i--) {
            //去重
            if(pkt.getSeqid() == rx.get(i).getSeqid())
            {
                Snmp.snmp.FECRepeatDataShards.incrementAndGet();
                pkt.release();
                return null;
            }
            if (pkt.getSeqid()> rx.get(i).getSeqid()) { // insertion
                insertIdx = i + 1;
                break;
            }
        }
        //插入 rx中
        if(insertIdx==n+1){
            this.rx.add(pkt);
        }else{
            rx.add(insertIdx,pkt);
        }

        //所有消息列表中的第一个包
        // shard range for current packet
        int shardBegin = pkt.getSeqid()-pkt.getSeqid()%shardSize;
        int shardEnd =shardBegin + shardSize - 1;

        //rx数组中的第一个包
        // max search range in ordered queue for current shard
        int searchBegin = insertIdx - pkt.getSeqid()%shardSize;
        if (searchBegin < 0) {
            searchBegin = 0;
        }

        int searchEnd = searchBegin + shardSize - 1;
        if (searchEnd >= rx.size()) {
            searchEnd = rx.size() - 1;
        }

        List<ByteBuf> result = null;
        if(searchEnd-searchBegin+1>=dataShards){
            //当前包组的已收到的包数量
            int numshard=0;
            //当前包组中属于数据包的数量
            int numDataShard=0;
            //搜到第一个包在搜索行中的位置
            int first= 0;
            //收到的最大包的字节长度
            int maxlen=0;

            // zero cache
            ByteBuf[] shards = decodeCache;
            boolean[] shardsflag = flagCache;
            for (int i = 0; i < shards.length; i++) {
                shards[i] = null;
                shardsflag[i]= false;
            }
            // shard assembly
            for (int i = searchBegin; i <= searchEnd; i++) {
                FecPacket fecPacket = rx.get(i);
                int seqid = fecPacket.getSeqid();
                if(seqid>shardEnd)
                    break;
                if(seqid<shardBegin)
                    continue;
                shards[seqid%shardSize] = fecPacket.getData();
                shardsflag[seqid%shardSize] = true;
                numshard++;
                if (fecPacket.getFlag() == typeData) {
                    numDataShard++;
                }
                if (numshard == 1) {
                    first = i;
                }
                if (fecPacket.getData().readableBytes() > maxlen) {
                    maxlen =fecPacket.getData().readableBytes();
                }
            }
            if(numDataShard==dataShards){
                // case 1:  no lost data shards
                freeRange(first, numshard, rx);
            }
            else if(numshard>=dataShards){
                // case 2: data shard lost, but  recoverable from parity shard
                //for k := range shards {
                //    if shards[k] != nil {
                //        dlen := len(shards[k])
                //        shards[k] = shards[k][:maxlen]
                //        copy(shards[k][dlen:], dec.zeros)
                //    }
                //}
                //if err := dec.codec.ReconstructData(shards); err == nil {
                //    for k := range shards[:dec.dataShards] {
                //        if !shardsflag[k] {
                //            recovered = append(recovered, shards[k])
                //        }
                //    }
                //}
                //dec.rx = dec.freeRange(first, numshard, dec.rx)
                // case 2: data shard lost, but  recoverable from parity shard
                for (int i = 0; i < shards.length; i++) {
                    ByteBuf shard  = shards[i];
                    //如果数据不存在 用0填充起来
                    if(shard==null){
                        shards[i] = zeros.copy(0,maxlen+Fec.fecHeaderSize);
                        shards[i].writerIndex(maxlen+ Fec.fecHeaderSize);
                        continue;
                    }
                    int left = maxlen-shard.readableBytes();
                    if(left>0){
                        shard.writeBytes(this.zeros,left);
                        zeros.resetReaderIndex();
                    }
                }
                codec.decodeMissing(shards,shardsflag,Fec.fecHeaderSize,maxlen);
                result = new ArrayList<>(this.dataShards);
                for (int i = 0; i < dataShards; i++) {
                    if(!shardsflag[i]){
                        ByteBuf byteBufs = shards[i];
                        int packageSize =byteBufs.getUnsignedShort(Fec.fecHeaderSize);
                        //判断长度
                        if(byteBufs.writerIndex()-Fec.fecHeaderSizePlus2>=packageSize&&packageSize>0)
                        {
                            byteBufs = byteBufs.slice(Fec.fecHeaderSizePlus2,packageSize);
                            result.add(byteBufs);
                            Snmp.snmp.FECRecovered.incrementAndGet();
                        }else{
                            System.out.println("bytebuf长度: "+byteBufs.writerIndex()+" 读出长度"+packageSize);
                            byte[] bytes = new byte[byteBufs.writerIndex()];
                            byteBufs.getBytes(0,bytes);
                            for (byte aByte : bytes) {
                                System.out.print("["+aByte+"] ");
                            }
                            Snmp.snmp.FECErrs.incrementAndGet();
                        }
                    }
                }
                freeRange(first, numshard, rx);
            }
        }
        if(rx.size()>rxlimit){
            if(rx.get(0).getFlag()==Fec.typeData){
                Snmp.snmp.FECShortShards.incrementAndGet();
            }
            freeRange(0, 1, rx);
        }
        return result;
    }




    public void release(){
        this.rxlimit = 0;
        this.dataShards=0;
        this.parityShards=0;
        this.shardSize=0;
        for (FecPacket fecPacket : this.rx) {
            if(fecPacket==null)
                continue;
            fecPacket.release();
        }
        this.zeros.release();
        codec=null;
    }

    /**
     * 1，回收first后n个bytebuf
     * 2，将q的first到first+n之间的数据移除掉
     * 3，将尾部的n个数据的data清空
     * 4，返回开头到尾部n个数组的对象
     *
     * @param first
     * @param n
     * @param q
     */
    private static void freeRange(int first,int n,ArrayList<FecPacket> q){
        for (int i = first; i < first + n; i++) {
            q.get(i).release();
        }
        //copy(q[first:], q[first+n:])
        for (int i = first; i < q.size(); i++) {
            int index = i+n;
            if(index==q.size())
                break;
            q.set(i,q.get(index));
        }
        //for (int i = 0; i < n; i++) {
        //    q.get(q.size()-1-i).setData(null);
        //}
        for (int i = 0; i < n; i++) {
            q.remove(q.size()-1);
        }
    }


    public static void main(String[] args) {
        int first = 0;
        int n=2;
        ArrayList<Integer> q = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            q.add(i);
        }
        for (int i = first; i < first+n; i++) {
            int index = i+n;
            if(index==q.size())
                break;
            q.set(i,q.get(i+n));
        }
        int removeIndex = q.size()-n;
        for (int i = 0; i < n; i++) {
            q.remove(q.size()-1);
        }
        System.out.println();
    }



}
