package Test;
import RedisClient.RedisFuture;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import RedisClient.*;

public class ClientTest {
    /**
     * 测试所有异步set / get操作是否可行
     * 回调里面是不允许有阻塞操作的
     * 目前是2000左右的QPS(客户端的极限，不是服务器的)
     *
     *   修改成Channel多路复用之后效率大大提高,单线程可达到20000QPS左右(不算启动时间)
     *
     *
     **/

    @Test
    public void asyncGetSetTest() throws Exception{
        //Logger.setDebug();
        int connNum = 200000;
        CountDownLatch c = new CountDownLatch(connNum);//todo XXXX
        try (RedisClient client = new RedisClient("127.0.0.1", 3333)) {
            // todo 配置化,从配置文件导入(需要在配置文件里面写出来 命令,对应的response类型名字)
            testForOne(client,c,connNum);
        }
        c.await();
        RedisClient.stop();//
    }

    @Test
    public void asyncGetSetTest2() throws Exception{
        //Logger.setDebug();
        int connNum = 10000;
        CountDownLatch c = new CountDownLatch(connNum);//todo XXXX
        try (RedisClient client = new RedisClient("127.0.0.1", 3333)) {
            testForThen(client,c,connNum);
        }
        c.await();
        RedisClient.stop();//
    }

    /**
     * 异步多线程测试
     * 目前是5000左右的QPS(8个线程，4线程)
     *
     * UPDATE:可到达23000QPS左右(启用了Channel的多路复用)
     *
     * */
    @Test
    public void asyncGetSetTestMultiThread() throws Exception{
        int ThreadNum = 4;
        ExecutorService executors = Executors.newFixedThreadPool(ThreadNum);
        int connNum = 200000;
        int taskLoad = connNum / ThreadNum;
        CountDownLatch c = new CountDownLatch(connNum);
        RedisClient client = new RedisClient("127.0.0.1", 3333);

        for(int _k = 0; _k <  ThreadNum; _k++){
            executors.execute(() -> { testForOne(client,c,taskLoad);});
        }

        c.await();
        executors.shutdown();
        executors.awaitTermination(25,TimeUnit.SECONDS);
    }


    /**
    * 测试同步的情况下操作命令,单线程操作
    * 操作10万次,耗时15s
    * 基本上6000左右的QPS
     *  update: 当我们使用socket复用的时候,做到的QPS是7500左右
    * */
    @Test
    public void getSetTest(){
        int connNum = 100000;
        CountDownLatch c = new CountDownLatch(connNum);//todo XXXX
        RedisClient client = new RedisClient("127.0.0.1", 3333);
        for(int i = 0; i < connNum; ++i){
            String value = "第" + i + "次给你的爱";
            client.set(i + "",value);
            String result = client.get(i + "");
            assert result.equals(value);
        }
    }

    /**
     * 测试同步的情况下多线程操作的情况
     * 一共10万次操作,分解成 2个任务 12s,8300QPS
     *                  4个任务 耗时9s,11000QPS
     *                  6个任务(6线程),8s,1160QPS
     *                  8个任务(8线程),7.6s 13000QPS
     *
     *   UPDATE:   使用Channel多路复用以后, 8线程 QPS达到16000
     * **/
    @Test
    public void getSetTestMultiThread() throws Exception{
        int ThreadNum = 8;
        ExecutorService executors = Executors.newFixedThreadPool(ThreadNum);
        int connNum = 200000;
        int taskLoad = connNum / ThreadNum;
        RedisClient client = new RedisClient("127.0.0.1", 3333);
        for(int _k = 0; _k <  ThreadNum; _k++){
            final int k = _k;
            executors.execute(() -> {
                for(int i = k * taskLoad ; i < taskLoad * (k + 1); ++i){
                String value = "第" + i + "次给你的爱";
                client.set(i + "",value);
                String result = client.get(i + "");
                assert result.equals(value);
            }});
        }
        executors.shutdown();
        executors.awaitTermination(25,TimeUnit.SECONDS);
    }

    @Test
    public void hsetGetTest() throws Exception{
        int connNum = 100000;
        CountDownLatch c = new CountDownLatch(connNum);//todo XXXX
        RedisClient client = new RedisClient("127.0.0.1", 3333);
        for(int i = 0; i < connNum; ++i){
            String value = i + "";
            client.hset("hashTest","h-" + i,value);
            String result = client.hget("hashTest","h-" + i);
            assert result.equals(value);
        }
    }


    /**
     * 用来执行异步测试,将被其它测试接口调用
     * client, countDownLatch c(用来进行同步,确保所有回调执行完)
     *
     *
     * **/
    private void testForThen(RedisClient client,CountDownLatch c,int taskLoad){
        for (int _i = 0; _i < taskLoad; _i++) {
            final int i = _i;
            String val = "(hello)你好,猪精(pig monster)" + _i;
            client.setAsync(_i + "",val ).then((future,result) -> {
                RedisFuture r = client.getAsync(i + "");
                r.addListener(f -> future.notifyNextListener(f.get()));  // 当get完成的时候通知下一个回调执行
            }).then((future,result)->{
                c.countDown();
                assert result.equals(val);
                future.notifyNextListener(1);
            });
        };
    }


    private void testForOne(RedisClient client,CountDownLatch c,int taskLoad){
        for (int _i = 0; _i < taskLoad; _i++) {
            final int i = _i;
            String val = "你好,master" + _i;
            client.setAsync(_i + "",val ).addListener(future -> {
                RedisFuture r = client.getAsync(i + "");
                r.addListener(f -> {
                    c.countDown();
                    String result = (String)f.get();
                    assert result.equals(val);
                });
            });
        };
    }
}
