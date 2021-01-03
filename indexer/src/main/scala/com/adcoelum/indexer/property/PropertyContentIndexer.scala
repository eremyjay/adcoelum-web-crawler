package com.adcoelum.indexer.property

import akka.actor.{Props, _}
import com.adcoelum.actors.{ActivityMonitor, RegisterRouter, TerminateActivity, Tick, InitializeRouter}
import com.adcoelum.common._
import com.adcoelum.data.DatabaseAccessor
import com.adcoelum.indexer.property.managers._
import com.typesafe.akka.extension.quartz.QuartzSchedulerExtension

import scala.concurrent.ExecutionContext.Implicits.global
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.language.postfixOps



/**
  *
  */
object PropertyContentIndexer extends App {
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
			Logger.error("Not correct number of arguments for Indexer instance - quitting", error = new Throwable)
			System.exit(1)
		}
	}

	try {
		// Initialise configuration data from config file
		Configuration.init
		Logger.info(s"Pre-loading Indexer instance with process ID ${Configuration.processID}")
	}
	catch {
		case e: Exception => {
			Logger.error(s"Failed to start crawler due to configuration error: ${e.getMessage}", error = e)
			System.exit(1)
		}
	}

	// Perform a check to ensure all critical infrastructure is available to begin crawler
	if (!Tools.ensureConnectivity)
		System.exit(1)

	Logger.info(s"Starting Indexer: ${Configuration.indexerActorHost}:${actorSystemPort}")
	var indexerName = Configuration.indexerActorHost.replace(".", "_") + "_" + Configuration.processID.toString + "_indexer"

	// Connect to the data collection
	val commandControlData = DatabaseAccessor.collection("command_control")

	// Set up the instruction listener
	Logger.info("Starting Indexer instruction listener")
	val listener = new HttpServer(Configuration.host, instructionPort)

	listener.get("/stop") {
		(_, _) => {
			stop

			s"<html><head></head><body><p>Stopping Crawler ${indexerName}</p></body></html>"
		}
	}


	// Upon graceful shutdown, end the application
	// Setting actor system configuration
	val actorSystemConfiguration = ConfigFactory.parseString(Configuration.indexerActorConfig + s"""
	  |akka.remote.netty.tcp.port=$actorSystemPort
    """.stripMargin)

	// Initiate Indexer actor system
	Logger.info("Starting Indexer Actor System")
    val indexerSystem = ActorSystem(indexerName, actorSystemConfiguration)
	Configuration.setAppName(indexerName)


	// Load data into database to add indexer
	try {
		// Set query information against which to set data
		val selector = Map("commander" -> commander)

		// Define data to store for tracking this searchBot command and controller
		val data = Map(s"indexers.${indexerName}" -> Map("process" -> Configuration.processID, "serverPort" -> instructionPort, "systemPort" -> actorSystemPort))

		// Perform the data write
		commandControlData.update(data, selector, wait = true)
		Logger.info("Writing Command and Control data for Indexer")
	}
	catch {
		case e: Throwable => Logger.warn(s"Write to Command and Control Data failed: ${e.getMessage}", error = e)
	}


	// Establish activity monitor
	Logger.info("Setting Up activity monitor for Indexer")
	val activityMonitor = indexerSystem.actorOf(Props(classOf[ActivityMonitor], commander, indexerName, false, true, "/user/*_router"), "activity_monitor")

	// Establish Routers
	Logger.info("Setting Up Routers for Indexer")
	val rawDataPointProcessRouter = indexerSystem.actorOf(Props[RawDataPointProcessRouter], "raw_data_point_process_router")
	val pageClassifyRouter = indexerSystem.actorOf(Props[PageClassifyRouter], "page_classify_router")
	val locationProcessRouter = indexerSystem.actorOf(Props[LocationProcessRouter], "location_process_router")
	val propertyFactoryRouter = indexerSystem.actorOf(Props[PropertyFactoryRouter], "property_factory_router")
	val advancedDataPointProcessRouter = indexerSystem.actorOf(Props[AdvancedDataPointProcessRouter], "advanced_data_point_process_router")
	Tools.sleep(2 seconds)

	// Initialise routers
	Logger.info("Initialising Routers for Indexer")
	rawDataPointProcessRouter ! InitializeRouter
	pageClassifyRouter ! InitializeRouter
	locationProcessRouter ! InitializeRouter
	propertyFactoryRouter ! InitializeRouter
	advancedDataPointProcessRouter ! InitializeRouter
	Tools.sleep(2 seconds)

	// Register routers with activity monitor
	Logger.info("Registering Routers for Indexer")
	indexerSystem.scheduler.scheduleOnce(Duration.create(1, "seconds"), activityMonitor, RegisterRouter("raw_data_point_processor"))
	indexerSystem.scheduler.scheduleOnce(Duration.create(1, "seconds"), activityMonitor, RegisterRouter("page_classifier"))
	indexerSystem.scheduler.scheduleOnce(Duration.create(1, "seconds"), activityMonitor, RegisterRouter("location_processor"))
	indexerSystem.scheduler.scheduleOnce(Duration.create(1, "seconds"), activityMonitor, RegisterRouter("property_factory"))
	indexerSystem.scheduler.scheduleOnce(Duration.create(1, "seconds"), activityMonitor, RegisterRouter("advanced_data_point_processor"))
	Tools.sleep(2 seconds)


	indexerSystem.registerOnTermination({
		Logger.info("Stopping Indexer and halting process")
		indexerScheduler.shutdown(true)

		// Load data into database to remove indexer
		try {
			// Set query information against which to set data
			val selector = Map("commander" -> commander)

			// Define data to remove for tracking this searchBot command and controller
			val data = Map(
				"$unset" -> Map(s"indexers.${indexerName}" -> "")
			)

			// Perform the data write
			commandControlData.update(data, selector, instructionBased = true)
			Logger.info("Removing Command and Control data for Indexer")
		}
		catch {
			case e: Throwable => Logger.warn(s"Write to Command and Control Data failed: ${e.getMessage}", error = e)
		}

		listener.stop

		Logger.info("Shutting down Indexer in 10 seconds...")
		Tools.sleep(10 seconds)

		System.exit(0)
	})


	// Start the activity monitor to handle termination if no more work is available
	Logger.info("Starting Activity Monitor for Indexer")
	val indexerScheduler = QuartzSchedulerExtension(indexerSystem)
	val activityPeriod = (Configuration.config \ "activity" \ "period").as[Int]
	indexerScheduler.createSchedule("IndexerScheduler", Some("Scheduler for checking activity"), s"0/${activityPeriod} * * * * ?", None)
	indexerScheduler.schedule("IndexerScheduler", activityMonitor, Tick)


	Logger.info("Indexer up and running")

	/**
	  * Handle the stop case to perform a shutdown of the crawler actor system
	  *
	  */
	def stop = {
		activityMonitor ! TerminateActivity
		Logger.info("Preparing to Shut Down Actor System for Indexer")
	}
}

