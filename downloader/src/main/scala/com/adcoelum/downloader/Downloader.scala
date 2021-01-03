package com.adcoelum.downloader

import akka.actor._
import com.adcoelum.actors.{ActivityMonitor, RegisterRouter, TerminateActivity, Tick, InitializeRouter}
import com.adcoelum.client.{ClientRegistry, Proxy}
import com.adcoelum.common._
import com.adcoelum.downloader.managers._
import com.adcoelum.data.DatabaseAccessor
import com.adcoelum.downloader.workers.DownloadQueuer
import com.typesafe.akka.extension.quartz.QuartzSchedulerExtension
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps


/**
  * This is the application for the downloader that launches a process, takes instructions and manages the
  * actor system that performs crawling of a site
  */
object Downloader extends App {
	var commander: String = _
	var instructionPort: Integer = 0
	var actorSystemPort: Integer = 0

	// Capture and handle arguments passed in to application
	args.length match {
		case 3 => {
			commander = args(0).toString
			instructionPort = args(1).toInt
			actorSystemPort = args(2).toInt
		}
		case _ => {
			Configuration.init
			Logger.error("Not correct number of arguments for Downloader instance - quitting", error = new Throwable)
			System.exit(1)
		}
	}

	try {
		// Initialise configuration data from config file
		Configuration.init
		Logger.info(s"Pre-loading Downloader instance with process ID ${Configuration.processID}")
	}
	catch {
		case e: Exception => {
			Logger.error(s"Failed to start downloader due to configuration error: ${e.getMessage}", error = e)
			System.exit(1)
		}
	}

	// Perform a check to ensure all critical infrastructure is available to begin downloader
	if (!Tools.ensureConnectivity)
		System.exit(1)

	Logger.info(s"Starting Downloader: ${Configuration.downloaderActorHost}:${actorSystemPort}")
	var downloaderName = Configuration.downloaderActorHost.replace(".", "_") + "_" + Configuration.processID.toString + "_downloader"

	// Connect to the data collection and cache
	val commandControlData = DatabaseAccessor.collection("command_control")
	val cache = DatabaseAccessor.cache

	// Set up the instruction listener
	Logger.info("Starting Downloader instruction listener")
	val listener = new HttpServer(Configuration.host, instructionPort)

	listener.get("/stop") {
		(_, _) => {
			stop

			s"<html><head></head><body><p>Stopping Crawler ${downloaderName}</p></body></html>"
		}
	}


	// Setting actor system configuration
	val actorSystemConfiguration = ConfigFactory.parseString(Configuration.indexerActorConfig + s"""
	   |akka.remote.netty.tcp.port=$actorSystemPort
    """.stripMargin)

	// Initiate Downloader actor system
	Logger.info("Starting Downloader Actor System")
    val downloaderSystem = ActorSystem(downloaderName, actorSystemConfiguration)
	Configuration.setAppName(downloaderName)


	// Load data into database to add downloader
	try {
		// Set query information against which to set data
		val selector = Map("commander" -> commander)

		// Define data to store for tracking this searchBot command and controller
		val data = Map(s"downloaders.${downloaderName}" -> Map("process" -> Configuration.processID, "serverPort" -> instructionPort, "systemPort" -> actorSystemPort))

		// Perform the data write
		commandControlData.update(data, selector, wait = true)
		Logger.info("Writing Command and Control data for Downloader")
	}
	catch {
		case e: Throwable => Logger.warn(s"Write to Command and Control Data failed: ${e.getMessage}", error = e)
	}

	// Establish proxy handler for downloader
	Logger.info("Starting Proxy Server")
	val proxy: Proxy = new Proxy
	proxy.start

	// Establish page download throttler and activity monitor
	Logger.info("Setting Up activity monitor for Downloader")
	val activityMonitor = downloaderSystem.actorOf(Props(classOf[ActivityMonitor], commander, downloaderName, false, true, "/user/*_router"), "activity_monitor")

	// Establish Routers and clients
	Logger.info("Setting Up Routers and Clients for Downloader")
	val downloadQueuer = downloaderSystem.actorOf(Props[DownloadQueuer], "download_queue_router")
	val downloadRouter = downloaderSystem.actorOf(Props[PageDownloadRouter], "download_router")
	Tools.sleep(2 seconds)

	// Establish any listeners
	// downloaderSystem.eventStream.subscribe(downloadRouter, classOf[DeadLetter])

	// Initialise routers
	Logger.info("Initialising Routers for Downloader")
	downloadQueuer ! InitializeRouter
	downloadRouter ! InitializeRouter
	Tools.sleep(2 seconds)

	// Register routers with activity monitor
	Logger.info("Registering Routers for Downloader")
	downloaderSystem.scheduler.scheduleOnce(Duration.create(1, "seconds"), activityMonitor, RegisterRouter("download_queuer"))
	downloaderSystem.scheduler.scheduleOnce(Duration.create(1, "seconds"), activityMonitor, RegisterRouter("downloader"))
	Tools.sleep(2 seconds)


	// Upon graceful shutdown, end the application
	downloaderSystem.registerOnTermination({
		Logger.info("Stopping Downloader and halting process")
		ClientRegistry.closeAllWithDelay(5 seconds)
		downloaderScheduler.shutdown(true)

		// Load data into database to remove downloader
		try {
			// Set query information against which to set data
			val selector = Map("commander" -> commander)

			// Define data to remove for tracking this searchBot command and controller
			val data = Map(
				"$unset" -> Map(s"downloaders.${downloaderName}" -> "")
			)

			// Perform the data write
			commandControlData.update(data, selector, instructionBased = true, wait = true)
			Logger.info("Removing Command and Control data for Downloader")
		}
		catch {
			case e: Throwable => Logger.warn(s"Write to Command and Control Data failed: ${e.getMessage}", error = e)
		}

		proxy.stop
		listener.stop

		Logger.info("Shutting down Downloader in 10 seconds...")
		Tools.sleep(10 seconds)

		System.exit(0)
	})

	// Start the activity monitor to handle termination if no more work is available
	Logger.info("Starting Activity Monitor for Downloader")
	val downloaderScheduler = QuartzSchedulerExtension(downloaderSystem)
	val activityPeriod = (Configuration.config \ "activity" \ "period").as[Int]
	downloaderScheduler.createSchedule("DownloaderScheduler", Some("Scheduler for checking activity"), s"0/${activityPeriod} * * * * ?", None)
	downloaderScheduler.schedule("DownloaderScheduler", activityMonitor, Tick)


	Logger.info("Downloader up and running")


	/**
	  * Handle the stop case to perform a shutdown of the downloader actor system
	  *
	  */
	def stop = {
		activityMonitor ! TerminateActivity
		Logger.info("Preparing to Shut Down Actor System for Downloader")
	}
}

