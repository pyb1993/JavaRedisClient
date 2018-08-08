package RedisClient;

import RedisClientHandler.RedisResponseHandler;
import RedisResponseDecoder.ResponseDecoder;
import Util.Logger;
import com.alibaba.fastjson.JSON;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;


/*
* 客户端使用Netty来实现
* 首先生成对应的bootstrap
* 然后所有的命令都是通过对应的channel实现的,逻辑如下:
* 1. 首先检查是否链接上了
*       如果没有链接上,调用getConnection() -> 从链接池里面获取链接／重新创建一个新的链接
* 2. 获取链接之后执行对应的逻辑,得到返回的future,可以添加Listener/callBack
* 3. 所有的操作都是通过异步执行的,如果需要同步执行可以调用sendSync
* 4. 功能
*       容量:  连接池,维护长链接,容量上限默认设置为128(超过这个范围的不维护)
*       过期:  服务器会因为连接长期不活动而过期,所以这里的通道可能会关闭。
*       超时:  todo 客户端应该可以设置读写的超时时间(同步操作简单,异步的话需要利用定时任务(xs 以后没有完成就直接抛出异常))
*       引用:  每一个handler都应该持有链接池的引用
*       线程:  因为客户端自己可能是多线程的,所以从连接池获取连接的时候要保证线程安全
*       线程:  网络IO就一个线程,理论上足够了,除了网络之外的逻辑是非常快的,可以直接在IO线程里面处理(同时也保证不会影响业务线程)
*       扩展:  一个客户端可以同时支持多个redis操作同时发起(不是pipeline,而是多个连接),所以这里需要handler来保存状态
*       异步:  客户端可以异步/同步 操作,那么对于异步操作,可以增加callback(调用addListener函数,注意回调在eventLoop执行)
*       关闭:  当一个客户端实例被关闭的时候,整个eventLoop都应该被关闭
*
* 5. ToDo:
*       1 维护多个host,port的情况,到时候每个client自己享有一个pool的引用,而整个类则持有一个ConCurrentHashMap<Host+port, pool> 的map
*       2 做到 类型js的promise的地步,消除 callback hell,思路:
*           首先会尝试使用
*
*
* */
public class RedisClient implements AutoCloseable {
    static private ConnectionPool pool = new ConnectionPool(128);// 一个进程中共享一个链接池
    static private EventLoopGroup group = new NioEventLoopGroup(1);// 一个进程中共享一个eventLoop

    private Bootstrap bootstrap;
    public RedisClient(String host, int port){
        this(host,port,null);
    }

    // 将整个eventLoop都关闭
    public static void stopGracefully(){
        group.shutdownGracefully();// 等到目前所有的listener都执行完
        pool.clear();
    }

    public static void stop() throws Exception{
        pool.clear();
        group.shutdownGracefully().sync();// 等到目前所有的listener都执行完
    }


    /* 这里没有办法使用内部类加载的单例模式,因为没有办法在最开始知道对应的Config  */

    /** @params config 用来初始化响应类型
     *  这样接下来的修改很容易, 如果调用者想要自定义命令,那么只需要自己将 extends RedisClient,
     *  然后将构造函数里面的调用的 config参数给修改成自己的新增加的内容
     *  然后再增加几个命令就好
     * **/
    public RedisClient(String host, int port, Map<String, Class<?>> config) throws RedisException{
        this.bootstrap = new Bootstrap();
        this.bootstrap.group(group).
                channel(NioSocketChannel.class).
                remoteAddress(new InetSocketAddress(host, port)).
                handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new ResponseDecoder());
                    }});

        HashMap<String,Class<?>> defaultConfig = new HashMap<>(){{
            put("get",String.class);
            put("incr", Integer.class);
            put("set", String.class);}};

        addToRegister(defaultConfig);
        addToRegister(config);
    }


    /**
     * TODO 利用反射自动注册,获取所有除了辅助方法之外的对象
     *
     *
     * **/
    public void addToRegister(Map<String, Class<?>> config){
        // 初始化注册类型
        if(config == null || config.isEmpty()){
            return;
        }
        for (Map.Entry<String, Class<?>> e : config.entrySet()) {
            ResponseRegister.register(e.getKey(), e.getValue());
        }
    }


    // 注册服务,添加自定义的类型
    public RedisClient rpc(String type, Class<?> clazz) {
        ResponseRegister.register(type, clazz);
        return this;
    }

    public void close(){
        // 这里不会真的关闭整个客户端的eventLoop,因为客户端实际上没有持有任何资源,所有的channel都是属于pool的
        // 由于竞争的客户端少了一个,所以选择从pool里面移除一个connection(Channel)
        pool.removeOne();
    }

    // 发送命令,获得请求
    public RedisFuture send(String type, Object payload) throws RedisException {
        try {
            return sendInternalAsync(type,payload);
            //return this.sendInternal(type, payload);
        } catch (Exception e) {
            throw new RedisException(e);
        }
    }

    /* 同步调用的版本
    * */
    public Object sendSync (String type, Object payload) throws RedisException {
        try {
            Channel output = getChannel();
            RedisFuture future = new RedisFuture(group.next());// 这里只有一个eventLoop
            RedisFuture f = this.sendInternal2(output,future,type, payload);
            Object ret = f.get();
            return ret;
        } catch (Exception e) {
            throw new RedisException(e);
        }
    }


    public void onResponseDone(Channel notifyChannel){
        pool.addOrClose(notifyChannel);
    }

    // 对应的channel已经关闭了,似乎什么也不需要做
    public void onChannelClose(Channel notifyChannel){

    }

    /***************** 各种业务命令 ******************/
    // 只能处理String
    public String get(String key) {
        String ret = (String)sendSync("get", key);
        return ret;
    }

    public RedisFuture getAsync(String key){
        return send("get", key);
    }

    public Integer incr(int delta){
        String ret = (String)sendSync("incr", delta);
        return Integer.parseInt(ret);
    }

    // 只能处理String
    public void set(String key, String value){
        sendSync("set",new RedisInputStringPair(key,value));
    }

    // 异步处理
    public RedisFuture setAsync(String key, String value){
        return send("set",new RedisInputStringPair(key,value));
    }

    // 只能处理hash
    public void hset(String key, String field, String value){
        sendSync("hset",new RedisInputStringList(key,field,value));
    }


    //
    public String hget(String key, String field){
        return (String) sendSync("hget",new RedisInputStringPair(key,field));
    }




    /* 辅助函数,也是真正的业务逻辑
     *  对比sendInternal版本,里面有个getChannel实际上是阻塞的操作(当然如果连接池有可用连接就不会阻塞)
     *  所以这里连getChannel()都变成异步连接了,实现逻辑如下:
     *         结构: Future2本身被Handler所持有,依靠RedisResponseHandler来设置Future的成功,
     *               然后Future2调用notifyListener,用来设置Future1的成功
     *         结论: 构造这样的数据结构即可达到目的:(A---->B 代表 B持有A的引用)
     *               Future(Parameters)------>handler1(需要持有type和payLoad,控制连接成功的过程)
     *                  |                           |
     *                  |                           |(Active时调用senInternal2,这样将添加上新的Handler(Decoder,ResponseHandler)
     *                  |                           | 同时需要让ResponseHandler 持有Future)
     *                  |-------------->handler2(Read的时候将会设置Future1成功)
     * */
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

    /*
    * 返回一个future,这个future可能包含(连接,发送,获取三个步骤的逻辑),全程异步操作
    *      * 如果能够直接获得Channel(线程池有缓存)
     *       step1: 我们尝试获取channel,如果获取失败,那么执行上述步骤,如果成功,执行step2
     *       step2: 首先拿到了一个Channel(移除所有的handler),然后直接再上面添加两个Handler,最后构造Future
     *     * 否则:
     *          先构造出future和handler,将上面的逻辑移动到ConnectionActiveHandler里面执行
    * */
    private RedisFuture sendInternalAsync(String type, Object payload) throws Exception{
        RedisFuture future = new RedisFuture(group.next());
        Channel output = pool.getConnection();

        if(output != null){
            // 已经有缓存好的连接了,那么直接执行sendInternal2
            return sendInternal2(output,future,type,payload);
        }else{
            // 需要创建一个新的channel,并且在connect完成之后实现新的操作(将future和新的handler绑定起来)
            ChannelFuture cFuture = bootstrap.connect();
            cFuture.addListener(f -> {
                if(f.isSuccess()) {
                    sendInternal2(cFuture.channel(), future, type, payload);
                }else{
                    future.setFailure(f.cause());
                    Logger.log("connect failed: \n"+ f.cause());
                }
            });
        }

        return future;
    }


    private Channel getChannel() throws Exception{
        Channel output = pool.getConnection();

        // 如果cachePool里面全部都失效了,那么需要new 一个新的出来
        // 如果这里采取异步的调用,那么会导致整个逻辑处理非常的费劲,由于大部分情况都不会走到这,所以同步
        /* 导致的一个问题是,这里实际上是阻塞操作,所以如果在 addListener里面的回调加入这个东西,
          会导致抛出 io.netty.util.concurrent.BlockingOperationException*/
        if(output == null){
            output = bootstrap.connect().sync().channel();
        }

        // 移除可能的handler,这是因为handler是是和Redisfuture相互绑定的,每一次调用都是新的future
        while (output.pipeline().last() != null){
            output.pipeline().removeLast();
        }
        return output;
    }


    private void writeStr(ByteBuf buf, String s) {
        buf.writeInt(s.getBytes(CharsetUtil.UTF_8).length);
        buf.writeBytes(s.getBytes(CharsetUtil.UTF_8));
    }

    public static void main (String[]args) throws Exception {
    }

}

/* 描述 连接池,是被RedisClient整个类共享,整个进程一起使用
*  目的 维持长链接
*  维护 超时的链接会从缓存里面移除
*  性能 这里不要直接调用size函数,这是O(n)的操作
*  存在两种情况: 1 pool里面没有可用的channel,那么会一直poll,直到为空
*              2 connection目前有剩余,那么直接返回一个connection
*
* */
class ConnectionPool{
    private class CachedSet extends ConcurrentLinkedQueue<Channel> { }

    final int capacity;
    final AtomicInteger size = new AtomicInteger(0);
    private CachedSet cachePool = new CachedSet();

    public ConnectionPool(int capacity){
        this.capacity = capacity;
    }

    public  Channel getConnection(){
        Channel ret = null;
        while (ret == null && size.get() > 0){
            ret = cachePool.poll();
            size.decrementAndGet();
            if(ret != null && !ret.isActive()){
                ret = null;
            }
        }
        return  ret;
    }

    // 这里会有一点小小的race condition,但是即便略微超过capacity也没有关系
    public void addOrClose(Channel ch){
        if(ch != null && size.get() < capacity){
            cachePool.offer(ch);
            size.incrementAndGet();
        }else{
            ch.close();
        }
    }

    // 移除一个connection(目前是因为一个客户端关闭,所以暂时不需要维持那么多连接)
    // 因为从 get() > 8 到 poll之间可能有数据竞争,所以需要double check
    public void removeOne(){
        if(size.get() > 8){
            Channel removed = cachePool.poll();
            if(removed != null){
                removed.close();
            }
            size.decrementAndGet();
        }
    }

    // 注意这里的顺序,因为我们都是依赖size来判断还有多少的,所以应该先设置 size
    public void clear(){
        size.set(0);
        cachePool.clear();
    }
}

// 用来传递两个String的结构
class RedisInputStringPair{
    String first;
    String second;
    public RedisInputStringPair(String first,String second){
        this.first = first;
        this.second = second;
    }
    public String getFirst(){
        return  first;
    }

    public String getSecond(){
        return second;
    }

    public void setFirst(String first){
        this.first = first;
    }

    public void setSecond(String second){
        this.second = second;
    }
}

// 用来传递两个String的结构
class RedisInputStringList{
    public RedisInputStringList(String ... args){
        arr = arr = new ArrayList<String>();
        for (String str : args){
            arr.add(str);
        }
    }

    public ArrayList<String> arr;
    public ArrayList<String> getArr(){return arr;}
}




