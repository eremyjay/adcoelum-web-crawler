package com.adcoelum.seed

import java.net.{SocketTimeoutException, URL}

import akka.actor.{Actor, ActorSelection}
import akka.util.Timeout
import com.adcoelum.actors._
import com.adcoelum.common.{Configuration, Logger, Tools}
import com.adcoelum.data.{Collection, DatabaseAccessor, Mongo}

import scala.collection.mutable
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.concurrent.duration._
import scala.language.postfixOps
import scalaj.http.{Http, HttpOptions}

/**
  * Created by jeremy on 2/01/2017.
  */
class Seeder(domainCollection: Collection) extends Actor {
    implicit val timeout = Timeout((Configuration.config \ "seeder" \ "resolverTimeout").as[Int] seconds)

    val crawlDelay = (Configuration.config \ "seeder" \ "crawlDelay").as[Int] seconds
    val broadCrawlDelay = (Configuration.config \ "seeder" \ "broadCrawlDelay").as[Int] seconds
    val domainChunkSize = (Configuration.config \ "seeder" \ "domainChunkSize").as[Int]

    val commandControlData = DatabaseAccessor.collection("command_control")
    val domainsData = DatabaseAccessor.collection("domains")
    val downloadQueue = DatabaseAccessor.collection("download_queue")

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


    // Setup other domain specific variables
    val domainSeederCount: HashMap[String, Int] = mutable.HashMap.empty[String, Int]
    val domainVisitCeiling: HashMap[String, Int] = mutable.HashMap.empty[String, Int]
    val lastDownloadInformation: HashMap[String, Map[String, Any]] = mutable.HashMap.empty[String, Map[String, Any]]

    val activeCrawlers: ListBuffer[String] = ListBuffer.empty[String]
    var seedCounter: Int = 0


    // Establish connections to local actors
    val seederActivityMonitor = context.actorSelection("/user/seeder_activity_monitor")


    def receive = {
        case Seed(selfCommander) => {
            checkDomains

            Logger.debug("Performing crawler seeding process")
            val now = System.currentTimeMillis

            var readyForSeeding: Boolean = false
            readyToSeed((result) => {
                readyForSeeding = result
            })

            if (isHighestRank(selfCommander) && readyForSeeding) {
                var idleCommanders: Map[String, Tuple2[String, Int]] = Map.empty
                idleCrawlingCommanders((result) => {
                    idleCommanders = result
                })

                if (idleCommanders.nonEmpty) {
                    idleCommanders foreach { commander =>
                        try {
                            var focusDomains: List[Map[String, Any]] = List.empty
                            domainsFocusMap((result) => {
                                focusDomains = result
                            })

                            var broadDomains: List[Map[String, Any]] = List.empty
                            domainsBroadMap((result) => {
                                broadDomains = result
                            })

                            var domain: Map[String, Any] = Map.empty[String, Any]
                            var domainToCrawl = false
                            var method = -1
                            var values = Map.empty[String, Any]

                            if (broadDomains.nonEmpty) {
                                domain = broadDomains.head
                                Logger.trace(s"Deploying crawler to commander ${commander._1} for domain ${domain("domain")} for broad crawl")

                                broadDomains = broadDomains.drop(1)
                                domainToCrawl = true

                                method = Configuration.BROAD_CRAWL
                                values = Map("state" -> "broad", "last_crawl_start" -> now, "last_broad_crawl_start" -> now, "crawl_attempts" -> (domain("crawl_attempts").asInstanceOf[Int] + 1))
                            }
                            else if (focusDomains.nonEmpty) {
                                domain = focusDomains.head
                                Logger.trace(s"Deploying crawler to commander ${commander._1} for domain ${domain("domain")} for focused crawl")

                                focusDomains = focusDomains.drop(1)
                                domainToCrawl = true

                                method = Configuration.FOCUS_CRAWL
                                values = Map("state" -> "focus", "last_crawl_start" -> now, "crawl_attempts" -> (domain("crawl_attempts").asInstanceOf[Int] + 1))
                            }

                            if (domainToCrawl) {
                                try {
                                    // Set query information against which to set data
                                    val selector = Map("domain" -> domain("domain"))

                                    // Perform the data write
                                    domainCollection.update(values, selector, wait = true)
                                }
                                catch {
                                    case e: Throwable => Logger.warn(s"Write to Domains list failed: ${e.getMessage}", error = e)
                                }

                                // Start crawler
                                try {
                                    Http(s"http://${commander._2._1}:${commander._2._2}/crawler/${method}/${domain("domain")}/${now}")
                                        .asString
                                }
                                catch {
                                    case e: SocketTimeoutException =>
                                    case e: Throwable => Logger.warn(s"Unable to start crawler: ${e.getMessage}", error = e)
                                }

                                currentFrequencies(domain("domain").asInstanceOf[String]) = throttlingDefaultFrequency
                                currentRates(domain("domain").asInstanceOf[String]) = throttlingDefaultRate
                                currentWaits(domain("domain").asInstanceOf[String]) = throttlingDefaultWait
                                numberOfDownloadsWithinRates(domain("domain").asInstanceOf[String]) = 0
                                lastThrottleWindowTimes(domain("domain").asInstanceOf[String]) = 0L
                                waitCounters(domain("domain").asInstanceOf[String]) = 0
                                changeTightCounters(domain("domain").asInstanceOf[String]) = 0
                                changeLooseCounters(domain("domain").asInstanceOf[String]) = 0
                                lastAdjustRequests(domain("domain").asInstanceOf[String]) = 0

                                lastDownloadInformation(domain("domain").asInstanceOf[String]) = Map.empty[String, Any]
                                domainSeederCount(domain("domain").asInstanceOf[String]) = 0
                                domainVisitCeiling(domain("domain").asInstanceOf[String]) = 0

                                seederActivityMonitor ! Statistic("throttle", Map("domain" -> domain("domain").asInstanceOf[String],
                                    "throttleFrequency" -> currentFrequencies(domain("domain").asInstanceOf[String]),
                                    "throttleRate" -> currentRates(domain("domain").asInstanceOf[String]).toMillis))
                            }
                        }
                        catch {
                            case e: Throwable => Logger.warn(s"Unable to seed domain to commander ${commander}, moving on to next option", error = e)
                        }
                    }
                }

                Logger.debug("Crawler seeding process complete")
            }
            else
                Logger.debug("Skipping seeding process as rank is not highest for this commander or minimum indexers/downloaders not available")


            Logger.trace("Updating crawl active list", Logger.VERBOSE_INTENSITY)
            var activeDomains: List[String] = List.empty
            domainsActiveMap((result) => {
                activeDomains = result
            })

            activeDomains foreach { domain =>
                if (!activeCrawlers.contains(domain)) {
                    activeCrawlers += domain
                    Logger.trace(s"Adding ${domain} to active crawler list", Logger.VERBOSE_INTENSITY)
                }
            }

            var counter = 0
            activeCrawlers foreach { domain =>
                if (!activeDomains.contains(domain)) {
                    activeCrawlers.remove(counter)
                    Logger.trace(s"Removing ${domain} from active crawler list", Logger.VERBOSE_INTENSITY)
                }
                counter += 1
            }

            Logger.debug("Updating crawl active list complete")
        }

        case Ask(message: String) => {
            handleRequest(message)
        }

        case AdjustThrottle(direction, domain) => {
            adjustThrottle(direction, domain)
        }

        case TriggerActivity() => {
            sender ! Activity("seeder", 0, 0)
        }
    }

    def domainsFocusMap(callback: Function1[List[Map[String, Any]], Unit]) = {
        try {
            val now = System.currentTimeMillis

            val query = Map("$or" -> List(Map("crawl_finish" -> Map("$lt" -> (now - crawlDelay.toMillis))), Map("state" -> "failed")))
            val projection = Map.empty[String, Any]
            val sort = ("crawl_finish", 1)

            def action(results: List[Map[String, Any]]) = {
                val list: ListBuffer[Map[String, Any]] = ListBuffer.empty

                if (results.nonEmpty) {
                    results foreach { result =>
                        var counter = 0

                        if (result("state").asInstanceOf[String] == "idle" || result("state").asInstanceOf[String] == "failed") {
                            list foreach { item =>
                                if (item("crawl_finish").asInstanceOf[Long] < result("crawl_finish").asInstanceOf[Long])
                                    counter += 1
                            }

                            if (counter >= list.length)
                                list += result
                            else
                                list.insert(counter, result)
                        }
                    }
                }

                callback(list.toList)
            }

            domainCollection.read(query, projection, action, sort = sort)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain domain focus map failed: ${e.getMessage}", error = e)
        }
    }

    def domainsBroadMap(callback: Function1[List[Map[String, Any]], Unit]) = {
        try {
            val now = System.currentTimeMillis

            val query = Map("crawl_finish" -> Map("$lt" -> (now - broadCrawlDelay.toMillis)))
            val projection = Map.empty[String, Any]
            val sort = ("crawl_finish", 1)

            def action(results: List[Map[String, Any]]) = {
                val list: ListBuffer[Map[String, Any]] = ListBuffer.empty

                if (results.nonEmpty) {
                    results foreach { result =>
                        var counter = 0

                        if (result("state").asInstanceOf[String] == "idle" || result("state").asInstanceOf[String] == "failed") {
                            list foreach { item =>
                                if (item("crawl_finish").asInstanceOf[Long] < result("crawl_finish").asInstanceOf[Long])
                                    counter += 1
                            }

                            if (counter >= list.length)
                                list += result
                            else
                                list.insert(counter, result)
                        }
                    }
                }

                callback(list.toList)
            }

            domainCollection.read(query, projection, action, sort = sort)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain domain focus map failed: ${e.getMessage}", error = e)
        }
    }


    def domainsActiveMap(callback: Function1[List[String], Unit]) = {
        try {
            val query = Map.empty[String, Any]
            val projection = Map.empty[String, Any]

            def action(results: List[Map[String, Any]]) = {
                val list: ListBuffer[String] = ListBuffer.empty

                if (results.nonEmpty) {
                    results foreach { result =>
                        if (result("state").asInstanceOf[String] == "broad" || result("state").asInstanceOf[String] == "focus")
                            list += result("domain").asInstanceOf[String]
                    }
                }

                callback(list.toList)
            }

            domainCollection.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain domain focus map failed: ${e.getMessage}", error = e)
        }
    }


    def idleCrawlingCommanders(callback: Function1[Map[String, Tuple2[String, Int]], Unit]) = {
        try {
            val query = Map.empty[String, Any]
            val projection = Map("commander" -> 1, "host" -> 1, "port" -> 1, "crawlers" -> 1, "maximumCrawlers" -> 1)

            def action(results: List[Map[String, Any]]) = {
                val map: HashMap[String, Tuple2[String, Int]] = HashMap.empty[String, Tuple2[String, Int]]

                if (results.nonEmpty) {
                    results foreach { result =>
                        if (result.contains("crawlers") && result.contains("maximumCrawlers") &&
                            result("crawlers").asInstanceOf[Map[String, Any]].size < result("maximumCrawlers").asInstanceOf[Int])
                            map += (result("commander").asInstanceOf[String] -> (result("host").asInstanceOf[String], result("port").asInstanceOf[Int]))
                    }
                }

                callback(map.toMap)
            }

            commandControlData.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain idle crawling commanders failed: ${e.getMessage}", error = e)
        }
    }

    def isHighestRank(commander: String): Boolean = {
        var commanders: Map[String, Int] = Map.empty
        commandersMap(commander, (results) => {commanders = results})

        var isHighest = true
        val selfRank = commanders.getOrElse(commander, -1)

        if (selfRank == -1)
            isHighest = false
        else {
            commanders.values foreach { commanderRank =>
                if (commanderRank != -1 && commanderRank < selfRank)
                    isHighest = false
            }
        }

        isHighest
    }

    def commandersMap(commander: String, callback: Function1[Map[String, Int], Unit]) = {
        try {
            val query = Map.empty[String, Any]
            val projection = Map("commander" -> 1, "rank" -> 1)

            def action(results: List[Map[String, Any]]) = {
                val map: HashMap[String, Int] = HashMap.empty[String, Int]

                if (results.nonEmpty) {
                    results foreach { result =>
                        val identifier = result("commander").asInstanceOf[String]

                        // Avoid including self
                        val rank = result.getOrElse("rank", -1).asInstanceOf[Int]
                        map += (identifier -> rank)
                    }
                }

                callback(map.toMap)
            }

            commandControlData.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain command and control map failed: ${e.getMessage}", error = e)
        }
    }

    def handleRequest(message: String) = {
        message match {
            case "download" => {
                Logger.trace(s"Received request to perform downloading from ${sender.path.toString}", Logger.VERBOSE_INTENSITY)
                var downloadMessage = Map.empty[String, Any]

                // Determine message to handle
                if (activeCrawlers.nonEmpty) {
                    val domain = activeCrawlers(seedCounter % activeCrawlers.size)
                    seedCounter += 1

                    if (domainSeederCount(domain) <= domainChunkSize) {
                        domainSeederCount(domain) += 1

                        val visitsChoice = domainSeederCount(domain) % (domainVisitCeiling(domain) + 1)

                        val lastMethod: Int = lastDownloadInformation(domain).getOrElse("method", Configuration.FOCUS_CRAWL).asInstanceOf[Int]
                        val lastVisits: Int = lastDownloadInformation(domain).getOrElse("visits", 0).asInstanceOf[Int]

                        var selector = visitsChoice
                        while (downloadMessage.isEmpty && selector < visitsChoice + domainVisitCeiling(domain) + 1) {
                            val visitsSelector: Map[String, Any] = Map("domain" -> domain, "visits" -> (selector % (domainVisitCeiling(domain) + 1)))
                            val filter = Map.empty[String, Any]

                            downloadQueue.readFirst(visitsSelector, filter, (result) => {
                                downloadMessage = result

                                if (downloadMessage.isEmpty)
                                    selector += 1
                            })
                        }


                        // Handle the message
                        if (downloadMessage != Map.empty[String, Any]) {
                            val method = downloadMessage("method").asInstanceOf[Int]
                            val visits = downloadMessage("visits").asInstanceOf[Int]
                            val chain = Tools.deserialize[List[URL]](downloadMessage("chain").asInstanceOf[Array[Byte]])
                            val hash = downloadMessage("hash").asInstanceOf[String]

                            Logger.trace(s"Data pulled from queue:\nmethod - ${method}\nchain - ${chain}\nhash - ${hash}", Logger.ULTRA_VERBOSE_INTENSITY)

                            val domain = chain.head.getHost
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

                                lastDownloadInformation(domain) = Map.empty[String, Any]
                                domainSeederCount(domain) = 0
                                domainVisitCeiling(domain) = 0
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

                            Logger.trace(s"Instructing download information to sender ${sender.path.toString} for domain ${domain}", Logger.VERBOSE_INTENSITY)
                            sender ! DownloadPage(method, visits, chain, domain, hash)
                            numberOfDownloadsWithinRates(domain) += 1

                            seederActivityMonitor ! Statistic("throttle", Map("domain" -> domain,
                                "downloadsWithinRate" -> numberOfDownloadsWithinRates(domain),
                                "timePassed" -> timePassed,
                                "changeTightCounter" -> changeTightCounters(domain),
                                "changeLooseCounter" -> changeLooseCounters(domain),
                                "waitCounter" -> waitCounters(domain),
                                "currentWait" -> currentWaits(domain),
                                "seederCount" -> domainSeederCount(domain)))

                            downloadQueue.delete(Map("id" -> downloadMessage("id")), wait = true)
                            lastDownloadInformation(domain) = Map("method" -> method, "visits" -> visits)
                        }
                    }
                }
            }
        }
    }

    def getCrawlFrontierRouter(name: String): ActorSelection = {
        var crawlerSystems = List.empty[Tuple3[String, String, Int]]
        crawlerSystemsList((crawlers) => {crawlerSystems = crawlers})

        var selectionString = ""
        var found = false

        crawlerSystems foreach { crawlerSystem =>
            if (!found && crawlerSystem._1 == name) {
                selectionString = s"akka.tcp://${crawlerSystem._1}@${crawlerSystem._2}:${crawlerSystem._3}/user/crawl_frontier_router"
                Logger.trace(s"Crawl Frontier Router used: $selectionString", Logger.MEGA_VERBOSE_INTENSITY)
                found = true
            }
        }


        if (selectionString == "") {
            Logger.warn(s"No matching crawler for ${name} available to set a router", error = new Exception("No crawlers available"))
            context.actorSelection("")
        }
        else
            context.actorSelection(selectionString)
    }

    def crawlerSystemsList(callback: Function1[List[Tuple3[String, String, Int]], Unit]) = {
        try {
            val query = Map.empty[String, Any]
            val projection = Map("host" -> 1, "crawlers" -> 1)

            // Return the results based on the search
            def action(results: List[Map[String, Any]]) = {
                var crawlers: ListBuffer[Tuple3[String, String, Int]] = ListBuffer.empty[Tuple3[String, String, Int]]

                results foreach { result =>
                    if (result.contains("host") && result.contains("crawlers")) {
                        val host = result("host").asInstanceOf[String]

                        result("crawlers").asInstanceOf[Map[String, Map[String, Any]]] foreach { indexMap =>
                            val name = indexMap._1
                            val port = indexMap._2.asInstanceOf[Map[String, Any]].getOrElse("systemPort", 0).asInstanceOf[Int]
                            crawlers += new Tuple3(name, host, port)
                        }
                    }
                }

                callback(crawlers.toList)
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
                if (waitCounters(domain) > currentWaits(domain) &&
                    lastAdjustRequests(domain) >= waitCounters(domain) - throttlingChangeLooseRange)
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

            seederActivityMonitor ! Statistic("throttle", Map("domain" -> domain,
                                                        "throttleFrequency" -> currentFrequencies(domain),
                                                        "throttleRate" -> currentRates(domain).toMillis,
                                                        "changeTightCounter" -> changeTightCounters(domain),
                                                        "changeLooseCounter" -> changeLooseCounters(domain),
                                                        "waitCounter" -> waitCounters(domain),
                                                        "currentWait" -> currentWaits(domain),
                                                        "seederCount" -> domainSeederCount(domain)))
        }
    }



    def checkDomains = {
        Logger.debug(s"Checking whether all active domains are still crawling")
        var activeCrawlerDomains: List[String] = List.empty[String]
        var activeDomains: List[String] = List.empty[String]
        allCrawlerDomainsList((result) => {activeCrawlerDomains = result})
        activeDomainsList((result) => {activeDomains = result})

        activeDomains foreach { domain =>
            Logger.trace(s"Checking if domain ${domain} is still active", Logger.VERBOSE_INTENSITY)

            if (!activeCrawlerDomains.contains(domain)) {
                Logger.debug(s"Clearing download queue for domain ${domain} which failed for crawl", Logger.VERBOSE_INTENSITY)

                // Load data into database to set domain failure
                try {
                    // Set query information against which to set data
                    val query = Map("domain" -> domain)

                    // Perform the data write
                    downloadQueue.delete(query, wait = true)
                }
                catch {
                    case e: Throwable => Logger.warn(s"Failed to update data to clean up domain information: ${e.getMessage}", error = e)
                }

                Logger.debug(s"Setting state for domain ${domain} to failed for crawl", Logger.VERBOSE_INTENSITY)

                // Load data into database to set domain failure
                try {
                    // Set query information against which to set data
                    val query = Map("domain" -> domain)
                    val values = Map("state" -> "failed")

                    // Perform the data write
                    domainCollection.update(values, query, wait = true)
                }
                catch {
                    case e: Throwable => Logger.warn(s"Failed to update data to clean up domain information: ${e.getMessage}", error = e)
                }
            }

            val query = Map("domain" -> domain)
            val projection = Map("visit_ceiling" -> 1)

            def action(result: Map[String, Any]) = {
                val visitCeiling = result.getOrElse("visit_ceiling", 0).asInstanceOf[Int]
                domainVisitCeiling(domain) = visitCeiling
                Logger.trace(s"Updating domain ${domain} visit ceiling to ${visitCeiling}", Logger.VERBOSE_INTENSITY)

            }

            domainCollection.readFirst(query, projection, action)
        }
    }

    def allCrawlerDomainsList(callback: Function1[List[String], Unit]) = {
        try {
            val query = Map.empty[String, Any]
            val projection = Map("crawlers" -> 1)

            def action(results: List[Map[String, Any]]) = {
                val list: ListBuffer[String] = ListBuffer.empty[String]

                results foreach { result =>
                    if (result.contains("crawlers")) {
                        var domain = ""

                        result("crawlers").asInstanceOf[Map[String, Any]] foreach { element =>
                            domain = element._2.asInstanceOf[Map[String, Any]].getOrElse("domain", "").asInstanceOf[String]

                            if (domain != "")
                                list += domain
                        }
                    }
                }

                callback(list.toList)
            }

            commandControlData.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain all crawler domains list failed: ${e.getMessage}", error = e)
        }
    }

    def activeDomainsList(callback: Function1[List[String], Unit]) = {
        try {
            val query = Map("state" -> Map("$nin" -> List("idle", "failed")))
            val projection = Map("domain" -> 1)

            def action(results: List[Map[String, Any]]) = {
                val list: ListBuffer[String] = ListBuffer.empty[String]

                results foreach { result =>
                    var domain = result.getOrElse("domain", "").asInstanceOf[String]

                    if (domain != "")
                        list += domain
                }

                callback(list.toList)
            }

            domainsData.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain all crawler domains list failed: ${e.getMessage}", error = e)
        }
    }

    def readyToSeed(callback: Function1[Boolean, Unit]) = {
        try {
            val indexersQuery = Map("$where" -> Mongo.Javascript(
                """
                  |var count = 0;
                  |for (var i in this.indexers)
                  |    count++;
                  |
                  |if (count == 0)
                  |    return false;
                  |else
                  |    return true;
                """.stripMargin))
            val downloadersQuery = Map("$where" -> Mongo.Javascript(
                """
                  |var count = 0;
                  |for (var i in this.downloaders)
                  |    count++;
                  |
                  |if (count == 0)
                  |    return false;
                  |else
                  |    return true;
                """.stripMargin))

            def indexersAction(resultA: Int) = {
                if (resultA > 0) {
                    def downloadersAction(resultB: Int) = {
                        if (resultB > 0)
                            callback(true)
                        else
                            callback(false)
                    }

                    commandControlData.count(downloadersQuery, downloadersAction)
                }
                else
                    callback(false)
            }

            commandControlData.count(indexersQuery, indexersAction)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain all crawler domains list failed: ${e.getMessage}", error = e)
        }
    }
}
