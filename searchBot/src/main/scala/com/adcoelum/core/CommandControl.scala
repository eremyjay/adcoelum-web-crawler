package com.adcoelum.core

import java.net.SocketTimeoutException

import com.adcoelum.pulse.PulseManager
import com.adcoelum.seed.SeederManager
import java.io.File
import java.nio.file.{Files, Paths}

import com.adcoelum.common.{Configuration, HttpServer, Logger, Tools}
import com.adcoelum.data.DatabaseAccessor
import java.text.{NumberFormat, SimpleDateFormat}

import com.aerospike.client.query.RecordSet

import scala.collection.immutable.ListMap
import scala.collection.mutable.{HashMap, ListBuffer}
import scalaj.http.Http
import scala.concurrent.duration._
import scala.collection.mutable.HashMap
import scala.sys.process._
import scala.collection.JavaConverters._
import scala.language.postfixOps

/**
  * The command and control responder class is responsible for receiving instructions from a network connection,
  * spawning crawlers and indexers, handling ongoing management of all jvm instances and ensuring connectivity
  * and availability of services.  This class plays a very important infrastructure management role.
  */
class CommandControl extends HttpServer(Configuration.host, (Configuration.config \ "responder" \ "port").as[Int]) {
    // Determine identifier for responder
    val identifier: String = System.currentTimeMillis.toString

    val refresh = (Configuration.config \ "responder" \ "refresh").as[Int]

    // Connect to the data collection
    val commandControlData = DatabaseAccessor.collection("command_control")
    val activityData = DatabaseAccessor.collection("activity")

    // Determine maximums to create types and counts
    val maximumCrawlers = (Configuration.config \ "maximumCrawlers").as[Int]
    val maximumIndexers = (Configuration.config \ "maximumIndexers").as[Int]
    val maximumDownloaders = (Configuration.config \ "maximumDownloaders").as[Int]

    // Perform a clearance of the cache - this is for testing environments
    if (Configuration.clearCacheOnStart) {
        Logger.info("Clearing cache on start")
        val cache = DatabaseAccessor.cache
        cache.clear
    }

    // Perform a clearance of the download queue - this is for testing environments
    if (Configuration.clearDownloadQueueOnStart) {
        Logger.info("Clearing Download Queue collection on start")
        val collection = DatabaseAccessor.collection("download_queue")
        collection.clear
    }

    // Perform a clearance of the activity information - this is for testing environments
    if (Configuration.clearActivityOnStart) {
        Logger.info("Clearing Activity collection on start")
        val collection = DatabaseAccessor.collection("activity")
        collection.clear
    }

    // Perform a clearance of the domains collection - this is for testing environments
    if (Configuration.clearDomainCollectionOnStart) {
        Logger.info("Clearing Domains collection on start")
        val collection = DatabaseAccessor.collection("domains")
        collection.clear
    }

    // Perform a clearance of the domains collection - this is for testing environments
    if (Configuration.clearPropertiesCollectionOnStart) {
        Logger.info("Clearing Properties collection on start")
        val collection = DatabaseAccessor.collection("properties")
        collection.clear
    }


    // Create and start a pulse process to auto manage the distributed network of nodes
    val pulse = new PulseManager(identifier)

    // Create and start a seeder process to manage domains available for crawling
    val seeder = new SeederManager(identifier)


    // Set the crawler and indexer jar paths
    val crawlerPath = (Configuration.config \ "crawler" \ "jvm" \ "path").as[String]
    val crawlerJar = (Configuration.config \ "crawler" \ "jvm" \ "jar").as[String]
    val crawlerClassPath = ""
    val indexerPath = (Configuration.config \ "indexer" \ "jvm" \ "path").as[String]
    val indexerJar = (Configuration.config \ "indexer" \ "jvm" \ "jar").as[String]
    val indexerClassPath = ""
    val downloaderPath = (Configuration.config \ "downloader" \ "jvm" \ "path").as[String]
    val downloaderJar = (Configuration.config \ "downloader" \ "jvm" \ "jar").as[String]
    val downloaderClassPath = ""

    // Set the java options for the crawler
    val crawlerBaselineMemory = (Configuration.config \ "crawler" \ "jvm" \ "minMem").as[Int]
    val crawlerSpecMinMem = (Configuration.config \ "crawler" \ "jvm" \ "minMem").as[Int]
    val crawlerSpecMaxMem = (Configuration.config \ "crawler" \ "jvm" \ "maxMem").as[Int]
    val crawlerSpecMaxStack = (Configuration.config \ "crawler" \ "jvm" \ "maxStack").as[Int]
    val crawlerMemMultiplier = (Configuration.config \ "crawler" \ "jvm" \ "memMultiplier").as[Int]
    val crawlerMinMemSize = {
        if (crawlerSpecMinMem > crawlerBaselineMemory)
            crawlerSpecMinMem
        else
            crawlerBaselineMemory
    }
    val crawlerMaxMemSize = {
        val calculatedMax = crawlerBaselineMemory + Configuration.crawlerWorkerRoutees * crawlerMemMultiplier
        if (crawlerSpecMaxMem < calculatedMax)
            crawlerSpecMaxMem
        else
            calculatedMax
    }
    val crawlerJavaMinMem = "-J-Xms" + crawlerMinMemSize.toString + "M"
    val crawlerJavaMaxMem = "-J-Xmx" + crawlerMaxMemSize.toString + "M"
    val crawlerJavaMaxStack = "-J-Xss" + crawlerSpecMaxStack.toString + "K"
    val crawlerJavaOptions = (Configuration.config \ "crawler" \ "jvm" \ "options").as[String]


    // Set the java options for the indexer
    val indexerBaselineMemory = (Configuration.config \ "indexer" \ "jvm" \ "minMem").as[Int]
    val indexerSpecMinMem = (Configuration.config \ "indexer" \ "jvm" \ "minMem").as[Int]
    val indexerSpecMaxMem = (Configuration.config \ "indexer" \ "jvm" \ "maxMem").as[Int]
    val indexerSpecMaxStack = (Configuration.config \ "indexer" \ "jvm" \ "maxStack").as[Int]
    val indexerMemMultiplier = (Configuration.config \ "indexer" \ "jvm" \ "memMultiplier").as[Int]
    val indexerMinMemSize = {
        if (indexerSpecMinMem > indexerBaselineMemory)
            indexerSpecMinMem
        else
            indexerBaselineMemory
    }
    val indexerMaxMemSize = {
        val calculatedMax = indexerBaselineMemory + Configuration.indexerWorkerRoutees * indexerMemMultiplier
        if (indexerSpecMaxMem > calculatedMax)
            indexerSpecMaxMem
        else
            calculatedMax
    }
    val indexerJavaMinMem = "-J-Xms" + indexerMinMemSize.toString + "M"
    val indexerJavaMaxMem = "-J-Xmx" + indexerMaxMemSize.toString + "M"
    val indexerJavaMaxStack = "-J-Xss" + indexerSpecMaxStack.toString + "K"
    val indexerJavaOptions = (Configuration.config \ "indexer" \ "jvm" \ "options").as[String]


    // Set the java options for the downloader
    val downloaderBaselineMemory = (Configuration.config \ "downloader" \ "jvm" \ "minMem").as[Int]
    val downloaderSpecMinMem = (Configuration.config \ "downloader" \ "jvm" \ "minMem").as[Int]
    val downloaderSpecMaxMem = (Configuration.config \ "downloader" \ "jvm" \ "maxMem").as[Int]
    val downloaderSpecMaxStack = (Configuration.config \ "downloader" \ "jvm" \ "maxStack").as[Int]
    val downloaderMemMultiplier = (Configuration.config \ "downloader" \ "jvm" \ "memMultiplier").as[Int]
    val downloaderMinMemSize = {
        if (downloaderSpecMinMem > downloaderBaselineMemory)
            downloaderSpecMinMem
        else
            downloaderBaselineMemory
    }
    val downloaderMaxMemSize = {
        val calculatedMax = downloaderBaselineMemory + Configuration.downloaderWorkerRoutees * downloaderMemMultiplier
        if (downloaderSpecMaxMem > calculatedMax)
            downloaderSpecMaxMem
        else
            calculatedMax
    }
    val downloaderJavaMinMem = "-J-Xms" + downloaderMinMemSize.toString + "M"
    val downloaderJavaMaxMem = "-J-Xmx" + downloaderMaxMemSize.toString + "M"
    val downloaderJavaMaxStack = "-J-Xss" + downloaderSpecMaxStack.toString + "K"
    val downloaderJavaOptions = (Configuration.config \ "downloader" \ "jvm" \ "options").as[String]
    

    // Define the process commands for the crawler and indexer
    val cmdCrawler = "scala " + crawlerClassPath + " " + crawlerJavaOptions + " " + crawlerJavaMinMem + " " + crawlerJavaMaxMem + " " + crawlerJavaMaxStack + " " + crawlerPath + crawlerJar
    val cmdIndexer = "scala " + indexerClassPath + " " + indexerJavaOptions + " " + indexerJavaMinMem + " " + indexerJavaMaxMem + " " + indexerJavaMaxStack + " " + indexerPath + indexerJar
    val cmdDownloader = "scala " + downloaderClassPath + " " + downloaderJavaOptions + " " + downloaderJavaMinMem + " " + downloaderJavaMaxMem + " " + downloaderJavaMaxStack + " " + downloaderPath + downloaderJar

    /* Instruction set:
        CRAWLER site
        STATUS
        STOP [site]
        HALT
     */



    /**
      * The response services handle all human and other search bot's instructions and provides feedback or performs activities
      * based on the instructions provided
      *
      */
    get("/") {
        (_, _) =>
            var commandersData = List.empty[Map[String, Any]]
            var activitiesData = List.empty[Map[String, Any]]

            commanders((result) => {commandersData = result})

            val commanderList = ListBuffer.empty[String]
            commandersData foreach { commander =>
                commanderList += commander.getOrElse("commander", "").asInstanceOf[String]
            }

            activities(commanderList.toList, (result) => {activitiesData = result})

            var output = ""

            output += s"""
                         | <html>
                         |     <head>
                         |         <title>Search Bot Network - Current Status</title>
                         |         <meta http-equiv="refresh" content="${refresh}" />
                         |     </head>
                         |     <body>
                         |         <h1>Search Bot Network - Current Status</h1>""".stripMargin

            commandersData foreach { selectedCommander =>
                var matchingResult = false
                val commander = {
                    var result = Map.empty[String, Any] ++ selectedCommander

                    activitiesData foreach { activity =>
                        if (activity.getOrElse("commander", "") == selectedCommander.getOrElse("commander", "")) {
                            result = activity ++ selectedCommander
                            matchingResult = true
                        }
                    }

                    result
                }

                if (matchingResult) {
                    output += s"<h2>Commander: ${commander.getOrElse("commander", "")}</h2>"

                    output += s"<div>Host address: ${commander.getOrElse("host", "")}</div>"
                    output += s"<div>Port: ${commander.getOrElse("port", "")}</div>"
                    output += s"<div>Process: ${commander.getOrElse("process", "")}</div><br />"

                    output += s"<div><a href='http://${commander.getOrElse("host", "")}:${commander.getOrElse("port", "")}/'>Commander Responder</a></div><br />"
                    output += s"<div><a href='http://${commander.getOrElse("host", "")}:${commander.getOrElse("port", "")}/logs'>View Logs</a></div><br />"

                    commander("monitors").asInstanceOf[Map[String, Any]] foreach { app =>
                        output += s"<h3>${app._1}</h3>"

                        val appData = app._2.asInstanceOf[Map[String, Any]]

                        output += formatData(appData)
                    }
                }
            }

            """
              |     </body>
              | </html>""".stripMargin

            output
    }

    get("/loglive/:port") {
        (request, _) => {
            try {
                var output = ""

                val port = request.params(":port")

                output += "<html><head>" +
                    s"<script type='text/javascript' " +
                    s"        src='http://${Configuration.host}:${port}/logback.js'" +
                    s"        style-info='color: Green;'" +
                    s"        style-warn='color: Orange;'" +
                    s"        style-error='color: DarkRed;'" +
                    s"        style-debug='color: DarkCyan;'" +
                    s"        style='color: DarkMagenta;'" +
                    s"></script>" +
                    "</head>" +
                    "<body><h1>Live Log</h1>" +
                    "<p>View the live log by opening the console - increment the port number for other logs</p>" +
                    "</body></html>"

                output
            }
            catch {
                case e: Throwable => {
                    "<html><head></head><body>Log file cannot be found or processed</body></html>"
                }
            }
        }
    }

    get("/logs") {
        (request, _) => {
            try {
                var output = ""

                output += "<html><head></head><body><h1>Log Files</h1>"

                val fileList = Tools.getListOfFiles(Logger.logfilePath)
                fileList foreach { file =>
                    output += s"<div><a href='/logs/${file.getName.dropRight(4)}'>${file.getName.dropRight(4)}</a></div>"
                }

                output += "</body></html>"

                output
            }
            catch {
                case e: Throwable => {
                    "<html><head></head><body>Log file cannot be found or processed</body></html>"
                }
            }
        }
    }

    get("/logs/:name") {
        (request, _) => {
            try {
                var output = ""
                val logName = request.params(":name")

                val path = Logger.logfilePath + "/" + logName + ".log"
                val logFile = new File(path)

                if (logFile.exists && !logFile.isDirectory) {
                    val encoded = Files.readAllBytes(Paths.get(path))
                    output += new String(encoded)
                }

                output
            }
            catch {
                case e: Throwable => {
                    "<html><head></head><body>Log file cannot be found or processed</body></html>"
                }
            }
        }
    }

    // Handle an ADD instruction, which adds a Url to the domain list if non-existent
    get("/add/:domain") {
        (request, _) => {
            val domain = request.params(":domain")

            if (domain == "")
                s"<html><head></head><body><p>Unable to process instruction.  Not enough information.</p></body>"
            else {
                var added = false
                seeder.addDomain(domain, (result) => {
                    added = result
                })

                if (added)
                    s"<html><head></head><body><p>Added domain ${domain} to the domain list.</p></body>"
                else
                    s"<html><head></head><body><p>Domain already exists in the domain list.</p></body>"
            }
        }
    }

    // Handle a CRAWLER instruction, which deals with crawling a site
    get("/crawler/:method/:domain/:crawlid") {
        (request, _) => {
            var crawlers: Map[String, Map[String, Any]] = Map.empty

            crawlersMap((result) => {crawlers = result})

            val method = request.params(":method")
            val domain = request.params(":domain")
            val crawlId = request.params(":crawlid")

            // Ensure that a crawling process is not already in place for this search bot
            if (crawlers.keySet.contains(domain) && crawlers(domain)("serverPort").asInstanceOf[Int] > 0)
                s"<html><head></head><body><p>Already in a CRAWLER process.  Instruction ignored.</p></body></html>"
            else {
                // AliveCheck to ensure a method and url is provided and well formed
                if (method != "" && domain != "") {
                    try {
                        val url = s"http://${domain}"

                        // Spawn the crawler
                        spawnCrawler(url, method.toLowerCase, crawlId)
                        s"<html><head></head><body><p>CRAWLER instruction processed.  Currently running crawler for " + domain + "</p></body></html>\""
                    }
                    catch {
                        case e: Throwable => s"<html><head></head><body><p>AProcessing CRAWLER instruction failed: " + e.getMessage + "\n" + e.getStackTrace.toString + "</p></body></html>\""
                    }
                }
                else {
                    s"<html><head></head><body><p>CRAWLER instruction invalid.  Instruction aborted.</p></body></html>"
                }
            }
        }
    }

    // Handle a STOP instruction, which causes a crawler (or all crawlers) to stop where they are
    get("/stop") {
        (_, _) => {
            var crawlers: Map[String, Map[String, Any]] = Map.empty

            crawlersMap((result) => {crawlers = result})

            // Just kill all running crawlers where no url is provided
            crawlers.values foreach { crawler =>
                killCrawler(crawler("serverPort").asInstanceOf[Int])
            }

            s"<html><head></head><body><p>Stopped any running crawlers.</p></body></html>"
        }
    }

    // Handle a STOP instruction, which causes a crawler (or all crawlers) to stop where they are
    get("/stop/:domain") {
        (request, _) => {
            var crawlers: Map[String, Map[String, Any]] = Map.empty

            crawlersMap((result) => {crawlers = result})

            val domain = request.params(":domain")

            // Handle the case where a url is provided and ensure there actually is a crawler currently running
            if (domain != "") {
                crawlers foreach { crawler =>
                    val values = crawler._2.asInstanceOf[Map[String, Any]]
                    if (values.getOrElse("domain", "").asInstanceOf[String] == domain) {
                        // Kill the crawler
                        killCrawler(crawlers(domain)("serverPort").asInstanceOf[Int])
                    }
                }
            }

            s"<html><head></head><body><p>Stopped any running crawlers for domain ${domain}.</p></body></html>"
        }
    }

    // Obtain the status of the search bot
    get("/status") {
        (_, _) => {
            var crawlers: Map[String, Map[String, Any]] = Map.empty
            var indexers: Map[String, Map[String, Any]] = Map.empty
            var downloaders: Map[String, Map[String, Any]] = Map.empty

            crawlersMap((result) => {crawlers = result})
            indexersMap((result) => {indexers = result})
            downloadersMap((result) => {downloaders = result})

            s"""<html><head></head><body>
               |<p>Number of Crawlers Active: {${crawlers.size}}</p>
               |<p>Number of Indexers Active: {${indexers.size}}</p>
               |<p>Number of Downloaders Active: {${downloaders.size}}</p>
                    </body></html>""".stripMargin
        }
    }

    // Halt the search bot
    get("/halt") {
        (_, _) => {
            var crawlers: Map[String, Map[String, Any]] = Map.empty
            var indexers: Map[String, Map[String, Any]] = Map.empty
            var downloaders: Map[String, Map[String, Any]] = Map.empty

            val performHalt = new Thread {
                override def run {
                    halt(indexers, crawlers, downloaders)
                }
            }
            performHalt.start
            s"<html><head></head><body><p>Halting the Search Bot with identifier ${identifier}.</p></body></html>"
        }
    }

    // Halt the search bot
    get("/indexer") {
        (_, _) => {
            spawnIndexer
            s"<html><head></head><body><p>Starting a new indexer.</p></body></html>"
        }
    }

    // Halt the search bot
    get("/downloader") {
        (_, _) => {
            spawnDownloader
            s"<html><head></head><body><p>Starting a new downloader.</p></body></html>"
        }
    }

    get("/sitemap/:domain/:start/:count") {
        (request, _) => {
            try {
                val domain = request.params(":domain")
                val start = request.params(":start").toInt - 1
                val count = request.params(":count").toInt

                val pageList = DatabaseAccessor.cache.find("domain", domain, start, count)

                var output = s"<html><head><body><table>"

                pageList foreach { page =>
                    output += s"<tr><td>${page.getOrElse("key", "")}</td>"

                    page foreach { item =>
                        if (item._1 != "key")
                           output += s"<td>${item._1} -> ${item._2}</td>"
                    }

                    output += s"</tr>"
                }

                output += s"</table></body></head></html>"

                output
            }
            catch {
                case e: Exception => s"${e.getMessage}<br/>{${e.printStackTrace}"
            }
        }
    }

    get("/score") {
        (request, _) => {
            try {
                val url = {
                    val urlData = request.queryParams(":url")

                    if (!urlData.contains("?"))
                        urlData + "?"
                    else
                        urlData
                }

                val page = DatabaseAccessor.cache.get(url)

                var output = s"<html><head><body><p>Score for url ${url} currently is: ${page.getOrElse("score", "unknown")}</p></body></head></html>"

                output
            }
            catch {
                case e: Exception => s"${e.getMessage}<br/>{${e.printStackTrace}"
            }
        }
    }

    get("/list") {
        (_, _) => {
            s"""
               |<html><head></head><body>
               |<h1>Command API List</h1>
               |<p>/list - provide a list of API commands</p>
               |<p>/ - View current state of Search Bot network</p>
               |<p>/loglive - View live log from console</p>
               |<p>/logs - View logs</p>
               |<p>/logs/:name - View log with :name</p>
               |<p>/add/:domain - Add a new :domain</p>
               |<p>/crawler/:method/:domain/:crawlid - Start a new crawler running :method on :domain (internal method) with id :crawlid</p>
               |<p>/stop - Stop all crawler instances (internal method)</p>
               |<p>/stop/:domain - Stop crawler on :domain (internal method)</p>
               |<p>/status - See number of running instances for Search Bot</p>
               |<p>/halt - Stop all instances (internal method)</p>
               |<p>/indexer - Load a new indexer (internal method)</p>
               |<p>/downloader - Load a new downloader (internal method)</p>
               |<p>/sitemap/:domain/:start/:count - Provide a known sitemap for a :domain, starting at record :start (1st record = 1) for number of records :count</p>
               |<p>/score/:url - Obtain the score for a particular url - use : instead of / for path and ! instead of ? for query</p>
               |</body></html>
             """.stripMargin
        }
    }


    /**
      * Start the command and control service, spawn an indexer and start the responding server to receive
      * human or another search bot's instructions
      */
    override def start = {
        Logger.info(s"Starting Processor on port ${getPort}")
        Tools.sleep(2 seconds)

        // Define data to store for tracking this searchBot command and controller
        val data = Map("$set" -> Map(
            "host" -> Configuration.responderAddress,
            "port" -> Configuration.responderPort,
            "process" -> Configuration.processID,
            "crawlers" -> Map.empty,
            "indexers" -> Map.empty,
            "downloaders" -> Map.empty,
            "maximumCrawlers" -> maximumCrawlers,
            "maximumDownloaders" -> maximumDownloaders,
            "maximumIndexers" -> maximumIndexers,
            "pulseName" -> pulse.pulseName,
            "pulsePort" -> pulse.pulsePort,
            "seederName" -> seeder.seederName,
            "seederPort" -> seeder.seederPort
        ))

        // Update the collection store with the data
        updateCommanderData(data)

        // Start the responder service
        Logger.info(s"Processor started.  Listening on port ${getPort}")
        super.start

        pulse.start
        seeder.start
    }

    /**
      * Halt the command and control services, kill all crawlers and indexers and shutdown the server
      */
    def halt(indexers: Map[String, Map[String, Any]], crawlers: Map[String, Map[String, Any]], downloaders: Map[String, Map[String, Any]]) = {
        Logger.info("Shutting down command and control in 30 seconds...")
        Tools.sleep(10 seconds)

        seeder.stop
        pulse.stop

        // Kill all crawlers
        crawlers.values foreach { crawler =>
            killCrawler(crawler("serverPort").asInstanceOf[Int])
        }

        // Kill all indexers
        indexers.values foreach { indexer =>
            killIndexer(indexer("serverPort").asInstanceOf[Int])
        }

        // Kill all indexers
        downloaders.values foreach { downloader =>
            killDownloader(downloader("serverPort").asInstanceOf[Int])
        }

        // Stop the responder service
        stop

        Logger.info("Shutting down command and control in 20 seconds...")
        Tools.sleep(10 seconds)
        Logger.info("Shutting down command and control in 10 seconds...")
        Tools.sleep(5 seconds)
        Logger.info("Shutting down command and control in 5 seconds...")
        Tools.sleep(5 seconds)

        // Remove the command and controller from the data store
        removeCommanderData

        // Shut down the application process
        System.exit(0)
    }

    /**
      * Spawn a crawler by determining the free ports available
      *
      * @param url
      * @return
      */
    def spawnCrawler(url: String, method: String, crawlerId: String) = {
        val crawlerServerPort = Tools.findFreePort
        val crawlerSystemPort = Tools.findFreePort

        // Spawn the process
        (cmdCrawler + " " + identifier + " " + url + " " + method + " " + crawlerId + " " + crawlerServerPort + " " + crawlerSystemPort) lineStream_! ProcessLogger(line => System.out.println(line))
    }

    /**
      * Kill a particular active crawler
      *
      * @param port
      * @return
      */
    def killCrawler(port: Integer) = {
        try {
            // Provide instruction to terminate crawler
            Http(s"http://${Configuration.host}:${port}/stop")
                .asString
        }
        catch {
            case e: SocketTimeoutException =>
            case e: Throwable => Logger.warn(s"Unable to kill crawler: ${e.getMessage}", error = e)
        }
    }

    /**
      * Spawn an indexer by determining the free ports available
      *
      * @return
      */
    def spawnIndexer = {
        val indexerServerPort = Tools.findFreePort
        val indexerSystemPort = Tools.findFreePort

        // Spawn the process
        (cmdIndexer + " " + identifier + " " + indexerServerPort + " " + indexerSystemPort) lineStream_! ProcessLogger(line => System.out.println(line))
    }

    /**
      * Kill an active indexer
      *
      * @param port
      * @return
      */
    def killIndexer(port: Integer) = {
        try {
            // Provide instruction to terminate indexer
            Http(s"http://${Configuration.host}:${port}/stop")
                .asString
        }
        catch {
            case e: SocketTimeoutException =>
            case e: Throwable => Logger.warn(s"Unable to kill indexer: ${e.getMessage}", error = e)
        }
    }


    /**
      * Spawn an downloader by determining the free ports available
      *
      * @return
      */
    def spawnDownloader = {
        val downloaderServerPort = Tools.findFreePort
        val downloaderSystemPort = Tools.findFreePort

        // Spawn the process
        (cmdDownloader + " " + identifier + " " + downloaderServerPort + " " + downloaderSystemPort) lineStream_! ProcessLogger(line => System.out.println(line))
    }

    /**
      * Kill an active downloader
      *
      * @param port
      * @return
      */
    def killDownloader(port: Integer) = {
        try {
            // Provide instruction to terminate downloader
            Http(s"http://${Configuration.host}:${port}/stop")
                .asString
        }
        catch {
            case e: SocketTimeoutException =>
            case e: Throwable => Logger.warn(s"Unable to kill Downloader: ${e.getMessage}", error = e)
        }
    }


    /**
      * Update the data for tracking a particular command and control responder
      *
      * @param values
      * @return
      */
    def updateCommanderData(values: Map[String, Any]) = {
        try {
            // Set query information against which to set data
            val selector = Map("commander" -> identifier)

            // Perform the data write
            commandControlData.update(values, selector, instructionBased = true)
        }
        catch {
            case e: Throwable => Logger.warn(s"Write to Command and Control Data failed: ${e.getMessage}", error = e)
        }
    }


    /**
      *
      * @return
      */
    def removeCommanderData = {
        try {
            // Set query information against which to set data
            val selector = Map("commander" -> identifier)

            // Perform removal of commander
            commandControlData.delete(selector)
        }
        catch {
            case e: Throwable => Logger.warn(s"Delete on Command and Control Data failed: ${e.getMessage}", error = e)
        }
    }


    def formatData(appData: Map[_, _], topLevel: Boolean = true): String = {
        val numberFormatter = NumberFormat.getNumberInstance
        val percentFormatter = NumberFormat.getPercentInstance

        val sortedData = ListMap(appData.asInstanceOf[Map[String, Any]].toSeq.sortWith(_._1 < _._1):_*)

        var output = ""

        var previousKey = ""
        sortedData foreach { dataPoint =>
            val currentKey = dataPoint._1.asInstanceOf[String]
            if (topLevel && currentKey.split("_")(0) != previousKey.split("_")(0)) {
                val heading = currentKey.split("_")(0)
                output += s"<br /><div><em>${heading.capitalize}</em></div>"
            }

            output += {
                dataPoint match {
                    case (key: String, i: Integer) => {
                        if (key.contains("size"))
                            s"<div>${key.replace("_", " ").capitalize}: ${Tools.bytesString(i.toLong)}</div>"
                        else
                            s"<div>${key.replace("_", " ").capitalize}: ${numberFormatter.format(i)}</div>"
                    }
                    case (key: String, l: Long) => {
                        if (key.contains("elapsed_time")) {
                            val seconds = l / 1000 % 60
                            val minutes = l / 1000 / 60 % 60
                            val hours = l / 1000 / 60 / 60 % 24
                            val days = l / 1000 / 60 / 60 / 24
                            s"<div>${key.replace("_", " ").capitalize}: ${days} days, ${hours} hours, ${minutes} mins, ${seconds} secs</div>"
                        }
                        else if (key.contains("time_rate")) {
                            val milliseconds = l % 1000
                            val seconds = l / 1000
                            s"<div>${key.replace("_", " ").capitalize}: ${seconds} secs, ${milliseconds} millis</div>"
                        }
                        else if (key.contains("time")) {
                            val dateFormat = new SimpleDateFormat("EEE dd-MM-yyyy hh:mm(:ss) a").format(l)
                            s"<div>${key.replace("_", " ").capitalize}: ${dateFormat}</div>"
                        }
                        else if (key.contains("memory"))
                            s"<div>${key.replace("_", " ").capitalize}: ${Tools.bytesString(l)}</div>"
                        else
                            s"<div>${key.replace("_", " ").capitalize}: ${numberFormatter.format(l)}</div>"
                    }
                    case (key: String, d: Double) => {
                        if (key.contains("percent"))
                            s"<div>${key.replace("_", " ").capitalize}: ${percentFormatter.format(d)}</div>"
                        else
                            s"<div>${key.replace("_", " ").capitalize}: ${numberFormatter.format(d)}</div>"
                    }
                    case (key: String, m: Map[_, _]) => s"<div>${key.replace("_", " ").capitalize}:<br /> ${formatData(m, false)}</div>"
                    case _ => s"<div>${dataPoint._1.asInstanceOf[String].replace("_", " ").capitalize}: ${dataPoint._2}</div>"
                }
            }

            previousKey = currentKey
        }

        output
    }

    def commanders(callback: Function1[List[Map[String, Any]], Unit]) = {
        try {
            val query = Map.empty[String, Any]
            val projection = Map.empty[String, Any]

            def action(result: List[Map[String, Any]]) = {
                callback(result)
            }

            commandControlData.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain commanders map failed: ${e.getMessage}", error = e)
        }
    }

    def activities(commanderList: List[String], callback: Function1[List[Map[String, Any]], Unit]) = {
        try {
            val subQuery = ListBuffer.empty[Map[String, String]]

            commanderList foreach { commander =>
                subQuery += Map("commander" -> commander)
            }

            val query = Map("$or" -> subQuery.toList)
            val projection = Map.empty[String, Any]

            def action(result: List[Map[String, Any]]) = {
                callback(result)
            }

            activityData.read(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain activity map failed: ${e.getMessage}", error = e)
        }
    }

    def crawlersMap(callback: Function1[Map[String, Map[String, Any]], Unit]) = {
        try {
            val query = Map("commander" -> identifier)
            val projection = Map("crawlers" -> 1)

            def action(result: Map[String, Any]) = {
                val map: HashMap[String, Map[String, Any]] = HashMap.empty[String, Map[String, Any]]

                if (result.nonEmpty && result.contains("crawlers")) {
                    result("crawlers").asInstanceOf[Map[String, Any]] foreach { element =>
                        map += (element._1.asInstanceOf[String] -> element._2.asInstanceOf[Map[String, Any]])
                    }
                }

                callback(map.toMap)
            }

            commandControlData.readFirst(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain crawlers map failed: ${e.getMessage}", error = e)
        }
    }


    def indexersMap(callback: Function1[Map[String, Map[String, Any]], Unit]) = {
        try {
            val query = Map("commander" -> identifier)
            val projection = Map("indexers" -> 1)

            def action(result: Map[String, Any]) = {
                val map: HashMap[String, Map[String, Any]] = HashMap.empty[String, Map[String, Any]]

                if (result.nonEmpty && result.contains("indexers")) {
                    result("indexers").asInstanceOf[Map[String, Any]] foreach { element =>
                        map += (element._1.asInstanceOf[String] -> element._2.asInstanceOf[Map[String, Any]])
                    }
                }

                callback(map.toMap)
            }

            commandControlData.readFirst(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain indexers map failed: ${e.getMessage}", error = e)
        }
    }


    def downloadersMap(callback: Function1[Map[String, Map[String, Any]], Unit]) = {
        try {
            val query = Map("commander" -> identifier)
            val projection = Map("downloaders" -> 1)

            def action(result: Map[String, Any]) = {
                val map: HashMap[String, Map[String, Any]] = HashMap.empty[String, Map[String, Any]]

                if (result.nonEmpty && result.contains("downloaders")) {
                    result("downloaders").asInstanceOf[Map[String, Any]] foreach { element =>
                        map += (element._1.asInstanceOf[String] -> element._2.asInstanceOf[Map[String, Any]])
                    }
                }

                callback(map.toMap)
            }

            commandControlData.readFirst(query, projection, action)
        }
        catch {
            case e: Throwable => Logger.warn(s"Obtain Downloaders map failed: ${e.getMessage}", error = e)
        }
    }
}


