package xmpp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

import static io.netty.handler.codec.http.HttpMethod.GET;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.util.ReferenceCountUtil;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Slightly grubby websocket server writted in Netty. Issues include:
 * - There are better classes for supporting the websocket protocol now, and 
 * 		this thing could probably be reduced to about 3 lines
 * - It doesn't seem to handle continuation frames, which will affect large
 * 		
 * Please feel free to rewrite or replace */
public class WSServer {
	protected static final Logger log = LogManager.getLogger(WSServer.class);

	private BiFunction<Channel, FullHttpRequest, HttpResponse> httpHandler;
	private BiConsumer<Channel, FullHttpRequest> wsUpgrade;
	private BiConsumer<Channel, byte[]> wsHandler;
	private Consumer<String> cbMonitor;
	
	public WSServer(int port) {
		this.port = port;
	}
	
	public WSServer httpHandler(
		BiFunction<Channel, FullHttpRequest, HttpResponse> httpHandler
	) {
		this.httpHandler = httpHandler;
		return this;
	}
	
	public WSServer wsUpgrade(BiConsumer<Channel, FullHttpRequest> wsUpgrade) {
		this.wsUpgrade = wsUpgrade;
		return this;
	}
	
	public WSServer wsHandler(BiConsumer<Channel, byte[]> wsHandler) {
		this.wsHandler = wsHandler;
		return this;
	}

	public WSServer monitor(Consumer<String> cb) {
		cbMonitor = cb;
		return this;
	}

	private class Handler extends ChannelInboundHandlerAdapter {
		private WebSocketServerHandshaker handshaker;

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			try {
				if (msg instanceof FullHttpRequest) {
					http(ctx, (FullHttpRequest) msg);
				} else if (msg instanceof WebSocketFrame) {
					websocket(ctx, (WebSocketFrame) msg);
				} else {
					ReferenceCountUtil.release(msg);
				}
			} catch (Throwable e) {
				log.error("Error processing request", e);
				if (cbMonitor!=null) cbMonitor.accept(e.getMessage());
			}
		}

		@Override
		public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
			ctx.flush();
		}
		
		private boolean isUpgrade(HttpRequest req) {
			return req.method() == GET && req.headers().contains("Upgrade");
		}
		
		private void http(final ChannelHandlerContext ctx, FullHttpRequest req) {
			HttpHeaders head = req.headers();
			Channel ch = ctx.channel();
			// Handle a bad request.
			if (!req.decoderResult().isSuccess()) {
				ch.close();
			} else if (isUpgrade(req)) {
				WebSocketServerHandshakerFactory wsFactory = 
					new WebSocketServerHandshakerFactory(
						"wss://" + head.get(HttpHeaderNames.HOST),
						"xmpp", false, Integer.MAX_VALUE
				);
				handshaker = wsFactory.newHandshaker(req);
				if (handshaker == null) {
					log.debug("Hit unsupported");
					WebSocketServerHandshakerFactory
						.sendUnsupportedVersionResponse(ch);
				} else {
					handshaker.handshake(ch, req);
					if (wsUpgrade!=null) wsUpgrade.accept(ch, req);
				}
			} else {
				if (httpHandler!=null) {
					HttpResponse res = httpHandler.apply(ch, req);
					ChannelFuture f = ch.writeAndFlush(res);
					if (!HttpUtil.isKeepAlive(req)) {
						f.addListener(ChannelFutureListener.CLOSE);
					}
				}
			}
			
			// TODO: proably matters httpRes.release();
		}
		
		private void websocket(ChannelHandlerContext ctx, WebSocketFrame f) {
			Channel ch = ctx.channel();
			if (f instanceof CloseWebSocketFrame) {
				handshaker.close(ch, (CloseWebSocketFrame) f);
			} else if (f instanceof PingWebSocketFrame) {
				ctx.write(new PongWebSocketFrame(
					f.isFinalFragment(), f.rsv(),
					f.content()), ctx.voidPromise());
			} else if (f instanceof BinaryWebSocketFrame) {
				if (wsHandler!=null) {
					wsHandler.accept(ch, bufToBytes(f.content()));
				}
				// } else if (frame instanceof ContinuationWebSocketFrame) {
				// ctx.write(frame, ctx.voidPromise());
				f.release();
			} else if (f instanceof TextWebSocketFrame) {
				log.error("text frame {}", f);
				if (wsHandler!=null) {
					wsHandler.accept(ch, bufToBytes(f.content()));
				}
				// } else if (frame instanceof ContinuationWebSocketFrame) {
				// ctx.write(frame, ctx.voidPromise());
				f.release();
			} else if (f instanceof PongWebSocketFrame) {
				f.release(); // Ignore
			} else {
				throw new UnsupportedOperationException(String.format(
					"%s frame types not supported", 
					f.getClass().getName()
				));
			}
		}
		
		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
			throws Exception {
			cause.printStackTrace();
			ctx.close();
		}
	}
	private final int port;
	private ChannelFuture f;
	public void run() throws InterruptedException {
		EventLoopGroup group = new NioEventLoopGroup();
		ServerBootstrap b = new ServerBootstrap();
		b.group(group).channel(NioServerSocketChannel.class)
			.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
			.option(ChannelOption.SO_BACKLOG, 1024)
			.childHandler(new ChannelInitializer<SocketChannel>() { // (4)
				@Override
				public void initChannel(SocketChannel ch) throws Exception {
					ch.pipeline()
						.addLast("decoder", new HttpRequestDecoder())
						.addLast("aggregator", new HttpObjectAggregator(2300000))
						.addLast("encoder", new HttpResponseEncoder())
						.addLast("handler", new Handler());
				}
			});
		f = b.bind(port);
		log.info("binding to port {}", port);
		f.addListener(new FutureLambda<ChannelFuture>(
			i->log.info((i.isSuccess() ? "bound" : "failed to bind") + " to port {}", port)));
		f.channel().closeFuture()
			.addListener(new FutureLambda<ChannelFuture>(i-> group.shutdownGracefully()));

	}
	
	public void stop() {
		f.channel().disconnect();
	}

	public static byte[] bufToBytes(ByteBuf buf) {
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		return bytes;
	}
	
	public void writeToChannel(Channel ch, byte[] data) {
		if (data.length>0) ch.writeAndFlush(
			new BinaryWebSocketFrame(Unpooled.copiedBuffer(data))
		);
	}
	
	public void writeToChannel(Channel ch, String data) {
		if (data.length()>0) ch.writeAndFlush(
			new TextWebSocketFrame(data)
		);
	}
	
	public static void main(String[] args) throws InterruptedException {
		WSServer wss = new WSServer(8080);
		wss.wsUpgrade((u,i)->log.error("upgrade"))
			.wsHandler((u,i)->{
				log.error("got {}", i);
				wss.writeToChannel(u, "you said "+new String(i));
			})
			.wsUpgrade((ch, d)->wss.writeToChannel(ch, "oh hai"))
			.run();
	}
}
