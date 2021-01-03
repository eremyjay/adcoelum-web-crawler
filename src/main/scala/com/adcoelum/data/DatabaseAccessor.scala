package com.adcoelum.data

import com.adcoelum.common.{Configuration, Logger, Tools}

import scala.language.postfixOps



object DatabaseAccessor {
    Logger.info("Establishing collection database connections")

    private val properties = new Collection
    properties.connect("properties")

    private val falseProperties = properties.copyConnection("false_properties")
    private val commandControl = falseProperties.copyConnection("command_control")
    private val domains = commandControl.copyConnection("domains")
    private val downloadQueue = commandControl.copyConnection("download_queue")
    private val activityMonitors = commandControl.copyConnection("activity")

    Logger.info("Establishing cache database connections")

    val cacheNamespace = Configuration.cacheStore("namespace").asInstanceOf[String]
    val cacheSet = Configuration.cacheStore("set").asInstanceOf[String]

    private val caching = new Cache
    caching.connect(cacheNamespace, cacheSet)

    Logger.info("Defining database indexes")

    defineDatasets
    defineIndexes

    def collection(name: String): Collection = {
        name match {
            case "command_control" => commandControl
            case "properties" => properties
            case "false_properties" => falseProperties
            case "domains" => domains
            case "download_queue" => downloadQueue
            case "activity" => activityMonitors
            case _ => throw new Exception("Unknown Collection for Database Accessor")
        }
    }

    def cache: Cache = {
        caching
    }

    def defineDatasets = {
        caching.establish
        properties.establish
        falseProperties.establish
        commandControl.establish
        domains.establish
        downloadQueue.establish
        activityMonitors.establish
    }

    def defineIndexes = {
        caching.ensureIndex("domain", 1, false)
        caching.ensureIndex("score", 2, false)
        properties.ensureIndex("address", 1, true)
        commandControl.ensureIndex("commander", 1, true)
        activityMonitors.ensureIndex("commander", 1, true)
        domains.ensureIndex("domains", 1, true)
    }
}



