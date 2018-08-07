package RedisClientHandler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.util.CharsetUtil;

// 这里之所以要继承SimpleChannelInboundHandler,是因为channelRead0之后,会释放msg的内存
public class EchoClientHandler extends SimpleChannelInboundHandler<ByteBuf> {
    // 每当链接完成的时候调用
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        FixedLengthFrameDecoder a = null;
        ctx.writeAndFlush(Unpooled.copiedBuffer("Netty rocks!", CharsetUtil.UTF_8));
        ctx.writeAndFlush(Unpooled.copiedBuffer("Netty rocks!", CharsetUtil.UTF_8));
    }

    // 当socket收到数据的时候,会调用这个回调,但是不确保只调用一次
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
        System.out.println("Client received length: " + msg.toString());
        System.out.println("Client received: " + msg.toString(CharsetUtil.UTF_8));
    }


    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }
}