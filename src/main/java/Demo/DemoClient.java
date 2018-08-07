package Demo;
import RedisClient.*;
import Util.Logger;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;


/*
* 所有的命令都分为同步和异步两个版本
* 同步是不带后缀的,异步会带上Async的后缀(get & getAsync)
*
* */
public class DemoClient {
    public static void main(String[] args) throws Exception {
        try (RedisClient client = new RedisClient("127.0.0.1", 3333)) {
            // todo 配置化,从配置文件导入(需要在配置文件里面写出来 命令,对应的response类型名字)
            CountDownLatch c = new CountDownLatch(5000);
            // 回调里面是不允许有阻塞操作的,但是回调也可能出错,因为eventLoop很可能已经全部关闭了,这样导致没有办法提交任务
            for (int _i = 0; _i < 5000; _i++) {
                final int i = _i;
                client.setAsync(_i + "", "你好,猪精 " + _i).addListener(future -> {
                    RedisFuture r = client.getAsync(i + "");
                    r.addListener(f -> {
                        System.out.println(f.get());
                        c.countDown();
                        System.out.println(c.getCount());
                    });
                });
            }
            c.await();
        }
        RedisClient.stop();//
    }
}