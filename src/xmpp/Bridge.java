package xmpp;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import static io.netty.util.CharsetUtil.UTF_8;;

public class Bridge {
	static final Logger log = LogManager.getLogger(Bridge.class);

	public static class XMPPClient {
	    private final Channel ws;
		public final String xmppServer;
		
	    public XMPPClient(Channel ws, String xmppServer) {
	    	this.ws = ws;
			this.xmppServer = xmppServer;
	    }
	    
		public ChannelFuture f;
		EventLoopGroup group = new NioEventLoopGroup();
		private XmlStreamHeadDecoder decoder;
		
		/** The XMPP spec calls for an XML stream, which is a continuously-open
		 * XML document which is wrapped in a <stream:stream> base element.
		 * By the time they came to do the websockets version they'd sobered up
		 * and realised that was ridiculous. As a result the websocket version
		 * requires each XML stanza to be a complete discrete message. 
		 * 
		 * To support this, the opening <stream:stream> tag is replaced with an 
		 * <open> element, which takes all the same attributes, except that the
		 * namespace must be replaced with urn:ietf:params:xml:ns:xmpp-framing
		 * 
		 * TODO: the way this function does that is awful and probably only
		 * works for ejabberd. For decency's sake an XML parser or at least a 
		 * regex should be used to do the job properly */
		private void onHeader(String header) {
			decoder.started = true;
			header = header.replace("<stream:stream", "<open");
			writeToChannel(ws, 
				header.substring(0, header.length()-"jabber:client'>".length()) 
				+ "urn:ietf:params:xml:ns:xmpp-framing'/>");
		}
		
		/** As above. the closing </stream:stream> tag is replaced with a 
		 * <close> element with namespace urn:ietf:params:xml:ns:xmpp-framing
		 * */
		private void onStreamEnd() {
			decoder.started = false;
			writeToChannel(ws, 
				"<close xmlns=\"urn:ietf:params:xml:ns:xmpp-framing\"/>");
		}
		
		/** Normal messages mostly just get copied straight across. The only
		 * exception to this is messages which are in the stream namespace.
		 * the XMPP spec has these in the overall stream document which defines
		 * the stream namespace. For the separate documents in websockets you
		 * need to add the namespace definition in.
		 * 
		 * TODO: again this should absolutely be done through something a
		 * bit more robust */
		private void onStanza(String s) {
			if (s.startsWith("<stream:")) {
				int pos = s.indexOf(" ");
				int pos2 = s.indexOf(">");
				if (pos2>=0 && pos2<pos) pos = pos2;
				s = s.substring(0, pos) 
					+ " xmlns:stream=\"http://etherx.jabber.org/streams\"" + s.substring(pos);
			}
			writeToChannel(ws, s);
		}
		
		private void onMessage(String s) {
			//lazy. assume whole stanza will be there
			log.error("Proxying message");
			if (!decoder.started) onHeader(s);
			else if ("</stream:stream>".equals(s)) onStreamEnd();
			else onStanza(s);
		}
		
		private static final String STREAM_TAG_PART = 
			"<?xml version='1.0'?>\n"
			+ "<stream:stream xmlns='jabber:client'"
			+ " xmlns:stream='http://etherx.jabber.org/streams' ";
		
		private static final int OPEN_TAG_PART_LEN =
			"<open xmlns='urn:ietf:params:xml:ns:xmpp-framing'".length();
		

		/** This is the opposite of the above. All stanza messages remain the
		 * same, but the stream:stream open and close tags are altered.
		 * 
		 * For open, again all the attributes are retained, but the tagname is
		 * changed, it's changed to an open tag rather than an empty one, and
		 * this namespace is changed from the xmpp-framing one to the standard 
		 * stream and jabber client ones.
		 *  
		 * TODO: Use a real parser/regex. If the client doesn't include the
		 * ns declaration first, or puts in some whitespace this will break.
		 * Also both replacement namespaces are dodgy, but are the ones 
		 * ejabberd seems to be using */
		public void sendMessage(String xml) {
			if (xml.startsWith("<open")) {
				decoder.started = false;
				xml =  STREAM_TAG_PART + xml.substring(OPEN_TAG_PART_LEN);
				xml = xml.replace("/>", ">");
			}
			String xml2 = xml;
			f.addListener(new FutureLambda<ChannelFuture>(c->{
				c.channel().writeAndFlush(Unpooled.copiedBuffer(xml2.getBytes()));
			}));
		}
		
		public XMPPClient run() throws InterruptedException {
			Bootstrap b = new Bootstrap();
			b.group(group);
			
			b.handler(new ChannelInitializer<SocketChannel>() { // (4)
				@Override
				public void initChannel(SocketChannel ch) throws Exception {
					ch.pipeline()
						.addLast("decoder", decoder = new XmlStreamHeadDecoder())
						.addLast("handler", new ChannelInboundHandlerAdapter() {
					    @Override
						public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
							try {
								if (msg instanceof ByteBuf) {
									onMessage(((ByteBuf)msg).toString(UTF_8));
								} else log.error("not a bytebuf {}", msg);
							} catch (Throwable e) {
								log.error("Error processing request", e);
							}
						}

						@Override
						public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
							ctx.flush();
						}

						@Override
						public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
							throws Exception {
							cause.printStackTrace();
							ctx.close();
						}
					});
				}
			});
			b.channel(NioSocketChannel.class);
			f = b.connect(xmppServer, 5222);
			f.addListener(new FutureLambda<ChannelFuture>(i->{
				if (i.isSuccess()) {
					log.error("bound to {}:{}", xmppServer, 5222);
				} else {
					log.error("failed to bind to {}:{}", xmppServer, 5222);
				}
			}));
			return this;
		}
		
		public void stop() {
			f.channel().disconnect();
		}

		public static byte[] bufToBytes(ByteBuf buf) {
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			return bytes;
		}
	}	

	public static void writeToChannel(Channel ch, String data) {
		log.error("writing to WS {}", data);
		if (data.length()>0) ch.writeAndFlush(
			new TextWebSocketFrame(data.replace("<?xml version='1.0'?>", ""))
		);
	}
	
	public static final AttributeKey<XMPPClient> ak = 
		AttributeKey.newInstance("xmpp");
	
    public static void main(String[] args) throws InterruptedException {
		new WSServer(5280)
		/** Create an XMPP client connection to match, and store a link to it
		 * on the inbound websocket channel */
		.wsUpgrade((ch, req)->{
			try {
				ch.attr(ak).set(new XMPPClient(ch, args[0]).run());
			} catch (InterruptedException e) {
				log.error("Failed to create XMPPClient");
			}
		})
		.wsHandler((channel, payload)->{
			channel.attr(ak).get().sendMessage(new String(payload));
		})
		
		.run();
		//Don't let the app finish. This should be a thread join....
		while (true) Thread.sleep(1000);
	}

}
