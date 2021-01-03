package com.adcoelum.data

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.runtime.universe._


/**
  * The cache trait defines the basic methods that must be implemented for a cache
  */
trait CacheCore {
    var defaultWaitTime: FiniteDuration = 5 seconds
    var defaultConnectWait = 5 seconds
    var defaultRetries = 3
    var defaultReadWait = true
    var defaultWriteWait = false

    var defaultFindStart = 0
    var defaultFindCount = 100

    def connect(cacheName: String, cacheSet: String)
    def setConnection(connection: Map[String, Any])
    def getConnection[X]: CacheConnection[X]
    def setCache(cacheName: String, cacheSet: String)

    def get[X:TypeTag](key: String, id: String): X
    def get(key: String): Map[String, Any]

    def put(key: String, id: String, value: Any): Boolean
    def put(key: String, values: Map[String, Any]): Boolean

    def find(field: String, value: String, start: Int, count: Int): List[Map[String, Any]]
    def findPointer(field: String, value: String): Any

    def delete(key: String): Boolean

    def establish
    def clear

    def ensureIndex(field: String, indexType: Int, unique: Boolean)
    def checkConnectivity: Boolean
}

class CacheConnection[X] {
    var connection: X = _

    def get: X = {
        connection
    }

    def set(newConnection: X) = {
        connection = newConnection
    }
}
