package com.example.amqp

import com.fasterxml.jackson.databind.ObjectMapper
import groovy.util.logging.Slf4j
import org.springframework.amqp.core.AmqpTemplate
import org.springframework.amqp.core.Binding as RabbitBinding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.HeadersExchange
import org.springframework.amqp.core.Queue as RabbitQueue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.annotation.RabbitListenerConfigurer
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

//TODO: showcase a synchronous request-reply scenario.  I want to know how a spy would react in that instance.

@Slf4j
@SpringBootApplication
class Application implements RabbitListenerConfigurer {

    /**
     * List of all subjects the system supports.
     */
    @Bean
    List<String> subjects() {
        ['dog', 'cat', 'mouse', 'bear']
    }

    @Bean
    HeadersExchange messageRouter() {
        new HeadersExchange( 'message-router' )
    }

    @Bean
    @Qualifier( 'commands' )
    List<RabbitQueue> commandQueues( List<String> subjects ) {
        subjects.collect {
            QueueBuilder.durable( "${it}-commands" ).withArgument( 'x-subject', it ).build()
        }
    }

    @Bean
    @Qualifier( 'events' )
    List<RabbitQueue> eventQueues( List<String> subjects ) {
        subjects.collect {
            QueueBuilder.durable( "${it}-events" ).withArgument( 'x-subject', it ).build()
        }
    }

    @Bean
    RabbitQueue everyCommandQueue() {
        QueueBuilder.durable( 'all-commands' ).build()
    }

    @Bean
    RabbitQueue everyEventQueue() {
        QueueBuilder.durable( 'all-events' ).build()
    }

    @Bean
    List<RabbitBinding> commandBindings( @Qualifier( 'commands' ) List<RabbitQueue> commandQueues, HeadersExchange messageRouter ) {
        commandQueues.collect {
            def headers = ['message-type': 'command', 'subject': (it.arguments.'x-subject')] as Map<String, Object>
            BindingBuilder.bind( it ).to( messageRouter ).whereAll( headers ).match()
        }
    }

    @Bean
    List<RabbitBinding> eventBindings( @Qualifier( 'events' ) List<RabbitQueue> eventQueues, HeadersExchange messageRouter ) {
        eventQueues.collect {
            def headers = ['message-type': 'event', 'subject': (it.arguments.'x-subject')] as Map<String, Object>
            BindingBuilder.bind( it ).to( messageRouter ).whereAll( headers ).match()
        }
    }

    @Bean
    RabbitBinding commandSpyBinding( RabbitQueue everyCommandQueue, HeadersExchange messageRouter ) {
        def headers = ['message-type': 'command'] as Map<String, Object>
        BindingBuilder.bind( everyCommandQueue ).to( messageRouter ).whereAll( headers ).match()
    }

    @Bean
    RabbitBinding eventSpyBinding( RabbitQueue everyEventQueue, HeadersExchange messageRouter ) {
        def headers = ['message-type': 'event'] as Map<String, Object>
        BindingBuilder.bind( everyEventQueue ).to( messageRouter ).whereAll( headers ).match()
    }


    @Override
    void configureRabbitListeners( RabbitListenerEndpointRegistrar registrar ) {
        commandQueues().each {
            def endpoint = new SimpleRabbitListenerEndpoint()
            endpoint.id = "${it.name}-listener"
            endpoint.queues = it
            endpoint.messageListener = new MessageProcessor( it.name )
            registrar.registerEndpoint( endpoint )
        }
        eventQueues().each {
            def endpoint = new SimpleRabbitListenerEndpoint()
            endpoint.id = "${it.name}-listener"
            endpoint.queues = it
            endpoint.messageListener = new MessageProcessor( it.name )
            registrar.registerEndpoint( endpoint )
        }
        [everyCommandQueue(),everyEventQueue()].each {
            def endpoint = new SimpleRabbitListenerEndpoint()
            endpoint.id = "${it.name}-listener"
            endpoint.queues = it
            endpoint.messageListener = new MessageProcessor( it.name )
            registrar.registerEndpoint( endpoint )
        }
    }

    @Bean
    TopologyGenerator generator() {
        new TopologyGenerator()
    }

    @Bean
    MessageProducer MessageProducer( TopologyGenerator generator, List<String> subjects, AmqpTemplate template,  ObjectMapper mapper ) {
        new MessageProducer( generator.generate( subjects ), template, mapper )
    }

    static void main( String[] args ) {
        SpringApplication.run Application, args
    }
}
