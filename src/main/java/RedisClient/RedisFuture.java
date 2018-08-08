package RedisClient;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;


/* 返回给调用客户端的线程的类
    这里最大的难点就是需要和命令回来的时候进行同步,而命令回来只会调用channelRead
* 这样客户端就可以调用addListener来等待结果的完成
*  具体的回调是通过handler在收到结果的时候setSuccess(result)来通知的
*
* */
/* public class RedisFuture2 extends DefaultPromise<Object>{

    public RedisFuture2(EventLoop loop){
        super(loop);
    }

}*/

 public class RedisFuture extends DefaultPromise<Object> {
     RedisFuture next;// 下一个RedisFuture,只有前一个执行结束之后才会调用后一个
     EventLoop loop;
     BiConsumer listener;

    public RedisFuture(EventLoop loop){
        super(loop);
        this.loop = loop;
    }


      // 注意这个listener只有在setSuccess才会执行,第一个setSuccess是在ChannelRead里面执行的
      // listener里面应该有「逻辑」会通知next需要执行了
      // 返回的next代表 listener的结果
    public RedisFuture then(BiConsumer<RedisFuture,Object> listener){
         next = new RedisFuture(loop);
         this.listener = listener;
         return next;
    }

     @Override
    public RedisFuture setSuccess(Object result){
        super.setSuccess(result);
        // 异步执行listener(需要依靠listener「内部逻辑」来通知 next)
         if(listener != null){
             loop.execute(()-> listener.accept(this,result));
         }
         return this;
     }

     // 用来通知下一个listener开始执行了
     public void notifyNextListener(Object result){
        if(next != null){
            next.setSuccess(result);
        }
     }

     @Override
     public RedisFuture setFailure(Throwable cause){
         super.setFailure(cause);
         // 异步执行listener(需要依靠listener「内部逻辑」来通知 next)
         if(listener != null){
             loop.execute(()->{
                 listener.accept(this,cause);
             });
         }
         return this;
     }

}