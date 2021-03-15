package com.crossoverjie.cim.client.client;

import com.crossoverjie.cim.client.config.AppConfiguration;
import com.crossoverjie.cim.client.init.CIMClientHandleInitializer;
import com.crossoverjie.cim.client.service.EchoService;
import com.crossoverjie.cim.client.service.MsgHandle;
import com.crossoverjie.cim.client.service.ReConnectManager;
import com.crossoverjie.cim.client.service.RouteRequest;
import com.crossoverjie.cim.client.service.impl.ClientInfo;
import com.crossoverjie.cim.client.thread.ContextHolder;
import com.crossoverjie.cim.client.vo.req.GoogleProtocolVO;
import com.crossoverjie.cim.client.vo.req.LoginReqVO;
import com.crossoverjie.cim.client.vo.res.CIMServerResVO;
import com.crossoverjie.cim.common.constant.Constants;
import com.crossoverjie.cim.common.protocol.CIMRequestProto;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Function:
 *
 * @author crossoverJie
 * Date: 22/05/2018 14:19
 * @since JDK 1.8
 */
@Component
public class CIMClient {

    private final static Logger LOGGER = LoggerFactory.getLogger(CIMClient.class);

    private EventLoopGroup group = new NioEventLoopGroup(0, new DefaultThreadFactory("cim-work"));

    //    @Value("${cim.user-info}")
    private Map<Long, String> userInfo = new HashMap<>(8);

    private List<Long> userIds;

    private Map<Long, SocketChannel> channelMap = new ConcurrentHashMap<>(8);

    @Autowired
    private EchoService echoService;

    @Autowired
    private RouteRequest routeRequest;

    @Autowired
    private AppConfiguration configuration;

    @Autowired
    private MsgHandle msgHandle;

    @Autowired
    private ClientInfo clientInfo;

    @Autowired
    private ReConnectManager reConnectManager;

    /**
     * 重试次数
     */
    private int errorCount;

    /**
     * 客户端数量
     */
    private final static int CLIENT_COUNT = 1000;

    private SocketChannel getChannel(Long userId) {
        return channelMap.get(userId);
    }

    @PostConstruct
    public void start() throws Exception {

        for (int i = 0; i < CLIENT_COUNT; i++) {
            userInfo.put(i + System.currentTimeMillis(), "client-" + i);
        }

        start0();

    }

    public void start0() {

        AtomicInteger counter = new AtomicInteger(0);

        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(CLIENT_COUNT, CLIENT_COUNT * 2, 300L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(CLIENT_COUNT), r -> new Thread(r, "client-" + counter.incrementAndGet()), (r, executor) -> System.out.println("拒绝处理啊"));
        userInfo.forEach((userId, userName) -> threadPoolExecutor.execute(() -> start1(userId, userName)));
    }

    public void start1(Long userId, String userName) {

        // 登录鉴权 + 获取可以使用的服务器 ip+port
        CIMServerResVO.ServerInfo cimServer = userLogin(userId, userName);

        // 启动客户端：从服务端获取socket连接
        SocketChannel channel = startClient(cimServer);

        channelMap.put(userId, channel);

        // 向服务端发送登录请求
        loginCIMServer(userId, userName, channel);
    }


    /**
     * 启动客户端
     *
     * @param cimServer
     * @throws Exception
     */
    private SocketChannel startClient(CIMServerResVO.ServerInfo cimServer) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new CIMClientHandleInitializer());

        ChannelFuture future = null;
        try {
            future = bootstrap.connect(cimServer.getIp(), cimServer.getCimServerPort()).sync();
        } catch (Exception e) {
            errorCount++;

//            if (errorCount >= configuration.getErrorCount()) {
//                LOGGER.error("连接失败次数达到上限[{}]次", errorCount);
//                msgHandle.shutdown();
//            }
            LOGGER.error("Connect fail!", e);
        }
        if (future.isSuccess()) {
            echoService.echo("Start cim client success!");
            LOGGER.info("启动 cim client 成功");
        }
        return (SocketChannel) future.channel();
    }

    /**
     * 登录+路由服务器
     *
     * @param userId
     * @param userName
     * @return 路由服务器信息
     * @throws Exception
     */
    private CIMServerResVO.ServerInfo userLogin(Long userId, String userName) {
        LoginReqVO loginReqVO = new LoginReqVO(userId, userName);
        CIMServerResVO.ServerInfo cimServer = null;
        try {
            cimServer = routeRequest.getCIMServer(loginReqVO);

            //保存系统信息
            clientInfo.saveServiceInfo(cimServer.getIp() + ":" + cimServer.getCimServerPort())
                    .saveUserInfo(userId, userName);

            LOGGER.info("cimServer=[{}]", cimServer.toString());
        } catch (Exception e) {
            errorCount++;
//
//            if (errorCount >= configuration.getErrorCount()) {
//                echoService.echo("The maximum number of reconnections has been reached[{}]times, close cim client!", errorCount);
//                msgHandle.shutdown();
//            }
            LOGGER.error("login fail", e);
        }
        return cimServer;
    }

    /**
     * 向服务器注册
     *
     * @param userId
     * @param userName
     * @param channel
     */
    private void loginCIMServer(Long userId, String userName, SocketChannel channel) {
        CIMRequestProto.CIMReqProtocol login = CIMRequestProto.CIMReqProtocol.newBuilder()
                .setRequestId(userId)
                .setReqMsg(userName)
                .setType(Constants.CommandType.LOGIN)
                .build();
        ChannelFuture future = channel.writeAndFlush(login);
        future.addListener((ChannelFutureListener) channelFuture -> echoService.echo("Registry cim server success!"));
    }


    /**
     * 发送消息字符串
     *
     * @param msg
     */
    public void sendStringMsg(String msg) {
        if (CollectionUtils.isEmpty(userIds)) {
            userIds = new ArrayList<>(userInfo.keySet());
        }
        Long userId = userIds.get(new Random().nextInt(userIds.size()));
        sendStringMsg(msg, channelMap.get(userId));
    }

    public void sendGoogleProtocolMsg(GoogleProtocolVO googleProtocolVO) {
        Long userId = userIds.get(new Random().nextInt(userIds.size()));
        sendGoogleProtocolMsg(googleProtocolVO, channelMap.get(userId));
    }

    /**
     * 发送消息字符串
     *
     * @param msg
     */
    public void sendStringMsg(String msg, SocketChannel channel) {
        ByteBuf message = Unpooled.buffer(msg.getBytes().length);
        message.writeBytes(msg.getBytes());
        ChannelFuture future = channel.writeAndFlush(message);
        future.addListener((ChannelFutureListener) channelFuture ->
                LOGGER.info("客户端手动发消息成功={}", msg));

    }

    /**
     * 发送 Google Protocol 编解码字符串
     *
     * @param googleProtocolVO
     */
    public void sendGoogleProtocolMsg(GoogleProtocolVO googleProtocolVO, SocketChannel channel) {

        CIMRequestProto.CIMReqProtocol protocol = CIMRequestProto.CIMReqProtocol.newBuilder()
                .setRequestId(googleProtocolVO.getRequestId())
                .setReqMsg(googleProtocolVO.getMsg())
                .setType(Constants.CommandType.MSG)
                .build();


        ChannelFuture future = channel.writeAndFlush(protocol);
        future.addListener((ChannelFutureListener) channelFuture ->
                LOGGER.info("客户端手动发送 Google Protocol 成功={}", googleProtocolVO.toString()));

    }


    /**
     * 1. clear route information.
     * 2. reconnect.
     * 3. shutdown reconnect job.
     * 4. reset reconnect state.
     *
     * @throws Exception
     */
    public void reconnect(SocketChannel channel) throws Exception {
        if (channel != null && channel.isActive()) {
            return;
        }
        //首先清除路由信息，下线
        routeRequest.offLine();

        echoService.echo("cim server shutdown, reconnecting....");
//        start();
        echoService.echo("Great! reConnect success!!!");
        reConnectManager.reConnectSuccess();
        ContextHolder.clear();
    }


    public void reconnect() throws Exception {
        for (Map.Entry<Long, SocketChannel> entry : channelMap.entrySet()) {
            reconnect(entry.getValue());
        }
    }

    /**
     * 关闭
     *
     * @throws InterruptedException
     */
    public void close() throws InterruptedException {
        channelMap.forEach((userId, channel) -> {
            if (channel != null) {
                channel.close();
            }
        });
    }
}
