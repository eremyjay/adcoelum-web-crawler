package com.adcoelum.crawler.managers

import akka.actor._
import akka.routing.{ActorRefRoutee, Router, SmallestMailboxRoutingLogic}
import com.adcoelum.actors.Activity
import com.adcoelum.common.Configuration
import com.adcoelum.actors.{TriggerActivity, UpdateRemoteSystems, InitializeRouter}
import com.adcoelum.crawler.workers.CrawlFrontier

import scala.language.postfixOps


/**
  * Managing actor for performing routing for Crawl List
  */
class CrawlFrontierRouter extends Actor {
	var messagesLastCheck = 0
	var messages = 0

	val updateRemoteFrequency = (Configuration.config \ "activity" \ "updateRemoteFrequency").as[Int]
	val activityPeriod = (Configuration.config \ "activity" \ "period").as[Int]

	var updateCounter = 0
	val updateFrequency = updateRemoteFrequency / activityPeriod

	// Create router
	var router = Router(SmallestMailboxRoutingLogic(), Vector.empty)

	/**
	  * Setup the router for use
	  */
	def setupRouter = {
		router = {
			// Setup routees for router
			val routees = Vector.fill(Configuration.crawlerWorkerRoutees) {
				val dataPointProcessor = context.actorOf(Props[CrawlFrontier])
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
      		val Crawlfrontier = context.actorOf(Props[CrawlFrontier])
      		context watch Crawlfrontier
      		router = router.addRoutee(Crawlfrontier)
      	}

		// Check the activity of the router	and track number of messages
		case TriggerActivity() => {
			sender ! Activity("crawl_frontier", messagesLastCheck, messages)
			messagesLastCheck = messages
			updateCounter += 1

			if (updateCounter % updateFrequency == 0) {
				router.routees foreach { routee =>
					routee.send(UpdateRemoteSystems(), sender)
				}
			}
		}

		// Handle all other messages and send to smallest mailbox
      	case message => {
      		router.route(message, sender)
			messages += 1
    	}
  	}
}