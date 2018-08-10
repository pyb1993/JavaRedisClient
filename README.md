### 这是一个用Netty实现的,支持多线程和异步调用,lock-free 的JavaRedis client客户端
###   目的1 配合JavaRedis服务器进行调试
###  目的2 用来学习各种异步回调/future的机制
### 目的3 用来学习线程安全的问题

---
### 目前实现的功能:

```
1. 同步调用
2. 异步调用 + future回调(可以嵌套使用)
3. 多线程 同步 异步 调用
4. 连接池缓存(超时无效)
```

### 看代码的思路:
	1 首先看初始化RedisClient的过程
	2 然后看异步命令 getAsync 的逻辑
		包括 连接的获取,编码/解码,handler的处理
	3 然后看同步命令 get的逻辑
	大致脉络就搞明白了


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

#### 大致思路:
		用decoder和fastJson进行编解码,用ResponseHandler处理命令完成时候的回调
		用连接池来提高性能,当连接不够当时候创建新的连接,命令完成的时候将创建的连接放回连接池
		用future代表异步回调,需要考虑connect的异步和发送命令的异步两个步骤

####  连接池:
		每一个客户端实际上都是共享唯一的链接池的,也是共享唯一的EventLoop的
#### 同步和异步:
		异步则是利用来Netty自己的ChannelFuture来实现,在此基础上进行了封装
		从发起链接到发送命令一直到命令结束,全部都是异步操作
		同步调用实际上是用异步调用实现的,方法是利用netty的`.sync`方法使得异步回调完成,关键点在于需要使用
####  关于多线程:
	 由于每一个命令都会创建一个Handler用来处理消息,所以各个handler之间并没有状态
	 唯一需要手动进行同步的地方实际上是线程池的部分,使用了一个`ConcurrentLinkedQueue`以及一个`AtomicInteger`来进行维护

#### 异步的具体实现:
##### 1. 首先是连接阶段的异步实现:
	
		由于并发较高的时候连接池里面不一定存在可用的线程,所以可能需要临时创建
		这导致发送命令被分成了两个异步阶段
		1 connect
		2 连接成功之后发送命令并等回复
	    为了实现这一点,基本逻辑如下：
	       
          如果能够直接获得Channel(连接池有缓存),那么就跳过第一步骤
          然后直接调用`sendInternal2`这个函数就可以了,这个函数里面包含了关于发送命令,为解析命令设置handler的所有操作
          否则,就在connect的回调里面实现:
          ChannelFuture cFuture = bootstrap.connect();
            cFuture.addListener(f -> {
                if(f.isSuccess()) {
                    sendInternal2(new RedisConnection(cFuture.channel()), future, type, payload);
                }else{
                    future.setFailure(f.cause());
                    Logger.debug("connect failed: \n" + f.cause());
                }
            });
	         
	
##### 2. 接着看发送命令的实现(sendInternal2):
    private RedisFuture sendInternal2(Channel output,RedisFuture future, String type, Object payload) throws Exception {
        // 下面执行step2, 把命令写入逻辑开始执行
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
        final String reqId = RequestId.next();
        writeStr(buf, reqId);
        writeStr(buf, type);
        writeStr(buf, JSON.toJSONString(payload));
        Logger.debug(("send payload:" + JSON.toJSONString(payload)));

        // todo 抽象为函数
        // 移除所有的handler,保持干净的状态
        while (output.pipeline().last() != null){
            output.pipeline().removeLast();
        }

        // 首先要注意,为decoder设置reqId,用来在获取返回的结果的时候进行检查id是否能够匹配
        ResponseDecoder decoder = new ResponseDecoder();
        decoder.setReqId(reqId);
        output.pipeline().addLast(decoder);

        // 设置第二个handler,需要该handler来通知future
        RedisResponseHandler notifier = new RedisResponseHandler(future,this);//notifier用来通知future什么时候结束
        output.pipeline().addLast(notifier);

        output.writeAndFlush(buf);
        // 需要在这里设置handler,异步的通知RedisFuture
        return future;
    }
		
解释: 

```
   结构: Future本身被Handler所持有,依靠RedisResponseHandler来设置Future的成功,
     *         然后在收到服务器回答时候在handler设置future成功
     *         构造这样的结构即可达到目的:
     *               Future(Parameters)------> listener callback(需要持有type和payLoad,控制连接成功的过程)
     *                  |                           |
     *                  |                           | 回调时调用SendInteral2
     *                  |                           | 
     *                  |-------------->decoder,responseHandler(Read的时候将会设置Future1成功)
```

----

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

------
### Version3 架构
#### 改进1:
		 实现了基于Channel的IO多路复用,极大的提高了客户端的吞吐量
		 这个改进对于服务器是透明的
	 
#### 改进2: 
	对连接池本身进行了改进,增加预热的功能,同时改善了以前并发过大的时候导致创建新连接过多的问题。

思路:
	所谓基于通道的IO多路复用,实际上就是在一个通道上面同时等待多个命令,类似与pipeline的机制。这和http2还不一样,http2本质上多个请求可以乱序执行,但是由于Javaredis自己是单线程	的模型,而且得到的结果一般非常短小,所以顺序执行足够快了(如果结果的量非常大,比如很多图片这样,那么就可以N个请求分别传输不同的图片)
	
所以需要做的是: **记录requestId 到 future之间的联系**,这样使得请求过来之后能够通知异步执行的future可以执行回调了;
虽然只有这么一点记录,但是实现起来有相当多的细节需要考虑,下面就来说说最关键的几个地方:

    1 线程安全: 相比v2版本,Channel在没有执行完当前命令的时候绝不会被其它客户端访问到。v3则不一样了,由于一个channel上可以有多个命令执行,首先获取连接池里面的channel这一步就必须保证线程安全。因为连接池里面的channel此时都是可能有没有执行玩的命令,随时可能被eventLoop进行访问的。
   
    同时还要考虑连接的关闭,这个时候关闭必须是在channel上面没有命令的情况下才能执行,这又涉及到线程之间状态的同步: eventLoop线程以及客户端线程。如果关闭的时候发现连接上没有命令,然后关闭。结果其它客户线程又在这个连接上加入新的命令就会出现严重问题。
	
 ---
	  2 连接池本身的优化:
		   挑选连接的规则也在发生变化,首先要定义一个合理的规则使得各个连接上面的命令数量差不多
		   其次检查连接池的连接的时候也存在线程安全的问题,因为handler此时还掌握着该连接(v2版本是完成命令时调用addOrClose将连接放回连接池), 如果此时handler把连接关闭了也是有严重问题的	
		  另外这里还需要考虑, 如果线程池没有合适的连接的情况下,怎么作出合理的决策:
			  1 创建新连接
			  2 直接在原来的连接上面复用命令
			  3 根据一定策略进行复用
			  
----

为了解决多线程问题,同时保证性能和代码逻辑的简单,我选择不进行复杂的状态控制:
		
		首先使用`ConcurrentLinkedQueue<RedisConnection>`, 这样就可以保证当前线程拿到的这个`Connection`是安全的(除了`eventloop`线程里面的`handler`也可能持有这个Connection的引用)
		当客户端线程执行完发送命令当步骤时,立刻将这个Connection放回pool里面,这样下一个客户端线程又有机会访问到它了
		从而实现了一个时间最多有两个线程持有一个连接的引用(eventloop和客户端线程)
		当eventloop线程处理命令完成的回调的时候,我们不对这个Connectin做任何修改(这样就彻底保证线程安全了)
		这样就可以保证线程安全了,但是问题转移了:
		什么时候删除那么超过capacity的连接呢?如果在add的时候发现超过capacity,那么就可以选择在当前线程执行`rebalanceNow`这个函数,它会立刻遍历整个连接池,同时关闭那些超过上限,且没有命令执行的连接(由于该连接没有命令执行所以一定不会被eventLoop线程操作,同时并发容器保证只能有一个线程从pool里面取出来,所以一定是线程安全的)
		这里可以进行一个小优化: 如果超过pool capacity数量不多,那么就可以选择延迟处理这个rebalance操作,如果超过的比较多,就在当前线程立刻执行(这样可以启到一个延缓客户端发送命令速度的作用)。
	
---
接下来要考虑连接池的策略:

		1 首先要考虑进行挑选:
    /**
     *   遍历整个pool,但是不需要精确的遍历,只要保证大部分连接都遍历过了就好,这样就避免的状态同步  
     *   如果这个连接上的命令超过 cmdEachConnection:
	 *        1.  当前存在的总的连接数量(在连接池里面的和不在连接池里面的) 大于 capacity,那么就选择当前这个连接
	 *        2. 否则把该连接放到队列尾部,继续遍历
     *     这种策略可以保证不会超过连接池的上限太多(只有在连接池所有的连接都被拿出去了的情况下才会创建新连接)
     *    同时需要记录连接的数量,如果没找到就意味着会创建新连接(RedisConnection.incrementConnectionNum();)    
    * -----------  为什么不在RedisConnection的构造函数里面使用  incrementConnectionNum()?因为Connect产生的异步回调会导致创建连接被延迟,然而实际上已经有很多连接必然被创建但是没有记录,这就会导致在接下来的一个阶段大量的连接被创建。所以我们直接记录了这个 趋势,而不是等到实际创建的时候再记录。
     *
    * */
    public  RedisConnection getConnection(){
        RedisConnection ret = null;
        int curSize = size.get();
        while (curSize-- > 0 && ((ret = cachePool.poll()) != null)){
            if(!ret.channel().isActive()){
                size.decrementAndGet();
            }else if(ret.getNum() > cmdEachConnection){
                if(RedisConnection.getTotalConnectionNum() >= capacity){
                    size.decrementAndGet();
                    break;// 总的连接数量已经很多了.所以直接复用当前的连接
                }else{
                    /*
                    * 当前状态: 总的连接数量还不多,所以可以继续往下找,即便找不到创建新连接可以接受
                    * 需要注意 ,如果 curSize这个时候刚好为0,就会导致ret被返回
                    * 而ret实际上已经被重新加入 cachePool 了,所以ret = null 这一句是必须的
                    * */
                    cachePool.add(ret);
                    ret = null;
                }
            }else{
                // 找到一个可用的connection
                size.decrementAndGet();
                break;
            }
        }
        if(ret == null){
            RedisConnection.incrementConnectionNum();
        }

        return  ret;
    }	
	
---
	2 考虑连接池的预热:
		为什么要预热连接池? 原因很多,但是我这里提供一个其它的角度。假设现在有大量的多线程 + 异步调用开始执行,
		如果线程池里面没有连接,就会导致新连接被大量创建,而由于新创建的连接有两个步骤的回调,所以被放回pool这个操作有一个延迟。从而这一段时间会有大量的连接被创建(因为连接池里面始终没有连接,所以只能创建连接)
		初始化的时候预热capacity * k(介于 0 到 1)

	3 考虑连接池的平衡
	    public void submitRebalanceTask(EventLoop loop,int delay){
        if (!rebalanceSubmit.compareAndSet(false,true)){return;}
        loop.schedule(()->{
                    this.rebalanceNow();
                    rebalanceSubmit.set(false);},
                delay,TimeUnit.SECONDS);
    }

    // 在当前线程立刻执行,无论是否有定时任务
    public void rebalanceNow(){
        int tmpSize = size.get();
        RedisConnection ret;
        while (tmpSize-- > 0){
            if((ret = cachePool.poll()) == null){ break;}// 队列是空
            // 这个时候不存在其它客户端访问到ret, 如果是idle那么一定可以安全删除
            if(ret.isIdle()){
                Logger.show("close channel" + ret.channel());
                ret.close();// 应该是这里主动将它关闭导致的
                size.decrementAndGet();
            }else{
                cachePool.add(ret);
            }
        }
    }

----
#### 性能改进：
对比V2版本提高了很多的性能(特别是异步操作)目前在我的机器上(Mac 2.3 GHz Intel Core i5，8 GB 2133 MHz LPDDR3)同时运行服务器和客户端。
	
	*O....* 同步单线程可以达到**7500QPS**
	
	*O...* 同步8线程可以达到**16000QPS**
	
	*OO...* 异步单线程: **20000QPS**
	
	*OO...* 异步4线程: **23000QPS**

  	
