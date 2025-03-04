import com.backblaze.erasure.fec.Snmp;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import kcp.ChannelConfig;
import kcp.KcpClient;
import kcp.KcpListener;
import kcp.Ukcp;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Created by JinMiao
 * 2019-06-26.
 */
public class KcpRttExampleClient implements KcpListener {

    private final ByteBuf data;

    private int[] rtts;

    private volatile int count;

    private ScheduledExecutorService scheduleSrv;

    private ScheduledFuture<?> future = null;

    private final long startTime ;

    public KcpRttExampleClient() {
        data = Unpooled.buffer(200);
        for (int i = 0; i < data.capacity(); i++) {
            data.writeByte((byte) i);
        }

        rtts = new int[300];
        for (int i = 0; i < rtts.length; i++) {
            rtts[i] = -1;
        }
        startTime = System.currentTimeMillis();
        scheduleSrv = Executors.newSingleThreadScheduledExecutor();
    }

    public static void main(String[] args) {
        KcpClient kcpClient = new KcpClient();
        kcpClient.init(38888);

        ChannelConfig channelConfig = new ChannelConfig();
        channelConfig.setFastresend(2);
        channelConfig.setSndwnd(512);
        channelConfig.setRcvwnd(512);
        channelConfig.setMtu(1400);
        channelConfig.setFecDataShardCount(10);
        channelConfig.setFecParityShardCount(3);
        channelConfig.setAckNoDelay(false);
        channelConfig.setInterval(40);
        channelConfig.setNocwnd(true);
        channelConfig.setCrc32Check(true);
        channelConfig.setTimeoutMillis(10000);

        KcpRttExampleClient kcpClientRttExample = new KcpRttExampleClient();
        kcpClient.connect(new InetSocketAddress("127.0.0.1",10003),channelConfig,kcpClientRttExample);
    }

    @Override
    public void onConnected(Ukcp ukcp) {
        future = scheduleSrv.scheduleWithFixedDelay(() -> {
            ukcp.write(rttMsg(++count));
            if (count >= rtts.length) {
                // finish
                future.cancel(true);
                ukcp.write(rttMsg(-1));

            }
        }, 20, 20, TimeUnit.MILLISECONDS);
    }

    @Override
    public void handleReceive(ByteBuf byteBuf, Ukcp ukcp) {
        int curCount = byteBuf.readShort();

        if (curCount == -1) {
            scheduleSrv.schedule(new Runnable() {
                @Override
                public void run() {
                    int sum = 0;
                    for (int rtt : rtts) {
                        sum += rtt;
                    }
                    System.out.println("average: "+ (sum / rtts.length));
                    System.out.println(Snmp.snmp.toString());
                    ukcp.setTimeoutMillis(System.currentTimeMillis());
                    System.exit(0);
                }
            }, 3, TimeUnit.SECONDS);
        } else {
            int idx = curCount - 1;
            long time = byteBuf.readInt();
            if (rtts[idx] != -1) {
                System.out.println("???");
            }
            //log.info("rcv count {} {}", curCount, System.currentTimeMillis());
            rtts[idx] = (int) (System.currentTimeMillis() - startTime - time);
            System.out.println("rtt : "+ curCount+"  "+ rtts[idx]);
        }
    }

    @Override
    public void handleException(Throwable ex, Ukcp kcp)
    {
        ex.printStackTrace();
    }

    @Override
    public void handleClose(Ukcp kcp) {
        scheduleSrv.shutdown();
        try {
            scheduleSrv.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        int sum = 0;
        for (int rtt : rtts) {
            sum += rtt;
        }
        System.out.println("average: "+ (sum / rtts.length));
        System.out.println(Snmp.snmp.toString());
    }


    /**
     * count+timestamp+dataLen+data
     *
     * @param count
     * @return
     */
    public ByteBuf rttMsg(int count) {
        ByteBuf buf = Unpooled.buffer(10);
        buf.writeShort(count);
        buf.writeInt((int) (System.currentTimeMillis() - startTime));

        //int dataLen = new Random().nextInt(200);
        //buf.writeBytes(new byte[dataLen]);

        int dataLen = data.readableBytes();
        buf.writeShort(dataLen);
        buf.writeBytes(data, data.readerIndex(), dataLen);

        return buf;
    }

}
