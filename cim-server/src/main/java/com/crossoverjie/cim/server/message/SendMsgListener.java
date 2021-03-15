package com.crossoverjie.cim.server.message;

import com.alibaba.fastjson.JSON;
import com.crossoverjie.cim.server.api.vo.req.SendMsgReqVO;
import com.crossoverjie.cim.server.server.CIMServer;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @author djx
 * @date 2021/3/12 上午11:24
 */
@Slf4j
@Component
public class SendMsgListener {
    @Autowired
    private CIMServer cimServer;

    @RabbitListener(queues = "${cim.rabbit.queue}")
    public void receiveMessage(Message message, Channel channel) throws IOException {

        String messageBody = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("message info :{}", messageBody);

        SendMsgReqVO vo = JSON.parseObject(messageBody, SendMsgReqVO.class);

        cimServer.sendMsg(vo);

        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }
}
