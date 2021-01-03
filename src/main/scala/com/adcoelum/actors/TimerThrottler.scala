package com.adcoelum.actors

import akka.actor.{Actor, ActorRef, FSM}

import scala.collection.immutable.{Queue => Q}
import scala.concurrent.duration._

/**
  * Acknowledgements
  *
  * Thanks to Roland Kuhn for his very helpful feedback on both the code and the blog post.
  *
  * About the Author
  *
  * Kaspar Fischer is an IT consultant and specialist working for a not-for-profit organization in Switzerland that fights
  * corruption and money laundering. He has a background in OSINT and is developing software to search the internet for
  * information that links a given person and company to corruption or money laundering. He has studied computer science and
  * holds a PhD from ETH Zürich. You can contact him at kaspar.fischer@dreizak.com.
  *
  * http://letitcrash.com/post/28901663062/throttling-messages-in-akka-2
  */

case class Rate(numberOfCalls: Int, duration: FiniteDuration) {
 /**
 * The duration in milliseconds.
 */
 def durationInMillis(): Long = duration.toMillis
}

case class ReduceRate(increase: FiniteDuration)

case class SetTarget(target: Option[ActorRef])

sealed case class Data(
    target: Option[ActorRef],
    vouchersLeft: Int,
    queue: Q[Any]
)


class TimerThrottler(var rate: Rate) extends Actor with FSM[State, Data] {
    startWith(Idle, Data(None, rate.numberOfCalls, Q[Any]()))

    when(Idle) {
        // Change the rate
        case Event(ReduceRate(increase), d) => {
            rate = Rate(rate.numberOfCalls, rate.duration + increase)
            stay
        }
        
        // Set the target
        case Event(SetTarget(t @ Some(_)), d) if d.queue.nonEmpty =>
            goto(Active) using deliverMessages(d.copy(target = t))
        case Event(SetTarget(t), d) =>
            stay using d.copy(target = t)
    
        // Queuing
        case Event(Queue(msg), d @ Data(None, _, queue)) =>
            stay using d.copy(queue = queue.enqueue(msg))
        case Event(Queue(msg), d @ Data(Some(_), _, Seq())) =>
            goto(Active) using deliverMessages(d.copy(queue = Q(msg)))
    }

    when(Active) {
        // Change the rate
        case Event(ReduceRate(increase), d) => {
            rate = Rate(rate.numberOfCalls, rate.duration + increase)
            stay
        }
        
        // Set the target (when the new target is None)
        case Event(SetTarget(None), d) =>
            goto(Idle) using d.copy(target = None)
    
        // Set the target (when the new target is not None)
        case Event(SetTarget(t @ Some(_)), d) =>
            stay using d.copy(target = t)
    
        // Queue a message (when we cannot send messages in the
        // current period anymore)
        case Event(Queue(msg), d @ Data(_, 0, queue)) =>
            stay using d.copy(queue = queue.enqueue(msg))

        // Queue a message (when we can send some more messages
        // in the current period)
        case Event(Queue(msg), d @ Data(_, _, queue)) =>
            stay using deliverMessages(d.copy(queue = queue.enqueue(msg)))
    
        // Period ends and we have no more messages
        case Event(Tick, d @ Data(_, _, Seq())) =>
            goto(Idle)
    
        // Period ends and we get more occasions to send messages
        case Event(Tick, d @ Data(_, _, _)) =>
            stay using deliverMessages(d.copy(vouchersLeft = rate.numberOfCalls))
    }

    onTransition {
        case Idle -> Active => setTimer("moreVouchers", Tick, rate.duration, repeat = true)
        case Active -> Idle => cancelTimer("moreVouchers")
    }
    

    initialize
    
    import scala.math.min

    private def deliverMessages(d: Data): Data = {
        val nrOfMsgToSend = min(d.queue.length, d.vouchersLeft)
    
        // Tell sends the message, like ! in our Akka intro
        d.queue.take(nrOfMsgToSend).foreach { case msg =>
            if (d.target.isDefined)
                d.target.get ! msg
        }
    
        d.copy(queue = d.queue.drop(nrOfMsgToSend),
            vouchersLeft = d.vouchersLeft - nrOfMsgToSend)
    }
}



