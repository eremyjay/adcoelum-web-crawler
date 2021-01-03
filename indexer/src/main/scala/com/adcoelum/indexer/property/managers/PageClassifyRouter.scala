package com.adcoelum.indexer.property.managers

import akka.actor._
import akka.routing.{ActorRefRoutee, Router, SmallestMailboxRoutingLogic}
import com.adcoelum.actors.Activity
import com.adcoelum.common.Configuration
import com.adcoelum.actors.{TriggerActivity, InitializeRouter}
import com.adcoelum.indexer.property.workers.PageClassifier

import scala.language.postfixOps



/**
  * Managing actor for performing routing for Page Classifier
  */
class PageClassifyRouter extends Actor {
	var messagesLastCheck = 0
	var messages = 0

	// Create router
	var router = Router(SmallestMailboxRoutingLogic(), Vector.empty)

	/**
	  * Setup the router for use
	  */
	def setupRouter = {
		router = {
			// Setup routees for router
			val routees = Vector.fill(Configuration.crawlerWorkerRoutees) {
				val dataPointProcessor = context.actorOf(Props[PageClassifier])
				context watch dataPointProcessor
				ActorRefRoutee(dataPointProcessor)
			}

			// Connect routees for router
			Router(SmallestMailboxRoutingLogic(), routees)
		}
	}


	/**
	  * Handle messages received and route to an idle actor
	  *
	  * @return
	  */
	def receive = {
		// Perform initial setup of the router
		case InitializeRouter => {
			setupRouter
		}

		// Handle termination message for router
    	case Terminated(a) => {
      		router = router.removeRoutee(a)
      		val pageClassifier = context.actorOf(Props[PageClassifier])
      		context watch pageClassifier
      		router = router.addRoutee(pageClassifier)
      	}

		// Check the activity of the router	and track number of messages
		case TriggerActivity() => {
			sender ! Activity("page_classifier", messagesLastCheck, messages)
			messagesLastCheck = messages
		}

		// Handle all other messages and send to smallest mailbox
		case message => {
      		router.route(message, sender)
			messages += 1
    	}
  	}
}