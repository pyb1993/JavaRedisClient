package RedisClient;

import RedisClientHandler.RedisResponseHandler;
import RedisResponseDecoder.ResponseDecoder;
import Util.Logger;
import Util.RedisConnection;
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

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


/**
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
*       复用:  基于Channel的多路复用,需要特别小心线程安全
*       关闭:  当一个客户端实例被关闭的时候,整个eventLoop都应该被关闭
*
* 5. ToDo:
*
*  todo 请求重试,这个需要依靠唯一ID的配合(如果需要去重),这样的话暂时和redis客户端协议就不一样了
*             所以可以先只重试ConnectionError这种操作
 *
 * todo 读写超时 这个只要超过多少s就进行超时(注意这个和连接池的空闲链接超时不是一回事,一般用定时事件实现,定时回调需要设置future对Setfailture)
 *
*
* */
public class RedisClient implements AutoCloseable {
    static private ConnectionPool pool = new ConnectionPool(512);// 一个进程中共享一个链接池
    static private EventLoopGroup group = new NioEventLoopGroup(1);// 一个进程中共享一个eventLoop
    static AtomicInteger x = new AtomicInteger(0);

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
            put("set", String.class);
            put("hget", String.class);
            put("hset", String.class);

        }};
        pool.init(bootstrap);
        addToRegister(defaultConfig);
        addToRegister(config);
        System.out.println("init done");
    }


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
    // todo 异步调用,交给客户端去重试
    public RedisFuture send(String type, Object payload) throws RedisException {
        try {
            return sendInternalAsync(type,payload);
            //return this.sendInternal(type, payload);
        } catch (Exception e) {
            throw new RedisException(e);
        }
    }

    /* 同步调用的版本,没有使用send来实现,因为这样效率
       todo 同步调用, 这里可能会抛异常,重试机制
    * */
    public Object sendSync (String type, Object payload) throws RedisException {
        try {
            RedisConnection output = getChannel();
            RedisFuture future = new RedisFuture(group.next());// 这里只有一个eventLoop
            RedisFuture f = this.sendInternal2(output,future,type, payload);
            Object ret = f.get();//
            return ret;
        } catch (Exception e) {
            throw new RedisException(e);
        }
    }


    public void onResponseDone(RedisConnection notifyConnection){
        //pool.add(notifyConnection);
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
        结构: Future本身被Handler所持有,依靠RedisResponseHandler来设置Future的成功,
     *         然后在收到服务器回答时候在handler设置future成功
     *         构造这样的结构即可达到目的:
     *               Future(Parameters)------> listener callback(需要持有type和payLoad,控制连接成功的过程)
     *                  |                           |
     *                  |                           | 回调时调用SendInternal2
     *                  |                           |
     *                  |-------------->decoder,responseHandler(Read的时候将会设置Future1成功)
     * */
    private RedisFuture sendInternal2(RedisConnection connection, RedisFuture future, String type, Object payload) throws Exception {
        // 下面执行step2, 把命令写入逻辑开始执行
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer();
        final String reqId = RequestId.next();
        writeStr(buf, reqId);
        writeStr(buf, type);
        writeStr(buf, JSON.toJSONString(payload));
        Logger.debug(("send payload:" + JSON.toJSONString(payload)));

        /*
        * ChannelPipeline 是线程安全的， 这意味着N个业务线程可以并发地操作ChannelPipeline
          而不存在多线程并发问题。但是，ChannelHandler却不是线程安全的，这意味着尽管
          ChannelPipeline 是线程去全的， 但是仍然需要自己保证ChannelHandler的线程安全。
        * */
        Channel output = connection.channel();
        connection.incrCmd(); // 未完成命令数量加1

        if(connection.handlerReady()){
            // 这个Connection一定有handler,只需要直接添加future
            RedisResponseHandler notifier = connection.getResponseHandler();
            notifier.addFuture(reqId,future);
        }else{
            // 没有未完成命令: 移除所有的handler,保持干净的状态
            while (output.pipeline().last() != null){
                output.pipeline().removeLast();
            }

            // 首先要注意,为decoder设置reqId,用来在获取返回的结果的时候进行检查id是否能够匹配
            ResponseDecoder decoder = new ResponseDecoder();
            output.pipeline().addLast(decoder);

            // 设置第二个handler,需要该handler来通知future
            RedisResponseHandler notifier = new RedisResponseHandler(new ConcurrentHashMap<>(),this,connection);
            notifier.addFuture(reqId,future);
            output.pipeline().addLast(notifier);
        }

        // 将connection重新加入,以便后来的请求复用
        pool.add(connection);
        output.writeAndFlush(buf);
        // 需要在这里设置handler,异步的通知RedisFuture
        return future;
    }

    /*
    *      * 如果能够直接获得Channel(线程池有缓存)
     *       step1: 我们尝试获取channel,如果获取失败,那么执行上述步骤,如果成功,执行step2
     *       step2: 首先拿到了一个Channel(移除所有的handler),然后直接再上面添加两个Handler,最后构造Future
     *     * 否则:
     *          先构造出future和handler,将上面的逻辑移动到connect的回调执行
    * */
    private RedisFuture sendInternalAsync(String type, Object payload) throws Exception{
        RedisFuture future = new RedisFuture(group.next());
        RedisConnection output = pool.getConnection();

        if(output != null){
            // 已经有缓存好的连接了,那么直接执行sendInternal2
            Logger.show(output.channel() + " call ");
            return sendInternal2(output,future,type,payload);
        }else{
            // 需要创建一个新的channel,并且在connect完成之后实现新的操作(将future和新的handler绑定起来)
            // 注意,这里的回调会导致连接被放回连接池里面有一个延迟.如果不进行连接池预热会导致性能问题
            ChannelFuture cFuture = bootstrap.connect();
            cFuture.addListener(f -> {
                if(f.isSuccess()) {
                    sendInternal2(new RedisConnection(cFuture.channel()), future, type, payload);
                }else{
                    future.setFailure(f.cause());
                    Logger.debug("connect failed: \n" + f.cause());
                }
            });
        }

        return future;
    }


    private RedisConnection getChannel() throws Exception{
        RedisConnection output = pool.getConnection();
        // 如果cachePool里面全部都失效了,那么需要new 一个新的出来
        if(output == null){
            output = new RedisConnection(bootstrap.connect().sync().channel());
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
*  性能 这里不要直接调用size函数,这是O(n)的操作
*  存在两种情况: 1 pool里面没有「可用」的channel,那么会一直poll,直到为空
*              2 connection目前有剩余,那么直接返回一个connection
*
* */

class ConnectionPool{
    private class CachedSet extends ConcurrentLinkedQueue<RedisConnection> { }
    final int capacity;
    final AtomicBoolean rebalanceSubmit = new AtomicBoolean(false);
    final AtomicInteger size = new AtomicInteger(0);
    int cmdEachConnection = 50; // 每个connection默认的命令数量
    private CachedSet cachePool = new CachedSet();

    public ConnectionPool(int capacity){
        this.capacity = capacity;
    }

    // 初始化
    // todo 这里可以用多线程初始化
    public void init(Bootstrap bootstrap)  {
        int initialSize = (int)(capacity / 2) ;
        try {
            while (initialSize-- > 0){
                RedisConnection.incrementConnectionNum();// 为统计做准备
                add(new RedisConnection(bootstrap.connect().sync().channel()));
            }
        }catch (Exception e){
            e.printStackTrace();
            return;
        }
    }


    // 获取一个可用的链接
    // 注意避免进入死循环
    /**
    * 一个优化策略是:
     *     因为已经使用预加载的方式提前创建连接了
     *     所以当连接池上面的连接命令较多,但是还有可用连接的时候,并不产生新的连接
     *     而是直接在原来的连接上面复用,这样就不会在负载较大的情况下一次性产生过多连接造成错误
     *     一个策略是:
     *          如果发现连接池的数量已经大于等于capacity,那么拒绝创建新的连接
     *     只有连接池里面确实找不到可用的连接的情况下,才会考虑进行创建
    *
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

    // 这里会有一点小小的race condition(因为size 本身在 getConnection的地方也会被修改,同时在这个函数也会被修改),但是即便略微超过capacity也没有关系
    // 需要检查链接是不是可用的
    // addOrClose 这个操作,可以废弃了,因为handler本身并没有持有该channel
    public void addOrClose(Channel ch){
     /*    if(ch != null && ch.isActive() && size.get() < capacity){
            cachePool.offer(new Connection());
            size.incrementAndGet();
        }else{
            ch.close();
        }
      */
    }

    /**
     * 这里会有一点小小的race condition：
     *  因为size 本身在 getConnection的地方也会被修改,同时在这个函数也会被修改,但是即便略微超过capacity也没有关系
     *  另外需要考虑到如果超过了capacity,这时候可能存在正在执行的命令,所以不能关闭,只需要等待
     *  当所有的命令都执行完毕之后(cmdNum == 0),应该删除
     *  考虑 此时add操作的调用者是在EventLoop线程里面的,而客户端此时可能调用 incrCmd 的操作
     *  如果直接关闭,则可能导致刚从getConnection里面获取的Connection变得不可用
     *  所以需要提供如下保护:
     *      1 在准备close的时候,需要设置一个状态表示当前正要关闭
     *      2 在getConnection的时候,需要判断是否处理正在被关闭的状态,如果正在被关闭就不能选择它
     *      3 如果从getConnection状态直到放回add的过程中,是不允许Connection关闭的
     *      4 只有完全没有正在使用Connection才允许被关闭
     *      ----------------------------- 我是分割线----------------------------
     *      还有另外一种策略:
     *      1 就是不让handler来允许(不让eventLoop的线程来关闭,而是定时清理所有的handler)
     *      2 定时器将定时清理ConnectionPool,如果超过capacity就关闭那些没有任务正在允许的Connection
     *      3 这种定时直接利用并发容器自己就足够保证线程安全了
     *      4 因此,所有的add操作都会将channel加入pool,如果发现pool的size没有超过capacity的话,就不执行定时任务
     *      5 否则直接添加一个定时任务,并设置任务已经设置的状态
     *      6 定时任务:
     *           如果超过的不多可以依靠定时任务执行
     *           如果超过的很多需要立刻执行避免链接过多造成内存占用过大,或者超出客户端允许的范围(65536)
     *
     * **/
    public void add(RedisConnection connection){
        int curSize = size.get();
        EventLoop loop = connection.channel().eventLoop();
        if(connection != null && connection.channel().isActive()){
            cachePool.offer(connection);
            curSize = size.incrementAndGet();
        }

        // 对整个连接池进行维护,保证不会太大
        if(curSize > capacity){
            if(curSize < 2 * capacity){
                submitRebalanceTask(loop,2);
            }else{
                rebalanceNow();
            }
        }
    }


    public void removeOne(){
        rebalanceNow();
    }

    /**
     * 提交任务: 对整个连接池进行遍历处理
     * 如果已经有定时任务就不会提交
     * **/
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




