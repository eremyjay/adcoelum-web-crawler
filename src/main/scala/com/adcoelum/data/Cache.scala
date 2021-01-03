package com.adcoelum.data


import com.adcoelum.common.Configuration

import scala.reflect.runtime.universe._


class Cache extends CacheCore {
    val cache: CacheCore = new AerospikeCache

    def connect(cacheName: String, cacheSet: String): Unit = {
        cache.connect(cacheName, cacheSet)
    }

    def setConnection(connection: Map[String, Any]): Unit = {
        cache.setConnection(connection)
    }

    def setCache(cacheName: String, cacheSet: String): Unit = {
        cache.connect(cacheName, cacheSet)
    }

    def getConnection[X]: CacheConnection[X] = {
        cache.getConnection[X]
    }

    def get[X: TypeTag](key: String, id: String): X = {
        cache.get[X](key, id)
    }

    def get(key: String): Map[String, Any] = {
        cache.get(key)
    }

    def put(key: String, id: String, value: Any): Boolean = {
        cache.put(key, id, value)
    }

    def put(key: String, values: Map[String, Any]): Boolean = {
        cache.put(key, values)
    }

    def find(field: String, value: String, start: Int = defaultFindStart, count: Int = defaultFindCount): List[Map[String, Any]] = {
        cache.find(field, value, start, count)
    }

    def findPointer(field: String, value: String): Any = {
        cache.findPointer(field, value)
    }

    def delete(key: String): Boolean = {
        cache.delete(key)
    }

    def establish = {
        cache.establish
    }

    def clear = {
        cache.clear
    }

    def ensureIndex(field: String, indexType: Int, unique: Boolean = true) = {
        cache.ensureIndex(field, indexType, unique)
    }

    def checkConnectivity: Boolean = {
        cache.checkConnectivity
    }
}
