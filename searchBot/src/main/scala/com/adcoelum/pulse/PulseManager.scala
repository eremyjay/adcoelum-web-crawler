package com.adcoelum.pulse

import akka.actor.{ActorSystem, Address, Props, RootActorPath}
import com.adcoelum.common.{Configuration, Logger, Tools}
import com.adcoelum.actors._
import com.typesafe.akka.extension.quartz.QuartzSchedulerExtension
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

/**
  * Created by jeremy on 28/12/2016.
  */

class PulseManager(ccIdentifier: String) {
    val aliveFrequency = (Configuration.config \ "pulse" \ "alivePulseFrequency").as[Int] seconds
    val commander = ccIdentifier

    var pulseName = System.currentTimeMillis.toString + "_pulse"
    val pulsePort = Tools.findFreePort

    // Setting actor system configuration
    val actorSystemConfiguration = ConfigFactory.parseString(Configuration.indexerActorConfig + s"""
       |akka.remote.netty.tcp.port=${pulsePort}
    """.stripMargin)

    // Initiate PulseManager actor system
    Logger.info("Initialising Pulse Actor System")
    val pulseSystem = ActorSystem(pulseName, actorSystemConfiguration)

    Logger.info("Setting Up activity monitor for Pulse Checking")
    val pulseActivityMonitor = pulseSystem.actorOf(Props(classOf[ActivityMonitor], commander, pulseName, false, false, "/user/pulse_check"), "pulse_activity_monitor")
    Tools.sleep(2 seconds)

    Logger.info("Setting Up actors for Pulse Checking")
    val pulseCheck = pulseSystem.actorOf(Props(classOf[PulseCheck]), "pulse_check")
    Tools.sleep(2 seconds)

    // Register routers with activity monitor
    Logger.info("Registering Routers for Pulse Checking")
    pulseSystem.scheduler.scheduleOnce(Duration.create(1, "seconds"), pulseActivityMonitor, RegisterRouter("pulse_check"))
    Tools.sleep(2 seconds)

    val aliveSeconds = {
        if (aliveFrequency.toSeconds >= 60)
            s"${aliveFrequency.toSeconds % 60}"
        else
            s"0/${aliveFrequency.toSeconds % 60}"
    }
    val aliveMinutes = {
        if (aliveFrequency.toSeconds >= (60 * 60))
            s"${aliveFrequency.toSeconds / 60}"
        else if (aliveFrequency.toSeconds < 60)
            s"*"
        else
            s"0/${aliveFrequency.toSeconds / 60}"
    }

    val pulseScheduler = QuartzSchedulerExtension(pulseSystem)
    pulseScheduler.createSchedule("PulseScheduler", Some("Scheduler for alive pulse checking"), s"${aliveSeconds} ${aliveMinutes} * * * ?", None)

    // Start the activity monitor to handle termination if no more work is available
    Logger.info("Starting Activity Monitor for Pulse Checking")
    val activityPeriod = (Configuration.config \ "activity" \ "period").as[Int]
    pulseScheduler.createSchedule("PulseActivityScheduler", Some("Scheduler for checking activity"), s"0/${activityPeriod} * * * * ?", None)

    def start = {
        if (aliveFrequency.toSeconds != 0) {
            // Start the pulse to handle checking of whether components are alive
            Logger.info("Starting Alive Pulse check scheduler")
            pulseScheduler.schedule("PulseActivityScheduler", pulseActivityMonitor, Tick)
            pulseScheduler.schedule("PulseScheduler", pulseCheck, AliveCheck(commander))
        }
    }

    def stop = {
        // Stop the pulse to handle checking of whether components are alive
        Logger.info("Stopping Alive Pulse check scheduler")
        pulseScheduler.cancelJob("PulseActivityScheduler")
        pulseScheduler.cancelJob("PulseScheduler")
    }

    def runCheck = {
        pulseCheck ! AliveCheck(commander)
    }
}
