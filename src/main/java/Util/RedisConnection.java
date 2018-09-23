package Util;

import RedisClientHandler.RedisResponseHandler;
import io.netty.channel.Channel;
import java.util.concurrent.atomic.AtomicInteger;

public class RedisConnection{
    static AtomicInteger totNum = new AtomicInteger(0);// 当前所有的连接数量
    Channel channel;
    AtomicInteger cmdNum; // 当前连接上持有命令的数量

    public static int getTotalConnectionNum(){
        return totNum.get();
    }

    public static void incrementConnectionNum(){
        totNum.incrementAndGet();
    }

    public RedisConnection(Channel ch){
        channel = ch;
        cmdNum = new AtomicInteger(0);
    }

    public Channel channel(){
        return  channel;
    }

    public void incrCmd(){
        cmdNum.incrementAndGet();
    }

    public void decrCmd(){
        cmdNum.decrementAndGet();
    }

    public boolean isIdle(){
        //System.out.println("cmd:" + cmdNum.get());
        return cmdNum.get() == 0;
    }

    public int getNum(){
        return cmdNum.get();
    }

    // 返回channel对应的 ResponseHandler
    public RedisResponseHandler getResponseHandler(){
        if(channel.pipeline().last() != null){
            return (RedisResponseHandler)channel.pipeline().last();
        }
        return null;
    }

    // 是否已经有了两个合适的handler
    // 线程安全
    public boolean handlerReady(){
        return channel.pipeline().last() != null
                && channel.pipeline().first() != channel.pipeline().last();
    }

    public void close(){
        channel.close();
        totNum.decrementAndGet();
    }
}