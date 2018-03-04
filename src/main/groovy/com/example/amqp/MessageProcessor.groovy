package com.example.amqp

import com.amazonaws.xray.AWSXRay
import com.amazonaws.xray.entities.TraceHeader
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.util.logging.Slf4j
import org.springframework.amqp.core.AmqpTemplate
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageBuilder
import org.springframework.amqp.core.MessageListener

import java.util.concurrent.ThreadLocalRandom

@Slf4j
class MessageProcessor implements MessageListener {

    private final String queueName

    /**
     * JSON codec.
     */
    private final ObjectMapper mapper

    private final AmqpTemplate template

    MessageProcessor( String queueName, ObjectMapper mapper, AmqpTemplate template ) {
        this.queueName = queueName
        this.mapper = mapper
        this.template = template
    }

    static void xrayTemplate( Message incoming, Closure logic ) {
        try {
            logic.call( incoming )
        }
        catch ( Exception e ) {
            AWSXRay.globalRecorder.currentSegment.addException( e )
            throw e
        }
        finally {
            AWSXRay.globalRecorder.endSegment()
        }
    }

    @Override
    void onMessage( Message incoming ) {
        xrayTemplate( incoming ) {
            dumpMessage( queueName, incoming )

            def traceString = incoming.messageProperties.headers.get( TraceHeader.HEADER_KEY ) as String
            def incomingHeader = TraceHeader.fromString( traceString )
            def traceId = incomingHeader.rootTraceId
            def parentId = incomingHeader.parentId
            def name = "${incoming.messageProperties.headers.get( 'message-type' ) as String}/${incoming.messageProperties.headers.get( 'subject' ) as String}"
            def segment = AWSXRay.globalRecorder.beginSegment( name, traceId, parentId )
            def header = new TraceHeader( segment.traceId,
                                          segment.sampled ? segment.id : null,
                                          segment.sampled ? TraceHeader.SampleDecision.SAMPLED : TraceHeader.SampleDecision.NOT_SAMPLED )

            def servicePath = mapper.readValue( incoming.body, ServicePath )
            log.debug( 'Simulating latency of {} milliseconds', servicePath.latencyMilliseconds )
            Thread.sleep( servicePath.latencyMilliseconds )
            def simulateFailure = ThreadLocalRandom.current().nextInt( 100 ) < servicePath.errorPercentage
            if ( simulateFailure ) {
                throw new IllegalStateException( 'Simulated failure!' )
            }
            servicePath.outbound.each {
                def payload = mapper.writeValueAsString( it )
                def outgoing = MessageBuilder.withBody( payload.bytes )
                                             .setAppId( 'pattern-matching' )
                                             .setContentType( 'text/plain' )
                                             .setMessageId( UUID.randomUUID() as String )
                                             .setType( 'counter' )
                                             .setTimestamp( new Date() )
                                             .setHeader( 'message-type', 'command' )
                                             .setHeader( 'subject', it.label )
                                             .setHeader( TraceHeader.HEADER_KEY, header as String )
                                             .build()
                //log.info( 'Producing command message {}', payload )
                template.send('message-router', 'should-not-matter', outgoing )
            }
        }
    }

    private static void dumpMessage( String queue, Message message ) {
        def flattened = message.messageProperties.headers.collectMany { key, value ->
            ["${key}: ${value}"]
        }
        log.info( 'From {} {} {}', queue, message.messageProperties.messageId, flattened )
    }
}
