package com.crossoverjie.cim.route.service.impl;

import com.alibaba.fastjson.JSON;
import com.crossoverjie.cim.common.enums.StatusEnum;
import com.crossoverjie.cim.common.exception.CIMException;
import com.crossoverjie.cim.common.pojo.CIMUserInfo;
import com.crossoverjie.cim.common.util.RouteInfoParseUtil;
import com.crossoverjie.cim.common.util.StringUtil;
import com.crossoverjie.cim.route.api.vo.req.ChatReqVO;
import com.crossoverjie.cim.route.api.vo.req.LoginReqVO;
import com.crossoverjie.cim.route.api.vo.res.CIMServerResVO;
import com.crossoverjie.cim.route.api.vo.res.RegisterInfoResVO;
import com.crossoverjie.cim.route.service.AccountService;
import com.crossoverjie.cim.route.service.UserInfoCacheService;
import com.crossoverjie.cim.server.api.vo.req.SendMsgReqVO;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.crossoverjie.cim.common.enums.StatusEnum.OFF_LINE;
import static com.crossoverjie.cim.route.constant.ConstantRedis.*;

/**
 * Function:
 *
 * @author crossoverJie
 * Date: 2018/12/23 21:58
 * @since JDK 1.8
 */
@Service
public class AccountServiceRedisImpl implements AccountService {
    private final static Logger LOGGER = LoggerFactory.getLogger(AccountServiceRedisImpl.class);

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserInfoCacheService userInfoCacheService;

    @Autowired
    private OkHttpClient okHttpClient;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Value("${cim.rabbit.exchange}")
    private String exchange;

    @Value("${cim.rabbit.routing}")
    private String routing;

    private Map<String, String> routeMap = new ConcurrentHashMap<>(64);

    @Override

    public RegisterInfoResVO register(RegisterInfoResVO info) {
        String key = ACCOUNT_PREFIX + info.getUserId();

        String name = redisTemplate.opsForValue().get(info.getUserName());
        if (null == name) {
            //为了方便查询，冗余一份
            redisTemplate.opsForValue().set(key, info.getUserName());
            redisTemplate.opsForValue().set(info.getUserName(), key);
        } else {
            long userId = Long.parseLong(name.split(":")[1]);
            info.setUserId(userId);
            info.setUserName(info.getUserName());
        }
        // TODO: 2021/3/11 维护 target 列表
        if (StringUtil.isEmpty(info.getTarget())) {
            info.setTarget("default-target");
        }
        // 维护标签
        String userKey = USER_TARGET_PREFIX + info.getUserId();
        String targetKey = TARGET_USER_PREFIX + info.getTarget();

        stringRedisTemplate.opsForZSet().add(userKey, info.getTarget(), System.currentTimeMillis());
        stringRedisTemplate.opsForZSet().add(targetKey, info.getUserId().toString(), System.currentTimeMillis());

        return info;
    }


    @Override
    public StatusEnum login(LoginReqVO loginReqVO) throws Exception {
        //再去Redis里查询
        String key = ACCOUNT_PREFIX + loginReqVO.getUserId();
        String userName = redisTemplate.opsForValue().get(key);
        if (null == userName) {
            return StatusEnum.ACCOUNT_NOT_MATCH;
        }

        if (!userName.equals(loginReqVO.getUserName())) {
            return StatusEnum.ACCOUNT_NOT_MATCH;
        }

        //登录成功，保存登录状态
        boolean status = userInfoCacheService.saveAndCheckUserLoginStatus(loginReqVO.getUserId());
        if (!status) {
            //重复登录
            return StatusEnum.REPEAT_LOGIN;
        }

        return StatusEnum.SUCCESS;
    }

    @Override
    public void saveRouteInfo(LoginReqVO loginReqVO, String serverInfo) throws Exception {
        String key = ROUTE_PREFIX + loginReqVO.getUserId();
        redisTemplate.opsForValue().set(key, serverInfo);

//        key = SERVER_CLIENT_PREFIX + serverInfo;
//        stringRedisTemplate.opsForSet().add(key, loginReqVO.getUserId().toString());
    }

    @Override
    public Map<Long, CIMServerResVO> loadRouteRelated() {

        Map<Long, CIMServerResVO> routes = new HashMap<>(64);


        RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
        ScanOptions options = ScanOptions.scanOptions()
                .match(ROUTE_PREFIX + "*")
                .build();
        Cursor<byte[]> scan = connection.scan(options);

        while (scan.hasNext()) {
            byte[] next = scan.next();
            String key = new String(next, StandardCharsets.UTF_8);
            LOGGER.info("key={}", key);
            parseServerInfo(routes, key);

        }
        try {
            scan.close();
        } catch (IOException e) {
            LOGGER.error("IOException", e);
        }

        return routes;
    }


    @Override
    public Map<Long, CIMServerResVO> loadRouteRelatedByTarget(String target) {

        // TODO: 2021/3/11 lua script
        Set<String> userIdSet = stringRedisTemplate.opsForZSet().range(TARGET_USER_PREFIX + target, 0, -1);
        Map<Long, CIMServerResVO> serverResMap = new HashMap<>(32);

        for (String userIdStr : userIdSet) {

            String value = cache(userIdStr);
            if (StringUtils.isEmpty(value)) {
                continue;
            }
            CIMServerResVO cimServerResVO = new CIMServerResVO(RouteInfoParseUtil.parse(value));
            serverResMap.put(Long.parseLong(userIdStr), cimServerResVO);
        }

        return serverResMap;
    }

    public String cache(String userId) {
        String route = routeMap.get(userId);
        if (StringUtils.isEmpty(route)) {
            route = stringRedisTemplate.opsForValue().get(ROUTE_PREFIX + userId);
            routeMap.put(userId, route);
            return route;
        }
        return route;
    }


    @Override
    public CIMServerResVO loadRouteRelatedByUserId(Long userId) {
        String value = redisTemplate.opsForValue().get(ROUTE_PREFIX + userId);

        if (value == null) {
            throw new CIMException(OFF_LINE);
        }

        return new CIMServerResVO(RouteInfoParseUtil.parse(value));
    }

    private void parseServerInfo(Map<Long, CIMServerResVO> routes, String key) {
        long userId = Long.parseLong(key.split(":")[1]);
        String value = redisTemplate.opsForValue().get(key);
        CIMServerResVO cimServerResVO = new CIMServerResVO(RouteInfoParseUtil.parse(value));
        routes.put(userId, cimServerResVO);
    }


    @Override
    public void pushMsg(CIMServerResVO cimServerResVO, long sendUserId, ChatReqVO chatReqVo) {
        CIMUserInfo cimUserInfo = userInfoCacheService.loadUserInfoByUserId(sendUserId);

        SendMsgReqVO vo = new SendMsgReqVO(cimUserInfo.getUserName() + ":" + chatReqVo.getMsg(), chatReqVo.getUserId());

        String routeInfo = cimServerResVO.getIp() + ":" + cimServerResVO.getCimServerPort() + ":" + cimServerResVO.getHttpPort();

        rabbitTemplate.convertAndSend(exchange, routing + "." + routeInfo, JSON.toJSONString(vo));
    }

    @Override
    public void offLine(Long userId) throws Exception {

        // TODO: 2019-01-21 改为一个原子命令，以防数据一致性

        //删除路由
        redisTemplate.delete(ROUTE_PREFIX + userId);

        //删除登录状态
        userInfoCacheService.removeLoginStatus(userId);
    }

    @Override
    public void pushMsg(ChatReqVO groupReqVO) {

        //获取所有的推送列表
        Map<Long, CIMServerResVO> serverResVoMap = loadRouteRelatedByTarget(groupReqVO.getTarget());

        //过滤掉自己
        if (Objects.nonNull(serverResVoMap.remove(groupReqVO.getUserId()))) {
            LOGGER.warn("过滤掉了发送者 userId={}", groupReqVO.getUserId());
        }
        // 发送者信息
        CIMUserInfo cimUserInfo = userInfoCacheService.loadUserInfoByUserId(groupReqVO.getUserId());
        for (Map.Entry<Long, CIMServerResVO> cimServerResVoEntry : serverResVoMap.entrySet()) {
            Long userId = cimServerResVoEntry.getKey();
            CIMServerResVO cimServerResVO = cimServerResVoEntry.getValue();

            // 组装消息
            SendMsgReqVO vo = new SendMsgReqVO(cimUserInfo.getUserName() + ":" + groupReqVO.getMsg(), userId);
            String routeInfo = cimServerResVO.getIp() + ":" + cimServerResVO.getCimServerPort() + ":" + cimServerResVO.getHttpPort();

            // 推送消息 - mq
            rabbitTemplate.convertAndSend(exchange, routing + "." + routeInfo, JSON.toJSONString(vo));
        }
    }

}
