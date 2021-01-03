package com.adcoelum.seed

import akka.actor.{ActorSystem, Props}
import com.adcoelum.actors._
import com.adcoelum.common.{Configuration, Logger, Tools}
import com.adcoelum.data.DatabaseAccessor
import com.typesafe.akka.extension.quartz.QuartzSchedulerExtension
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

/**
  * Created by jeremy on 2/01/2017.
  */

case class Seed(commander: String)

class SeederManager(commander: String) {
    // Connect to the data collection
    val domainCollection = DatabaseAccessor.collection("domains")

    val frequency = (Configuration.config \ "seeder" \ "seedFrequency").as[Int] seconds

    var seederName = System.currentTimeMillis.toString + "_seeder"
    val seederPort = Tools.findFreePort

    val CRAWLER_START = 1
    val CRAWLER_END = 2

    // Setting actor system configuration
    // Setting actor system configuration
    val actorSystemConfiguration = ConfigFactory.parseString(Configuration.indexerActorConfig + s"""
        |akka.remote.netty.tcp.port=${seederPort}
    """.stripMargin)


    // Initiate PulseManager actor system
    Logger.info("Initialising Seeder Actor System")
    val seederSystem = ActorSystem(seederName, actorSystemConfiguration)

    Logger.info("Setting Up activity monitor for Seeder")
    val seederActivityMonitor = seederSystem.actorOf(Props(classOf[ActivityMonitor], commander, seederName, false, false, "/user/seeder"), "seeder_activity_monitor")
    Tools.sleep(2 seconds)

    Logger.info("Setting Up actors for Seeder")
    val seedCrawler = seederSystem.actorOf(Props(classOf[Seeder], domainCollection), "seeder")
    Tools.sleep(2 seconds)

    // Register routers with activity monitor
    Logger.info("Registering Routers for Pulse Check")
    seederSystem.scheduler.scheduleOnce(Duration.create(1, "seconds"), seederActivityMonitor, RegisterRouter("seeder"))
    Tools.sleep(2 seconds)

    val seconds = {
        if (frequency.toSeconds >= 60)
            s"${frequency.toSeconds % 60}"
        else
            s"0/${frequency.toSeconds % 60}"
    }
    val minutes = {
        if (frequency.toSeconds >= (60 * 60))
            s"${frequency.toSeconds / 60}"
        else
            s"0/${frequency.toSeconds / 60}"
    }

    val seederScheduler = QuartzSchedulerExtension(seederSystem)
    seederScheduler.createSchedule("SeederScheduler", Some("Scheduler for seeding urls for crawling and other tasks"), s"${seconds} ${minutes} * * * ?", None)

    // Start the activity monitor to handle termination if no more work is available
    Logger.info("Starting Activity Monitor for Seeder")
    val activityPeriod = (Configuration.config \ "activity" \ "period").as[Int]
    seederScheduler.createSchedule("SeederActivityScheduler", Some("Scheduler for checking activity"), s"0/${activityPeriod} * * * * ?", None)

    def start = {
        // Start the seeder to handle checking and other duites
        Logger.info("Starting Seeder scheduler")
        seederScheduler.schedule("SeederActivityScheduler", seederActivityMonitor, Tick)
        seederScheduler.schedule("SeederScheduler", seedCrawler, Seed(commander))
        seedCrawler ! Initialize()
    }

    def stop = {
        // Start the seeder to handle checking and other duites
        Logger.info("Stopping Seeder scheduler")
        seederScheduler.cancelJob("SeederActivityScheduler")
        seederScheduler.cancelJob("SeederScheduler")
    }

    def addDomain(domain: String, callback: Function1[Boolean, Unit]) = {
        try {
            val query = Map("domain" -> domain)
            val projection = Map("domain" -> 1)

            def action(results: List[Map[String, Any]]) = {
                var added = false

                if (results.isEmpty) {
                    try {
                        // Set query information against which to set data
                        val values = Map("state" -> "idle", "domain" -> domain, "last_crawl_start" -> 0L, "last_broad_crawl_start" -> 0L, "crawl_finish" -> 0L, "crawl_attempts" -> 0)

                        // Perform the data write
                        domainCollection.create(values)
                        Logger.trace(s"Adding domain ${domain} to domains list", Logger.VERBOSE_INTENSITY)
                    }
                    catch {
                        case e: Throwable => Logger.warn(s"Write to domains list failed: ${e.getMessage}", error = e)
                    }

                    added = true
                }
                else
                    Logger.trace(s"Domain ${domain} already exists in domains list - doing nothing", Logger.VERBOSE_INTENSITY)


                callback(added)
            }

            domainCollection.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain domain map failed: ${e.getMessage}", error = e)
        }
    }
}
