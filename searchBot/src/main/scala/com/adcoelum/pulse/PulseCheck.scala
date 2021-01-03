package com.adcoelum.pulse

import java.io.FileInputStream
import java.net.SocketTimeoutException

import akka.actor.Actor
import com.adcoelum.common.{Configuration, Logger, Tools}
import com.adcoelum.actors.{Activity, AliveCheck, TriggerActivity}
import com.adcoelum.data.DatabaseAccessor
import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable.{HashMap, ListBuffer}
import scala.concurrent.duration._
import scala.language.postfixOps
import scalaj.http.{Http, HttpOptions}
import sys.process._


/**
  * Created by jeremy on 29/12/2016.
  */
class PulseCheck extends Actor {
    // Connect to the data collections
    val commandControlData = DatabaseAccessor.collection("command_control")
    val domainCollection = DatabaseAccessor.collection("domains")
    var currentVersion = (Configuration.config \ "version").as[Int]
    var latestVersion = 0

    val maximumCrawlers = (Configuration.config \ "maximumCrawlers").as[Int]
    val maximumIndexers = (Configuration.config \ "maximumIndexers").as[Int]
    val maximumDownloaders = (Configuration.config \ "maximumDownloaders").as[Int]

    val killListCrawlers = {
        val list = (Configuration.config \ "crawler" \ "killList").as[String].split(",")
        if (list(0) == "")
            List.empty[String]
        else
            list.toList
    }

    val killListIndexers = {
        val list = (Configuration.config \ "indexer" \ "killList").as[String].split(",")
        if (list(0) == "")
            List.empty[String]
        else
            list.toList
    }

    val killListDownloaders = {
        val list = (Configuration.config \ "downloader" \ "killList").as[String].split(",")
        if (list(0) == "")
            List.empty[String]
        else
            list.toList
    }

    val strayProcessList = {
        val list = (Configuration.config \ "pulse" \ "strayProcessList").as[String].split(",")
        if (list(0) == "")
            List.empty[String]
        else
            list.toList
    }

    val strayProcessListThresholds = {
        val list = (Configuration.config \ "pulse" \ "strayProcessListThresholds").as[String].split(",")
        if (list(0) == "")
            List.empty[Int]
        else {
            val thresholds: ListBuffer[Int] = ListBuffer.empty[Int]
            list.toList foreach { item =>
                thresholds += item.toInt
            }

            thresholds.toList
        }
    }

    /** PulseManager checks
      *
      * Own:
      * Crawlers port response
      * - Actions
      * Indexers port response
      * - Actions
      * Downloaders port response
      * - Actions
      *
      * Self and Others:
      * Command and Control port response
      * - Actions
      * - Before checking using a directly above/below strategy
      *
      */
    def receive = {
        case AliveCheck(commander) => {
            Logger.debug(s"Commencing Pulse check from commander ${commander}")

            latestVersion = (Configuration.getConfig \ "version").as[Int]

            if (latestVersion > currentVersion) {
                Logger.debug(s"Update config based on version change for commander ${commander}")
                currentVersion = latestVersion

                versionReset(commander)
            }

            if ((Configuration.config \ "pulse" \ "aliveCrawlerCheck").as[Boolean])
                checkCrawlers(commander)

            if ((Configuration.config \ "pulse" \ "aliveIndexerCheck").as[Boolean])
                checkIndexers(commander)

            if ((Configuration.config \ "pulse" \ "aliveDownloaderCheck").as[Boolean])
                checkDownloaders(commander)

            val otherCommanders = getOtherCommanders(commander)
            val rank = checkRank(commander, otherCommanders)

            if ((Configuration.config \ "pulse" \ "aliveSelfCheck").as[Boolean])
                checkSelf(commander)

            if ((Configuration.config \ "pulse" \ "aliveOthersCheck").as[Boolean])
                checkCommanders(commander, rank, otherCommanders)

            if ((Configuration.config \ "pulse" \ "cleanStrayProcesses").as[Boolean])
                cleanStrayProcesses(commander)

            Logger.debug(s"Completed Pulse check from commander ${commander}")
        }

        case TriggerActivity() => {
            sender ! Activity("pulse_check", 0, 0)
        }
    }

    def checkCrawlers(commander: String) = {
        Logger.debug(s"Checking pulse of crawlers of commander ${commander}")
        var crawlers: Map[String, Tuple3[Int, Int, String]] = Map.empty
        crawlersMap(commander, (result) => {crawlers = result})

        var crawlersCount = 0
        crawlers foreach { crawler =>
            crawlersCount += 1
            Logger.trace(s"Checking pulse of crawler ${crawler._1} of commander ${commander}", Logger.VERBOSE_INTENSITY)

            // Perform version adjustment if not active
            var versionKill = false
            if (latestVersion > currentVersion) {
                versionKill = true
                Logger.trace(s"Version change - crawler ${crawler._1} to be killed", Logger.VERBOSE_INTENSITY)
            }

            val connectSuccess = Tools.testConnectivity(Configuration.host, crawler._2._1, errorMessage = " - crawler connect failed")
            if (versionKill || !connectSuccess || crawlersCount > maximumCrawlers) {
                // Load data into database to remove crawler
                try {
                    // Set query information against which to set data
                    val query = Map("domain" -> crawler._2._3)
                    val values = Map("state" -> "failed")

                    // Perform the data write
                    domainCollection.update(values, query)


                    // Set query information against which to set data
                    val selector = Map("commander" -> commander)

                    // Define data to remove for tracking this searchBot command and controller
                    val data = Map(
                        "$unset" -> Map(s"crawlers.${crawler._1}" -> "")
                    )


                    // Perform the data write
                    commandControlData.update(data, selector, instructionBased = true, wait = true)
                    cleanUpActivity(s"${crawler._1}", commander)
                    Logger.info(s"Removing Command and Control data for crawler: ${crawler._1}")

                    // Kill process
                    s"kill -9 ${crawler._2._2}" !

                    // Kill listed processes
                    killListCrawlers foreach { process =>
                        if (process != "")
                            s"pkill -9 $process" !
                    }
                }
                catch {
                    case e: Throwable => Logger.warn(s"Command and Control kill crawler failed: ${e.getMessage}", error = e)
                }
            }
        }
    }

    def checkIndexers(commander: String) = {
        Logger.debug(s"Checking pulse of indexers of commander ${commander}")
        var indexers: Map[String, Tuple2[Int, Int]] = Map.empty
        indexersMap(commander, (result) => {indexers = result})

        var indexersCount = 0
        indexers foreach { indexer =>
            indexersCount += 1
            Logger.trace(s"Checking pulse of indexer ${indexer._1} of commander ${commander}", Logger.VERBOSE_INTENSITY)

            // Perform version adjustment if not active
            var versionKill = false
            if (latestVersion > currentVersion) {
                versionKill = true
                Logger.trace(s"Version change - indexer ${indexer._1} to be killed", Logger.VERBOSE_INTENSITY)
            }

            val connectSuccess = Tools.testConnectivity(Configuration.host, indexer._2._1, errorMessage = " - indexer connect failed")
            if (versionKill || !connectSuccess || indexersCount > maximumIndexers) {
                // Load data into database to remove crawler
                try {
                    // Set query information against which to set data
                    val selector = Map("commander" -> commander)

                    // Define data to remove for tracking this searchBot command and controller
                    val data = Map(
                        "$unset" -> Map(s"indexers.${indexer._1}" -> "")
                    )

                    // Perform the data write
                    commandControlData.update(data, selector, instructionBased = true)
                    cleanUpActivity(indexer._1, commander)
                    Logger.info(s"Removing Command and Control data for indexer: ${indexer._1}")

                    // Kill process
                    s"kill -9 ${indexer._2._2}" !

                    // Kill listed processes
                    killListIndexers foreach { process =>
                        if (process != "")
                            s"pkill -9 $process" !
                    }
                }
                catch {
                    case e: Throwable => Logger.warn(s"Command and Control kill indexer failed: ${e.getMessage}", error = e)
                }
            }
        }

        if (indexers.size < maximumIndexers) {
            Logger.debug("Launching an indexer due to available capacity")

            try {
                Http(s"http://${Configuration.responderAddress}:${Configuration.responderPort}/indexer")
                    .asString
            }
            catch {
                case e: SocketTimeoutException =>
                case e: Throwable => Logger.warn(s"Unable to send message to create indexer for ${commander}", error = e)
            }
        }
    }


    def checkDownloaders(commander: String) = {
        Logger.debug(s"Checking pulse of downloaders of commander ${commander}")
        var downloaders: Map[String, Tuple2[Int, Int]] = Map.empty
        downloadersMap(commander, (result) => {downloaders = result})

        var downloadersCount = 0
        downloaders foreach { downloader =>
            downloadersCount += 1
            Logger.trace(s"Checking pulse of downloader ${downloader._1} of commander ${commander}", Logger.VERBOSE_INTENSITY)

            // Perform version adjustment if not active
            var versionKill = false
            if (latestVersion > currentVersion) {
                versionKill = true
                Logger.trace(s"Version change - downloader ${downloader._1} to be killed", Logger.VERBOSE_INTENSITY)
            }

            val connectSuccess = Tools.testConnectivity(Configuration.host, downloader._2._1, errorMessage = " - downloader connect failed")
            if (versionKill || !connectSuccess || downloadersCount > maximumDownloaders) {
                // Load data into database to remove crawler
                try {
                    // Set query information against which to set data
                    val selector = Map("commander" -> commander)

                    // Define data to remove for tracking this searchBot command and controller
                    val data = Map(
                        "$unset" -> Map(s"downloaders.${downloader._1}" -> "")
                    )

                    // Perform the data write
                    commandControlData.update(data, selector, instructionBased = true)
                    cleanUpActivity(downloader._1, commander)
                    Logger.info(s"Removing Command and Control data for downloader: ${downloader._1}")

                    // Kill process
                    s"kill -9 ${downloader._2._2}" !

                    // Kill listed processes
                    killListDownloaders foreach { process =>
                        if (process != "")
                            s"pkill -9 $process" !
                    }
                }
                catch {
                    case e: Throwable => Logger.warn(s"Command and Control kill downloader failed: ${e.getMessage}", error = e)
                }
            }
        }

        if (downloaders.size < maximumDownloaders) {
            Logger.debug("Launching a downloader due to available capacity")

            try {
                Http(s"http://${Configuration.responderAddress}:${Configuration.responderPort}/downloader")
                    .asString
            }
            catch {
                case e: SocketTimeoutException =>
                case e: Throwable => Logger.warn(s"Unable to send message to create downloader for ${commander}", error = e)
            }
        }
    }


    def checkSelf(commander: String) = {
        Logger.debug(s"Checking pulse of self for commander ${commander}")

        val connectSuccess = Tools.testConnectivity(Configuration.host, Configuration.responderPort, errorMessage = " - commander connect failed")
        if (!connectSuccess) {
            // Load data into database to remove crawler
            try {
                // Set query information against which to set data
                val selector = Map("commander" -> commander)

                // Perform the data write
                commandControlData.delete(selector)
                Logger.info(s"Removing Command and Control data commander: ${commander}")

                // Kill process
                Logger.info(s"Shutting down commander and child processes in 10 seconds...")
                Tools.sleep(10 seconds)
                System.exit(1)
            }
            catch {
                case e: Throwable => Logger.warn(s"Command and Control kill commander failed: ${e.getMessage}", error = e)
            }
        }
    }
    

    def checkRank(commander: String, otherCommanders: Map[String, Tuple3[String, Int, Int]]): Int = {
        Logger.debug(s"Checking rank of commander: ${commander}")
        var rank = -10 // ignore if a total fail on obtaining rank
        commanderRank(commander, (result) => {rank = result})
        Logger.trace(s"Current rank of commander ${commander} is: ${rank}", Logger.VERBOSE_INTENSITY)

        // Handle case where no rank is set
        if (rank <= 0)
            performRanking(commander)
        else
            rank
    }

    def getOtherCommanders(commander: String): Map[String, Tuple3[String, Int, Int]] = {
        var commanders: Map[String, Tuple3[String, Int, Int]] = Map.empty
        commandersMap(commander, (results) => {commanders = results})
        commanders
    }

    def checkCommanders(commander: String, rank: Int, commanders: Map[String, Tuple3[String, Int, Int]]) = {
        Logger.debug(s"Check status of other commanders from commander ${commander}")
        var alterRank = false

        commanders foreach { otherCommander =>
            val otherCommanderID = otherCommander._1
            val otherHost = otherCommander._2._1
            val otherPort = otherCommander._2._2
            val otherRank = otherCommander._2._3

            if (otherHost == "" || otherPort == 0 || (otherHost == Configuration.responderAddress && otherPort == Configuration.responderPort)) {
                try {
                    // Set query information against which to set data
                    val selector = Map("commander" -> otherCommanderID)

                    // Perform removal of commander
                    commandControlData.delete(selector)
                    alterRank = true
                    Logger.trace(s"Removing defunct commander ${otherCommanderID} with rank ${otherRank}", Logger.VERBOSE_INTENSITY)
                }
                catch {
                    case e: Throwable => Logger.warn(s"Delete on Command and Control Data failed: ${e.getMessage}", error = e)
                }
            }
            else {
                // Perform check using above and below strategy
                if ((rank + 1) == otherRank || (rank - 1) == otherRank) {
                    Logger.trace(s"Checking status of other commander ${otherCommanderID} with rank ${otherRank} from commander ${commander}", Logger.VERBOSE_INTENSITY)
                    val connectSuccess = Tools.testConnectivity(otherHost, otherPort, errorMessage = s" - unable to connect to other commander ${otherCommanderID}")

                    if (!connectSuccess) {
                        try {
                            // Set query information against which to set data
                            val selector = Map("commander" -> otherCommanderID)

                            // Perform removal of commander
                            commandControlData.delete(selector)
                            alterRank = true
                            Logger.trace(s"Removing unresponsive commander ${otherCommanderID} with rank ${rank}", Logger.VERBOSE_INTENSITY)
                        }
                        catch {
                            case e: Throwable => Logger.warn(s"Delete on Command and Control Data failed: ${e.getMessage}", error = e)
                        }
                    }
                }
                else if (rank == otherRank) {
                    // Randomly decide the re-assigner to deal with the odd case where there are equal ranks
                    alterRank = scala.util.Random.nextBoolean
                }
            }
        }

        if (alterRank) {
            // Take a pause to allow the removal of defunct commanders to happen
            Tools.sleep(5 seconds)
            performRanking(commander)
        }
    }

    def crawlersMap(commander: String, callback: Function1[Map[String, Tuple3[Int, Int, String]], Unit]) = {
        try {
            val query = Map("commander" -> commander)
            val projection = Map("crawlers" -> 1)

            def action(results: List[Map[String, Any]]) = {
                val map: HashMap[String, Tuple3[Int, Int, String]] = HashMap.empty[String, Tuple3[Int, Int, String]]

                results foreach { result =>
                    if (result.contains("crawlers")) {
                        var name = ""
                        var domain = ""
                        var server = 0
                        var process = 0

                        result("crawlers").asInstanceOf[Map[String, Any]] foreach { element =>
                            name = element._1
                            process = element._2.asInstanceOf[Map[String, Any]].getOrElse("process", 0).asInstanceOf[Int]
                            server = element._2.asInstanceOf[Map[String, Any]].getOrElse("serverPort", 0).asInstanceOf[Int]
                            domain = element._2.asInstanceOf[Map[String, Any]].getOrElse("domain", "").asInstanceOf[String]

                            if (name != "")
                                map += (name -> (server, process, domain))
                        }
                    }
                }

                callback(map.toMap)
            }

            commandControlData.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain crawler servers map failed: ${e.getMessage}", error = e)
        }
    }

    def indexersMap(commander: String, callback: Function1[Map[String, Tuple2[Int, Int]], Unit]) = {
        try {
            val query = Map("commander" -> commander)
            val projection = Map("indexers" -> 1)

            def action(results: List[Map[String, Any]]) = {
                val map: HashMap[String, Tuple2[Int, Int]] = HashMap.empty[String, Tuple2[Int, Int]]

                results foreach { result =>
                    if (result.contains("indexers")) {
                        var name = ""
                        var server = 0
                        var process = 0

                        result("indexers").asInstanceOf[Map[String, Any]] foreach { element =>
                            name = element._1
                            process = element._2.asInstanceOf[Map[String, Any]].getOrElse("process", 0).asInstanceOf[Int]
                            server = element._2.asInstanceOf[Map[String, Any]].getOrElse("serverPort", 0).asInstanceOf[Int]

                            if (name != "")
                                map += (name -> (server, process))
                        }
                    }
                }

                callback(map.toMap)
            }

            commandControlData.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain indexer servers map failed: ${e.getMessage}", error = e)
        }
    }

    def performRanking(commander: String, avoidCommander: String = ""): Int = {
        var selfRank = -1
        var rankIncrement = 1

        var allCommanders: Map[String, Tuple3[String, Int, Int]] = Map.empty
        commandersMap(commander, (results) => {allCommanders = results}, true)

        // Change the rank of all commanders given the removal of one
        allCommanders foreach { commandersToRank =>
            if (commandersToRank._1 != avoidCommander) {
                try {
                    if (commandersToRank._1 == commander)
                        selfRank = rankIncrement

                    // Set query information against which to set data
                    val selector = Map("commander" -> commandersToRank._1)
                    val values = Map("rank" -> (rankIncrement))

                    // Perform the data write
                    commandControlData.update(values, selector, wait = true)
                    Logger.trace(s"Setting new rank for commander ${commandersToRank._1}: ${rankIncrement}", Logger.VERBOSE_INTENSITY)
                    rankIncrement += 1
                }
                catch {
                    case e: Throwable => Logger.warn(s"Write to Command and Control Data failed: ${e.getMessage}", error = e)
                }
            }
        }

        selfRank
    }

    def downloadersMap(commander: String, callback: Function1[Map[String, Tuple2[Int, Int]], Unit]) = {
        try {
            val query = Map("commander" -> commander)
            val projection = Map("downloaders" -> 1)

            def action(results: List[Map[String, Any]]) = {
                val map: HashMap[String, Tuple2[Int, Int]] = HashMap.empty[String, Tuple2[Int, Int]]

                results foreach { result =>
                    if (result.contains("downloaders")) {
                        var name = ""
                        var server = 0
                        var process = 0

                        result("downloaders").asInstanceOf[Map[String, Any]] foreach { element =>
                            name = element._1
                            process = element._2.asInstanceOf[Map[String, Any]].getOrElse("process", 0).asInstanceOf[Int]
                            server = element._2.asInstanceOf[Map[String, Any]].getOrElse("serverPort", 0).asInstanceOf[Int]

                            if (name != "")
                                map += (name -> (server, process))
                        }
                    }
                }

                callback(map.toMap)
            }

            commandControlData.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain downloader servers map failed: ${e.getMessage}", error = e)
        }
    }
    

    def commanderRank(commander: String, callback: Function1[Int, Unit]) = {
        try {
            val query = Map("commander" -> commander)
            val projection = Map("rank" -> 1)

            def action(result: Map[String, Any]) = {
                callback(result.getOrElse("rank", -1).asInstanceOf[Int])
            }

            commandControlData.readFirst(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain indexer servers map failed: ${e.getMessage}", error = e)
        }
    }


    def commandersMap(commander: String, callback: Function1[Map[String, Tuple3[String, Int, Int]], Unit], includeSelf: Boolean = false) = {
        try {
            val query = Map.empty[String, Any]
            val projection = Map("commander" -> 1, "host" -> 1, "port" -> 1, "rank" -> 1)

            def action(results: List[Map[String, Any]]) = {
                val map: HashMap[String, Tuple3[String, Int, Int]] = HashMap.empty[String, Tuple3[String, Int, Int]]

                if (results.nonEmpty) {
                    results foreach { result =>
                        val identifier = result("commander").asInstanceOf[String]

                        // Avoid including self
                        if (includeSelf || identifier != commander) {
                            val host = result.getOrElse("host", "").asInstanceOf[String]
                            val port = result.getOrElse("port", 0).asInstanceOf[Int]
                            val rank = result.getOrElse("rank", -1).asInstanceOf[Int]

                            map += (identifier -> (host, port, rank))
                        }
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

    def cleanUpActivity(appName: String, commander: String) = {
        try {
            Logger.info(s"Cleaning up activity and logging end time for app: ${appName}")
            val values: HashMap[String, Any] = HashMap.empty
            values += (s"monitors.${appName}.premature_time_end" -> System.currentTimeMillis)

            val monitorValues: HashMap[String, Any] = HashMap.empty
            values foreach { value =>
                monitorValues += (value._1 -> value._2)
            }

            // Setup data for writing
            val commandData = monitorValues.toMap

            // Set query information against which to set data
            val commandFilter: Map[String, Any] = Map("commander" -> commander)
        }
        catch {
            case e: Throwable => Logger.warn(s"Failed to clean up monitor data: ${e.getMessage}", error = e)
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

    def versionReset(commander: String) = {
        if ((Configuration.config \ "versionReset").as[Boolean]) {
            try {
                Http(s"http://${Configuration.responderAddress}:${Configuration.responderPort}/halt")
                    .asString
                Logger.warn(s"Halting commander to allow for a version reset: ${commander}", error = new Exception("Expected Halt"))
            }
            catch {
                case e: SocketTimeoutException =>
                case e: Throwable => Logger.warn(s"Unable to halt commander ${commander}", error = e)
            }
        }
        else
            Configuration.loadConfig
    }

    def cleanStrayProcesses(commander: String) = {
        var counter = 0
        strayProcessList foreach { process =>
            Logger.debug(s"Checking for stray processes with name ${process} where in excess of ${strayProcessListThresholds(counter)}")
            // List out processes for cleaning
            val result: Stream[String] = s"ps -eo comm,pid,etime" #| "sort -n -k 3" #| s"grep ${process}" lineStream_!

            if (result.length > strayProcessListThresholds(counter)) {
                var resultCounter = 0

                result foreach { resultProcess =>
                    val processInfo = resultProcess.split("\\s+")
                    resultCounter += 1

                    if (resultCounter > strayProcessListThresholds(counter)) {
                        Logger.debug(s"Removing stray process ${process} with process ID ${processInfo(1)}")
                        s"kill -9 ${processInfo(1)}" !
                    }
                }
            }

            counter += 1
        }
    }
}
