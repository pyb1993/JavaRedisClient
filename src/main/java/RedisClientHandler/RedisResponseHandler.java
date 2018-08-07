package RedisClientHandler;
import  RedisClient.RedisFuture;
import RedisClient.RedisClient;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.lang.ref.WeakReference;
import RedisClient.RedisException;

/* 用来检查Response的合法性并且获取结果
   这个handler持有RedisClient的引用,所以可在检查完成之后设置RedisClient的结果
   这个handler需要设置RedisFuture的结果
*/
public class RedisResponseHandler extends ChannelInboundHandlerAdapter {
    private RedisFuture future;
    private WeakReference<RedisClient> clientRef;// 有可能client会提前结束
    // 获取RedisFuture的引用,这样就可以通知调用者什么时候结束了
    public RedisResponseHandler(RedisFuture future, RedisClient client){
        super();
        this.future = future;
        this.clientRef = new WeakReference<RedisClient>(client);
    }

    /* 将持有的channel缓存或者关闭
         1. 如果结果返回时, client已经关闭了怎么办?
            因为我们没有在其他地方持有notifyChannel的引用,所以如果client已经关闭,那么我们需要将这个channel也关闭
         2. 如果client没有关闭,那么就看需要进一步处理
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object resp) throws Exception {
        RedisClient cl = clientRef.get();
        if(cl != null) {
            cl.onResponseDone(ctx.channel());
            future.setSuccess(resp);
        }else{
            ctx.close();
        }
    }

    /* 每当链接完成的时候调用,对方主动关闭
     或者该连接idle/read/write 超时 */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
        if(!future.isDone()){
            future.setSuccess(cause);
        }
    }

    // todo 关闭的时候必须要将Client的Connection
    // 要处理client已经关闭的情况
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        RedisClient cl = clientRef.get();
        if(cl != null) {
            cl.onResponseDone(ctx.channel());
            if(!future.isDone()){
                future.setFailure(new RedisException("Connection Closed By peer In Advance"));
            }
        }
        ctx.fireChannelInactive();
    }
}
