package RedisResponseDecoder;


import RedisClient.RedisException;
import RedisClient.ResponseRegister;
import RedisClientHandler.RedisResponseHandler;
import Util.Logger;
import Util.Resp;
import com.alibaba.fastjson.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.util.CharsetUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* 作用是将返回的数据解析成需要的Response
 * 同时将最终结果设置到对应的client上面,这样client就可以在操作完成之后执行了
 *
 */
public class ResponseDecoder extends ReplayingDecoder<Object> {
    static final int MAX_LEN = 1 << 20;
    static Set<String> protocalSet = new HashSet<>() {
        {this.add("get");
         this.add("set");
         this.add("del");
         this.add("hget");
         this.add("expire");
        }};

    public static boolean newProtocal(String key){
        return protocalSet.contains(key);
    }

    @Override
    // @todo 设置checkPoint
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Logger.debug("decode called");
        String requestId = readStr(in);
        String type = readStr(in);
        Class<?> clazz =  ResponseRegister.get(type);
        if (clazz == null) {
            throw new RedisException("unrecognized rpc response type=" + type);
        }
        // 反序列化json串
        String content = readStr(in);
        if(content == null){
            System.out.println(1111);
        }
        Logger.debug("read response: " + content);
        Object res;
        if(protocalSet.contains(type)){
            // 用自定义的协议来解析,目前使用的是简单文本协议
            // 目前暂时只有一种结构,就是单个字符串,所以不区分
            // todo 以后的结构以后再增加定义
            res = content;
        }else {
            // 用fastJSON来解析
            res = JSON.parseObject(content, clazz);
        }

        out.add(new Resp(requestId,res));
    }

    private String readStr(ByteBuf in) {
        // 字符串先长度后字节数组，统一UTF8编码
        int len = in.readInt();
        if (len < 0 || len > MAX_LEN) {
            throw new DecoderException("string too long len=" + len);
        }

        byte[] bytes = new byte[len];
        in.readBytes(bytes);
        return new String(bytes, CharsetUtil.UTF_8);
    }
}
