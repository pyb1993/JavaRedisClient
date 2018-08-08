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

解释: 实际上就是传统的回调写法,注意`addListener`实际上是Netty提供的,如果添加很多`listener`,它会一个接着一个执行,而且`listener`里面不能写阻塞操作,所以如果里面有异步操作,就只能回调嵌套回调。

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
	 

---
### Version-2改进
改造来`RedisFuture`结构,在保留原有回调方式的情况下,提供一种性能稍差,但是可以消除**回调地狱**的方式
####  1.思路
受到javascript Promise这种调用的启发,所以模仿着在java里面弄了一个,但是区别在于Promise不能取消,而且第一个逻辑是立刻执行的。
首先将RedisFuture进行扩展,类型的定义如下:

	
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
	    ...
    }
listener和以前类似,只有在setSuccess被调用的时候才会被通知执行
利用这个特点,我们必须在listener里面设置好通知下一个回调开始执行的逻辑,我把这个函数封装为`notifyNextListener`
因此如果我们在listener里面又继续做了其它的异步操作,那么我们可以为这个异步操作设置一个回调,在这个回调里面调用`notifyNextListener`

看看 `notifyNextListener`怎么定义:

       public void notifyNextListener(Object result){
        if(next != null){
            next.setSuccess(result);
        }
     }
     
再看看`setSuccess`

         @Override
    public RedisFuture setSuccess(Object result){
        super.setSuccess(result);
        // 异步执行listener(需要依靠listener「内部逻辑」来通知 next)
         if(listener != null){
             loop.execute(()-> listener.accept(this,result));
         }
         return this;
     }
其实除了原有的`ChannelPromise`的逻辑,就只增加了一个对`listener`的调用而已

那么`next`是在什么地方生成的? 又是怎么和`listener`绑定的，看上面的代码可以知道是在`then`这个函数生成的,
所以`then`实际上就是一个构造函数而已,生成一个新的`RedisFuture`而已,这样就可以实现链式调用,把异步的操作看起来像同步的写法一样,实际上是我把异步的回调细节隐藏起来而已。
这样回调嵌套最多不超过2层,但是性能会稍微差一些

----
例子:

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


很明显,我们可以利用`addListener`来控制什么时候执行下一个回调,而不是和原来一样一旦第一个回调执行完就执行第二个
