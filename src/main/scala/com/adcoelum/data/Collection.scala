package com.adcoelum.data

import scala.concurrent.duration._


class Collection extends CollectionCore {
    private var collection: CollectionCore = new MongoCollection

    def connect(collectionName: String): Unit = {
        collection.connect(collectionName)   
    }

    def setConnection(connection: Map[String, Any]): Unit = {
        collection.setConnection(connection)
    }

    def getConnection[DefaultDB]: CollectionConnection[DefaultDB] = {
        collection.getConnection
    }

    def setCollection(collectionName: String): Unit = {
        collection.setCollection(collectionName)
    }

    def copyConnection(collectionName: String): Collection = {
        val copy = collection.copyConnection(collectionName)
        val newCollection = new Collection
        newCollection.collection = copy
        newCollection
    }

    def create(data: Map[String, Any], callback: (Boolean) => Any = (status) => {}, instruction: String = "$set", instructionBased: Boolean = false, wait: Boolean = defaultWriteWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries): Unit = {
        collection.create(data, callback, instruction, instructionBased, wait, waitTime, retries)
    }
    
    def update(data: Map[String, Any], filter: Map[String, Any], callback: (Boolean) => Any = (status) => {}, instruction: String = "$set", instructionBased: Boolean = false, wait: Boolean = defaultWriteWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries): Unit = {
        collection.update(data, filter, callback, instruction, instructionBased, wait, waitTime, retries)
    }

    def delete(query: Map[String, Any], callback: (Boolean) => Any = (status) => {}, wait: Boolean = defaultWriteWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries): Unit = {
        collection.delete(query, callback, wait, waitTime, retries)
    }
    
    def readFirst(query: Map[String, Any], filter: Map[String, Any], callback: (Map[String, Any]) => Any, sort: Tuple2[String, Int] = ("id", 1), wait: Boolean = defaultReadWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries): Unit = {
        collection.readFirst(query, filter, callback, sort, wait, waitTime, retries)
    }
   
    def read(query: Map[String, Any], filter: Map[String, Any], callback: (scala.List[Map[String, Any]]) => Any, sort: Tuple2[String, Int] = ("id", 1), resultCount: Int = defaultResultCount, wait: Boolean = defaultReadWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries): Unit = {
        collection.read(query, filter, callback, sort, resultCount, wait, waitTime, retries)
    }

    def random(resultCount: Int = 1, callback: Function1[List[Map[String, Any]], Any], wait: Boolean = defaultReadWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries) = {
        collection.random(resultCount, callback, wait, waitTime, retries)
    }

    def count(query: Map[String, Any], callback: (Int) => Any, limit: Int = defaultCountLimit, skip: Int = defaultCountSkip, wait: Boolean = defaultReadWait, waitTime: FiniteDuration = defaultWaitTime, retries: Int = defaultRetries): Unit = {
        collection.count(query, callback, limit, skip, wait, waitTime, retries)
    }

    def establish = {
        collection.establish
    }

    def clear = {
        collection.clear
    }

    def ensureIndex(field: String, indexType: Int, unique: Boolean = true) = {
        collection.ensureIndex(field, indexType, unique)
    }

    def checkConnectivity: Boolean = {
        collection.checkConnectivity
    }
}


