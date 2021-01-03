package com.adcoelum.data

import com.adcoelum.common.Configuration

import scala.concurrent.duration._
import scala.language.postfixOps


/**
  * Created by jeremy on 5/08/2016.
  */
trait CollectionCore {
    var defaultWaitTime: FiniteDuration = (Configuration.config \ "collections" \ "defaultWaitTime").as[Int] seconds
    var defaultConnectWait = (Configuration.config \ "collections" \ "defaultConnectTime").as[Int] seconds
    var defaultRetries = (Configuration.config \ "collections" \ "connectRetries").as[Int]
    var defaultResultCount = (Configuration.config \ "collections" \ "defaultResultCount").as[Int]
    var defaultCountLimit = (Configuration.config \ "collections" \ "defaultCountLimit").as[Int]
    var defaultCountSkip = (Configuration.config \ "collections" \ "defaultCountSkip").as[Int]
    var defaultReadWait = true
    var defaultWriteWait = false

    def connect(collectionName: String)
    def setConnection(connection: Map[String, Any])
    def getConnection[X]: CollectionConnection[X]
    def copyConnection(collectionName: String): CollectionCore
    def setCollection(collectionName: String)


    /* CRUD Ops */
    def create(data: Map[String, Any], callback: Function1[Boolean, Any],
               instruction: String, instructionBased: Boolean, wait: Boolean, waitTime: FiniteDuration, retries: Int)
    def update(data: Map[String, Any], filter: Map[String, Any], callback: Function1[Boolean, Any],
               instruction: String, instructionBased: Boolean, wait: Boolean, waitTime: FiniteDuration, retries: Int)
    def delete(query: Map[String, Any], callback: Function1[Boolean, Any], wait: Boolean, waitTime: FiniteDuration, retries: Int)

    def read(query: Map[String, Any], filter: Map[String, Any], callback: Function1[List[Map[String, Any]], Any], sort: Tuple2[String, Int], resultCount: Int, wait: Boolean, waitTime: FiniteDuration, retries: Int)

    def readFirst(query: Map[String, Any], filter: Map[String, Any], callback: Function1[Map[String, Any], Any], sort: Tuple2[String, Int], wait: Boolean, waitTime: FiniteDuration, retries: Int)

    def random(resultCount: Int = 1, callback: Function1[List[Map[String, Any]], Any], wait: Boolean, waitTime: FiniteDuration, retries: Int)

    def count(query: Map[String, Any], callback: (Int) => Any, limit: Int, skip: Int, wait: Boolean, waitTime: FiniteDuration, retries: Int)

    def establish
    def clear

    def ensureIndex(field: String, indexType: Int, unique: Boolean = true)
    def checkConnectivity: Boolean
}


class CollectionConnection[X] {
    var connection: X = _

    def get: X = {
        connection
    }

    def set(newConnection: X) = {
        connection = newConnection
    }
}
