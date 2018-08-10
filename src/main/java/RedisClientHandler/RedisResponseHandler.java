package RedisClientHandler;
import  RedisClient.RedisFuture;
import RedisClient.RedisClient;
import Util.Logger;
import Util.Resp;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.lang.ref.WeakReference;
import Util.RedisConnection;
import java.util.concurrent.ConcurrentHashMap;

import RedisClient.RedisException;

/* 用来检查Response的合法性并且获取结果
   这个handler持有RedisClient的引用,所以可在检查完成之后设置RedisClient的结果
   这个handler需要设置RedisFuture的结果
*/
public class RedisResponseHandler extends ChannelInboundHandlerAdapter {
    private ConcurrentHashMap<String,RedisFuture> futureMap;
    private WeakReference<RedisClient> clientRef;// 有可能client会提前结束
    private RedisConnection connection;

    // 需要一个Map reqId => future来获取引用
    public RedisResponseHandler(ConcurrentHashMap<String,RedisFuture> futureMap, RedisClient client,RedisConnection c){
        super();
        this.futureMap = futureMap;
        this.clientRef = new WeakReference<RedisClient>(client);
        this.connection = c;
    }

    /* 将持有的channel缓存或者关闭
         0. 需要将未完成命令的数量减少1
         1. 如果结果返回时, client已经关闭了怎么办?
            因为我们没有在其他地方持有notifyChannel的引用,所以如果client已经关闭,那么我们需要将这个channel也关闭
         2. 如果client没有关闭,那么就看需要进一步处理

     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object data) throws Exception {
        RedisClient cl = clientRef.get();
        Resp resp = (Resp)data;
        if(cl != null) {
            cl.onResponseDone(connection);
            RedisFuture future = futureMap.get(resp.getReqId());
            future.setSuccess(resp.getResp());

            futureMap.remove(resp.getReqId()); // 移除对应的引用
            connection.decrCmd();// 未完成命令数量减少1
        }else{
            ctx.close();
        }
    }

    /* 每当链接完成的时候调用,
     对方主动关闭
     或者该连接idle/read/write超时
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
        for(RedisFuture future : futureMap.values()){
            if(!future.isDone()){
                future.setFailure(cause);
            }
        }
    }


    // 这里是已经链接上的链接断开了,有空闲链接断开的可能,也有可能是正在使用的请求断开了
    //
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        RedisClient cl = clientRef.get();
        if(cl != null) {
            for(RedisFuture future : futureMap.values()){
                if(!future.isDone()){
                    future.setFailure(new RedisException("Connection Closed By peer In Advance"));
                }
            }
        }

/*        if(!connection.isIdle()){
            System.out.println(connection.channel() + "" + connection.getNum());
            for(String reqid : futureMap.keySet()){
                System.out.println(reqid);
            }
        }*/

        ctx.fireChannelInactive();
    }

    public void addFuture(String reqId, RedisFuture future){
        //Logger.show(reqId + connection.channel());
        futureMap.put(reqId,future);
    }
}
