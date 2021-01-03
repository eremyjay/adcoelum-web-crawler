package com.adcoelum.downloader.workers

import akka.actor.{Actor, ActorRef, ActorSelection}
import com.adcoelum.actors._
import com.adcoelum.common.{Configuration, Logger, Tools}
import com.adcoelum.data.DatabaseAccessor

import scala.collection.mutable
import scala.collection.mutable.HashMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.language.postfixOps



class DownloadQueuer extends Actor {
    // Connect to the data collection
    val commandControlData = DatabaseAccessor.collection("command_control")

    // Establish connections to remote actors - treat as a method call
    var downloaderSystems: List[Tuple3[String, String, Int]] = List.empty[Tuple3[String, String, Int]]

    // Obtain default throttle settings
    val throttlingEnabled = (Configuration.config \ "downloader" \ "throttling" \ "enable").as[Boolean]
    val throttlingDefaultFrequency = (Configuration.config \ "downloader" \ "throttling" \ "defaultFrequency").as[Int]
    val throttlingDefaultRate = (Configuration.config \ "downloader" \ "throttling" \ "defaultRate").as[Int] seconds
    val throttlingDefaultWait = (Configuration.config \ "downloader" \ "throttling" \ "defaultWait").as[Int]
    val throttlingFrequencyLimit = (Configuration.config \ "downloader" \ "throttling" \ "frequencyLimit").as[Int]
    val throttlingRateLimit = (Configuration.config \ "downloader" \ "throttling" \ "rateLimit").as[Int] seconds
    val throttlingWaitLimit = (Configuration.config \ "downloader" \ "throttling" \ "waitLimit").as[Int]
    val throttlingChangeTightThreshold = (Configuration.config \ "downloader" \ "throttling" \ "changeTightThreshold").as[Int]
    val throttlingChangeLooseThreshold = (Configuration.config \ "downloader" \ "throttling" \ "changeLooseThreshold").as[Int]
    val throttlingChangeTightRange = (Configuration.config \ "downloader" \ "throttling" \ "changeTightRange").as[Int]
    val throttlingChangeLooseRange = (Configuration.config \ "downloader" \ "throttling" \ "changeLooseRange").as[Int]
    val throttlingMultiplier = (Configuration.config \ "downloader" \ "throttling" \ "multiplier").as[Int]
    val throttlingAutoAdjust = (Configuration.config \ "downloader" \ "throttling" \ "autoAdjust").as[Boolean]
    val randomiserRange = (Configuration.config \ "downloader" \ "randomiser").as[Int]

    // Initialise throttling variables
    var numberOfDownloadsWithinRates: HashMap[String, Int] = mutable.HashMap.empty[String, Int]
    var lastThrottleWindowTimes: HashMap[String, Long] = mutable.HashMap.empty[String, Long]
    var waitCounters: HashMap[String, Int] = mutable.HashMap.empty[String, Int]

    val currentFrequencies: HashMap[String, Int] = mutable.HashMap.empty[String, Int]
    val currentRates: HashMap[String, Duration] = mutable.HashMap.empty[String, Duration]
    val currentWaits: HashMap[String, Int] = mutable.HashMap.empty[String, Int]

    val changeTightCounters: HashMap[String, Int] = mutable.HashMap.empty[String, Int]
    val changeLooseCounters: HashMap[String, Int] = mutable.HashMap.empty[String, Int]
    val lastAdjustRequests: HashMap[String, Int] = mutable.HashMap.empty[String, Int]

    // Establish connections to local actors
    val activityMonitor = context.actorSelection("/user/activity_monitor")

    val downloadQueue = DatabaseAccessor.collection("download_queue")
    val digest = java.security.MessageDigest.getInstance("MD5")
    val randomiser = scala.util.Random

    val messageMethod = (Configuration.config \ "downloader" \ "method").as[String]

    def receive = {
        case Queue(message) => {
            if (messageMethod.toLowerCase == "push") {
                if (message.isInstanceOf[DownloadPage]) {
                    val downloadMessage = message.asInstanceOf[DownloadPage]

                    val domain = downloadMessage.urlChain.head.getHost
                    if (!lastThrottleWindowTimes.contains(domain)) {
                        currentFrequencies(domain) = throttlingDefaultFrequency
                        currentRates(domain) = throttlingDefaultRate
                        currentWaits(domain) = throttlingDefaultWait
                        numberOfDownloadsWithinRates(domain) = 0
                        lastThrottleWindowTimes(domain) = 0L
                        waitCounters(domain) = 0
                        changeTightCounters(domain) = 0
                        changeLooseCounters(domain) = 0
                        lastAdjustRequests(domain) = 0
                    }

                    var timePassed = System.currentTimeMillis - lastThrottleWindowTimes(domain)

                    if (changeTightCounters(domain) > 0 && lastAdjustRequests(domain) < waitCounters(domain) - throttlingChangeTightRange)
                        changeTightCounters(domain) = 0
                    if (changeLooseCounters(domain) > 0 && lastAdjustRequests(domain) < waitCounters(domain) - throttlingChangeLooseRange)
                        changeLooseCounters(domain) = 0

                    if (timePassed >= currentRates(domain).toMillis) {
                        lastThrottleWindowTimes(domain) = System.currentTimeMillis
                        timePassed = 0
                        numberOfDownloadsWithinRates(domain) = 0
                        waitCounters(domain) += 1
                    }
                    else if (throttlingEnabled && numberOfDownloadsWithinRates(domain) >= currentFrequencies(domain)) {
                        self ! AdjustThrottle(-1, domain)
                        Tools.sleep((currentRates(domain).toMillis - timePassed + 1) milliseconds)
                        timePassed = System.currentTimeMillis - lastThrottleWindowTimes(domain)
                    }

                    getDownloader ! message
                    numberOfDownloadsWithinRates(domain) += 1

                    activityMonitor ! Statistic("throttle", Map("domain" -> domain,
                        "downloadsWithinRate" -> numberOfDownloadsWithinRates(domain),
                        "timePassed" -> timePassed))
                }
            }
            else if (messageMethod.toLowerCase == "pull") {
                if (message.isInstanceOf[DownloadPage]) {
                    // case class DownloadPage(crawlMethod: Int, urlChain: List[URL], hash: String)

                    val downloadMessage = message.asInstanceOf[DownloadPage]
                    val messageToStore = Map("method" -> downloadMessage.crawlMethod, "visits" -> downloadMessage.visits,
                                             "chain" -> Tools.serialize(downloadMessage.urlChain), "hash" -> downloadMessage.hash,
                                             "id" -> System.currentTimeMillis.toString, "domain" -> downloadMessage.domain,
                                             "random" -> randomiser.nextInt(randomiserRange))

                    downloadQueue.create(messageToStore)
                }
            }
        }
    }

    // Callback function to perform the set
    def setDownloaderSystems(list: List[Tuple3[String, String, Int]]) = {
        downloaderSystems = list
    }


    def getDownloader: ActorSelection = {
        if (downloaderSystems.isEmpty) {
            Logger.warn("No downloaders available to set a router", error = new Exception("No downloaders available"))
            context.actorSelection("")
        }
        else {
            val rand = scala.util.Random
            val chosen = {
                if (downloaderSystems.size > 1)
                    rand.nextInt(downloaderSystems.size)
                else
                    0
            }

            val selectionString = s"akka.tcp://${downloaderSystems(chosen)._1}@${downloaderSystems(chosen)._2}:${downloaderSystems(chosen)._3}/user/downloader_router"
            Logger.trace(s"Page Downloader used: $selectionString", Logger.ULTRA_VERBOSE_INTENSITY)
            context.actorSelection(selectionString)
        }
    }

    def downloaderSystemsList(callback: Function1[List[Tuple3[String, String, Int]], Unit]) = {
        try {
            val query = Map.empty[String, Any]
            val projection = Map("host" -> 1, "downloaders" -> 1)

            // Return the results based on the search
            def action(results: List[Map[String, Any]]) = {
                var downloaders: ListBuffer[Tuple3[String, String, Int]] = ListBuffer.empty[Tuple3[String, String, Int]]

                results foreach { result =>
                    if (result.contains("host") && result.contains("downloaders")) {
                        val host = result("host").asInstanceOf[String]

                        result("downloaders").asInstanceOf[Map[String, Map[String, Any]]] foreach { indexMap =>
                            val name = indexMap._1
                            val port = indexMap._2.asInstanceOf[Map[String, Any]].getOrElse("systemPort", 0).asInstanceOf[Int]
                            downloaders += new Tuple3(name, host, port)
                        }
                    }
                }

                callback(downloaders.toList)
            }

            commandControlData.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain indexer map failed: ${e.getMessage}", error = e)
        }
    }


    def adjustThrottle(direction: Int, domain: String) = {
        if (throttlingAutoAdjust) {
            // Handle when to deal with time to change
            // Tighten throttling
            if (direction > 0) {
                if (lastAdjustRequests(domain) >= waitCounters(domain) - throttlingChangeTightRange)
                    changeTightCounters(domain) += 1
                else
                    changeTightCounters(domain) = 1

                if (currentWaits(domain) * throttlingMultiplier <= throttlingWaitLimit)
                    currentWaits(domain) = currentWaits(domain) * throttlingMultiplier

                if (changeTightCounters(domain) >= throttlingChangeTightThreshold) {
                    if (currentFrequencies(domain) > 1) {
                        currentFrequencies(domain) = Math.ceil(currentFrequencies(domain) / throttlingMultiplier).toInt
                    }
                    else if (currentRates(domain) < throttlingRateLimit) {
                        currentRates(domain) = (currentRates(domain).toSeconds + 1) seconds
                    }

                    changeTightCounters(domain) = 0
                    changeLooseCounters(domain) = 0
                    waitCounters(domain) = 0
                }
            }
            // Loosen Throttling
            else if (direction < 0) {
                if (lastAdjustRequests(domain) >= waitCounters(domain) - throttlingChangeLooseRange)
                    changeLooseCounters(domain) += 1
                else
                    changeLooseCounters(domain) = 1

                if (waitCounters(domain) <= currentWaits(domain)) {
                    if (waitCounters(domain) <= throttlingChangeLooseRange && currentWaits(domain) + 1 <= throttlingWaitLimit)
                        currentWaits(domain) += 1
                }
                else {
                    if (Math.ceil(currentWaits(domain) / throttlingMultiplier) >= 1)
                        currentWaits(domain) = Math.ceil(currentWaits(domain) / throttlingMultiplier).toInt

                    if (changeLooseCounters(domain) >= throttlingChangeLooseThreshold) {
                        if (currentRates(domain).toSeconds > 1)
                            currentRates(domain) = Math.ceil(currentRates(domain).toSeconds / throttlingMultiplier).toInt seconds
                        else if (currentFrequencies(domain) < throttlingFrequencyLimit)
                            currentFrequencies(domain) += 1

                        changeTightCounters(domain) = 0
                        changeLooseCounters(domain) = 0
                        waitCounters(domain) = 0
                    }
                }
            }

            lastAdjustRequests(domain) = waitCounters(domain)

            activityMonitor ! Statistic("throttle", Map("domain" -> domain,
                "throttleFrequency" -> currentFrequencies(domain),
                "throttleRate" -> currentRates(domain).toMillis,
                "changeTightCounter" -> changeTightCounters(domain),
                "changeLooseCounter" -> changeLooseCounters(domain),
                "waitCounter" -> waitCounters(domain),
                "currentWait" -> currentWaits(domain)))
        }
    }
}



