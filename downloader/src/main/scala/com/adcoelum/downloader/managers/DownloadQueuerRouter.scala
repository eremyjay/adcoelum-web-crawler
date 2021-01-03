package com.adcoelum.downloader.managers

import akka.actor._
import akka.routing.{ActorRefRoutee, Router, SmallestMailboxRoutingLogic}
import com.adcoelum.actors.{Activity, TriggerActivity, InitializeRouter}
import com.adcoelum.downloader.workers.DownloadQueuer
import com.adcoelum.common.Configuration

import scala.language.postfixOps


/**
  * Managing actor for performing routing for Page Classifier
  */
class DownloadQueuerRouter extends Actor {
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
			val routees = Vector.fill(Configuration.downloaderWorkerRoutees) {
				val downloadQueuer = context.actorOf(Props[DownloadQueuer])
				context watch downloadQueuer
				ActorRefRoutee(downloadQueuer)
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
      		val downloadQueuer = context.actorOf(Props[DownloadQueuer])
      		context watch downloadQueuer
      		router = router.addRoutee(downloadQueuer)
      	}

		// Check the activity of the router	and track number of messages
		case TriggerActivity() => {
			sender ! Activity("download_queuer", messagesLastCheck, messages)
			messagesLastCheck = messages
		}

		// Handle all other messages and send to smallest mailbox
		case message => {
      		router.route(message, sender)
			messages += 1
    	}
  	}
}