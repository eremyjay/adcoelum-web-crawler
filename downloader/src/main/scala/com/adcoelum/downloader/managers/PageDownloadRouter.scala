package com.adcoelum.downloader.managers


import akka.actor._
import akka.routing.{ActorRefRoutee, Router, SmallestMailboxRoutingLogic}
import com.adcoelum.actors._
import com.adcoelum.common.{Configuration, Logger}
import com.adcoelum.downloader.workers.PageDownloader
import com.typesafe.akka.extension.quartz.QuartzSchedulerExtension

import scala.concurrent.duration._
import scala.language.postfixOps


/**
  * Managing actor for performing routing for Downloader
  */
class PageDownloadRouter extends Actor {
	var messagesLastCheck = 0
	var messages = 0

	// Handle a regular check for when the state of the downloader is idle
	val idleFrequency = (Configuration.config \ "downloader" \ "idleCheckFrequency").as[Int] seconds

	val idleSeconds = {
		if (idleFrequency.toSeconds >= 60)
			s"${idleFrequency.toSeconds % 60}"
		else
			s"0/${idleFrequency.toSeconds % 60}"
	}
	val idleMinutes = {
		if (idleFrequency.toSeconds >= (60 * 60))
			s"${idleFrequency.toSeconds / 60}"
		else if (idleFrequency.toSeconds < 60)
			s"*"
		else
			s"0/${idleFrequency.toSeconds / 60}"
	}

	val downloaderScheduler = QuartzSchedulerExtension(context.system)
	val downloaderSchedulerName = s"DownloaderIdleScheduler_${System.currentTimeMillis}"
	downloaderScheduler.createSchedule(downloaderSchedulerName, Some("Scheduler for asking for work when idle"), s"${idleSeconds} ${idleMinutes} * * * ?", None)

	// Create router
	var router = Router(SmallestMailboxRoutingLogic(), Vector.empty)

	/**
	  * Setup the router for use
	  */
	def setupRouter = {
		router = {
			// Setup routees for router
			val routees = Vector.fill(Configuration.downloaderWorkerRoutees) {
				val downloader = context.actorOf(Props[PageDownloader])
				context watch downloader
				ActorRefRoutee(downloader)
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

			if((Configuration.config \ "downloader" \ "method").as[String] == "pull") {
				// Start the pulse to handle checking of whether components are idle
				Logger.info(s"Starting Downloader Idle check scheduler: ${downloaderSchedulerName}")
				downloaderScheduler.schedule(downloaderSchedulerName, self, IdleCheck())
			}
		}

		// Handle termination message for router
    	case Terminated(a) => {
      		router = router.removeRoutee(a)
      		val pageDownloader = context.actorOf(Props[PageDownloader])
      		context watch pageDownloader
      		router = router.addRoutee(pageDownloader)
      	}

		// Check the activity of the router	and track number of messages
		// Update the indexer systems data
		case TriggerActivity() => {
			sender ! Activity("downloader", messagesLastCheck, messages)
			messagesLastCheck = messages

			router.routees foreach { routee =>
				routee.send(UpdateRemoteSystems(), sender)
			}
		}

		case IdleCheck() => {
			Logger.trace(s"Performing an idle check to all downloaders", Logger.VERBOSE_INTENSITY)
			router.routees foreach { routee =>
				routee.send(IdleCheck(), sender)
			}

			/*
			TODO: Handle killing a browser if downloading for too long

			else if (state == Active && System.currentTimeMillis - lastWorkAsk >= idleTimeout.toMillis) {
				Logger.trace(s"Killing browser due to idle timeout for active downloader with browser ${client.id}", Logger.VERBOSE_INTENSITY)
				client.reset
			}
			 */
		}

		// Handle all other messages and send to smallest mailbox
		case message => {
      		router.route(message, sender)
			messages += 1
    	}
  	}
}