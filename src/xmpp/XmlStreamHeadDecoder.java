package xmpp;

import java.util.List;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.CharsetUtil;

/** This decoder is the built-in netty one from 4.0.56, but mangled so it does
 * the opening and closing stanzas xml streaming protocols such as
 * <a href="http://xmpp.org/rfcs/rfc6120.html">XMPP</a>, where an initial xml 
 * element opens the stream and only gets closed at the end of the session. */
public class XmlStreamHeadDecoder extends ByteToMessageDecoder {

    public boolean started = false;
    private Consumer<String> infoLogger;

    public XmlStreamHeadDecoder() {
    	//ByteBufs do my head in. This will probably fail with a decent
    	//number of users
    	setDiscardAfterReads(100);
    }

    /** Instantiate with some logging if you want
     * @param infoLogger gets called each round with the buffer contents */
    public XmlStreamHeadDecoder(Consumer<String> infoLogger) {
    	this();
    	this.infoLogger = infoLogger;
    }

    @Override
    protected void decode(
    	ChannelHandlerContext ctx, ByteBuf in, List<Object> out
    ) throws Exception {
    	if (infoLogger!=null) infoLogger.accept(in.toString(CharsetUtil.UTF_8));
        boolean openingBracketFound = false;
        boolean atLeastOneXmlElementFound = false;
        boolean inCDATASection = false;
        long openBracketsCount = 0;
        int length = 0;
        int leadingWhiteSpaceCount = 0;

        for (int i = in.readerIndex(); i < in.writerIndex(); i++) {
            final byte readByte = in.getByte(i);
            if (!openingBracketFound && Character.isWhitespace(readByte)) {
                // xml has not started and whitespace char found
                leadingWhiteSpaceCount++;
            } else if (!openingBracketFound && readByte != '<') {
                // garbage found before xml start
              ctx.fireExceptionCaught(new 
            	  CorruptedFrameException("frame contains content before the xml starts"));
                in.skipBytes(1); //in.readableBytes());
                return;
            } else if (!inCDATASection && readByte == '<') {
                openingBracketFound = true;

                if (i < in.writerIndex() - 1) {
                    final byte peekAheadByte = in.getByte(i + 1);
                    if (peekAheadByte == '/') {
                        // found </, decrementing openBracketsCount
                        openBracketsCount--;
                    } else if (isValidStartCharForXmlElement(peekAheadByte)) {
                        atLeastOneXmlElementFound = true;
                        // char after < is a valid xml element start char,
                        // incrementing openBracketsCount
                        openBracketsCount++;
                    } else if (peekAheadByte == '!') {
                        if (isCommentBlockStart(in, i)) {
                            // <!-- comment --> start found
                            openBracketsCount++;
                        } else if (isCDATABlockStart(in, i)) {
                            // <![CDATA[ start found
                            openBracketsCount++;
                            inCDATASection = true;
                        }
                    } else if (peekAheadByte == '?') {
                        // <?xml ?> start found
                        openBracketsCount++;
                    }
                }
            } else if (!inCDATASection && readByte == '/') {
                if (i < in.writerIndex() - 1 && in.getByte(i + 1) == '>') {
                    // found />, decrementing openBracketsCount
                    openBracketsCount--;
                }
            } else if (readByte == '>') {
                length = i + 1 - in.readerIndex();

                if (i - 1 > -1) {
                    final byte peekBehindByte = in.getByte(i - 1);

                    if (!inCDATASection) {
                        if (peekBehindByte == '?') {
                            // an <?xml ?> tag was closed
                            openBracketsCount--;
                        } else if (peekBehindByte == '-' && i - 2 > -1 && in.getByte(i - 2) == '-') {
                            // a <!-- comment --> was closed
                            openBracketsCount--;
                        }
                    } else if (peekBehindByte == ']' && i - 2 > -1 && in.getByte(i - 2) == ']') {
                        // a <![CDATA[...]]> block was closed
                        openBracketsCount--;
                        inCDATASection = false;
                    }
                }

                if (atLeastOneXmlElementFound && openBracketsCount<= (started ? 0 : 1)) {
                    // we have enough to start
                    break;
                }
            }
        }

        final int readerIndex = in.readerIndex();

        if (openBracketsCount <= (started ? 0 : 1) && length > 0) {
            if (length + readerIndex > in.writerIndex()) {
                ctx.fireExceptionCaught(new 
              	  CorruptedFrameException("badness. blew the frame size "+length+" "+readerIndex + " "+ in.writerIndex()));
            }
            final ByteBuf frame =
                    extractFrame(in, readerIndex + leadingWhiteSpaceCount, length - leadingWhiteSpaceCount);
            in.skipBytes(length);
            out.add(frame);
        }
    }

    private ByteBuf extractFrame(ByteBuf buffer, int index, int length) {
        return buffer.copy(index, length);
    }

    /**
     * Asks whether the given byte is a valid
     * start char for an xml element name.
     * <p/>
     * Please refer to the
     * <a href="http://www.w3.org/TR/2004/REC-xml11-20040204/#NT-NameStartChar">NameStartChar</a>
     * formal definition in the W3C XML spec for further info.
     *
     * @param b the input char
     * @return true if the char is a valid start char
     */
    private boolean isValidStartCharForXmlElement(final byte b) {
        return b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b == ':' || b == '_';
    }

    private boolean isCommentBlockStart(final ByteBuf in, final int i) {
        return i < in.writerIndex() - 3
                && in.getByte(i + 2) == '-'
                && in.getByte(i + 3) == '-';
    }

    private boolean isCDATABlockStart(final ByteBuf in, final int i) {
        return i < in.writerIndex() - 8
                && in.getByte(i + 2) == '['
                && in.getByte(i + 3) == 'C'
                && in.getByte(i + 4) == 'D'
                && in.getByte(i + 5) == 'A'
                && in.getByte(i + 6) == 'T'
                && in.getByte(i + 7) == 'A'
                && in.getByte(i + 8) == '[';
    }
}