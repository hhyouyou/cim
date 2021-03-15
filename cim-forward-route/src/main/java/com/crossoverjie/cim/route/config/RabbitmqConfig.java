package com.crossoverjie.cim.route.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @author djx
 * @date 2021/3/11 下午5:46
 */
@Configuration
public class RabbitmqConfig {

    @Resource
    private NotifyConfirmCallBack notifyConfirmCallBack;

    @Value("${cim.rabbit.exchange}")
    private String exchange;

    @Value("${cim.rabbit.routing}")
    private String routing;

    @Value("${cim.rabbit.queue}")
    private String queue;


    @Bean
    public TopicExchange exchange() {
        return new TopicExchange(exchange);
    }

    @Bean
    public Queue sendMsgQueue1() {
        return new Queue(queue + ".127.0.1.1:11212:8081");
    }

    @Bean
    public Binding sendMsgBind1() {
        return BindingBuilder.bind(sendMsgQueue1()).to(exchange()).with(routing + ".127.0.1.1:11212:8081");
    }

    @Bean
    public Queue sendMsgQueue2() {
        return new Queue(queue + ".127.0.1.1:11211:8085");
    }

    @Bean
    public Binding sendMsgBind2() {
        return BindingBuilder.bind(sendMsgQueue2()).to(exchange()).with(routing + ".127.0.1.1:11211:8085");
    }


    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory factory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(factory);
        rabbitTemplate.setConfirmCallback(notifyConfirmCallBack);
        return rabbitTemplate;
    }

    @Slf4j
    @Component
    public static class NotifyConfirmCallBack implements RabbitTemplate.ConfirmCallback {

        @Override
        public void confirm(CorrelationData correlationData, boolean ack, String cause) {

            Long msgId = Long.valueOf(correlationData.getId());
            if (ack) {
                // update message status
                log.info(" msg{} send ok", msgId);
            } else {
                // 消息推送失败
                log.error(" msg{} send error :{} ", msgId, cause);
            }

        }
    }
}
