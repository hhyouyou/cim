package com.crossoverjie.cim.server.message;

import com.alibaba.fastjson.JSON;
import com.crossoverjie.cim.server.api.vo.req.SendMsgReqVO;
import com.crossoverjie.cim.server.server.CIMServer;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 接收需要推送的消息
 *
 * @author djx
 * @date 2021/3/12 上午11:24
 */
@Slf4j
@Component
public class SendMsgListener {

    @Resource
    private CIMServer cimServer;

    @RabbitListener(queues = "${cim.rabbit.queue}")
    public void receiveMessage(Message message, Channel channel) throws IOException {

        String messageBody = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("message info :{}", messageBody);

        // 解析消息内容
        SendMsgReqVO vo = JSON.parseObject(messageBody, SendMsgReqVO.class);

        // real send msg
        cimServer.sendMsg(vo);

        // ack
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }
}
