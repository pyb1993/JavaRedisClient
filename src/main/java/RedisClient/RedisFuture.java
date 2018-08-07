package RedisClient;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.DefaultPromise;


/* 返回给调用客户端的线程的类
    这里最大的难点就是需要和命令回来的时候进行同步,而命令回来只会调用channelRead
* 这样客户端就可以调用addListener来等待结果的完成
*  具体的回调是通过handler在收到结果的时候setSuccess(result)来通知的
*
* */
 public class RedisFuture extends DefaultPromise<Object>{
    public RedisFuture(EventLoop loop){
        super(loop);
    }
}