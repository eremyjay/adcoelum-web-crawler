package com.adcoelum.crawler

import java.net.URL

import akka.actor.{Props, _}
import com.adcoelum.actors._
import com.adcoelum.common._
import com.adcoelum.crawler.managers._
import com.adcoelum.data.{Cache, DatabaseAccessor}
import com.aerospike.client.query.RecordSet
import com.typesafe.akka.extension.quartz.QuartzSchedulerExtension
import com.typesafe.config.ConfigFactory

import scalaj.http.Http
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.language.postfixOps



/**
  * This is the application for the crawler that launches a process, takes instructions and manages the
  * actor system that performs crawling of a site
  */
object Crawler extends App {
	var commander: String = _
	var crawlUrl: URL = _
	var crawlMethod: Int = _
	var instance: String = _
	var instructionPort: Integer = 0
	var actorSystemPort: Integer = 0

	// Capture and handle arguments passed in to application
	args.length match {
		case 6 => {
			commander = args(0).toString
			crawlUrl = new URL(args(1))
			crawlMethod = args(2).toInt
			instance = args(3).toString
			instructionPort = args(4).toInt
			actorSystemPort = args(5).toInt
		}
		case _ => {
			Configuration.init
			Logger.error("Not correct number of arguments for Crawler instance - quitting", error = new Throwable)
			System.exit(1)
		}
	}

	try {
		// Initialise configuration data from config file
		Configuration.init
		Logger.info(s"Pre-loading Crawler instance with process ID ${Configuration.processID}")
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

	Logger.info(s"Starting Crawler: ${crawlUrl} ${Configuration.crawlerActorHost}:${actorSystemPort}")

	// Set root url in config
	Configuration.setRootUrl(crawlUrl)
	var crawlerName = Configuration.rootUrlAsName + "_crawler"

	// Obtain configuration variables
	val domainChunkSize = (Configuration.config \ "seeder" \ "domainChunkSize").as[Int]

	// Connect to the data collection and cache
	val commandControlData = DatabaseAccessor.collection("command_control")
	val domainCollection = DatabaseAccessor.collection("domains")
	val downloadQueue = DatabaseAccessor.collection("download_queue")
	val cache = DatabaseAccessor.cache

	// Set up the instruction listener
	Logger.info("Starting Crawler instruction listener")
	val listener = new HttpServer(Configuration.host, instructionPort)

	listener.get("/stop") {
		(_, _) => {
			stop

			s"<html><head></head><body><p>Stopping Crawler ${crawlerName}</p></body></html>"
		}
	}


	// Setting actor system configuration
	val actorSystemConfiguration = ConfigFactory.parseString(Configuration.indexerActorConfig + s"""
	   |akka.remote.netty.tcp.port=$actorSystemPort
    """.stripMargin)

	// Initiate Crawler actor system
	Logger.info("Starting Crawler Actor System")
    val crawlerSystem = ActorSystem(crawlerName, actorSystemConfiguration)
	Configuration.setAppName(crawlerName)


	// Load data into database to add crawler
	try {
		// Set query information against which to set data
		val selector = Map("commander" -> commander)

		// Define data to store for tracking this searchBot command and controller
		val data = Map(s"crawlers.${crawlerName}" -> Map("domain" -> crawlUrl.getHost, "process" -> Configuration.processID, "serverPort" -> instructionPort, "systemPort" -> actorSystemPort))

		// Perform the data write
		commandControlData.update(data, selector, wait = true)
		Logger.info("Writing Command and Control data for Crawler")
	}
	catch {
		case e: Throwable => Logger.warn(s"Write to Command and Control Data failed: ${e.getMessage}", error = e)
	}


	// Establish page download throttler and activity monitor
	Logger.info("Setting Up activity monitor for Crawler")
	val activityMonitor = crawlerSystem.actorOf(Props(classOf[ActivityMonitor], commander, crawlerName, true, true, "/user/*_router"), "activity_monitor")

	// Establish Routers and clients
	Logger.info("Setting Up Routers and Clients for Crawler")
	val crawlFrontierRouter = crawlerSystem.actorOf(Props[CrawlFrontierRouter], "crawl_frontier_router")
	val linkFollowRouter = crawlerSystem.actorOf(Props[LinkFollowRouter], "link_follow_router")
	Tools.sleep(2 seconds)

	// Initialise routers
	Logger.info("Initialising Routers for Crawler")
	crawlFrontierRouter ! InitializeRouter
	linkFollowRouter ! InitializeRouter
	Tools.sleep(2 seconds)

	// Register routers with activity monitor
	Logger.info("Registering Routers for Crawler")
	crawlerSystem.scheduler.scheduleOnce(Duration.create(1, "seconds"), activityMonitor, RegisterRouter("crawl_frontier"))
	crawlerSystem.scheduler.scheduleOnce(Duration.create(1, "seconds"), activityMonitor, RegisterRouter("link_follower"))
	Tools.sleep(2 seconds)


	// Upon graceful shutdown, end the application
	crawlerSystem.registerOnTermination({
		Logger.info("Stopping Crawler and halting process")
		crawlerScheduler.shutdown(true)

		// Load data into database to remove crawler
		try {
			// Set query information against which to set data
			val selector = Map("commander" -> commander)

			// Define data to remove for tracking this searchBot command and controller
			val data = Map(
				"$unset" -> Map(s"crawlers.${crawlerName}" -> "")
			)

			// Perform the data write
			commandControlData.update(data, selector, instructionBased = true, wait = true)
			Logger.info("Removing Command and Control data for Crawler")
		}
		catch {
			case e: Throwable => Logger.warn(s"Write to Command and Control Data failed: ${e.getMessage}", error = e)
		}

		listener.stop


		Logger.debug(s"Clearing remaining download queue for domain ${crawlUrl.getHost} as crawl has finished", Logger.VERBOSE_INTENSITY)

		// Load data into database to set domain failure
		try {
			// Set query information against which to set data
			val query = Map("domain" -> crawlUrl.getHost)

			// Perform the data write
			downloadQueue.delete(query, wait = true)
		}
		catch {
			case e: Throwable => Logger.warn(s"Failed to update data to clean up domain information: ${e.getMessage}", error = e)
		}


		// Notify crawl ended for managing domain crawl info
		try {
			// Set query information against which to set data
			val selector = Map("domain" -> crawlUrl.getHost)
			val values = Map("crawl_finish" -> System.currentTimeMillis, "state" -> "idle")

			// Perform the data write
			domainCollection.update(values, selector, wait = true)
		}
		catch {
			case e: Throwable => Logger.warn(s"Write to Command and Control Data failed: ${e.getMessage}", error = e)
		}


		Logger.info("Shutting down Crawler in 10 seconds...")
		Tools.sleep(10 seconds)

		System.exit(0)
	})

	// Start the activity monitor to handle termination if no more work is available
	Logger.info("Starting Activity Monitor for Crawler")
	val crawlerScheduler = QuartzSchedulerExtension(crawlerSystem)
	val activityPeriod = (Configuration.config \ "activity" \ "period").as[Int]
	crawlerScheduler.createSchedule("CrawlerScheduler", Some("Scheduler for checking activity"), s"0/${activityPeriod} * * * * ?", None)
	crawlerScheduler.schedule("CrawlerScheduler", activityMonitor, Tick)


	// Activate crawler and cause it to begin
	Logger.info("Initialising the frontier for the Crawler")
	initalizeCrawlFrontier


	Logger.info("Crawler up and running")


	/**
	  * Handle the stop case to perform a shutdown of the crawler actor system
	  *
	  */
	def stop = {
		activityMonitor ! TerminateActivity
		Logger.info("Preparing to Shut Down Actor System for Crawler")
	}


	def initalizeCrawlFrontier = {
		crawlMethod match {
			case Configuration.BROAD_CRAWL => {
				Logger.info(s"Activate Crawler with full crawl using url: $crawlUrl")

				// Notify highest visit count allowed
				try {
					// Set query information against which to set data
					val selector = Map("domain" -> crawlUrl.getHost)
					val values = Map("visit_ceiling" -> 0)

					// Perform the data write
					domainCollection.update(values, selector)
				}
				catch {
					case e: Throwable => Logger.warn(s"Write to Command and Control Data failed: ${e.getMessage}", error = e)
				}

				crawlerSystem.scheduler.scheduleOnce(Duration.create(5, "seconds"), crawlFrontierRouter, CheckShouldDownloadPage(Configuration.BROAD_CRAWL, crawlUrl :: List.empty[URL]))
			}
			case Configuration.FOCUS_CRAWL => {
				Logger.info(s"Activate Crawler with focused crawl using url: $crawlUrl")
				var pageListPointer = cache.findPointer("domain", crawlUrl.getHost).asInstanceOf[RecordSet]

				// Assess how to seed
				// val pageOrderedList = pageList.sortWith(_.getOrElse("score",0).asInstanceOf[Int] > _.getOrElse("score", 0).asInstanceOf[Int])
				val visitCounter: mutable.HashMap[Int, Int] = new mutable.HashMap[Int, Int].empty
				var totalCount = 0
				while (pageListPointer.next()) {
					val item = pageListPointer.getRecord.bins.asScala

					val score = {
						item.getOrElse("score", 0) match {
							case i: Int => i
							case l: Long => l.toInt
						}
					}

					if (score >= Configuration.revisitScore) {
						val visits = {
							item.getOrElse("visits", 0) match {
								case i: Int => i
								case l: Long => l.toInt
							}
						}

						if (visits >= 0)
							visitCounter(visits) = visitCounter.getOrElse(visits, 0) + 1
						totalCount += 1
					}
				}

				// Work out limiting point
				val sortedVisitCount = visitCounter.toSeq.sortBy(_._1).iterator
				var remainder = totalCount
				var visitCeiling = 0
				var counter = 0

				while (counter <= domainChunkSize && sortedVisitCount.hasNext) {
					val next = sortedVisitCount.next

					if (remainder == totalCount)
						visitCeiling = next._1

					remainder -= next._2
					counter += next._2
				}

				// Notify highest visit count allowed
				try {
					// Set query information against which to set data
					val selector = Map("domain" -> crawlUrl.getHost)
					val values = Map("visit_ceiling" -> visitCeiling)

					// Perform the data write
					domainCollection.update(values, selector)
				}
				catch {
					case e: Throwable => Logger.warn(s"Write to Command and Control Data failed: ${e.getMessage}", error = e)
				}


				// Perform seeding
				pageListPointer = cache.findPointer("domain", crawlUrl.getHost).asInstanceOf[RecordSet]

				var seedCounter = 0
				while (pageListPointer.next() && seedCounter <= domainChunkSize) {
					val url = pageListPointer.getKey.userKey.toString
					val item = pageListPointer.getRecord.bins.asScala

					val score = {
						item.getOrElse("score", Configuration.revisitScore) match {
							case i: Int => i
							case l: Long => l.toInt
						}
					}

					val status = {
						item.getOrElse("status", 0) match {
							case i: Int => i
							case l: Long => l.toInt
						}
					}

					val visits = {
						item.getOrElse("visits", 0) match {
							case i: Int => i
							case l: Long => l.toInt
						}
					}

					val priorInstance = item.getOrElse("instance", "")
					val readyToVisit = if (System.currentTimeMillis - item.getOrElse("last_crawl", 0L).asInstanceOf[Long] > Configuration.revisitDelay.toMillis) true else false

					if (priorInstance != instance && readyToVisit &&
						(visits == 0 || score >= Configuration.revisitScore) &&
						status < 400 &&
						visits <= visitCeiling) {
						crawlerSystem.scheduler.scheduleOnce(Duration.create(5, "seconds"), crawlFrontierRouter, CheckShouldDownloadPage(Configuration.FOCUS_CRAWL, new URL(s"http://${url}") :: List.empty[URL]))

						seedCounter += 1
					}
				}

				Logger.info(s"Seeding complete for focused crawl with ${seedCounter} urls to visit: ${crawlUrl.getHost}")
			}
		}
	}

	def getCommanderServer(callback: Function1[Tuple2[String, Int], Unit]) = {
		try {
			val query = Map("commander" -> commander)
			val projection = Map("commander" -> 1, "host" -> 1, "port" -> 1)

			def action(results: List[Map[String, Any]]) = {
				if (results.nonEmpty) {
					results foreach { result =>
						val identifier = result("commander").asInstanceOf[String]

						if (identifier == commander) {
							val host = result.getOrElse("host", "").asInstanceOf[String]
							val port = result.getOrElse("port", 0).asInstanceOf[Int]

							callback(host -> port)
						}
					}
				}
			}

			commandControlData.read(query, projection, action)
		}
		catch {
			case e: Throwable => Logger.warn(s"Obtain command and control data failed: ${e.getMessage}", error = e)
		}
	}
}

