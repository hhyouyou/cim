package com.crossoverjie.cim.route.config;

import com.rabbitmq.client.AMQP;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * @author djx
 * @date 2021/3/11 下午5:46
 */
@Configuration
public class RabbitmqConfig {


    @Value("cim.rabbit.exchange")
    private String exchange;

    @Value("cim.rabbit.routing")
    private String routing;

    @Value("cim.rabbit.queue")
    private String queue;


    public AMQP.Exchange sendMsgExchange() {
        return new AMQP.Exchange();
    }


}
