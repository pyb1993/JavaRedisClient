### 这是一个用Netty实现的Redis client客户端

###   目的1 配合JavaRedis服务器进行调试
###  目的2 用来学习各种异步回调/future的机制

---
### 目前实现的功能:

```
1. 同步调用
2. 异步调用 + future回调(可以嵌套使用)
3. 多线程 同步 异步 调用
4. 连接池缓存(超时无效)
```

### 使用的方法

    同步使用:
    @Test
    public void getSetTest(){
        int connNum = 100000;
        CountDownLatch c = new CountDownLatch(connNum);//todo XXXX
        RedisClient client = new RedisClient("127.0.0.1", 3333);
        for(int i = 0; i < connNum; ++i){
            String value = "第" + i + "次";
            client.set(i + "",value);
            String result = client.get(i + "");
            assert result.equals(value);
        }
    }

    异步调用
    @Test
    public void asyncGetSetTest() throws Exception{
        //Logger.setDebug();
        int connNum = 10000;
        CountDownLatch c = new CountDownLatch(connNum);//todo XXXX
        try (RedisClient client = new RedisClient("127.0.0.1", 3333)) {
            // todo 配置化,从配置文件导入(需要在配置文件里面写出来 命令,对应的response类型名字)
            testForOne(client,c,connNum);
        }
        c.await();
        RedisClient.stop();//
    }
    /**
     * 用来执行异步测试,将被其它测试接口调用
     * client, countDownLatch c(用来进行同步,确保所有回调执行完)
     * **/
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


---




### Version-1架构
使用了Netty实现的客户端,保存了一个线程池,使用Netty原生的回调进行简单的包装。
####  连接池:
		每一个客户端实际上都是共享唯一的链接池的,也是共享唯一的EventLoop的
#### 同步和异步:
		异步则是利用来Netty自己的ChannelFuture来实现,在此基础上进行了封装
		从发起链接到发送命令一直到命令结束,全部都是异步操作
		同步调用实际上是用异步调用实现的,方法是利用netty的`.sync`方法使得异步回调完成,关键点在于需要使用
####  关于多线程:
	 由于每一个命令都会创建一个Handler用来处理消息,所以各个handler之间并没有状态
	 唯一需要手动进行同步的地方实际上是线程池的部分,使用了一个`ConcurrentLinkedQueue`以及一个`AtomicInteger`来进行维护

#### 不足:
1. 最大的问题是存在回调地狱`callback hell`,因为回调里面不允许阻塞操作(netty的限制),所以导致只能写成回调嵌套回调的方式
2. 没有重连,负载均衡之类的功能
3. 读写超时的控制依靠服务器来实现,但是服务器是对长链接的控制,客户端自己也需要加上可配置的`readTimeout`,`writeTimeout`之类的参数,加上超时限制同时也会导致性能有所下降。
	 

