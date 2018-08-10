package RedisResponseDecoder;


import RedisClient.RedisException;
import RedisClient.ResponseRegister;
import RedisClientHandler.RedisResponseHandler;
import Util.Logger;
import Util.Resp;
import com.alibaba.fastjson.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.util.CharsetUtil;

import java.util.List;

/* 作用是将返回的数据解析成需要的Response
 * 同时将最终结果设置到对应的client上面,这样client就可以在操作完成之后执行了
 *
 */
public class ResponseDecoder extends ReplayingDecoder<Object> {
    static final int MAX_LEN = 1 << 20;


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
        Logger.debug("read response: " + content);
        Object res = JSON.parseObject(content, clazz);
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
