package com.adcoelum.common

import java.io.FileInputStream
import java.net.URL

import com.adcoelum.network.Host
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.collection.mutable.HashMap
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * The configuration object handles pull in a configuration file and setting up variables that can be used
  * across an instance for a number of needs.  This class essentially provides an ability to centralise variables
  * and give the ability to change key information at a moments notice prior to launching an instance.  Configuration
  * files are formatted using JSON
  */
object Configuration {
    // Open the JSON file
    var configFile = "./config.json" //TODO: workout how to create a variable config file name

    // Pull the data and parse it
    var config = getConfig
    Logger.info("Configuration data loading complete")

    // Obtain process id and store
    val processID = Tools.getProcessID
    val host = Host.getBestAddress((config \ "responder" \ "interface").as[String])

    // Set up the akka settings for the crawler
    val crawlerActorHost = host
    val crawlerActorConfig = s"""
        |akka.actor.provider="akka.remote.RemoteActorRefProvider"
        |akka.remote.transport="akka.remote.netty.NettyRemoteTransport"
        |akka.remote.netty.tcp.hostname="$crawlerActorHost"
        |akka.actor.default-dispatcher.type=Dispatcher
        |akka.actor.default-dispatcher.executor="${(config \ "crawler" \ "actorSystem" \ "dispatcher" \ "executor").as[String]}"
        |akka.actor.default-dispatcher.throughput=${(config \ "crawler" \ "actorSystem" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-min=${(config \ "crawler" \ "actorSystem" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-factor=${(config \ "crawler" \ "actorSystem" \ "dispatcher" \ "throughput").as[Double]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-max=${(config \ "crawler" \ "actorSystem" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-min=${(config \ "crawler" \ "actorSystem" \ "dispatcher" \ "core-pool-size-min").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-factor=${(config \ "crawler" \ "actorSystem" \ "dispatcher" \ "core-pool-size-factor").as[Double]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-max=${(config \ "crawler" \ "actorSystem" \ "dispatcher" \ "core-pool-size-max").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-min=${(config \ "crawler" \ "actorSystem" \ "dispatcher" \ "max-pool-size-min").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-factor=${(config \ "crawler" \ "actorSystem" \ "dispatcher" \ "max-pool-size-factor").as[Double]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-max=${(config \ "crawler" \ "actorSystem" \ "dispatcher" \ "max-pool-size-max").as[Int]}
        """.stripMargin


    // Set up the akka settings for the crawler
    val downloaderActorHost = host
    val downloaderActorConfig = s"""
        |akka.actor.provider="akka.remote.RemoteActorRefProvider"
        |akka.remote.transport="akka.remote.netty.NettyRemoteTransport"
        |akka.remote.netty.tcp.hostname="$downloaderActorHost"
        |akka.actor.default-dispatcher.type=Dispatcher
        |akka.actor.default-dispatcher.executor="${(config \ "downloader" \ "actorSystem" \ "dispatcher" \ "executor").as[String]}"
        |akka.actor.default-dispatcher.throughput=${(config \ "downloader" \ "actorSystem" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-min=${(config \ "downloader" \ "actorSystem" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-factor=${(config \ "downloader" \ "actorSystem" \ "dispatcher" \ "throughput").as[Double]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-max=${(config \ "downloader" \ "actorSystem" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-min=${(config \ "downloader" \ "actorSystem" \ "dispatcher" \ "core-pool-size-min").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-factor=${(config \ "downloader" \ "actorSystem" \ "dispatcher" \ "core-pool-size-factor").as[Double]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-max=${(config \ "downloader" \ "actorSystem" \ "dispatcher" \ "core-pool-size-max").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-min=${(config \ "downloader" \ "actorSystem" \ "dispatcher" \ "max-pool-size-min").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-factor=${(config \ "downloader" \ "actorSystem" \ "dispatcher" \ "max-pool-size-factor").as[Double]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-max=${(config \ "downloader" \ "actorSystem" \ "dispatcher" \ "max-pool-size-max").as[Int]}
        """.stripMargin
    

    // Set up the akka settings for the indexer
    val indexerActorHost = host
    val indexerActorConfig = s"""
        |akka.actor.provider="akka.remote.RemoteActorRefProvider"
        |akka.remote.transport="akka.remote.netty.NettyRemoteTransport"
        |akka.remote.netty.tcp.hostname="$indexerActorHost"
        |akka.actor.default-dispatcher.type=Dispatcher
        |akka.actor.default-dispatcher.executor="${(config \ "indexer" \ "actorSystem" \ "dispatcher" \ "executor").as[String]}"
        |akka.actor.default-dispatcher.throughput=${(config \ "indexer" \ "actorSystem" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-min=${(config \ "indexer" \ "actorSystem" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-factor=${(config \ "indexer" \ "actorSystem" \ "dispatcher" \ "throughput").as[Double]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-max=${(config \ "indexer" \ "actorSystem" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-min=${(config \ "indexer" \ "actorSystem" \ "dispatcher" \ "core-pool-size-min").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-factor=${(config \ "indexer" \ "actorSystem" \ "dispatcher" \ "core-pool-size-factor").as[Double]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-max=${(config \ "indexer" \ "actorSystem" \ "dispatcher" \ "core-pool-size-max").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-min=${(config \ "indexer" \ "actorSystem" \ "dispatcher" \ "max-pool-size-min").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-factor=${(config \ "indexer" \ "actorSystem" \ "dispatcher" \ "max-pool-size-factor").as[Double]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-max=${(config \ "indexer" \ "actorSystem" \ "dispatcher" \ "max-pool-size-max").as[Int]}
        """.stripMargin

    // Set up the akka settings for the indexer
    val pulseActorHost = host
    val pulseActorPort = Tools.findFreePort
    val pulseActorConfig = s"""
        |akka.actor.provider="akka.remote.RemoteActorRefProvider"
        |akka.remote.transport="akka.remote.netty.NettyRemoteTransport"
        |akka.remote.netty.tcp.hostname="$pulseActorHost"
        |akka.actor.default-dispatcher.type=Dispatcher
        |akka.actor.default-dispatcher.executor="${(config \ "pulse" \ "dispatcher" \ "executor").as[String]}"
        |akka.actor.default-dispatcher.throughput=${(config \ "pulse" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-min=${(config \ "pulse" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-factor=${(config \ "pulse" \ "dispatcher" \ "throughput").as[Double]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-max=${(config \ "pulse" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-min=${(config \ "pulse" \ "dispatcher" \ "core-pool-size-min").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-factor=${(config \ "pulse" \ "dispatcher" \ "core-pool-size-factor").as[Double]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-max=${(config \ "pulse" \ "dispatcher" \ "core-pool-size-max").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-min=${(config \ "pulse" \ "dispatcher" \ "max-pool-size-min").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-factor=${(config \ "pulse" \ "dispatcher" \ "max-pool-size-factor").as[Double]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-max=${(config \ "pulse" \ "dispatcher" \ "max-pool-size-max").as[Int]}
        """.stripMargin


    // Set up the akka settings for the indexer
    val seederActorHost = host
    val seederActorPort = Tools.findFreePort
    val seederActorConfig = s"""
        |akka.actor.provider="akka.remote.RemoteActorRefProvider"
        |akka.remote.transport="akka.remote.netty.NettyRemoteTransport"
        |akka.remote.netty.tcp.hostname="$seederActorHost"
        |akka.actor.default-dispatcher.type=Dispatcher
        |akka.actor.default-dispatcher.executor="${(config \ "seeder" \ "dispatcher" \ "executor").as[String]}"
        |akka.actor.default-dispatcher.throughput=${(config \ "seeder" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-min=${(config \ "seeder" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-factor=${(config \ "seeder" \ "dispatcher" \ "throughput").as[Double]}
        |akka.actor.default-dispatcher.fork-join-executor.parallelism-max=${(config \ "seeder" \ "dispatcher" \ "throughput").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-min=${(config \ "seeder" \ "dispatcher" \ "core-pool-size-min").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-factor=${(config \ "seeder" \ "dispatcher" \ "core-pool-size-factor").as[Double]}
        |akka.actor.default-dispatcher.thread-pool-executor.core-pool-size-max=${(config \ "seeder" \ "dispatcher" \ "core-pool-size-max").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-min=${(config \ "seeder" \ "dispatcher" \ "max-pool-size-min").as[Int]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-factor=${(config \ "seeder" \ "dispatcher" \ "max-pool-size-factor").as[Double]}
        |akka.actor.default-dispatcher.thread-pool-executor.max-pool-size-max=${(config \ "seeder" \ "dispatcher" \ "max-pool-size-max").as[Int]}
        """.stripMargin



    // Set the Collection (Document) Store Settings
    val collectionsStore: HashMap[String, Any] = new HashMap[String, Any]()
    collectionsStore += ("servers" -> (config \ "collections" \ "servers").as[List[String]])
    collectionsStore += ("dbname" -> (config \ "collections" \ "dbname").as[String])
    collectionsStore += ("username" -> (config \ "collections" \ "username").as[String])
    collectionsStore += ("password" -> (config \ "collections" \ "password").as[String])

    // Set the Cache Store Settings
    val cacheStore: HashMap[String, Any] = new HashMap[String, Any]()
    cacheStore += ("server" -> (config \ "cache" \ "server").as[String])
    cacheStore += ("port" -> (config \ "cache" \ "port").as[Int])
    cacheStore += ("namespace" -> (config \ "cache" \ "namespace").as[String])
    cacheStore += ("set" -> (config \ "cache" \ "set").as[String])

    // Set the Geocoder Settings
    val geoCoder: HashMap[String, Any] = new HashMap[String, Any]()
    geoCoder += ("photon_source" -> (config \ "geocoder" \ "photon_source").as[String])

    // Set the number of workers to be used for akka instances
    val crawlerWorkerRoutees = (config \ "crawler" \ "actorSystem" \ "workers").as[Int]
    val indexerWorkerRoutees = (config \ "indexer" \ "actorSystem" \ "workers").as[Int]
    val downloaderWorkerRoutees = (config \ "downloader" \ "actorSystem" \ "workers").as[Int]

    // Determine the NLP path
    val nlpPath = {
        val path = (config \ "nlp" \ "path").as[String]
        if (!path.startsWith("/"))
            System.getProperty("user.dir") + "/" + path + "/"
        else if (path.startsWith("./"))
            System.getProperty("user.dir") + path.drop(1) + "/"
        else
            path + "/"
    }

    private var domain = ""

    /**
      * Determine the root url for a crawl instance
      *
      * @param url
      */
    def setRootUrl(url: URL): Unit = {
        domain = url.getHost
    }

    /**
      * Obtain the root url for a crawl instance
      *
      * @return
      */
    def rootUrl = {
        domain
    }

    /**
      * Convert the url to a name for general use
      *
      * @return
      */
    def rootUrlAsName = {
        domain.replaceAll("[.]", "_")
    }

    // Crawl Methods
    val IGNORE = 0
    val BROAD_CRAWL = 1
    val FOCUS_CRAWL = 2


    val crawlFocusDepth = (config \ "crawler" \ "focusDepth").as[Int]
    val crawlBroadDepth = (Configuration.config \ "crawler" \ "broadDepth").as[Int]
    val revisitDelay = (config \ "crawler" \ "revisitDelay").as[Int] seconds
    val revisitScore = (config \ "crawler" \ "revisitScore").as[Int]

    private var applicationName = ""

    /**
      * Set the crawler name
      *
      * @param name
      */
    def setAppName(name: String): Unit = {
        applicationName = name
    }

    /**
      * Obtain the crawler name
      *
      * @return
      */
    def appName = {
        applicationName
    }

    // Determine whether to clear the Cache prior to a crawl
    val clearCacheOnStart = (config \ "cache" \ "clearCache").as[Boolean]

    // Determine whether to clear collections prior to a crawl
    val clearDomainCollectionOnStart = (config \ "collections" \ "clearDomains").as[Boolean]
    val clearPropertiesCollectionOnStart = (config \ "collections" \ "clearProperties").as[Boolean]
    val clearDownloadQueueOnStart = (config \ "collections" \ "clearDownloadQueue").as[Boolean]
    val clearActivityOnStart = (config \ "collections" \ "clearActivity").as[Boolean]

    // Determine whether to pause all browsers after obtaining and processing a page - for DEBUGGING purposes
    val pause = (config \ "downloader" \ "pause").as[Boolean]

    // Determine whether to use text compression in passing information between actor instances
    val textCompression = (config \ "text_compression").as[Boolean]

    // Determine the response server address and port
    val responderAddress = host
    val responderPort = (config \ "responder" \ "port").as[Int]


    /**
      * Initialise all system properties defined in the config file
      * These include logging settings among other things
      */
    def init() = {
        val systemProperties = (config \ "system_properties").as[JsObject]

        systemProperties.fields foreach { grouping =>
            val groupingName = grouping._1

            val properties = grouping._2.as[JsObject]

            properties.fields foreach { property =>
                val propertyName = property._1

                System.setProperty(groupingName + "." + propertyName, property._2.as[String])
            }
        }
    }

    def getConfig: JsValue = {
        val jsonStream = new FileInputStream(Configuration.configFile)

        // Pull the data and parse it
        try {  Json.parse(jsonStream) } finally { jsonStream.close }
    }

    def loadConfig = {
        config = getConfig
    }
}

 /* http://blog.sokolenko.me/2014/11/javavm-options-production.html */

