package org.openfeed.client.api.impl.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.CharsetUtil;
import org.openfeed.*;
import org.openfeed.client.api.OpenfeedClientConfig;
import org.openfeed.client.api.OpenfeedClientHandler;
import org.openfeed.client.api.OpenfeedClientMessageHandler;
import org.openfeed.client.api.impl.OpenfeedClientConfigImpl;
import org.openfeed.client.api.impl.PbUtil;
import org.openfeed.client.api.impl.SubscriptionManagerImpl;
import org.openfeed.client.api.impl.WireStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

public class OpenfeedWebSocketHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger log = LoggerFactory.getLogger(OpenfeedWebSocketHandler.class);

    private final WebSocketClientHandshaker handshaker;
    private ChannelPromise handshakeFuture;
    private OpenfeedClientWebSocket client;
    private OpenfeedClientConfigImpl config;
    private final SubscriptionManagerImpl subscriptionManager;
    private OpenfeedClientHandler clientHandler;
    private final OpenfeedClientMessageHandler messageHandler;
    private WireStats stats;

    public OpenfeedWebSocketHandler(OpenfeedClientConfigImpl config, OpenfeedClientWebSocket client, SubscriptionManagerImpl subscriptionManager,
                                    OpenfeedClientHandler clientHandler, WebSocketClientHandshaker handshaker, OpenfeedClientMessageHandler messageHandler) {
        this.config = config;
        this.subscriptionManager = subscriptionManager;
        this.clientHandler = clientHandler;
        this.handshaker = handshaker;
        this.client = client;
        this.messageHandler = messageHandler;
    }

    public WireStats getStats() {
        return this.stats;
    }

    public ChannelFuture handshakeFuture() {
        return handshakeFuture;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        handshakeFuture = ctx.newPromise();
        // Dump config
        ChannelConfig config = ctx.channel().config();
        SocketChannelConfig socketConfig = (SocketChannelConfig) config;
        Map<ChannelOption<?>, Object> options = socketConfig.getOptions();
        StringBuilder sb = new StringBuilder(ctx.channel().remoteAddress() + ": Options\n");
        for (Entry<ChannelOption<?>, Object> opt : options.entrySet()) {
            sb.append(opt.getKey() + "=" + opt.getValue() + ",");
        }
        log.debug("{}", sb.toString());
        if (this.config.isWireStats()) {
            this.stats = new WireStats();
        }
    }

    private void logStats() {
        log.info("{}", this.stats);
        this.stats.reset();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        handshaker.handshake(ctx.channel());
        if (this.config.isWireStats()) {
            // Track some wire metrics
            ctx.channel().eventLoop().scheduleAtFixedRate(this::logStats, 1, 1, TimeUnit.SECONDS);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.warn("WebSocket Client disconnected. {}", ctx.channel().localAddress());
        this.client.disconnect();
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        Channel ch = ctx.channel();
        if (!handshaker.isHandshakeComplete()) {
            try {
                handshaker.finishHandshake(ch, (FullHttpResponse) msg);
                log.info("{}: WebSocket Client connected!", config.getClientId());
                handshakeFuture.setSuccess();
            } catch (WebSocketHandshakeException e) {
                log.error("{}: WebSocket Client failed to connect", config.getClientId());
                handshakeFuture.setFailure(e);
            }
            return;
        }

        if (msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;
            throw new IllegalStateException("Unexpected FullHttpResponse (getStatus=" + response.status() + ", content="
                    + response.content().toString(CharsetUtil.UTF_8) + ')');
        }

        WebSocketFrame frame = (WebSocketFrame) msg;
        if (frame instanceof TextWebSocketFrame) {
            TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
            OpenfeedGatewayMessage ofgm = decodeJson(textFrame.text());
            handleResponse(ofgm, new byte[0]);
        } else if (frame instanceof BinaryWebSocketFrame) {
            try {
                BinaryWebSocketFrame binaryFrame = (BinaryWebSocketFrame) frame;
                final byte[] array;
                ByteBuf binBuf = frame.content();
                final int length = binBuf.readableBytes();
                if (this.config.isWireStats()) {
                    this.stats.update(length);
                }

                if (messageHandler != null) {// make copy
                    array = ByteBufUtil.getBytes(binBuf, binBuf.readerIndex(), length, true);
                } else {
                    array = ByteBufUtil.getBytes(binBuf, binBuf.readerIndex(), length, false);
                }
                OpenfeedGatewayMessage rsp = OpenfeedGatewayMessage.parseFrom(array);
                handleResponse(rsp, array);
            } catch (Exception e) {
                log.error("{}: Could not process message: ", ctx.channel().remoteAddress(), e);
            }
        } else if (frame instanceof PongWebSocketFrame) {
            log.info("WebSocket Client received pong");
        } else if (frame instanceof CloseWebSocketFrame) {
            log.info("WebSocket Client received closing");
            ch.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("ExceptionCaught from: {}", ctx.channel().remoteAddress(), cause);
        if (!handshakeFuture.isDone()) {
            handshakeFuture.setFailure(cause);
        }
        ctx.close();
        client.disconnect();
    }

    private OpenfeedGatewayMessage decodeJson(String data) {
        try {
            OpenfeedGatewayMessage.Builder rsp = OpenfeedGatewayMessage.newBuilder();
            PbUtil.decode(data, rsp);
            return rsp.build();
        } catch (Exception e) {
            throw new RuntimeException("decode exception, data: " + data, e);
        }
    }

    void handleResponse(OpenfeedGatewayMessage ofgm, byte[] bytes) {
        if (ofgm == null) {
            log.warn("Empty Message received.");
            return;
        }
        if (config.isLogAll()) {
            logMsg(ofgm);
        }

        switch (ofgm.getDataCase()) {
            case LOGINRESPONSE:
                log(ofgm);
                LoginResponse loginResponse = ofgm.getLoginResponse();
                if (loginResponse.getStatus().getResult() == Result.SUCCESS) {
                    log.debug("{}: Login successful: token {}", config.getClientId(), PbUtil.toJson(ofgm));
                    this.client.setToken(loginResponse.getToken());
                    this.client.completeLogin(true, null);
                } else {
                    String error = config.getClientId() + ": Login failed: " + PbUtil.toJson(ofgm);
                    log.error("{}", error);
                    this.client.completeLogin(false, error);
                }
                if (clientHandler != null) {
                    clientHandler.onLoginResponse(ofgm.getLoginResponse());
                }
                break;
            case LOGOUTRESPONSE:
                log(ofgm);
                LogoutResponse logout = ofgm.getLogoutResponse();
                if (logout.getStatus().getResult() == Result.SUCCESS) {
                    this.client.completeLogout(true);
                } else if (logout.getStatus().getResult() == Result.DUPLICATE_LOGIN &&
                        config.isDisableClientOnDuplicateLogin()) {
                    log.error("{}: Duplicate Login, stopping client: {}", config.getClientId(), PbUtil.toJson(ofgm));
                    this.config.setReconnect(false);
                    this.client.disconnect();
                } else {
                    this.client.completeLogout(false);
                }

                if (clientHandler != null) {
                    clientHandler.onLogoutResponse(ofgm.getLogoutResponse());
                }
                break;
            case INSTRUMENTRESPONSE:
                log(ofgm);
                if (clientHandler != null) {
                    clientHandler.onInstrumentResponse(ofgm.getInstrumentResponse());
                }
                break;
            case INSTRUMENTREFERENCERESPONSE:
                log(ofgm);
                if (clientHandler != null) {
                    clientHandler.onInstrumentReferenceResponse(ofgm.getInstrumentReferenceResponse());
                }
                break;
            case EXCHANGERESPONSE:
                log(ofgm);
                if (clientHandler != null) {
                    clientHandler.onExchangeResponse(ofgm.getExchangeResponse());
                }
                break;
            case SUBSCRIPTIONRESPONSE:
                log(ofgm);
                // Update Subscription State
                this.subscriptionManager.updateSubscriptionState(ofgm.getSubscriptionResponse());
                if (clientHandler != null) {
                    clientHandler.onSubscriptionResponse(ofgm.getSubscriptionResponse());
                }
                break;
            case MARKETSTATUS:
                log(ofgm);
                if (clientHandler != null) {
                    clientHandler.onMarketStatus(ofgm.getMarketStatus());
                }
                break;
            case HEARTBEAT:
                if (config.isLogHeartBeat()) {
                    logMsg(ofgm);
                }
                if (clientHandler != null) {
                    clientHandler.onHeartBeat(ofgm.getHeartBeat());
                }
                break;
            case INSTRUMENTDEFINITION:
                InstrumentDefinition def = ofgm.getInstrumentDefinition();
                this.client.addMapping(def);
                if (clientHandler != null) {
                    clientHandler.onInstrumentDefinition(ofgm.getInstrumentDefinition());
                }
                break;
            case MARKETSNAPSHOT:
                if (clientHandler != null) {
                    clientHandler.onMarketSnapshot(ofgm.getMarketSnapshot());
                }
                break;
            case MARKETUPDATE:
                if (clientHandler != null) {
                    clientHandler.onMarketUpdate(ofgm.getMarketUpdate());
                }
                break;
            case VOLUMEATPRICE:
                if (clientHandler != null) {
                    clientHandler.onVolumeAtPrice(ofgm.getVolumeAtPrice());
                }
                break;
            case OHLC:
                if (clientHandler != null) {
                    clientHandler.onOhlc(ofgm.getOhlc());
                }
                break;
            case INSTRUMENTACTION:
                if (clientHandler != null) {
                    clientHandler.onInstrumentAction(ofgm.getInstrumentAction());
                }
                break;
            default:
            case DATA_NOT_SET:
                break;
        }

        if(messageHandler != null) {
            messageHandler.onMessage(bytes);
        }

    }

    private void log(OpenfeedGatewayMessage ofmsg) {
        if (config.isLogRequestResponse()) {
            logMsg(ofmsg);
        }
    }

    private void logMsg(OpenfeedGatewayMessage ofmsg) {
        log.info("{} < {}", config.getClientId(), PbUtil.toJson(ofmsg));
    }


}
